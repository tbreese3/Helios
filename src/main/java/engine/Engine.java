package engine;

/** A pluggable, protocol-agnostic chess engine core. */
public interface Engine {
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
