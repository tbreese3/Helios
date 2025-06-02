package engine.internal.search;

/**
 * Low-level, allocation-free move generator working directly on the 13-element packed-bitboard
 * representation produced by {@link PackedPositionFactory}.
 *
 * <p>The caller supplies a pre-allocated {@code int[]} buffer; the implementation fills it with
 * <em>packed</em> 16-bit moves (see {@link PackedMoveFactory}) and returns the count of entries
 * written. No heap memory is touched on the critical path.
 */
public interface MoveGenerator {

  int generateAll(long[] packedPosition, int[] mv);
  int generateCaptures(long[] packedPosition, int[] mv);
  int generateQuiets(long[] packedPosition, int[] mv);
  int generateEvasions(long[] packedPosition, int[] mv);
  boolean kingAttacked(long[] bb, boolean moverWasWhite);
}
