package core.impl;

import core.contracts.TimeManager;
import core.records.SearchSpec;
import core.records.TimeAllocation;

/**
 * An advanced time manager that uses a hybrid of logic from established engines.
 * It allocates time dynamically based on remaining time, increment, and moves to go,
 * while also accounting for communication overhead. This implementation is stateless.
 */
public class TimeManagerImpl implements TimeManager {

    // A small buffer to ensure we don't exceed time due to communication latency.
    private static final int MOVE_OVERHEAD_MS = 50;

    // A reasonable assumption for the number of moves in a typical game. Used when
    // the GUI does not provide a "movestogo" value. A lower value allocates more
    // time per move early on.
    private static final int DEFAULT_MOVES_TO_GO = 40;

    @Override
    public TimeAllocation calculate(SearchSpec spec, boolean isWhiteToMove) {
        if (spec.infinite() || spec.ponder()) {
            return new TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        if (spec.moveTimeMs() > 0) {
            long time = Math.max(1, spec.moveTimeMs() - MOVE_OVERHEAD_MS);
            return new TimeAllocation(time, time);
        }

        long time = isWhiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long inc = isWhiteToMove ? spec.wIncMs() : spec.bIncMs();
        int mtg = spec.movesToGo() > 0 ? Math.min(spec.movesToGo(), DEFAULT_MOVES_TO_GO) : DEFAULT_MOVES_TO_GO;

        // Calculate an "effective" time left, considering the increment over the remaining moves.
        long timeLeft = Math.max(1, time + inc * (mtg - 1) - MOVE_OVERHEAD_MS);
        double optScale;

        // Use different scaling factors for "sudden death" vs. "moves to go" time controls.
        if (spec.movesToGo() == 0) { // Sudden Death
            optScale = Math.min(0.025, 0.214 * time / (double) timeLeft);
        } else { // Time Control Period
            optScale = Math.min(0.95 / mtg, 0.88 * time / (double) timeLeft);
        }

        long optimalTime = (long) (optScale * timeLeft);
        // Don't use more than 80% of the remaining time for a single move.
        long maximumTime = (long) (time * 0.8 - MOVE_OVERHEAD_MS);

        // Clamp the values to ensure they are sane.
        optimalTime = Math.max(1, optimalTime);
        maximumTime = Math.max(1, maximumTime);
        optimalTime = Math.min(optimalTime, maximumTime);
        optimalTime = Math.min(optimalTime, time - MOVE_OVERHEAD_MS);

        return new TimeAllocation(optimalTime, maximumTime);
    }
}