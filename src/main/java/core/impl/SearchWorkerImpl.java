// File: LazySmpSearchWorkerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.NNUEState;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static core.constants.CoreConstants.*;
import static core.contracts.PositionFactory.*;

public final class SearchWorkerImpl implements Runnable, SearchWorker {
    private final WorkerPoolImpl pool;
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
    private TimeManager tm;
    private InfoHandler ih;
    private TranspositionTable tt;
    private MoveOrderer moveOrderer;

    /* ── NNUE ────────── */
    private final NNUEState nnueState = new NNUEState();
    private NNUE nnue;

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
    private static final int[][] LMR_TABLE = new int[MAX_PLY][MAX_PLY]; // Using MAX_PLY for size safety


    static {
        for (int d = 1; d < MAX_PLY; d++) {
            for (int m = 1; m < MAX_PLY; m++) {
                double reduction = (
                        75 / 100.0 +
                                Math.log(d) * Math.log(m) / (250 / 100.0)
                );
                // Clamp the reduction to a reasonable maximum, e.g., depth - 2
                LMR_TABLE[d][m] = Math.max(0, (int) Math.round(reduction));
            }
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

    public SearchWorkerImpl(boolean isMainThread, WorkerPoolImpl pool) {
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
        for (long[] row : this.nodeTable) {
            Arrays.fill(row, 0);
        }
        for (int[] k : killers) Arrays.fill(k, 0);
        /* Reset history table before new search */
        for (int[] row : history) {
            Arrays.fill(row, 0);
        }
        nnue.refreshAccumulator(nnueState, rootBoard);
        // Change: Pass history to move orderer
        this.moveOrderer = new MoveOrdererImpl(history);

        long searchStartMs = pool.getSearchStartTime();
        int maxDepth = spec.depth() > 0 ? spec.depth() : CoreConstants.MAX_PLY;

        int aspirationScore = 0;

        for (int depth = 1; depth <= maxDepth; ++depth) {
            if (pool.isStopped()) break;

            int score;
            int window = ASP_WINDOW_INITIAL_DELTA;
            int alpha  = aspirationScore - window;
            int beta   = aspirationScore + window;

            while (true) {
                score = pvs(rootBoard, depth, alpha, beta, 0);

                if (score <= alpha) {                 // fail‑low  → widen downward
                    window <<= 1;                     // double the window
                    alpha  = Math.max(score - window, -SCORE_INF);
                    beta   = alpha + (window << 1);   // keep it symmetric
                } else if (score >= beta) {           // fail‑high → widen upward
                    window <<= 1;
                    beta   = Math.min(score + window, SCORE_INF);
                    alpha  = beta - (window << 1);
                } else {
                    break;                            // inside window → done
                }
            }

            // Store the successful score for the next iteration's aspiration window
            aspirationScore = score;

            lastScore = score;
            mateScore = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY;
            completedDepth = depth;

            if (frames[0].len > 0) {
                pv = new ArrayList<>(frames[0].len);
                for (int i = 0; i < frames[0].len; i++) {
                    pv.add(frames[0].pv[i]);
                }
                bestMove = pv.get(0);
                ponderMove = pv.size() > 1 ? pv.get(1) : 0;

                if (bestMove == lastBestMove) {
                    stability++;
                } else {
                    stability = 0;
                }
                lastBestMove = bestMove;
            }
            searchScores.add(lastScore);

            elapsedMs = System.currentTimeMillis() - searchStartMs;

            if (isMainThread && ih != null) {
                long totalNodes = pool.totalNodes();
                long nps = elapsedMs > 0 ? (totalNodes * 1000) / elapsedMs : 0;
                ih.onInfo(new SearchInfo(
                        depth, completedDepth, 1, score, mateScore, totalNodes,
                        nps, elapsedMs, pv, tt.hashfull(), 0));
            }

            if (isMainThread) {
                if (mateScore || softTimeUp(searchStartMs, pool.getSoftMs())) {
                    pool.stopSearch();
                }
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

    private int pvs(long[] bb, int depth, int alpha, int beta, int ply) {
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
            if (ply >= MAX_PLY) return nnue.evaluateFromAccumulator(nnueState, PositionFactory.whiteToMove(bb[META]));
        }

        boolean isPvNode = (beta - alpha) > 1;
        long key = pf.zobrist(bb);

        int ttIndex = tt.probe(key);

        // 1. Introduce ttHit boolean
        boolean ttHit = tt.wasHit(ttIndex, key);

        // 2. Use ttHit for the cutoff check
        if (ttHit && tt.getDepth(ttIndex) >= depth && ply > 0 && !isPvNode) {
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


        final int IIR_MIN_DEPTH = 4;

        // 3. Adjust IIR condition slightly to use ttHit
        if (depth >= IIR_MIN_DEPTH && isPvNode && (!ttHit || tt.getMove(ttIndex) == 0)) {
            depth--;
        }

        // 4. Centralize Static Evaluation Calculation
        int staticEval = Integer.MIN_VALUE;

        // --- Static Evaluation ---
        // Try to get staticEval from TT
        if (ttHit) {
            int ttEval = tt.getStaticEval(ttIndex);
            if (ttEval != SCORE_NONE) {
                staticEval = ttEval;
            }
        }

        // If not from TT (or if SCORE_NONE was stored), calculate it.
        if (staticEval == Integer.MIN_VALUE) {
            staticEval = nnue.evaluateFromAccumulator(nnueState, PositionFactory.whiteToMove(bb[META]));
        }

        // This prunes branches where the static evaluation is so high that it's
        // unlikely any move will drop the score below beta. It's a cheap check
        // performed before the more expensive Null Move Pruning.
        final int RFP_MAX_DEPTH = 8;
        final int RFP_MARGIN = 75; // Margin per ply of depth

        if (!isPvNode && !inCheck && depth <= RFP_MAX_DEPTH && Math.abs(beta) < SCORE_MATE_IN_MAX_PLY) {
            if (staticEval - RFP_MARGIN * depth >= beta) {
                return beta; // Prune, static eval is high enough.
            }
        }

        if (!inCheck && !isPvNode && depth >= 3 && ply > 0 && pf.hasNonPawnMaterial(bb)) {
            if (staticEval >= beta) {
                // The reduction is larger for deeper searches.
                int r = 3 + depth / 4;
                int nmpDepth = depth - 1 - r;

                // Make the null move
                long oldMeta = bb[META];
                bb[META] ^= PositionFactory.STM_MASK;
                bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

                int nullScore = -pvs(bb, nmpDepth, -beta, -beta + 1, ply + 1);

                // Undo the null move
                bb[META] = oldMeta;
                bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

                // If the null-move search causes a cutoff, we can trust it and prune.
                if (nullScore >= beta) {
                    return beta; // Prune the node.
                }
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

        int ttMove = 0;
        if (ttHit) {
            ttMove = tt.getMove(ttIndex);
            if (ttMove != 0) {
                for (int i = 0; i < nMoves; i++) {
                    if (list[i] == ttMove) {
                        list[i] = list[0];
                        list[0] = ttMove;
                        break;
                    }
                }
            }
        }

        moveOrderer.orderMoves(bb, list, nMoves, ttMove, killers[ply]);

        int bestScore = -SCORE_INF;
        int localBestMove = 0;
        int originalAlpha = alpha;
        int legalMovesFound = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];

            long nodesBeforeMove = this.nodes;
            int capturedPiece = getCapturedPieceType(bb, mv);
            int moverPiece = ((mv >>> 16) & 0xF);
            int from = (mv >>> 6) & 0x3F;
            int to = mv & 0x3F;

            final int SEE_MARGIN_PER_DEPTH = -70;
            if (!isPvNode && !inCheck && depth <= 8 && moveOrderer.see(bb, mv) < SEE_MARGIN_PER_DEPTH * depth) {
                continue; // Prune this move
            }

            boolean isCapture = (i < capturesEnd);
            boolean isPromotion = ((mv >>> 14) & 0x3) == 1;
            boolean isTactical = isCapture || isPromotion;

            // --- Futility Pruning (Enhanced with History and Killers) ---
            if (!isPvNode && !inCheck && bestScore > -SCORE_MATE_IN_MAX_PLY && !isTactical) {
                // Pruning is only applied up to a certain depth from the horizon.
                if (depth <= FP_MAX_DEPTH) {
                    // New quadratic margin calculation
                    int margin = (depth * FP_MARGIN_PER_PLY) + (depth * depth * FP_MARGIN_QUADRATIC);

                    // If the static evaluation plus the margin is still below alpha, prune the move.
                    if (staticEval + margin < alpha) {
                        continue; // Prune this move
                    }
                }
            }

            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
            legalMovesFound++;
            nnue.updateNnueAccumulator(nnueState, moverPiece, capturedPiece, mv);

            int score;
            if (i == 0) {
                score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
            } else {
                // Late Move Reductions (LMR)
                int reduction = 0;
                if (depth >= LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !isTactical && !inCheck) {
                    reduction = calculateReduction(depth, i);
                }
                int reducedDepth = Math.max(0, depth - 1 - reduction);

                // 2. Perform a fast zero-window search to test the move
                score = -pvs(bb, reducedDepth, -alpha - 1, -alpha, ply + 1);

                // 3. If the test was promising (score > alpha), re-search with the full window and full depth
                if (score > alpha) {
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
                }
            }

            pf.undoMoveInPlace(bb);
            nnue.undoNnueAccumulatorUpdate(nnueState, moverPiece, capturedPiece, mv);
            if (pool.isStopped()) return 0;

            if (ply == 0) {
                long nodesAfterMove = this.nodes;
                long nodesForThisMove = nodesAfterMove - nodesBeforeMove;
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
                            history[from][to] += depth * depth;  // Increment by depth^2 for stronger weighting
                        }

                        if (!isTactical) {
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

        if (legalMovesFound == 0) {
            return inCheck ? -(SCORE_MATE_IN_MAX_PLY - ply) : SCORE_STALEMATE;
        }

        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestScore > originalAlpha) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        tt.store(ttIndex, key, flag, depth, localBestMove, bestScore, staticEval, isPvNode, ply);

        return bestScore;
    }

    // In core/impl/SearchWorkerImpl.java

    private int quiescence(long[] bb, int alpha, int beta, int ply) {
        searchPathHistory[ply] = bb[HASH];
        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return SCORE_DRAW;
        }

        if ((nodes & 2047) == 0 && pool.isStopped()) {
            return 0;
        }

        if (ply >= MAX_PLY) {
            return nnue.evaluateFromAccumulator(nnueState, PositionFactory.whiteToMove(bb[META]));
        }

        nodes++;

        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        int staticEval = SCORE_NONE; // To be populated by TT or NNUE
        int localBestMove = 0; // To store the best move found in this node

        if (tt.wasHit(ttIndex, key)) {
            // A depth of 0 marks a q-search entry, equivalent to Stockfish's DEPTH_QS.
            if (tt.getDepth(ttIndex) >= 0) {
                int score = tt.getScore(ttIndex, ply);
                int flag = tt.getBound(ttIndex);

                // Check for a cutoff using the stored bound.
                if ((flag == TranspositionTable.FLAG_LOWER && score >= beta) ||
                        (flag == TranspositionTable.FLAG_UPPER && score <= alpha)) {
                    return score; // TT Cutoff
                }
            }
            // Use the stored static eval if it exists to avoid re-calculating.
            staticEval = tt.getStaticEval(ttIndex);
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        int bestScore;

        if (inCheck) {
            // --- In Check: Search Evasions ---
            int[] list = moves[ply];
            int nMoves = mg.generateEvasions(bb, list, 0);
            int legalMovesFound = 0;
            bestScore = -SCORE_INF;
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                int capturedPiece = getCapturedPieceType(bb, mv);
                int moverPiece = ((mv >>> 16) & 0xF);

                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                legalMovesFound++;
                nnue.updateNnueAccumulator(nnueState, moverPiece, capturedPiece, mv);

                int score = -quiescence(bb, -beta, -alpha, ply + 1);

                nnue.undoNnueAccumulatorUpdate(nnueState, moverPiece, capturedPiece, mv);
                pf.undoMoveInPlace(bb);

                if (pool.isStopped()) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    localBestMove = mv; // Store the best move found
                    if (score >= beta) break; // Beta cutoff
                    if (score > alpha) alpha = score;
                }
            }

            if (legalMovesFound == 0) {
                bestScore = -(SCORE_MATE_IN_MAX_PLY - ply);
            }

        } else {
            // --- Not in Check: Stand-Pat and Tactical Moves ---
            // If we didn't get static eval from TT, calculate it now.
            if (staticEval == SCORE_NONE) {
                staticEval = nnue.evaluateFromAccumulator(nnueState, PositionFactory.whiteToMove(bb[META]));
            }

            bestScore = staticEval; // This is the stand-pat score.

            if (bestScore >= beta) {
                // The position is already good enough. Store as a lower bound and prune.
                tt.store(ttIndex, key, TranspositionTable.FLAG_LOWER, 0, 0, bestScore, staticEval, false, ply);
                return beta;
            }
            if (bestScore > alpha) {
                alpha = bestScore;
            }

            int[] list = moves[ply];
            int nMoves = mg.generateCaptures(bb, list, 0);
            nMoves = moveOrderer.seePrune(bb, list, nMoves);
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                int capturedPiece = getCapturedPieceType(bb, mv);
                int moverPiece = ((mv >>> 16) & 0xF);

                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                nnue.updateNnueAccumulator(nnueState, moverPiece, capturedPiece, mv);

                int score = -quiescence(bb, -beta, -alpha, ply + 1);

                nnue.undoNnueAccumulatorUpdate(nnueState, moverPiece, capturedPiece, mv);
                pf.undoMoveInPlace(bb);

                if (pool.isStopped()) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    localBestMove = mv; // Store best move
                    if (score >= beta) break; // Beta cutoff
                    if (score > alpha) alpha = score;
                }
            }
        }

        // Determine the bound based on whether we failed high or failed low.
        // Q-search is not exhaustive, so it cannot prove an exact score.
        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : TranspositionTable.FLAG_UPPER;

        // Store with depth 0 to mark it as a q-search entry.
        tt.store(ttIndex, key, flag, 0, localBestMove, bestScore, staticEval, false, ply);

        return bestScore;
    }

    private int calculateReduction(int depth, int moveNumber) {
        // Ensure indices are within the bounds of the pre-calculated table
        int d = Math.min(depth, CoreConstants.MAX_PLY - 1);
        int m = Math.min(moveNumber, CoreConstants.MAX_PLY - 1);
        return LMR_TABLE[d][m];
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

    private int getCapturedPieceType(long[] bb, int move) {
        int to = move & 0x3F;
        long toBit = 1L << to;
        int moveType = (move >>> 14) & 0x3;
        boolean isWhiteMover = (((move >>> 16) & 0xF) < 6);

        if (moveType == 2) { // En-passant
            return isWhiteMover ? PositionFactory.BP : PositionFactory.WP;
        }

        for (int p = isWhiteMover ? PositionFactory.BP : PositionFactory.WP; p <= (isWhiteMover ? PositionFactory.BK : PositionFactory.WK); p++) {
            if ((bb[p] & toBit) != 0) {
                return p;
            }
        }
        return -1; // No capture
    }

    @Override
    public void prepareForSearch(long[] root, SearchSpec s, PositionFactory p, MoveGenerator m, NNUE nnue, TranspositionTable t, TimeManager timeMgr) {
        this.rootBoard = root.clone();
        this.spec = s;
        this.pf = p;
        this.mg = m;
        this.tt = t;
        this.tm = timeMgr;
        this.gameHistory = s.history();
        this.nnue = nnue;
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