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
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static core.constants.CoreConstants.*;
import static core.contracts.PositionFactory.*;
import static core.impl.PositionFactoryImpl.PIECE_SQUARE;

/**
 * Advanced SearchWorker implementation translated from a high-Elo C++ engine.
 */
public final class SearchWorkerImpl implements Runnable, SearchWorker {

    // ========================================================================================
    // Constants and Tuning Parameters (Imported from C++ reference)
    // ========================================================================================

    // Time management
    private static final float TM_INITIAL_ADJUSTMENT = 1.0151f;
    private static final int TM_BEST_MOVE_STABILITY_MAX = 18;
    private static final float TM_BEST_MOVE_STABILITY_BASE = 1.4117f;
    private static final float TM_BEST_MOVE_STABILITY_FACTOR = 0.0470f;
    private static final float TM_EVAL_DIFF_BASE = 0.9010f;
    private static final float TM_EVAL_DIFF_FACTOR = 0.0082f;
    private static final int TM_EVAL_DIFF_MIN = -16;
    private static final int TM_EVAL_DIFF_MAX = 58;
    private static final float TM_NODES_BASE = 1.7139f;
    private static final float TM_NODES_FACTOR = 0.8519f;

    // Aspiration windows
    private static final int ASP_MIN_DEPTH = 4;
    private static final int ASP_DELTA = 17;
    private static final int ASP_MAX_FAIL_HIGHS = 3;
    private static final float ASP_DELTA_FACTOR = 1.7892f;

    // LMR Formulas
    private static final float LMR_REDUCTION_NOISY_BASE = -0.5507f;
    private static final float LMR_REDUCTION_NOISY_FACTOR = 2.9945f;
    private static final float LMR_REDUCTION_QUIET_BASE = 0.7842f;
    private static final float LMR_REDUCTION_QUIET_FACTOR = 2.8063f;
    private static final int LMR_MC_BASE = 2;
    private static final int LMR_MC_PV = 2;
    private static final int LMR_MIN_DEPTH = 3;
    private static final int LMR_HISTORY_FACTOR_QUIET = 13199;
    private static final int LMR_HISTORY_FACTOR_CAPTURE = 13199;
    // Deeper/Shallower search adjustments
    private static final int LMR_DEEPER_BASE = 43;
    private static final int LMR_DEEPER_FACTOR = 2;


    // SEE Pruning
    private static final float SEE_MARGIN_NOISY = -26.146f;
    private static final float SEE_MARGIN_QUIET = -75.367f;
    private static final int SEE_DEPTH = 9;

    // LMP Formulas
    private static final float LMP_MARGIN_WORSENING_BASE = 1.4194f;
    private static final float LMP_MARGIN_WORSENING_FACTOR = 0.4609f;
    private static final float LMP_MARGIN_WORSENING_POWER = 1.7857f;
    private static final float LMP_MARGIN_IMPROVING_BASE = 3.0963f;
    private static final float LMP_MARGIN_IMPROVING_FACTOR = 1.0584f;
    private static final float LMP_MARGIN_IMPROVING_POWER = 1.8914f;

    // QSearch
    private static final int QS_FUTILITY_OFFSET = 49;

    // Pre-search pruning
    private static final int IIR_MIN_DEPTH = 4;
    private static final int RFP_DEPTH = 8;
    private static final int RFP_FACTOR = 84;
    private static final int RAZORING_DEPTH = 5;
    private static final int RAZORING_FACTOR = 310;

    // NMP
    private static final int NMP_RED_BASE = 3;
    private static final int NMP_DEPTH_DIV = 3;
    private static final int NMP_MIN = 3;
    private static final int NMP_DIVISOR = 183;

    // In-search pruning
    private static final int FP_DEPTH = 11;
    private static final int FP_BASE = 240;
    private static final int FP_FACTOR = 132;
    private static final int HISTORY_PRUNING_DEPTH = 4;
    private static final int HISTORY_PRUNING_FACTOR = -2096;

    // Singular Extensions
    private static final int SINGULAR_MIN_DEPTH = 7;
    private static final int DOUBLE_EXTENSION_MARGIN = 15;
    private static final int DOUBLE_EXTENSION_LIMIT = 12;

    // ProbCut
    private static final int PROBCUT_MARGIN = 215;
    private static final int PROBCUT_MIN_DEPTH = 5;
    private static final int PROBCUT_REDUCTION = 4;

    // History Updates
    private static final int HISTORY_BONUS_BASE = 0;
    private static final int HISTORY_BONUS_FACTOR = 160;
    private static final int HISTORY_BONUS_MAX = 1757;
    private static final int HISTORY_MAX = 32000; // Scaling factor for main history

    // Correction History
    private static final int CORRECTION_HISTORY_SIZE = 16384;
    private static final int CORRECTION_HISTORY_LIMIT = 1024; // Scaling factor for correction history

    // Indexing constants
    private static final int TACTICAL = 0;
    private static final int QUIET = 1;
    private static final int WORSENING = 0;
    private static final int IMPROVING = 1;

    // ========================================================================================
    // Fields and Data Structures
    // ========================================================================================

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
    private InfoHandler ih;
    private TranspositionTable tt;
    // MoveOrderer is primarily used for SEE calculations now; ordering happens in search.
    private MoveOrderer moveOrderer;

    /* ── NNUE ────────── */
    private final NNUEState nnueState = new NNUEState();
    private final NNUE nnue = new NNUEImpl(); // Assuming NNUEImpl exists

    /* ── Search Context State ────────── */
    private long nodes;
    private int rootDepth;
    private int selDepth;
    private int nmpPlies; // Used for NMP verification tracking
    private long startTimeMs;

    private List<Long> gameHistory;
    private final long[] searchPathHistory = new long[MAX_PLY + 2];
    private final Map<Integer, Long> rootMoveNodes = new HashMap<>();
    private List<RootMove> rootMoves = new ArrayList<>();


    /* ── Time Management State (Main thread only) ────────── */
    private int bestMoveStability = 0;
    private int previousValue = SCORE_NONE;
    private int previousMove = 0;


    /* ── History Heuristics ────────── */
    // We use 'int' in Java for history scores, corresponding to 'int16_t' in C++.
    // Assuming standard piece encoding (WP=0..WK=5, BP=6..BK=11).
    private static final int PIECE_TYPES = 12;

    // 1. Quiet History [stm][from][to]
    private final int[][][] quietHistory = new int[2][64][64];

    // 2. Capture History [movedPieceType(0-11)][target][capturedPieceType(0-11)]
    // Note: C++ implementation used [stm][moved][target][captured]. We simplify slightly as stm is redundant with movedPieceType.
    private final int[][][] captureHistory = new int[PIECE_TYPES][64][PIECE_TYPES];

    // 3. Counter Moves [prev_from][prev_to] -> counter_move
    private final int[][] counterMoves = new int[64][64];

    // 4. Continuation History
    // [prevPieceType(0-11)][prevTarget][currentPieceType * 64 + currentTarget] (Flattened: 12*64=768)
    private final int[][][] continuationHistory = new int[PIECE_TYPES][64][PIECE_TYPES * 64];

    // 5. Correction History
    // [stm][pawnHash % SIZE]
    private final int[][] correctionHistory = new int[2][CORRECTION_HISTORY_SIZE];


    /* ── scratch buffers and Tables ─────────────── */
    // We add padding to the stack (STACK_OVERHEAD) to allow safe lookbehind (e.g., frame.prev(4))
    private static final int STACK_OVERHEAD = 4;
    private static final int STACK_SIZE = MAX_PLY + STACK_OVERHEAD + 2;
    private final SearchFrame[] frames;
    // Scratchpad for move generation
    private final int[][] moves = new int[STACK_SIZE][256];

    // Pre-calculated tables
    private static final int MAX_MOVES = 256;
    // [isQuiet][depth][moveIndex]
    private static final int[][][] LMR_TABLE = new int[2][MAX_PLY][MAX_MOVES];
    // [depth][isQuiet]
    private static final int[][] SEE_MARGIN_TABLE = new int[MAX_PLY][2];
    // [depth][isImproving]
    private static final int[][] LMP_MARGIN_TABLE = new int[MAX_PLY][2];

    // Initialization of tables
    static {
        initTables();
    }

