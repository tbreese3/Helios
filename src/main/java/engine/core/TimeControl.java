package engine.core;

/**
 * Read-only view of the remaining clock and increment values derived from a single {@code go …}
 * line.
 */
public interface TimeControl {
  /** milliseconds left for White; 0 if unknown. */
  long whiteTimeMs();

  /** milliseconds left for Black; 0 if unknown. */
  long blackTimeMs();

  /** Fischer increment per move for White, in ms. */
  long whiteIncMs();

  /** Fischer increment per move for Black, in ms. */
  long blackIncMs();

  /** Number of moves until the next time control. */
  int movesToGo();

  /**
   * @return {@code true} for {@code go movetime x}.
   */
  boolean isFixedMoveTime();

  /**
   * @return {@code true} for {@code go infinite}.
   */
  boolean isInfinite();

  /** Effective time budget for the side to move, i.e. */
  default long timeBudget(boolean whiteToMove) {
    return whiteToMove ? whiteTimeMs() + whiteIncMs() : blackTimeMs() + blackIncMs();
  }
}
