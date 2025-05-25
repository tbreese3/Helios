package engine.core;

import java.util.List;

/**
 * Incremental snapshot of the search, emitted via {@link SearchListener#onInfo(SearchInfo)}.
 * Mirrors the information fields defined by the UCI protocol.
 *
 * <p>All numerical fields are <em>absolute</em>; listeners decide whether to compute deltas.
 */
public interface SearchInfo {

  /* ────────────── depth & nodes ────────────── */

  /** Current depth in plies. */
  int depth();

  /** Current selective search depth (0 ≤ selDepth ≤ depth). */
  int selDepth();

  /** Nodes searched so far. */
  long nodes();

  /** Nodes per second, averaged over the search so far. */
  long nps();

  /* ─────────────── score / mate ────────────── */

  /** Centipawn score from the side-to-move’s perspective. */
  int scoreCp();

  /**
   * Mate distance in moves (positive means mating, negative = being mated); {@code null} if not a
   * mate score.
   */
  Integer mateIn();

  /** True if {@link #scoreCp()} is a lower bound (“score cp … lowerbound”). */
  boolean lowerBound();

  /** True if {@link #scoreCp()} is an upper bound. */
  boolean upperBound();

  /* ─────────────── time / TB / hash ───────── */

  /** Milliseconds spent searching so far. */
  long timeMs();

  /** Hash-table fullness in ‰ (0-1000), or {@code -1} if unknown. */
  int hashFull();

  /** Table-base hits so far. */
  long tbHits();

  /* ─────────────── current line / PV ───────── */

  /** Principal variation for this info step; never {@code null}. */
  List<Move> pv();

  /** Move currently being searched at the leaf, or {@code null}. */
  Move currentMove();

  /** 1-based index of {@link #currentMove()} inside its move list, or {@code 0} if unknown. */
  int currentMoveNumber();
}
