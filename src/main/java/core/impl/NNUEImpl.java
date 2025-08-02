package core.impl;

import core.contracts.NNUE;
import core.contracts.PositionFactory;
import core.records.NNUEState;
import main.Main;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static core.contracts.PositionFactory.*;
import static core.contracts.PositionFactory.BK;
import static core.contracts.PositionFactory.BR;
import static core.contracts.PositionFactory.WK;

/**
 * Manages NNUE network loading, feature transformation, and evaluation logic.
 * All methods are static, making this a central utility class for NNUE operations.
 */
public final class NNUEImpl implements NNUE {
    // --- Network Parameters (Weights and Biases) ---
    private static short[][] L1_WEIGHTS; // [INPUT_SIZE][HL_SIZE]
    private static short[] L1_BIASES;    // [HL_SIZE]
    private static short[][] L2_WEIGHTS; // [2][HL_SIZE]
    private static short[] L2_BIASES;    // [1]

    private static boolean isLoaded = false;

    public void loadNetwork()
    {
        try (InputStream nnueStream = Main.class.getResourceAsStream(networkPath)) {
            loadNetwork(nnueStream, "embedded resource");
        } catch (Exception e) {
            System.out.println("info string Error loading embedded NNUE file: " + e.getMessage());
        }
    }

    /**
     * Loads the quantized network weights from a filesystem path.
     */
    private synchronized void loadNetwork(String filePath) {
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
    private synchronized void loadNetwork(InputStream is, String sourceName) {
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

    public boolean isLoaded() {
        return isLoaded;
    }

    private static int[] getFeatureIndices(int piece, int square) {
        int color = piece / 6;
        int pieceType = piece % 6;
        int whiteFeature = (color * 384) + (pieceType * 64) + square;
        int blackFeature = ((1 - color) * 384) + (pieceType * 64) + (square ^ 56);
        return new int[]{whiteFeature, blackFeature};
    }

    public void refreshAccumulator(NNUEState state, long[] bb) {
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

    public void updateNnueAccumulator(NNUEState state, int moverPiece, int capturedPiece, int move) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            removePiece(state, capturedPiece, capturedSquare);
        }

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            removePiece(state, moverPiece, from);
            addPiece(state, promotedToPiece, to);
        } else if (moveType == 3) { // Castle
            int rook = moverPiece < 6 ? WR : BR;
            switch(to) {
                case 6: updateCastle(state, WK, rook, 4, 6, 7, 5); break; // White O-O
                case 2: updateCastle(state, WK, rook, 4, 2, 0, 3); break; // White O-O-O
                case 62: updateCastle(state, BK, rook, 60, 62, 63, 61); break; // Black O-O
                case 58: updateCastle(state, BK, rook, 60, 58, 56, 59); break; // Black O-O-O
            }
        } else { // Normal move
            updateAccumulator(state, moverPiece, from, to);
        }
    }

    public void undoNnueAccumulatorUpdate(NNUEState state, int moverPiece, int capturedPiece, int move) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            removePiece(state, promotedToPiece, to);
            addPiece(state, moverPiece, from);
        } else if (moveType == 3) { // Castle
            int rook = moverPiece < 6 ? WR : BR;
            switch(to) {
                case 6: updateCastle(state, WK, rook, 6, 4, 5, 7); break; // Undo White O-O
                case 2: updateCastle(state, WK, rook, 2, 4, 3, 0); break; // Undo White O-O-O
                case 62: updateCastle(state, BK, rook, 62, 60, 61, 63); break; // Undo Black O-O
                case 58: updateCastle(state, BK, rook, 58, 60, 59, 56); break; // Undo Black O-O-O
            }
        } else { // Normal move
            updateAccumulator(state, moverPiece, to, from);
        }

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            addPiece(state, capturedPiece, capturedSquare);
        }
    }

    private void updateAccumulator(NNUEState state, int piece, int from, int to) {
        removePiece(state, piece, from);
        addPiece(state, piece, to);
    }

    private void updateCastle(NNUEState state, int king, int rook, int k_from, int k_to, int r_from, int r_to) {
        removePiece(state, king, k_from);
        removePiece(state, rook, r_from);
        addPiece(state, king, k_to);
        addPiece(state, rook, r_to);
    }

    private void addPiece(NNUEState state, int piece, int square) {
        int[] indices = getFeatureIndices(piece, square);
        addWeights(state.whiteAcc, L1_WEIGHTS[indices[0]]);
        addWeights(state.blackAcc, L1_WEIGHTS[indices[1]]);
    }

    private void removePiece(NNUEState state, int piece, int square) {
        int[] indices = getFeatureIndices(piece, square);
        subtractWeights(state.whiteAcc, L1_WEIGHTS[indices[0]]);
        subtractWeights(state.blackAcc, L1_WEIGHTS[indices[1]]);
    }

    /**
     * Calculates the final evaluation score from the current accumulator state.
     * This version fixes integer overflow, scaling, and perspective weight selection.
     */
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