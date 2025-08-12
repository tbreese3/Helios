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

import static core.contracts.PositionFactory.*;
import static core.contracts.PositionFactory.BK;
import static core.contracts.PositionFactory.BN;
import static core.contracts.PositionFactory.BR;
import static core.contracts.PositionFactory.WK;
import static core.contracts.PositionFactory.WN;
import static core.contracts.PositionFactory.WR;

public final class NNUEImpl implements NNUE {
    // --- Network Architecture Constants ---
    public static final int INPUT_SIZE = 768;
    public static final int HL_SIZE = 1024;
    private static final int QA = 255;
    private static final int QB = 64;
    private static final int QAB = QA * QB;
    private static final int FV_SCALE = 400;
    private static final int OUTPUT_BUCKETS = 8; // can be adjusted
    private static final int DIVISOR = (32 + OUTPUT_BUCKETS - 1) / OUTPUT_BUCKETS;

    // --- Network Parameters (Weights and Biases) ---
    private static short[][] L1_WEIGHTS; // [INPUT_SIZE][HL_SIZE]
    private static short[] L1_BIASES;    // [HL_SIZE]
    private static short[][] L2_WEIGHTS; // [2][HL_SIZE]
    private static short[] L2_BIASES;    // [1]

    private static final int[] INPUT_BUCKETS = new int[]
   {
           0, 1, 2, 3,
           4, 4, 5, 5,
           6, 6, 6, 6,
           7, 7, 7, 7,
           8, 8, 8, 8,
           8, 8, 8, 8,
           9, 9, 9, 9,
           9, 9, 9, 9,
   };

    private static final VectorSpecies<Short> S = ShortVector.SPECIES_PREFERRED;
    private static final int UB = S.loopBound(HL_SIZE); // largest multiple ≤ HL_SIZE

    private static boolean isLoaded = false;

    private final static int[] screluPreCalc = new int[Short.MAX_VALUE - Short.MIN_VALUE + 1];
    private final static int[] creluPreCalc = new int[Short.MAX_VALUE - Short.MIN_VALUE + 1];

    static {
        String resourcePath = "/core/nnue/network.bin";
        try (InputStream nnueStream = Main.class.getResourceAsStream(resourcePath)) {
            NNUEImpl.loadNetwork(nnueStream, "embedded resource");
        } catch (Exception e) {
            System.out.println("info string Error loading embedded NNUE file: " + e.getMessage());
        }

        for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
            screluPreCalc[i - (int) Short.MIN_VALUE] = screlu((short) (i));
            creluPreCalc[i - (int) Short.MIN_VALUE] = crelu((short) (i));
        }
    }

    /**
     * Loads the quantized network weights from an InputStream.
     */
    private static synchronized void loadNetwork(InputStream is, String sourceName) {
        if (isLoaded) return;
        if (is == null) {
            System.err.println("Failed to load NNUE network: InputStream is null. Source: " + sourceName);
            isLoaded = false;
            return;
        }

        try (DataInputStream dis = new DataInputStream(is)) {
            L1_WEIGHTS = new short[INPUT_SIZE][HL_SIZE];
            L1_BIASES = new short[HL_SIZE];
            L2_WEIGHTS = new short[2][HL_SIZE];
            L2_BIASES = new short[1];

            byte[] buffer = new byte[2];

            for (int i = 0; i < INPUT_SIZE; i++) {
                for (int j = 0; j < HL_SIZE; j++) {
                    dis.readFully(buffer);
                    L1_WEIGHTS[i][j] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
                }
            }
            for (int i = 0; i < HL_SIZE; i++) {
                dis.readFully(buffer);
                L1_BIASES[i] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
            }
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < HL_SIZE; j++) {
                    dis.readFully(buffer);
                    L2_WEIGHTS[i][j] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
                }
            }
            dis.readFully(buffer);
            L2_BIASES[0] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();

            isLoaded = true;
        } catch (IOException e) {
            System.err.println("Failed to read NNUE stream from " + sourceName + ": " + e.getMessage());
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

        for (int p = PositionFactory.WP; p <= PositionFactory.BK; p++) {
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

    public int evaluateFromAccumulator(NNUEState state, boolean whiteToMove) {
        short[] stmAcc = whiteToMove ? state.whiteAcc : state.blackAcc;
        short[] oppAcc = whiteToMove ? state.blackAcc : state.whiteAcc;

        // BUG FIX: The weights are perspective-based, not color-based.
        // L2_WEIGHTS[0] is for the side to move, L2_WEIGHTS[1] is for the opponent.
        short[] stmWeights = L2_WEIGHTS[0];
        short[] oppWeights = L2_WEIGHTS[1];

        // BUG FIX: Use 'long' to prevent overflow during summation.
        long output = 0;
        for (int i = 0; i < HL_SIZE; i++) {
            output += screluPreCalc[stmAcc[i] - (int) Short.MIN_VALUE] * stmWeights[i];
            output += screluPreCalc[oppAcc[i] - (int) Short.MIN_VALUE] * oppWeights[i];
        }

        // BUG FIX: Correctly apply bias before the final division.
        output /= QA;
        output += L2_BIASES[0];

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

    private static int crelu(short v)
    {
        int vCalc = Math.max(0, Math.min(v, QA));
        return vCalc;
    }

    private static void addSubtractWeights(short[] acc, short[] addW, short[] subW) {
        for (int i = 0; i < UB; i += S.length()) {
            var a = ShortVector.fromArray(S, acc, i);
            var ad = ShortVector.fromArray(S, addW, i);
            var sb = ShortVector.fromArray(S, subW, i);
            a.add(ad).sub(sb).intoArray(acc, i);
        }
        for (int i = UB; i < HL_SIZE; i++) acc[i] = (short) (acc[i] + addW[i] - subW[i]);
    }

    private static void subtractWeights(short[] acc, short[] subW)
    {
        for (int i = 0; i < UB; i += S.length()) {
            var a = ShortVector.fromArray(S, acc, i);
            var b = ShortVector.fromArray(S, subW, i);
            a.sub(b).intoArray(acc, i);
        }
        for (int i = UB; i < HL_SIZE; i++) acc[i] -= subW[i];
    }

    private static void addWeights(short[] acc, short[] addW) {
        for (int i = 0; i < UB; i += S.length()) {
            var a = ShortVector.fromArray(S, acc, i);
            var b = ShortVector.fromArray(S, addW,   i);
            a.add(b).intoArray(acc, i);
        }
        // tail (if ever needed)
        for (int i = UB; i < HL_SIZE; i++) acc[i] += addW[i];
    }

    private static void addAddSubSubWeights(short[] acc, short[] addW1, short[] addW2, short[] subW1, short[] subW2) {
        for (int i = 0; i < UB; i += S.length()) {
            var a   = ShortVector.fromArray(S, acc,   i);
            var a1  = ShortVector.fromArray(S, addW1, i);
            var a2  = ShortVector.fromArray(S, addW2, i);
            var s1  = ShortVector.fromArray(S, subW1, i);
            var s2  = ShortVector.fromArray(S, subW2, i);
            a.add(a1).add(a2).sub(s1).sub(s2).intoArray(acc, i);
        }
        for (int i = UB; i < HL_SIZE; i++) acc[i] = (short)(acc[i] + addW1[i] + addW2[i] - subW1[i] - subW2[i]);
    }

    private int getOutputBucket(long[] bb)
    {
        final long own   = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
        final long enemy = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
        final long occ   = own | enemy;
        return (Long.bitCount(occ) - 2) / DIVISOR;
    }
}