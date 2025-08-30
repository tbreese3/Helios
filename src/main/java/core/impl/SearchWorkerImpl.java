package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.NNUEState;
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

// FIX: Import PositionFactoryImpl to access Zobrist keys (SIDE_TO_MOVE, EP_FILE) required for NMP.
import core.impl.PositionFactoryImpl;

import static core.constants.CoreConstants.*;
// This import provides access to constants like META, HASH, STM_MASK, EP_MASK, EP_SHIFT, EP_NONE.
import static core.contracts.PositionFactory.*;

public final class SearchWorkerImpl implements Runnable, SearchWorker {
    private final WorkerPoolImpl pool;
    final boolean isMainThread;

    /* ── threading primitives (Retained Lazy SMP structure) ─────────── */
    private final Lock mutex = new ReentrantLock();
    private final Condition startCondition = mutex.newCondition();
    private final Condition finishedCondition = mutex.newCondition();
    private volatile boolean searching = false;
    private volatile boolean quit = false;

    /* ── dependencies ────────── */
    private PositionFactory pf;
    private MoveGenerator mg;
    private InfoHandler ih;
    private TranspositionTable tt;
    private MoveOrderer moveOrderer;

    /* ── NNUE ────────── */
    // We use a single NNUE state and rely on incremental update/undo.
    private final NNUEState nnueState = new NNUEState();
    private final NNUE nnue = new NNUEImpl();

    /* ── Search State ────────── */
    private long[] rootBoard;
    private SearchSpec spec;
    private List<Long> gameHistory;
    private int rootDepth;
    private int ply;
    private long nodes;
    private int selDepth; // Selective depth tracking
    // Centralized time management field
    private long maximumTimeMs; // Hard time limit for the search

    // History of positions within the current search path
    private final long[] searchPathHistory = new long[MAX_PLY + 2];

    /* ── Heuristics Data Structures (C++ Inspired) ────────── */

    // Main History (Butterfly boards): [from][to] -> score
    private final int[][] mainHistory = new int[64][64];

    // Continuation History: [prev_piece_to_index][current_piece_to_index] -> score
    private static final int PIECE_TO_SIZE = 12 * 64;
    private final int[][] contHistory = new int[PIECE_TO_SIZE][PIECE_TO_SIZE];

    // Counter Move History: [prev_piece_type][prev_to_sq] -> Move
    private final int[][] counterMoveHistory = new int[12][64];

    /* ── LMR Table Initialization ────────── */
    private static final int[][] LMR_TABLE = new int[MAX_PLY][MAX_MOVES];

    static {
        LMR_TABLE[0][0] = 0;
        for (int d = 1; d < MAX_PLY; d++) {
            for (int m = 1; m < MAX_MOVES; m++) {
                // C++ Formula: 0.25 + log(d) * log(m) / 2.25
                double reduction = LMR_BASE + Math.log(d) * Math.log(m) / LMR_DIVISOR;
                LMR_TABLE[d][m] = (int) Math.floor(reduction);
            }
        }
    }

    /* ── Search Stack (C++ SearchInfo equivalent) ────────── */
    private final SearchStackFrame[] stack = new SearchStackFrame[MAX_PLY + 8];

    // Helper class for stack frames
    private static final class SearchStackFrame {
        int staticEval = SCORE_NONE;
        int playedMove = 0; // Move played to reach this position (0 for null move/root)
        int[] killers = new int[2];
        int[] pv = new int[MAX_PLY];
        int pvLength = 0;
        int excludedMove = 0; // For Singular Extensions
        // FIX: Added field to store previous META state for correct Null Move undo (EP restoration).
        long previousMeta = 0;
    }

    /* ── Root Search Results (for MultiPV) ────────── */
    private int multiPvCount;
    private final List<RootMoveInfo> rootMoves = new ArrayList<>();
    private SearchResult finalResult;

    // Helper class for tracking root moves, scores, and PVs
    private static class RootMoveInfo implements Comparable<RootMoveInfo> {
        int move;
        int score = -SCORE_INF;
        int depth;
        List<Integer> pv = new ArrayList<>();

        RootMoveInfo(int move) { this.move = move; }

        @Override
        public int compareTo(RootMoveInfo other) {
            // Sort descending by score
            return Integer.compare(other.score, this.score);
        }
    }


    public SearchWorkerImpl(boolean isMainThread, WorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        for (int i = 0; i < stack.length; ++i) {
            stack[i] = new SearchStackFrame();
        }
    }

    // --- Threading Implementation ---

    @Override
    public void run() {
        idleLoop();
    }

