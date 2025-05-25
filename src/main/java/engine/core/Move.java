package engine.core;

/**
 * Immutable chess move in long-algebraic form (“e2e4”, “e7e8q”, “0000”).
 *
 * <p>Implementations remain free to store the move in a packed {@code int} or any other encoding;
 * callers work only with the accessors below.
 */
public interface Move {

  /** 0 – 63 index of the origin square (A1 = 0, H8 = 63). */
  int from();

  /** 0 – 63 index of the destination square. */
  int to();

  /* — High-level categories — */

  /**
   * @return {@code true} if the move is a capture (including en-passant).
   */
  boolean isCapture();

  /**
   * @return {@code true} if the move promotes a pawn.
   */
  boolean isPromotion();

  /**
   * @return {@code true} if the move is kingside or queenside castling.
   */
  boolean isCastle();

  /**
   * @return {@code true} if the move is an en-passant capture.
   */
  boolean isEnPassant();

  /**
   * @return {@code true} if the move is the two-square pawn push.
   */
  boolean isDoublePawnPush();

  /**
   * @return {@code true} if this is the explicit “null move” (0000).
   */
  boolean isNull();

  /** Promotion piece letter (‘q’, ‘r’, ‘b’, ‘n’) or {@code '\0'} if none. */
  char promotion();

  /** Render as long algebraic notation, e.g. “e2e4”, “e7e8q”, “0000”. */
  String toUci();
}
