package engine.internal.search;

import engine.Position;

/**
 * Abstraction for producing and manipulating *packed* immutable {@link Position} instances. A
 * dedicated factory avoids leaking internal representation details (bitboard layout, Zobrist
 * arrays, etc.) into the rest of the engine.
 */
public interface PackedPositionFactory {
  /**
   * Builds a position directly from a 12‑element *bitboard* array and accompanying meta‑data. The
   * array layout must be {WP,WN,WB,WR,WQ,WK,BP,BN,BB,BR,BQ,BK}.
   */
  Position fromBitboards(long[] bitboards);

  /** Returns a defensive *copy* of the 12 bitboards contained in the supplied position. */
  long[] toBitboards(Position position);

  /**
   * Mutate the supplied <code>bitboards</code> array in‑place to reflect <code>packedMove</code>.
   * Faster (no allocation) but obviously not thread‑safe and the caller must maintain its own stack
   * for un‑make.
   */
  void makeMoveInPlace(long[] bitboards, int packedMove);

  /* Helpers exposed for search code */
  static int lsb(long bb) {
    return Long.numberOfTrailingZeros(bb);
  }

  static long popLsb(long bb) {
    return bb & (bb - 1);
  }
}
