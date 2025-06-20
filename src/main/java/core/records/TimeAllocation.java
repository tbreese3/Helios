package core.records;

/**
 * A record to hold the calculated optimal and maximum thinking time for a move.
 *
 * @param optimal The target time in milliseconds the engine should aim for.
 * @param maximum The hard limit in milliseconds the engine must not exceed.
 */
public record TimeAllocation(long optimal, long maximum) {}