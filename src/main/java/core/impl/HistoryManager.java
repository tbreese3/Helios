package core.impl;

import java.util.Arrays;

/**
 * Manages all history heuristic tables for a single search thread.
 */
public final class HistoryManager {
    // Maximum bonus value
    public static final int MAX_BONUS = 16384;

    // Main History
    private final int[][] mainHistory = new int[64][64];

    // Continuation History :
    // [PrevPiece][PrevToSquare][CurrentPiece][CurrentToSquare]
    // 12 pieces (WP..BK), 64 squares.
    private final int[][][][] continuationHistory = new int[12][64][12][64];

    // --- Main History Accessors ---
    public int getMainHistory(int from, int to) {
        return mainHistory[from][to];
    }

    public void updateMainHistory(int from, int to, int bonus) {
        // Update the specific entry using the decaying average formula.
        updateStat(mainHistory[from], to, bonus);
    }

    // --- Continuation History Accessors ---
    // Note: We assume the caller ensures indices are valid based on the context provided during search.
    public int getContinuationHistory(int prevPiece, int prevTo, int currentPiece, int currentTo) {
        return continuationHistory[prevPiece][prevTo][currentPiece][currentTo];
    }

    public void updateContinuationHistory(int prevPiece, int prevTo, int currentPiece, int currentTo, int bonus) {
        // Update the specific entry using the decaying average formula.
        updateStat(continuationHistory[prevPiece][prevTo][currentPiece], currentTo, bonus);
    }

    // --- Utility Methods ---

    /**
     * The standard decaying average update formula.
     * Formula: new = old + bonus - (old * |bonus|) / MAX_BONUS
     */
    private void updateStat(int[] array, int index, int bonus) {
        int clampedBonus = Math.max(-MAX_BONUS, Math.min(MAX_BONUS, bonus));
        // Using long for intermediate multiplication to prevent potential overflow.
        array[index] += clampedBonus - (int)(((long)array[index] * Math.abs(clampedBonus)) / MAX_BONUS);
    }

    /**
     * Clears all history tables. Called at the start of a new search.
     */
    public void clear() {
        for (int[] row : mainHistory) {
            Arrays.fill(row, 0);
        }
        // Clearing the large 4D continuation array
        for (int[][][] p1 : continuationHistory) {
            for (int[][] s1 : p1) {
                for (int[] p2 : s1) {
                    Arrays.fill(p2, 0);
                }
            }
        }
    }
}