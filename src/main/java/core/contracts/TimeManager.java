// File: TimeManager.java
package core.contracts;

import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * Calculates the optimal and maximum thinking time for a single move.
 * This is a stateless service.
 */
public interface TimeManager {

    /**
     * Calculates the time allocation for the upcoming search.
     * @param spec The search specification from the GUI (containing wtime, btime, etc.).
     * @param positionState The current board state array, used to determine move number and side to move.
     * @return A {@link TimeAllocation} record containing the optimal and maximum move times.
     */
    TimeAllocation calculate(SearchSpec spec, long[] positionState);
}