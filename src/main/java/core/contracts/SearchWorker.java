// C:\dev\Helios\src\main\java\core\contracts\SearchWorker.java
package core.contracts;

import core.records.SearchResult;
import core.records.SearchSpec;

public interface SearchWorker {
    void prepareForSearch(long[] bb, SearchSpec spec, PositionFactory pf, MoveGenerator mg, Evaluator eval, TranspositionTable tt, TimeManager tm);
    void terminate();
    SearchResult getSearchResult();
    void setInfoHandler(InfoHandler handler);
    void join() throws InterruptedException;
    long getNodes();
}