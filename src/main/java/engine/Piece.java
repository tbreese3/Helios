package engine;

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

  /** Enumerates the two piece colors recognised by orthodox chess. */
  enum Color {
    WHITE,
    BLACK
  }

  /**
   * @return the type (king, queen, etc) of this piece (never {@code null}).
   */
  Type type();

  /**
   * @return the color (black, white) of this piece (never {@code null}).
   */
  Color color();
}

