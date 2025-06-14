package core.eval;

import core.contracts.Evaluator;
import core.contracts.PositionFactory;

/**
 * Ultra‑naïve material‑only evaluator: sum(pieceValue × pieceCount) and return
 * the difference (White − Black) in centipawns. No tempo, king safety, pawn
 * structure… nothing.  Great for smoke‑testing the search loop.
 */
public final class EvaluatorImpl implements Evaluator {

    /** Piece values expressed in centipawns, indexed by PositionFactory piece constants. */
    private static final int[] VALUE = {
            100, 320, 330, 500, 900, 0,    // White: P, N, B, R, Q, K
            100, 320, 330, 500, 900, 0     // Black: p, n, b, r, q, k (same values)
    };

    @Override
    public int evaluate(long[] bb) {
        int white = 0, black = 0;

        // Sum material for both colours. Kings contribute 0 (they are priceless).
        for (int piece = PositionFactory.WP; piece <= PositionFactory.WK; piece++) {
            white += VALUE[piece] * Long.bitCount(bb[piece]);
        }
        for (int piece = PositionFactory.BP; piece <= PositionFactory.BK; piece++) {
            black += VALUE[piece] * Long.bitCount(bb[piece]);
        }

        return white - black; // >0 = White ahead, <0 = Black ahead
    }

    @Override
    public void reset() {
        // No caches to clear.
    }
}
