// C:/dev/Helios/src/main/java/core/impl/MovePicker.java
package core.impl;

import core.contracts.MoveGenerator;
import core.contracts.MoveOrderer;

/**
 * Provides moves one by one, in stages, for the search algorithm.
 * This avoids the cost of generating and sorting all moves at once.
 */
public class MovePicker {

    private enum Stage { TT_MOVE, GOOD_CAPTURES, KILLERS, QUIETS, BAD_CAPTURES }

    private final long[] board;
    private final MoveGenerator moveGenerator;
    private final MoveOrderer moveOrderer;
    private final int ttMove;
    private final int[] killerMoves;
    private final int[] moves = new int[256];
    private final int[] badCaptures = new int[256];

    private Stage currentStage;
    private int moveIndex = 0;
    private int numMoves = 0;
    private int numBadCaptures = 0;

    public MovePicker(long[] board, MoveGenerator mg, MoveOrderer mo, int ttMove, int[] killers) {
        this.board = board;
        this.moveGenerator = mg;
        this.moveOrderer = mo;
        this.ttMove = ttMove;
        this.killerMoves = killers;
        this.currentStage = Stage.TT_MOVE;
    }

    /**
     * @return The next best move from the current stage, or 0 if no moves are left.
     */
    public int nextMove() {
        while (true) {
            if (moveIndex < numMoves) {
                return moves[moveIndex++];
            }

            // Current stage is exhausted, advance to the next one
            switch (currentStage) {
                case TT_MOVE:
                    currentStage = Stage.GOOD_CAPTURES;
                    if (ttMove != 0) {
                        moves[0] = ttMove;
                        numMoves = 1;
                        moveIndex = 0;
                        continue; // Re-loop to return the TT move
                    }
                    // Fall-through if no TT move
                case GOOD_CAPTURES:
                    currentStage = Stage.KILLERS;
                    numMoves = moveGenerator.generateCaptures(board, moves, 0);
                    // Use SEE to partition captures into "good" and "bad"
                    int goodCount = 0;
                    for (int i = 0; i < numMoves; i++) {
                        if (moveOrderer.see(board, moves[i]) >= 0) {
                            moves[goodCount++] = moves[i];
                        } else {
                            badCaptures[numBadCaptures++] = moves[i];
                        }
                    }
                    numMoves = goodCount;
                    moveOrderer.orderMoves(board, moves, numMoves, 0, null); // Sort only good captures
                    moveIndex = 0;
                    continue; // Re-loop to start returning captures

                case KILLERS:
                    currentStage = Stage.QUIETS;
                    numMoves = 0;
                    // Add valid killer moves that are not the TT move
                    if (killerMoves != null) {
                        if (killerMoves[0] != 0 && killerMoves[0] != ttMove) moves[numMoves++] = killerMoves[0];
                        if (killerMoves[1] != 0 && killerMoves[1] != ttMove) moves[numMoves++] = killerMoves[1];
                    }
                    moveIndex = 0;
                    continue;

                case QUIETS:
                    currentStage = Stage.BAD_CAPTURES;
                    numMoves = moveGenerator.generateQuiets(board, moves, 0);
                    moveOrderer.orderMoves(board, moves, numMoves, 0, killerMoves); // Sort quiets
                    moveIndex = 0;
                    continue;

                case BAD_CAPTURES:
                    // Load the bad captures we saved earlier
                    System.arraycopy(badCaptures, 0, moves, 0, numBadCaptures);
                    numMoves = numBadCaptures;
                    moveOrderer.orderMoves(board, moves, numMoves, 0, null); // Sort bad captures
                    moveIndex = 0;
                    currentStage = null; // Mark as exhausted
                    continue;

                default:
                    return 0; // All stages are done
            }
        }
    }
}