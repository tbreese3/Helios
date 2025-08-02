package core.contracts;

import core.records.NNUEState;

import java.io.InputStream;

public interface NNUE {
    String networkPath = "/core/nnue/network.bin";

    // --- Network Architecture Constants ---
    int INPUT_SIZE = 768;
    int HL_SIZE = 1536;
    int QA = 255;
    int QB = 64;
    int QAB = QA * QB;
    int FV_SCALE = 400;

    void loadNetwork();
    boolean isLoaded();
    void refreshAccumulator(NNUEState state, long[] bb);
    int evaluateFromAccumulator(NNUEState state, boolean whiteToMove);
    void updateNnueAccumulator(NNUEState state, int moverPiece, int capturedPiece, int move);
    void undoNnueAccumulatorUpdate(NNUEState state, int moverPiece, int capturedPiece, int move);
}
