package core.impl;

import core.constants.CoreConstants;
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
        this.factory      = f;
        this.parallelism  = threads;
        resizePool();                         // builds workers & executor
    }
    public LazySmpWorkerPoolImpl(SearchWorkerFactory f) { this(1, f); }

    /* ── API ─────────────────────────────────────────────────── */

    @Override public synchronized void setParallelism(int threads) {
        if (threads == parallelism) return;
        close();                              // drop executor & workers
        parallelism = threads;
        resizePool();
    }

    /** builds fresh workers and a new fixed-thread pool */
    private void resizePool() {
        // dispose previous executor if any
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
        deriveTimeLimits(spec, tm,
                PositionFactory.whiteToMove(root[PositionFactory.META]));

        stopFlag.set(false);
        nodes.set(0);

        CountDownLatch finished = new CountDownLatch(workers.size());

        for (int i = 0; i < workers.size(); i++) {
            LazySmpSearchWorkerImpl w = workers.get(i);

            w.prepareForSearch(root, spec, pf, mg, ev, tt, tm);
            w.setInfoHandler((i == 0) ? ih : null);

            executor.submit(() -> {            // threads are persistent
                w.run();                       // but search object is fresh
                finished.countDown();
            });
        }

        /* future completes once all workers finished and vote() chose best line */
        return CompletableFuture.supplyAsync(() -> {
            try { finished.await(); } catch (InterruptedException ignored) {}
            return vote();
        });
    }

    /* called by workers each iteration */
    void report(LazySmpSearchWorkerImpl w) { nodes.addAndGet(w.nodes); }

    /* enhanced tie-break identical to the C++ reference */
    private SearchResult vote() {

        List<LazySmpSearchWorkerImpl> finished = workers.stream()
                .filter(w -> w.getSearchResult().depth() > 0)
                .toList();

        if (finished.isEmpty())
            return new SearchResult(0,0,List.of(),0,false,0,0,0);

        int minScore = finished.stream()
                .mapToInt(w -> w.getSearchResult().scoreCp())
                .min().orElse(0);

        Map<Integer, Long> vote = new HashMap<>();
        for (LazySmpSearchWorkerImpl w : finished) {
            SearchResult sr = w.getSearchResult();
            long v = (long) (sr.scoreCp() - minScore + 9) * sr.depth();
            vote.merge(w.hashCode(), v, Long::sum);
        }

        LazySmpSearchWorkerImpl best = null;
        long bestVote = Long.MIN_VALUE;

        for (LazySmpSearchWorkerImpl w : finished) {
            SearchResult sr = w.getSearchResult();
            long v = vote.get(w.hashCode());

            if (best == null) { best = w; bestVote = v; continue; }

            int bestScore = best.getSearchResult().scoreCp();
            int currScore = sr.scoreCp();

            if (Math.abs(bestScore) >= CoreConstants.SCORE_TB_WIN_IN_MAX_PLY) {
                if (currScore > bestScore) { best = w; bestVote = v; }
            }
            else if (currScore >= CoreConstants.SCORE_TB_WIN_IN_MAX_PLY) {
                best = w; bestVote = v;
            }
            else if (currScore > CoreConstants.SCORE_TB_LOSS_IN_MAX_PLY && v > bestVote) {
                best = w; bestVote = v;
            }
        }
        return best.getSearchResult();
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

    private void deriveTimeLimits(SearchSpec spec, TimeManager tm, boolean white) {
        TimeAllocation ta = tm.calculate(spec, white);
        optimumTimeMs = Math.min(ta.optimal(), ta.maximum());
        maximumTimeMs = ta.maximum();
    }
}
