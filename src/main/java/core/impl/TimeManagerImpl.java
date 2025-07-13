// C:\dev\Helios\src\main\java\core\impl\TimeManagerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.TimeManager;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * A hybrid time management system that calculates both a soft (optimal)
 * and hard (maximum) time limit for each move, ensuring responsiveness
 * without overstepping the clock.
 */
public final class TimeManagerImpl implements TimeManager {

    private static final int DEFAULT_MOVES_TO_GO = 40;

    public TimeManagerImpl() {
        // This constructor is now dependency-free.
    }

    @Override
    public TimeAllocation calculate(SearchSpec spec, boolean isWhiteToMove) {

        if (spec.infinite() || spec.ponder()) {
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        if (spec.moveTimeMs() > 0) {
            long time = Math.max(1, spec.moveTimeMs() - CoreConstants.MOVE_TIME_BUFFER);
            return new TimeAllocation(time, time);
        }

        long playerTime = isWhiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long playerInc = isWhiteToMove ? spec.wIncMs() : spec.bIncMs();

        if (playerTime <= 0) {
            return new TimeAllocation(1, 2); // Return a minimal time to avoid errors
        }

        int movesToGo = spec.movesToGo() > 0 ? spec.movesToGo() : DEFAULT_MOVES_TO_GO;

        // --- Hybrid Time Calculation ---
        // 1. Calculate a base allocation for the move.
        long baseAllocation = (playerTime / movesToGo);

        // 2. Soft time is a fraction of the base, plus most of the increment.
        long softTime = (long) (baseAllocation * 0.75) + (playerInc * 3 / 4);

        // 3. Hard time is more generous but has a firm safety brake.
        long hardTime = (long) (baseAllocation * 2.2);

        // 4. Apply safety nets to prevent time forfeiture.
        // The hard limit should not risk flagging. Leave a 150ms buffer.
        hardTime = Math.min(hardTime, playerTime - 150);

        // The soft limit must be less than the hard limit.
        softTime = Math.min(softTime, hardTime - 50);

        // Ensure we always have at least a few milliseconds to think.
        return new TimeAllocation(Math.max(1, softTime), Math.max(1, hardTime));
    }
}