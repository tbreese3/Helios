// C:\dev\Helios\src\main\java\core\impl\LazySmpWorkerPoolImpl.java
package core.impl;

import core.contracts.*;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class LazySmpWorkerPoolImpl implements WorkerPool {

    private final SearchWorkerFactory factory;
    private int parallelism;
    private final List<LazySmpSearchWorkerImpl> workers = new ArrayList<>();
    private ExecutorService executor;
    final AtomicBoolean stopFlag = new AtomicBoolean();
    final AtomicLong nodes = new AtomicLong();
    private volatile TimeManager timeManager;

    public LazySmpWorkerPoolImpl(int threads, SearchWorkerFactory f) {
        this.factory = f;
        this.parallelism = threads;
        resizePool();
    }
    public LazySmpWorkerPoolImpl(SearchWorkerFactory f) { this(1, f); }

    @Override
    public synchronized void setParallelism(int threads) {
        if (threads == parallelism) return;
        close();
        parallelism = threads;
        resizePool();
    }

    private void resizePool() {
        if (executor != null) executor.shutdownNow();
        workers.clear();
        for (int i = 0; i < parallelism; i++)
            workers.add((LazySmpSearchWorkerImpl) factory.create(i == 0, this));
        executor = Executors.newFixedThreadPool(parallelism, r -> new Thread(r, "LSMP-" + UUID.randomUUID()));
    }

    @Override
    public CompletableFuture<SearchResult> startSearch(long[] root, SearchSpec spec, PositionFactory pf, MoveGenerator mg, Evaluator ev, TranspositionTable tt, TimeManager tm, InfoHandler ih) {
        this.timeManager = tm;
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

    private SearchResult vote() {
        LazySmpSearchWorkerImpl best = workers.stream()
                .max(Comparator.comparingInt(w -> w.getSearchResult().depth()))
                .orElse(workers.get(0));
        SearchResult br = best.getSearchResult();
        return new SearchResult(br.bestMove(), br.ponderMove(), br.pv(), br.scoreCp(), br.mateFound(), br.depth(), totalNodes(), br.timeMs());
    }

    @Override public void stopSearch() { stopFlag.set(true); }
    @Override public AtomicBoolean getStopFlag() { return stopFlag; }
    @Override public long totalNodes() { return nodes.get(); }
    @Override public AtomicLong getNodesCounter() { return nodes; }
    @Override public TimeManager getTimeManager() { return timeManager; }
    @Override public synchronized void close() {
        stopFlag.set(true);
        if (executor != null) executor.shutdownNow();
        workers.clear();
    }
}