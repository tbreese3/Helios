package core.contracts;

import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.concurrent.CompletableFuture;

/**
 * Game-tree search driver – roughly the “UCI engine core”.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>Support <b>all</b> UCI search modes (depth, nodes, move-time, ponder,
 *       multi-PV, mate-in-N, searchmoves, infinite analysis).</li>
 *   <li>Allow callers (GUIs, test harnesses) to choose <em>synchronous</em>
 *       or <em>asynchronous</em> searches.</li>
 *   <li>Be engine-agnostic: evaluator, TT and thread pool are pluggable.</li>
 *   <li>Make cancellation and “ponder hit” <em>lock-free</em>:
 *       one atomic flag polled by <u>all</u> worker threads every few
 *       thousand nodes is enough.</li>
 * </ul>
 *
 * <p><b>Threading contract</b> – Implementations MUST:</p>
 * <ol>
 *   <li>Hold no long-running locks inside search loops.</li>
 *   <li>Poll an {@code AtomicBoolean stopped} frequently and exit ASAP when it
 *       flips (<code>stop()</code>).</li>
 *   <li>Accept that <code>stop()</code> can arrive from <em>any</em> thread,
 *       including other workers or the GUI thread.</li>
 *   <li>Handle “work-stealing recursion”: tasks may submit new tasks while
 *       already running inside a {@link TaskPool} worker.</li>
 * </ol>
 */
public interface Search extends AutoCloseable {

    /* ───────────────────── runtime configuration ───────────────────── */

    /**
     * Replace or hot-reload the static evaluator (e.g. switch NNUE nets).
     * Call between games; thread-safe implementations may allow mid-game swaps.
     */
    void setEvaluator(Evaluator evaluator);

    /**
     * Plug in a shared transposition table.  The searcher never owns its life-
     * cycle; the host application is responsible for sizing and clearing it.
     */
    void setTranspositionTable(TranspositionTable tt);

    /** Hint how many helper threads the engine may spawn internally. */
    void setThreads(int workerCount);

    /**
     * Provide the task-execution back-end.  It <em>must</em> support
     * re-entrant submissions (split-points) and should be work-stealing
     * (see {@link java.util.concurrent.ForkJoinPool}).
     */
    void setTaskPool(TaskPool pool);

    /* ───────────────────── single-search entry-points ───────────────── */

    /**
     * Blocking search: returns only when a result is ready or
     * {@link #stop()} cancels the search.
     *
     * @param bb   immutable packed position (side-to-move encoded in META)
     * @param spec depth / time / node limits & UCI flags
     * @param ih   callback for incremental “info” updates (may be {@code null})
     * @return     final principal variation and score
     */
    SearchResult search(long[] bb, SearchSpec spec, InfoHandler ih);

    /**
     * Non-blocking variant preferred by GUIs.  The computation runs on the
     * engine’s {@link TaskPool}.  The returned {@link CompletableFuture}
     * completes with the same {@link SearchResult} produced by
     * {@link #search(long[], SearchSpec, InfoHandler)}, or is
     * <em>cancelled</em> when {@link #stop()} is invoked first.
     *
     * @see CompletableFuture#cancel(boolean)
     */
    CompletableFuture<SearchResult> searchAsync(long[] bb,
                                                SearchSpec spec,
                                                InfoHandler ih);

    /* ────────────────────────── control hooks ───────────────────────── */

    /**
     * Request the current search to terminate <u>as soon as possible</u>.
     * Implementations must:
     * <ul>
     *   <li>flip a single {@code AtomicBoolean stopped}</li>
     *   <li>ensure every worker thread checks that flag frequently
     *       (&lt;= 2048 nodes recommended)</li>
     *   <li>return control without blocking on worker shutdown</li>
     * </ul>
     */
    void stop();

    /**
     * GUI call that follows a <em>ponder</em> search once the opponent has
     * indeed played the predicted move.  Engines should:
     * <ol>
     *   <li>promote the current ponder PV to the principal PV</li>
     *   <li>continue searching without re-initialising the whole root</li>
     * </ol>
     * Ignored if the active {@link SearchSpec#ponder()} flag was {@code false}.
     */
    void ponderHit();

    /* ───────────────────── misc. utility functions ──────────────────── */

    /** Naïve perft for validating move-generation correctness. */
    long perft(long[] bb, int depth);

    /* ──────────────────── resource clean-up hooks ───────────────────── */

    /**
     * Terminate helper threads, flush caches, free JNI handles, etc.
     * Idempotent – safe to call more than once.
     */
    @Override void close();
}
