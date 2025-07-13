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
     * Fixed network/GUI overhead subtracted from our thinking time.
     */
    public static final int TM_OVERHEAD_MS = 50;

    /**
     * The minimum depth to activate confidence-based heuristics.
     */
    public static final int TM_CONFIDENCE_MIN_DEPTH = 8;

    /**
     * The absolute maximum factor by which ideal time can be extended.
     */
    public static final double TM_CONFIDENCE_MAX_EXTENSION = 3.5;

    /**
     * A large, one-time bonus factor when a previously stable move is suddenly
     * overturned, indicating a critical moment of re-evaluation.
     */
    public static final double TM_PV_DISRUPTION_BONUS = 1.6;

    /**
     * A factor to *reduce* thinking time when the search is stable and confident.
     * A value of 0.9 means use only 90% of the planned time for this iteration.
     */
    public static final double TM_STABILITY_REDUCTION = 0.90;

    /**
     * A small bonus factor for uncertain, drawish positions where the score
     * oscillates around zero.
     */
    public static final double TM_OSCILLATION_BONUS = 1.1;
    public static final int TM_OSCILLATION_SCORE_MARGIN_CP = 40;

    /**
     * A factor to drastically reduce time in winning/losing positions.
     */
    public static final double TM_WINNING_REDUCTION = 0.6;
    public static final int TM_WINNING_THRESHOLD_CP = 850;

    /* ========================================================================
     * Late Move Reduction (LMR) Constants
     * ======================================================================== */
    public static final int LMR_MIN_DEPTH = 3;
    public static final int LMR_MIN_MOVE_COUNT = 2;
}
