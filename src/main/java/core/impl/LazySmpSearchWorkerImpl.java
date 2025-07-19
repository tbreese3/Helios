// C:\dev\Helios\src\main\java\core\impl\LazySmpSearchWorkerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static core.constants.CoreConstants.*;
import static core.contracts.PositionFactory.HASH;
import static core.contracts.PositionFactory.META;

public final class LazySmpSearchWorkerImpl implements Runnable, SearchWorker {
    private final LazySmpWorkerPoolImpl pool;
    final boolean isMainThread;

    /* ── threading primitives ─────────── */
    private final Lock mutex = new ReentrantLock();
    private final Condition startCondition = mutex.newCondition();
    private final Condition finishedCondition = mutex.newCondition();
    private volatile boolean searching = false;
    private volatile boolean quit = false;

    /* ── per-search state ────────── */
    private long[] rootBoard;
    private SearchSpec spec;
    private PositionFactory pf;
    private MoveGenerator mg;
    private Evaluator eval;
    private TimeManager tm;
    private InfoHandler ih;
    private TranspositionTable tt;
    private MoveOrderer moveOrderer;

    private int lastScore;
    private boolean mateScore;
    private long elapsedMs;
    private int completedDepth;
    private long nodes;
    private int bestMove;
    private int ponderMove;
    private List<Integer> pv = new ArrayList<>();
    private List<Long> gameHistory;
    private final long[] searchPathHistory = new long[MAX_PLY + 2];

    /* ── Multi-PV Root Move Data ────────── */
    private static class RootMove implements Comparable<RootMove> {
        final int move;
        int score = 0;
        int prevScore = 0;
        long nodes = 0;
        List<Integer> pv = new ArrayList<>();

        RootMove(int move) { this.move = move; }

        @Override
        public int compareTo(RootMove other) {
            // Sort descending by score
            return Integer.compare(other.score, this.score);
        }
    }
    private final List<RootMove> rootMoves = new ArrayList<>();

    /* ── Heuristics for Time Management ── */
    private int stability;
    private int lastBestMove;
    private final List<Integer> searchScores = new ArrayList<>();
    private final int[][] killers = new int[MAX_PLY + 2][2];

    /* ── History Heuristic ────────── */
    private final int[][] history = new int[64][64];  // from-to scores for quiet moves

    /* ── scratch buffers ─────────────── */
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private final int[][] moves = new int[MAX_PLY + 2][256];
    private static final int LIST_CAP = 256;

    private static final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int len;

