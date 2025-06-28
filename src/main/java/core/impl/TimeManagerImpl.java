// C:\dev\Helios\src\main\java\core\impl\TimeManagerImpl.java
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
    public TimeAllocation calculate(SearchSpec spec, boolean isWhiteToMove, int fullMoveNumber) {

        /* “go infinite / ponder” → think forever */
        if (spec.infinite() || spec.ponder())
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);

        long overhead = moveOverhead();

        /* strict “movetime N” from the GUI */
        if (spec.moveTimeMs() > 0) {
            long slice = Math.max(1, spec.moveTimeMs() - overhead);
            return new TimeAllocation(slice, slice);
        }

        /* regular clock */
        long myTime = isWhiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long myInc  = isWhiteToMove ? spec.wIncMs()  : spec.bIncMs();

        // If we have very little time, play very fast.
        if (myTime < overhead * 2) {
            return new TimeAllocation(1, Math.max(1, myTime - overhead));
        }

        int  mtg    = spec.movesToGo() > 0
                ? Math.min(spec.movesToGo(), 50)   // Stockfish caps at 50
                : DEFAULT_MOVES_TO_GO;

        /* “effective” remaining time (SF formula) */
        long timeLeft = Math.max(
                1,
                myTime + myInc * (mtg - 1) - overhead * (2 + mtg)
        );

        /* decide how much of it we spend now */
        double optScale;
        if (spec.movesToGo() == 0) {  // sudden-death
            // Spend less time in the opening. The divisor starts high and lowers as the game progresses.
            // Original logic was equivalent to a fixed divisor of 40 (1 / 0.025).
            // This new logic varies it from ~65 down to 35.
            double divisor = 35.0 + Math.max(0, 20 - fullMoveNumber) * 1.5;
            optScale = 1.0 / divisor;

            // Retain the part of the original logic that scales with the ratio of time left
            optScale = Math.min(optScale, 0.88 * myTime / (double) timeLeft);

        } else { // fixed moves-to-go (original logic is fine here)
            optScale = Math.min(0.95 / mtg,
                    0.88 * myTime / (double) timeLeft);
        }

        long optimum = Math.max(1, (long) (optScale * timeLeft));

        // A much more sensible maximum time. This prevents losing on time by spending
        // too long on one move. A multiple of the optimum is a robust heuristic.
        long maximum = Math.min((long)(myTime * 0.9) - overhead, optimum * 4);

        if (optimum > maximum) optimum = maximum;   // paranoia

        return new TimeAllocation(Math.max(1, optimum), Math.max(1, maximum));
    }

    /* helper ---------------------------------------------------------------- */

    private int moveOverhead() {
        String v = options.getOptionValue("Move Overhead");
        return v != null ? Integer.parseInt(v) : DEFAULT_MOVE_OVERHEAD_MS;
    }
}