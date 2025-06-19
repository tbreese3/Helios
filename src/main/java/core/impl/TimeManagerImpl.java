package core.impl;

import core.contracts.TimeManager;
import core.contracts.UciOptions;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * Time control identical to the reference engine.
 *             ───────────────────────────────────
 * The class needs access to the current UCI options because “Move Overhead”
 * is user-tweakable.  We therefore receive a reference to the already-created
 * {@link UciOptionsImpl} once in the constructor.
 */
public final class TimeManagerImpl implements TimeManager {

    /** fallback when the option is not (yet) available                       */
    private static final int DEFAULT_MOVE_OVERHEAD_MS = 50;
    private static final int DEFAULT_MOVES_TO_GO      = 40;

    private final UciOptions options;

    public TimeManagerImpl(UciOptions options) {
        this.options = options;
    }

    @Override
    public TimeAllocation calculate(SearchSpec spec, boolean whiteToMove) {

        // 1. “go infinite / ponder” → no time limit at all
        if (spec.infinite() || spec.ponder())
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);

        // 2.  “movetime N” – GUI forces a fixed per-move slice
        if (spec.moveTimeMs() > 0) {
            long t = Math.max(1, spec.moveTimeMs() - moveOverhead());
            return new TimeAllocation(t, t);
        }

        /* remaining (clock) parameters */
        long myTime = whiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long myInc  = whiteToMove ? spec.wIncMs()  : spec.bIncMs();
        int  mtg    = spec.movesToGo() > 0
                ? Math.min(spec.movesToGo(), 50)
                : DEFAULT_MOVES_TO_GO;

        // Effective remaining time (Stockfish style)
        long timeLeft = Math.max(1,
                myTime + myInc * (mtg - 1) - (moveOverhead() * (2 + mtg)));

        double optScale;
        if (spec.movesToGo() == 0) {                       // sudden-death
            optScale = Math.min(0.025,
                    0.214 * myTime / (double) timeLeft);
        } else {                                           // period control
            optScale = Math.min(0.95 / mtg,
                    0.88 * myTime / (double) timeLeft);
        }

        long opt  = Math.max(1, (long)(optScale * timeLeft));
        long hard = Math.max(1, (long)(myTime * 0.8) - moveOverhead());

        if (opt > hard) opt = hard;
        if (opt > myTime - moveOverhead()) opt = myTime - moveOverhead();

        return new TimeAllocation(opt, hard);
    }

    /** current Move-Overhead (ms) with safe fallback                          */
    private int moveOverhead() {
        String v = options.getOptionValue("Move Overhead");
        return v != null ? Integer.parseInt(v) : DEFAULT_MOVE_OVERHEAD_MS;
    }
}
