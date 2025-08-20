package core.impl;

import core.contracts.NNUE;
import core.contracts.PositionFactory;
import core.records.NNUEState;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;

import java.io.DataInputStream;
import java.io.IOException;
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
    private final static PositionFactory positionFactory = new PositionFactoryImpl();

    // --- Network Architecture Constants ---
    public static final int INPUT_SIZE = 768;
    public static final int HL_SIZE = 1536;
    static final int INPUT_BUCKETS = 10;
    private static final int[] INPUT_BUCKET = new int[]
    {
            0, 0, 1, 1, 2, 2, 3, 3,
            4, 4, 4, 4, 5, 5, 5, 5,
            6, 6, 6, 6, 6, 6, 6, 6,
            7, 7, 7, 7, 7, 7, 7, 7,
            8, 8, 8, 8, 8, 8, 8, 8,
            8, 8, 8, 8, 8, 8, 8, 8,
            9, 9, 9, 9, 9, 9, 9, 9,
            9, 9, 9, 9, 9, 9, 9, 9,
    };
    static final int OUTPUT_BUCKETS = 8;
    private static final int DIVISOR = (32 + OUTPUT_BUCKETS - 1) / OUTPUT_BUCKETS;
    private static final int QA = 255;
    private static final int QB = 64;
    private static final int QAB = QA * QB;
    private static final int FV_SCALE = 400;

    // --- Network Parameters
    private static final short[][][] L1_WEIGHTS = new short[INPUT_BUCKETS][INPUT_SIZE][HL_SIZE];
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
// read l0w as [HL][IN] row-major, then de-interleave per bucket
            short[][] l0RowMajor = new short[HL_SIZE][INPUT_SIZE * INPUT_BUCKETS];
            for (int h = 0; h < HL_SIZE; h++) {
                for (int in = 0; in < INPUT_SIZE * INPUT_BUCKETS; in++) {
                    l0RowMajor[h][in] = Short.reverseBytes(dis.readShort());
                }
            }

            // remap to L1_WEIGHTS[b][f][h] = l0RowMajor[h][b*INPUT_SIZE + f]
            for (int b = 0; b < INPUT_BUCKETS; b++) {
                int base = b * INPUT_SIZE;
                for (int f = 0; f < INPUT_SIZE; f++) {
                    int col = base + f;
                    for (int h = 0; h < HL_SIZE; h++) {
                        L1_WEIGHTS[b][f][h] = l0RowMajor[h][col];
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
        int moveType = (move >>> 14) & 0x3;
        int mover = (move >>> 16) & 0xF;
        boolean moverIsWhite = ((moverPiece & 7) < 6);

        // King move?
        if (mover == WK || mover == BK) {
            if (isCastle(from, to, mover)) { // rook also moves -> accumulator deltas needed
                refreshAccumulator(nnueState, bb);
                return;
            }
            // (Optionally keep your bucket-change refresh here)
            boolean kingIsWhite = (mover == WK);
            if (shouldRefresh(to, from, kingIsWhite)) {
                refreshAccumulator(nnueState, bb);
                return;
            }
        }

        // Buckets from *current* (post-move) king squares
        final int wBucket = getWhiteInputBucket(whiteKingSq(bb));
        final int bBucket = getBlackInputBucket(blackKingSq(bb));

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indicies = getFeatureIndices(capturedPiece, capturedSquare);
            subtractWeights(nnueState.whiteAcc, L1_WEIGHTS[wBucket][indicies[0]]);
            subtractWeights(nnueState.blackAcc, L1_WEIGHTS[bBucket][indicies[1]]);
        }

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] subPawnFrom = getFeatureIndices(moverPiece, from);
            int[] addPromoTo  = getFeatureIndices(promotedToPiece, to);

            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[wBucket][addPromoTo[0]], L1_WEIGHTS[wBucket][subPawnFrom[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[bBucket][addPromoTo[1]], L1_WEIGHTS[bBucket][subPawnFrom[1]]);
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from);
            int[] indicesTo = getFeatureIndices(moverPiece, to);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[wBucket][indicesTo[0]], L1_WEIGHTS[wBucket][indicesFrom[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[bBucket][indicesTo[1]], L1_WEIGHTS[bBucket][indicesFrom[1]]);
        }
    }

    /**
     * Undoes a move's effect on the NNUE accumulator, now correctly handling castling.
     */
    public void undoNnueAccumulatorUpdate(NNUEState nnueState, int moverPiece, int capturedPiece, int move, long[] bb) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;
        int mover = (move >>> 16) & 0xF;
        boolean moverIsWhite = ((moverPiece & 7) < 6);
        int wBucket = getWhiteInputBucket(whiteKingSq(bb));
        int bBucket = getBlackInputBucket(blackKingSq(bb));

        if (mover == WK || mover == BK) {
            if (isCastle(from, to, mover)) { // rook also moves -> accumulator deltas needed
                refreshAccumulator(nnueState, bb);
                return;
            }
            // (Optionally keep your bucket-change refresh here)
            boolean kingIsWhite = (mover == WK);
            if (shouldRefresh(to, from, kingIsWhite)) {
                refreshAccumulator(nnueState, bb);
                return;
            }
        }

        if (mover == WK) wBucket = getWhiteInputBucket(from);
        if (mover == BK) bBucket = getBlackInputBucket(from);

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] addPawnFrom = getFeatureIndices(moverPiece, from);
            int[] subPromoTo  = getFeatureIndices(promotedToPiece, to);

            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[wBucket][addPawnFrom[0]], L1_WEIGHTS[wBucket][subPromoTo[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[bBucket][addPawnFrom[1]], L1_WEIGHTS[bBucket][subPromoTo[1]]);
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from);
            int[] indicesTo = getFeatureIndices(moverPiece, to);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[wBucket][indicesFrom[0]], L1_WEIGHTS[wBucket][indicesTo[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[bBucket][indicesFrom[1]], L1_WEIGHTS[bBucket][indicesTo[1]]);
        }

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indicies = getFeatureIndices(capturedPiece, capturedSquare);
            addWeights(nnueState.whiteAcc, L1_WEIGHTS[wBucket][indicies[0]]);
            addWeights(nnueState.blackAcc, L1_WEIGHTS[bBucket][indicies[1]]);
        }
    }

    public void refreshAccumulator(NNUEState state, long[] bb) {
        System.arraycopy(L1_BIASES, 0, state.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, state.blackAcc, 0, HL_SIZE);

        final int wBucket = getWhiteInputBucket(whiteKingSq(bb));
        final int bBucket = getBlackInputBucket(blackKingSq(bb));

        for (int p = WP; p <= BK; p++) {
            long board = bb[p];
            while (board != 0) {
                int sq = Long.numberOfTrailingZeros(board);
                int[] idx = getFeatureIndices(p, sq);
                addWeights(state.whiteAcc, L1_WEIGHTS[wBucket][idx[0]]);
                addWeights(state.blackAcc, L1_WEIGHTS[bBucket][idx[1]]);
                board &= board - 1;
            }
        }
    }

    public int evaluateFromAccumulator(NNUEState state, long[] bb) {
        boolean whiteToMove = PositionFactory.whiteToMove(bb[META]);
        int outputBucket = getOutputBucket(bb);

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

    public static int getOutputBucket(long[] bb)
    {
        final long occ = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK] | bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
        return (Long.bitCount(occ) - 2) / DIVISOR;
    }

    private static int getWhiteInputBucket(int kSqr) {
        int kW = kSqr;      // White king in white POV
        return INPUT_BUCKET[kW];
    }

    private static int getBlackInputBucket(int kSqr) {
        int kB = kSqr ^ 56; // mirror Black to white POV
        return INPUT_BUCKET[kB];
    }

    private static boolean shouldRefresh(int kSqr, int kSqrPrev, boolean kingIsWhite) {
        if (((kSqr ^ kSqrPrev) & 4) != 0) return true; // fast path (crossing files d/e)
        int curr = kingIsWhite ? getWhiteInputBucket(kSqr)     : getBlackInputBucket(kSqr);
        int prev = kingIsWhite ? getWhiteInputBucket(kSqrPrev) : getBlackInputBucket(kSqrPrev);
        return curr != prev;
    }

    private static int whiteKingSq(long[] bb) { return Long.numberOfTrailingZeros(bb[WK]); }
    private static int blackKingSq(long[] bb) { return Long.numberOfTrailingZeros(bb[BK]); }

    private static boolean isCastle(int from, int to, int mover) {
        // king move by two squares
        return (mover == WK || mover == BK) && Math.abs((to & 7) - (from & 7)) == 2;
    }
}