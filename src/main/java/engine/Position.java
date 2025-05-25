package engine;

/**
 * Immutable snapshot of a chessboard—sufficient for move‑generation, hashing, and FEN export—yet
 * free of any UI or protocol concerns. See the original user‑supplied javadoc for details.
 */
public interface Position {
  /* ────── Side to move & clocks ────── */
  boolean whiteToMove();

  int halfmoveClock();

  int fullmoveNumber();

  /* ────── Castling / en‑passant ────── */
  int castlingRights(); // bit mask 0x1,0x2,0x4,0x8 – as defined earlier

  int enPassantSquare(); // 0‑63 or –1 if none

  /* ────── Piece / board access ────── */
  long toBitboard(); // aggregate occupancy (All)

  /* ────── Hashing / serialisation ────── */
  long zobrist();

  String toFen();
}
