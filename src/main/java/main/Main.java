package main;

import core.contracts.*;
import core.impl.*;

/**
 * Main entry point for the Helios chess engine command-line interface.
 * Wires all components together and starts the UCI command loop.
 */
public final class Main {

    public static void main(String[] args) {
        System.out.println("Helios Chess Engine");

        /* ── core singletons ───────────────────────────────────── */
        PositionFactory    pf = new PositionFactoryImpl();
        MoveGenerator      mg = new MoveGeneratorImpl();
        Evaluator          ev = new EvaluatorImpl();
        TranspositionTable tt = new TranspositionTableImpl(64);
        TimeManager        tm = new TimeManagerImpl();

        /* ── worker pool: Lazy-SMP with vote combiner ──────────── */
        SearchWorkerFactory swf =
                (isMain, pool) -> new LazySmpSearchWorkerImpl(
                        isMain,
                        (LazySmpWorkerPoolImpl) pool);          // cast required by ctor

        WorkerPool pool = new LazySmpWorkerPoolImpl(swf);
        pool.setParallelism(Runtime.getRuntime().availableProcessors());

        /* ── search façade ─────────────────────────────────────── */
        Search search = new SearchImpl(pf, mg, ev, pool, tm);
        search.setTranspositionTable(tt);

        /* ── UCI front-end ─────────────────────────────────────── */
        UciOptions opts = new UciOptionsImpl(search, tt);
        UciHandler uci  = new UciHandlerImpl(search, pf, opts);

        try {
            uci.runLoop();                 // blocks until “quit”
        } finally {
            search.close();                // graceful shutdown
        }
    }
}
