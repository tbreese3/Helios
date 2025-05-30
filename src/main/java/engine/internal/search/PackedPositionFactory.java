/*
 *  A position is stored in a 13-element long[]:
 *
 *  ┌──────────── indices 0-11 ────────────┐  ┌ index 12 ┐
 *  │ WP WN WB WR WQ WK  BP BN BB BR BQ BK │  │  META-64 │
 *  └───────────────────────────────────────┘  └──────────┘
 *
 *  META packs all non-geometric state (see constants below).
 *
 *  API
 *  ──
 *    • immutable Position ↔ long[13] conversions
 *    • makeMoveInPlace / undoMoveInPlace  (classic cookie style)
 *    • fastMakeMove / fastUndoMove        (branch-light, cookie-less)
 */
package engine.internal.search;

import engine.Position;

public interface PackedPositionFactory {

  /* ───────── Piece indices ──────── */
  int WP = 0, WN = 1, WB = 2, WR = 3, WQ = 4, WK = 5;
  int BP = 6, BN = 7, BB = 8, BR = 9, BQ = 10, BK = 11;
  int META = 12;

  long EP_NONE = 63;

  long STM_MASK = 1L;
  int CR_SHIFT = 1;
  long CR_MASK = 0b1111L << CR_SHIFT;
  int EP_SHIFT = 5;
  long EP_MASK = 0x3FL << EP_SHIFT;
  int HC_SHIFT = 11;
  long HC_MASK = 0x7FL << HC_SHIFT;
  int FM_SHIFT = 18;
  long FM_MASK = 0x1FFL << FM_SHIFT;

  /* ───────── Primary (cookie) API ───────── */
  Position fromBitboards(long[] packed);

  long[] toBitboards(Position position);

  record Diff(int move, int from, int to, int capturedIdx, long oldMeta) {}

  /* fast helpers – **signatures only** */
  Diff makeMoveInPlace(long[] bb, int move, MoveGenerator gen);

  void undoMoveInPlace(long[] bb, Diff diff);

  /** true if bit 0 of META is 0 (= White to move) */
  static boolean whiteToMove(long meta) {
    return (meta & STM_MASK) == 0;
  }

  static long castling(long meta) {
    return (meta & CR_MASK) >>> CR_SHIFT;
  }

  static long epSquare(long meta) {
    return (meta & EP_MASK) >>> EP_SHIFT;
  }

  static long halfClock(long meta) {
    return (meta & HC_MASK) >>> HC_SHIFT;
  }

  static long fullMove(long meta) {
    return 1 + ((meta & FM_MASK) >>> FM_SHIFT);
  }
}
