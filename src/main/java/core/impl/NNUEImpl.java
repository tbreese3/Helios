package core.impl;

import core.contracts.NNUE;
import core.contracts.PositionFactory;
import core.records.NNUEState;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;
import main.Main;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import static core.contracts.PositionFactory.*;
import static core.contracts.PositionFactory.BK;
import static core.contracts.PositionFactory.BN;
import static core.contracts.PositionFactory.BR;
import static core.contracts.PositionFactory.WK;
import static core.contracts.PositionFactory.WN;
import static core.contracts.PositionFactory.WR;

public final class NNUEImpl implements NNUE {
    private final static int[] screluPreCalc = new int[Short.MAX_VALUE - Short.MIN_VALUE + 1];

    // --- Network Architecture Constants ---
    public static final int INPUT_SIZE = 768;
    public static final int HL_SIZE = 1536;
    static final int OUTPUT_BUCKETS = 8;
    private static final int DIVISOR = (32 + OUTPUT_BUCKETS - 1) / OUTPUT_BUCKETS;
    private static final int QA = 255;
    private static final int QB = 64;
    private static final int QAB = QA * QB;
    private static final int FV_SCALE = 400;

    // --- Network Parameters
    private static final short[][] L1_WEIGHTS = new short[INPUT_SIZE][HL_SIZE];
    private static final short[] L1_BIASES = new short[HL_SIZE];
    private static final short[][][] L2_WEIGHTS = new short[OUTPUT_BUCKETS][2][HL_SIZE];
    private static final short[] L2_BIASES= new short[OUTPUT_BUCKETS];

    private static final VectorSpecies<Short> S = ShortVector.SPECIES_PREFERRED;
    private static final int UB = S.loopBound(HL_SIZE); // largest multiple ≤ HL_SIZE

    private static boolean isLoaded = false;

