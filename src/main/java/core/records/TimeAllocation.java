// File: TimeAllocation.java
package core.records;

/**
 * A record to hold the calculated thinking times for a move.
 *
 * @param optimal The soft target time in milliseconds the engine should aim for.
 * @param maximum The hard limit in milliseconds the engine must not exceed.
 */
public record TimeAllocation(long optimal, long maximum) {}