    // FIX: Added exception handling for robustness.
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
                    // This is the standard exit path when the application shuts down (e.g., UCI 'quit').
                    return;
                }
            } finally {
                mutex.unlock();
            }

            // Wrap search execution in try-catch to prevent the worker thread from crashing.
            try {
                if (isMainThread) {
                    mainThreadSearch();
                } else {
                    // Helper threads run the same search logic in Lazy SMP
                    search();
                }
            } catch (Throwable t) {
                // Log the error but keep the worker alive.
                System.err.println("Error during search in worker thread: " + Thread.currentThread().getName());
                t.printStackTrace();

                // Handle cleanup if the main thread crashed.
                if (isMainThread) {
                    pool.stopSearch(); // Stop helpers.
                    // Ensure helpers are finished before finalizing.
                    pool.waitForHelpersFinished();

                    // Attempt to finalize gracefully by salvaging results from previous iterations.
                    if (finalResult == null) {
                        finalizeResultFallback();
                    }
                    // Signal completion to the pool despite the error.
                    pool.finalizeSearch(finalResult);
                }
            }
        }
    }

    private void mainThreadSearch() {
        tt.incrementAge();
        pool.startHelpers();
        search();
        pool.waitForHelpersFinished();
        // The main thread is responsible for finalizing the search.
        // If search() throws, idleLoop handles finalization.
        if (finalResult != null) {
            pool.finalizeSearch(finalResult);
        }
    }

    // --- Search Initialization ---

    private void resetSearchState() {
        this.nodes = 0;
        this.ply = 0;
        this.rootDepth = 0;
        this.finalResult = null;
        this.multiPvCount = 1; // Default to 1 as MultiPV is not in SearchSpec
        this.maximumTimeMs = Long.MAX_VALUE / 2; // Default to infinite until calculated

        // Clear stack frames
        for (SearchStackFrame frame : stack) {
            frame.staticEval = SCORE_NONE;
            frame.playedMove = 0;
            Arrays.fill(frame.killers, 0);
            frame.pvLength = 0;
            frame.excludedMove = 0;
            frame.previousMeta = 0;
        }

        // Initialize NNUE accumulator for the root position
        nnue.refreshAccumulator(nnueState, rootBoard);

        // Initialize MoveOrderer.
        // Assuming MoveOrdererImpl exists and is compatible.
        this.moveOrderer = new MoveOrdererImpl(mainHistory);
    }

    // --- Iterative Deepening & MultiPV Loop (C++ Inspired Structure) ---

    // --- Iterative Deepening & MultiPV Loop (fixed to search ALL root moves) ---
    private void search() {
        resetSearchState();

        final long searchStartMs = pool.getSearchStartTime();
        final int maxDepth = spec.depth() > 0 ? spec.depth() : MAX_PLY - 1;

        // Calculate time limits (Centralized)
        long[] timeLimits = calculateTimeLimits();
        final long optimumTimeMs = timeLimits[0];
        maximumTimeMs = timeLimits[1]; // Hard limit

        // 1) Generate Root Moves
        generateRootMoves();

        if (rootMoves.isEmpty()) {
            // Handle mate/stalemate at root
            boolean inCheck = mg.kingAttacked(rootBoard, PositionFactory.whiteToMove(rootBoard[META]));
            int score = inCheck ? -SCORE_MATE : SCORE_STALEMATE;
            finalizeResult(0, 0, score, 0);
            return;
        }

        // Seed ordering (optional: keep existing order for the first iteration)
        Collections.sort(rootMoves);

        int searchStability = 0;

        // 2) Iterative Deepening
        for (int depth = 1; depth <= maxDepth; depth++) {
            if (pool.isStopped()) break;

            rootDepth = depth;

            // Remember previous best for stability accounting
            int bestMoveBeforeDepth = rootMoves.get(0).move;

            // Keep good ordering from previous iteration
            Collections.sort(rootMoves);

            // 3) Search ALL root moves at this depth
            for (int i = 0; i < rootMoves.size(); i++) {
                if (pool.isStopped()) break;

                RootMoveInfo m = rootMoves.get(i);

                // Reset PV frame before each root child
                stack[0].pvLength = 0;
                selDepth = 0;

                // Use your aspiration wrapper (widens until success)
                int score = searchRootMove(m, depth);
                if (pool.isStopped()) break;

                // Update this root move’s record
                m.score = score;
                m.depth = depth;

                // Capture PV from the root stack frame
                m.pv.clear();
                for (int k = 0; k < stack[0].pvLength; k++) {
                    m.pv.add(stack[0].pv[k]);
                }
            }

            if (pool.isStopped()) break;

            // 4) Sort by fresh scores and report MultiPV
            Collections.sort(rootMoves);
            reportInfo(depth, searchStartMs);

            // 5) Main-thread time & stability checks (soft stop)
            if (isMainThread) {
                RootMoveInfo best = rootMoves.get(0);

                // If we found a mating score, stop.
                if (best.score >= SCORE_MATE_IN_MAX_PLY) {
                    pool.stopSearch();
                    break;
                }

                // Stability bookkeeping
                int bestMoveAfterDepth = best.move;
                if (depth > 1 && bestMoveAfterDepth == bestMoveBeforeDepth) {
                    searchStability = Math.min(searchStability + 1, TM_MAX_STABILITY);
                } else {
                    searchStability = 0;
                }

                // Soft time management (after a few depths)
                if (depth >= 4 && shouldStopSearchSoft(searchStartMs, optimumTimeMs, searchStability)) {
                    pool.stopSearch();
                    break;
                }
            }
        }

        // 6) Finalize result (fallback ensures a valid move if possible)
        if (finalResult == null) {
            finalizeResultFallback();
        }
    }

    private void generateRootMoves() {
        rootMoves.clear();
        int[] moves = new int[MAX_MOVES];
        boolean inCheck = mg.kingAttacked(rootBoard, PositionFactory.whiteToMove(rootBoard[META]));
        int cnt;
        if (inCheck) {
            cnt = mg.generateEvasions(rootBoard, moves, 0);
        } else {
            cnt = mg.generateCaptures(rootBoard, moves, 0);          // noisy first
            cnt = mg.generateQuiets(rootBoard, moves, cnt);          // then quiets appended
        }

        for (int i = 0; i < cnt; i++) {
            int move = moves[i];
            // Legality check
            if (pf.makeMoveInPlace(rootBoard, move, mg)) {
                pf.undoMoveInPlace(rootBoard);
                rootMoves.add(new RootMoveInfo(move));
            }
        }
    }

    // Manages Aspiration Windows for a specific root move (C++ Style)
    private int searchRootMove(RootMoveInfo moveInfo, int depth) {
        int score;
        int window = ASP_WINDOW_INITIAL_SIZE;
        int aspirationScore = moveInfo.score; // Score from previous iteration

        // Use full window if depth is low or previous score is near mate
        if (depth < ASP_WINDOW_MIN_DEPTH || Math.abs(aspirationScore) >= SCORE_MATE_IN_MAX_PLY - 500) {
            return executeRootSearch(moveInfo.move, depth, -SCORE_INF, SCORE_INF);
        }

        int alpha = aspirationScore - window;
        int beta = aspirationScore + window;

        int failedHighCount = 0; // C++ reference tracks this

        while (true) {
            if (pool.isStopped()) return 0;

            // C++ style: reduce depth if failing high repeatedly
            int adjustedDepth = Math.max(1, depth - failedHighCount);

            score = executeRootSearch(moveInfo.move, adjustedDepth, alpha, beta);

            if (pool.isStopped()) return 0;

            // Handle immediate widening for mate scores
            if (Math.abs(score) >= SCORE_MATE_IN_MAX_PLY) {
                if (score > 0 && beta < SCORE_INF) {
                    beta = SCORE_INF;
                    failedHighCount = 0;
                    continue;
                }
                if (score < 0 && alpha > -SCORE_INF) {
                    alpha = -SCORE_INF;
                    failedHighCount = 0;
                    continue;
                }
            }

            if (score <= alpha) {
                // Fail-low
                beta = (alpha + beta) / 2;
                alpha = Math.max(-SCORE_INF, alpha - window);
                failedHighCount = 0;
            } else if (score >= beta) {
                // Fail-high
                beta = Math.min(SCORE_INF, beta + window);
                failedHighCount = Math.min(11, failedHighCount + 1); // C++ caps this count
            } else {
                // Success
                break;
            }

            // Increase window size (C++: windowSize += windowSize / 3)
            window += window / 3;
        }
        return score;
    }

    // Helper to execute the PVS search for a specific root move
    private int executeRootSearch(int rootMove, int depth, int alpha, int beta) {
        // In Lazy SMP, each worker needs its own copy of the board to search.
        long[] bb = rootBoard.clone();
        int capturedPiece = getCapturedPieceType(bb, rootMove);
        int moverPiece = ((rootMove >>> 16) & 0xF);

        // Make the move on the local board copy
        // Assumes root move is legal (checked during generation).
        pf.makeMoveInPlace(bb, rootMove, mg);
        // Update the worker's NNUE state incrementally
        nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, rootMove);

        // Setup search state
        ply = 1;
        searchPathHistory[0] = rootBoard[HASH]; // Store the root hash at ply 0 history
        stack[0].playedMove = 0;
        stack[1].playedMove = rootMove;

        // Call negaMax. Root children are PV nodes (cutNode=false).
        int score = -negaMax(bb, depth - 1, -beta, -alpha, false);

        // Update PV in the root stack frame (stack[0]) if successful and search wasn't stopped
        // We typically update the PV if the score is within the window (alpha < score < beta).
        if (score > alpha && score < beta && !pool.isStopped()) {
            stack[0].pv[0] = rootMove;
            System.arraycopy(stack[1].pv, 0, stack[0].pv, 1, stack[1].pvLength);
            stack[0].pvLength = stack[1].pvLength + 1;
        }

        // CRITICAL FIX: Undo the incremental NNUE update using the rootBoard context.
        // NNUE undo needs the board state corresponding to the position we are reverting TO (the root).
        // Using 'bb' (the position after the move) is incorrect because NNUEImpl relies on the board state for bucket detection (e.g., king position).
        nnue.undoNnueAccumulatorUpdate(nnueState, rootBoard, moverPiece, capturedPiece, rootMove);

        // Reset ply
        ply = 0;
        return score;
    }


    // --- Time Management Helpers (C++ Style, Centralized) ---

    /**
     * Calculates the time limits based on the Obsidian C++ reference logic.
     * @return An array: [optimumTimeMs, maximumTimeMs].
     */
    private long[] calculateTimeLimits() {
        // --- Configuration based on C++ reference (Obsidian timeman.cpp) ---
        final int MTG_HORIZON = 50;
        final double MAX_TIME_FACTOR = 0.8;
        final long overhead = TM_OVERHEAD_MS;

        long optimumTime = Long.MAX_VALUE / 2;
        long maximumTime = Long.MAX_VALUE / 2;

        // 1. Handle non-time-controlled searches.
        if (spec.infinite() || spec.ponder()) {
            return new long[]{optimumTime, maximumTime};
        }

        // 2. Handle fixed move time (e.g., "go movetime 3000")
        if (spec.moveTimeMs() > 0) {
            long time = Math.max(1, spec.moveTimeMs() - overhead);
            return new long[]{time, time};
        }

        // 3. Get time and increment
        boolean whiteToMove = PositionFactory.whiteToMove(rootBoard[META]);
        long myTime = whiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long myInc = whiteToMove ? spec.wIncMs() : spec.bIncMs();

        // 4. Handle searches where time is not the limiting factor or time has run out.
        if (myTime <= 0 && myInc <= 0) {
            if (spec.depth() > 0 || spec.nodes() > 0) {
                return new long[]{optimumTime, maximumTime};
            }
            return new long[]{1, 1}; // Force move if genuinely out of time
        }

        // 5. Handle very low time (Bullet safety)
        if (myTime <= overhead) {
            return new long[]{1, 1};
        }

        // --- Obsidian Time Management Logic Implementation ---

        // 6. Determine Moves To Go (mtg)
        int mtg;
        boolean suddenDeath = spec.movesToGo() == 0;

        if (!suddenDeath) {
            mtg = Math.min(spec.movesToGo(), MTG_HORIZON);
        } else {
            mtg = MTG_HORIZON;
        }

        // 7. Calculate effective time left
        // We use (2L + mtg) to ensure long arithmetic for overhead calculation.
        long timeLeft = myTime + myInc * (mtg - 1) - overhead * (2L + mtg);
        timeLeft = Math.max(1, timeLeft);

        // 8. Calculate Optimal Scaling Factor (optScale)
        double optScale;
        if (suddenDeath) {
            // C++ Logic: std::min(0.025, 0.214 * settings.time[us] / double(timeLeft));
            double factor = 0.214 * myTime / (double) timeLeft;
            optScale = Math.min(0.025, factor);
        } else {
            // C++ Logic: std::min(0.95 / mtg, 0.88 * settings.time[us] / double(timeLeft));
            double mtgFactor = 0.95 / mtg;
            double timeFactor = 0.88 * myTime / (double) timeLeft;
            optScale = Math.min(mtgFactor, timeFactor);
        }

        // 9. Calculate Optimum Time
        optimumTime = (long) (optScale * timeLeft);

        // 10. Calculate Maximum Time (Hard Limit)
        maximumTime = (long) (myTime * MAX_TIME_FACTOR) - overhead;
        maximumTime = Math.max(1, maximumTime);

        // Ensure optimum is not greater than maximum
        optimumTime = Math.min(optimumTime, maximumTime);

        return new long[]{optimumTime, maximumTime};
    }


    // C++ style stability based stop (Soft limit check in ID loop)
    private boolean shouldStopSearchSoft(long searchStartMs, long optimumTimeMs, int stability) {
        long elapsed = System.currentTimeMillis() - searchStartMs;

        // C++: double optScale = 1.1 - 0.05 * searchStability;
        double optScale = TM_STABILITY_BASE - TM_STABILITY_FACTOR * stability;
        long scaledOptimumTime = (long) (optimumTimeMs * optScale);

        if (elapsed > scaledOptimumTime) {
            return true;
        }

        return false;
    }

    /**
     * Checks if the hard time limit has been exceeded. (Used inside search)
     */
    private boolean isTimeUp() {
        if (maximumTimeMs >= Long.MAX_VALUE / 2) {
            return false; // Infinite time
        }
        long elapsed = System.currentTimeMillis() - pool.getSearchStartTime();
        return elapsed >= maximumTimeMs;
    }

    // --- NegaMax Search Implementation (Core Logic, C++ Inspired) ---

    /**
     * The main search function (PVS/NegaMax).
     * @param bb The current board state (modified in place during search).
     * @param cutNode True if this is a Cut-Node (often follows a reduced search).
     */
    private int negaMax(long[] bb, int depth, int alpha, int beta, boolean cutNode) {
        boolean isPvNode = (beta - alpha) > 1;

        // 1. Initialize node
        stack[ply].pvLength = 0;
        if (isPvNode && ply > selDepth) {
            selDepth = ply;
        }

        // Check time/stop conditions periodically
        if ((nodes & 2047) == 0) {
            // FIX: Allow all threads (not just main thread) to check time limit in Lazy SMP for responsiveness.
            if (pool.isStopped() || isTimeUp()) {
                // Ensure stop signal is propagated globally if time runs out.
                if (!pool.isStopped()) {
                    pool.stopSearch();
                }
                return 0;
            }
        }

        if (depth <= 0) {
            return quiescence(bb, alpha, beta);
        }

        // --- Preliminaries ---

        searchPathHistory[ply] = bb[HASH];
        nodes++;

        // Initialize next ply's state
        if (ply + 1 < stack.length) {
            stack[ply + 1].killers[0] = 0;
            stack[ply + 1].killers[1] = 0;
            stack[ply + 1].excludedMove = 0;
        }

        // Draw detection (Repetition and 50-move rule)
        // FIX: Do not check for repetitions immediately after a null move (stack[ply].playedMove == 0).
        // The check ply > 0 ensures we are not at the root.
        if (ply > 0 && stack[ply].playedMove != 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return SCORE_DRAW;
        }

        // Mate distance pruning
        if (ply > 0) {
            alpha = Math.max(alpha, ply - SCORE_MATE);
            beta = Math.min(beta, SCORE_MATE - ply - 1);
            if (alpha >= beta) return alpha;
        }

        if (ply >= MAX_PLY - 1) {
            return nnue.evaluateFromAccumulator(nnueState, bb);
        }

        // --- Transposition Table Probe ---

        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        boolean ttHit = tt.wasHit(ttIndex, key);
        int ttMove = 0;
        int ttScore = SCORE_NONE;
        int ttDepth = -1;
        int ttFlag = TranspositionTable.FLAG_NONE;
        int staticEval = SCORE_NONE;

        if (ttHit) {
            ttMove = tt.getMove(ttIndex);
            ttScore = tt.getScore(ttIndex, ply);
            ttDepth = tt.getDepth(ttIndex);
            ttFlag = tt.getBound(ttIndex);
            staticEval = tt.getStaticEval(ttIndex);

            // TT Cutoff
            if (!isPvNode && stack[ply].excludedMove == 0 && ttDepth >= depth) {
                if (ttFlag == TranspositionTable.FLAG_EXACT ||
                        (ttFlag == TranspositionTable.FLAG_LOWER && ttScore >= beta) ||
                        (ttFlag == TranspositionTable.FLAG_UPPER && ttScore <= alpha)) {
                    return ttScore;
                }
            }
        }

        // --- Evaluation and Pruning Setup ---

        // Optimization: Calculate this once per node.
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));

        // Check Extensions
        // FIX: Do not apply check extensions during singular verification search (excludedMove != 0).
        if (inCheck && ply > 0 && stack[ply].excludedMove == 0) {
            depth = Math.max(1, depth + 1);
        }

        // Calculate Static Evaluation (C++ structure)
        if (inCheck) {
            staticEval = SCORE_NONE;
            stack[ply].staticEval = staticEval;
        } else if (stack[ply].excludedMove != 0) {
            // If excluding a move (Singular Extensions), use previously calculated eval.
            staticEval = stack[ply].staticEval;
        }
        else {
            // Calculate static evaluation if not readily available from TT
            if (staticEval == SCORE_NONE) {
                staticEval = nnue.evaluateFromAccumulator(nnueState, bb);
            }
            stack[ply].staticEval = staticEval;

            // C++: Use TT score if it provides a better bound than static eval
            if (ttHit) {
                if ((ttFlag == TranspositionTable.FLAG_LOWER && ttScore > staticEval) ||
                        (ttFlag == TranspositionTable.FLAG_UPPER && ttScore < staticEval)) {
                    staticEval = ttScore;
                }
            }
        }

        // Improving heuristic (C++ reference: checks 2 and 4 plies ago)
        boolean improving = false;
        if (!inCheck && ply >= 2 && stack[ply].staticEval != SCORE_NONE) {
            int prevEval = stack[ply - 2].staticEval;
            if (prevEval != SCORE_NONE) {
                improving = stack[ply].staticEval > prevEval;
            } else if (ply >= 4) {
                int prevPrevEval = stack[ply-4].staticEval;
                if (prevPrevEval != SCORE_NONE) {
                    improving = stack[ply].staticEval > prevPrevEval;
                }
            }
        }


        // --- Pruning Techniques (if not in check) ---

        if (!inCheck && staticEval != SCORE_NONE) {

            // 1. Razoring (C++ feature)
            if (!isPvNode && depth <= RAZORING_MAX_DEPTH && staticEval <= alpha - RAZORING_MARGIN * depth) {
                int value = quiescence(bb, alpha - 1, alpha);
                if (value < alpha) {
                    return value;
                }
            }

            // 2. Reverse Futility Pruning (RFP) (C++ style)
            if (!isPvNode && depth <= RFP_MAX_DEPTH && Math.abs(staticEval) < SCORE_TB_WIN_IN_MAX_PLY) {
                // C++ logic: eval + 120 * improving - 140 * depth >= beta
                int margin = RFP_BASE_MARGIN * depth - (improving ? RFP_IMPROVING_BONUS : 0);
                if (staticEval - margin >= beta) {
                    return staticEval; // Return static eval as a safe lower bound
                }
            }

            // 3. Null Move Pruning (NMP) (C++ style)
            // Condition stack[ply-1].playedMove != 0 prevents consecutive null moves.
            if (!isPvNode && depth >= NMP_MIN_DEPTH && stack[ply].excludedMove == 0 && ply > 0 && stack[ply-1].playedMove != 0 &&
                    staticEval >= beta && pf.hasNonPawnMaterial(bb) && beta > -SCORE_MATE_IN_MAX_PLY) {

                // C++ Adaptive R calculation
                int R = NMP_DEPTH_BASE + depth / NMP_DEPTH_DIVISOR;
                int eval_bonus = (staticEval - beta) / NMP_EVAL_DIVISOR;
                R += Math.min(eval_bonus, NMP_EVAL_CAP);

                int nmpDepth = depth - R;

                // This now calls the corrected makeNullMove implementation.
                if (makeNullMove(bb)) {
                    // Search with inverted cutNode status
                    int nullScore = -negaMax(bb, nmpDepth, -beta, -beta + 1, !cutNode);
                    // This now calls the corrected undoNullMove implementation.
                    undoNullMove(bb);

                    if (pool.isStopped()) return 0;

                    // C++ verification condition: abs(nullValue) < VALUE_TB_WIN_IN_MAX_PLY
                    if (nullScore >= beta && Math.abs(nullScore) < SCORE_TB_WIN_IN_MAX_PLY) {
                        return nullScore;
                    }
                }
            }
        }

        // --- Internal Iterative Reduction (IIR) ---
        // C++ logic: if (cutNode && depth >= 4 && !ttMove) depth -= 2;
        if (cutNode && depth >= IIR_MIN_DEPTH && ttMove == 0) {
            depth -= IIR_REDUCTION_CUTNODE;
            depth = Math.max(1, depth);
        }

        // --- Singular Extensions (C++ implementation) ---
        int extension = 0;
        // Check if the TT move is singular (significantly better than others).
        if (isPvNode && depth >= SINGULAR_MIN_DEPTH && stack[ply].excludedMove == 0 && ttMove != 0 &&
                ttHit && (ttFlag == TranspositionTable.FLAG_LOWER || ttFlag == TranspositionTable.FLAG_EXACT) &&
                ttDepth >= depth - SINGULAR_TT_DEPTH_MARGIN &&
                Math.abs(ttScore) < SCORE_TB_WIN_IN_MAX_PLY) {

            // C++ Beta: ttValue - depth
            int singularBeta = ttScore - depth;
            int singularDepth = (depth - 1) / 2;

            // Perform a verification search at reduced depth, excluding the TT move
            stack[ply].excludedMove = ttMove;

            // The recursive call operates on the same board state 'bb'.
            int singularScore = negaMax(bb, singularDepth, singularBeta - 1, singularBeta, cutNode);

            stack[ply].excludedMove = 0; // Reset excluded move

            // If the score without the TT move is less than singularBeta, the TT move is singular.
            if (singularScore < singularBeta) {
                extension = 1;
            }
            // Optimization: If verification failed high (singularBeta >= beta), we might have a cutoff already
            else if (singularBeta >= beta) {
                return singularBeta;
            }
        }


        // --- Move Generation and Ordering ---
        int[] list = new int[MAX_MOVES];
        int nMoves;

        // 1. Decide which move list to generate depending on check state
        if (inCheck) {
            nMoves = mg.generateEvasions(bb, list, 0);
        } else {
            nMoves = mg.generateCaptures(bb, list, 0);          // noisy first
            nMoves = mg.generateQuiets(bb, list, nMoves);          // then quiets appended
        }

        // Score moves (Relies on MoveOrderer implementation)
        scoreMoves(bb, list, nMoves, ttMove);

        // --- Main Search Loop ---

        int bestScore = -SCORE_INF;
        int bestMove = 0;
        int originalAlpha = alpha;
        int legalMovesFound = 0;
        boolean skipQuiets = false;

        // Track quiet moves for history updates
        int[] quietMoves = new int[MAX_MOVES];
        int quietMovesCount = 0;

        for (int i = 0; i < nMoves; i++) {
            // Pick next best move (assuming MoveOrderer sorted the list)
            int move = nextBestMove(list, i, nMoves);

            if (move == stack[ply].excludedMove) continue;

            // --- Legality Check and Move Info ---
            int capturedPiece = getCapturedPieceType(bb, move);
            int moverPiece = ((move >>> 16) & 0xF);
            boolean isQuiet = (capturedPiece == -1) && (((move >>> 14) & 0x3) != 1); // Not capture and not promotion

            // --- Pruning during the loop ---

            if (isQuiet) {
                if (skipQuiets) {
                    continue;
                }
            }

            // C++ pruning logic (LMP and SEE Pruning)
            if (ply > 0 && pf.hasNonPawnMaterial(bb) && bestScore > -SCORE_MATE_IN_MAX_PLY) {

                // 1. Late Move Pruning (LMP) (C++ style)
                if (isQuiet && !inCheck) {
                    // C++ formula: (3 * depth * depth + 9) / (2 - improving)
                    int denominator = improving ? 1 : 2;
                    int lmpLimit = (LMP_DEPTH_FACTOR * depth * depth + LMP_BASE) / denominator;
                    if (i >= lmpLimit) {
                        skipQuiets = true;
                        continue;
                    }
                }

                // 2. SEE Pruning (C++ style)
                if (!isQuiet) {
                    // C++: if (!position.see_ge(move, Value(-140 * depth)))
                    int seeThreshold = SEE_PRUNING_TACTICAL_MARGIN * depth;
                    if (moveOrderer.see(bb, move) < seeThreshold) {
                        continue;
                    }
                }
            }

            // --- Make Move ---
            // Legality check is implicitly handled here (makeMoveInPlace returns false if illegal).
            if (!pf.makeMoveInPlace(bb, move, mg)) continue;

            legalMovesFound++;

            if (isQuiet && quietMovesCount < MAX_MOVES) {
                quietMoves[quietMovesCount++] = move;
            }

            // --- Search the move ---
            // Extension value determined earlier (Singular/Check extensions)
            int newDepth = depth + extension - 1;

            // Update NNUE incrementally and update ply
            nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, move);
            ply++;
            stack[ply].playedMove = move;

            int score = 0;

            // --- Late Move Reductions (LMR) and PVS (C++ style) ---

            boolean needFullSearch;
            // C++: if (!wasInCheck && depth >= 3 && playedMoves > (1 + 2 * PvNode))
            int lmrMinMoves = isPvNode ? LMR_MIN_MOVE_COUNT_PV : LMR_MIN_MOVE_COUNT_NON_PV;

            if (!inCheck && depth >= LMR_MIN_DEPTH && legalMovesFound > lmrMinMoves) {
                // Calculate reduction R
                int R = LMR_TABLE[Math.min(depth, MAX_PLY-1)][Math.min(legalMovesFound, MAX_MOVES-1)];

                // Adjust R based on heuristics (C++ style)
                if (!improving) R++;
                if (isPvNode) R--;
                if (cutNode) R++;

                // Clamp R
                R = Math.max(0, R);
                int reducedDepth = Math.max(1, newDepth - R);

                // Reduced depth search (ZWS), child is a cut node
                score = -negaMax(bb, reducedDepth, -alpha - 1, -alpha, true);

                needFullSearch = score > alpha && reducedDepth < newDepth;
            } else {
                // C++: needFullSearch = !PvNode || playedMoves >= 1;
                needFullSearch = !isPvNode || legalMovesFound > 1;
            }

            // Full depth search (ZWS if needed), child cutNode status inverted
            if (needFullSearch) {
                score = -negaMax(bb, newDepth, -alpha - 1, -alpha, !cutNode);
            }

            // Principal Variation Search (PVS)
            // If PV node AND (first move OR score is inside the window)
            if (isPvNode && (legalMovesFound == 1 || (score > alpha && score < beta))) {
                // Full window search, child is not a cut node
                score = -negaMax(bb, newDepth, -beta, -alpha, false);
            }

            // Undo move
            ply--;
            // CRITICAL: Ensure board state is restored BEFORE calling NNUE undo.
            // NNUEImpl relies on the board state (bb) for bucketing (e.g., king position).
            pf.undoMoveInPlace(bb);
            // Undo NNUE update incrementally using the restored 'bb'.
            nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, move);

            if (pool.isStopped()) return 0;

            // --- Update Bounds and PV ---

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;

                if (score > alpha) {
                    // PVS refinement: Only update alpha if score < beta (not a fail-high in PV nodes).
                    if (!isPvNode || score < beta) {
                        alpha = score;
                    }

                    if (isPvNode) {
                        updatePV(move);
                    }

                    if (score >= beta) {
                        // Beta cutoff
                        break;
                    }
                }
            }
        }

        // --- Post-Loop Processing ---

        // Checkmate/Stalemate detection
        if (legalMovesFound == 0) {
            if (stack[ply].excludedMove != 0) {
                return alpha; // In singular search, if no other move found, return the bound.
            }
            return inCheck ? -(SCORE_MATE - ply) : SCORE_STALEMATE;
        }

        // Update Heuristics (History, Killers, CounterMove)
        if (bestScore >= beta && bestMove != 0) {
            // Recheck quiet status
            if (isQuietMove(bb, bestMove)) {
                updateHistories(depth, bestMove, bestScore, beta, quietMoves, quietMovesCount);
            }
        }

        // Transposition Table Store
        if (stack[ply].excludedMove == 0) {
            int flag;
            if (bestScore >= beta) {
                flag = TranspositionTable.FLAG_LOWER;
            } else if (bestScore > originalAlpha) {
                // Store EXACT if alpha improved (PV or Non-PV)
                flag = TranspositionTable.FLAG_EXACT;
            } else {
                flag = TranspositionTable.FLAG_UPPER;
            }

            // Store static eval (if calculated)
            int evalToStore = stack[ply].staticEval;
            tt.store(ttIndex, key, flag, depth, bestMove, bestScore, evalToStore, isPvNode, ply);
        }

        return bestScore;
    }

    // --- Quiescence Search Implementation (C++ Inspired) ---

    private int quiescence(long[] bb, int alpha, int beta) {
        boolean isPvNode = (beta - alpha) > 1;

        if (isPvNode && ply > selDepth) {
            selDepth = ply;
        }

        // Check time/stop conditions
        if ((nodes & 2047) == 0) {
            // FIX: Allow all threads (not just main thread) to check time limit in Lazy SMP.
            if (pool.isStopped() || isTimeUp()) {
                // Ensure stop signal is propagated globally.
                if (!pool.isStopped()) {
                    pool.stopSearch();
                }
                return 0;
            }
        }
        nodes++;

        if (PositionFactory.halfClock(bb[META]) >= 100) {
            return SCORE_DRAW;
        }

        if (ply >= MAX_PLY - 1) {
            return nnue.evaluateFromAccumulator(nnueState, bb);
        }

        // --- TT Probe ---
        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        boolean ttHit = tt.wasHit(ttIndex, key);
        int ttMove = 0;
        int ttScore = SCORE_NONE;
        int ttFlag = TranspositionTable.FLAG_NONE;

        if (ttHit) {
            ttMove = tt.getMove(ttIndex);
            ttScore = tt.getScore(ttIndex, ply);
            ttFlag = tt.getBound(ttIndex);

            // TT Cutoff (C++ only does this in NonPV nodes in QSearch)
            if (!isPvNode) {
                if ((ttFlag == TranspositionTable.FLAG_LOWER && ttScore >= beta) ||
                        (ttFlag == TranspositionTable.FLAG_UPPER && ttScore <= alpha) ||
                        ttFlag == TranspositionTable.FLAG_EXACT) {
                    return ttScore;
                }
            }
        }

        // --- Evaluation and Stand Pat ---
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        int staticEval = SCORE_NONE;
        int bestScore;

        if (inCheck) {
            bestScore = -SCORE_INF;
            stack[ply].staticEval = SCORE_NONE;
        } else {
            // Calculate static evaluation
            if (ttHit) {
                staticEval = tt.getStaticEval(ttIndex);
            }
            if (staticEval == SCORE_NONE) {
                staticEval = nnue.evaluateFromAccumulator(nnueState, bb);
            }
            stack[ply].staticEval = staticEval;
            bestScore = staticEval;

            // C++: Use TT score if it provides a better bound
            if (ttHit) {
                if ((ttFlag == TranspositionTable.FLAG_LOWER && ttScore > bestScore) ||
                        (ttFlag == TranspositionTable.FLAG_UPPER && ttScore < bestScore)) {
                    bestScore = ttScore;
                }
            }

            // Stand Pat
            if (bestScore >= beta) {
                return bestScore;
            }
            if (bestScore > alpha) {
                alpha = bestScore;
            }
        }

        // --- Move Generation and Search Loop ---

        int[] list = new int[MAX_MOVES];
        int nMoves;

        // 1. Decide which move list to generate depending on check state
        if (inCheck) {
            nMoves = mg.generateEvasions(bb, list, 0);
        } else {
            // Only captures/promotions. Do not generate quiets here.
            nMoves = mg.generateCaptures(bb, list, 0);
        }

        // Score moves
        scoreMoves(bb, list, nMoves, ttMove);

        int bestMove = 0;
        int originalAlpha = alpha;
        int legalMovesFound = 0;

        for (int i = 0; i < nMoves; i++) {
            int move = nextBestMove(list, i, nMoves);

            // --- Pruning in QSearch (C++ style SEE pruning) ---
            if (!inCheck && bestScore > -SCORE_MATE_IN_MAX_PLY) {
                // C++: if (!position.see_ge(move, Value(-50)))
                if (moveOrderer.see(bb, move) < SEE_QSEARCH_MARGIN) {
                    continue;
                }
            }

            // Make Move
            int capturedPiece = getCapturedPieceType(bb, move);
            int moverPiece = ((move >>> 16) & 0xF);

            if (!pf.makeMoveInPlace(bb, move, mg)) continue;
            legalMovesFound++;

            // Update NNUE incrementally
            nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, move);
            ply++;

            // Recursive QSearch call
            int score = -quiescence(bb, -beta, -alpha);

            // Undo Move
            ply--;
            // CRITICAL: Ensure board state is restored BEFORE calling NNUE undo.
            pf.undoMoveInPlace(bb);
            nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, move);

            if (pool.isStopped()) return 0;

            // Update Bounds
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;

                if (score > alpha) {
                    alpha = score;

                    if (score >= beta) {
                        break;
                    }
                }
            }
        }

        // Checkmate detection in QSearch
        if (inCheck && legalMovesFound == 0) {
            return -(SCORE_MATE - ply);
        }

        // TT Store
        int flag;
        if (bestScore >= beta) {
            flag = TranspositionTable.FLAG_LOWER;
        } else if (bestScore > originalAlpha) {
            flag = TranspositionTable.FLAG_EXACT;
        } else {
            flag = TranspositionTable.FLAG_UPPER;
        }

        // Depth 0 for QSearch entries
        tt.store(ttIndex, key, flag, 0, bestMove, bestScore, stack[ply].staticEval, isPvNode, ply);

        return bestScore;
    }


    // --- Helper Functions (Move Ordering, History, Utilities) ---

    // Relies on MoveOrderer implementation.
    private void scoreMoves(long[] bb, int[] moves, int count, int ttMove) {
        int[] killers = stack[ply].killers;
        // The MoveOrderer implementation (MoveOrdererImpl) should utilize the heuristics.
        moveOrderer.orderMoves(bb, moves, count, ttMove, killers);
    }

    // Assumes MoveOrderer.orderMoves performed a full sort.
    private int nextBestMove(int[] moves, int index, int count) {
        return moves[index];
    }

    // --- History Updates (C++ style) ---

    // C++: stat_bonus(int d) -> std::min(2 * d * d + 16 * d, 1000);
    private int calculateHistoryBonus(int depth) {
        int bonus = 2 * depth * depth + 16 * depth;
        return Math.min(bonus, HISTORY_BONUS_CAP);
    }

    // Exponential decay update (C++ addToHistory implementation)
    private void updateHistoryScore(int[][] historyTable, int idx1, int idx2, int delta) {
        int current = historyTable[idx1][idx2];
        // new = old + delta - (old * abs(delta)) / HISTORY_MAX
        // Use long for intermediate multiplication to prevent overflow
        historyTable[idx1][idx2] += delta - (int)(((long) current * Math.abs(delta)) / HISTORY_MAX);
    }

    // Updates all history tables and killers upon a beta cutoff
    private void updateHistories(int depth, int bestMove, int bestValue, int beta, int[] quietMoves, int quietCount) {
        // C++: Increased bonus if the move caused significant improvement over beta
        int bonusDepth = (bestValue > beta + 110) ? depth + 1 : depth;
        int bonus = calculateHistoryBonus(bonusDepth);
        int malus = -bonus;

        int bestFrom = (bestMove >>> 6) & 0x3F;
        int bestTo = bestMove & 0x3F;
        int bestPieceType = (bestMove >>> 16) & 0xF;
        int bestPieceToIndex = pieceToIndex(bestPieceType, bestTo);

        // 1. Main History (Butterfly)
        updateHistoryScore(mainHistory, bestFrom, bestTo, bonus);

        // 2. Continuation History
        int prevPieceToIndex = -1;
        int prevPrevPieceToIndex = -1;

        if (ply > 0) {
            int prevMove = stack[ply-1].playedMove;
            if (prevMove != 0) { // Check it wasn't a null move
                int prevPieceType = (prevMove >>> 16) & 0xF;
                int prevTo = prevMove & 0x3F;
                prevPieceToIndex = pieceToIndex(prevPieceType, prevTo);
                updateHistoryScore(contHistory, prevPieceToIndex, bestPieceToIndex, bonus);
            }
        }
        if (ply > 1) {
            int prevPrevMove = stack[ply-2].playedMove;
            if (prevPrevMove != 0) {
                int prevPrevPieceType = (prevPrevMove >>> 16) & 0xF;
                int prevPrevTo = prevPrevMove & 0x3F;
                prevPrevPieceToIndex = pieceToIndex(prevPrevPieceType, prevPrevTo);
                updateHistoryScore(contHistory, prevPrevPieceToIndex, bestPieceToIndex, bonus);
            }
        }

        // 3. Malus for other quiet moves
        for (int i = 0; i < quietCount; i++) {
            int otherMove = quietMoves[i];
            if (otherMove == bestMove) continue;

            int otherFrom = (otherMove >>> 6) & 0x3F;
            int otherTo = otherMove & 0x3F;
            int otherPieceType = (otherMove >>> 16) & 0xF;
            int otherPieceToIndex = pieceToIndex(otherPieceType, otherTo);

            // Main history malus
            updateHistoryScore(mainHistory, otherFrom, otherTo, malus);

            // Continuation history malus
            if (prevPieceToIndex != -1) {
                updateHistoryScore(contHistory, prevPieceToIndex, otherPieceToIndex, malus);
            }
            if (prevPrevPieceToIndex != -1) {
                updateHistoryScore(contHistory, prevPrevPieceToIndex, otherPieceToIndex, malus);
            }
        }

        // 4. Counter Move History
        if (ply > 0) {
            int prevMove = stack[ply-1].playedMove;
            if (prevMove != 0) {
                // The piece type that made the previous move.
                int prevPieceType = (prevMove >>> 16) & 0xF;
                int prevTo = prevMove & 0x3F;
                // Store the move that refuted the previous move.
                if (prevPieceType >= 0 && prevPieceType < 12) {
                    counterMoveHistory[prevPieceType][prevTo] = bestMove;
                }
            }
        }

        // 5. Killers
        if (bestMove != stack[ply].killers[0]) {
            stack[ply].killers[1] = stack[ply].killers[0];
            stack[ply].killers[0] = bestMove;
        }
    }

    // Helper to calculate index for continuation/counter move history
    private int pieceToIndex(int pieceType, int square) {
        // Assumes pieceType is indexed 0-11.
        if (pieceType < 0 || pieceType >= 12) return 0; // Safety check
        return pieceType * 64 + square;
    }


    // --- Utility Functions (Repetition, Move/Piece Info, Null Move) ---

    private boolean isRepetitionDraw(long[] bb, int ply) {
        // Implementation identical to original Java code, which correctly handles search path and game history.
        final long currentHash = bb[HASH];
        final int halfmoveClock = (int) PositionFactory.halfClock(bb[META]);

        // Iterate backwards from the previous position with the same side to move
        for (int i = 2; i <= halfmoveClock; i += 2) {
            int prevPly = ply - i;
            long previousHash;

            if (prevPly < 0) {
                // Look in gameHistory.
                int gameHistoryIdx = gameHistory.size() + prevPly;
                if (gameHistoryIdx >= 0) {
                    previousHash = gameHistory.get(gameHistoryIdx);
                } else {
                    break;
                }
            } else {
                // Within the current search path (including root at index 0).
                previousHash = searchPathHistory[prevPly];
            }

            if (previousHash == currentHash) {
                return true; // Repetition found
            }
        }
        return false;
    }

    private int getCapturedPieceType(long[] bb, int move) {
        // Implementation identical to original Java code.
        int to = move & 0x3F;
        long toBit = 1L << to;
        int moveType = (move >>> 14) & 0x3;
        // Assumes piece encoding: 0-5 White, 6-11 Black.
        boolean isWhiteMover = (((move >>> 16) & 0xF) < 6);

        if (moveType == 2) { // En-passant
            return isWhiteMover ? PositionFactory.BP : PositionFactory.WP;
        }

        int startP = isWhiteMover ? PositionFactory.BP : PositionFactory.WP;
        int endP = isWhiteMover ? PositionFactory.BK : PositionFactory.WK;

        for (int p = startP; p <= endP; p++) {
            if ((bb[p] & toBit) != 0) {
                return p;
            }
        }
        return -1; // No capture
    }

    private boolean isQuietMove(long[] bb, int move) {
        // Check if capture or promotion
        return getCapturedPieceType(bb, move) == -1 && (((move >>> 14) & 0x3) != 1);
    }

    private void updatePV(int move) {
        stack[ply].pv[0] = move;
        System.arraycopy(stack[ply + 1].pv, 0, stack[ply].pv, 1, stack[ply + 1].pvLength);
        stack[ply].pvLength = stack[ply + 1].pvLength + 1;
    }

    /**
     * FIX: Corrected implementation of makeNullMove.
     * Updates Zobrist hash (STM and EP) and clears the En Passant square, storing the previous state for undo.
     */
    private boolean makeNullMove(long[] bb) {
        long oldMeta = bb[META];
        // Flip STM
        long newMeta = oldMeta ^ STM_MASK;

        // Update Zobrist hash for side to move change.
        // Relies on PositionFactoryImpl exposing SIDE_TO_MOVE.
        long hashChange = PositionFactoryImpl.SIDE_TO_MOVE;

        // Handle EP clear. A null move must clear the EP square.
        int oldEP = (int) ((oldMeta & EP_MASK) >>> EP_SHIFT);
        if (oldEP != EP_NONE) {
            // Clear EP in newMeta.
            newMeta = (newMeta & ~EP_MASK) | ((long)EP_NONE << EP_SHIFT);

            // Update Hash for EP change (XOR out the old EP key).
            // Requires access to EP_FILE from PositionFactoryImpl.
            hashChange ^= PositionFactoryImpl.EP_FILE[oldEP & 7];
        }

        bb[META] = newMeta;
        bb[HASH] ^= hashChange;

        ply++;
        stack[ply].playedMove = 0; // Indicate Null Move
        // Store old META for undo in the new stack frame.
        stack[ply].previousMeta = oldMeta;

        // NNUE state does not change during a null move.
        return true;
    }

    /**
     * FIX: Corrected implementation of undoNullMove.
     * Restores the board state (META and HASH) exactly as it was before the null move.
     */
    private void undoNullMove(long[] bb) {
        // Restore META from the stack frame.
        long oldMeta = stack[ply].previousMeta;

        // Recalculate the hash change to restore HASH.
        long hashChange = PositionFactoryImpl.SIDE_TO_MOVE;
        int oldEP = (int) ((oldMeta & EP_MASK) >>> EP_SHIFT);
        if (oldEP != EP_NONE) {
            // XOR back in the old EP key.
            hashChange ^= PositionFactoryImpl.EP_FILE[oldEP & 7];
        }

        bb[HASH] ^= hashChange;
        bb[META] = oldMeta;

        ply--;
    }


    // --- Reporting and Finalization ---

    private void reportInfo(int depth, long searchStartMs) {
        if (!isMainThread || ih == null) return;

        long elapsedMs = System.currentTimeMillis() - searchStartMs;
        // Ensure elapsedMs is at least 1 to prevent division by zero.
        elapsedMs = Math.max(1, elapsedMs);

        long totalNodes = pool.totalNodes();
        long nps = (totalNodes * 1000) / elapsedMs;
        int hashfull = tt.hashfull();

        // Report info for each PV line (MultiPV)
        for (int i = 0; i < multiPvCount && i < rootMoves.size(); i++) {
            RootMoveInfo info = rootMoves.get(i);
            // Only report if the info corresponds to the completed depth
            if (info.depth != depth) continue;

            int score = info.score;
            boolean mateScore = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY;

            // Use the global selDepth primarily for the main PV line (index 0).
            int reportSelDepth = (i == 0) ? selDepth : depth;

            ih.onInfo(new SearchInfo(
                    depth, reportSelDepth, i + 1, score, mateScore, totalNodes,
                    nps, elapsedMs, info.pv, hashfull, 0));
        }
    }

    private void finalizeResult(int bestMove, int ponderMove, int score, int depth) {
        boolean mateScore = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY;
        List<Integer> pv = new ArrayList<>();
        // Ensure the PV list matches the best move reported
        if (!rootMoves.isEmpty() && bestMove != 0) {
            // Robustness: Find the matching RootMoveInfo instead of assuming index 0,
            // although finalizeResultFallback should ensure index 0 is the best if called correctly.
            for (RootMoveInfo info : rootMoves) {
                if (info.move == bestMove) {
                    pv = info.pv;
                    break;
                }
            }
        }

        finalResult = new SearchResult(bestMove, ponderMove, pv, score, mateScore, depth, pool.totalNodes(), System.currentTimeMillis() - pool.getSearchStartTime());
    }

    // Helper for finalizing results during normal termination or error recovery.
    private void finalizeResultFallback() {
        if (!rootMoves.isEmpty()) {
            // Ensure sorting (robustness fix).
            try {
                Collections.sort(rootMoves);
            } catch (Exception e) {
                // Ignore sorting errors during recovery, proceed with potentially unsorted list
                System.err.println("Warning: Could not sort root moves during fallback finalization.");
            }

            RootMoveInfo best = rootMoves.get(0);
            // Ensure the best move is valid before reporting (it might be 0 if search stopped very early)
            if (best.move != 0) {
                int ponderMove = best.pv.size() > 1 ? best.pv.get(1) : 0;
                finalizeResult(best.move, ponderMove, best.score, best.depth);
                return;
            }
        }

        // Absolute fallback if no valid move found
        finalizeResult(0, 0, 0, 0);
    }


    // --- Interface Implementation and Thread Control (Identical to original Java) ---

    @Override
    public void prepareForSearch(long[] root, SearchSpec s, PositionFactory p, MoveGenerator m, TranspositionTable t) {
        this.rootBoard = root.clone();
        this.spec = s;
        this.pf = p;
        this.mg = m;
        this.tt = t;
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
        return finalResult;
    }
    @Override public long getNodes() { return nodes; }

    // This method is crucial for fixing the application hang reported in the logs.
    // The application must call this (e.g., via the WorkerPool) when shutting down.
    @Override public void terminate() {
        mutex.lock();
        try {
            quit = true;
            searching = true; // Wake up the thread if it's waiting
            startCondition.signal();
        } finally {
            mutex.unlock();
        }
    }
    @Override public void join() throws InterruptedException { /* Handled by pool */ }
}