package core.impl;

import core.contracts.*;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.concurrent.CompletableFuture;

/**
 * Thin façade that delegates all heavy lifting to the configured WorkerPool.
 * Nothing here is pool-specific – you can plug in LazySmpWorkerPoolImpl or any
 * other WorkerPool implementation.
 */
public final class SearchImpl implements Search {

    /* immutable engine parts */
    private final PositionFactory positionFactory;
    private final MoveGenerator   moveGenerator;

    /* configurable services */
    private TranspositionTable  transpositionTable;
    private WorkerPool          workerPool;
    private TimeManager         timeManager;

    /* thread option remembered for future pool swaps */
    private int requestedThreads = 1;

    public SearchImpl(PositionFactory pf,
                      MoveGenerator   mg,
                      WorkerPool      pool,
                      TimeManager     tm) {

        this.positionFactory = pf;
        this.moveGenerator   = mg;
        this.workerPool      = pool;
        this.timeManager     = tm;
    }

    /* ── setters required by Search interface ───────────────────── */
    @Override public void setTranspositionTable(TranspositionTable tt) { this.transpositionTable = tt; }

    @Override public void setThreads(int n) {
        this.requestedThreads = n;
        if (workerPool != null) workerPool.setParallelism(n);
    }

    @Override public int getThreads() {
        return workerPool.getParallelism();
    }

    @Override public void setWorkerPool(WorkerPool pool) {
        if (this.workerPool != null) {
            try { this.workerPool.close(); } catch (Exception ignored) {}
        }
        this.workerPool = pool;
        if (pool != null) pool.setParallelism(requestedThreads);
    }

    @Override public void setTimeManager(TimeManager tm) { this.timeManager = tm; }

    /* ── synchronous / asynchronous search ─────────────────────── */

    @Override
    public SearchResult search(long[] bb, SearchSpec spec, InfoHandler ih) {
        if (workerPool == null)
            throw new IllegalStateException("WorkerPool not set");
        return workerPool
                .startSearch(bb, spec,
                        positionFactory, moveGenerator,
                        transpositionTable,
                        timeManager, ih)
                .join(); // block caller
    }

    @Override
    public CompletableFuture<SearchResult> searchAsync(long[] bb,
                                                       SearchSpec spec,
                                                       InfoHandler ih) {
        return CompletableFuture.supplyAsync(() -> search(bb, spec, ih));
    }

    /* ── UCI helpers ───────────────────────────────────────────── */

    @Override public void stop()       { if (workerPool != null) workerPool.stopSearch(); }
    @Override public void ponderHit()  { /* not implemented */  }

    @Override
    public void close() {
        if (workerPool != null) {
            try { workerPool.close(); } catch (Exception ignored) {}
        }
    }
}
