package engine.internal.search;

/**
 * Low-level, allocation-free move generator working directly on the
 * 13-element packed-bitboard representation produced by
 * {@link PackedPositionFactory}.
 *
 * <p>The caller supplies a pre-allocated {@code int[]} buffer; the
 * implementation fills it with <em>packed</em> 16-bit moves
 * (see {@link PackedMoveFactory}) and returns the count of entries
 * written. No heap memory is touched on the critical path.</p>
 */
public interface MoveGenerator {

    /**
     * Selection of move subsets that can be requested from the generator.
     *
     * <ul>
     *   <li>{@code ALL}    all pseudo-legal moves</li>
     *   <li>{@code CAPTURES} only moves that capture an enemy piece
     *       (plus queen promotions, Stockfish-style)</li>
     *   <li>{@code QUIETS}  all non-capture moves, including under-promotions</li>
     *   <li>{@code EVASIONS} moves that get the side-to-move out of check
     *       (captures + blocks + king moves + castling if legal)</li>
     * </ul>
     */
    enum GenMode { ALL, CAPTURES, QUIETS, EVASIONS }

    /**
     * Fills {@code buf} with moves for the position encoded in
     * {@code packedPosition}.
     *
     * @param packedPosition the 13-long[] position in the layout defined by
     *                       {@link PackedPositionFactory}
     * @param buf            caller-owned buffer to receive packed moves
     *                       (must be large enough for MAX_MOVES ≈ 218)
     * @param mode           which subset of moves to generate
     * @return               number of valid entries written to {@code buf}
     */
    int generate(long[] packedPosition, int[] buf, GenMode mode);
}
