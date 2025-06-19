package core.impl;

import core.contracts.*;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

public final class SearchImpl implements Search {

    private Evaluator evaluator;
    private TranspositionTable transpositionTable;
    private WorkerPool workerPool;
    private TimeManager timeManager;
    private final PositionFactory positionFactory;
    private final MoveGenerator moveGenerator;

    public SearchImpl(PositionFactory pf, MoveGenerator mg, Evaluator eval, WorkerPool wp, TimeManager tm) {
        this.positionFactory = pf;
        this.moveGenerator = mg;
        this.evaluator = eval;
        this.workerPool = wp;
        this.timeManager = tm;
    }

    @Override
    public void setEvaluator(Evaluator evaluator) { this.evaluator = evaluator; }

    @Override
    public void setTranspositionTable(TranspositionTable tt) { this.transpositionTable = tt; }

    @Override
    public void setThreads(int workerCount) { if (workerPool != null) workerPool.setParallelism(workerCount); }

    @Override
    public void setWorkerPool(WorkerPool pool) {
        if (this.workerPool != null) {
            try { this.workerPool.close(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        this.workerPool = pool;
    }

    @Override
    public void setTimeManager(TimeManager timeManager) { this.timeManager = timeManager; }

    @Override
    public SearchResult search(long[] bb, SearchSpec spec, InfoHandler ih) {
        workerPool.setInfoHandler(ih);
        workerPool.startSearch(bb, spec, positionFactory, moveGenerator, evaluator, transpositionTable, timeManager);
        workerPool.waitForSearchFinished();
        SearchResult result = workerPool.getFinalResult();
        return result != null ? result : new SearchResult(0, 0, Collections.emptyList(), 0, false, 0, 0, 0);
    }

    @Override
    public CompletableFuture<SearchResult> searchAsync(long[] bb, SearchSpec spec, InfoHandler ih) {
        return CompletableFuture.supplyAsync(() -> search(bb, spec, ih));
    }

    @Override
    public void stop() { if (workerPool != null) workerPool.stopSearch(); }

    @Override
    public void ponderHit() { /* Not implemented */ }

    @Override
    public void close() {
        if (workerPool != null) {
            try { workerPool.close(); } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}