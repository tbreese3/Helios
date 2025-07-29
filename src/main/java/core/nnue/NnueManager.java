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

    // --- Network Parameters (Weights and Biases) ---
    private static short[][] L1_WEIGHTS; // [INPUT_SIZE][HL_SIZE]
    private static short[] L1_BIASES;    // [HL_SIZE]
    private static short[][] L2_WEIGHTS; // [2][HL_SIZE]
    private static short[] L2_BIASES;    // [1] - just one bias value

    private static boolean isLoaded = false;

    /**
     * Loads the quantized network weights from a filesystem path.
     * This is useful for the UCI 'NNUEFile' option.
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
     * This is the primary method used for loading bundled resources.
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

    /**
     * Calculates the feature indices for a given piece and square.
     * This mirrors the logic from the Rust trainer's 'Chess768' input type.
     * @return An array of two integers: [white_perspective_index, black_perspective_index]
     */
    private static int[] getFeatureIndices(int piece, int square) {
        // Color: 0 for white, 1 for black
        int color = piece / 6;
        // Piece type: 0 for pawn, 1 for knight, ..., 5 for king
        int pieceType = piece % 6;

        int whiteFeature = (color * 384) + (pieceType * 64) + square;
        int blackFeature = ((1 - color) * 384) + (pieceType * 64) + (square ^ 56);
        return new int[]{whiteFeature, blackFeature};
    }

    /**
     * Fully re-calculates the accumulator and active features for a given board state.
     * Called at the beginning of a search.
     */
    public static void refreshAccumulator(NnueState state, long[] bb) {
        // Start with the biases
        System.arraycopy(L1_BIASES, 0, state.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, state.blackAcc, 0, HL_SIZE);
        state.activeWhiteFeatures.clear();
        state.activeBlackFeatures.clear();

        for (int p = PositionFactory.WP; p <= PositionFactory.BK; p++) {
            long board = bb[p];
            while (board != 0) {
                int sq = Long.numberOfTrailingZeros(board);
                int[] indices = getFeatureIndices(p, sq);
                state.activeWhiteFeatures.add(indices[0]);
                state.activeBlackFeatures.add(indices[1]);
                addWeights(state.whiteAcc, L1_WEIGHTS[indices[0]]);
                addWeights(state.blackAcc, L1_WEIGHTS[indices[1]]);
                board &= board - 1;
            }
        }
    }

    /**
     * Applies a move to the accumulator incrementally.
     */
    public static void updateAccumulator(NnueState state, int piece, int from, int to) {
        // 1. Deactivate piece at 'from' square
        int[] fromIndices = getFeatureIndices(piece, from);
        subtractWeights(state.whiteAcc, L1_WEIGHTS[fromIndices[0]]);
        subtractWeights(state.blackAcc, L1_WEIGHTS[fromIndices[1]]);

        // 2. Activate piece at 'to' square
        int[] toIndices = getFeatureIndices(piece, to);
        addWeights(state.whiteAcc, L1_WEIGHTS[toIndices[0]]);
        addWeights(state.blackAcc, L1_WEIGHTS[toIndices[1]]);
    }

    /**
     * Adds weights for a captured piece to the accumulator during an undo operation.
     */
    public static void addPiece(NnueState state, int piece, int square) {
        int[] indices = getFeatureIndices(piece, square);
        addWeights(state.whiteAcc, L1_WEIGHTS[indices[0]]);
        addWeights(state.blackAcc, L1_WEIGHTS[indices[1]]);
    }

    /**
     * Removes weights for a piece from the accumulator (e.g., when captured).
     */
    public static void removePiece(NnueState state, int piece, int square) {
        int[] indices = getFeatureIndices(piece, square);
        subtractWeights(state.whiteAcc, L1_WEIGHTS[indices[0]]);
        subtractWeights(state.blackAcc, L1_WEIGHTS[indices[1]]);
    }


    /**
     * Calculates the final evaluation score from the current accumulator state.
     */
    public static int evaluateFromAccumulator(NnueState state, boolean whiteToMove) {
        short[] stmAcc = whiteToMove ? state.whiteAcc : state.blackAcc;
        short[] oppAcc = whiteToMove ? state.blackAcc : state.whiteAcc;
        short[] stmWeights = whiteToMove ? L2_WEIGHTS[0] : L2_WEIGHTS[1];
        short[] oppWeights = whiteToMove ? L2_WEIGHTS[1] : L2_WEIGHTS[0];

        int output = 0;
        for (int i = 0; i < HL_SIZE; i++) {
            output += screlu(stmAcc[i]) * stmWeights[i];
            output += screlu(oppAcc[i]) * oppWeights[i];
        }

        // De-quantize the output layer sum
        output /= QA;
        output += L2_BIASES[0];

        // Final scaling to get centipawns
        // The scale of 400 from your trainer is for converting win probability, not the raw eval.
        // The common formula is (score * factor) / normalization. Let's assume a factor of 600.
        // Final score = (output * 600) / (QA * QB)
        // With integer math: (output * 600) / 16320. This simplifies to (output * 25) / 680
        // We will just use the output for now and scale later if needed.
        return (output * 25)/ 680;
    }

    // SCReLU activation: (clamp(v, 0, 255) ^ 2) / 255
    // Using integer math: (v * v) >> 8 is a good approximation for v*v/256.
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