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
    private static final int ETH_WINDOW_DEPTH = 4;
    private static final int ETH_WINDOW_SIZE = 10; // Initial delta

    private static final int ETH_BETA_PRUNING_DEPTH = 8;
    private static final int ETH_BETA_MARGIN = 65;

    private static final int ETH_NULL_MOVE_PRUNING_DEPTH = 2;
    private static final int ETH_NMP_EVAL_DIVISOR = 191;

    private static final int ETH_LATE_MOVE_PRUNING_DEPTH = 8;
    private static final int[][] ETH_LMP_COUNTS = new int[2][11]; // [improving][depth]

    private static final int ETH_SEE_PRUNING_DEPTH = 10;
    private static final int ETH_SEE_QUIET_MARGIN = -64;
    private static final int ETH_SEE_NOISY_MARGIN = -20;

    private static final int[][] LMR_TABLE = new int[64][64];

    private static final int HISTORY_MAX = 16384;
    private static final int LMR_HISTORY_DIVISOR = 6167;

    static {
        // Initialize LMR Table
        for (int depth = 1; depth < 64; depth++) {
            for (int played = 1; played < 64; played++) {
                // 0.7844 + log(depth) * log(played) / 2.4696
                LMR_TABLE[depth][played] = (int) Math.round(0.7844 + Math.log(depth) * Math.log(played) / 2.4696);
            }
        }

        // Initialize LMP Counts
        for (int depth = 1; depth <= 10; depth++) {
            // Not Improving (0): 2.0767 + 0.3743 * depth * depth
            ETH_LMP_COUNTS[0][depth] = (int) Math.round(2.0767 + 0.3743 * depth * depth);
            // Improving (1): 3.8733 + 0.7124 * depth * depth
            ETH_LMP_COUNTS[1][depth] = (int) Math.round(3.8733 + 0.7124 * depth * depth);
        }
    }

    // ------------------------------------------------------------------------
    // Fields and Structures
    // ------------------------------------------------------------------------

    private final WorkerPoolImpl pool;
    final boolean isMainThread;

    // Threading primitives
    private final Lock mutex = new ReentrantLock();
    private final Condition startCondition = mutex.newCondition();
    private final Condition finishedCondition = mutex.newCondition();
    private volatile boolean searching = false;
    private volatile boolean quit = false;

    // Per-search state
    private long[] rootBoard;
    private SearchSpec spec;
    private PositionFactory pf;
    private MoveGenerator mg;
    // TimeManager tm; // Not used directly
    private InfoHandler ih;
    private TranspositionTable tt;
    private MoveOrderer moveOrderer;

    // NNUE
    private final NNUEState nnueState = new NNUEState();
    private final NNUE nnue = new NNUEImpl();

    // Search Results & Accounting
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

    // Heuristics & Time Management
    private int stability;
    private int lastBestMove;
    private final List<Integer> searchScores = new ArrayList<>();
    private final int[][] killers = new int[MAX_PLY + 2][2];
    private final int[][] history = new int[64][64];

    // Scratch buffers
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private final int[][] moves = new int[MAX_PLY + 2][256];

    // Represents a node in the search tree stack
    private static final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int len;
        int staticEval = SCORE_NONE; // For improving heuristic
        int score = SCORE_NONE; // For aspiration window seeding

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

    // ------------------------------------------------------------------------
    // Thread Lifecycle
    // ------------------------------------------------------------------------

    @Override
    public void run() {
        idleLoop();
    }

    private void idleLoop() {
        while (true) {
            mutex.lock();
            try {
                searching = false;
                finishedCondition.signal();
                while (!searching && !quit) {
                    try {
                        startCondition.await();
                    } catch (InterruptedException e) { }
                }
                if (quit) return;
            } finally {
                mutex.unlock();
            }

            if (isMainThread) {
                mainThreadSearch();
            } else {
                iterativeDeepening();
            }
        }
    }

    private void mainThreadSearch() {
        tt.incrementAge();
        pool.startHelpers();
        iterativeDeepening();
        pool.waitForHelpersFinished();
        pool.finalizeSearch(getSearchResult());
    }

    // ------------------------------------------------------------------------
    // Iterative Deepening and Aspiration
    // ------------------------------------------------------------------------

    private void iterativeDeepening() {
        resetSearchState();
        long searchStartMs = pool.getSearchStartTime();
        int maxDepth = spec.depth() > 0 ? spec.depth() : CoreConstants.MAX_PLY;

        // Iterative Deepening Loop
        for (int depth = 1; depth <= maxDepth; ++depth) {
            if (pool.isStopped()) break;

            // Aspiration Windows
            boolean success = aspirationSearch(rootBoard, depth);

            if (success && !pool.isStopped()) {
                updateSearchResults(depth, searchStartMs);
            }

            // Time Management Check
            if (isMainThread) {
                if (mateScore || softTimeUp(searchStartMs, pool.getSoftMs())) {
                    pool.stopSearch();
                    break;
                }
            }
        }
    }

    private void resetSearchState() {
        // Reset stats, heuristics, NNUE, and MoveOrderer
        this.nodes = 0;
        this.completedDepth = 0;
        this.pv.clear();
        this.stability = 0;
        this.lastBestMove = 0;
        this.searchScores.clear();

        for (int[] k : killers) Arrays.fill(k, 0);
        for (int[] row : history) Arrays.fill(row, 0);
        for (SearchFrame frame : frames) {
            frame.staticEval = SCORE_NONE;
            frame.score = SCORE_NONE;
        }

        nnue.refreshAccumulator(nnueState, rootBoard);
        this.moveOrderer = new MoveOrdererImpl(history);
    }

    private boolean aspirationSearch(long[] bb, int depth) {
        int alpha = -SCORE_MATE;
        int beta = SCORE_MATE;
        int delta = ETH_WINDOW_SIZE;
        int currentDepth = depth;

        // Use previous result if deep enough
        if (depth >= ETH_WINDOW_DEPTH && completedDepth > 0) {
            int prevScore = frames[0].score;
            alpha = Math.max(-SCORE_MATE, prevScore - delta);
            beta = Math.min(SCORE_MATE, prevScore + delta);
        }

        while (true) {
            if (pool.isStopped()) return false;

            // Call the main search function.
            int score = search(bb, currentDepth, alpha, beta, 0, false);

            if (pool.isStopped()) return false;

            // Success
            if (score > alpha && score < beta) {
                return true;
            }

            // Fail low
            if (score <= alpha) {
                beta = (alpha + beta) / 2;
                alpha = Math.max(-SCORE_MATE, alpha - delta);
                currentDepth = depth; // Reset depth on fail low
            }
            // Fail high
            else if (score >= beta) {
                beta = Math.min(SCORE_MATE, beta + delta);

                if (Math.abs(score) <= SCORE_MATE / 2) {
                    currentDepth = Math.max(1, currentDepth - 1);
                }
            }

            // Expand the window
            delta = delta + delta / 2;
        }
    }

    private void updateSearchResults(int depth, long searchStartMs) {
        // Score is stored in the root frame by the search function.
        lastScore = frames[0].score;
        mateScore = Math.abs(lastScore) >= SCORE_MATE_IN_MAX_PLY;
        completedDepth = depth;

        if (frames[0].len > 0) {
            pv = new ArrayList<>(frames[0].len);
            for (int i = 0; i < frames[0].len; i++) {
                pv.add(frames[0].pv[i]);
            }
            bestMove = pv.get(0);
            ponderMove = pv.size() > 1 ? pv.get(1) : 0;

            // Update stability for TM
            if (bestMove == lastBestMove) {
                stability++;
            } else {
                stability = 0;
            }
            lastBestMove = bestMove;
        }
        searchScores.add(lastScore);

        elapsedMs = System.currentTimeMillis() - searchStartMs;

        // Reporting
        if (isMainThread && ih != null) {
            long totalNodes = pool.totalNodes();
            long nps = elapsedMs > 0 ? (totalNodes * 1000) / elapsedMs : 0;
            ih.onInfo(new SearchInfo(
                    depth, completedDepth, 1, lastScore, mateScore, totalNodes,
                    nps, elapsedMs, pv, tt.hashfull(), 0));
        }
    }

    // Time Management logic (Kept from original Java implementation)
    private boolean softTimeUp(long searchStartMs, long softTimeLimit) {
        if (softTimeLimit >= Long.MAX_VALUE / 2) return false;
        long currentElapsed = System.currentTimeMillis() - searchStartMs;
        if (currentElapsed >= pool.getMaximumMs()) return true;
        if (completedDepth < CoreConstants.TM_HEURISTICS_MIN_DEPTH) return currentElapsed >= softTimeLimit;

        // Instability-Based Time Extension
        double instability = 0.0;
        if (bestMove != lastBestMove) {
            instability += CoreConstants.TM_INSTABILITY_PV_CHANGE_BONUS;
        }
        if (searchScores.size() >= 2) {
            int prevScore = searchScores.get(searchScores.size() - 2);
            int scoreDifference = Math.abs(lastScore - prevScore);
            instability += scoreDifference * CoreConstants.TM_INSTABILITY_SCORE_WEIGHT;
        }

        double extensionFactor = 1.0 + instability;
        extensionFactor = Math.min(extensionFactor, CoreConstants.TM_MAX_EXTENSION_FACTOR);
        long extendedSoftTime = (long)(softTimeLimit * extensionFactor);
        return currentElapsed >= extendedSoftTime;
    }

    /**
     * The main recursive search function (PVS).
     */
    private int search(long[] bb, int depth, int alpha, int beta, int ply, boolean cutnode) {
        // Step 1. Initialization and QSearch Entry
        SearchFrame frame = frames[ply];
        frame.len = 0;
        searchPathHistory[ply] = bb[HASH];

        final boolean RootNode = (ply == 0);
        final boolean PvNode = (beta - alpha) > 1;

        if (depth <= 0) {
            return qsearch(bb, alpha, beta, ply);
        }

        depth = Math.max(0, depth);
        nodes++;

        // Step 2. Abort Check (Time)
        if ((nodes & 2047) == 0) {
            if (pool.isStopped() || (isMainThread && pool.shouldStop(pool.getSearchStartTime(), false))) {
                pool.stopSearch();
                return 0;
            }
        }

        // Step 3. Early Exit Conditions (Draws, Max Ply, Mate Distance)
        if (!RootNode) {
            // Draw Detection
            if (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100) {
                return 1 - ((int)nodes & 2);
            }

            // Max Ply
            if (ply >= MAX_PLY) {
                return nnue.evaluateFromAccumulator(nnueState, bb);
            }

            // Mate Distance Pruning
            int rAlpha = Math.max(alpha, -SCORE_MATE + ply);
            int rBeta = Math.min(beta, SCORE_MATE - ply - 1);
            if (rAlpha >= rBeta) return rAlpha;
        }

        // Step 4. Transposition Table Probe
        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        boolean ttHit = tt.wasHit(ttIndex, key);
        int ttMove = 0;
        int ttEval = SCORE_NONE;
        int ttDepth = -1;

        if (ttHit) {
            ttMove = tt.getMove(ttIndex);
            int ttValue = tt.getScore(ttIndex, ply);
            ttEval = tt.getStaticEval(ttIndex);
            ttDepth = tt.getDepth(ttIndex);
            int ttBound = tt.getBound(ttIndex);

            // TT Cutoff
            if (ttDepth >= depth && (depth == 0 || !PvNode)) {
                if (ttBound == TranspositionTable.FLAG_EXACT ||
                        (ttBound == TranspositionTable.FLAG_LOWER && ttValue >= beta) ||
                        (ttBound == TranspositionTable.FLAG_UPPER && ttValue <= alpha)) {
                    return ttValue;
                }
            }
        }

        // Step 6. Initialization and Evaluation
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));

        int eval;
        if (inCheck) {
            eval = SCORE_NONE;
        } else if (ttEval != SCORE_NONE) {
            eval = ttEval;
        } else {
            eval = nnue.evaluateFromAccumulator(nnueState, bb);
        }
        frame.staticEval = eval;

        // Improving Heuristic
        boolean improving = false;
        if (!inCheck && ply >= 2 && frames[ply-2].staticEval != SCORE_NONE && eval != SCORE_NONE) {
            improving = eval > frames[ply-2].staticEval;
        }
        final int improvingIdx = improving ? 1 : 0;

        // ------------------------------------------------------------------------
        // Pruning Techniques (>= 10 Elo)
        // ------------------------------------------------------------------------

        // Step 7. Beta Pruning / RFP (~32 Elo)
        if (!PvNode && !inCheck && depth <= ETH_BETA_PRUNING_DEPTH && eval != SCORE_NONE) {

            int margin = ETH_BETA_MARGIN * Math.max(0, depth - improvingIdx);
            if (eval - margin >= beta) {
                return eval;
            }
        }

        // Step 8. Alpha Pruning (~3 Elo) - EXCLUDED

        // Step 9. Null Move Pruning (~93 Elo)
        if (!PvNode && !inCheck && eval >= beta && depth >= ETH_NULL_MOVE_PRUNING_DEPTH && pf.hasNonPawnMaterial(bb)) {

            // R = 4 + depth / 5 + MIN(3, (eval - beta) / 191)
            int R = 4 + depth / 5 + Math.min(3, (eval - beta) / ETH_NMP_EVAL_DIVISOR);

            // Make the null move
            long oldMeta = bb[META];
            bb[META] ^= PositionFactory.STM_MASK;
            // Crucial: Clear EP square if it exists, as a null move invalidates it.
            if (PositionFactory.epSquare(bb[META]) != 64) {
                bb[META] &= ~PositionFactory.EP_MASK;
            }
            bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

            // Search with zero window, flipping cutnode status
            int nullScore = -search(bb, depth - R, -beta, -beta + 1, ply + 1, !cutnode);

            // Undo the null move
            bb[META] = oldMeta;
            bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

            if (nullScore >= beta) {
                // Don't return unproven Mates
                return (nullScore > SCORE_MATE_IN_MAX_PLY) ? beta : nullScore;
            }
        }

        // Step 10. ProbCut (~9 Elo) - EXCLUDED

        // Step 11. Internal Iterative Reductions (IIR)
        if (depth >= 7 && (PvNode || cutnode) && (ttMove == 0 || (ttHit && ttDepth + 4 < depth))) {
            depth--;
        }

        // ------------------------------------------------------------------------
        // Move Loop
        // ------------------------------------------------------------------------

        // Step 12. Move Generation and Ordering
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

        moveOrderer.orderMoves(bb, list, nMoves, ttMove, killers[ply]);

        int bestScore = -SCORE_INF;
        int localBestMove = 0;
        int originalAlpha = alpha;
        int movesSeen = 0;
        int legalMovesPlayed = 0;
        boolean skipQuiets = false;

        // Tracking quiet moves for history updates
        int[] quietsTried = new int[256];
        int quietsPlayedCount = 0;

        // Prepare SEE Margins
        // Noisy margin is depth^2, Quiet margin is depth
        int seeMarginNoisy = ETH_SEE_NOISY_MARGIN * depth * depth;
        int seeMarginQuiet = ETH_SEE_QUIET_MARGIN * depth;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            movesSeen++;

            // Determine move type. Note: Promotions are generally treated as tactical.
            final boolean isPromotion = ((mv >>> 14) & 0x3) == 1;
            final boolean isQuiet = (i >= capturesEnd) && !isPromotion;

            if (isQuiet && skipQuiets) continue;

            final int from = (mv >>> 6) & 0x3F;
            final int to = mv & 0x3F;

            // Step 13. Late Move Pruning (~80 Elo)
            if (bestScore > -SCORE_MATE_IN_MAX_PLY && depth <= ETH_LATE_MOVE_PRUNING_DEPTH && isQuiet) {
                if (depth <= 10) { // Check bounds of the precomputed table
                    if (movesSeen >= ETH_LMP_COUNTS[improvingIdx][depth]) {
                        skipQuiets = true;
                        continue;
                    }
                }
            }

            // Step 14. Quiet Move Pruning
            // 14A/B (Futility) EXCLUDED. 14C (Continuation) omitted.

            // Step 15. SEE Pruning (~42 Elo)
            if (bestScore > -SCORE_MATE_IN_MAX_PLY && depth <= ETH_SEE_PRUNING_DEPTH) {
                int threshold = isQuiet ? seeMarginQuiet : seeMarginNoisy;
                if (moveOrderer.see(bb, mv) < threshold) {
                    continue;
                }
            }

            // Make Move
            int capturedPiece = getCapturedPieceType(bb, mv);
            int moverPiece = ((mv >>> 16) & 0xF);

            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
            legalMovesPlayed++;
            nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

            if (isQuiet) {
                quietsTried[quietsPlayedCount++] = mv;
            }

            // Step 16. Extensions (~60 Elo)
            int extension = 0;
            // Check Extensiongit
            if (inCheck) {
                extension = 1;
            }
            // Singular Extensions are omitted.

            int newDepth = depth - 1 + extension;
            int score = 0;
            boolean doFullSearch = true;

            // Step 18. Late Move Reductions
            if (depth >= 3 && legalMovesPlayed > 1 && extension == 0) {

                // Step 18A. Quiet LMR (~249 Elo)
                if (isQuiet) {
                    int d = Math.min(depth, 63);
                    int p = Math.min(legalMovesPlayed, 63);
                    int R = LMR_TABLE[d][p];

                    // Adjustments
                    R += (!PvNode ? 1 : 0) + (!improving ? 1 : 0);

                    // Adjust based on history
                    R -= history[from][to] / LMR_HISTORY_DIVISOR;

                    // Clamp R
                    R = Math.min(depth - 1, Math.max(R, 1));

                    // Reduced depth ZWS
                    score = -search(bb, newDepth - R, -alpha - 1, -alpha, ply + 1, true);
                    doFullSearch = (score > alpha && R > 0);
                }
                // Step 18B. Noisy LMR (~3 Elo) - EXCLUDED
            }

            // PVS Search Logic
            if (doFullSearch) {
                // If LMR wasn't run, or if it failed high, we need a search.

                // ZWS first (unless it's the first move in a PV node)
                if (legalMovesPlayed > 1 || !PvNode) {
                    // If LMR ran and failed high, 'score' holds the reduced ZWS result.
                    // We only run a new ZWS if LMR didn't run OR if the reduced ZWS was > alpha.
                    // Simplified condition: if we haven't yet searched this move fully and need to verify.
                    if (depth < 3 || !isQuiet || score > alpha) {
                        score = -search(bb, newDepth, -alpha - 1, -alpha, ply + 1, !cutnode);
                    }
                }

                // Full window search (PVS)
                if (PvNode && (legalMovesPlayed == 1 || score > alpha)) {
                    score = -search(bb, newDepth, -beta, -alpha, ply + 1, false);
                }
            }


            // Undo Move
            pf.undoMoveInPlace(bb);
            nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);

            if (pool.isStopped()) return 0;

            // Step 19. Update Best Score and PV
            if (score > bestScore) {
                bestScore = score;
                localBestMove = mv;

                if (score > alpha) {
                    alpha = score;
                    if (PvNode) {
                        frame.set(frames[ply + 1].pv, frames[ply + 1].len, mv);
                    }

                    // Beta Cutoff
                    if (score >= beta) {
                        // Step 20. Update History and Killers (~760 Elo)
                        if (isQuiet) {
                            // Update Killers
                            if (killers[ply][0] != mv) {
                                killers[ply][1] = killers[ply][0];
                                killers[ply][0] = mv;
                            }
                            // Update History (Bonus/Penalty system)
                            updateHistoryHeuristics(mv, quietsTried, quietsPlayedCount, depth);
                        }
                        break;
                    }
                }
            }
        }

        // Step 21. Checkmate/Stalemate
        if (legalMovesPlayed == 0) {
            return inCheck ? -(SCORE_MATE - ply) : SCORE_STALEMATE;
        }

        // Step 23. TT Store
        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestScore > originalAlpha) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        tt.store(ttIndex, key, flag, depth, localBestMove, bestScore, eval, PvNode, ply);

        // Store score in frame for aspiration window seeding
        frame.score = bestScore;

        return bestScore;
    }

    // ------------------------------------------------------------------------
    // Quiescence Search (Standard Implementation)
    // ------------------------------------------------------------------------

    private int qsearch(long[] bb, int alpha, int beta, int ply) {
        // (Standard QSearch implementation including Stand-Pat, TT, and SEE-pruned captures)

        searchPathHistory[ply] = bb[HASH];
        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return 1 - ((int)nodes & 2);
        }

        if ((nodes & 2047) == 0 && pool.isStopped()) {
            return 0;
        }

        if (ply >= MAX_PLY) {
            return nnue.evaluateFromAccumulator(nnueState, bb);
        }

        nodes++;

        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        int staticEval = SCORE_NONE;
        int localBestMove = 0;

        if (tt.wasHit(ttIndex, key)) {
            // Check for TT cutoff
            if (tt.getDepth(ttIndex) >= 0) {
                int score = tt.getScore(ttIndex, ply);
                int flag = tt.getBound(ttIndex);
                if ((flag == TranspositionTable.FLAG_LOWER && score >= beta) ||
                        (flag == TranspositionTable.FLAG_UPPER && score <= alpha) ||
                        (flag == TranspositionTable.FLAG_EXACT)) {
                    return score;
                }
            }
            staticEval = tt.getStaticEval(ttIndex);
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        int bestScore;
        int originalAlpha = alpha;

        if (inCheck) {
            // In Check: Search Evasions
            bestScore = -SCORE_INF;
            int[] list = moves[ply];
            int nMoves = mg.generateEvasions(bb, list, 0);
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            int legalMoves = 0;
            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                int capturedPiece = getCapturedPieceType(bb, mv);
                int moverPiece = ((mv >>> 16) & 0xF);

                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                legalMoves++;
                nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

                int score = -qsearch(bb, -beta, -alpha, ply + 1);

                pf.undoMoveInPlace(bb);
                nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);

                if (pool.isStopped()) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    localBestMove = mv;
                    if (score >= beta) break;
                    if (score > alpha) alpha = score;
                }
            }
            if (legalMoves == 0) {
                bestScore = -(SCORE_MATE - ply);
            }

        } else {
            // Not in Check: Stand-Pat and Tactical Moves

            // Calculate static eval if needed
            if (staticEval == SCORE_NONE) {
                staticEval = nnue.evaluateFromAccumulator(nnueState, bb);
            }
            bestScore = staticEval; // Stand-pat

            if (bestScore >= beta) {
                // Prune immediately (no need to store if we return immediately, but good practice)
                tt.store(ttIndex, key, TranspositionTable.FLAG_LOWER, 0, 0, bestScore, staticEval, false, ply);
                return bestScore;
            }
            if (bestScore > alpha) {
                alpha = bestScore;
            }

            int[] list = moves[ply];
            int nMoves = mg.generateCaptures(bb, list, 0);
            // Prune losing captures (SEE < 0)
            nMoves = moveOrderer.seePrune(bb, list, nMoves);
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                int capturedPiece = getCapturedPieceType(bb, mv);
                int moverPiece = ((mv >>> 16) & 0xF);

                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

                int score = -qsearch(bb, -beta, -alpha, ply + 1);

                pf.undoMoveInPlace(bb);
                nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);

                if (pool.isStopped()) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    localBestMove = mv;
                    if (score >= beta) break;
                    if (score > alpha) alpha = score;
                }
            }
        }

        // TT Store
        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestScore > originalAlpha) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        // Ensure we have a static eval for the TT store, calculating if necessary
        if (staticEval == SCORE_NONE) {
            staticEval = nnue.evaluateFromAccumulator(nnueState, bb);
        }

        tt.store(ttIndex, key, flag, 0, localBestMove, bestScore, staticEval, false, ply);

        return bestScore;
    }

    private void updateHistoryHeuristics(int bestMove, int[] quietsTried, int count, int depth) {
        // Use depth*depth weighting.
        int bonus = depth * depth;

        // Iterate through all quiet moves tried at this node
        for (int i = 0; i < count; i++) {
            int mv = quietsTried[i];

            int from = (mv >>> 6) & 0x3F;
            int to = mv & 0x3F;

            // Bonus if it caused the cutoff, penalty otherwise
            int delta = (mv == bestMove) ? bonus : -bonus;

            // Apply update with saturation (clamping)
            int current = history[from][to];
            current += delta;

            if (current > HISTORY_MAX) current = HISTORY_MAX;
            else if (current < -HISTORY_MAX) current = -HISTORY_MAX;

            history[from][to] = current;
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
                return true;
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

        int start = isWhiteMover ? PositionFactory.BP : PositionFactory.WP;
        int end = isWhiteMover ? PositionFactory.BK : PositionFactory.WK;

        for (int p = start; p <= end; p++) {
            if ((bb[p] & toBit) != 0) {
                return p;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------
    // Interface Implementation (Threading/Setup)
    // ------------------------------------------------------------------------

    @Override
    public void prepareForSearch(long[] root, SearchSpec s, PositionFactory p, MoveGenerator m, TranspositionTable t, TimeManager timeMgr) {
        this.rootBoard = root.clone();
        this.spec = s;
        this.pf = p;
        this.mg = m;
        this.tt = t;
        // this.tm = timeMgr;
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