// File: MaterialOnlyEvaluator.java
package core.impl;

import core.contracts.Evaluator;
import core.contracts.PositionFactory;

/**
 * Evaluation that considers **material only** – no PSTs, no bishop-pair,
 * no pawn-structure, no side-to-move bonus.
 * <p>
 * The score is returned from the point of view of the side
 * that is *about to move* (positive = good for the mover).
 * <p>
 * Thread-safe and allocation-free.
 */
public final class EvaluatorImpl implements Evaluator {

    /** Piece values in centipawns, aligned with PositionFactory indices. */
    private static final int[] VALUE = {
            100, 320, 330, 500, 900, 0,   // White P N B R Q K
            100, 320, 330, 500, 900, 0    // Black p n b r q k
    };

    @Override
    public int evaluate(long[] bb) {

        int white = 0;
        int black = 0;

        /* --- count pieces --- */
        for (int p = PositionFactory.WP; p <= PositionFactory.WK; ++p) {
            int cnt = Long.bitCount(bb[p]);
            white += cnt * VALUE[p];
        }
        for (int p = PositionFactory.BP; p <= PositionFactory.BK; ++p) {
            int cnt = Long.bitCount(bb[p]);
            black += cnt * VALUE[p];
        }

        int diff = white - black;  // >0 : White ahead

        // make the score relative to the *side to move*
        long meta = bb[PositionFactory.META];
        return PositionFactory.whiteToMove(meta) ? diff : -diff;
    }

    @Override
    public void reset() {
        /* Stateless – nothing to reset. */
    }
}
