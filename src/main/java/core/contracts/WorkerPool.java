// C:\dev\Helios\src\main\java\core\contracts\WorkerPool.java
package core.contracts;

import core.records.SearchResult;
import core.records.SearchSpec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public interface WorkerPool extends AutoCloseable {
    void setParallelism(int threads);
    CompletableFuture<SearchResult> startSearch(long[] root, SearchSpec spec, PositionFactory pf, MoveGenerator mg,
                                                Evaluator eval, TranspositionTable tt, TimeManager tm, InfoHandler ih);
    void stopSearch();
    AtomicBoolean getStopFlag();
    AtomicLong getNodesCounter();
    TimeManager getTimeManager();
    long totalNodes();
    @Override void close();
}