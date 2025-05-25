package engine.internal;

import engine.*;
import java.util.Objects;

/** Value-object implementation of {@link Piece} based on a Java <em>record</em>. */
public record SimplePiece(Type type, Color color) implements Piece {
  /** Performs basic sanity checks (non-null type and color). */
  public SimplePiece {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(color, "color must not be null");
  }
}
