package engine;

/**
 * Call-back sink for asynchronous search progress.
 *
 * <p>A front-end (UCI session, GUI adapter, CLI) implements this interface and receives incremental
 * <em>info</em> records plus the final result.
 */
public interface SearchListener {

  /**
   * Periodic search information—depth, nodes, score, PV, etc. <br>
   * Guaranteed to be called from the engine’s worker thread; the listener should return quickly.
   */
  void onInfo(SearchInfo info);

  /** Final result of a search, emitted exactly once per {@link Engine#startSearch Search}. */
  void onResult(SearchResult result);
}
