// File: MovePicker.java
package core.impl;

import core.contracts.MoveGenerator;

import static core.contracts.PositionFactory.*;

/**
 * A move picker that generates, scores, and sorts moves for the search algorithm.
 * It provides moves one by one, from best to worst, according to various heuristics.
 * This replaces the older MoveOrderer system with a stateful picker, similar to
 * the one in the reference code.
 */
public final class MovePicker {
    // --- Stages of move picking ---
    private static final int STAGE_TT_MOVE = 0;
    private static final int STAGE_GENERATE_AND_SORT = 1;
    private static final int STAGE_ITERATE = 2;

    // --- Scoring constants for move ordering ---
    private static final int SCORE_CAPTURE_BASE = 8_000_000;
    private static final int SCORE_PROMOTION_BASE = 7_000_000;
    private static final int SCORE_KILLER_1 = 6_000_000;
    private static final int SCORE_KILLER_2 = 5_900_000;

    // --- MVV-LVA (Most Valuable Victim - Least Valuable Attacker) table ---
    private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 20000}; // P, N, B, R, Q, K
    private static final int[][] MVV_LVA_SCORES = new int[6][6];

    static {
        // Pre-compute MVV-LVA scores
        for (int victim = 0; victim < 6; victim++) {
            for (int attacker = 0; attacker < 6; attacker++) {
                MVV_LVA_SCORES[victim][attacker] = (PIECE_VALUES[victim] * 100) - PIECE_VALUES[attacker];
            }
        }
    }

    private final MoveGenerator mg;
    private final long[] bb;
    private final int ttMove;
    private final int[] killers;
    private final int[][] history;
    private final boolean qsearch;

    private final int[] moves = new int[256];
    private final int[] scores = new int[256];
    private int moveCount = 0;
    private int currentIndex = 0;
    private int stage;

    /**
     * Constructs a new MovePicker.
     *
     * @param mg      The move generator.
     * @param bb      The board state.
     * @param ttMove  The move from the transposition table.
     * @param killers The killer moves for the current ply.
     * @param history The history heuristic table.
     * @param qsearch True if this is for quiescence search (only tactical moves).
     */
    public MovePicker(MoveGenerator mg, long[] bb, int ttMove, int[] killers, int[][] history, boolean qsearch) {
        this.mg = mg;
        this.bb = bb;
        this.ttMove = ttMove;
        this.killers = killers;
        this.history = history;
        this.qsearch = qsearch;
        this.stage = (ttMove != 0) ? STAGE_TT_MOVE : STAGE_GENERATE_AND_SORT;
    }

    /**
     * Returns the next best move or 0 if no moves are left.
     */
    public int next() {
        if (stage == STAGE_TT_MOVE) {
            stage = STAGE_GENERATE_AND_SORT;
            return ttMove;
        }

        if (stage == STAGE_GENERATE_AND_SORT) {
            generateAndScore();
            stage = STAGE_ITERATE;
        }

        if (stage == STAGE_ITERATE) {
            if (currentIndex >= moveCount) {
                return 0; // No more moves
            }

            // Use selection sort to find the best move in the remaining list
            int bestIndex = currentIndex;
            for (int i = currentIndex + 1; i < moveCount; i++) {
                if (scores[i] > scores[bestIndex]) {
                    bestIndex = i;
                }
            }

            swap(currentIndex, bestIndex);
            int move = moves[currentIndex];
            currentIndex++;

            // Skip the TT move if we already returned it, and get the next one
            if (move == ttMove) {
                return next();
            }
            return move;
        }

        return 0; // Should not be reached
    }

    /**
     * Gets the 1-based index of the move currently being searched (excluding the TT move).
     * This is used for Late Move Reductions (LMR).
     */
    public int getMoveIndex() {
        return currentIndex;
    }

    private void generateAndScore() {
        if (qsearch) {
            // Q-search: only generate captures and non-capture promotions.
            moveCount = mg.generateCaptures(bb, moves, 0);
            int quietStart = moveCount;
            int totalMoves = mg.generateQuiets(bb, moves, quietStart);
            int promotionIndex = quietStart;
            for (int i = quietStart; i < totalMoves; i++) {
                if (((moves[i] >>> 14) & 0x3) == 1) { // Is promotion
                    moves[promotionIndex++] = moves[i];
                }
            }
            moveCount = promotionIndex; // We now have captures + promotions
        } else {
            // Normal search: generate all pseudo-legal moves.
            int capturesEnd = mg.generateCaptures(bb, moves, 0);
            moveCount = mg.generateQuiets(bb, moves, capturesEnd);
        }

        // Score all the generated moves.
        for (int i = 0; i < moveCount; i++) {
            scores[i] = scoreMove(moves[i]);
        }
    }

    private int scoreMove(int move) {
        // Promotions are scored high.
        if (((move >>> 14) & 0x3) == 1) {
            int promoPieceType = (move >>> 12) & 0x3;
            return SCORE_PROMOTION_BASE + (promoPieceType * 100); // Queen promo (type 3) is highest.
        }

        // Captures are scored next, using MVV-LVA.
        int capturedPieceType = getCapturedPieceTypeCode(move);
        if (capturedPieceType != -1) {
            int moverType = ((move >>> 16) & 0xF) % 6;
            return SCORE_CAPTURE_BASE + MVV_LVA_SCORES[capturedPieceType][moverType];
        }

        // Quiet moves are scored last.
        if (!qsearch) {
            // Killer moves get a bonus.
            if (killers != null) {
                if (move == killers[0]) return SCORE_KILLER_1;
                if (move == killers[1]) return SCORE_KILLER_2;
            }
            // History heuristic score for all other quiet moves.
            if (history != null) {
                int from = (move >>> 6) & 0x3F;
                int to = move & 0x3F;
                return history[from][to];
            }
        }

        return 0;
    }

    private int getCapturedPieceTypeCode(int move) {
        int to = move & 0x3F;
        long toBit = 1L << to;
        int moveType = (move >>> 14) & 0x3;
        boolean isWhiteMover = (((move >>> 16) & 0xF) < 6);

        if (moveType == 2) { // En-passant capture
            return 0; // The captured piece is a pawn.
        }

        int p_start = isWhiteMover ? BP : WP;
        int p_end = isWhiteMover ? BK : WK;

        for (int p = p_start; p <= p_end; p++) {
            if ((bb[p] & toBit) != 0) {
                return p % 6; // Return piece type index (0-5)
            }
        }
        return -1; // Not a capture
    }

    private void swap(int i, int j) {
        int tempMove = moves[i];
        moves[i] = moves[j];
        moves[j] = tempMove;

        int tempScore = scores[i];
        scores[i] = scores[j];
        scores[j] = tempScore;
    }
}