// C:/dev/Helios/src/main/java/core/nnue/NnueManager.java
package core.nnue;

import core.impl.LazySmpSearchWorkerImpl;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages NNUE network loading and provides static utility methods for evaluation.
 * The actual computation is delegated to a chosen Inference implementation (e.g., SIMD).
 */
public final class NnueManager {
    // --- Network Architecture Constants ---
    public static final int INPUT_SIZE = 768;
    public static final int HL_SIZE = 1536;
    public static final int QA = 255;
    public static final int QB = 64;
    public static final int QAB = QA * QB;
    public static final int FV_SCALE = 400;

    // --- Network Parameters ---
    private static short[][] L1_WEIGHTS; // [INPUT_SIZE][HL_SIZE]
    private static short[] L1_BIASES;    // [HL_SIZE]
    private static short[][] L2_WEIGHTS; // [2][HL_SIZE]
    private static short[] L2_BIASES;    // [1]

    private static boolean isLoaded = false;
    private static final VectorizedInference F = new VectorizedInference(); // Use the fast SIMD implementation

    public static synchronized void loadNetwork(InputStream is, String sourceName) {
        if (isLoaded || is == null) {
            if (is == null) System.err.println("NNUE InputStream is null from " + sourceName);
            return;
        }

        try (DataInputStream dis = new DataInputStream(is)) {
            L1_WEIGHTS = new short[INPUT_SIZE][HL_SIZE];
            L1_BIASES = new short[HL_SIZE];
            L2_WEIGHTS = new short[2][HL_SIZE];
            L2_BIASES = new short[1];
            byte[] buffer = new byte[2]; // Buffer for reading 2 bytes (a short)

            // Read L1 Weights
            for (int i = 0; i < INPUT_SIZE; i++) {
                for (int j = 0; j < HL_SIZE; j++) {
                    dis.readFully(buffer);
                    L1_WEIGHTS[i][j] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
                }
            }
            // Read L1 Biases
            for (int i = 0; i < HL_SIZE; i++) {
                dis.readFully(buffer);
                L1_BIASES[i] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
            }
            // Read L2 Weights
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < HL_SIZE; j++) {
                    dis.readFully(buffer);
                    L2_WEIGHTS[i][j] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
                }
            }
            // Read L2 Biases
            dis.readFully(buffer);
            L2_BIASES[0] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();

            isLoaded = true;
            System.out.println("info string NNUE loaded successfully from " + sourceName);
        } catch (Exception e) {
            isLoaded = false;
            System.err.println("Failed to read NNUE stream from " + sourceName + ": " + e.getMessage());
        }
    }

    public static boolean isLoaded() {
        return isLoaded;
    }

    /**
     * Evaluates the position given the current state on the accumulator stack.
     */
    public static int evaluate(LazySmpSearchWorkerImpl.NnueStackEntry entry, boolean whiteToMove) {
        if (!isLoaded) return 0;

        short[] stmAcc = whiteToMove ? entry.whiteAcc : entry.blackAcc;
        short[] oppAcc = whiteToMove ? entry.blackAcc : entry.whiteAcc;
        short[] stmWeights = L2_WEIGHTS[0];
        short[] oppWeights = L2_WEIGHTS[1];
        short bias = L2_BIASES[0];

        return F.evaluate(stmAcc, oppAcc, stmWeights, oppWeights, bias);
    }

    /**
     * Populates the initial accumulator state from a board position.
     */
    public static void refreshAccumulator(LazySmpSearchWorkerImpl.NnueStackEntry entry, long[] bb) {
        System.arraycopy(L1_BIASES, 0, entry.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, entry.blackAcc, 0, HL_SIZE);

        List<Integer> whiteIndices = new ArrayList<>();
        List<Integer> blackIndices = new ArrayList<>();

        for (int p = 0; p < 12; p++) {
            long board = bb[p];
            while (board != 0) {
                int sq = Long.numberOfTrailingZeros(board);
                whiteIndices.add(getFeatureIndex(p, sq, true));
                blackIndices.add(getFeatureIndex(p, sq, false));
                board &= board - 1;
            }
        }

        // Use the general applyChanges method for initial setup
        F.applyChanges(entry.whiteAcc, L1_BIASES, L1_WEIGHTS, whiteIndices.stream().mapToInt(i -> i).toArray(), new int[0]);
        F.applyChanges(entry.blackAcc, L1_BIASES, L1_WEIGHTS, blackIndices.stream().mapToInt(i -> i).toArray(), new int[0]);
    }

    /**
     * Applies the differences from a parent entry to compute a child entry's accumulators.
     */
    public static void applyChanges(LazySmpSearchWorkerImpl.NnueStackEntry child, LazySmpSearchWorkerImpl.NnueStackEntry parent) {
        F.applyChanges(child.whiteAcc, parent.whiteAcc, L1_WEIGHTS, child.diff.addedWhite, child.diff.removedWhite);
        F.applyChanges(child.blackAcc, parent.blackAcc, L1_WEIGHTS, child.diff.addedBlack, child.diff.removedBlack);
    }

    /**
     * Gets the feature index for a given piece on a square from a specific color's perspective.
     */
    public static int getFeatureIndex(int piece, int square, boolean isWhitePerspective) {
        int color = piece / 6;       // 0 for white, 1 for black
        int pieceType = piece % 6;   // 0 for pawn, 1 for knight, etc.
        int pColor = isWhitePerspective ? color : 1 - color;
        int pSq = isWhitePerspective ? square : square ^ 56;
        return (pColor * 384) + (pieceType * 64) + pSq;
    }
}