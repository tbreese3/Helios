package core.contracts;

public interface PositionFactory {

  /* ───────── Piece indices ──────── */
  int WP = 0, WN = 1, WB = 2, WR = 3, WQ = 4, WK = 5;
  int BP = 6, BN = 7, BB = 8, BR = 9, BQ = 10, BK = 11;
  int META = 12;
  int DIFF_META = 13;
  int DIFF_INFO = 14;

  /* ───────── Board array layout (indices) ───────── */
  int HASH      = 15; // 64-bit Zobrist key
  int COOKIE_SP = 16; // stack pointer
  int COOKIE_BASE = 17;
  int COOKIE_CAP  = 1000;
  int BB_LEN      = COOKIE_BASE + COOKIE_CAP; // New total length

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

  /* fast helpers – **signatures only** */
  boolean makeMoveInPlace(long[] bb, int move, MoveGenerator gen);
  void undoMoveInPlace(long[] bb);

  long[] fromFen(String fen);

  String toFen(long[] bb);

  long zobrist(long[] bb);

  long zobrist50(long[] bb);
  boolean hasNonPawnMaterial(long[] bb);

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