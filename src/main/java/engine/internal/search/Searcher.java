package engine.internal.search;

import engine.*;

/**
 * Pluggable search algorithm (iterative αβ, MCTS, …).
 */
public interface Searcher {

    /** Fire-and-forget; callbacks arrive on an implementation-controlled thread. */
    void start(SearchRequest req, SearchListener lsn);

    /** Soft stop (“stop”).  The {@code SearchListener} must still emit the final best-move. */
    void stop();

    /** The GUI played the predicted move (“ponderhit”). */
    void ponderHit();
}
