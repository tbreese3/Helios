// C:/dev/Helios/src/main/java/core/impl/TimeManagerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.PositionFactory;
import core.contracts.TimeManager;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * A dynamic time management system. It allocates an "ideal time" for a move
 * based on the remaining time and the current stage of the game.
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

        long moveNumber = PositionFactory.fullMove(boardState[PositionFactory.META]);
        int movesLeft = getDynamicMovesLeft(moveNumber);

        // In time controls with an increment, we can be more generous.
        long idealTime = (playerTime / movesLeft) + (playerInc * 2 / 3);

        // The soft time is our ideal time, but never too large a chunk of the clock.
        long softTimeMs = Math.min(idealTime, playerTime / 4);

        // The hard time is a generous multiple, but firmly capped.
        long hardTimeMs = softTimeMs * 5;
        hardTimeMs = Math.min(hardTimeMs, playerTime - 100);

        return new TimeAllocation(Math.max(1, softTimeMs), Math.max(1, hardTimeMs));
    }

    /**
     * Estimates the number of moves remaining using a smooth decay function.
     * Assumes more moves left in the opening than in the endgame.
     */
    private int getDynamicMovesLeft(long moveNumber) {
        // This formula smoothly transitions from ~65 moves at the start to ~25 in the lategame.
        return 25 + (int) (40 * Math.exp(-0.045 * (moveNumber - 1)));
    }
}