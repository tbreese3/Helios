package core.impl;

import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;

/**
 * A basic, reusable move orderer that sorts a move list in-place.
 * It prioritizes moves based on a simple scoring system:
 * 1. Transposition Table Move
 * 2. Queen Promotions
 * 3. Captures (using MVV-LVA)
 * 4. Other Promotions
 * 5. Quiet Moves
 */
public final class MoveOrdererImpl implements MoveOrderer {

    // --- Score Constants ---
    private static final int SCORE_TT_MOVE = 100_000;
    private static final int SCORE_QUEEN_PROMO = 90_000;
    private static final int SCORE_CAPTURE_BASE = 80_000;
    private static final int SCORE_UNDER_PROMO = 70_000;

    // --- Piece Values for MVV-LVA ---
    private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 10000}; // P,N,B,R,Q,K
    private static final int[][] MVV_LVA_SCORES = new int[6][6];

    // --- Scratch Buffers ---
    private final int[] moveScores = new int[256]; // Assumes max 256 moves

    static {
        // Pre-compute MVV-LVA scores
        for (int victim = 0; victim < 6; victim++) {
            for (int attacker = 0; attacker < 6; attacker++) {
                MVV_LVA_SCORES[victim][attacker] = PIECE_VALUES[victim] - (PIECE_VALUES[attacker] / 100);
            }
        }
    }

    @Override
    public void orderMoves(long[] bb, int[] moves, int count, int ttMove) {
        boolean whiteToMove = PositionFactory.whiteToMove(bb[PositionFactory.META]);

        // 1. Score every move
        for (int i = 0; i < count; i++) {
            int move = moves[i];
            if (move == ttMove) {
                moveScores[i] = SCORE_TT_MOVE;
                continue;
            }

            int moveType = (move >>> 14) & 0x3;
            int moverType = ((move >>> 16) & 0xF) % 6;
            int toSquare = move & 0x3F;

            if (moveType == 1) { // Promotion
                int promoType = (move >>> 12) & 0x3;
                moveScores[i] = (promoType == 3) ? SCORE_QUEEN_PROMO : SCORE_UNDER_PROMO;
            } else {
                int capturedPieceType = getCapturedPieceType(bb, toSquare, whiteToMove);
                if (capturedPieceType != -1) { // Capture
                    moveScores[i] = SCORE_CAPTURE_BASE + MVV_LVA_SCORES[capturedPieceType][moverType];
                } else { // Quiet move
                    moveScores[i] = 0;
                }
            }
        }

        // 2. Sort the move list in-place using insertion sort
        for (int i = 1; i < count; i++) {
            int currentMove = moves[i];
            int currentScore = moveScores[i];
            int j = i - 1;

            // Shift elements to the right until the correct spot for the current move is found
            while (j >= 0 && moveScores[j] < currentScore) {
                moves[j + 1] = moves[j];
                moveScores[j + 1] = moveScores[j];
                j--;
            }
            moves[j + 1] = currentMove;
            moveScores[j + 1] = currentScore;
        }
    }

    /**
     * Determines the type of piece on a given square.
     * @return The piece type (0-5 for P,N,B,R,Q,K), or -1 if the square is empty.
     */
    private int getCapturedPieceType(long[] bb, int toSquare, boolean whiteToMove) {
        long toBit = 1L << toSquare;
        int start = whiteToMove ? PositionFactory.BP : PositionFactory.WP;
        int end = whiteToMove ? PositionFactory.BK : PositionFactory.WK;

        for (int pieceType = start; pieceType <= end; pieceType++) {
            if ((bb[pieceType] & toBit) != 0) {
                return pieceType % 6; // Return 0-5
            }
        }
        return -1; // Not a capture
    }
}