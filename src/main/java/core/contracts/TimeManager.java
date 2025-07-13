// C:/dev/Helios/src/main/java/core/contracts/TimeManager.java
package core.contracts;

import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * Calculates the optimal and maximum thinking time for a single move.
 */
public interface TimeManager {

    /**
     * Calculates the time allocation for the upcoming search.
     * @param spec The search specification from the GUI (containing wtime, btime, etc.).
     * @param boardState The current board state as a long array.
     * @return A {@link TimeAllocation} record containing the optimal and maximum move times.
     */
    TimeAllocation calculate(SearchSpec spec, long[] boardState);
}