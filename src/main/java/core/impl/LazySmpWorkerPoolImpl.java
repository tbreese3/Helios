package core.impl;

import core.contracts.*;
import core.records.SearchResult;
import core.records.SearchSpec;
import core.records.TimeAllocation;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static core.constants.CoreConstants.SCORE_TB_LOSS_IN_MAX_PLY;

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

    /* timing */
    private volatile long optimumTimeMs = Long.MAX_VALUE;
    private volatile long maximumTimeMs = Long.MAX_VALUE;

    public LazySmpWorkerPoolImpl(int threads, SearchWorkerFactory f) {
        this.factory     = f;
        this.parallelism = threads;
        resizePool();                                // builds workers & executor
    }
    public LazySmpWorkerPoolImpl(SearchWorkerFactory f) { this(1, f); }

    /* ── API ─────────────────────────────────────────────────── */

    @Override public synchronized void setParallelism(int threads) {
        if (threads == parallelism) return;
        close();                                     // drop executor & workers
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

        CountDownLatch finished = new CountDownLatch(workers.size());

        for (int i = 0; i < workers.size(); i++) {
            LazySmpSearchWorkerImpl w = workers.get(i);

            w.prepareForSearch(root, spec, pf, mg, ev, tt, tm);
            w.setInfoHandler((i == 0) ? ih : null);

            executor.submit(() -> {                  // threads are persistent
                w.run();                             // but search object is fresh
                finished.countDown();
            });
        }

        /* future completes once all workers finished and vote() chose best line */
        return CompletableFuture.supplyAsync(() -> {
            try { finished.await(); } catch (InterruptedException ignored) {}
            return vote();
        });
    }

    /** choose best PV, but return the aggregate node count of *all* workers */
    private SearchResult vote() {

        // ── 1. collect only the workers that actually finished a depth ─────────
        List<LazySmpSearchWorkerImpl> finished = workers.stream()
                .filter(w -> w.getSearchResult().depth() > 0)
                .collect(Collectors.toList());

        if (finished.isEmpty())
            return new SearchResult(0, 0, List.of(), 0, false, 0, 0, 0);

        // ── 2. Stockfish-style tie-break to pick the PV we’ll return ───────────
        LazySmpSearchWorkerImpl best = finished.get(0);

        if (finished.size() > 1) {
            Map<Integer, Long> moveVotes = new HashMap<>();
            int minScore = finished.stream()
                    .mapToInt(w -> w.getSearchResult().scoreCp())
                    .min().orElse(0);

            for (LazySmpSearchWorkerImpl w : finished) {
                SearchResult sr = w.getSearchResult();
                if (sr.bestMove() == 0) continue;
                long voteValue = (long) (sr.scoreCp() - minScore + 9) * sr.depth();
                moveVotes.merge(sr.bestMove(), voteValue, Long::sum);
            }

            for (int i = 1; i < finished.size(); i++) {
                LazySmpSearchWorkerImpl currentWorker = finished.get(i);
                SearchResult currentResult = currentWorker.getSearchResult();
                SearchResult bestResult = best.getSearchResult();

                if (currentResult.bestMove() == 0) continue;

                boolean bestIsMate = bestResult.mateFound();
                boolean currentIsMate = currentResult.mateFound();

                if (bestIsMate) {
                    if (currentIsMate && currentResult.scoreCp() > bestResult.scoreCp()) {
                        best = currentWorker;
                    }
                } else if (currentIsMate) {
                    best = currentWorker;
                } else {
                    long bestMoveVote = moveVotes.getOrDefault(bestResult.bestMove(), 0L);
                    long currentMoveVote = moveVotes.getOrDefault(currentResult.bestMove(), 0L);

                    if (currentResult.scoreCp() > SCORE_TB_LOSS_IN_MAX_PLY && currentMoveVote > bestMoveVote) {
                        best = currentWorker;
                    }
                }
            }
        }

        SearchResult br = best.getSearchResult();

        // ── 3. use the *aggregate* node counter collected via totalNodes() ────
        long allNodes = totalNodes();

        return new SearchResult(
                br.bestMove(),
                br.ponderMove(),
                br.pv(),
                br.scoreCp(),
                br.mateFound(),
                br.depth(),
                allNodes,      // ← pool-wide total, not just the winner’s
                br.timeMs()
        );
    }

    /* ── life-cycle helpers ─────────────────────────────────── */

    @Override public void stopSearch() { stopFlag.set(true); }

    @Override public AtomicBoolean getStopFlag() { return stopFlag; }

    @Override public long totalNodes() {
        long result = 0;
        for (SearchWorker worker : workers) {
            result += worker.getNodes();
        }
        return result;
    }

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