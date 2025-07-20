// File: LazySmpWorkerPoolImpl.java
package core.impl;

import core.contracts.*;
import core.records.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread pool that manages a fixed set of persistent worker threads.
 */
public final class LazySmpWorkerPoolImpl implements WorkerPool {

    private final SearchWorkerFactory factory;
    private int parallelism;
    private final List<LazySmpSearchWorkerImpl> workers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    final AtomicBoolean stopFlag = new AtomicBoolean(false);
    final AtomicLong totalNodes = new AtomicLong(0);

    private CompletableFuture<SearchResult> searchFuture;

    public LazySmpWorkerPoolImpl(int threads, SearchWorkerFactory f) {
        this.factory = f;
        this.parallelism = threads;
        resizePool();
    }

    public LazySmpWorkerPoolImpl(SearchWorkerFactory f) {
        this(1, f);
    }

    @Override
    public synchronized void setParallelism(int threads) {
        if (threads == this.parallelism) return;
        close();
        this.parallelism = threads;
        resizePool();
    }

    private void resizePool() {
        workers.clear();
        threads.clear();
        for (int i = 0; i < parallelism; i++) {
            LazySmpSearchWorkerImpl worker = (LazySmpSearchWorkerImpl) factory.create(i == 0, this);
            workers.add(worker);
            Thread thread = new Thread(worker, "Helios-Worker-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
    }

    @Override
    public CompletableFuture<SearchResult> startSearch(
            long[] root, SearchSpec spec, PositionFactory pf, MoveGenerator mg,
            Evaluator ev, TranspositionTable tt, TimeManager tm, InfoHandler ih)
    {
        // Wait for the previous search to completely finish
        workers.get(0).waitWorkerFinished();

        // Setup for the new search
        this.stopFlag.set(false);
        this.totalNodes.set(0);

        // **NEW**: Configure the stateful TimeManager for this specific search
        tm.set(spec, root);

        this.searchFuture = new CompletableFuture<>();

        for (LazySmpSearchWorkerImpl worker : workers) {
            // Pass the configured TimeManager to each worker
            worker.prepareForSearch(root, spec, pf, mg, ev, tt, tm);
            worker.setInfoHandler(worker.isMainThread ? ih : null);
        }

        // Start the main worker, which will orchestrate the others
        workers.get(0).startWorkerSearch();

        return searchFuture;
    }

    void startHelpers() {
        for (int i = 1; i < workers.size(); i++) {
            workers.get(i).startWorkerSearch();
        }
    }

    void waitForHelpersFinished() {
        for (int i = 1; i < workers.size(); i++) {
            workers.get(i).waitWorkerFinished();
        }
    }

    void finalizeSearch(SearchResult mainResult) {
        long allNodes = 0;
        for (LazySmpSearchWorkerImpl w : workers) {
            allNodes += w.getNodes();
        }

        SearchResult finalResult = new SearchResult(
                mainResult.bestMove(), mainResult.ponderMove(), mainResult.pv(),
                mainResult.scoreCp(), mainResult.mateFound(), mainResult.depth(),
                allNodes,
                mainResult.timeMs()
        );

        if (searchFuture != null && !searchFuture.isDone()) {
            searchFuture.complete(finalResult);
        }
    }

    @Override public long totalNodes() {
        long total = 0;
        for (SearchWorker w : workers) {
            total += w.getNodes();
        }
        return total;
    }

    @Override public void stopSearch() { stopFlag.set(true); }
    boolean isStopped() { return stopFlag.get(); }
    @Override public AtomicBoolean getStopFlag() { return stopFlag; }

    @Override
    public synchronized void close() {
        if (workers.isEmpty()) return;
        for (LazySmpSearchWorkerImpl worker : workers) {
            worker.terminate();
        }
        for (Thread thread : threads) {
            try {
                thread.join(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        threads.clear();
    }
}