        void set(int[] childPv, int childLen, int move) {
            pv[0] = move;
            if (childLen > 0) {
                System.arraycopy(childPv, 0, pv, 1, childLen);
            }
            len = childLen + 1;
        }
    }

    public LazySmpSearchWorkerImpl(boolean isMainThread, LazySmpWorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        for (int i = 0; i < frames.length; ++i) {
            frames[i] = new SearchFrame();
        }
    }

    @Override
    public void run() {
        idleLoop();
    }

    private void idleLoop() {
        while (true) {
            mutex.lock();
            try {
                searching = false;
                finishedCondition.signal(); // Signal that we are done

                while (!searching && !quit) {
                    try {
                        startCondition.await();
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }

                if (quit) {
                    return;
                }
            } finally {
                mutex.unlock();
            }

            if (isMainThread) {
                mainThreadSearch();
            } else {
                search();
            }
        }
    }

    private void mainThreadSearch() {
        tt.incrementAge();
        pool.startHelpers();
        search();
        pool.waitForHelpersFinished();
        pool.finalizeSearch(getSearchResult());
    }

    private void search() {
        // 1. INITIALIZATION
        // ===============================================
        this.nodes = 0;
        this.completedDepth = 0;
        this.elapsedMs = 0;
        this.stability = 0;
        this.lastBestMove = 0;
        this.searchScores.clear();
        this.bestMove = 0;

        for (int[] k : killers) Arrays.fill(k, 0);
        for (int[] row : history) Arrays.fill(row, 0);
        this.moveOrderer = new MoveOrdererImpl(history);

        long searchStartMs = pool.getSearchStartTime();
        int maxDepth = spec.depth() > 0 ? spec.depth() : CoreConstants.MAX_PLY;
        int multiPV = isMainThread ? spec.multiPV() : 1;

        // Generate and initialize root moves
        rootMoves.clear();
        int[] moveList = moves[0];
        int nMoves = mg.generateCaptures(rootBoard, moveList, 0);
        nMoves = mg.generateQuiets(rootBoard, moveList, nMoves);
        if (nMoves == 0) return;
        for (int i = 0; i < nMoves; i++) {
            rootMoves.add(new RootMove(moveList[i]));
        }
        this.bestMove = rootMoves.get(0).move;


        // 2. ITERATIVE DEEPENING LOOP
        // ===============================================
        for (int rootDepth = 1; rootDepth <= maxDepth; ++rootDepth) {
            if (pool.isStopped()) break;

            // Heuristic: If there's only one move, don't think for too long.
            if (spec.moveTimeMs() == 0 && rootMoves.size() == 1 && (System.currentTimeMillis() - searchStartMs) >= 200) {
                break;
            }

            // Copy scores from last iteration and sort to establish candidate order
            for (RootMove rm : rootMoves) {
                rm.prevScore = rm.score;
            }
            Collections.sort(rootMoves);

            int bestPrevScore = rootMoves.isEmpty() ? 0 : rootMoves.get(0).prevScore;
            int initialWindow = ASP_WINDOW_INITIAL_DELTA + (bestPrevScore * bestPrevScore / ASP_WINDOW_SCORE_DIVISOR);

            // 3. MULTI-PV LOOP (research top candidates)
            // ===============================================
            for (int pvIdx = 0; pvIdx < Math.min(multiPV, rootMoves.size()); pvIdx++) {
                RootMove currentMove = rootMoves.get(pvIdx);

                int alpha = -SCORE_INF;
                int beta = SCORE_INF;
                int window = initialWindow;

                if (rootDepth >= ASP_WINDOW_START_DEPTH) {
                    alpha = Math.max(-SCORE_INF, currentMove.prevScore - window);
                    beta = Math.min(SCORE_INF, currentMove.prevScore + window);
                }

                int failHighCount = 0;

                // Aspiration search loop for the current root move
                while (true) {
                    if (pool.isStopped()) break;

                    int adjustedDepth = Math.max(1, rootDepth - failHighCount);
                    long nodesBefore = this.nodes;

                    pf.makeMoveInPlace(rootBoard, currentMove.move, mg);
                    int score = -pvs(rootBoard, adjustedDepth - 1, -beta, -alpha, 1);
                    pf.undoMoveInPlace(rootBoard);

                    if (pool.isStopped()) break; // Check again after the long search call

                    // Fail low/high/success logic
                    if (score <= alpha) {
                        beta = (alpha + beta) / 2;
                        alpha = Math.max(-SCORE_INF, score - window);
                        failHighCount = 0;
                    } else if (score >= beta) {
                        beta = Math.min(SCORE_INF, score + window);
                        if (score < 2000) failHighCount++;
                    } else {
                        // Success: Update the move's data
                        currentMove.score = score;
                        currentMove.nodes = this.nodes - nodesBefore;
                        currentMove.pv.clear();
                        currentMove.pv.add(currentMove.move);
                        for (int i = 0; i < frames[1].len; i++) {
                            currentMove.pv.add(frames[1].pv[i]);
                        }
                        break; // Exit aspiration loop
                    }
                    window += window / 3; // Widen window on any failure
                }

                if (pool.isStopped()) break;

                // Re-sort the entire list after one move is fully searched.
                // This is the crucial `sortRootMoves(0)` from the reference.
                Collections.sort(rootMoves);
            }

            if (pool.isStopped()) break;

            // 4. POST-DEPTH PROCESSING
            // ===============================================
            completedDepth = rootDepth;
            elapsedMs = System.currentTimeMillis() - searchStartMs;

            // Update final results for this depth
            RootMove bestRootMove = rootMoves.get(0);
            this.bestMove = bestRootMove.move;
            this.ponderMove = bestRootMove.pv.size() > 1 ? bestRootMove.pv.get(1) : 0;
            this.pv = bestRootMove.pv;
            this.lastScore = bestRootMove.score;
            this.mateScore = Math.abs(this.lastScore) >= SCORE_MATE_IN_MAX_PLY;

            // Report PV info
            if (isMainThread && ih != null) {
                long totalNodes = pool.totalNodes();
                long nps = elapsedMs > 0 ? (totalNodes * 1000) / elapsedMs : 0;
                for (int i = 0; i < Math.min(multiPV, rootMoves.size()); i++) {
                    RootMove rm = rootMoves.get(i);
                    boolean isMate = Math.abs(rm.score) >= SCORE_MATE_IN_MAX_PLY;
                    ih.onInfo(new SearchInfo(rootDepth, completedDepth, i + 1, rm.score, isMate, totalNodes,
                            nps, elapsedMs, rm.pv, tt.hashfull(), 0));
                }
            }

            // Check time management
            if (isMainThread) {
                if (mateScore || softTimeUp(searchStartMs, pool.getSoftMs())) {
                    pool.stopSearch();
                }
            }

            // Update state for the next depth's TM and stability checks
            if (this.bestMove == this.lastBestMove) {
                this.stability++;
            } else {
                this.stability = 0;
            }
            this.lastBestMove = this.bestMove;
            this.searchScores.add(this.lastScore);

        } // End of iterative deepening loop

        // Final result is already stored in member variables from the last completed depth
    }

    private boolean softTimeUp(long searchStartMs, long softTimeLimit) {
        if (softTimeLimit >= Long.MAX_VALUE / 2) {
            return false; // Infinite time, never stop.
        }

        long currentElapsed = System.currentTimeMillis() - searchStartMs;

        // Always obey the hard time limit.
        if (currentElapsed >= pool.getMaximumMs()) {
            return true;
        }

        // Before heuristics kick in, we must respect the soft limit.
        if (completedDepth < CoreConstants.TM_HEURISTICS_MIN_DEPTH) {
            return currentElapsed >= softTimeLimit;
        }

        if (rootMoves.isEmpty()) return true;

        // --- Use existing instability-based time extension ---
        double instability = 0.0;

        int currentBestMove = rootMoves.get(0).move;
        int currentScore = rootMoves.get(0).score;

        if (currentBestMove != lastBestMove) {
            instability += CoreConstants.TM_INSTABILITY_PV_CHANGE_BONUS;
        }

        if (searchScores.size() >= 1) {
            int prevScore = searchScores.get(searchScores.size() - 1);
            int scoreDifference = Math.abs(currentScore - prevScore);
            instability += scoreDifference * CoreConstants.TM_INSTABILITY_SCORE_WEIGHT;
        }

        double extensionFactor = 1.0 + instability;
        extensionFactor = Math.min(extensionFactor, CoreConstants.TM_MAX_EXTENSION_FACTOR);
        long extendedSoftTime = (long)(softTimeLimit * extensionFactor);

        return currentElapsed >= extendedSoftTime;
    }

    private int pvs(long[] bb, int depth, int alpha, int beta, int ply) {
        frames[ply].len = 0;
        searchPathHistory[ply] = bb[HASH];

        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return SCORE_DRAW;
        }

        if (depth <= 0) return quiescence(bb, alpha, beta, ply);

        nodes++;
        if ((nodes & 2047) == 0) {
            if (pool.isStopped() || (isMainThread && pool.shouldStop(pool.getSearchStartTime(), false))) {
                pool.stopSearch();
                return 0;
            }
        }
        if (ply >= MAX_PLY) return eval.evaluate(bb);

        boolean isPvNode = (beta - alpha) > 1;
        long key = pf.zobrist(bb);

        int ttIndex = tt.probe(key);
        if (tt.wasHit(ttIndex, key) && tt.getDepth(ttIndex) >= depth && ply > 0 && !isPvNode) {
            int score = tt.getScore(ttIndex, ply);
            int flag = tt.getBound(ttIndex);
            if (flag == TranspositionTable.FLAG_EXACT ||
                    (flag == TranspositionTable.FLAG_LOWER && score >= beta) ||
                    (flag == TranspositionTable.FLAG_UPPER && score <= alpha)) {
                return score; // TT Hit
            }
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        if (inCheck) depth++;

        if (!inCheck && depth >= 3 && !isPvNode && ply > 0 && pf.hasNonPawnMaterial(bb)) {    // Conditions to apply null move
            // Make null move
            long oldMeta = bb[META];
            bb[META] ^= PositionFactory.STM_MASK;
            bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

            int nullScore = -pvs(bb, depth - 1 - 2, -beta, -beta + 1, ply + 1);

            // Undo null move
            bb[META] = oldMeta;
            bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

            if (nullScore >= beta) {
                return beta;
            }
        }

        int[] list = moves[ply];
        int nMoves;
        int capturesEnd;

        if (inCheck) {
            nMoves = mg.generateEvasions(bb, list, 0);
            capturesEnd = nMoves;
        } else {
            capturesEnd = mg.generateCaptures(bb, list, 0);
            nMoves = mg.generateQuiets(bb, list, capturesEnd);
        }

        if (nMoves == 0) return inCheck ? -(SCORE_MATE_IN_MAX_PLY - ply) : SCORE_STALEMATE;

        int ttMove = 0;
        if (tt.wasHit(ttIndex, key)) {
            ttMove = tt.getMove(ttIndex);
        }

        moveOrderer.orderMoves(bb, list, nMoves, ttMove, killers[ply]);

        int bestScore = -SCORE_INF;
        int localBestMove = 0;
        int originalAlpha = alpha;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];

            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            boolean isCapture = (i < capturesEnd);
            boolean isPromotion = ((mv >>> 14) & 0x3) == 1;
            boolean isTactical = isCapture || isPromotion;

            int score;
            if (i == 0) {
                score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
            } else {
                int lmrDepth = depth - 1;

                if (depth >=LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !isTactical && !inCheck) {
                    int reduction = (int) (0.75 + Math.log(depth) * Math.log(i) / 2.0);
                    lmrDepth -= Math.max(0, reduction);
                }

                score = -pvs(bb, lmrDepth, -alpha - 1, -alpha, ply + 1);

                if (score > alpha && lmrDepth < depth - 1) {
                    score = -pvs(bb, depth - 1, -alpha - 1, -alpha, ply + 1);
                }
                if (score > alpha && isPvNode) {
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
                }
            }

            pf.undoMoveInPlace(bb);
            if (pool.isStopped()) return 0;

            if (score > bestScore) {
                bestScore = score;
                localBestMove = mv;
                if (score > alpha) {
                    alpha = score;
                    if (isPvNode) {
                        frames[ply].set(frames[ply + 1].pv, frames[ply + 1].len, mv);
                    }
                    if (score >= beta) {
                        if (!isTactical) {
                            int from = (mv >>> 6) & 0x3F;
                            int to = mv & 0x3F;
                            history[from][to] += depth * depth;

                            if (killers[ply][0] != mv) {
                                killers[ply][1] = killers[ply][0];
                                killers[ply][0] = mv;
                            }
                        }
                        break;
                    }
                }
            }
        }

        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestScore > originalAlpha) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        tt.store(ttIndex, key, flag, depth, localBestMove, bestScore, SCORE_NONE, isPvNode, ply);

        return bestScore;
    }

    private int quiescence(long[] bb, int alpha, int beta, int ply) {
        searchPathHistory[ply] = bb[HASH];
        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return SCORE_DRAW;
        }

        if ((nodes & 2047) == 0) {
            if (pool.isStopped()) {
                return 0;
            }
        }

        if (ply >= MAX_PLY) return eval.evaluate(bb);

        nodes++;

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));

        // Logic Path 1: The king is in check.
        if (inCheck) {
            int[] list = moves[ply];
            int nMoves = mg.generateEvasions(bb, list, 0);

            // If no legal evasions, it's checkmate.
            if (nMoves == 0) {
                return -(SCORE_MATE_IN_MAX_PLY - ply);
            }

            // The concept of "stand-pat" is invalid in check. Start with the worst possible score.
            int bestScore = -SCORE_INF;

            // Order evasions to search the best ones first.
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

                int score = -quiescence(bb, -beta, -alpha, ply + 1);
                pf.undoMoveInPlace(bb);

                if (pool.isStopped()) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    if (score >= beta) return beta; // Fail-high (cutoff)
                    if (score > alpha) alpha = score;
                }
            }
            return bestScore;

        }
        // Logic Path 2: The king is NOT in check.
        else {
            // Use stand-pat evaluation as a baseline.
            int standPat = eval.evaluate(bb);
            if (standPat >= beta) return beta; // Prune if the static eval is already too high.
            if (standPat > alpha) alpha = standPat;

            // Delta Pruning: if stand-pat is much worse than alpha, assume no capture will help.
            final int futilityMargin = 900; // A queen's value
            if (standPat < alpha - futilityMargin) {
                return alpha;
            }

            // Generate and search only captures.
            int[] list = moves[ply];
            int nMoves = mg.generateCaptures(bb, list, 0);

            // Prune losing captures with SEE and order the rest.
            nMoves = moveOrderer.seePrune(bb, list, nMoves);
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            int bestScore = standPat;

            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

                int score = -quiescence(bb, -beta, -alpha, ply + 1);
                pf.undoMoveInPlace(bb);

                if (pool.isStopped()) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    if (score >= beta) return beta; // Fail-high (cutoff)
                    if (score > alpha) alpha = score;
                }
            }
            return bestScore;
        }
    }

    private boolean isRepetitionDraw(long[] bb, int ply) {
        final long currentHash = bb[HASH];
        final int halfmoveClock = (int) PositionFactory.halfClock(bb[META]);

        for (int i = 2; i <= halfmoveClock; i += 2) {
            int prevPly = ply - i;
            long previousHash;
            if (prevPly < 0) {
                int gameHistoryIdx = gameHistory.size() + prevPly;
                if (gameHistoryIdx >= 0) {
                    previousHash = gameHistory.get(gameHistoryIdx);
                } else {
                    break;
                }
            } else {
                previousHash = searchPathHistory[prevPly];
            }

            if (previousHash == currentHash) {
                return true; // Repetition found
            }
        }
        return false;
    }

    @Override
    public void prepareForSearch(long[] root, SearchSpec s, PositionFactory p, MoveGenerator m, Evaluator e, TranspositionTable t, TimeManager timeMgr) {
        this.rootBoard = root.clone();
        this.spec = s;
        this.pf = p;
        this.mg = m;
        this.eval = e;
        this.tt = t;
        this.tm = timeMgr;
        this.gameHistory = s.history();
    }

    public void startWorkerSearch() {
        mutex.lock();
        try {
            searching = true;
            startCondition.signal();
        } finally {
            mutex.unlock();
        }
    }

    public void waitWorkerFinished() {
        mutex.lock();
        try {
            while (searching) {
                try {
                    finishedCondition.await();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        } finally {
            mutex.unlock();
        }
    }

    @Override public void setInfoHandler(InfoHandler handler) { this.ih = handler; }
    @Override public SearchResult getSearchResult() {
        return new SearchResult(bestMove, ponderMove, pv, lastScore, mateScore, completedDepth, nodes, elapsedMs);
    }
    @Override public long getNodes() { return nodes; }
    @Override public void terminate() {
        mutex.lock();
        try {
            quit = true;
            searching = true;
            startCondition.signal();
        } finally {
            mutex.unlock();
        }
    }
    @Override public void join() throws InterruptedException { /* Handled by pool */ }
}