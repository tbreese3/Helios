// C:\dev\Helios\src\main\java\core\impl\SearchImpl.java
package core.impl;

import core.contracts.*;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.concurrent.CompletableFuture;

public final class SearchImpl implements Search {

    private final PositionFactory positionFactory;
    private final MoveGenerator moveGenerator;
    private final TimeManagerFactory timeManagerFactory;

    private Evaluator evaluator;
    private TranspositionTable transpositionTable;
    private WorkerPool workerPool;
    private int requestedThreads = 1;

    public SearchImpl(PositionFactory pf, MoveGenerator mg, Evaluator eval, WorkerPool pool, TimeManagerFactory tmf) {
        this.positionFactory = pf;
        this.moveGenerator = mg;
        this.evaluator = eval;
        this.workerPool = pool;
        this.timeManagerFactory = tmf;
    }

    @Override
    public void setEvaluator(Evaluator e) { this.evaluator = e; }
    @Override
    public void setTranspositionTable(TranspositionTable tt) { this.transpositionTable = tt; }
    @Override
    public void setWorkerPool(WorkerPool pool) {
        if (this.workerPool != null) {
            try { this.workerPool.close(); } catch (Exception ignored) {}
        }
        this.workerPool = pool;
        if (pool != null) pool.setParallelism(requestedThreads);
    }
    @Override
    public void setThreads(int n) {
        this.requestedThreads = n;
        if (workerPool != null) workerPool.setParallelism(n);
    }

    @Override
    public SearchResult search(long[] bb, SearchSpec spec, InfoHandler ih) {
        return searchAsync(bb, spec, ih).join();
    }

    @Override
    public CompletableFuture<SearchResult> searchAsync(long[] bb, SearchSpec spec, InfoHandler ih) {
        if (workerPool == null) throw new IllegalStateException("WorkerPool not set");
        TimeManager timeManager = timeManagerFactory.create(spec, PositionFactory.whiteToMove(bb[PositionFactory.META]));
        return workerPool.startSearch(bb, spec, positionFactory, moveGenerator, evaluator, transpositionTable, timeManager, ih);
    }

    @Override
    public void stop() { if (workerPool != null) workerPool.stopSearch(); }
    @Override
    public void ponderHit() { /* not implemented */ }
    @Override
    public void close() {
        if (workerPool != null) {
            try { workerPool.close(); } catch (Exception ignored) {}
        }
    }
}