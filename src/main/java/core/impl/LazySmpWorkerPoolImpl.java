// File: LazySmpWorkerPoolImpl.java
package core.impl;

import core.contracts.*;
import core.records.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread pool that manages a fixed set of persistent worker threads.
 * This design is based on the threading model of high-performance chess engines,
 * where threads are long-lived and coordinated using condition variables.
 */
public final class LazySmpWorkerPoolImpl implements WorkerPool {

    private final SearchWorkerFactory factory;
    private int parallelism;
    private final List<LazySmpSearchWorkerImpl> workers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    final AtomicBoolean stopFlag = new AtomicBoolean(false);
    final AtomicLong totalNodes = new AtomicLong(0);

    private volatile long optimumTimeMs;
    private volatile long maximumTimeMs;
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
        deriveTimeLimits(spec, tm, PositionFactory.whiteToMove(root[PositionFactory.META]));

        this.searchFuture = new CompletableFuture<>();

        for (LazySmpSearchWorkerImpl worker : workers) {
            worker.prepareForSearch(root, spec, pf, mg, ev, tt, tm);
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
    void finalizeSearch(SearchResult ignored) { // mainResult is no longer needed
        SearchResult finalResult = getBestResult(); // Find the best result from all threads

        if (searchFuture != null && !searchFuture.isDone()) {
            searchFuture.complete(finalResult);
        }
    }

    /**
     * Finds the best search result among all worker threads, similar to Lizard's GetBestThread.
     * The best result is determined by the highest score at the greatest completed depth.
     * Also aggregates the total node count.
     *
     * @return The final, aggregated SearchResult.
     */
    private SearchResult getBestResult() {
        LazySmpSearchWorkerImpl bestWorker = workers.get(0);
        long allNodes = 0;

        for (LazySmpSearchWorkerImpl worker : workers) {
            allNodes += worker.getNodes();
            if (worker == bestWorker) continue;

            SearchResult currentResult = worker.getSearchResult();
            SearchResult bestResult = bestWorker.getSearchResult();

            // A higher score is better. If scores are equal, deeper is better.
            if (currentResult.scoreCp() > bestResult.scoreCp() && currentResult.depth() >= bestResult.depth()) {
                bestWorker = worker;
            } else if (currentResult.scoreCp() == bestResult.scoreCp() && currentResult.depth() > bestResult.depth()) {
                bestWorker = worker;
            }
        }

        SearchResult finalBest = bestWorker.getSearchResult();

        // Return a new result with the aggregated node count but the PV from the best thread.
        return new SearchResult(
                finalBest.bestMove(),
                finalBest.ponderMove(),
                finalBest.pv(),
                finalBest.scoreCp(),
                finalBest.mateFound(),
                finalBest.depth(),
                allNodes, // Use aggregate node count
                finalBest.timeMs()
        );
    }

    boolean shouldStop(long searchStartMs, boolean mateFound) {
        if (mateFound) return true;
        long elapsed = System.currentTimeMillis() - searchStartMs;
        return elapsed >= maximumTimeMs || elapsed >= optimumTimeMs;
    }

    void reportNodeCount(long nodes) {
        // In this model, node counting is simpler. The main thread can sum them at the end.
        // This method can be used if more frequent updates are needed.
    }

    @Override public long totalNodes() { return totalNodes.get(); }
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
                thread.join(100); // Wait briefly for clean exit
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        threads.clear();
    }

    @Override public long getOptimumMs() { return optimumTimeMs; }
    @Override public long getMaximumMs() { return maximumTimeMs; }

    private void deriveTimeLimits(SearchSpec spec, TimeManager tm, boolean white) {
        TimeAllocation ta = tm.calculate(spec, white);
        optimumTimeMs = ta.optimal();
        maximumTimeMs = ta.maximum();
    }
}