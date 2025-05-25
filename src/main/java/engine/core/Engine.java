package engine.core;

/** A pluggable, protocol-agnostic chess engine core. */
public interface Engine {

  /** Reset long-lived state such as hash tables, learning files, or opening statistics. */
  void newGame();

  /**
   * Perform a <em>blocking</em> search and return the final result.
   *
   * <p>Intended for CLI tools and unit tests where a synchronous call is more convenient than an
   * event stream.
   *
   * @param req fully populated search request, never {@code null}
   * @return a non-{@code null} {@link SearchResult} containing the best move and search statistics
   */
  SearchResult search(SearchRequest req) throws InterruptedException;

  /**
   * Start an <em>asynchronous</em> search (“go …”). Results and incremental information are
   * delivered to the supplied listener on an engine-controlled thread.
   *
   * @param req fully populated search request, never {@code null}
   * @param lsn listener that receives {@code info} and {@code bestmove} callbacks; never {@code
   *     null}
   * @throws IllegalStateException if a search is already running
   */
  void startSearch(SearchRequest req, SearchListener lsn);

  /** Inform the engine that the GUI has just played the move it was pondering on (“ponderhit”). */
  void onPonderHit();

  /**
   * Request the engine to terminate the current search as soon as reasonably possible (“stop”).
   * Implementations must still emit a {@code bestmove} result (via the {@link SearchListener})
   * before returning idle.
   */
  void stop();
}
