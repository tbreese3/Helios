package core.impl;

import core.contracts.Evaluator;
import core.contracts.PositionFactory;

public final class EvaluatorImpl implements Evaluator {

    /** Piece values in centipawns, aligned with {@link PositionFactory} indices. */
    private static final int[] VALUE = {
            100, 320, 330, 500, 900, 0,   // White: P, N, B, R, Q, K
            100, 320, 330, 500, 900, 0    // Black: p, n, b, r, q, k
    };

    @Override
    public int evaluate(long[] bb) {
        int white = 0;
        int black = 0;

        // Kings are priceless → value 0.
        for (int p = PositionFactory.WP; p <= PositionFactory.WK; ++p) {
            white += VALUE[p] * Long.bitCount(bb[p]);
        }
        for (int p = PositionFactory.BP; p <= PositionFactory.BK; ++p) {
            black += VALUE[p] * Long.bitCount(bb[p]);
        }

        int diff = white - black; // >0: White ahead

        // Stockfish returns score relative to the side that will move next.
        long meta = bb[PositionFactory.META];
        return PositionFactory.whiteToMove(meta) ? diff : -diff;
    }

    @Override
    public void reset() {
        // Stateless – nothing to clear.
    }
}
