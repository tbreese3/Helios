package core.constants;

/**
 * Central place for *all* engine-wide compile-time constants.
 * This version is heavily inspired by the C++ reference implementation.
 */
public final class CoreConstants {

    private CoreConstants() {}

    /* ────────────── Search / stack limits ────────────── */
    public static final int MAX_PLY = 128;
    public static final int MAX_MOVES = 256; // Max moves in a position

    /* ─────────────── Evaluation score space ───────────── */
    public static final int SCORE_INF = 32767;
    public static final int SCORE_MATE = 32000;
    public static final int SCORE_MATE_IN_MAX_PLY = SCORE_MATE - MAX_PLY;
    public static final int SCORE_TB_WIN_IN_MAX_PLY = SCORE_MATE_IN_MAX_PLY - 1;
    public static final int SCORE_TB_LOSS_IN_MAX_PLY = -SCORE_TB_WIN_IN_MAX_PLY;

    public static final int SCORE_NONE = 32002;
    public static final int SCORE_DRAW = 0;
    public static final int SCORE_STALEMATE = SCORE_DRAW;

    /* ─────────────── Aspiration window params ─────────── */
    // C++ reference: starts at rootDepth >= 4, initial windowSize = 10
    public static final int ASP_WINDOW_MIN_DEPTH = 4;
    public static final int ASP_WINDOW_INITIAL_SIZE = 10;

    /* ========================================================================
     * Time Management (Based on C++ reference stability model)
     * ======================================================================== */

    public static final int TM_OVERHEAD_MS = 30;
    // Typical number of moves remaining (used if movesToGo not provided)
    public static final int TM_DEFAULT_MOVES_TO_GO = 40;

    // Stability factors for time scaling (C++: 1.1 - 0.05 * stability)
    public static final double TM_STABILITY_BASE = 1.1;
    public static final double TM_STABILITY_FACTOR = 0.05;
    public static final int TM_MAX_STABILITY = 8; // C++ clamps stability at 8

    // Maximum time usage factor (C++ reference uses 0.7 to 0.8)
    public static final double TM_MAX_USAGE_FACTOR = 0.75;


    /* ========================================================================
     * Search Heuristics and Pruning Constants
     * ======================================================================== */

    /* ───────────── Late Move Reduction (LMR) ───────────── */
    // C++ Formula: 0.25 + log(i) * log(m) / 2.25
    public static final double LMR_BASE = 0.25;
    public static final double LMR_DIVISOR = 2.25;
    public static final int LMR_MIN_DEPTH = 3;
    // Based on C++ logic: playedMoves > (1 + 2 * PvNode)
    public static final int LMR_MIN_MOVE_COUNT_PV = 3;
    public static final int LMR_MIN_MOVE_COUNT_NON_PV = 1;

    /* ───────────── History Heuristic ───────────── */
    // Max value for history scores (used for scaling/decay)
    public static final int HISTORY_MAX = 16384;
    public static final int HISTORY_BONUS_CAP = 1000; // C++ caps bonus at 1000

    /* ───────────── Razoring ───────────── */
    public static final int RAZORING_MARGIN = 400; // C++: alpha - 400 * depth
    public static final int RAZORING_MAX_DEPTH = 3; // C++ implies depth < 4

    /* ───────────── Reverse Futility Pruning (RFP) / Static Null Move ───────────── */
    public static final int RFP_MAX_DEPTH = 8; // C++: depth < 9
    public static final int RFP_BASE_MARGIN = 140; // C++: 140 * depth
    public static final int RFP_IMPROVING_BONUS = 120; // C++: 120 * improving

    /* ───────────── Null Move Pruning (NMP) ───────────── */
    // C++ R calculation: std::min((eval - beta) / 200, 3) + depth / 3 + 4;
    public static final int NMP_MIN_DEPTH = 3;
    public static final int NMP_DEPTH_BASE = 4;
    public static final int NMP_DEPTH_DIVISOR = 3;
    public static final int NMP_EVAL_DIVISOR = 200;
    public static final int NMP_EVAL_CAP = 3;

    /* ───────────── Internal Iterative Reduction (IIR) ───────────── */
    public static final int IIR_MIN_DEPTH = 4;
    public static final int IIR_REDUCTION_CUTNODE = 2;

    /* ───────────── Late Move Pruning (LMP) ───────────── */
    // C++ formula: (3 * depth * depth + 9) / (2 - improving)
    public static final int LMP_BASE = 9;
    public static final int LMP_DEPTH_FACTOR = 3;

    /* ───────────── SEE Pruning ───────────── */
    public static final int SEE_PRUNING_TACTICAL_MARGIN = -140; // C++: -140 * depth
    public static final int SEE_QSEARCH_MARGIN = -50; // C++: -50

    /* ───────────── Singular Extensions ───────────── */
    public static final int SINGULAR_MIN_DEPTH = 6;
    public static final int SINGULAR_TT_DEPTH_MARGIN = 3;
}