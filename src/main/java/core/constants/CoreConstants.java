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
     * Time Management Heuristics
     * ======================================================================== */

    /**
     * The minimum depth required before the time-extension heuristics are activated.
     */
    public static final int TM_HEURISTICS_MIN_DEPTH = 4;

    /**
     * Coefficients for the Best Move Stability heuristic. A lower value for
     * low stability (the first element) makes the engine less likely to extend
     * the search when the best move changes.
     */
    public static final double[] TM_STABILITY_COEFF = {1.4, 1.2, 1.1, 1.0, 1.0, 0.95, 0.9};

    /**
     * Multiplier for the Node Time Management heuristic. A lower value reduces the
     * impact of node distribution on time extension.
     */
    public static final double TM_NODE_TM_MULT = 1.35;

    /**
     * Factor for the Score Stability heuristic. A lower value reduces the impact
     * of evaluation swings on time extension.
     */
    public static final double TM_SCORE_STABILITY_FACTOR = 0.025;


    /* ========================================================================
     * Late Move Reduction (LMR) Constants
     * ======================================================================== */
    public static final int LMR_MIN_DEPTH = 3;
    public static final int LMR_MIN_MOVE_COUNT = 2;
}
