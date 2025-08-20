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
    static final int INPUT_BUCKETS = 10;
    private static final int[] INPUT_BUCKET = new int[]
     {
                    0, 1, 2, 3, 3, 2, 1, 0, // Rank 1 (Rust: 0, 1, 2, 3)
                    4, 4, 5, 5, 5, 5, 4, 4, // Rank 2 (Rust: 4, 4, 5, 5)
                    6, 6, 6, 6, 6, 6, 6, 6, // Rank 3 (Rust: 6, 6, 6, 6)
                    7, 7, 7, 7, 7, 7, 7, 7, // Rank 4 (Rust: 7, 7, 7, 7)
                    8, 8, 8, 8, 8, 8, 8, 8, // Rank 5 (Rust: 8, 8, 8, 8)
                    8, 8, 8, 8, 8, 8, 8, 8, // Rank 6 (Rust: 8, 8, 8, 8)
                    9, 9, 9, 9, 9, 9, 9, 9, // Rank 7 (Rust: 9, 9, 9, 9)
                    9, 9, 9, 9, 9, 9, 9, 9, // Rank 8 (Rust: 9, 9, 9, 9)
     };
    private static final int DIVISOR = (32 + OUTPUT_BUCKETS - 1) / OUTPUT_BUCKETS;
    private static final int QA = 255;
    private static final int QB = 64;
    private static final int QAB = QA * QB;
    private static final int FV_SCALE = 400;

    // --- Network Parameters
    private static final short[][][] L1_WEIGHTS = new short[INPUT_SIZE][INPUT_BUCKETS][HL_SIZE];
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
            for(int j = 0; j < INPUT_BUCKETS; j++) {
                for (int i = 0; i < INPUT_SIZE; i++) {
                    for (int k = 0; k < HL_SIZE; k++) {
                        // The array definition remains L1_WEIGHTS[Feature][Bucket][Hidden]
                        L1_WEIGHTS[i][j][k] = Short.reverseBytes(dis.readShort());
                    }
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

    public void updateNnueAccumulator(NNUEState nnueState, int moverPiece, int capturedPiece, int move, long[] bb) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;;
        int mover = (move >>> 16) & 0x0F;

        if(mover == WK)
        {
            if(shouldRefresh(from, to, true))
            {
                refreshAccumulator(nnueState, bb);
                return;
            }
        }

        if(mover == BK) {
            if(shouldRefresh(from, to, false))
            {
                refreshAccumulator(nnueState, bb);
                return;
            }
        }

        if (moveType == 3)
        {
            refreshAccumulator(nnueState, bb);
            return;
        }

        int whiteInputBucket = getWhiteInputBucket(Long.numberOfTrailingZeros(bb[WK]));
        int blackInputBucket = getBlackInputBucket(Long.numberOfTrailingZeros(bb[BK]));

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indicies = getFeatureIndices(capturedPiece, capturedSquare);
            subtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicies[0]][whiteInputBucket]);
            subtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicies[1]][blackInputBucket]);
        }

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] subPawnFrom = getFeatureIndices(moverPiece, from);
            int[] addPromoTo  = getFeatureIndices(promotedToPiece, to);

            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[addPromoTo[0]][whiteInputBucket], L1_WEIGHTS[subPawnFrom[0]][whiteInputBucket]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[addPromoTo[1]][blackInputBucket], L1_WEIGHTS[subPawnFrom[1]][blackInputBucket]);
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from);
            int[] indicesTo = getFeatureIndices(moverPiece, to);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicesTo[0]][whiteInputBucket], L1_WEIGHTS[indicesFrom[0]][whiteInputBucket]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicesTo[1]][blackInputBucket], L1_WEIGHTS[indicesFrom[1]][blackInputBucket]);
        }
    }

    /**
     * Undoes a move's effect on the NNUE accumulator, now correctly handling castling.
     */
    public void undoNnueAccumulatorUpdate(NNUEState nnueState, int moverPiece, int capturedPiece, int move, long[] bb) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;
        int mover = (move >>> 16) & 0x0F;

        if(mover == WK)
        {
            if(shouldRefresh(from, to, true))
            {
                refreshAccumulator(nnueState, bb);
                return;
            }
        }

        if(mover == BK) {
            if(shouldRefresh(from, to, false))
            {
                refreshAccumulator(nnueState, bb);
                return;
            }
        }

        if (moveType == 3)
        {
            refreshAccumulator(nnueState, bb);
            return;
        }

        int whiteInputBucket = getWhiteInputBucket(Long.numberOfTrailingZeros(bb[WK]));
        int blackInputBucket = getBlackInputBucket(Long.numberOfTrailingZeros(bb[BK]));

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] addPawnFrom = getFeatureIndices(moverPiece, from);
            int[] subPromoTo  = getFeatureIndices(promotedToPiece, to);

            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[addPawnFrom[0]][whiteInputBucket], L1_WEIGHTS[subPromoTo[0]][whiteInputBucket]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[addPawnFrom[1]][blackInputBucket], L1_WEIGHTS[subPromoTo[1]][blackInputBucket]);
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from);
            int[] indicesTo = getFeatureIndices(moverPiece, to);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicesFrom[0]][whiteInputBucket], L1_WEIGHTS[indicesTo[0]][whiteInputBucket]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicesFrom[1]][blackInputBucket], L1_WEIGHTS[indicesTo[1]][blackInputBucket]);
        }

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indicies = getFeatureIndices(capturedPiece, capturedSquare);
            addWeights(nnueState.whiteAcc, L1_WEIGHTS[indicies[0]][whiteInputBucket]);
            addWeights(nnueState.blackAcc, L1_WEIGHTS[indicies[1]][blackInputBucket]);
        }
    }

    public void refreshAccumulator(NNUEState state, long[] bb) {
        System.arraycopy(L1_BIASES, 0, state.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, state.blackAcc, 0, HL_SIZE);

        int whiteInputBucket = getWhiteInputBucket(Long.numberOfTrailingZeros(bb[WK]));
        int blackInputBucket = getBlackInputBucket(Long.numberOfTrailingZeros(bb[BK]));

        for (int p = WP; p <= BK; p++) {
            long board = bb[p];
            while (board != 0) {
                int sq = Long.numberOfTrailingZeros(board);
                int[] indicies = getFeatureIndices(p, sq);
                addWeights(state.whiteAcc, L1_WEIGHTS[indicies[0]][whiteInputBucket]);
                addWeights(state.blackAcc, L1_WEIGHTS[indicies[1]][blackInputBucket]);
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

    private static int screlu(short v) {
        int vCalc = Math.max(0, Math.min(v, QA));
        return vCalc * vCalc;
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

    public static int chooseOutputBucket(long[] bb)
    {
        final long occ = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK] | bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
        return (Long.bitCount(occ) - 2) / DIVISOR;
    }

    public static boolean shouldRefresh(int kingFrom, int kingTo, boolean kingIsWhite)
    {
        if (((kingTo ^ kingFrom) & 4) != 0) return true; // fast path (crossing files d/e)
        int curr = kingIsWhite ? getWhiteInputBucket(kingTo) : getBlackInputBucket(kingTo);
        int prev = kingIsWhite ? getWhiteInputBucket(kingFrom) : getBlackInputBucket(kingFrom);
        return curr != prev;
    }

    private static int getWhiteInputBucket(int kSqr) {
        int kW = kSqr;      // White king in white POV
        return INPUT_BUCKET[kW];
    }

    private static int getBlackInputBucket(int kSqr) {
        int kB = kSqr ^ 56; // mirror Black to white POV
        return INPUT_BUCKET[kB];
    }
}