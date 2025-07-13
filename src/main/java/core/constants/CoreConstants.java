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
     * The minimum depth required before time-extension heuristics are activated.
     */
    public static final int TM_HEURISTICS_MIN_DEPTH = 5;

    /**
     * The buffer subtracted from a strict 'movetime' command.
     */
    public static final int MOVE_TIME_BUFFER = 20;

    /**
     * The maximum total time extension factor. The soft time limit will never be
     * multiplied by more than this value. This is our key safety valve.
     */
    public static final double TM_MAX_EXTENSION_FACTOR = 2.2;

    /**
     * Additive bonus per ply of best-move stability. E.g., 0.20 means a 20%
     * time bonus for each depth the best move has remained the same.
     */
    public static final double TM_STABILITY_BONUS = 0.20;

    /**
     * Maximum additive bonus from node distribution. A wide, complex search can
     * add up to this much to the time extension factor.
     */
    public static final double TM_NODE_DIST_MAX_BONUS = 0.40;

    /**
     * Maximum additive bonus from score volatility. Large evaluation swings can
     * add up to this much to the time extension factor.
     */
    public static final double TM_SCORE_SWING_MAX_BONUS = 0.35;




    /* ========================================================================
     * Late Move Reduction (LMR) Constants
     * ======================================================================== */
    public static final int LMR_MIN_DEPTH = 3;
    public static final int LMR_MIN_MOVE_COUNT = 2;
}
