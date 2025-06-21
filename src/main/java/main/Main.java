package main;

import core.contracts.*;
import core.impl.*;

/**
 * Wire everything together and run the UCI loop.
 *
 * Added: if the first CLI argument is the literal string "bench"
 *        the engine runs its internal benchmark and exits – this
 *        is what the OpenBench client expects:
 *
 *        ./Helios-<sha> bench [ttMB] [threads] [depth]
 *
 *        - ttMB    (optional)  size of the transposition table in MB  (default 64)
 *        - threads (optional)  number of simultaneous benches         (default 1)
 *        - depth   (optional)  search depth                           (default 13)
 *
 * All other invocations behave exactly as before (interactive UCI).
 */
public final class Main {

    public static void main(String[] args) {

        /* ── 1) standalone BENCH mode ─────────────────────────── */
        if (args.length > 0 && "bench".equalsIgnoreCase(args[0])) {

            /* ── parse optional parameters ───────────────────── */
            int ttMb    = args.length > 1 ? Integer.parseInt(args[1]) : 64;
            int threads = 1;
            int depth   = 5;

            /* ── build minimal engine stack just for bench ───── */
            PositionFactory       pf   = new PositionFactoryImpl();
            MoveGenerator         mg   = new MoveGeneratorImpl();
            Evaluator             ev   = new EvaluatorImpl();
            TranspositionTable    tt   = new TranspositionTableImpl(ttMb);
            UciOptionsImpl        opts = new UciOptionsImpl(null, tt);

            TimeManager           tm   = new TimeManagerImpl(opts);
            SearchWorkerFactory   swf  = (isMain, pool) ->
                    new LazySmpSearchWorkerImpl(isMain, (LazySmpWorkerPoolImpl) pool);
            WorkerPool            pool = new LazySmpWorkerPoolImpl(swf);
            pool.setParallelism(threads);

            Search search = new SearchImpl(pf, mg, ev, pool, tm);
            search.setTranspositionTable(tt);
            opts.attachSearch(search);

            /* ── front-end solely to access runBench() ───────── */
            UciHandlerImpl uci = new UciHandlerImpl(search, pf, opts, mg);
            uci.runBench(ttMb, threads, depth);   // prints Nodes searched / nps / benchok
            search.close();
            return;                               // do not start the interactive loop
        }

        /* ── 2) regular interactive UCI mode ─────────────────── */
        System.out.println("Helios Chess Engine");

        /* ─── stateless singletons ───────────────────────────── */
        PositionFactory pf = new PositionFactoryImpl();
        MoveGenerator   mg = new MoveGeneratorImpl();
        Evaluator       ev = new EvaluatorImpl();

        /* ─── TT + Options (Options only needs TT) ───────────── */
        TranspositionTable tt   = new TranspositionTableImpl(64);
        UciOptionsImpl     opts = new UciOptionsImpl(null /* search yet */, tt);

        /* ─── Time-manager ───────────────────────────────────── */
        TimeManager tm = new TimeManagerImpl(opts);

        /* ─── Lazy-SMP worker-pool ───────────────────────────── */
        SearchWorkerFactory swf = (isMain, pool) ->
                new LazySmpSearchWorkerImpl(isMain, (LazySmpWorkerPoolImpl) pool);
        WorkerPool pool = new LazySmpWorkerPoolImpl(swf);
        pool.setParallelism(1);

        /* ─── Search façade ──────────────────────────────────── */
        Search search = new SearchImpl(pf, mg, ev, pool, tm);
        search.setTranspositionTable(tt);

        /* now the options handler can finally talk to Search */
        opts.attachSearch(search);

        /* ─── UCI front-end ─────────────────────────────────── */
        UciHandler uci = new UciHandlerImpl(search, pf, opts, mg);

        try   { uci.runLoop(); }      // blocks until "quit"
        finally { search.close(); }   // graceful shutdown
    }

    private static int getThreads(String[] args) {
        return args.length > 2 ? Integer.parseInt(args[2]) : 1;
    }
}
