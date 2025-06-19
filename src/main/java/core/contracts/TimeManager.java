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
     * @param isWhiteToMove True if it is white's turn to move, false otherwise.
     * @return A {@link TimeAllocation} record containing the optimal and maximum move times.
     */
    TimeAllocation calculate(SearchSpec spec, boolean isWhiteToMove);
}