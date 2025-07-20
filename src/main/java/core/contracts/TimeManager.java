// C:/dev/Helios/src/main/java/core/contracts/TimeManager.java
package core.contracts;

import core.records.SearchSpec;

/**
 * Defines the contract for a stateful time manager that calculates and tracks
 * thinking time for a single search. An implementation must be configured via the
 * {@link #set(SearchSpec, long[])} method before each search begins.
 */
public interface TimeManager {

    /**
     * Configures the time limits for the upcoming search based on UCI parameters.
     * This method must be called before starting each new search.
     *
     * @param spec The search specification from the GUI (containing wtime, btime, etc.).
     * @param boardState The current board state, used to determine whose time to use.
     */
    void set(SearchSpec spec, long[] boardState);

    /**
     * Checks if the hard time limit has been exceeded. This is used for periodic checks
     * inside the search algorithm to force a stop.
     *
     * @return {@code true} if time is up, {@code false} otherwise.
     */
    boolean shouldStop();

    /**
     * Checks if the soft time limit has been exceeded. This is used to stop
     * iterative deepening and decide when to commit to a move.
     *
     * @return {@code true} if the soft limit is passed, {@code false} otherwise.
     */
    boolean shouldStopIterativeDeepening();

    /**
     * Gets the elapsed time since the search started.
     * @return Elapsed time in milliseconds.
     */
    long timePassedMs();
}