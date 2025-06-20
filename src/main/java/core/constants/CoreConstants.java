package core.constants;

public class CoreConstants {
    /* Score constants */
    public static final int MAX_PLY = 127;
    public static final int SCORE_MATE = 32000;
    public static final int SCORE_MATE_IN_MAX_PLY = SCORE_MATE - MAX_PLY;
    public static final int SCORE_TB_WIN_IN_MAX_PLY  = SCORE_MATE_IN_MAX_PLY - 1;
    public static final int SCORE_TB_LOSS_IN_MAX_PLY = -SCORE_TB_WIN_IN_MAX_PLY;
    public static final int SCORE_NONE = 32002;
    public static final int SCORE_DRAW = 0;

    /* Search constants */
    public static final int ASP_WINDOW_START_DEPTH = 5;
    public static final int ASP_WINDOW_INITIAL_DELTA = 15;

    public static final int QSEARCH_MAX_PLY = 8;
}
