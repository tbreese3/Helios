package engine.core;

/**
 * Immutable snapshot of a chessboard—sufficient for move-generation, hashing, and FEN export—yet
 * free of any UI or protocol concerns.
 *
 * <p>Square indices follow the common bitboard convention:
 *
 * <pre>
 * A1 = 0, B1 = 1, …, H8 = 63
 * </pre>
 *
 * <p>Implementations should be <strong>thread-safe</strong> and side-effect-free; all mutating
 * operations belong in a separate {@code BoardUpdater} or {@code MoveMaker} helper.
 */
public interface Position {

  /* ────── Side to move & clocks ────── */

  /**
   * @return {@code true} if White is to move.
   */
  boolean whiteToMove();

  /** Half-move clock for the fifty-move rule. */
  int halfmoveClock();

  /** Full-move number (starts at 1, incremented after Black’s move). */
  int fullmoveNumber();

  /* ────── Castling / en-passant ────── */

  /**
   * Bit-mask of castling rights:
   *
   * <pre>
   * 0x1 = White O-O, 0x2 = White O-O-O,
   * 0x4 = Black O-O, 0x8 = Black O-O-O
   * </pre>
   */
  int castlingRights();

  /**
   * En-passant target square (0-63) set by the <em>previous</em> double-pawn push, or {@code -1} if
   * none.
   */
  int enPassantSquare();

  /* ────── Piece accessors ────── */

  /**
   * Packed 64-bit bitboard for the given piece and colour. Implementations may return 0 for unknown
   * pieces.
   */
  long bitboard(Piece piece, boolean white);

  /**
   * Fast lookup of the piece on a square.
   *
   * @return piece enum or {@code null} for empty
   */
  Piece pieceAt(int square);

  /* ────── Hashing / serialisation ────── */

  /** Zobrist hash of this position. */
  long zobrist();

  /** Standard FEN string of the position. */
  String toFen();
}
