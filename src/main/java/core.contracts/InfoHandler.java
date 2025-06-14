package core.contracts;

import core.records.SearchInfo;

/**
 * Callback for incremental search updates (“info” lines in UCI terms).
 *
 * <p>The engine should call {@link #onInfo} every time it has new data to
 * report: a deeper ply reached, a better PV found, etc.  GUI front-ends or
 * test harnesses can then render or record the progress without parsing
 * textual UCI output.</p>
 */
@FunctionalInterface
public interface InfoHandler {

    /**
     * Invoked by the search whenever it generates a new {@link SearchInfo}
     * snapshot.
     *
     * @param info immutable data object describing the current search state
     */
    void onInfo(SearchInfo info);
}
