// C:/dev/Helios/src/main/java/core/impl/TimeManagerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.PositionFactory;
import core.contracts.TimeManager;
import core.records.SearchSpec;

/**
 * Implements the stateful time management system based on the Serendipity engine.
 * It calculates soft and hard time limits once at the start of the search and uses
 * System.nanoTime() for precise checking.
 */
public final class TimeManagerImpl implements TimeManager {

    private long startTimeNs;
    private long hardLimitTimeStampNs;
    private long softLimitTimeStampNs;

    @Override
    public void set(SearchSpec spec, long[] boardState) {
        // Handle infinite, ponder, or fixed move time cases first.
        if (spec.infinite() || spec.ponder()) {
            this.startTimeNs = System.nanoTime();
            this.softLimitTimeStampNs = Long.MAX_VALUE;
            this.hardLimitTimeStampNs = Long.MAX_VALUE;
            return;
        }

        if (spec.moveTimeMs() > 0) {
            long moveTime = Math.max(1, spec.moveTimeMs() - CoreConstants.TM_MOVE_OVERHEAD_MS);
            this.startTimeNs = System.nanoTime();
            this.softLimitTimeStampNs = this.startTimeNs + moveTime * 1_000_000L;
            this.hardLimitTimeStampNs = this.startTimeNs + moveTime * 1_000_000L;
            return;
        }

        // Standard time controls (sudden death or with moves to go).
        boolean isWhiteToMove = PositionFactory.whiteToMove(boardState[PositionFactory.META]);
        long timeLeft = isWhiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long increment = isWhiteToMove ? spec.wIncMs() : spec.bIncMs();
        int movesToGo = spec.movesToGo();

        if (timeLeft <= 0) {
            timeLeft = 1000; // A small default if time is somehow negative or zero.
        }

        timeLeft -= Math.min(CoreConstants.TM_MOVE_OVERHEAD_MS, timeLeft) / 2;

        long hardLimitMs;
        long softLimitMs;

        if (movesToGo > 0) {
            // Case 1: Time control with a fixed number of moves to go.
            hardLimitMs = timeLeft / movesToGo + increment * 3 / 4;
            softLimitMs = hardLimitMs / 2;
        } else {
            // Case 2: Sudden death time control.
            int baseTime = (int) (timeLeft * 0.054 + increment * 0.85);
            int maxTime = (int) (timeLeft * 0.76);
            hardLimitMs = Math.min(maxTime, (int) (baseTime * 3.04));
            softLimitMs = Math.min(maxTime, (int) (baseTime * 0.76));
        }

        this.startTimeNs = System.nanoTime();
        this.hardLimitTimeStampNs = this.startTimeNs + hardLimitMs * 1_000_000L;
        this.softLimitTimeStampNs = this.startTimeNs + softLimitMs * 1_000_000L;
    }

    @Override
    public boolean shouldStop() {
        return System.nanoTime() > this.hardLimitTimeStampNs;
    }

    @Override
    public boolean shouldStopIterativeDeepening() {
        return System.nanoTime() > this.softLimitTimeStampNs;
    }

    @Override
    public long timePassedMs() {
        return (System.nanoTime() - this.startTimeNs) / 1_000_000L;
    }
}