package core.impl;

import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;

/**
 * A basic, reusable move orderer that prioritizes moves based on a simple
 * scoring system. It scores moves in the following order of importance:
 * 1. Transposition Table Move
 * 2. Queen Promotions
 * 3. Captures (using MVV-LVA)
 * 4. Other Promotions
 * 5. Quiet Moves
 */
public final class MoveOrdererImpl implements MoveOrderer {

    private static final int TT_MOVE_SCORE = 200_000;
    private static final int PROMOTION_SCORE_BASE = 180_000;
    private static final int CAPTURE_SCORE_BASE = 100_000;

    // Piece values for MVV-LVA: {P, N, B, R, Q, K}
    private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 20000};

    private int[] moveList;
    private int moveCount;
    private final int[] moveScores = new int[256]; // Max possible moves

    /**
     * Creates a reusable move orderer. The internal buffers are reused
     * on each call to scoreMoves().
     */
    public MoveOrdererImpl() {}

    @Override
    public void scoreMoves(long[] bb, int[] moves, int count, int ttMove) {
        this.moveList = moves;
        this.moveCount = count;
        // Reset only the scores needed for the current move list
        for (int i = 0; i < count; i++) {
            moveScores[i] = 0;
        }

        boolean isWhite = PositionFactory.whiteToMove(bb[PositionFactory.META]);
        int enemyPieceOffset = isWhite ? PositionFactory.BP : PositionFactory.WP;

        for (int i = 0; i < moveCount; i++) {
            int move = this.moveList[i];
            if (move == ttMove) {
                moveScores[i] = TT_MOVE_SCORE;
                continue;
            }

            int to = move & 0x3F;
            int type = (move >>> 14) & 0x3;
            int mover = (move >>> 16) & 0xF;

            // Score promotions
            if (type == 1) {
                int promotionPieceType = (move >>> 12) & 0x3; // 3=Q, 2=R, 1=B, 0=N
                moveScores[i] = PROMOTION_SCORE_BASE + promotionPieceType * 1000;
            }
            // Score captures using MVV-LVA
            else if (isCapture(bb, to, isWhite)) {
                int victim = findVictim(bb, to, enemyPieceOffset);
                if (victim != -1) {
                    int victimValue = PIECE_VALUES[victim % 6];
                    int aggressorValue = PIECE_VALUES[mover % 6];
                    moveScores[i] = CAPTURE_SCORE_BASE + (victimValue * 10 - aggressorValue);
                }
            }
        }
    }

    private boolean isCapture(long[] bb, int to, boolean isWhite) {
        long enemyPieces = 0L;
        int start = isWhite ? PositionFactory.BP : PositionFactory.WP;
        int end = isWhite ? PositionFactory.BK : PositionFactory.WK;
        for (int i = start; i <= end; i++) {
            enemyPieces |= bb[i];
        }
        return (enemyPieces & (1L << to)) != 0;
    }

    private int findVictim(long[] bb, int to, int enemyPieceOffset) {
        long toBit = 1L << to;
        for (int i = 0; i < 6; i++) {
            if ((bb[enemyPieceOffset + i] & toBit) != 0) {
                return enemyPieceOffset + i;
            }
        }
        return -1; // Should not happen on a valid capture
    }

    @Override
    public int selectNextMove(int moveIndex) {
        int bestScore = -1;
        int bestIndex = moveIndex;

        for (int j = moveIndex; j < moveCount; j++) {
            if (moveScores[j] > bestScore) {
                bestScore = moveScores[j];
                bestIndex = j;
            }
        }

        // Swap the best-found move to the current position
        if (bestIndex != moveIndex) {
            int tempMove = moveList[moveIndex];
            moveList[moveIndex] = moveList[bestIndex];
            moveList[bestIndex] = tempMove;

            // Swap its score as well to keep arrays in sync
            int tempScore = moveScores[moveIndex];
            moveScores[moveIndex] = moveScores[bestIndex];
            moveScores[bestIndex] = tempScore;
        }

        return moveList[moveIndex];
    }
}