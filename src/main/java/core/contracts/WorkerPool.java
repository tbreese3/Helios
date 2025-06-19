package core.contracts;

import core.records.SearchResult;
import core.records.SearchSpec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-pool façade identical to Obsidian’s design:
 * – fixed-size pool created once
 * – work handed out via a blocking queue
 * – helpers are completely fire-and-forget
 * – stop() flips one shared AtomicBoolean
 */
public interface WorkerPool extends AutoCloseable {

    /* one-off configuration */
    void setParallelism(int threads);

    /* search life-cycle */
    CompletableFuture<SearchResult> startSearch(long[] root,
                                                SearchSpec spec,
                                                PositionFactory pf,
                                                MoveGenerator mg,
                                                Evaluator      eval,
                                                TranspositionTable tt,
                                                TimeManager    tm,
                                                InfoHandler    ih);

    void stopSearch();
    AtomicBoolean getStopFlag();
    long totalNodes();

    /* infra */
    @Override void close();

    long getOptimumMs();
    long getMaximumMs();
}
