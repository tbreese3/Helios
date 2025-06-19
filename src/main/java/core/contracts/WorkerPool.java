package core.contracts;

import core.records.SearchResult;
import core.records.SearchSpec;
import java.util.concurrent.atomic.AtomicBoolean;

public interface WorkerPool extends AutoCloseable {
    void startSearch(long[] bb, SearchSpec spec, PositionFactory pf, MoveGenerator mg, Evaluator eval, TranspositionTable tt, TimeManager tm);
    void stopSearch();
    void waitForSearchFinished();
    long getTotalNodes();
    void setParallelism(int numThreads);
    void shutdownNow();
    void setInfoHandler(InfoHandler handler);
    SearchResult getFinalResult();
    AtomicBoolean getStopFlag();
    void workerReady() throws InterruptedException;
    void awaitWork() throws InterruptedException;
    void workerFinished();
    void addNodes(long count);
}