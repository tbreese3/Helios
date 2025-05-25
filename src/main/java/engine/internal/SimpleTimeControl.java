package engine.internal;

import engine.*;

/** Immutable value-class implementation of {@link TimeControl} using a Java <em>record</em>. */
public record SimpleTimeControl(
    long whiteTimeMs,
    long blackTimeMs,
    long whiteIncMs,
    long blackIncMs,
    int movesToGo,
    boolean isFixedMoveTime,
    boolean isInfinite)
    implements TimeControl {

  /** Performs basic sanity checks (non‑negative clock and increment values). */
  public SimpleTimeControl {
    if (whiteTimeMs < 0 || blackTimeMs < 0 || whiteIncMs < 0 || blackIncMs < 0) {
      throw new IllegalArgumentException("Time or increment values cannot be negative");
    }
  }
}
