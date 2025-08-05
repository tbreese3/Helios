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
    void orderMoves(long[] bb, int[] moves, int count, int ttMove, int[] killers, int prevMove);

    /**
     * Applies Static Exchange Evaluation (SEE) to prune losing captures.
     * It reorders the move list in-place so that only moves with a non-negative
     * SEE score remain at the beginning of the list.
     *
     * @param bb    The current board state.
     * @param moves The array of moves to be pruned (typically captures).
     * @param count The number of moves in the array.
     * @return The number of moves remaining after pruning.
     */
    int seePrune(long[] bb, int[] moves, int count);

    /**
        Calculates the Static Exchange Evaluation for a single move.
        @param bb The current board state.
        @param move The move to evaluate.
        @return The static evaluation of the exchange in centipawns.
    */
    int see(long[] bb, int move);
}