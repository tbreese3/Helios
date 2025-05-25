package engine.internal;

import engine.TimeControl;

/** Immutable value-class implementation of {@link TimeControl} using a Java <em>record</em>. */
public record TimeControlImpl(
        long whiteTimeMs,
        long blackTimeMs,
        long whiteIncMs,
        long blackIncMs,
        int movesToGo,
        boolean isFixedMoveTime,
        boolean isInfinite)
        implements TimeControl {

  /** Performs basic sanity checks (non‑negative clock and increment values). */
  public TimeControlImpl {
    if (whiteTimeMs < 0 || blackTimeMs < 0 || whiteIncMs < 0 || blackIncMs < 0) {
      throw new IllegalArgumentException("Time or increment values cannot be negative");
    }
  }
}
