// C:/dev/Helios/src/main/java/core/impl/MovePicker.java
package core.impl;

import core.contracts.MoveGenerator;
import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;

/**
 * Provides moves one by one, in stages, for the search algorithm.
 * This class now handles both standard positions and check evasions correctly.
 */
public class MovePicker {

    private enum Stage { TT_MOVE, GOOD_CAPTURES, KILLERS, QUIETS, BAD_CAPTURES, EVASIONS, DONE }

    private final long[] board;
    private final MoveGenerator moveGenerator;
    private final MoveOrderer moveOrderer;
    private final boolean inCheck;

    private final int ttMove;
    private final int[] killerMoves;
    private final int[] moves = new int[256];
    private final int[] badCaptures = new int[256];

    private Stage currentStage;
    private int moveIndex = 0;
    private int numMoves = 0;
    private int numBadCaptures = 0;

    public MovePicker(long[] board, MoveGenerator mg, MoveOrderer mo, int ttMove, int[] killers, boolean inCheck) {
        this.board = board;
        this.moveGenerator = mg;
        this.moveOrderer = mo;
        this.ttMove = ttMove;
        this.killerMoves = killers;
        this.inCheck = inCheck;

        if (inCheck) {
            this.currentStage = Stage.EVASIONS;
            this.numMoves = moveGenerator.generateEvasions(board, this.moves, 0);
            moveOrderer.orderMoves(board, this.moves, this.numMoves, ttMove, null);
        } else {
            this.currentStage = Stage.TT_MOVE;
        }
    }

    public int nextMove() {
        if (moveIndex < numMoves) {
            return moves[moveIndex++];
        }

        if (inCheck) {
            return 0;
        }

        while (currentStage != Stage.DONE) {
            currentStage = Stage.values()[currentStage.ordinal() + 1];
            moveIndex = 0;

            switch (currentStage) {
                case GOOD_CAPTURES:
                    numMoves = moveGenerator.generateCaptures(board, moves, 0);
                    int goodCount = 0;
                    for (int i = 0; i < numMoves; i++) {
                        if (moves[i] != ttMove) {
                            if (moveOrderer.see(board, moves[i]) >= 0) {
                                moves[goodCount++] = moves[i];
                            } else {
                                badCaptures[numBadCaptures++] = moves[i];
                            }
                        }
                    }
                    numMoves = goodCount;
                    moveOrderer.orderMoves(board, moves, numMoves, 0, null);
                    break;

                case KILLERS:
                    numMoves = 0;
                    if (killerMoves != null) {
                        // Check first killer
                        if (isValidKiller(killerMoves[0])) {
                            moves[numMoves++] = killerMoves[0];
                        }
                        // Check second killer (ensure it's different)
                        if (isValidKiller(killerMoves[1]) && killerMoves[1] != killerMoves[0]) {
                            moves[numMoves++] = killerMoves[1];
                        }
                    }
                    break;

                case QUIETS:
                    numMoves = moveGenerator.generateQuiets(board, moves, 0);
                    moveOrderer.orderMoves(board, moves, numMoves, 0, killerMoves);
                    break;

                case BAD_CAPTURES:
                    System.arraycopy(badCaptures, 0, moves, 0, numBadCaptures);
                    numMoves = numBadCaptures;
                    moveOrderer.orderMoves(board, moves, numMoves, 0, null);
                    break;

                case EVASIONS:
                case DONE:
                    numMoves = 0;
                    break;

                case TT_MOVE:
                    numMoves = 0;
                    if (ttMove != 0) {
                        moves[0] = ttMove;
                        numMoves = 1;
                    }
                    break;
            }

            if (numMoves > 0) {
                return moves[moveIndex++];
            }
        }

        return 0;
    }

    /**
     * Checks if a killer move is valid in the current position.
     * A killer is only valid if it's not the TT move and it's a quiet move (not a capture).
     */
    private boolean isValidKiller(int killerMove) {
        if (killerMove == 0 || killerMove == ttMove) {
            return false;
        }

        // A killer move, by definition, must be a quiet move.
        // We must check if the destination square is empty.
        int toSquare = killerMove & 0x3F;
        long allPieces = 0L;
        for (int i = 0; i <= PositionFactory.BK; i++) {
            allPieces |= board[i];
        }

        // If the 'to' square is occupied, this move is a capture here, not a quiet move.
        // Therefore, it cannot be a killer move in this position.
        return (allPieces & (1L << toSquare)) == 0;
    }
}