    private static void initTables() {
        for (int d = 1; d < MAX_PLY; d++) {
            for (int m = 1; m < MAX_MOVES; m++) {
                double logD = Math.log(d);
                double logM = Math.log(m);

                // Tactical (Noisy)
                double reductionNoisy = LMR_REDUCTION_NOISY_BASE + logD * logM / LMR_REDUCTION_NOISY_FACTOR;
                LMR_TABLE[TACTICAL][d][m] = Math.max(0, (int) Math.round(reductionNoisy));

                // Quiet
                double reductionQuiet = LMR_REDUCTION_QUIET_BASE + logD * logM / LMR_REDUCTION_QUIET_FACTOR;
                LMR_TABLE[QUIET][d][m] = Math.max(0, (int) Math.round(reductionQuiet));
            }
        }

        for (int depth = 0; depth < MAX_PLY; depth++) {
            // SEE Margins
            SEE_MARGIN_TABLE[depth][TACTICAL] = (int) (SEE_MARGIN_NOISY * depth * depth);
            SEE_MARGIN_TABLE[depth][QUIET] = (int) (SEE_MARGIN_QUIET * depth);

            // LMP Margins
            LMP_MARGIN_TABLE[depth][WORSENING] = (int) (LMP_MARGIN_WORSENING_BASE + LMP_MARGIN_WORSENING_FACTOR * Math.pow(depth, LMP_MARGIN_WORSENING_POWER));
            LMP_MARGIN_TABLE[depth][IMPROVING] = (int) (LMP_MARGIN_IMPROVING_BASE + LMP_MARGIN_IMPROVING_FACTOR * Math.pow(depth, LMP_MARGIN_IMPROVING_POWER));
        }
    }

    // ========================================================================================
    // Helper Structures (Frame, RootMove, ScoredMove)
    // ========================================================================================

    // Helper structure for results from the root search
    private static final class RootMove implements Comparable<RootMove> {
        int value;
        int depth;
        int selDepth;
        List<Integer> pv;

        RootMove(int value, int depth, int selDepth, List<Integer> pv) {
            this.value = value;
            this.depth = depth;
            this.selDepth = selDepth;
            this.pv = pv;
        }

        @Override
        public int compareTo(RootMove other) {
            // Sort descending by value
            return Integer.compare(other.value, this.value);
        }
    }

    // Helper for internal move ordering
    private static class ScoredMove {
        int move;
        long score;
        ScoredMove(int move, long score) { this.move = move; this.score = score; }
    }

    // The Search Stack Frame (C++ SearchStack equivalent)
    private final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int pvLength;
        int ply;

        // State for search algorithms
        int staticEval = SCORE_NONE;
        int excludedMove = 0;
        final int[] killers = new int[2];
        int doubleExtensions = 0;
        int movedPiece = -1; // Encoded piece (0-11) that was moved to reach this position.
        int move = 0;        // The move made to reach this position.
        boolean improving = false;

        // Updates the PV line based on the child frame's PV.
        void setPV(int move, SearchFrame nextFrame) {
            // C++ logic ensures PV is indexed by ply starting from the root (ply 0).
            if (ply >= MAX_PLY) return;

            pv[ply] = move;
            if (nextFrame.pvLength > ply + 1) {
                int len = nextFrame.pvLength - (ply + 1);
                if (ply + 1 + len <= MAX_PLY) {
                    System.arraycopy(nextFrame.pv, ply + 1, pv, ply + 1, len);
                }
                pvLength = nextFrame.pvLength;
            } else {
                pvLength = ply + 1;
            }
        }

        void reset(int p) {
            this.ply = p;
            this.staticEval = SCORE_NONE;
            this.excludedMove = 0;
            this.killers[0] = 0;
            this.killers[1] = 0;
            this.movedPiece = -1;
            this.move = 0;
            this.improving = false;
            // Double extensions are inherited during the search, reset at ID loop start.
        }

