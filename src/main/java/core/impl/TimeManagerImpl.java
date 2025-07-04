package core.impl;

import core.contracts.TimeManager;
import core.contracts.UciOptions;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * Time management logic based on the Lizard chess engine.
 * It calculates a soft time limit for normal search termination and a hard
 * maximum time limit.
 */
public final class TimeManagerImpl implements TimeManager {

    private static final int DEFAULT_MOVES_TO_GO = 20; // From Lizard
    private static final int MOVE_TIME_BUFFER = 5;     // From Lizard
    private final UciOptions options;

    public TimeManagerImpl(UciOptions options) {
        this.options = options;
    }

    @Override
    public TimeAllocation calculate(SearchSpec spec, boolean isWhiteToMove) {

        /* "go infinite / ponder" -> think forever */
        if (spec.infinite() || spec.ponder()) {
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        /* strict "movetime N" from the GUI */
        if (spec.moveTimeMs() > 0) {
            long time = Math.max(1, spec.moveTimeMs() - MOVE_TIME_BUFFER);
            return new TimeAllocation(time, time);
        }

        long playerTime = isWhiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long playerInc = isWhiteToMove ? spec.wIncMs() : spec.bIncMs();

        if (playerTime <= 0) {
            // Give a tiny amount of time to at least make one move if possible
            return new TimeAllocation(1, 2);
        }

        int movesToGo = spec.movesToGo() > 0 ? spec.movesToGo() : DEFAULT_MOVES_TO_GO;

        // Lizard's logic for MaxSearchTime (hard limit for the move)
        long hardTime = playerInc + Math.max(playerTime / 2, playerTime / movesToGo);
        hardTime = Math.min(hardTime, Math.max(1, playerTime - moveOverhead())); // Don't use more than available time

        // Lizard's logic for SoftTimeLimit
        double softTime = 0.65 * ((double) playerTime / movesToGo + (playerInc * 3.0 / 4.0));

        long softTimeMs = Math.max(1, (long)softTime);
        long hardTimeMs = Math.max(1, hardTime);

        // Soft limit can't exceed hard limit
        if (softTimeMs > hardTimeMs) {
            softTimeMs = hardTimeMs;
        }

        return new TimeAllocation(softTimeMs, hardTimeMs);
    }

    /**
     * Move Overhead is used as a safety buffer to prevent losing on time.
     * Corresponds to Lizard's TimerBuffer.
     */
    private int moveOverhead() {
        String v = options.getOptionValue("Move Overhead");
        return v != null ? Integer.parseInt(v) : 50;
    }
}