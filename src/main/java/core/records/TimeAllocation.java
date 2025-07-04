package core.records;

/**
 * A record to hold the calculated soft and maximum thinking time for a move.
 *
 * @param soft The target time in milliseconds the engine should aim for (soft limit).
 * @param maximum The hard limit in milliseconds the engine must not exceed.
 */
public record TimeAllocation(long soft, long maximum) {}