        // Helper to safely access previous frames using the STACK_OVERHEAD
        SearchFrame prev(int distance) {
            int index = this.ply + STACK_OVERHEAD - distance;
            // Check bounds, ensuring we don't access below the root (index < STACK_OVERHEAD)
            if (index >= 0 && index < frames.length) {
                return frames[index];
            }
            return null;
        }
    }

    // Method to access the frame at a specific ply, accounting for the overhead offset.
    private SearchFrame frame(int ply) {
        return frames[ply + STACK_OVERHEAD];
    }

    // ========================================================================================
    // Constructor and Thread Management
    // ========================================================================================

    public SearchWorkerImpl(boolean isMainThread, WorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        this.frames = new SearchFrame[STACK_SIZE];
        for (int i = 0; i < frames.length; ++i) {
            frames[i] = new SearchFrame();
            // Initialize ply relative to the overhead
            frames[i].ply = i - STACK_OVERHEAD;
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

            // Start the search process
            tsearch();
        }
    }

    // ========================================================================================
    // Root Search (Iterative Deepening and Time Management)
    // ========================================================================================

    // The main search entry point, analogous to C++ tsearch()
    private void tsearch() {
        if (isMainThread) {
            tt.incrementAge();
            // Start helper threads if this is the main thread (Lazy SMP)
            pool.startHelpers();
        }

        // Initialize search context
        this.nodes = 0;
        this.nmpPlies = 0;
        this.rootMoves.clear();
        this.rootMoveNodes.clear();
        this.startTimeMs = pool.getSearchStartTime();

        if (isMainThread) {
            this.bestMoveStability = 0;
            this.previousValue = SCORE_NONE;
            this.previousMove = 0;
        }

        // Initialize NNUE accumulator for the root position
        nnue.refreshAccumulator(nnueState, rootBoard);

        // Initialize MoveOrderer (used primarily for SEE calculations)
        // We instantiate a placeholder if the user hasn't provided a full implementation.
        this.moveOrderer = new SEEPlaceholder(this);

        int maxDepth = spec.depth() > 0 ? spec.depth() : CoreConstants.MAX_PLY - 1;
        int multiPvCount = 1; // MultiPV support is complex; we stick to 1 for this translation.

        List<Integer> excludedRootMoves = new ArrayList<>();


        // --- Iterative Deepening Loop ---
        for (int depth = 1; depth <= maxDepth; ++depth) {
            if (pool.isStopped()) break;

            // Prepare for this iteration
            this.rootDepth = depth;
            excludedRootMoves.clear();

            // Loop for MultiPV (currently only 1 iteration)
            for (int rootMoveIdx = 0; rootMoveIdx < multiPvCount; rootMoveIdx++) {

                // Reset the search stack frames for this new search
                // We only need to reset the root frame; subsequent frames are reset during search.
                frame(0).reset(0);
                frame(0).doubleExtensions = 0;

                this.selDepth = 0;

                // --- Aspiration Windows ---
                float delta = (float) ASP_DELTA;
                int alpha = -SCORE_INF;
                int beta = SCORE_INF;
                int value;

                if (depth >= ASP_MIN_DEPTH && previousValue != SCORE_NONE) {
                    alpha = Math.max(previousValue - (int)delta, -SCORE_INF);
                    beta = Math.min(previousValue + (int)delta, SCORE_INF);
                } else if (depth < ASP_MIN_DEPTH) {
                    delta = 2 * SCORE_INF; // Infinite window for shallow depths
                }

                int failHighs = 0;
                while (true) {
                    // Adjust depth based on fail highs (C++ optimization)
                    int searchDepth = Math.max(1, depth - failHighs);

                    // Start the actual search from the root
                    value = search(rootBoard, searchDepth, alpha, beta, 0, NodeType.ROOT_NODE, false, excludedRootMoves);

                    if (pool.isStopped()) break;

                    // Analyze the result relative to the window
                    if (value <= alpha) {
                        // Fail Low
                        beta = (alpha + beta) / 2;
                        alpha = Math.max(value - (int)delta, -SCORE_INF);
                        failHighs = 0;
                    } else if (value >= beta) {
                        // Fail High
                        beta = Math.min(value + (int)delta, SCORE_INF);
                        failHighs = Math.min(failHighs + 1, ASP_MAX_FAIL_HIGHS);
                    } else {
                        // Success
                        break;
                    }

                    // Widen window fully if mate score found
                    if (Math.abs(value) >= SCORE_MATE_IN_MAX_PLY) {
                        beta = SCORE_INF;
                        alpha = -SCORE_INF;
                        failHighs = 0;
                    }

                    // Increase delta exponentially
                    delta *= ASP_DELTA_FACTOR;
                }

                if (pool.isStopped()) break;

                // Store the result of this PV search
                SearchFrame rootFrame = frame(0);
                // Check if the PV length extends beyond the root (ply 0)
                if (rootFrame.pvLength > 0) {
                    List<Integer> pv = new ArrayList<>();
                    // Extract PV starting from index 0
                    for (int i = 0; i < rootFrame.pvLength; i++) {
                        pv.add(rootFrame.pv[i]);
                    }
                    RootMove rm = new RootMove(value, depth, selDepth, pv);

                    // For MultiPV (if implemented), we'd add the move to excludedRootMoves here.
                    // excludedRootMoves.add(pv.get(0));

                    if (rootMoveIdx == 0) rootMoves.clear();
                    rootMoves.add(rm);
                } else if (rootMoveIdx == 0) {
                    // If the primary search failed to produce a move, stop.
                    rootMoves.clear();
                    break;
                }

            } // End MultiPV loop

            if (pool.isStopped() || rootMoves.isEmpty()) break;

            // Post-processing after a depth is completed (Main Thread Only)
            if (isMainThread) {
                Collections.sort(rootMoves); // Sort results (best first)

                // 1. Report results to GUI
                reportInfo();

                // 2. Time Management Check
                if (timeManagementCheck()) {
                    pool.stopSearch();
                    break;
                }

                // Update state for the next iteration
                previousMove = rootMoves.get(0).pv.get(0);
                previousValue = rootMoves.get(0).value;
            }

        } // End Iterative Deepening loop

        // Finalize the search
        if (isMainThread) {
            pool.waitForHelpersFinished();
            pool.finalizeSearch(getSearchResult());
        }
    }

    private void reportInfo() {
        if (ih == null || rootMoves.isEmpty()) return;

        long currentElapsedMs = System.currentTimeMillis() - startTimeMs;
        long totalNodes = pool.totalNodes();
        long nps = currentElapsedMs > 0 ? (totalNodes * 1000) / currentElapsedMs : 0;
        int hashfull = tt.hashfull();

        // Report the primary PV (MultiPV=1)
        RootMove primary = rootMoves.get(0);
        boolean mateScore = Math.abs(primary.value) >= SCORE_MATE_IN_MAX_PLY;

        ih.onInfo(new SearchInfo(
                primary.depth, primary.depth, 1, primary.value, mateScore, totalNodes,
                nps, currentElapsedMs, primary.pv, hashfull, primary.selDepth));
    }

    // Advanced Time Management logic (C++ implementation)
    private boolean timeManagementCheck() {
        long softTimeLimit = pool.getSoftMs();
        long hardTimeLimit = pool.getMaximumMs();
        long currentElapsed = System.currentTimeMillis() - startTimeMs;

        // 1. Hard time limit.
        if (currentElapsed >= hardTimeLimit) {
            return true;
        }

        // 2. Infinite time.
        if (softTimeLimit >= Long.MAX_VALUE / 2) {
            return false;
        }

        // 3. Check for mate found.
        if (Math.abs(rootMoves.get(0).value) >= SCORE_MATE_IN_MAX_PLY) {
            return true;
        }

        // FIX: Define a minimum depth before advanced heuristics apply.
        // This prevents instability at very low depths from causing premature termination.
        final int MIN_DEPTH_FOR_HEURISTICS = 3;

        if (this.rootDepth < MIN_DEPTH_FOR_HEURISTICS) {
            // Before heuristics kick in, just respect the base soft limit.
            return currentElapsed >= softTimeLimit;
        }

        // Calculate time adjustment factor based on search stability.
        double tmAdjustment = TM_INITIAL_ADJUSTMENT;

        // H1: Best Move Stability
        int currentBestMove = rootMoves.get(0).pv.get(0);
        if (currentBestMove == previousMove) {
            bestMoveStability = Math.min(bestMoveStability + 1, TM_BEST_MOVE_STABILITY_MAX);
        } else {
            bestMoveStability = 0;
        }
        // Formula: base - stability * factor
        tmAdjustment *= (TM_BEST_MOVE_STABILITY_BASE - bestMoveStability * TM_BEST_MOVE_STABILITY_FACTOR);

        // H2: Score difference to last iteration
        // Formula: base + clamped_diff * factor
        // We ensure previousValue is valid (it should be, since depth >= MIN_DEPTH_FOR_HEURISTICS).
        // Use long arithmetic for robustness against potential overflow if score constants are large.
        long evalDiff = 0;
        if (previousValue != SCORE_NONE) {
            evalDiff = (long)previousValue - (long)rootMoves.get(0).value;
        }

        long clampedDiff = Math.max(TM_EVAL_DIFF_MIN, Math.min(TM_EVAL_DIFF_MAX, evalDiff));
        tmAdjustment *= (TM_EVAL_DIFF_BASE + clampedDiff * TM_EVAL_DIFF_FACTOR);

        // H3: Fraction of nodes that went into the best move
        // Formula: base - factor * fraction
        long nodesForBestMove = rootMoveNodes.getOrDefault(currentBestMove, 0L);

        // Use nodes from this worker only for the calculation, mirroring C++ logic structure.
        double nodeFraction = (this.nodes > 0) ? ((double) nodesForBestMove / this.nodes) : 0.0;
        tmAdjustment *= (TM_NODES_BASE - TM_NODES_FACTOR * nodeFraction);

        // Apply the adjustment factor. We stop if elapsed time exceeds the adjusted soft limit.
        return currentElapsed >= (softTimeLimit * tmAdjustment);
    }

    // ========================================================================================
    // Main Search (PVS)
    // ========================================================================================

    private enum NodeType {
        ROOT_NODE, PV_NODE, NON_PV_NODE
    }

    /**
     * The main Principal Variation Search (PVS) implementation.
     */
    private int search(long[] bb, int depth, int alpha, int beta, int ply, NodeType nt, boolean cutNode, List<Integer> excludedRootMoves) {
        // --- 0. Initialization and Node Setup ---

        final boolean rootNode = nt == NodeType.ROOT_NODE;
        final boolean pvNode = nt != NodeType.NON_PV_NODE;

        // Determine the node type for the recursive calls
        final NodeType nextNodeType = (nt == NodeType.ROOT_NODE) ? NodeType.PV_NODE : NodeType.NON_PV_NODE;

        SearchFrame stack = frame(ply);

        // Initialize the frame for this ply (resetting necessary fields)
        stack.reset(ply);
        // Inherit double extensions count from the previous frame if not root.
        if (!rootNode && ply > 0) {
            stack.doubleExtensions = frame(ply-1).doubleExtensions;
        }


        if (pvNode) {
            // Initialize PV length for this node.
            stack.pvLength = ply;
        }
        this.selDepth = Math.max(ply, this.selDepth);

        // Store hash for repetition detection
        if (ply >= 0 && ply < searchPathHistory.length) {
            searchPathHistory[ply] = bb[HASH];
        }


        // --- 1. Check Stopping Conditions and Draws ---

        // Check for upcoming repetition (C++ hasUpcomingRepetition logic)
        if (!rootNode && alpha < 0 && isRepetition(bb, ply)) {
            int drawScore = drawEval();
            if (drawScore >= beta) return drawScore;
            alpha = Math.max(alpha, drawScore);
        }

        // Check 50-move rule
        if (PositionFactory.halfClock(bb[META]) >= 100) {
            return drawEval();
        }

        // Check if we should drop into Quiescence Search
        if (depth <= 0) {
            return qsearch(bb, alpha, beta, ply, nextNodeType);
        }

        // Time/Stop check and Max Ply
        if (!rootNode) {
            if (shouldStop()) return 0;

            if (ply >= MAX_PLY) {
                // Evaluate at max depth
                return nnue.evaluateFromAccumulator(nnueState, bb);
            }

            // Mate Distance Pruning
            alpha = Math.max(alpha, matedIn(ply));
            beta = Math.min(beta, mateIn(ply + 1));
            if (alpha >= beta) {
                return alpha;
            }
        }

        // Increment node count (C++ increments during the move loop, we do it here for non-root nodes)
        if (!rootNode) nodes++;

        // --- 2. Transposition Table (TT) Lookup ---

        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        boolean ttHit = false;

        int ttMove = 0;
        int ttValue = SCORE_NONE;
        int ttEval = SCORE_NONE;
        int ttDepth = -1;
        int ttFlag = TranspositionTable.FLAG_NONE;
        boolean ttPv = pvNode;

        // Check if this search is excluding a move (for Singular Extensions)
        int excludedMove = stack.excludedMove;
        boolean excluded = (excludedMove != 0);

        if (!excluded) {
            ttHit = tt.wasHit(ttIndex, key);
            if (ttHit) {
                ttMove = tt.getMove(ttIndex);
                // Retrieve score adjusted for current ply
                ttValue = tt.getScore(ttIndex, ply);
                ttEval = tt.getStaticEval(ttIndex);
                ttDepth = tt.getDepth(ttIndex);
                ttFlag = tt.getBound(ttIndex);
                ttPv = ttPv || tt.wasPv(ttIndex);
            }
        }

        // TT Cutoff
        if (!pvNode && ttDepth >= depth && ttValue != SCORE_NONE && !excluded) {
            if ((ttFlag == TranspositionTable.FLAG_UPPER && ttValue <= alpha) ||
                    (ttFlag == TranspositionTable.FLAG_LOWER && ttValue >= beta) ||
                    (ttFlag == TranspositionTable.FLAG_EXACT)) {
                return ttValue;
            }
        }

        // --- 3. Static Evaluation and Position Analysis ---

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        int eval = SCORE_NONE;
        int unadjustedEval = SCORE_NONE;
        boolean improving = false;

        if (inCheck) {
            stack.staticEval = SCORE_NONE;
            // Skip static analysis and most pruning if in check.
        } else {
            // Calculate or retrieve static evaluation
            if (excluded) {
                // Use the previously calculated static eval if excluding a move.
                unadjustedEval = eval = stack.staticEval;
            } else {
                if (ttHit && ttEval != SCORE_NONE) {
                    unadjustedEval = ttEval;
                } else {
                    unadjustedEval = nnue.evaluateFromAccumulator(nnueState, bb);
                    // Store the static eval in TT if it was missing (using minimal depth).
                    if (!ttHit || ttEval == SCORE_NONE) {
                        tt.store(ttIndex, key, TranspositionTable.FLAG_NONE, 0, 0, SCORE_NONE, unadjustedEval, ttPv, ply);
                    }
                }

                // Apply Correction History
                eval = correctStaticEval(unadjustedEval, bb);
                stack.staticEval = eval;

                // Use TT value to refine eval if bounds suggest it's more accurate (C++ optimization)
                if (!excluded && ttHit && ttValue != SCORE_NONE) {
                    if ((ttFlag == TranspositionTable.FLAG_UPPER && ttValue < eval) ||
                            (ttFlag == TranspositionTable.FLAG_LOWER && ttValue > eval) ||
                            (ttFlag == TranspositionTable.FLAG_EXACT)) {
                        eval = ttValue;
                    }
                }
            }

            // Determine if the position is improving based on history
            SearchFrame prev2 = stack.prev(2);
            if (prev2 != null && prev2.staticEval != SCORE_NONE) {
                improving = stack.staticEval > prev2.staticEval;
            } else {
                // Look back 4 plies if 2 plies ago is unavailable (e.g., after Null Move)
                SearchFrame prev4 = stack.prev(4);
                if (prev4 != null && prev4.staticEval != SCORE_NONE) {
                    improving = stack.staticEval > prev4.staticEval;
                }
            }
            stack.improving = improving;

            // --- 4. Pruning Techniques (Pre-Search) ---

            if (!rootNode && Math.abs(eval) < SCORE_MATE_IN_MAX_PLY) {

                // 4.1 Reverse Futility Pruning (RFP)
                if (depth < RFP_DEPTH) {
                    // Margin increases with depth, decreases if improving.
                    int rfpMargin = RFP_FACTOR * (depth - (improving ? 1 : 0));
                    if (eval - rfpMargin >= beta) {
                        return eval;
                    }
                }

                // 4.2 Razoring
                if (depth < RAZORING_DEPTH && !excluded && !pvNode) {
                    if (eval + (RAZORING_FACTOR * depth) < alpha) {
                        // If eval is very low, drop into QSearch to verify.
                        int razorValue = qsearch(bb, alpha, beta, ply, NodeType.NON_PV_NODE);
                        if (razorValue <= alpha) {
                            return razorValue;
                        }
                    }
                }
            }

            // 4.3 Null Move Pruning (NMP)
            // Conditions: Not PV, eval is good (>= beta and >= static eval), not excluded, depth >= 3,
            // not currently verifying NMP (ply >= nmpPlies), and has non-pawn material.
            if (!pvNode && eval >= stack.staticEval && eval >= beta && beta > -SCORE_MATE_IN_MAX_PLY &&
                    !excluded && depth >= 3 && ply >= nmpPlies && pf.hasNonPawnMaterial(bb)) {

                SearchFrame prev1 = stack.prev(1);
                // Ensure last move was not null (movedPiece != -1)
                if (prev1 != null && prev1.movedPiece != -1) {

                    // Calculate reduction R (adaptive)
                    int R = NMP_RED_BASE + depth / NMP_DEPTH_DIV + Math.min((eval - beta) / NMP_DIVISOR, NMP_MIN);
                    int nmpDepth = depth - R;

                    // Make Null Move
                    long oldMeta = bb[META];
                    long oldHash = bb[HASH];
                    bb[META] ^= PositionFactory.STM_MASK;
                    // CRITICAL: Assumes PositionFactoryImpl.SIDE_TO_MOVE exists for Zobrist toggle.
                    bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

                    // Update stack context for null move
                    stack.move = 0;
                    stack.movedPiece = -1;

                    // Search with reduced depth and zero window around -beta
                    int nullValue = -search(bb, nmpDepth, -beta, -beta + 1, ply + 1, NodeType.NON_PV_NODE, !cutNode, null);

                    // Undo Null Move
                    bb[META] = oldMeta;
                    bb[HASH] = oldHash;

                    if (pool.isStopped()) return 0;

                    if (nullValue >= beta) {
                        // Clamp mate scores
                        if (nullValue > SCORE_MATE_IN_MAX_PLY) nullValue = beta;

                        // Verification Search (C++ logic)
                        // If shallow depth (<15) or already in verification (nmpPlies>0), trust the result.
                        if (nmpPlies > 0 || depth < 15) {
                            return nullValue;
                        }

                        // Start verification search
                        int verificationDepth = depth - R;
                        int oldNmpPlies = this.nmpPlies;
                        // Set verification context depth
                        this.nmpPlies = ply + verificationDepth * 2 / 3;
                        // Research around beta
                        int verificationValue = search(bb, verificationDepth, beta - 1, beta, ply, NodeType.NON_PV_NODE, false, null);
                        this.nmpPlies = oldNmpPlies;

                        if (verificationValue >= beta) {
                            return nullValue; // Verification succeeded
                        }
                    }
                }
            }

            // 4.4 ProbCut
            if (!pvNode && !excluded && depth >= PROBCUT_MIN_DEPTH && Math.abs(beta) < SCORE_MATE_IN_MAX_PLY) {
                int probCutBeta = beta + PROBCUT_MARGIN;

                // Check if TT entry suggests this node likely fails low anyway.
                if (!(ttDepth >= depth - 3 && ttValue != SCORE_NONE && ttValue < probCutBeta)) {

                    // Generate captures
                    int[] clist = moves[ply + STACK_OVERHEAD];
                    int ccount = mg.generateCaptures(bb, clist, 0);

                    // Basic ordering (MVV-LVA) - sufficient for ProbCut loop
                    moveOrderer.orderMoves(bb, clist, ccount, 0, null);

                    int requiredSeeGain = probCutBeta - stack.staticEval;

                    for (int i = 0; i < ccount; i++) {
                        int mv = clist[i];

                        // Prune if SEE is too low
                        if (moveOrderer.see(bb, mv) < requiredSeeGain) continue;

                        // FIX: Determine move info BEFORE making the move.
                        int moverPiece = (mv >>> 16) & 0xF;
                        int capturedPiece = getCapturedPieceType(bb, mv);

                        if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

                        nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

                        // Reduced depth search (C++ optimization: Q-search first, then PVS if Q-search succeeds)
                        int value = -qsearch(bb, -probCutBeta, -probCutBeta + 1, ply + 1, NodeType.NON_PV_NODE);

                        if (value >= probCutBeta) {
                            // If Q-search succeeded, verify with a reduced PVS search.
                            value = -search(bb, depth - PROBCUT_REDUCTION, -probCutBeta, -probCutBeta + 1, ply + 1, NodeType.NON_PV_NODE, !cutNode, null);
                        }

                        pf.undoMoveInPlace(bb);

                        // FIX: Use the locally stored captured piece info for NNUE undo.
                        nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);

                        if (pool.isStopped()) return 0;

                        if (value >= probCutBeta) {
                            // Store the result in TT and return the cutoff.
                            tt.store(ttIndex, key, TranspositionTable.FLAG_LOWER, depth - 3, mv, value, unadjustedEval, false, ply);
                            return value;
                        }
                    }
                }
            }
        } // End if (!inCheck) block


        // --- 5. Internal Iterative Deepening (IID) / Reduction (IIR) ---
        // If no TT move was found, reduce the depth slightly, effectively performing IID.
        if (ttMove == 0 && depth >= IIR_MIN_DEPTH && !excluded) {
            depth--;
        }

        // --- 6. Move Generation and Ordering ---

        int[] list = moves[ply + STACK_OVERHEAD];
        int nMoves;

        // Generate moves
        if (inCheck) {
            nMoves = mg.generateEvasions(bb, list, 0);
        } else {
            // Generate all moves (captures followed by quiets)
            int capturesEnd = mg.generateCaptures(bb, list, 0);
            nMoves = mg.generateQuiets(bb, list, capturesEnd);
        }

        // Order moves using advanced heuristics (integrated scoring)
        List<ScoredMove> scoredMoves = new ArrayList<>(nMoves);
        for(int i=0; i<nMoves; i++) {
            int move = list[i];
            boolean isCapture = isCapture(bb, move);
            long score = calculateMoveScore(bb, stack, move, ttMove, isCapture);
            scoredMoves.add(new ScoredMove(move, score));
        }
        // Sort descending by score
        Collections.sort(scoredMoves, Comparator.comparingLong((ScoredMove sm) -> sm.score).reversed());


        // --- 7. Search Loop Initialization ---

        int bestValue = -SCORE_INF;
        int bestMove = 0;
        int originalAlpha = alpha;
        int legalMovesFound = 0;
        boolean skipQuiets = false;

        // Buffers for history updates (limited size as in C++)
        int[] quietMovesSearched = new int[64];
        int quietMovesCount = 0;
        int[] captureMovesSearched = new int[64];
        int captureMovesCount = 0;

        // Initialize next frame basics
        SearchFrame nextFrame = frame(ply + 1);
        nextFrame.killers[0] = 0;
        nextFrame.killers[1] = 0;


        // --- 8. Search Loop ---
        for (int i = 0; i < nMoves; i++) {
            // moveIndex is 1-based for LMR/LMP calculations
            // We use 'i + 1' here as pruning relies on the index before legality is confirmed.
            int moveIndex = i + 1;
            int mv = scoredMoves.get(i).move;

            if (mv == excludedMove) continue;

            // Handle root move exclusion (for MultiPV)
            if (rootNode && excludedRootMoves != null && excludedRootMoves.contains(mv)) continue;

            // FIX: Determine move info BEFORE making the move.
            int moverPiece = (mv >>> 16) & 0xF;
            int capturedPiece = getCapturedPieceType(bb, mv);
            boolean isCapture = (capturedPiece != -1);

            if (!isCapture && skipQuiets) continue;

            // Legality check required before pruning/extensions in C++ structure.
            // We use make/undo as a robust legality check.
            if (!pf.makeMoveInPlace(bb, mv, mg)) {
                continue;
            }
            // Immediately undo the move. We only needed to verify legality.
            pf.undoMoveInPlace(bb);


            long nodesBeforeMove = this.nodes;
            int extension = 0;

            // Clamping depth/index for table access
            int clampedDepth = Math.min(depth, MAX_PLY-1);
            int clampedIndex = Math.min(moveIndex, MAX_MOVES-1);

            // --- 8.1. In-Search Pruning and Reductions ---

            // Conditions for pruning: Not root, score is not near mate, and material exists.
            boolean doPruning = !rootNode && bestValue > -SCORE_MATE_IN_MAX_PLY && pf.hasNonPawnMaterial(bb);

            if (doPruning && !inCheck) {
                int moveType = isCapture ? TACTICAL : QUIET;
                // Calculate base LMR reduction for pruning depth estimation

                int reduction = LMR_TABLE[moveType][clampedDepth][clampedIndex];
                int lmrDepth = Math.max(0, depth - reduction);

                // Pruning for quiet moves in Non-PV nodes
                if (!pvNode && !skipQuiets && !isCapture) {

                    // 8.1.1 Late Move Pruning (LMP)
                    int lmpMargin = LMP_MARGIN_TABLE[clampedDepth][stack.improving ? IMPROVING : WORSENING];
                    if (moveIndex >= lmpMargin) {
                        skipQuiets = true;
                        continue;
                    }

                    // 8.1.2 Futility Pruning (FP)
                    // Requires eval to be valid (it is, because !inCheck)
                    if (lmrDepth < FP_DEPTH) {
                        int fpMargin = FP_BASE + FP_FACTOR * lmrDepth;
                        // If static eval + margin is still below alpha, prune.
                        if (eval + fpMargin <= alpha) {
                            skipQuiets = true;
                            continue;
                        }
                    }
                }

                // 8.1.3 History Pruning
                if (!pvNode && lmrDepth < HISTORY_PRUNING_DEPTH) {
                    int moveHistory = getCombinedHistory(bb, stack, mv, isCapture);
                    // Prune if history score is significantly negative.
                    if (moveHistory < HISTORY_PRUNING_FACTOR * depth) {
                        continue;
                    }
                }

                // 8.1.4 SEE Pruning
                if (!pvNode && depth < SEE_DEPTH) {
                    int seeMargin = SEE_MARGIN_TABLE[clampedDepth][moveType];
                    // Prune if SEE is below the depth-dependent margin.
                    if (moveOrderer.see(bb, mv) < seeMargin) {
                        continue;
                    }
                }
            }

            // --- 8.2. Singular Extensions ---

            boolean doExtensions = !rootNode && ply < this.rootDepth * 2;

            // Conditions: Sufficient depth, move is the TT move, not excluded, TT entry is reliable (LOWER/EXACT bound),
            // score is not mate, and TT depth is recent.
            if (doExtensions && depth >= SINGULAR_MIN_DEPTH && mv == ttMove && !excluded &&
                    (ttFlag == TranspositionTable.FLAG_LOWER || ttFlag == TranspositionTable.FLAG_EXACT) &&
                    Math.abs(ttValue) < SCORE_MATE_IN_MAX_PLY && ttDepth >= depth - 3) {

                int singularBeta = ttValue - depth;
                int singularDepth = (depth - 1) / 2;

                // Perform a search excluding the TT move (recursive call)
                stack.excludedMove = mv;
                int singularValue = search(bb, singularDepth, singularBeta - 1, singularBeta, ply, NodeType.NON_PV_NODE, cutNode, null);
                stack.excludedMove = 0;

                // Analyze result
                if (singularValue < singularBeta) {
                    // Singularity confirmed -> Extend search
                    extension = 1;
                    // Double Extension check
                    if (!pvNode && singularValue + DOUBLE_EXTENSION_MARGIN < singularBeta && stack.doubleExtensions <= DOUBLE_EXTENSION_LIMIT) {
                        extension = 2;
                        // C++ optimization: slight depth increase for shallow searches
                        if (depth < 10) depth++;
                    }
                }
                // Multicut optimization
                else if (singularBeta >= beta) {
                    return singularBeta;
                }
                // Reductions based on failure to prove singularity
                else if (ttValue >= beta) {
                    extension = -2;
                }
                else if (cutNode && ttValue <= alpha) {
                    extension = -1;
                }
            }

            // --- 8.3. Make Move ---

            // We know it's legal from the earlier check. Make the move for real.
            pf.makeMoveInPlace(bb, mv, mg);
            legalMovesFound++;

            // Use the locally stored captured piece info for NNUE update.
            nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

            // Update stack context for the current ply (move made to reach here)
            stack.move = mv;
            stack.movedPiece = moverPiece;

            // Update double extensions count for the next frame if extended
            if (extension >= 2) {
                nextFrame.doubleExtensions = stack.doubleExtensions + 1;
            }


            // Check extension (Checkers extension)
            if (doExtensions && extension == 0) {
                boolean nextInCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
                if (nextInCheck) {
                    extension = 1;
                }
            }

            int newDepth = depth - 1 + extension;
            int value;

            // --- 8.4. Late Move Reductions (LMR) and PVS Search ---

            // Conditions for LMR: Late in the move list, sufficient depth, not a tactical move (unless ttPv allows).
            if (moveIndex > LMR_MC_BASE + LMR_MC_PV * (pvNode ? 1 : 0) && depth >= LMR_MIN_DEPTH && (!isCapture || !ttPv)) {

                int moveType = isCapture ? TACTICAL : QUIET;
                int reduction = LMR_TABLE[moveType][clampedDepth][clampedIndex];

                // Adjustments based on node type and TT info
                if (!ttPv) reduction++;
                if (cutNode) reduction += 2;

                int reducedDepth = newDepth - reduction;

                // Adjust reduction based on history heuristic
                int moveHistory = getCombinedHistory(bb, stack, mv, isCapture);
                int historyFactor = isCapture ? LMR_HISTORY_FACTOR_CAPTURE : LMR_HISTORY_FACTOR_QUIET;

                // Protect against division by zero if factors are tuned badly
                if (historyFactor != 0) {
                    reducedDepth += moveHistory / historyFactor;
                }

                // Clamp depth
                reducedDepth = Math.max(1, Math.min(reducedDepth, newDepth));

                // Zero-window search (ZWS) at reduced depth
                value = -search(bb, reducedDepth, -(alpha + 1), -alpha, ply + 1, NodeType.NON_PV_NODE, true, null);

                // Dynamic depth adjustment (Deeper/Shallower search) - C++ logic
                boolean doShallowerSearch = !rootNode && value < bestValue + newDepth;
                boolean doDeeperSearch = value > (bestValue + LMR_DEEPER_BASE + LMR_DEEPER_FACTOR * newDepth);
                int adjustedNewDepth = newDepth + (doDeeperSearch ? 1 : 0) - (doShallowerSearch ? 1 : 0);


                // Re-search if the move looks promising (beat alpha) and we reduced the depth
                if (value > alpha && reducedDepth < adjustedNewDepth) {
                    value = -search(bb, adjustedNewDepth, -(alpha + 1), -alpha, ply + 1, NodeType.NON_PV_NODE, !cutNode, null);
                }
            } else if (!pvNode || moveIndex > 1) {
                // Standard ZWS (for non-PV nodes or late PV moves)
                value = -search(bb, newDepth, -(alpha + 1), -alpha, ply + 1, NodeType.NON_PV_NODE, !cutNode, null);
            } else {
                // First move in PV node
                value = alpha + 1; // Force PVS full search below
            }

            // Principal Variation Search (PVS) - Full window search
            if (pvNode && (moveIndex == 1 || value > alpha)) {
                value = -search(bb, newDepth, -beta, -alpha, ply + 1, NodeType.PV_NODE, false, null);
            }


            // --- 8.5. Undo Move and Update Results ---

            pf.undoMoveInPlace(bb);

            // FIX: Use the locally stored captured piece info for NNUE undo.
            nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);

            if (pool.isStopped()) return 0;

            // Track moves for history updates (must be done after search/undo)
            if (isCapture) {
                if (captureMovesCount < 64) captureMovesSearched[captureMovesCount++] = mv;
            } else {
                if (quietMovesCount < 64) quietMovesSearched[quietMovesCount++] = mv;
            }

            // Track nodes spent on root moves
            if (rootNode) {
                long nodesForThisMove = this.nodes - nodesBeforeMove;
                // If root node, increment nodes here as C++ does it in the loop for root.
                nodes++;
                rootMoveNodes.merge(mv, nodesForThisMove, Long::sum);
            }

            if (value > bestValue) {
                bestValue = value;
                bestMove = mv;

                if (value > alpha) {
                    alpha = value;

                    if (pvNode) {
                        // Update the PV line
                        stack.setPV(mv, nextFrame);
                    }

                    // Beta Cutoff
                    if (value >= beta) {
                        // Update History Heuristics on cutoff
                        updateHeuristicsOnCutoff(bb, stack, mv, depth, isCapture, eval, originalAlpha, beta, value,
                                quietMovesSearched, quietMovesCount, captureMovesSearched, captureMovesCount);
                        break;
                    }
                }
            }
        } // End Move Loop


        // --- 9. Checkmate/Stalemate and Finalization ---

        if (legalMovesFound == 0) {
            if (excluded) {
                // If we are in an excluded search and found no moves, it means the excluded move was the only legal move.
                return -SCORE_INF;
            }
            return inCheck ? matedIn(ply) : SCORE_STALEMATE;
        }

        // --- 10. TT Store ---
        if (!excluded) {
            int flag = (bestValue >= beta) ? TranspositionTable.FLAG_LOWER
                    : (bestValue > originalAlpha) ? TranspositionTable.FLAG_EXACT
                    : TranspositionTable.FLAG_UPPER;

            // Store the result of the search
            tt.store(ttIndex, key, flag, depth, bestMove, bestValue, unadjustedEval, ttPv, ply);
        }

        // --- 11. Correction History Update ---
        // Conditions: Not in check, not excluded, best move found was quiet (or no move found).
        // Note: isCapture check now uses the dedicated helper function.
        if (!inCheck && !excluded && (bestMove == 0 || !isCapture(bb, bestMove))) {
            // Specific conditions from C++: avoid updating if the score caused a beta cutoff but was lower than static eval,
            // or if no move was found but the score was higher than static eval.
            if (!(bestValue >= beta && bestValue <= stack.staticEval) &&
                    !(bestMove == 0 && bestValue >= stack.staticEval))
            {
                // Bonus is proportional to the difference (eval error) and depth.
                int bonus = (int)((long)(bestValue - stack.staticEval) * depth / 8);
                // Clamp the bonus
                int clampedBonus = Math.max(-CORRECTION_HISTORY_LIMIT / 4, Math.min(CORRECTION_HISTORY_LIMIT / 4, bonus));
                updateCorrectionHistory(bb, clampedBonus);
            }
        }

        return bestValue;
    }


    // ========================================================================================
    // Quiescence Search (QSearch)
    // ========================================================================================

    private int qsearch(long[] bb, int alpha, int beta, int ply, NodeType nt) {
        final boolean pvNode = nt != NodeType.NON_PV_NODE;

        SearchFrame stack = frame(ply);
        stack.reset(ply); // Reset frame context

        if (pvNode) {
            stack.pvLength = ply;
        }
        this.selDepth = Math.max(ply, this.selDepth);

        if (ply >= 0 && ply < searchPathHistory.length) {
            searchPathHistory[ply] = bb[HASH];
        }

        // --- 1. Check Stopping Conditions and Draws ---

        if (shouldStop()) return 0;

        // Draw checks
        if (isRepetition(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100) {
            return drawEval();
        }

        // Max Ply Check
        if (ply >= MAX_PLY) {
            return nnue.evaluateFromAccumulator(nnueState, bb);
        }

        nodes++;

        // --- 2. TT Lookup ---

        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        boolean ttHit = tt.wasHit(ttIndex, key);

        int ttValue = SCORE_NONE;
        int ttEval = SCORE_NONE;
        int ttMove = 0;
        int ttFlag = TranspositionTable.FLAG_NONE;
        boolean ttPv = pvNode;

        if (ttHit) {
            // Q-search entries are stored with depth 0.
            if (tt.getDepth(ttIndex) >= 0) {
                ttValue = tt.getScore(ttIndex, ply);
                ttEval = tt.getStaticEval(ttIndex);
                ttMove = tt.getMove(ttIndex);
                ttFlag = tt.getBound(ttIndex);
                ttPv = ttPv || tt.wasPv(ttIndex);
            }
        }

        // TT Cutoff
        if (!pvNode && ttValue != SCORE_NONE) {
            if ((ttFlag == TranspositionTable.FLAG_UPPER && ttValue <= alpha) ||
                    (ttFlag == TranspositionTable.FLAG_LOWER && ttValue >= beta) ||
                    (ttFlag == TranspositionTable.FLAG_EXACT)) {
                return ttValue;
            }
        }

        // --- 3. Static Evaluation and Stand Pat ---

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        int bestValue;
        int futilityValue;
        int unadjustedEval = SCORE_NONE;

        if (inCheck) {
            stack.staticEval = bestValue = futilityValue = -SCORE_INF;
        } else {
            // Calculate or retrieve static evaluation
            if (ttHit && ttEval != SCORE_NONE) {
                unadjustedEval = ttEval;
            } else {
                unadjustedEval = nnue.evaluateFromAccumulator(nnueState, bb);
                // Store the static eval in TT if it was missing.
                if (!ttHit || ttEval == SCORE_NONE) {
                    tt.store(ttIndex, key, TranspositionTable.FLAG_NONE, 0, 0, SCORE_NONE, unadjustedEval, ttPv, ply);
                }
            }

            // Apply Correction History
            bestValue = correctStaticEval(unadjustedEval, bb);
            stack.staticEval = bestValue;

            // Futility Pruning base value
            futilityValue = bestValue + QS_FUTILITY_OFFSET;

            // Stand Pat
            if (bestValue >= beta) {
                return bestValue;
            }
            if (bestValue > alpha) {
                alpha = bestValue;
            }
        }

        // Mate Distance Pruning
        alpha = Math.max(alpha, matedIn(ply));
        beta = Math.min(beta, mateIn(ply + 1));
        if (alpha >= beta) {
            return alpha;
        }

        // --- 4. Move Generation and Search Loop ---

        int[] list = moves[ply + STACK_OVERHEAD];
        int nMoves;

        if (inCheck) {
            nMoves = mg.generateEvasions(bb, list, 0);
        } else {
            nMoves = mg.generateCaptures(bb, list, 0);
        }

        // Order moves
        List<ScoredMove> scoredMoves = new ArrayList<>(nMoves);
        for(int i=0; i<nMoves; i++) {
            // In QSearch, we treat moves as tactical.
            long score = calculateMoveScore(bb, stack, list[i], ttMove, true);
            scoredMoves.add(new ScoredMove(list[i], score));
        }
        Collections.sort(scoredMoves, Comparator.comparingLong((ScoredMove sm) -> sm.score).reversed());


        int bestMove = 0;
        int originalAlpha = alpha;
        int legalMovesFound = 0;
        SearchFrame nextFrame = frame(ply + 1);

        for (int i = 0; i < nMoves; i++) {
            int mv = scoredMoves.get(i).move;

            // Q-Search Pruning (Futility Pruning + SEE Pruning)
            if (!inCheck) {

                // Futility Pruning (Delta Pruning)
                if (bestValue >= -SCORE_MATE_IN_MAX_PLY && futilityValue <= alpha) {
                    // We check if the gain is at least 1 centipawn.
                    if (moveOrderer.see(bb, mv) < 1) {
                        bestValue = Math.max(bestValue, futilityValue);
                        continue;
                    }
                }

                // SEE Pruning (Threshold -107 from C++)
                if (moveOrderer.see(bb, mv) < -107) {
                    // Assuming moves are ordered reasonably well, we can break early.
                    break;
                }
            }

            // FIX: Determine move info BEFORE making the move.
            int moverPiece = (mv >>> 16) & 0xF;
            int capturedPiece = getCapturedPieceType(bb, mv);

            // Make Move (handles legality check)
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
            legalMovesFound++;

            nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

            // Update stack context
            stack.move = mv;
            stack.movedPiece = moverPiece;

            // Recursive Q-Search call
            int value = -qsearch(bb, -beta, -alpha, ply + 1, nt);

            // Undo Move
            pf.undoMoveInPlace(bb);

            // FIX: Use the locally stored captured piece info for NNUE undo.
            nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);

            if (pool.isStopped()) return 0;

            // Update Results
            if (value > bestValue) {
                bestValue = value;
                bestMove = mv;

                if (value > alpha) {
                    alpha = value;

                    if (pvNode) {
                        stack.setPV(mv, nextFrame);
                    }

                    if (value >= beta) {
                        break; // Beta Cutoff
                    }
                }
            }
        }

        // Checkmate
        if (inCheck && legalMovesFound == 0) {
            return matedIn(ply);
        }

        // --- 5. TT Store ---
        int flag = (bestValue >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestValue > originalAlpha) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        // Store with depth 0
        tt.store(ttIndex, key, flag, 0, bestMove, bestValue, unadjustedEval, ttPv, ply);

        return bestValue;
    }

    // ========================================================================================
    // Utility Functions and Heuristics Implementation
    // ========================================================================================

    private boolean shouldStop() {
        // Check global stop flag first.
        if (pool.isStopped()) return true;

        // Only check time periodically (every 2048 nodes).
        if ((nodes & 2047) != 0) return false;

        // Only the main thread manages time and stops the pool.
        if (isMainThread && pool.shouldStop(startTimeMs, false)) {
            pool.stopSearch();
            return true;
        }
        return false;
    }

    private int drawEval() {
        // Small variation based on node count to avoid contempt=0 blindness (C++ logic)
        return (int)(4 - (nodes & 3));
    }

    private int mateIn(int ply) {
        return SCORE_MATE - ply;
    }

    private int matedIn(int ply) {
        return -SCORE_MATE + ply;
    }

    // Check if the position occurred at least once before (C++ hasUpcomingRepetition)
    private boolean isRepetition(long[] bb, int ply) {
        final long currentHash = bb[HASH];
        // Only check repetitions within the bounds of the 50-move rule.
        final int halfmoveClock = (int) PositionFactory.halfClock(bb[META]);

        // Iterate backwards, checking every second ply (same side to move).
        for (int i = 2; i <= halfmoveClock; i += 2) {
            int prevPly = ply - i;
            long previousHash;

            if (prevPly < 0) {
                // Look into the actual game history (before the root).
                int gameHistoryIdx = gameHistory.size() + prevPly;
                if (gameHistoryIdx >= 0) {
                    previousHash = gameHistory.get(gameHistoryIdx);
                } else {
                    break; // Reached the start of the game history.
                }
            } else {
                // Look into the current search path history.
                if (prevPly < searchPathHistory.length) {
                    previousHash = searchPathHistory[prevPly];
                } else {
                    // Should not happen if MAX_PLY is correctly set, but safe check.
                    break;
                }
            }

            if (previousHash == currentHash) {
                return true;
            }
        }
        return false;
    }

    private boolean isCapture(long[] bb, int move) {
        // A move is a capture if it captures a piece (including en-passant).
        return getCapturedPieceType(bb, move) != -1;
    }

    // Helper to get the captured piece (0-11), BEFORE the move is made.
    // This is used primarily for move ordering, pruning heuristics, and NNUE updates.
    private int getCapturedPieceType(long[] bb, int move) {
        int to = move & 0x3F;
        long toBit = 1L << to;
        int moveType = (move >>> 14) & 0x3;
        int moverPiece = (move >>> 16) & 0xF; // Encoded piece type
        boolean isWhiteMover = (moverPiece < 6);

        if (moveType == 2) { // En-passant
            return isWhiteMover ? PositionFactory.BP : PositionFactory.WP;
        }

        // Check for normal captures
        int startP = isWhiteMover ? PositionFactory.BP : PositionFactory.WP;
        int endP = isWhiteMover ? PositionFactory.BK : PositionFactory.WK;

        for (int p = startP; p <= endP; p++) {
            if ((bb[p] & toBit) != 0) {
                return p;
            }
        }
        return -1; // No capture
    }

    // ========================================================================================
    // History Heuristics Management
    // ========================================================================================

    // --- Correction History ---
    // We rely on the implementation in PositionFactory.
    private long getPawnHash(long[] bb) {
        long k = 0;

        // White Pawns
        long wpBits = bb[WP];
        while (wpBits != 0) {
            int sq = Long.numberOfTrailingZeros(wpBits);
            k ^= PIECE_SQUARE[WP][sq];
            wpBits &= wpBits - 1; // Clear the least significant bit
        }

        // Black Pawns
        long bpBits = bb[BP];
        while (bpBits != 0) {
            int sq = Long.numberOfTrailingZeros(bpBits);
            k ^= PIECE_SQUARE[BP][sq];
            bpBits &= bpBits - 1;
        }

        return k;
    }

    private int getCorrectionHistory(long[] bb) {
        int stm = PositionFactory.whiteToMove(bb[META]) ? 0 : 1;
        long pawnHash = getPawnHash(bb);
        int index = (int) (pawnHash & (CORRECTION_HISTORY_SIZE - 1));
        return correctionHistory[stm][index];
    }

    private int correctStaticEval(int eval, long[] bb) {
        // Do not adjust mate scores.
        if (Math.abs(eval) >= SCORE_MATE_IN_MAX_PLY) return eval;

        int history = getCorrectionHistory(bb);
        // Formula: eval + (history * abs(history)) / 16384
        int adjustment = (history * Math.abs(history)) / 16384;
        int adjustedEval = eval + adjustment;

        // Clamp the evaluation within bounds.
        return Math.max(-SCORE_MATE_IN_MAX_PLY + 1, Math.min(SCORE_MATE_IN_MAX_PLY - 1, adjustedEval));
    }

    // Generic history update function using exponential decay.
    private void updateHistoryScore(int[] entry, int index, int bonus, int limit) {
        int oldVal = entry[index];
        // Scaled update formula: bonus - (oldVal * abs(bonus) / limit)
        int scaledBonus = bonus - (int) (((long) oldVal * Math.abs(bonus)) / limit);
        entry[index] = oldVal + scaledBonus;
    }

    private void updateCorrectionHistory(long[] bb, int bonus) {
        int stm = PositionFactory.whiteToMove(bb[META]) ? 0 : 1;
        long pawnHash = getPawnHash(bb);
        int index = (int) (pawnHash & (CORRECTION_HISTORY_SIZE - 1));

        // Update using the specific limit for Correction History.
        updateHistoryScore(correctionHistory[stm], index, bonus, CORRECTION_HISTORY_LIMIT);
    }

    // --- Combined History (Quiet + Continuation or Capture) ---

    public int getCombinedHistory(long[] bb, SearchFrame frame, int move, boolean isCapture) {
        if (isCapture) {
            return getCaptureHistory(bb, move);
        } else {
            // Quiet move: Quiet History + 2 * Continuation History
            return getQuietHistory(bb, move) + 2 * getContinuationHistory(frame, move);
        }
    }

    // --- Quiet History ---

    public int getQuietHistory(long[] bb, int move) {
        int stm = PositionFactory.whiteToMove(bb[META]) ? 0 : 1;
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        return quietHistory[stm][from][to];
    }

    // --- Capture History ---

    public int getCaptureHistory(long[] bb, int move) {
        int moverPiece = ((move >>> 16) & 0xF);
        int to = move & 0x3F;

        int capturedPiece = getCapturedPieceType(bb, move);
        if (capturedPiece == -1) {
            // Handle promotions in history indexing (C++ treats them tactically)
            if (((move >>> 14) & 0x3) == 1) {
                // Approximate as capturing an opposing pawn.
                capturedPiece = (moverPiece < 6) ? BP : WP;
            } else {
                return 0; // Should not happen if isCapture is true.
            }
        }

        return captureHistory[moverPiece][to][capturedPiece];
    }

    // --- Continuation History ---

    public int getContinuationHistory(SearchFrame frame, int move) {
        int moverPiece = ((move >>> 16) & 0xF);
        int to = move & 0x3F;
        // Index into the flattened last dimension.
        int pieceTo = moverPiece * 64 + to;

        int score = 0;

        // Look back 1, 2, and 4 plies (C++ logic)
        SearchFrame f1 = frame.prev(1);
        if (f1 != null && f1.movedPiece != -1) {
            // Access: continuationHistory[prevPiece][prevTarget][pieceTo]
            score += continuationHistory[f1.movedPiece][f1.move & 0x3F][pieceTo];
        }
        SearchFrame f2 = frame.prev(2);
        if (f2 != null && f2.movedPiece != -1) {
            score += continuationHistory[f2.movedPiece][f2.move & 0x3F][pieceTo];
        }
        SearchFrame f4 = frame.prev(4);
        if (f4 != null && f4.movedPiece != -1) {
            score += continuationHistory[f4.movedPiece][f4.move & 0x3F][pieceTo];
        }
        return score;
    }

    // --- History Update Helpers ---

    // Central function to handle all heuristic updates upon a beta cutoff.
    private void updateHeuristicsOnCutoff(long[] bb, SearchFrame frame, int bestMove, int depth, boolean isCapture,
                                          int eval, int alpha, int beta, int achievedValue,
                                          int[] quietMoves, int quietCount, int[] captureMoves, int captureCount) {

        // Calculate bonus (C++ logic)
        int depthComponent = depth;
        // Bonus if the static eval was lower than alpha (suggesting pruning was aggressive).
        if (eval != SCORE_NONE && eval <= alpha) depthComponent++;
        // Bonus if the resulting value significantly beat beta.
        if (achievedValue - 250 > beta) depthComponent++;

        int bonus = HISTORY_BONUS_BASE + HISTORY_BONUS_FACTOR * depthComponent;
        bonus = Math.min(bonus, HISTORY_BONUS_MAX);
        int malus = -bonus;

        if (!isCapture) {
            // 1. Update Killers
            if (frame.killers[0] != bestMove) {
                frame.killers[1] = frame.killers[0];
                frame.killers[0] = bestMove;
            }

            // 2. Update Counter Move
            SearchFrame prevFrame = frame.prev(1);
            if (prevFrame != null && prevFrame.move != 0) {
                int prevFrom = (prevFrame.move >>> 6) & 0x3F;
                int prevTo = prevFrame.move & 0x3F;
                counterMoves[prevFrom][prevTo] = bestMove;
            }

            // 3. Update Quiet and Continuation History (Bonus/Malus)
            updateQuietAndContinuation(bb, frame, bestMove, bonus);
            for (int i = 0; i < quietCount; i++) {
                int mv = quietMoves[i];
                if (mv != bestMove) {
                    updateQuietAndContinuation(bb, frame, mv, malus);
                }
            }
        } else {
            // 4. Update Capture History
            updateSingleCaptureHistory(bb, bestMove, bonus);
            for (int i = 0; i < captureCount; i++) {
                int mv = captureMoves[i];
                if (mv != bestMove) {
                    updateSingleCaptureHistory(bb, mv, malus);
                }
            }
        }
    }

    // Helper to update both Quiet and Continuation history for a single move.
    private void updateQuietAndContinuation(long[] bb, SearchFrame frame, int move, int bonus) {
        int stm = PositionFactory.whiteToMove(bb[META]) ? 0 : 1;
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moverPiece = (move >>> 16) & 0xF;
        int pieceTo = moverPiece * 64 + to;

        // Update Quiet History
        updateHistoryScore(quietHistory[stm][from], to, bonus, HISTORY_MAX);

        // Update Continuation History
        // We need the scaled bonus relative to the current continuation history total.
        int currentContHistory = getContinuationHistory(frame, move);
        // Calculate scaled bonus specifically for continuation history update.
        int scaledBonus = bonus - (int)(((long) currentContHistory * Math.abs(bonus)) / HISTORY_MAX);

        // Apply scaled bonus to the relevant history tables (1, 2, and 4 plies back).
        SearchFrame f1 = frame.prev(1);
        if (f1 != null && f1.movedPiece != -1) {
            continuationHistory[f1.movedPiece][f1.move & 0x3F][pieceTo] += scaledBonus;
        }
        SearchFrame f2 = frame.prev(2);
        if (f2 != null && f2.movedPiece != -1) {
            continuationHistory[f2.movedPiece][f2.move & 0x3F][pieceTo] += scaledBonus;
        }
        SearchFrame f4 = frame.prev(4);
        if (f4 != null && f4.movedPiece != -1) {
            continuationHistory[f4.movedPiece][f4.move & 0x3F][pieceTo] += scaledBonus;
        }
    }

    private void updateSingleCaptureHistory(long[] bb, int move, int bonus) {
        int moverPiece = ((move >>> 16) & 0xF);
        int to = move & 0x3F;
        int capturedPiece = getCapturedPieceType(bb, move);

        if (capturedPiece == -1) {
            // Handle promotions
            if (((move >>> 14) & 0x3) == 1) {
                capturedPiece = (moverPiece < 6) ? BP : WP;
            } else {
                return;
            }
        }
        updateHistoryScore(captureHistory[moverPiece][to], capturedPiece, bonus, HISTORY_MAX);
    }




    // ========================================================================================
    // Move Ordering (Integrated Scoring)
    // ========================================================================================

    // Constants for move ordering scores
    private static final long TT_MOVE_SCORE = 200_000_000_000L;
    private static final long CAPTURE_SCORE_BASE = 100_000_000_000L;
    private static final long KILLER_1_SCORE = 90_000_000L;
    private static final long KILLER_2_SCORE = 80_000_000L;
    private static final long COUNTERMOVE_SCORE = 70_000_000L;

    // Calculate move score based on heuristics (Mirrors C++ MoveGen scoring logic)
    private long calculateMoveScore(long[] bb, SearchFrame frame, int move, int ttMove, boolean isCapture) {
        if (move == ttMove) {
            return TT_MOVE_SCORE;
        }

        if (isCapture) {
            // Use Capture History for captures
            long score = CAPTURE_SCORE_BASE;
            score += getCaptureHistory(bb, move);
            // MVV-LVA could be added here as a tie-breaker if history is low, but C++ relies heavily on the history score.
            return score;
        } else {
            // Quiet moves
            if (move == frame.killers[0]) return KILLER_1_SCORE;
            if (move == frame.killers[1]) return KILLER_2_SCORE;

            // Countermove heuristic
            SearchFrame prevFrame = frame.prev(1);
            if (prevFrame != null && prevFrame.move != 0) {
                int prevFrom = (prevFrame.move >>> 6) & 0x3F;
                int prevTo = prevFrame.move & 0x3F;
                if (move == counterMoves[prevFrom][prevTo]) {
                    return COUNTERMOVE_SCORE;
                }
            }

            // History + Continuation History
            return getCombinedHistory(bb, frame, move, false);
        }
    }

    // Placeholder SEE Implementation (CRITICAL: Must be replaced with a real SEE implementation)
    private static class SEEPlaceholder implements MoveOrderer {
        private final SearchWorkerImpl worker;

        SEEPlaceholder(SearchWorkerImpl worker) {
            this.worker = worker;
        }

        @Override
        public int see(long[] bb, int move) {
            // !!! PLACEHOLDER IMPLEMENTATION !!!
            // This returns only the value of the captured piece. A real SEE implementation is required.
            int capturedPiece = worker.getCapturedPieceType(bb, move);
            if (capturedPiece != -1) {
                return getPieceValue(capturedPiece);
            }
            return 0;
        }

        // Helper for basic piece values
        private int getPieceValue(int pieceConst) {
            if (pieceConst == -1) return 0;
            int type = pieceConst % 6;
            switch (type) {
                case 0: return 100; // Pawn
                case 1: return 300; // Knight
                case 2: return 300; // Bishop
                case 3: return 500; // Rook
                case 4: return 900; // Queen
                case 5: return 10000; // King
            }
            return 0;
        }

        @Override
        public void orderMoves(long[] bb, int[] moves, int nMoves, int ttMove, int[] killers) {
            // Used only for simple MVV-LVA ordering in ProbCut if needed.
            // The main search ordering is handled by calculateMoveScore.
        }

        @Override
        public int seePrune(long[] bb, int[] moves, int nMoves) {
            return nMoves;
        }
    }


    // ========================================================================================
    // Interface Implementation (Worker Management)
    // ========================================================================================

    @Override
    public void prepareForSearch(long[] root, SearchSpec s, PositionFactory p, MoveGenerator m, TranspositionTable t, TimeManager timeMgr) {
        this.rootBoard = root.clone();
        this.spec = s;
        this.pf = p;
        this.mg = m;
        this.tt = t;
        this.gameHistory = s.history();
        // History heuristics persist across searches.
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

    @Override
    public void setInfoHandler(InfoHandler handler) {
        this.ih = handler;
    }

    @Override
    public SearchResult getSearchResult() {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;

        if (rootMoves.isEmpty()) {
            // Ensure we return total nodes even if search stopped before completing depth 1
            return new SearchResult(0, 0, new ArrayList<>(), 0, false, 0, pool.totalNodes(), elapsedMs);
        }

        // Return the best result found (already sorted)
        RootMove best = rootMoves.get(0);
        int bestMove = best.pv.get(0);
        int ponderMove = best.pv.size() > 1 ? best.pv.get(1) : 0;
        boolean mateScore = Math.abs(best.value) >= SCORE_MATE_IN_MAX_PLY;

        return new SearchResult(bestMove, ponderMove, best.pv, best.value, mateScore, best.depth, pool.totalNodes(), elapsedMs);
    }

    @Override
    public long getNodes() {
        return nodes;
    }

    @Override
    public void terminate() {
        mutex.lock();
        try {
            quit = true;
            searching = true; // Wake up if idle
            startCondition.signal();
        } finally {
            mutex.unlock();
        }
    }

    @Override
    public void join() throws InterruptedException { /* Handled by pool executor */ }
}