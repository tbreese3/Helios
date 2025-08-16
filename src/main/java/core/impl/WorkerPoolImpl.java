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
 * This design is based on the threading model of high-performance chess engines,
 * where threads are long-lived and coordinated using condition variables.
 */
public final class WorkerPoolImpl implements WorkerPool {

    private final SearchWorkerFactory factory;
    private int parallelism;
    private final List<SearchWorkerImpl> workers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    final AtomicBoolean stopFlag = new AtomicBoolean(false);
    final AtomicLong totalNodes = new AtomicLong(0);

    private volatile long softTimeMs;
    private volatile long hardTimeMs;
    private volatile long searchStartMs;
    private CompletableFuture<SearchResult> searchFuture;

    public WorkerPoolImpl(int threads, SearchWorkerFactory f) {
        this.factory = f;
        this.parallelism = threads;
        resizePool();
    }

    public WorkerPoolImpl(SearchWorkerFactory f) {
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
            SearchWorkerImpl worker = (SearchWorkerImpl) factory.create(i == 0, this);
            workers.add(worker);
            Thread thread = new Thread(worker, "Helios-Worker-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
    }

    @Override
    public CompletableFuture<SearchResult> startSearch(long[] root, SearchSpec spec, PositionFactory pf, MoveGenerator mg, TranspositionTable tt, TimeManager tm, InfoHandler ih)
    {
        // Wait for the previous search to completely finish
        workers.get(0).waitWorkerFinished();

        // Setup for the new search
        this.stopFlag.set(false);
        this.totalNodes.set(0);
        deriveTimeLimits(spec, tm, root);
        this.searchStartMs = System.currentTimeMillis();

        this.searchFuture = new CompletableFuture<>();

        for (SearchWorkerImpl worker : workers) {
            worker.prepareForSearch(root, spec, pf, mg, tt, tm);
            worker.setInfoHandler(worker.isMainThread ? ih : null);
        }

        // Start the main worker, which will orchestrate the others
        workers.get(0).startWorkerSearch();

        return searchFuture;
    }

    // Called by the main worker to wake up helpers
    void startHelpers() {
        for (int i = 1; i < workers.size(); i++) {
            workers.get(i).startWorkerSearch();
        }
    }

    // Called by the main worker to wait for helpers to finish an iteration
    void waitForHelpersFinished() {
        for (int i = 1; i < workers.size(); i++) {
            workers.get(i).waitWorkerFinished();
        }
    }

    // Called by main worker when search is fully complete
    void finalizeSearch(SearchResult mainResult) {
        long allNodes = 0;
        for (SearchWorkerImpl w : workers) {
            allNodes += w.getNodes();
        }

        SearchResult finalResult = new SearchResult(
                mainResult.bestMove(), mainResult.ponderMove(), mainResult.pv(),
                mainResult.scoreCp(), mainResult.mateFound(), mainResult.depth(),
                allNodes, // Use aggregate node count
                mainResult.timeMs()
        );

        if (searchFuture != null && !searchFuture.isDone()) {
            searchFuture.complete(finalResult);
        }
    }

    boolean shouldStop(long searchStartMs, boolean mateFound) {
        if (mateFound) return true;
        // This is now only the HARD limit check. Soft limit is handled by the worker.
        if (hardTimeMs >= Long.MAX_VALUE / 2) return false; // Infinite time
        long elapsed = System.currentTimeMillis() - searchStartMs;
        return elapsed >= hardTimeMs;
    }

    void reportNodeCount(long nodes) {
        // This method can be used if more frequent updates are needed.
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

        for (SearchWorkerImpl worker : workers) {
            worker.terminate();
        }
        for (Thread thread : threads) {
            try {
                thread.join(100); // Wait briefly for clean exit
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        threads.clear();
    }

    public long getSoftMs() { return softTimeMs; }
    public long getSearchStartTime() { return searchStartMs; }

    // Kept for interface compatibility if other parts of the code use them.
    @Override public long getOptimumMs() { return softTimeMs; }
    @Override public long getMaximumMs() { return hardTimeMs; }

    private void deriveTimeLimits(SearchSpec spec, TimeManager tm, long[] board) {
        TimeAllocation ta = tm.calculate(spec, board);
        this.softTimeMs = ta.soft();
        this.hardTimeMs = ta.maximum();
    }
}