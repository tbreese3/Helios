package engine.internal;

import engine.*;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper that enforces “one search at a time” and delegates all
 * heavy lifting to a pluggable {@link engine.internal.search.Searcher}.
 */
public class EngineImpl implements Engine {

  private final engine.internal.search.Searcher searcher;
  private final AtomicBoolean running = new AtomicBoolean(false);

  EngineImpl(engine.internal.search.Searcher searcher) {
    this.searcher = Objects.requireNonNull(searcher, "searcher");
  }

  @Override
  public void startSearch(SearchRequest req, SearchListener lsn) {
    Objects.requireNonNull(req,  "req");
    Objects.requireNonNull(lsn,  "lsn");

    if (!running.compareAndSet(false, true))
      throw new IllegalStateException("Search already running");

    /* Listener wrapper to clear the flag when the search ends. */
    SearchListener wrapper = new SearchListener() {
      @Override public void onInfo(SearchInfo info)          { lsn.onInfo(info); }
      @Override public void onResult(SearchResult result)    {
        try { lsn.onResult(result); }
        finally { running.set(false); }
      }
    };

    searcher.start(req, wrapper);   // fire-and-forget
  }

  @Override
  public void stop() {
    if (running.get()) searcher.stop();
  }

  @Override
  public void onPonderHit() {
    if (running.get()) searcher.ponderHit();
  }
}
