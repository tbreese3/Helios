package core.contracts;

import core.records.NNUEState;

/**
 * Manages NNUE network loading, feature transformation, and evaluation logic.
 */
public interface NNUE {
    void updateNnueAccumulator(NNUEState nnueState, int moverPiece, int capturedPiece, int move);
    void undoNnueAccumulatorUpdate(NNUEState nnueState, int moverPiece, int capturedPiece, int move);
    int evaluateFromAccumulator(NNUEState state, boolean whiteToMove);
}
