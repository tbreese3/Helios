/*
 *  A position is stored in a **13-element {@code long[]}**:
 *
 *  ┌───────────── indices 0-11 ─────────────┐  ┌ index 12 ┐
 *  │ WP WN WB WR WQ WK  BP BN BB BR BQ BK │  │  META-64  │
 *  └────────────────────────────────────────┘  └──────────┘
 *
 *  The META word packs all non-bitboard state in **one 64-bit long**:
 *
 *  <pre>
 *      bit  0     side-to-move     0 = White, 1 = Black
 *      bits 1-4   castling rights  0x1 K, 0x2 Q, 0x4 k, 0x8 q
 *      bits 5-10  en-passant sq.   0-63, value 63 ⇒ “no EP”
 *      bits 11-17 half-move clock  0-127   (7 bits)
 *      bits 18-26 full-move number 1-512   (9 bits)
 *      bits 27-63 reserved/future
 *  </pre>
 *
 *  This interface exposes:
 *
 *  • creation of an immutable {@link engine.Position} from the packed array
 *  • reverse-copy of the packed array from any {@link engine.Position}
 *  • an *allocation-free* {@link #makeMoveInPlace} that updates the array
 *    according to a 32-bit move produced by {@code PackedMoveFactory}
 *
 *  The constants and helpers below reflect **the new bit offsets**.
 */
package engine.internal.search;

import engine.Position;

public interface PackedPositionFactory {

  /* ───────────────────────────────────── Piece-array indices ──────────── */

  int WP = 0, WN = 1, WB = 2, WR = 3, WQ = 4, WK = 5;
  int BP = 6, BN = 7, BB = 8, BR = 9, BQ = 10, BK = 11;
  int META = 12; // metadata long

  long EP_NONE = 63; // encoded “no en-passant square”

  /* ──────────────────────────────── Public API ────────────────────────── */

  Position fromBitboards(long[] packed13);

  long[] toBitboards(Position position);

  void makeMoveInPlace(long[] packed13, int packedMove);

  /* ─────────────────────────── Low-level helpers ──────────────────────── */

  static int lsb(long bb) {
    return Long.numberOfTrailingZeros(bb);
  }

  static long popLsb(long bb) {
    return bb & (bb - 1);
  }

  /* ─────────────────────────── Metadata helpers ───────────────────────── */

  /*  Masks / shifts for the new layout */
  long STM_MASK = 1L; // bit 0
  int CR_SHIFT = 1; // bits 1-4
  long CR_MASK = 0b1111L << CR_SHIFT;
  int EP_SHIFT = 5; // bits 5-10
  long EP_MASK = 0x3FL << EP_SHIFT;
  int HC_SHIFT = 11; // bits 11-17
  long HC_MASK = 0x7FL << HC_SHIFT;
  int FM_SHIFT = 18; // bits 18-26
  long FM_MASK = 0x1FFL << FM_SHIFT;

  /*  Accessors  */
  static boolean whiteToMove(long meta) {
    return (meta & STM_MASK) == 0;
  }

  static int castling(long meta) {
    return (int) ((meta & CR_MASK) >>> CR_SHIFT);
  }

  static int epSquare(long meta) {
    return (int) ((meta & EP_MASK) >>> EP_SHIFT);
  }

  static int halfClock(long meta) {
    return (int) ((meta & HC_MASK) >>> HC_SHIFT);
  }

  static int fullMove(long meta) {
    return (int) ((meta & FM_MASK) >>> FM_SHIFT) + 1;
  }

  /*  Packer for the new bit layout  */
  static long packMeta(
      boolean whiteToMove,
      int castlingRights,
      int enPassantSq, // 0-63, or EP_NONE
      int halfMoveClock, // 0-127
      int fullMoveNumber) // 1-512  (values >512 are clamped)
      {
    long m = 0;
    m |= whiteToMove ? 0L : STM_MASK;
    m |= (long) (castlingRights & 0xF) << CR_SHIFT;
    m |= (long) (enPassantSq & 0x3F) << EP_SHIFT;
    m |= (long) (halfMoveClock & 0x7F) << HC_SHIFT;
    m |= (long) (Math.max(1, Math.min(fullMoveNumber, 512)) - 1) << FM_SHIFT;
    return m;
  }
}
