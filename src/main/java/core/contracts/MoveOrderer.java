package core.contracts;

/**
 * A service that scores and sorts a list of moves in-place to improve
 * alpha-beta search efficiency. An instance of this class is designed
 * to be reused across different nodes in the search tree to reduce
 * memory allocations.
 */
public interface MoveOrderer {
    /**
     * Scores and sorts the provided move list in-place, with the best
     * moves appearing first.
     *
     * @param bb     The current board state.
     * @param moves  The array containing pseudo-legal moves. This array will be modified.
     * @param count  The number of moves in the array.
     * @param ttMove The move from the transposition table, if any, to prioritize.
     */
    void orderMoves(long[] bb, int[] moves, int count, int ttMove);
}