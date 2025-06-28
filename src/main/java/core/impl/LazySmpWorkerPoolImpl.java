// File: LazySmpWorkerPoolImpl.java
package core.impl;

import core.contracts.*;
import core.records.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fixed-size pool for Lazy-SMP searchers – now with persistent threads */
public final class LazySmpWorkerPoolImpl implements WorkerPool {

    private final SearchWorkerFactory factory;
    private int parallelism;

    /** immutable workers reused search-after-search */
    private final List<LazySmpSearchWorkerImpl> workers = new ArrayList<>();

    /** threads live forever inside this executor */
    private ExecutorService executor;

    /* shared state */
    final AtomicBoolean stopFlag = new AtomicBoolean();
    final AtomicLong    nodes    = new AtomicLong();

    /* timing */
    private volatile long optimumTimeMs = Long.MAX_VALUE;
    private volatile long maximumTimeMs = Long.MAX_VALUE;

    public LazySmpWorkerPoolImpl(int threads, SearchWorkerFactory f) {
        this.factory     = f;
        this.parallelism = threads;
        resizePool();
    }
    public LazySmpWorkerPoolImpl(SearchWorkerFactory f) { this(1, f); }

    /* ── API ─────────────────────────────────────────────────── */

    @Override public synchronized void setParallelism(int threads) {
        if (threads == parallelism) return;
        close();
        parallelism = threads;
        resizePool();
    }

    /** builds fresh workers and a new fixed-thread pool */
    private void resizePool() {
        if (executor != null) executor.shutdownNow();

        workers.clear();
        for (int i = 0; i < parallelism; i++)
            workers.add((LazySmpSearchWorkerImpl) factory.create(i == 0, this));

        executor = Executors.newFixedThreadPool(
                parallelism,
                r -> new Thread(r, "LSMP-" + UUID.randomUUID()));
    }

    @Override
    public CompletableFuture<SearchResult> startSearch(
            long[] root, SearchSpec spec,
            PositionFactory pf, MoveGenerator mg, Evaluator ev,
            TranspositionTable tt, TimeManager tm, InfoHandler ih)
    {
        // *** MODIFIED to pass the full 'root' array to the time manager ***
        deriveTimeLimits(spec, tm, root);

        stopFlag.set(false);
        nodes.set(0);

        CountDownLatch finished = new CountDownLatch(workers.size());

        for (int i = 0; i < workers.size(); i++) {
            LazySmpSearchWorkerImpl w = workers.get(i);

            w.prepareForSearch(root, spec, pf, mg, ev, tt, tm);
            w.setInfoHandler((i == 0) ? ih : null);

            executor.submit(() -> {
                w.run();
                finished.countDown();
            });
        }

        return CompletableFuture.supplyAsync(() -> {
            try { finished.await(); } catch (InterruptedException ignored) {}
            return vote();
        });
    }

    /* called by workers each iteration */
    void report(LazySmpSearchWorkerImpl w) {
        nodes.addAndGet(w.getNodes());
    }

    /* enhanced tie-break identical to the C++ reference */
    private SearchResult vote() {
        List<LazySmpSearchWorkerImpl> finished = workers.stream()
                .filter(w -> w.getSearchResult().depth() > 0)
                .toList();

        if (finished.isEmpty())
            return new SearchResult(0, 0, List.of(), 0, false, 0, 0, 0);

        LazySmpSearchWorkerImpl best = finished.get(0);
        long bestVote = Long.MIN_VALUE;

        int minScore = finished.stream()
                .mapToInt(w -> w.getSearchResult().scoreCp())
                .min().orElse(0);

        for (LazySmpSearchWorkerImpl w : finished) {
            SearchResult sr = w.getSearchResult();
            long v = (long) (sr.scoreCp() - minScore + 9) * sr.depth();

            if (v > bestVote) {
                best = w;
                bestVote = v;
            }
        }

        SearchResult br = best.getSearchResult();
        long allNodes = nodes.get();

        return new SearchResult(
                br.bestMove(),
                br.ponderMove(),
                br.pv(),
                br.scoreCp(),
                br.mateFound(),
                br.depth(),
                allNodes,
                br.timeMs()
        );
    }

    /* ── life-cycle helpers ─────────────────────────────────── */

    @Override public void stopSearch() { stopFlag.set(true); }
    @Override public AtomicBoolean getStopFlag() { return stopFlag; }
    @Override public long totalNodes() { return nodes.get(); }

    @Override public synchronized void close() {
        stopFlag.set(true);
        if (executor != null) executor.shutdownNow();
        workers.clear();
    }

    public long getOptimumMs() { return optimumTimeMs; }
    public long getMaximumMs() { return maximumTimeMs; }

    /**
     * MODIFIED to pass the full board state to the time manager.
     */
    private void deriveTimeLimits(SearchSpec spec, TimeManager tm, long[] root) {
        TimeAllocation ta = tm.calculate(spec, root);
        optimumTimeMs = Math.min(ta.optimal(), ta.maximum());
        maximumTimeMs = ta.maximum();
    }
}