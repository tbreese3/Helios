package core.impl;

import core.contracts.*;
import core.records.SearchResult;
import core.records.SearchSpec;
import core.records.TimeAllocation;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Fixed-size pool for Lazy-SMP searchers.
 *
 * NOTE: workers are *constructed* once and re-used, but they are **not**
 *       started until a search begins – this avoids the NPE that occurred
 *       when run() was triggered with an un-initialised SearchSpec.
 */
public final class LazySmpWorkerPoolImpl implements WorkerPool {

    final AtomicBoolean stopFlag = new AtomicBoolean();
    final AtomicLong    nodes    = new AtomicLong();

    // ↺ no pre-started OS threads are required any more
    private final List<LazySmpSearchWorkerImpl> workers = new ArrayList<>();

    private final SearchWorkerFactory factory;
    private       int parallelism;

    /* timing */
    private volatile long optimumTimeMs = Long.MAX_VALUE;   // “optimum”
    private volatile long maximumTimeMs = Long.MAX_VALUE;   // hard limit

    public LazySmpWorkerPoolImpl(SearchWorkerFactory factory) {
        this(1, factory);
    }
    public LazySmpWorkerPoolImpl(int threads, SearchWorkerFactory f){
        this.factory = f;
        this.parallelism = threads;
        resizePool();
    }

    /* ── WorkerPool interface ───────────────────────────── */

    @Override public synchronized void setParallelism(int threads){
        if (threads == parallelism) return;
        close();
        parallelism = threads;
        resizePool();
    }

    /* ↺ build workers only – do NOT .start() anything here */
    /* ------------------------------------------------------------------ */
    /*  Replace the whole resizePool() helper with this implementation.   */
    /* ------------------------------------------------------------------ */
    private void resizePool() {
    /* Make absolutely sure we never reuse worker instances that might
       still be running from a previously queued search. */
        workers.clear();

        for (int i = 0; i < parallelism; i++) {
            workers.add(
                    (LazySmpSearchWorkerImpl) factory.create(i == 0, this)
            );
        }
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

        CountDownLatch ready = new CountDownLatch(workers.size());

        /* launch one short-lived OS thread per worker **now**, after prepareForSearch() */
        for (int i = 0; i < workers.size(); i++) {
            LazySmpSearchWorkerImpl w = workers.get(i);
            w.prepareForSearch(root, spec, pf, mg, ev, tt, tm);
            w.setInfoHandler((i == 0) ? ih : null);

            new Thread(() -> {                       // worker life-time = one search
                w.run();
                ready.countDown();
            }, "LSMP-" + i).start();
        }

        /* future completes once all workers finished and vote() chose the best line */
        return CompletableFuture.supplyAsync(() -> {
            try { ready.await(); } catch (InterruptedException ignored) {}
            return vote();
        });
    }

    /* same as before … ─────────────────────────────────── */
    void report(LazySmpSearchWorkerImpl w){ nodes.addAndGet(w.nodes); }

    /* choose the line with the highest “lazy-SMP vote” */
    private SearchResult vote() {

        /* discard workers that never finished an iteration */
        List<LazySmpSearchWorkerImpl> finished =
                workers.stream()
                        .filter(w -> w.getSearchResult().depth() > 0)
                        .toList();

        if (finished.isEmpty())         // nothing searched at all (very short time limit)
            return new SearchResult(0,0, List.of(), 0,false, 0,0,0);

        int minScore = finished.stream()
                .mapToInt(w -> w.getSearchResult().scoreCp())
                .min()
                .orElse(0);

        LazySmpSearchWorkerImpl best = null;
        long bestVote = Long.MIN_VALUE;

        for (LazySmpSearchWorkerImpl w : finished) {
            SearchResult sr = w.getSearchResult();
            long vote = (long) (sr.scoreCp() - minScore + 9) * sr.depth();

            /* higher vote wins; tie-break on raw score */
            if (best == null || vote > bestVote ||
                    (vote == bestVote && sr.scoreCp() > best.getSearchResult().scoreCp())) {
                best = w;
                bestVote = vote;
            }
        }
        return best.getSearchResult();
    }

    @Override public void stopSearch(){ stopFlag.set(true); }

    @Override public AtomicBoolean getStopFlag(){ return stopFlag; }

    @Override public long totalNodes(){ return nodes.get(); }

    @Override public synchronized void close(){
        stopFlag.set(true);
        workers.clear();                       // ↺ nothing else to shut down
    }

    public long getOptimumMs() { return optimumTimeMs; }
    public long getMaximumMs() { return maximumTimeMs; }

    private void deriveTimeLimits(SearchSpec spec,
                                  TimeManager tm,
                                  boolean whiteToMove) {

        TimeAllocation ta = tm.calculate(spec, whiteToMove);

        /* be paranoid – optimum must never exceed maximum                */
        this.optimumTimeMs = Math.min(ta.optimal(), ta.maximum());
        this.maximumTimeMs = ta.maximum();
    }
}
