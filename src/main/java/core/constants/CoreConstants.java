package core.constants;

/**
 * Central place for *all* engine-wide compile-time constants.
 *
 * <p>Anything that is a plain literal value (scores, depth limits,
 * magic numbers, …) belongs here so that the rest of the code has
 * zero hard-wired numbers.</p>
 */
public final class CoreConstants {

    private CoreConstants() {}            // utility class – not instantiable

    /* ────────────── Search / stack limits ────────────── */
    /** Maximum ply this engine will ever search (½-moves). */
    public static final int MAX_PLY = 127;
    /** Maximum ply for quiescence search. */
    public static final int QSEARCH_MAX_PLY = 8;

    /* ─────────────── Evaluation score space ───────────── */
    /** “Infinity” for alpha-beta – must be ≥ every other score. */
    public static final int SCORE_INF = 32_767;

    public static final int SCORE_MATE               = 32_000;
    public static final int SCORE_MATE_IN_MAX_PLY    = SCORE_MATE - MAX_PLY;
    public static final int SCORE_TB_WIN_IN_MAX_PLY  = SCORE_MATE_IN_MAX_PLY - 1;
    public static final int SCORE_TB_LOSS_IN_MAX_PLY = -SCORE_TB_WIN_IN_MAX_PLY;

    /** Used when no meaningful score is available yet. */
    public static final int SCORE_NONE = 32_002;
    /** A perfectly even position. */
    public static final int SCORE_DRAW = 0;
    /** Returned when no legal move but not in check. */
    public static final int SCORE_STALEMATE = SCORE_DRAW;

    /* ─────────────── Aspiration window params ─────────── */
    public static final int ASP_WINDOW_START_DEPTH   = 5;
    public static final int ASP_WINDOW_INITIAL_DELTA = 15;


    /* ========================================================================
     * Time Management - NEW ADDITIVE MODEL CONSTANTS
     * ======================================================================== */


    /**
     * A fixed overhead in milliseconds to account for GUI/network latency.
     */
    public static final int TM_OVERHEAD_MS = 30;

    /**
     * The typical number of moves expected in a game. Used for initial time allocation.
     * This is a simple, robust replacement for dynamic 'moves-left' calculation.
     */
    public static final int TM_MOVE_HORIZON = 50;

    /**
     * The minimum depth required before time extension heuristics are applied.
     */
    public static final int TM_HEURISTICS_MIN_DEPTH = 6;

    /**
     * The maximum factor by which the ideal move time can be extended.
     * This is the essential safety cap.
     */
    public static final double TM_MAX_EXTENSION_FACTOR = 3.5;

    /**
     * The weight of score instability. A larger value makes score swings
     * contribute more to the decision to extend time.
     * Value is multiplied by centipawn difference.
     */
    public static final double TM_INSTABILITY_SCORE_WEIGHT = 0.007;

    /**
     * A flat bonus to the extension factor when the best move (PV) changes,
     * indicating a critical re-assessment of the position.
     */
    public static final double TM_INSTABILITY_PV_CHANGE_BONUS = 0.45;

    /* ========================================================================
     * Late Move Reduction (LMR) Constants
     * ======================================================================== */
    public static final int LMR_MIN_DEPTH = 3;
    public static final int LMR_MIN_MOVE_COUNT = 2;

    /* ========================================================================
     * Futility Pruning (FP) Constants
     * ======================================================================== */
    /** Maximum depth (from the horizon) at which futility pruning is applied. */
    public static final int FP_MAX_DEPTH = 7;
    /** A linear margin per ply of remaining depth. */
    public static final int FP_MARGIN_PER_PLY = 125;
    /** A quadratic margin based on depth^2 for more aggressive pruning. */
    public static final int FP_MARGIN_QUADRATIC = 7;

    /* ───────────── ProbCut (capture-only verification) ───────────── */
    public static final int PROBCUT_MIN_DEPTH = 5;        // enable from this depth
    public static final int PROBCUT_MARGIN_CP = 175;      // rBeta = beta + margin
    public static final int PROBCUT_REDUCTION = 4;        // search at depth - R

    public static final int LMP_MAX_DEPTH      = 6;   // only shallow depths
    public static final int LMP_BASE_MOVES     = 2;   // always see a few moves
    public static final int LMP_DEPTH_SCALE    = 2;   // threshold grows ~depth^2
    public static final int LMP_HIST_MIN       = 50;  // history floor to keep (in cp-ish units)
}
