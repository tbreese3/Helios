// C:/dev/Helios/src/main/java/core/impl/TimeManagerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.PositionFactory;
import core.contracts.TimeManager;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * A robust time management system based on proven engine principles. It allocates
 * a reliable baseline time for the move, leaving fine-grained adjustments to the
 * in-search heuristics.
 */
public final class TimeManagerImpl implements TimeManager {

    public TimeManagerImpl() {}

    @Override
    public TimeAllocation calculate(SearchSpec spec, long[] boardState) {
        if (spec.infinite() || spec.ponder()) {
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        if (spec.moveTimeMs() > 0) {
            long time = Math.max(1, spec.moveTimeMs() - CoreConstants.TM_OVERHEAD_MS);
            return new TimeAllocation(time, time);
        }

        boolean isWhiteToMove = PositionFactory.whiteToMove(boardState[PositionFactory.META]);
        long playerTime = isWhiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long playerInc = isWhiteToMove ? spec.wIncMs() : spec.bIncMs();

        if (playerTime <= 0) {
            return new TimeAllocation(1, 2);
        }

        playerTime = Math.max(1, playerTime - CoreConstants.TM_OVERHEAD_MS);

        // Use a fixed move horizon for stability. In games with increment, we add it.
        int movesToGo = spec.movesToGo() > 0 ? spec.movesToGo() : CoreConstants.TM_MOVE_HORIZON;
        long idealTime = (playerTime / movesToGo) + playerInc;

        // The soft limit is our ideal time.
        long softTimeMs = idealTime;

        // Hard time is a generous multiple of the ideal time, capped by remaining time.
        // This gives the search algorithm maximum control over when to stop.
        long hardTimeMs = softTimeMs * 5;
        hardTimeMs = Math.min(hardTimeMs, playerTime - 100);

        // Basic sanity checks.
        softTimeMs = Math.min(softTimeMs, hardTimeMs > 50 ? hardTimeMs - 50 : hardTimeMs);

        return new TimeAllocation(Math.max(1, softTimeMs), Math.max(1, hardTimeMs));
    }
}