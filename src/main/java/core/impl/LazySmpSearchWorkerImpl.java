// File: LazySmpSearchWorkerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.*;
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

    /* ── Heuristics for Time Management ── */
    private int stability;
    private int lastBestMove;
    private final List<Integer> searchScores = new ArrayList<>();
    private final long[][] nodeTable = new long[64][64];
    private final int[][] killers = new int[MAX_PLY + 2][2];

    /* ── History Heuristic ────────── */
    private final int[][] history = new int[64][64];  // from-to scores for quiet moves

    /* ── scratch buffers ─────────────── */
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private final int[][] moves = new int[MAX_PLY + 2][256];
    private static final int LIST_CAP = 256;
    private List<RootMove> rootMoves = new ArrayList<>();
    private int multiPV = 1;

    private static class RootMove implements Comparable<RootMove> {
        final int move;
        int score;
        List<Integer> pv;

        RootMove(int move) {
            this.move = move;
            this.score = -SCORE_INF; // Initialize with a very low score
            this.pv = new ArrayList<>();
        }

        /**
         * Sorts moves in descending order of score.
         */
        @Override
        public int compareTo(RootMove other) {
            return Integer.compare(other.score, this.score);
        }
    }

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

    private void sortRootMoves(int startIndex) {
        // This replicates the selection sort from the C++ code.
        for (int i = startIndex; i < rootMoves.size(); i++) {
            int best = i;
            for (int j = i + 1; j < rootMoves.size(); j++) {
                if (rootMoves.get(j).score > rootMoves.get(best).score) {
                    best = j;
                }
            }
            if (best != i) {
                Collections.swap(rootMoves, i, best);
            }
        }
    }

    private void updateRootMove(int move, int score, SearchFrame childFrame) {
        for (RootMove rm : this.rootMoves) {
            if (rm.move == move) {
                rm.score = score;
                rm.pv.clear();
                rm.pv.add(move);
                for (int i = 0; i < childFrame.len; i++) {
                    rm.pv.add(childFrame.pv[i]);
                }
                return;
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
        // Reset counters and heuristics
        this.nodes = 0;
        this.completedDepth = 0;
        this.lastScore = 0;
        this.mateScore = false;
        this.elapsedMs = 0;
        this.pv.clear();
        this.stability = 0;
        this.lastBestMove = 0;
        this.searchScores.clear();
        this.bestMove = 0;
        for (long[] row : this.nodeTable) Arrays.fill(row, 0);
        for (int[] k : killers) Arrays.fill(k, 0);
        for (int[] row : history) Arrays.fill(row, 0);
        this.moveOrderer = new MoveOrdererImpl(history);

        // Setup for MultiPV search
        this.multiPV =  1;

        this.rootMoves.clear();

        // Populate initial list of legal root moves
        int[] pseudoLegalMoves = new int[256];
        int numPseudoLegal = mg.generateCaptures(rootBoard, pseudoLegalMoves, 0);
        numPseudoLegal = mg.generateQuiets(rootBoard, pseudoLegalMoves, numPseudoLegal);
        for (int i = 0; i < numPseudoLegal; i++) {
            long[] tempBoard = rootBoard.clone();
            if (pf.makeMoveInPlace(tempBoard, pseudoLegalMoves[i], mg)) {
                rootMoves.add(new RootMove(pseudoLegalMoves[i]));
                pf.undoMoveInPlace(tempBoard);
            }
        }

        if (rootMoves.isEmpty()) {
            pool.stopSearch();
            this.bestMove = 0;
            this.ponderMove = 0;
            return;
        }
        this.multiPV = Math.min(this.multiPV, rootMoves.size());

        long searchStartMs = pool.getSearchStartTime();
        int maxDepth = spec.depth() > 0 ? spec.depth() : CoreConstants.MAX_PLY;

        try {
            // Iterative Deepening Loop
            for (int depth = 1; depth <= maxDepth; ++depth) {
                if (pool.isStopped()) break;

                Set<Integer> excludedMoves = new HashSet<>();

                for (int pvIdx = 0; pvIdx < this.multiPV; pvIdx++) {
                    if (pool.isStopped()) break;

                    RootMove candidateMove = rootMoves.get(pvIdx);
                    int aspirationScore = candidateMove.score;
                    if (depth < ASP_WINDOW_START_DEPTH || aspirationScore == -SCORE_INF) {
                        aspirationScore = lastScore;
                    }

                    int delta = ASP_WINDOW_INITIAL_DELTA;
                    int alpha = aspirationScore - delta;
                    int beta = aspirationScore + delta;

                    int bestScoreInSearch;

                    // *******************************************************************
                    // ** START: CORRECTED ASPIRATION WINDOW LOGIC                      **
                    // *******************************************************************
                    while (true) {
                        bestScoreInSearch = pvs(rootBoard, depth, alpha, beta, 0, excludedMoves);
                        sortRootMoves(pvIdx);
                        if (pool.isStopped()) break;

                        if (bestScoreInSearch <= alpha) { // Fail Low
                            // The score is lower than we expected. Widen the window downwards.
                            beta = alpha; // The old floor is the new ceiling.
                            alpha = bestScoreInSearch - delta;
                        } else if (bestScoreInSearch >= beta) { // Fail High
                            // The score is higher than we expected. Widen the window upwards.
                            alpha = beta; // The old ceiling is the new floor.
                            beta = bestScoreInSearch + delta;
                        } else { // Success!
                            break;
                        }

                        delta += delta / 2; // Increase window size for the next attempt
                        // Clamp to prevent overflow or invalid windows
                        alpha = Math.max(alpha, -SCORE_INF);
                        beta = Math.min(beta, SCORE_INF);
                    }
                    // *******************************************************************
                    // ** END: CORRECTED ASPIRATION WINDOW LOGIC                        **
                    // *******************************************************************

                    if (pool.isStopped()) break;
                    excludedMoves.add(rootMoves.get(pvIdx).move);
                }
                if (pool.isStopped()) break;

                sortRootMoves(0);
                this.completedDepth = depth;

                if (isMainThread) {
                    long totalNodes = pool.totalNodes();
                    long currentElapsed = System.currentTimeMillis() - searchStartMs;
                    long nps = currentElapsed > 0 ? (totalNodes * 1000) / currentElapsed : 0;
                    for (int i = 0; i < this.multiPV && i < this.rootMoves.size(); i++) {
                        RootMove rm = this.rootMoves.get(i);
                        boolean isMate = Math.abs(rm.score) >= SCORE_MATE_IN_MAX_PLY;
                        ih.onInfo(new SearchInfo(
                                depth, completedDepth, i + 1, rm.score, isMate, totalNodes,
                                nps, currentElapsed, rm.pv, tt.hashfull(), 0));
                    }
                }

                if (isMainThread && !rootMoves.isEmpty() && (rootMoves.get(0).score >= SCORE_MATE_IN_MAX_PLY || softTimeUp(searchStartMs, pool.getSoftMs()))) {
                    pool.stopSearch();
                }
            }
        } finally {
            if (!rootMoves.isEmpty()) {
                sortRootMoves(0);
                RootMove topPv = this.rootMoves.get(0);
                this.bestMove = topPv.move;
                this.ponderMove = topPv.pv.size() > 1 ? topPv.pv.get(1) : 0;
                this.pv = topPv.pv;
                this.lastScore = topPv.score;
                this.mateScore = Math.abs(topPv.score) >= SCORE_MATE_IN_MAX_PLY;
                this.searchScores.add(lastScore);
                this.elapsedMs = System.currentTimeMillis() - searchStartMs;
            }
        }
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

        // --- High-Fidelity Instability-Based Time Extension ---
        double instability = 0.0;

        // Heuristic 1: PV (Best Move) Change
        // If the best move is different from the last iteration, it's a major sign of
        // instability. We add a large flat bonus to our instability metric.
        if (bestMove != lastBestMove) {
            instability += CoreConstants.TM_INSTABILITY_PV_CHANGE_BONUS;
        }

        // Heuristic 2: Score Instability
        // We measure the difference in evaluation between this depth and the previous one.
        // Large swings indicate a volatile position that needs more thought.
        if (searchScores.size() >= 2) {
            int prevScore = searchScores.get(searchScores.size() - 2);
            int scoreDifference = Math.abs(lastScore - prevScore);
            instability += scoreDifference * CoreConstants.TM_INSTABILITY_SCORE_WEIGHT;
        }

        // The final extension factor is 1.0 plus our calculated instability metric.
        double extensionFactor = 1.0 + instability;

        // Apply the absolute maximum extension cap as a final safety measure.
        extensionFactor = Math.min(extensionFactor, CoreConstants.TM_MAX_EXTENSION_FACTOR);

        long extendedSoftTime = (long)(softTimeLimit * extensionFactor);

        return currentElapsed >= extendedSoftTime;
    }


    /**
     * Principal Variation Search.
     * This version has corrected re-search logic to ensure PV construction.
     */
    private int pvs(long[] bb, int depth, int alpha, int beta, int ply, Set<Integer> excludedMoves) {
        frames[ply].len = 0;
        searchPathHistory[ply] = bb[HASH];

        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return SCORE_DRAW;
        }

        if (depth <= 0) return quiescence(bb, alpha, beta, ply);

        if (ply > 0) {
            nodes++;
            if ((nodes & 2047) == 0) {
                if (pool.isStopped() || (isMainThread && pool.shouldStop(pool.getSearchStartTime(), false))) {
                    pool.stopSearch();
                    return 0;
                }
            }
            if (ply >= MAX_PLY) return eval.evaluate(bb);
        }

        alpha = Math.max(alpha, -(SCORE_MATE - ply));
        beta = Math.min(beta, SCORE_MATE - (ply + 1));
        if (alpha >= beta) return alpha;

        boolean isPvNode = (beta - alpha) > 1;
        long key = pf.zobrist(bb);

        int ttIndex = tt.probe(key);
        if (tt.wasHit(ttIndex, key) && tt.getDepth(ttIndex) >= depth && ply > 0 && !isPvNode) {
            int score = tt.getScore(ttIndex, ply);
            int flag = tt.getBound(ttIndex);
            if (flag == TranspositionTable.FLAG_EXACT ||
                    (flag == TranspositionTable.FLAG_LOWER && score >= beta) ||
                    (flag == TranspositionTable.FLAG_UPPER && score <= alpha)) {
                return score;
            }
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        if (inCheck) depth++;

        if (!inCheck && depth >= 3 && !isPvNode && ply > 0 && pf.hasNonPawnMaterial(bb)) {
            long oldMeta = bb[META];
            bb[META] ^= PositionFactory.STM_MASK;
            bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;
            int nullScore = -pvs(bb, depth - 1 - 2, -beta, -beta + 1, ply + 1, null);
            bb[META] = oldMeta;
            bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;
            if (nullScore >= beta) return beta;
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

        if (nMoves == 0) return inCheck ? -(SCORE_MATE - ply) : SCORE_STALEMATE;

        int ttMove = tt.wasHit(ttIndex, key) ? tt.getMove(ttIndex) : 0;
        moveOrderer.orderMoves(bb, list, nMoves, ttMove, killers[ply]);

        int bestScore = -SCORE_INF;
        int localBestMove = 0;
        int originalAlpha = alpha;
        int movesSearched = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];

            if (ply == 0 && excludedMoves != null && excludedMoves.contains(mv)) {
                continue;
            }

            long nodesBeforeMove = this.nodes;
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            movesSearched++;

            boolean isCapture = (i < capturesEnd);
            boolean isPromotion = ((mv >>> 14) & 0x3) == 1;
            boolean isTactical = isCapture || isPromotion;

            int score;
            // *******************************************************************
            // ** START: CORRECTED PVS SEARCH LOGIC                             **
            // *******************************************************************
            if (movesSearched == 1) {
                // Full-window search for the first valid move
                score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1, null);
            } else {
                // Apply Late Move Reduction (LMR)
                int lmrDepth = depth - 1;
                if (depth >= LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !isTactical && !inCheck) {
                    int reduction = (int) (0.75 + Math.log(depth) * Math.log(i) / 2.0);
                    lmrDepth -= Math.max(0, reduction);
                }

                // Zero-Window Search (ZWS)
                score = -pvs(bb, lmrDepth, -alpha - 1, -alpha, ply + 1, null);

                // If ZWS is promising, re-search with a full window to get an accurate score and PV
                if (score > alpha && isPvNode) {
                    // Re-search must be at the full, non-reduced depth
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1, null);
                }
            }
            // *******************************************************************
            // ** END: CORRECTED PVS SEARCH LOGIC                               **
            // *******************************************************************

            pf.undoMoveInPlace(bb);
            if (pool.isStopped()) return 0;

            if (ply == 0) {
                updateRootMove(mv, score, frames[ply + 1]);
                long nodesAfterMove = this.nodes;
                long nodesForThisMove = nodesAfterMove - nodesBeforeMove;
                int from = (mv >>> 6) & 0x3F;
                int to = mv & 0x3F;
                nodeTable[from][to] += nodesForThisMove;
            }

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

        if (movesSearched == 0) {
            return inCheck ? -(SCORE_MATE - ply) : SCORE_STALEMATE;
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

        if (inCheck) {
            int[] list = moves[ply];
            int nMoves = mg.generateEvasions(bb, list, 0);

            if (nMoves == 0) {
                // FIX: Use SCORE_MATE for consistency with pvs() mate scoring
                return -(SCORE_MATE - ply);
            }

            int bestScore = -SCORE_INF;
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                int score = -quiescence(bb, -beta, -alpha, ply + 1);
                pf.undoMoveInPlace(bb);

                if (pool.isStopped()) return 0;
                if (score > bestScore) {
                    bestScore = score;
                    if (score >= beta) return beta;
                    if (score > alpha) alpha = score;
                }
            }
            return bestScore;

        } else {
            int standPat = eval.evaluate(bb);
            if (standPat >= beta) return beta;
            if (standPat > alpha) alpha = standPat;

            final int futilityMargin = 900;
            if (standPat < alpha - futilityMargin) {
                return alpha;
            }

            int[] list = moves[ply];
            int nMoves = mg.generateCaptures(bb, list, 0);

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
                    if (score >= beta) return beta;
                    if (score > alpha) alpha = score;
                }
            }
            return bestScore;
        }
    }

    /**
     * Checks if the current position is a draw by repetition.
     * It looks through the history of the current search path and the game history
     * within the bounds of the 50-move rule.
     * @param bb The current board state.
     * @param ply The current search ply.
     * @return true if the position is a repetition, false otherwise.
     */
    private boolean isRepetitionDraw(long[] bb, int ply) {
        final long currentHash = bb[HASH];
        final int halfmoveClock = (int) PositionFactory.halfClock(bb[META]);

        // Iterate backwards from the previous position with the same side to move (ply - 2)
        // up to the limit of the current 50-move rule window.
        for (int i = 2; i <= halfmoveClock; i += 2) {
            int prevPly = ply - i;

            long previousHash;
            if (prevPly < 0) {
                // We've gone past the start of the search, so look in gameHistory.
                int gameHistoryIdx = gameHistory.size() + prevPly;
                if (gameHistoryIdx >= 0) {
                    previousHash = gameHistory.get(gameHistoryIdx);
                } else {
                    // We've searched past the beginning of the relevant game history.
                    break;
                }
            } else {
                // We are still within the current search path.
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