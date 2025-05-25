package engine.core;

/**
 * Immutable identity of a chess piece.
 *
 * <p>The public contract purposely exposes <em>only</em> colour and kind—implementation classes are
 * free to pack that into a single byte, two booleans, an enum constant, or anything else.
 */
public interface Piece {

  /** Enumerates the six piece kinds recognised by orthodox chess. */
  enum Type {
    PAWN,
    KNIGHT,
    BISHOP,
    ROOK,
    QUEEN,
    KING
  }

  /**
   * @return the kind of this piece (never {@code null}).
   */
  Type type();

  /**
   * @return {@code true} if the piece is White, {@code false} for Black.
   */
  boolean white();

  /* — Convenience — */

  /** Shorthand for {@code !white()}. */
  default boolean black() {
    return !white();
  }
}