    static {
        String resourcePath = "/core/nnue/network.bin";
        try {
            NNUEImpl.loadNetwork(resourcePath);
        } catch (Exception e) {
            System.out.println("info string Error loading embedded NNUE file: " + e.getMessage());
        }

        for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
            screluPreCalc[i - (int) Short.MIN_VALUE] = screlu((short) (i));
        }
    }

    /**
     * Loads the quantized network weights from an InputStream.
     */
    private static synchronized void loadNetwork(String filePath) {
        if (isLoaded) return;

        try (DataInputStream dis =  new DataInputStream(Objects.requireNonNull(NNUEImpl.class.getResourceAsStream(filePath)))) {
            for (int i = 0; i < INPUT_SIZE; i++) {
                for (int j = 0; j < HL_SIZE; j++) {
                    L1_WEIGHTS[i][j] = Short.reverseBytes(dis.readShort());
                }
            }
            for (int i = 0; i < HL_SIZE; i++) {
                L1_BIASES[i] = Short.reverseBytes(dis.readShort());
            }

            for (int k = 0; k < OUTPUT_BUCKETS; k++) {
                // STM half
                for (int j = 0; j < HL_SIZE; j++) {
                    L2_WEIGHTS[k][0][j] = Short.reverseBytes(dis.readShort());
                }
                // NTM half
                for (int j = 0; j < HL_SIZE; j++) {
                    L2_WEIGHTS[k][1][j] = Short.reverseBytes(dis.readShort());
                }
            }

            for (int i = 0; i < OUTPUT_BUCKETS; i++)
            {
                L2_BIASES[i] = Short.reverseBytes(dis.readShort());
            }

            isLoaded = true;
        } catch (IOException e) {
            System.err.println("Failed to read NNUE stream: " + e.getMessage());
            isLoaded = false;
        }
    }

    public static boolean isLoaded() {
        return isLoaded;
    }

    public void updateNnueAccumulator(NNUEState nnueState, int moverPiece, int capturedPiece, int move) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indicies = getFeatureIndices(capturedPiece, capturedSquare);
            subtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicies[0]]);
            subtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicies[1]]);
        }

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] subPawnFrom = getFeatureIndices(moverPiece, from);
            int[] addPromoTo  = getFeatureIndices(promotedToPiece, to);

            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[addPromoTo[0]], L1_WEIGHTS[subPawnFrom[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[addPromoTo[1]], L1_WEIGHTS[subPawnFrom[1]]);
        } else if (moveType == 3) { // Castle
            int rook = moverPiece < 6 ? WR : BR;
            switch (to) {
                case 6:
                    NNUEImpl.updateCastle(nnueState, WK, rook, 4, 6, 7, 5);
                    break; // White O-O
                case 2:
                    NNUEImpl.updateCastle(nnueState, WK, rook, 4, 2, 0, 3);
                    break; // White O-O-O
                case 62:
                    NNUEImpl.updateCastle(nnueState, BK, rook, 60, 62, 63, 61);
                    break; // Black O-O
                case 58:
                    NNUEImpl.updateCastle(nnueState, BK, rook, 60, 58, 56, 59);
                    break; // Black O-O-O
            }
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from);
            int[] indicesTo = getFeatureIndices(moverPiece, to);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicesTo[0]], L1_WEIGHTS[indicesFrom[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicesTo[1]], L1_WEIGHTS[indicesFrom[1]]);
        }
    }

    /**
     * Undoes a move's effect on the NNUE accumulator, now correctly handling castling.
     */
    public void undoNnueAccumulatorUpdate(NNUEState nnueState, int moverPiece, int capturedPiece, int move) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] addPawnFrom = getFeatureIndices(moverPiece, from);
            int[] subPromoTo  = getFeatureIndices(promotedToPiece, to);

            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[addPawnFrom[0]], L1_WEIGHTS[subPromoTo[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[addPawnFrom[1]], L1_WEIGHTS[subPromoTo[1]]);
        } else if (moveType == 3) { // Castle
            int rook = moverPiece < 6 ? WR : BR;
            switch (to) {
                case 6:
                    NNUEImpl.updateCastle(nnueState, WK, rook, 6, 4, 5, 7);
                    break; // Undo White O-O
                case 2:
                    NNUEImpl.updateCastle(nnueState, WK, rook, 2, 4, 3, 0);
                    break; // Undo White O-O-O
                case 62:
                    NNUEImpl.updateCastle(nnueState, BK, rook, 62, 60, 61, 63);
                    break; // Undo Black O-O
                case 58:
                    NNUEImpl.updateCastle(nnueState, BK, rook, 58, 60, 59, 56);
                    break; // Undo Black O-O-O
            }
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from);
            int[] indicesTo = getFeatureIndices(moverPiece, to);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicesFrom[0]], L1_WEIGHTS[indicesTo[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicesFrom[1]], L1_WEIGHTS[indicesTo[1]]);
        }

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indicies = getFeatureIndices(capturedPiece, capturedSquare);
            addWeights(nnueState.whiteAcc, L1_WEIGHTS[indicies[0]]);
            addWeights(nnueState.blackAcc, L1_WEIGHTS[indicies[1]]);
        }
    }

    public void refreshAccumulator(NNUEState state, long[] bb) {
        System.arraycopy(L1_BIASES, 0, state.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, state.blackAcc, 0, HL_SIZE);

        for (int p = WP; p <= BK; p++) {
            long board = bb[p];
            while (board != 0) {
                int sq = Long.numberOfTrailingZeros(board);
                int[] indicies = getFeatureIndices(p, sq);
                addWeights(state.whiteAcc, L1_WEIGHTS[indicies[0]]);
                addWeights(state.blackAcc, L1_WEIGHTS[indicies[1]]);
                board &= board - 1;
            }
        }
    }

    public int evaluateFromAccumulator(NNUEState state, long[] bb) {
        boolean whiteToMove = PositionFactory.whiteToMove(bb[META]);
        int outputBucket = chooseOutputBucket(bb);

        short[] stmAcc = whiteToMove ? state.whiteAcc : state.blackAcc;
        short[] oppAcc = whiteToMove ? state.blackAcc : state.whiteAcc;

        short[] stmWeights = L2_WEIGHTS[outputBucket][0];
        short[] oppWeights = L2_WEIGHTS[outputBucket][1];

        long output = 0;
        for (int i = 0; i < HL_SIZE; i++) {
            output += screluPreCalc[stmAcc[i] - (int) Short.MIN_VALUE] * stmWeights[i];
            output += screluPreCalc[oppAcc[i] - (int) Short.MIN_VALUE] * oppWeights[i];
        }

        // BUG FIX: Correctly apply bias before the final division.
        output /= QA;
        output += L2_BIASES[outputBucket];

        output *= FV_SCALE;
        output /= QAB;

        return (int) output;
    }

    private static int[] getFeatureIndices(int piece, int square) {
        int color = piece / 6;
        int pieceType = piece % 6;
        int whiteFeature = (color * 384) + (pieceType * 64) + square;
        int blackFeature = ((1 - color) * 384) + (pieceType * 64) + (square ^ 56);
        return new int[]{whiteFeature, blackFeature};
    }

    private static void updateCastle(NNUEState state, int king, int rook, int k_from, int k_to, int r_from, int r_to) {
        int[] kFrom = getFeatureIndices(king, k_from);
        int[] kTo   = getFeatureIndices(king, k_to);
        int[] rFrom = getFeatureIndices(rook, r_from);
        int[] rTo   = getFeatureIndices(rook, r_to);

        addAddSubSubWeights(state.whiteAcc, L1_WEIGHTS[kTo[0]], L1_WEIGHTS[rTo[0]], L1_WEIGHTS[kFrom[0]], L1_WEIGHTS[rFrom[0]]);
        addAddSubSubWeights(state.blackAcc, L1_WEIGHTS[kTo[1]], L1_WEIGHTS[rTo[1]], L1_WEIGHTS[kFrom[1]], L1_WEIGHTS[rFrom[1]]);
    }

    private static int screlu(short v) {
        int vCalc = Math.max(0, Math.min(v, QA));
        return vCalc * vCalc;
    }

    private static void addWeights(short[] acc, short[] addW) {
        for (int i = 0; i < HL_SIZE; i++) acc[i] = satAdd(acc[i], addW[i]);
    }
    private static void subtractWeights(short[] acc, short[] subW) {
        for (int i = 0; i < HL_SIZE; i++) acc[i] = satSub(acc[i], subW[i]);
    }
    private static void addSubtractWeights(short[] acc, short[] addW, short[] subW) {
        for (int i = 0; i < HL_SIZE; i++) acc[i] = satAdd(satSub(acc[i], subW[i]), addW[i]);
    }

    private static void addAddSubSubWeights(short[] acc, short[] addW1, short[] addW2,
                                            short[] subW1, short[] subW2) {
        for (int i = 0; i < HL_SIZE; i++) {
            short v = acc[i];
            v = satAdd(v, addW1[i]);
            v = satAdd(v, addW2[i]);
            v = satSub(v, subW1[i]);
            v = satSub(v, subW2[i]);
            acc[i] = v;
        }
    }

    public static int chooseOutputBucket(long[] bb)
    {
        final long occ = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK] | bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
        return (Long.bitCount(occ) - 2) / DIVISOR;
    }

    private static short satAdd(short a, short b) {
        int s = a + b;
        if (s > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (s < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) s;
    }
    private static short satSub(short a, short b) {
        int s = a - b;
        if (s > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (s < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) s;
    }
}