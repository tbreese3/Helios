package core.contracts;

import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.concurrent.CompletableFuture;

public interface Search extends AutoCloseable {

    void setEvaluator(Evaluator evaluator);
    void setTranspositionTable(TranspositionTable tt);
    void setThreads(int workerCount);
    void setWorkerPool(WorkerPool pool);
    void setTimeManager(TimeManager timeManager); // Added this line

    SearchResult search(long[] bb, SearchSpec spec, InfoHandler ih);
    CompletableFuture<SearchResult> searchAsync(long[] bb, SearchSpec spec, InfoHandler ih);

    void stop();
    void ponderHit();
    @Override
    void close();
}