package engine.internal.search;

import engine.Move;

/**
 * 16 bit move stored in an {@code int} for fast access. bits 0-5 to-square (0-63) bits 6-11
 * from-square (0-63) bits 12-13 promotion piece (0 = N, 1 = B, 2 = R, 3 = Q) bits 14-15 move type
 * (0 = normal, 1 = promotion, 2 = en-passant, 3 = castle) bits 16-31 reserved
 */
public interface PackedMoveFactory {
  /** Convert a high-level {@link Move} into the packed 32-bit form. */
  int toPacked(Move unpackedMove);

  /** Reconstruct an immutable {@link Move} view from the packed value. */
  Move toUnpacked(int packedMove);

  /** Destination square (0–63). */
  static int to(int packed) {
    return packed & 0x3F;
  }

  /** Origin square (0–63). */
  static int from(int packed) {
    return (packed >>> 6) & 0x3F;
  }

  /** 2-bit promotion index (0 = N, 1 = B, 2 = R, 3 = Q). */
  static int promoIndex(int packed) {
    return (packed >>> 12) & 0x3;
  }

  /** 2-bit move type (0 = normal, 1 = promotion, 2 = en-passant, 3 = castle). */
  static int type(int packed) {
    return (packed >>> 14) & 0x3;
  }
}
