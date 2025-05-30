package engine.internal.search;

/**
 * Allocation-free <strong>legal</strong> move generator that works directly on the
 * 13-element packed bit-board representation defined by {@link PackedPositionFactory}.
 *
 * <p>The caller passes a pre-allocated {@code int[]} buffer; the implementation writes
 * packed 16-bit moves (see {@link PackedMoveFactory}) and returns the number of
 * entries produced — absolutely no heap traffic happens on the hot path.</p>
 */
public interface MoveGenerator {

  // ──────────────────────────────────────────────────────────────
  //  Requested move subsets
  // ──────────────────────────────────────────────────────────────

  /**
   * Which fraction of <em>legal</em> moves to generate.
   *
   * <ul>
   *   <li>{@code ALL}      every legal move in the position</li>
   *   <li>{@code CAPTURES} legal captures <em>plus</em> the standard
   *       Stockfish-style “<q>good</q>” promotions (i.e. x-piece → queen)</li>
   *   <li>{@code QUIETS}   all legal non-capture moves (pushes, under-promotions,
   *       castling, …)</li>
   *   <li>{@code EVASIONS} only moves that legally get the side to move
   *       out of check – captures, blocks, king moves and, if allowed, castling</li>
   * </ul>
   *
   * <p><strong>Note:</strong> because the generator is already legal,
   * {@code EVASIONS} will naturally yield an empty list when the king is
   * not in check.</p>
   */
  enum GenMode { ALL, CAPTURES, QUIETS, EVASIONS }

  // ──────────────────────────────────────────────────────────────
  //  API
  // ──────────────────────────────────────────────────────────────

  /**
   * Generates moves for the supplied packed position.
   *
   * @param packedPosition 13-long[] position as produced by
   *                       {@link PackedPositionFactory}
   * @param buf            caller-owned buffer; must hold at least ≈218 ints
   * @param mode           subset selector
   * @return number of valid entries written to {@code buf}
   */
  int generate(long[] packedPosition, int[] buf, GenMode mode);
}
