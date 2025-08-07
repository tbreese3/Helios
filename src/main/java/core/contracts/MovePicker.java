package core.contracts;

/**
 * A stage-based move picker designed for efficient move ordering in a chess search.
 * <p>
 * Instead of generating and sorting all moves at once, this class provides moves
 * in distinct stages, allowing the search to potentially prune branches after
 * trying only the most promising moves (e.g., the TT move or good captures).
 * This is more efficient than the traditional generate-and-sort approach.
 * </p>
 * <p>Usage:</p>
 * <pre>{@code
 * MovePicker picker = new MovePickerImpl(...);
 * picker.scoreAndPrepare(board, ttMove, killers, history, false);
 * int move;
 * while ((move = picker.nextMove()) != 0) {
 * // ... search this move ...
 * }
 * }</pre>
 */
public interface MovePicker {

    /**
     * Scores all available moves and prepares the picker for the search at the current node.
     * This method should be called once before starting to iterate through moves with {@link #nextMove()}.
     *
     * @param bb           The current board state.
     * @param ttMove       The move from the transposition table, which should be tried first (can be 0).
     * @param killers      An array of killer moves for the current ply (can be null).
     * @param history      A 2D array representing the history heuristic scores (can be null).
     * @param capturesOnly If true, generate and score only capture moves.
     */
    void scoreAndPrepare(long[] bb, int ttMove, int[] killers, int[][] history, boolean capturesOnly);

    /**
     * Returns the next best move to search.
     * <p>
     * The moves are returned according to the following stages:
     * <ol>
     * <li>Transposition Table Move</li>
     * <li>Good Captures (those with non-negative SEE)</li>
     * <li>Killer Moves</li>
     * <li>Quiet Moves (ordered by history heuristic)</li>
     * <li>Bad Captures (those with negative SEE)</li>
     * </ol>
     *
     * @return The next move as an integer, or {@code 0} if no moves are left.
     */
    int nextMove();
}