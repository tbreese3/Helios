package core.nnue;

import core.contracts.PositionFactory;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Manages NNUE network loading, feature transformation, and evaluation logic.
 * All methods are static, making this a central utility class for NNUE operations.
 */
public final class NnueManager {
    // --- Network Architecture Constants ---
    public static final int INPUT_SIZE = 768;
    public static final int HL_SIZE = 1536;
    private static final int QA = 255;
    private static final int QB = 64;
    private static final int QAB = QA * QB;
    private static final int FV_SCALE = 400;

    // --- Network Parameters (Weights and Biases) ---
    private static short[][] L1_WEIGHTS; // [INPUT_SIZE][HL_SIZE]
    private static short[] L1_BIASES;    // [HL_SIZE]
    private static short[][] L2_WEIGHTS; // [2][HL_SIZE]
    private static short[] L2_BIASES;    // [1]

    private static boolean isLoaded = false;

    /**
     * Loads the quantized network weights from a filesystem path.
     */
    public static synchronized void loadNetwork(String filePath) {
        try (InputStream is = new FileInputStream(filePath)) {
            loadNetwork(is, filePath);
        } catch (IOException e) {
            System.err.println("Failed to open NNUE file from path: " + filePath);
            isLoaded = false;
        }
    }

    /**
     * Loads the quantized network weights from an InputStream.
     */
    public static synchronized void loadNetwork(InputStream is, String sourceName) {
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
            System.out.println("info string NNUE network loaded successfully from " + sourceName);
        } catch (IOException e) {
            System.err.println("Failed to read NNUE stream from " + sourceName + ": " + e.getMessage());
            isLoaded = false;
        }
    }

    public static boolean isLoaded() {
        return isLoaded;
    }

    private static int[] getFeatureIndices(int piece, int square) {
        int color = piece / 6;
        int pieceType = piece % 6;
        int whiteFeature = (color * 384) + (pieceType * 64) + square;
        int blackFeature = ((1 - color) * 384) + (pieceType * 64) + (square ^ 56);
        return new int[]{whiteFeature, blackFeature};
    }

    public static void refreshAccumulator(NnueState state, long[] bb) {
        System.arraycopy(L1_BIASES, 0, state.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, state.blackAcc, 0, HL_SIZE);

        for (int p = PositionFactory.WP; p <= PositionFactory.BK; p++) {
            long board = bb[p];
            while (board != 0) {
                int sq = Long.numberOfTrailingZeros(board);
                addPiece(state, p, sq);
                board &= board - 1;
            }
        }
    }

    public static void updateAccumulator(NnueState state, int piece, int from, int to) {
        removePiece(state, piece, from);
        addPiece(state, piece, to);
    }

    public static void updateCastle(NnueState state, int king, int rook, int k_from, int k_to, int r_from, int r_to) {
        removePiece(state, king, k_from);
        removePiece(state, rook, r_from);
        addPiece(state, king, k_to);
        addPiece(state, rook, r_to);
    }

    public static void addPiece(NnueState state, int piece, int square) {
        int[] indices = getFeatureIndices(piece, square);
        addWeights(state.whiteAcc, L1_WEIGHTS[indices[0]]);
        addWeights(state.blackAcc, L1_WEIGHTS[indices[1]]);
    }

    public static void removePiece(NnueState state, int piece, int square) {
        int[] indices = getFeatureIndices(piece, square);
        subtractWeights(state.whiteAcc, L1_WEIGHTS[indices[0]]);
        subtractWeights(state.blackAcc, L1_WEIGHTS[indices[1]]);
    }

    /**
     * Calculates the final evaluation score from the current accumulator state.
     * This version fixes integer overflow, scaling, and perspective weight selection.
     */
    public static int evaluateFromAccumulator(NnueState state, boolean whiteToMove) {
        short[] stmAcc = whiteToMove ? state.whiteAcc : state.blackAcc;
        short[] oppAcc = whiteToMove ? state.blackAcc : state.whiteAcc;

        // BUG FIX: The weights are perspective-based, not color-based.
        // L2_WEIGHTS[0] is for the side to move, L2_WEIGHTS[1] is for the opponent.
        short[] stmWeights = L2_WEIGHTS[0];
        short[] oppWeights = L2_WEIGHTS[1];

        // BUG FIX: Use 'long' to prevent overflow during summation.
        long output = 0;
        for (int i = 0; i < HL_SIZE; i++) {
            output += screlu(stmAcc[i]) * stmWeights[i];
            output += screlu(oppAcc[i]) * oppWeights[i];
        }

        // BUG FIX: Correctly apply bias before the final division.
        output += L2_BIASES[0];
        return (int) (output * FV_SCALE / QAB);
    }

    private static int screlu(short v) {
        int val = Math.max(0, v);
        return (val * val) >> 8;
    }

    private static void addWeights(short[] accumulator, short[] weights) {
        for (int i = 0; i < HL_SIZE; i++) {
            accumulator[i] += weights[i];
        }
    }

    private static void subtractWeights(short[] accumulator, short[] weights) {
        for (int i = 0; i < HL_SIZE; i++) {
            accumulator[i] -= weights[i];
        }
    }
}