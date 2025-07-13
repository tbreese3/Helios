// C:/dev/Helios/src/main/java/core/impl/TimeManagerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.TimeManager;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * A hybrid time management system that calculates both a soft (optimal)
 * and hard (maximum) time limit for each move.
 */
public final class TimeManagerImpl implements TimeManager {

    private static final int DEFAULT_MOVES_TO_GO = 40;

    public TimeManagerImpl() {}

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
            return new TimeAllocation(1, 2);
        }

        int movesToGo = spec.movesToGo() > 0 ? spec.movesToGo() : DEFAULT_MOVES_TO_GO;
        long baseAllocation = (playerTime / movesToGo);

        // Make the initial soft time slightly more conservative.
        // This gives more control to the in-search extension logic.
        long softTime = (long) (baseAllocation * 0.65) + (playerInc / 2);
        long hardTime = (long) (baseAllocation * 2.5); // Allow a slightly higher hard ceiling

        // Apply safety nets
        hardTime = Math.min(hardTime, playerTime - 150);
        softTime = Math.min(softTime, hardTime - 50);

        return new TimeAllocation(Math.max(1, softTime), Math.max(1, hardTime));
    }
}