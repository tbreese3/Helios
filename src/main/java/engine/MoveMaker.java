package engine;

public interface MoveMaker {
  /**
   * Applies {@code move} to {@code pos} and returns the resulting position.
   *
   * @throws IllegalMoveException if {@code move} is not legal in {@code pos}.
   */
  Position make(Position pos, Move move);
}
