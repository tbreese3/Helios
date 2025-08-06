// C:/dev/Helios/src/main/java/core/impl/MovePicker.java
package core.impl;

import core.contracts.MoveGenerator;
import core.contracts.MoveOrderer;

/**
 * Provides moves one by one, in stages, for the search algorithm.
 * This class now handles both standard positions and check evasions.
 */
public class MovePicker {

    private enum Stage { TT_MOVE, GOOD_CAPTURES, KILLERS, QUIETS, BAD_CAPTURES, EVASIONS }

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
            // If in check, we only care about evasions. Generate them all at once.
            this.currentStage = Stage.EVASIONS;
            this.numMoves = moveGenerator.generateEvasions(board, this.moves, 0);
            moveOrderer.orderMoves(board, this.moves, this.numMoves, ttMove, null); // Sort evasions
        } else {
            // Not in check, start with the normal staged generation process.
            this.currentStage = Stage.TT_MOVE;
        }
    }

    /**
     * @return The next best move from the current stage, or 0 if no moves are left.
     */
    public int nextMove() {
        // If we have moves ready in the current buffer, return one.
        if (moveIndex < numMoves) {
            return moves[moveIndex++];
        }

        // If we were in check, all evasions were generated in the constructor.
        // If the buffer is now empty, there are no more moves.
        if (inCheck) {
            return 0;
        }

        // --- Not in check: Advance to the next stage ---
        while (true) {
            // Current stage is exhausted, advance to the next one
            switch (currentStage) {
                case TT_MOVE:
                    currentStage = Stage.GOOD_CAPTURES;
                    if (ttMove != 0) {
                        moves[0] = ttMove;
                        numMoves = 1;
                        moveIndex = 0;
                        return moves[moveIndex++]; // Return TT move immediately
                    }
                    // Fall-through if no TT move
                case GOOD_CAPTURES:
                    currentStage = Stage.KILLERS;
                    numMoves = moveGenerator.generateCaptures(board, moves, 0);
                    int goodCount = 0;
                    for (int i = 0; i < numMoves; i++) {
                        if (moves[i] != ttMove) { // Don't re-test the TT move
                            if (moveOrderer.see(board, moves[i]) >= 0) {
                                moves[goodCount++] = moves[i];
                            } else {
                                badCaptures[numBadCaptures++] = moves[i];
                            }
                        }
                    }
                    numMoves = goodCount;
                    moveOrderer.orderMoves(board, moves, numMoves, 0, null);
                    moveIndex = 0;
                    if (moveIndex < numMoves) return moves[moveIndex++];
                    // Fall-through if no good captures
                case KILLERS:
                    currentStage = Stage.QUIETS;
                    numMoves = 0;
                    if (killerMoves != null) {
                        if (killerMoves[0] != 0 && killerMoves[0] != ttMove) moves[numMoves++] = killerMoves[0];
                        if (killerMoves[1] != 0 && killerMoves[1] != ttMove && killerMoves[1] != killerMoves[0]) moves[numMoves++] = killerMoves[1];
                    }
                    moveIndex = 0;
                    if (moveIndex < numMoves) return moves[moveIndex++];
                    // Fall-through if no killer moves
                case QUIETS:
                    currentStage = Stage.BAD_CAPTURES;
                    numMoves = moveGenerator.generateQuiets(board, moves, 0);
                    moveOrderer.orderMoves(board, moves, numMoves, 0, killerMoves);
                    moveIndex = 0;
                    if (moveIndex < numMoves) return moves[moveIndex++];
                    // Fall-through if no quiet moves
                case BAD_CAPTURES:
                    currentStage = null; // Mark as exhausted
                    System.arraycopy(badCaptures, 0, moves, 0, numBadCaptures);
                    numMoves = numBadCaptures;
                    moveOrderer.orderMoves(board, moves, numMoves, 0, null);
                    moveIndex = 0;
                    if (moveIndex < numMoves) return moves[moveIndex++];
                    // Fall-through
                default:
                    return 0; // All stages are done
            }
        }
    }
}