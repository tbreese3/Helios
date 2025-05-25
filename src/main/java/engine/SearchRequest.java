package engine;

import java.util.List;

/**
 * Read-only view of all parameters derived from a single UCI {@code go …} command.
 *
 * <p>Implementations MUST be immutable. Any getter that returns {@code null} or an empty collection
 * simply means “not specified / use engine default.”
 *
 * <h3>Typical mappings</h3>
 *
 * <pre>{@code
 * go depth 8                    → depth()      = 8
 * go movetime 3000              → moveTimeMs() = 3_000
 * go wtime 120000 btime 90000   → timeControl().whiteTimeMs() = 120_000
 * go infinite                   → infinite()   = true
 * go ponder                     → ponder()     = true
 * go searchmoves e2e4 d2d4      → searchMoves() = [e2e4, d2d4]
 * }</pre>
 */
public interface SearchRequest {

  /** Position to search; never {@code null}. */
  Position position();

  /** Fixed depth in plies, or {@code null}. */
  Integer depth();

  /** Number of threads to use for search. */
  Integer threadCount();

  /** Node budget, or {@code null}. */
  Long nodes();

  /** Fixed slice of time in milliseconds ({@code go movetime}), or {@code null}. */
  Long moveTimeMs();

  /** {@code true} if the engine should stay in ponder mode. */
  boolean ponder();

  /** {@code true} if search should run until an explicit {@code stop}. */
  boolean infinite();

  /**
   * Optional restriction list supplied via {@code go searchmoves …}; an empty list means “consider
   * all moves.” Implementations MUST return an unmodifiable list.
   */
  List<Move> searchMoves();
}
