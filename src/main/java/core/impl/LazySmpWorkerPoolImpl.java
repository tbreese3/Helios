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
        deriveTimeLimits(spec, tm,
                PositionFactory.whiteToMove(root[PositionFactory.META]));

        stopFlag.set(false);

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

    /**
     * Chooses the best result from all workers.
     * This involves accumulating votes per-move and prioritizing mates.
     */
    private SearchResult vote() {
        // 1. Collect only the workers that actually finished a search depth
        List<LazySmpSearchWorkerImpl> finished = workers.stream()
                .filter(w -> w.getSearchResult().depth() > 0)
                .collect(Collectors.toList());

        if (finished.isEmpty()) {
            return new SearchResult(0, 0, List.of(), 0, false, 0, 0, 0);
        }

        // 2. The main voting logic starts here
        LazySmpSearchWorkerImpl bestWorker = finished.get(0);

        if (finished.size() > 1) {
            // Step A: Find minScore to normalize votes, identical to C++ reference
            int minScore = finished.stream()
                    .mapToInt(w -> w.getSearchResult().scoreCp())
                    .min().orElse(0);

            // Step B: Accumulate votes for each unique best move across all threads
            Map<Integer, Long> moveVotes = new HashMap<>();
            for (LazySmpSearchWorkerImpl worker : finished) {
                SearchResult sr = worker.getSearchResult();
                if (sr.bestMove() == 0) continue; // Skip workers that didn't find a move
                long voteValue = (long) (sr.scoreCp() - minScore + 9) * sr.depth();
                moveVotes.merge(sr.bestMove(), voteValue, Long::sum);
            }

            // Step C: Select the best worker using mate priority and accumulated votes
            for (int i = 1; i < finished.size(); i++) {
                LazySmpSearchWorkerImpl currentWorker = finished.get(i);
                SearchResult currentResult = currentWorker.getSearchResult();
                SearchResult bestResult = bestWorker.getSearchResult();

                if (currentResult.bestMove() == 0) continue;

                long bestVote = moveVotes.getOrDefault(bestResult.bestMove(), 0L);
                long currentVote = moveVotes.getOrDefault(currentResult.bestMove(), 0L);

                // C++ logic: if the current best has a mate, only a faster mate can beat it.
                if (bestResult.mateFound()) {
                    if (currentResult.mateFound() && currentResult.scoreCp() > bestResult.scoreCp()) {
                        bestWorker = currentWorker;
                    }
                }
                // If the current worker has a mate and the best doesn't, the current worker wins.
                else if (currentResult.mateFound()) {
                    bestWorker = currentWorker;
                }
                // Otherwise, neither has a mate, so we compare their accumulated move votes.
                else if (currentResult.scoreCp() > SCORE_TB_LOSS_IN_MAX_PLY && currentVote > bestVote) {
                    bestWorker = currentWorker;
                }
            }
        }

        // 3. Build the final result from the winning worker, using the total node count
        SearchResult winningResult = bestWorker.getSearchResult();
        long allNodes = totalNodes();

        return new SearchResult(
                winningResult.bestMove(),
                winningResult.ponderMove(),
                winningResult.pv(),
                winningResult.scoreCp(),
                winningResult.mateFound(),
                winningResult.depth(),
                allNodes, // Use the pool-wide total node count
                winningResult.timeMs()
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