package main;

import core.contracts.*;
import core.impl.*;

/**
 * Wire everything together and run the UCI loop.
 * The order matters: we must create the UCI-options handler FIRST because
 * TimeManagerImpl needs it – but the handler itself needs the transposition
 * table only (not Search).  After Search is built we inject it back into
 * the options handler so that “setoption Threads …” can call search.setThreads().
 */
public final class Main {

    public static void main(String[] args) {

        System.out.println("Helios Chess Engine");

        /* ─── stateless singletons ─────────────────────────────────────── */
        PositionFactory pf = new PositionFactoryImpl();
        MoveGenerator   mg = new MoveGeneratorImpl();
        Evaluator       ev = new EvaluatorImpl();

        /* ─── TT + Options (Options only needs TT) ─────────────────────── */
        TranspositionTable tt   = new TranspositionTableImpl(64);
        UciOptions    opts = new UciOptionsImpl(null /*search yet*/, tt);

        /* ─── Time-manager that can read “Move Overhead” ───────────────── */
        TimeManager tm = new TimeManagerImpl(opts);

        /* ─── Lazy-SMP worker-pool ─────────────────────────────────────── */
        SearchWorkerFactory swf = (isMain, pool) ->
                new LazySmpSearchWorkerImpl(isMain, (LazySmpWorkerPoolImpl) pool);

        WorkerPool pool = new LazySmpWorkerPoolImpl(swf);
        pool.setParallelism(Runtime.getRuntime().availableProcessors());

        /* ─── Search façade ────────────────────────────────────────────── */
        Search search = new SearchImpl(pf, mg, ev, pool, tm);
        search.setTranspositionTable(tt);

        /* now the options handler can finally talk to Search */
        opts.attachSearch(search);                // one-liner we add below

        /* ─── UCI front-end ────────────────────────────────────────────── */
        UciHandler uci = new UciHandlerImpl(search, pf, opts);

        try   { uci.runLoop(); }          // blocks until “quit”
        finally { search.close(); }       // graceful shutdown
    }
}
