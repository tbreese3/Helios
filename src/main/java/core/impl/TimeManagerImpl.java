// C:/dev/Helios/src/main/java/core/impl/TimeManagerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.PositionFactory;
import core.contracts.TimeManager;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * A time management system based on the principles of modern engines like Stockfish.
 * It dynamically allocates time based on the game situation.
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

        // Subtract a small overhead to prevent losing on time due to latency
        playerTime = Math.max(1, playerTime - CoreConstants.TM_OVERHEAD_MS);

        long idealTime;
        long maxTime;

        if (spec.movesToGo() > 0) {
            // Time control with a fixed number of moves
            idealTime = (playerTime / spec.movesToGo()) + (playerInc * 3 / 4);
        } else {
            // Dynamic time control based on game stage
            long moveNumber = PositionFactory.fullMove(boardState[PositionFactory.META]);
            int movesLeft = getDynamicMovesLeft(moveNumber);
            idealTime = (playerTime / movesLeft) + (playerInc * 3 / 4);
        }

        // The "soft" time is our ideal time. The search will aim for this.
        long softTimeMs = idealTime;

        // The "hard" time is a multiple of the ideal time, but capped by the remaining time.
        // This gives the search flexibility but prevents a catastrophic time loss.
        maxTime = idealTime * 4;
        maxTime = Math.min(maxTime, playerTime - 50); // Don't use all the clock

        return new TimeAllocation(Math.max(1, softTimeMs), Math.max(1, maxTime));
    }

    /**
     * Estimates the number of moves remaining in the game.
     * Becomes more aggressive as the game progresses.
     */
    private int getDynamicMovesLeft(long moveNumber) {
        if (moveNumber < 20) return 60;
        if (moveNumber < 40) return 50;
        if (moveNumber < 60) return 40;
        return 30;
    }
}