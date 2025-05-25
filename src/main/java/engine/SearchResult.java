package engine;

import java.util.List;

/**
 * Immutable bundle of information returned by the engine when a search finishes or is forcibly
 * stopped.
 */
public interface SearchResult {

  /** The move the engine recommends in the root position; never {@code null}. */
  Move bestMove();

  /**
   * The move the engine intends to ponder on next, or {@code null} if no pondering is suggested.
   */
  Move ponderMove();

  /**
   * Centipawn score from the point of view of the side to move. Positive means better for the side
   * to move.
   */
  int scoreCp();

  /**
   * Mate distance in <em>moves</em> (not plies); positive means the engine can force mate, negative
   * means it is being mated. Returns {@code null} if the score is not a mate score.
   */
  Integer mateIn();

  /** Total number of nodes searched. */
  long nodes();

  /** Principal variation (PV) line; never {@code null} but may be empty. */
  List<Move> pv();

  /**
   * @return {@code true} if {@link #mateIn()} is non-null.
   */
  default boolean isMateScore() {
    return mateIn() != null;
  }
}
