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
     * The fixed network/GUI overhead subtracted from our thinking time.
     */
    public static final int TM_OVERHEAD_MS = 30;

    /**
     * The minimum depth required before in-search time extensions are considered.
     */
    public static final int TM_HEURISTICS_MIN_DEPTH = 7;

    /**
     * The maximum factor by which the ideal time can be extended. A key safety valve.
     */
    public static final double TM_MAX_EXTENSION_FACTOR = 4.0;

    /**
     * Time is extended by this factor if the score drops unexpectedly, suggesting a
     * miscalculation in the previous iteration.
     */
    public static final double TM_SCORE_DROP_FACTOR = 1.25;
    public static final int    TM_SCORE_DROP_MARGIN_CP = 18;

    /**
     * Time is extended by this factor if the best move changes, indicating instability.
     */
    public static final double TM_PV_CHANGE_FACTOR = 1.15;

    /**
     * Time is reduced by this factor in clearly winning/losing positions to avoid
     * wasting time when the result is not in doubt.
     */
    public static final double TM_WINNING_SCORE_REDUCTION_FACTOR = 0.75;
    public static final int    TM_WINNING_SCORE_THRESHOLD_CP = 900;

    /* ========================================================================
     * Late Move Reduction (LMR) Constants
     * ======================================================================== */
    public static final int LMR_MIN_DEPTH = 3;
    public static final int LMR_MIN_MOVE_COUNT = 2;
}
