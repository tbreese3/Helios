package core.impl;

import core.contracts.*;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkerPoolImpl implements WorkerPool {

    private final List<SearchWorker> workers = new ArrayList<>();
    private final SearchWorkerFactory workerFactory;

    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private final AtomicLong totalNodes = new AtomicLong(0);
    // This flag is protected by synchronizing on `this`
    private boolean isSearching = false;
    private CountDownLatch searchLatch;
    private InfoHandler infoHandler;

    public WorkerPoolImpl(int numThreads, SearchWorkerFactory workerFactory) {
        this.workerFactory = workerFactory;
        setParallelism(numThreads);
    }

    @Override
    public synchronized void setParallelism(int numThreads) {
        if (numThreads < 1) {
            throw new IllegalArgumentException("Number of threads must be at least 1.");
        }
        if (isSearching) {
            System.err.println("Warning: Cannot change thread count while a search is active.");
            return;
        }
        if (workers.size() == numThreads && !workers.isEmpty()) {
            return;
        }

        shutdownNow(); // Gracefully stop existing threads

        workers.clear();
        for (int i = 0; i < numThreads; i++) {
            boolean isMain = (i == 0);
            SearchWorker worker = workerFactory.create(isMain, this);
            workers.add(worker);
        }
    }

    @Override
    public synchronized void startSearch(long[] bb, SearchSpec spec, PositionFactory pf, MoveGenerator mg, Evaluator eval, TranspositionTable tt, TimeManager timeManager) {
        this.stopFlag.set(false);
        this.totalNodes.set(0);
        this.searchLatch = new CountDownLatch(workers.size());

        for (SearchWorker worker : workers) {
            worker.prepareForSearch(bb, spec, pf, mg, eval, tt, timeManager);
            if (infoHandler != null) {
                worker.setInfoHandler(infoHandler);
            }
        }

        this.isSearching = true;
        this.notifyAll();
    }

    @Override
    public void stopSearch() {
        stopFlag.set(true);
    }

    @Override
    public void waitForSearchFinished() {
        try {
            if (searchLatch != null) {
                searchLatch.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void shutdownNow() {
        if (workers.isEmpty()) return;
        stopFlag.set(true);
        isSearching = true; // Unblock any waiting threads to allow them to terminate
        this.notifyAll();
        for (SearchWorker worker : workers) {
            worker.terminate();
        }
        for (SearchWorker worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        isSearching = false;
    }

    @Override
    public synchronized void setInfoHandler(InfoHandler handler) {
        this.infoHandler = handler;
        if (!workers.isEmpty()) {
            workers.get(0).setInfoHandler(handler);
        }
    }

    @Override
    public long getTotalNodes() {
        return totalNodes.get();
    }

    @Override
    public synchronized SearchResult getFinalResult() {
        if (workers.isEmpty()) {
            return new SearchResult(0, 0, Collections.emptyList(), 0, false, 0, 0, 0);
        }
        return workers.get(0).getSearchResult();
    }

    @Override
    public void close() {
        shutdownNow();
    }

    @Override
    public AtomicBoolean getStopFlag() {
        return stopFlag;
    }

    @Override
    public void workerReady() throws InterruptedException {
        synchronized (this) {
            while (!isSearching) {
                this.wait();
            }
        }
    }

    @Override
    public void awaitWork() throws InterruptedException {
        workerReady();
    }

    @Override
    public void workerFinished() {
        if (searchLatch != null) {
            searchLatch.countDown();
        }
        if (searchLatch != null && searchLatch.getCount() == 0) {
            synchronized (this) {
                isSearching = false;
            }
        }
    }

    @Override
    public void addNodes(long count) {
        if (count > 0) {
            totalNodes.addAndGet(count);
        }
    }
}