package core.contracts;

/**
 * A service that scores and sorts a list of moves to improve
 * alpha-beta search efficiency. An instance of this class is designed
 * to be reused across different nodes in the search tree to reduce
 * memory allocations.
 */
public interface MoveOrderer {
    /**
     * Resets the move orderer with a new list of moves for the current node.
     * This method scores all moves and must be called before move selection begins.
     *
     * @param bb      The current board state.
     * @param moves   The array containing pseudo-legal moves.
     * @param count   The number of moves in the array.
     * @param ttMove  The move from the transposition table, if any.
     */
    void scoreMoves(long[] bb, int[] moves, int count, int ttMove);

    /**
     * Finds the best-scoring move in the remaining list, swaps it
     * to the current moveIndex, and returns it.
     *
     * @param moveIndex The current index in the move list to fill.
     * @return The best move selected for the current stage.
     */
    int selectNextMove(int moveIndex);
}