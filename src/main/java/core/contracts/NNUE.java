package core.contracts;

import core.records.NNUEState;

/**
 * Manages NNUE network loading, feature transformation, and evaluation logic.
 */
public interface NNUE {
    void updateNnueAccumulator(NNUEState state, long[] bb, int moverPiece, int capturedPiece, int move);
    void undoNnueAccumulatorUpdate(NNUEState state, long[] bb, int moverPiece, int capturedPiece, int move);
    int evaluateFromAccumulator(NNUEState state, long[] bb);
    void refreshAccumulator(NNUEState state, long[] bb);
}
