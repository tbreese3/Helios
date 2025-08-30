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
    static final int INPUT_BUCKETS = 7;
    private static final int[] INPUT_BUCKET_MAP = new int[] {
            0, 0, 1, 1, 2, 2, 3, 3,
            4, 4, 4, 4, 5, 5, 5, 5,
            6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6,
    };


    // --- Network Parameters
    private static final short[][] L1_WEIGHTS = new short[INPUT_SIZE * INPUT_BUCKETS][HL_SIZE];
    private static final short[]  L1_BIASES  = new short[HL_SIZE];
    private static final short[][][] L2_WEIGHTS = new short[OUTPUT_BUCKETS][2][HL_SIZE];
    private static final short[] L2_BIASES = new short[OUTPUT_BUCKETS];

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
            for (int i = 0; i < INPUT_SIZE * INPUT_BUCKETS; i++) {
                for (int j = 0; j < HL_SIZE; j++) {
                    L1_WEIGHTS[i][j] = Short.reverseBytes(dis.readShort());
                }
            }
            for (int i = 0; i < HL_SIZE; i++) {
                L1_BIASES[i] = Short.reverseBytes(dis.readShort());
            }

            for (int i = 0; i < HL_SIZE * 2; i++) {
                for (int k = 0; k < OUTPUT_BUCKETS; k++) {
                    short v = Short.reverseBytes(dis.readShort());
                    if (i < HL_SIZE) {
                        L2_WEIGHTS[k][0][i] = v;             // STM half
                    } else {
                        L2_WEIGHTS[k][1][i - HL_SIZE] = v;   // NTM half
                    }
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

    public void updateNnueAccumulator(NNUEState nnueState, long[] bb, int moverPiece, int capturedPiece, int move) {
        int from = (move >>> 6) & 0x3F;
        int to   =  move        & 0x3F;
        int moveType = (move >>> 14) & 0x3;

        final int wb   = chooseInputBucketWhite(bb);
        final int bbkt = chooseInputBucketBlack(bb);

        // If king move AND bucket changes → full refresh from the post-move board
        if (moveType == 3 || (moverPiece == WK && INPUT_BUCKET_MAP[from] != INPUT_BUCKET_MAP[to]) ||
                (moverPiece == BK && INPUT_BUCKET_MAP[from ^ 56] != INPUT_BUCKET_MAP[to ^ 56])) {
            refreshAccumulator(nnueState, bb);
            return;
        }

        // Use current buckets for all incremental diffs
        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indices = getFeatureIndices(capturedPiece, capturedSquare, wb, bbkt);
            subtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indices[0]]);
            subtractWeights(nnueState.blackAcc, L1_WEIGHTS[indices[1]]);
        }

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] subPawnFrom = getFeatureIndices(moverPiece, from, wb, bbkt);
            int[] addPromoTo  = getFeatureIndices(promotedToPiece, to, wb, bbkt);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[addPromoTo[0]], L1_WEIGHTS[subPawnFrom[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[addPromoTo[1]], L1_WEIGHTS[subPawnFrom[1]]);
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from, wb, bbkt);
            int[] indicesTo   = getFeatureIndices(moverPiece, to,   wb, bbkt);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicesTo[0]], L1_WEIGHTS[indicesFrom[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicesTo[1]], L1_WEIGHTS[indicesFrom[1]]);
        }
    }

    /**
     * Undoes a move's effect on the NNUE accumulator, now correctly handling castling.
     */
    public void undoNnueAccumulatorUpdate(NNUEState nnueState, long[] bb, int moverPiece, int capturedPiece, int move) {
        int from = (move >>> 6) & 0x3F;
        int to   =  move        & 0x3F;
        int moveType = (move >>> 14) & 0x3;

        final int wb   = chooseInputBucketWhite(bb);
        final int bbkt = chooseInputBucketBlack(bb);

        // If king move AND bucket changes → full refresh from the pre-move board (already undone)
        if (moveType == 3 || (moverPiece == WK && INPUT_BUCKET_MAP[from] != INPUT_BUCKET_MAP[to]) ||
                (moverPiece == BK && INPUT_BUCKET_MAP[from ^ 56] != INPUT_BUCKET_MAP[to ^ 56])) {
            refreshAccumulator(nnueState, bb);
            return;
        }

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] addPawnFrom = getFeatureIndices(moverPiece, from, wb, bbkt);
            int[] subPromoTo  = getFeatureIndices(promotedToPiece, to,   wb, bbkt);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[addPawnFrom[0]], L1_WEIGHTS[subPromoTo[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[addPawnFrom[1]], L1_WEIGHTS[subPromoTo[1]]);
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from, wb, bbkt);
            int[] indicesTo   = getFeatureIndices(moverPiece, to,   wb, bbkt);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicesFrom[0]], L1_WEIGHTS[indicesTo[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicesFrom[1]], L1_WEIGHTS[indicesTo[1]]);
        }

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indices = getFeatureIndices(capturedPiece, capturedSquare, wb, bbkt);
            addWeights(nnueState.whiteAcc, L1_WEIGHTS[indices[0]]);
            addWeights(nnueState.blackAcc, L1_WEIGHTS[indices[1]]);
        }
    }

    public void refreshAccumulator(NNUEState state, long[] bb) {
        System.arraycopy(L1_BIASES, 0, state.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, state.blackAcc, 0, HL_SIZE);

        final int wb = chooseInputBucketWhite(bb);
        final int bbkt = chooseInputBucketBlack(bb);

        for (int p = WP; p <= BK; p++) {
            long board = bb[p];
            while (board != 0) {
                int sq = Long.numberOfTrailingZeros(board);
                int[] indices = getFeatureIndices(p, sq, wb, bbkt);
                addWeights(state.whiteAcc, L1_WEIGHTS[indices[0]]);
                addWeights(state.blackAcc, L1_WEIGHTS[indices[1]]);
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

        long output = (long) L2_BIASES[outputBucket] * QA;
        for (int i = 0; i < HL_SIZE; i++) {
            output += screluPreCalc[stmAcc[i] - (int) Short.MIN_VALUE] * stmWeights[i];
            output += screluPreCalc[oppAcc[i] - (int) Short.MIN_VALUE] * oppWeights[i];
        }

        output = (output * FV_SCALE) / QAB;

        return (int) output;
    }

    private static int[] getFeatureIndices(int piece, int square, int whiteBucket, int blackBucket) {
        int color = piece / 6;
        int pieceType = piece % 6;

        int baseWhite = (color * 384) + (pieceType * 64) + square;
        int baseBlack = ((1 - color) * 384) + (pieceType * 64) + (square ^ 56);

        int whiteFeature = whiteBucket * INPUT_SIZE + baseWhite;
        int blackFeature = blackBucket * INPUT_SIZE + baseBlack;
        return new int[] { whiteFeature, blackFeature };
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

    public static int chooseOutputBucket(long[] bb)
    {
        final long occ = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK] | bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
        return (Long.bitCount(occ) - 2) / DIVISOR;
    }

    private static int chooseInputBucketWhite(long[] bb) {
        int wkSq = Long.numberOfTrailingZeros(bb[WK]);         // 0..63
        return INPUT_BUCKET_MAP[wkSq];
    }

    private static int chooseInputBucketBlack(long[] bb) {
        int bkSq = Long.numberOfTrailingZeros(bb[BK]);
        return INPUT_BUCKET_MAP[bkSq ^ 56];                    // mirror for black perspective
    }
}