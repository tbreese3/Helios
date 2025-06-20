package core.impl;

import core.contracts.TimeManager;
import core.contracts.UciOptions;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/** 1-for-1 port of Stockfish’s TimeMan::calcOptimumTime() */
public final class TimeManagerImpl implements TimeManager {

    private static final int DEFAULT_MOVE_OVERHEAD_MS = 50;
    private static final int DEFAULT_MOVES_TO_GO      = 50;  // ← Stockfish uses 50

    private final UciOptions options;

    public TimeManagerImpl(UciOptions options) {
        this.options = options;
    }

    @Override
    public TimeAllocation calculate(SearchSpec spec, boolean whiteToMove) {

        /* “go infinite / ponder” → think forever */
        if (spec.infinite() || spec.ponder())
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);

        /* strict “movetime N” from the GUI */
        if (spec.moveTimeMs() > 0) {
            long slice = Math.max(1, spec.moveTimeMs() - moveOverhead());
            return new TimeAllocation(slice, slice);
        }

        /* regular clock */
        long myTime = whiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long myInc  = whiteToMove ? spec.wIncMs()  : spec.bIncMs();

        int  mtg    = spec.movesToGo() > 0
                ? Math.min(spec.movesToGo(), 50)   // Stockfish caps at 50
                : DEFAULT_MOVES_TO_GO;

        long overhead = moveOverhead();

        /* “effective” remaining time (SF formula) */
        long timeLeft = Math.max(
                1,
                myTime + myInc * (mtg - 1) - overhead * (2 + mtg)
        );

        /* decide how much of it we spend now */
        double optScale;
        if (spec.movesToGo() == 0) {                // sudden-death
            optScale = Math.min(0.025,
                    0.214 * myTime / (double) timeLeft);
        } else {                                    // fixed moves-to-go
            optScale = Math.min(0.95 / mtg,
                    0.88 * myTime / (double) timeLeft);
        }

        long optimum = Math.max(1, (long) (optScale * timeLeft));
        long maximum = Math.max(1, (long) (myTime * 0.8) - overhead);

        if (optimum > maximum) optimum = maximum;   // paranoia

        return new TimeAllocation(optimum, maximum);
    }

    /* helper ---------------------------------------------------------------- */

    private int moveOverhead() {
        String v = options.getOptionValue("Move Overhead");
        return v != null ? Integer.parseInt(v) : DEFAULT_MOVE_OVERHEAD_MS;
    }
}
