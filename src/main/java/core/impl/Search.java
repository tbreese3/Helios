package core.impl;

import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Thin façade that delegates all heavy lifting to the configured WorkerPool.
 * Nothing here is pool-specific – you can plug in LazySmpWorkerPoolImpl or any
 * other WorkerPool implementation.
 */
public final class Search {

    /* immutable engine parts */
    private final PositionFactory positionFactory;
    private final MoveGenerator   moveGenerator;

    /* configurable services */
    private NNUE           nnue;
    private TranspositionTable  transpositionTable;
    private WorkerPool          workerPool;
    private TimeManager         timeManager;

    /* thread option remembered for future pool swaps */
    private int requestedThreads = 1;

    public Search(PositionFactory pf,
                  MoveGenerator   mg,
                  NNUE       nnue,
                  WorkerPool      pool,
                  TimeManager     tm) {

        this.positionFactory = pf;
        this.moveGenerator   = mg;
        this.nnue            = nnue;
        this.workerPool      = pool;
        this.timeManager     = tm;
    }

    /* ── setters required by Search interface ───────────────────── */
    public void setTranspositionTable(TranspositionTable tt) { this.transpositionTable = tt; }

    public void setThreads(int n) {
        this.requestedThreads = n;
        if (workerPool != null) workerPool.setParallelism(n);
    }

    public void setWorkerPool(WorkerPool pool) {
        if (this.workerPool != null) {
            try { this.workerPool.close(); } catch (Exception ignored) {}
        }
        this.workerPool = pool;
        if (pool != null) pool.setParallelism(requestedThreads);
    }

    public void setTimeManager(TimeManager tm) { this.timeManager = tm; }

    /* ── synchronous / asynchronous search ─────────────────────── */

    public SearchResult search(long[] bb, SearchSpec spec, Consumer<SearchInfo> ih) {
        if (workerPool == null)
            throw new IllegalStateException("WorkerPool not set");
        return workerPool
                .startSearch(bb, spec,
                        positionFactory, moveGenerator,
                        nnue, transpositionTable,
                        timeManager, ih)
                .join(); // block caller
    }

    public CompletableFuture<SearchResult> searchAsync(long[] bb,
                                                       SearchSpec spec,
                                                       Consumer<SearchInfo> ih) {
        return CompletableFuture.supplyAsync(() -> search(bb, spec, ih));
    }

    /* ── UCI helpers ───────────────────────────────────────────── */

    public void stop()       { if (workerPool != null) workerPool.stopSearch(); }
    public void ponderHit()  { /* not implemented */  }

    public void close() {
        if (workerPool != null) {
            try { workerPool.close(); } catch (Exception ignored) {}
        }
    }
}
