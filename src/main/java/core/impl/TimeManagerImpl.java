// File: TimeManagerImpl.java
package core.impl;

import core.contracts.PositionFactory;
import core.contracts.TimeManager;
import core.contracts.UciOptions;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * A time manager that directly ports the core logic from the Lizard C# engine.
 * It calculates a soft (optimal) and hard (maximum) time limit for each move,
 * and uses less time during the opening phase.
 */
public final class TimeManagerImpl implements TimeManager {

    private static final int DEFAULT_MOVE_OVERHEAD_MS = 30;
    private static final int DEFAULT_MOVES_TO_GO      = 20;

    private final UciOptions options;

    public TimeManagerImpl(UciOptions options) {
        this.options = options;
    }

    @Override
    public TimeAllocation calculate(SearchSpec spec, long[] positionState) {

        long meta = positionState[PositionFactory.META];
        boolean isWhiteToMove = PositionFactory.whiteToMove(meta);
        long fullMoveNumber = PositionFactory.fullMove(meta);

        // Handle "go infinite / ponder" -> think forever
        if (spec.infinite() || spec.ponder()) {
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        // Handle strict "movetime N" from the GUI
        if (spec.moveTimeMs() > 0) {
            long overhead = moveOverhead();
            long time = Math.max(1, spec.moveTimeMs() - overhead);
            return new TimeAllocation(time, time);
        }

        long myTime = isWhiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long myInc = isWhiteToMove ? spec.wIncMs() : spec.bIncMs();

        // If no time is specified for the side to move, search indefinitely.
        if (myTime <= 0) {
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        int movesToGo = spec.movesToGo() > 0 ? spec.movesToGo() : DEFAULT_MOVES_TO_GO;

        // *** This logic is a direct port of Lizard's TimeManager.MakeMoveTime() ***

        // Calculate the hard limit for the search.
        long maximumTime = myInc + Math.max(myTime / 2, myTime / movesToGo);
        maximumTime = Math.min(maximumTime, myTime);

        // Calculate the soft limit (optimal time).
        double optimalTime = 0.65 * ((double)myTime / movesToGo + (double)myInc * 3 / 4);

        // Use less time in the opening phase.
        if (fullMoveNumber < 10) {
            // Scale time from ~60% at move 1 up to 100% by move 10
            optimalTime *= (0.6 + fullMoveNumber * 0.04);
        }

        // Apply a small overhead to prevent losing on time due to network latency.
        long overhead = moveOverhead();
        maximumTime = Math.max(1, maximumTime - overhead);
        optimalTime = Math.max(1, optimalTime - overhead);

        return new TimeAllocation((long)optimalTime, maximumTime);
    }

    private int moveOverhead() {
        String v = options.getOptionValue("Move Overhead");
        return v != null ? Integer.parseInt(v) : DEFAULT_MOVE_OVERHEAD_MS;
    }
}