// File: Main.java
package main;

import core.contracts.*;
import core.impl.*;

import java.util.List;

/**
 * Wire everything together and run the UCI loop.
 * <p>
 * The first CLI argument can be the literal word {@code bench}. In that case we
 * run a <b>pure PERFT benchmark</b> (no search, no TT, no evaluator) over the
 * internal FEN suite and exit – exactly what the OpenBench client expects.
 * <pre>
 *   ./Helios-<sha> bench [ttMB] [threads] [depth]
 * </pre>
 * The "ttMB" and "threads" parameters are now ignored but kept for CLI
 * compatibility; only the optional <i>depth</i> value is used.
 */
public final class Main {

    /* ------------------------------------------------------------------
     *  Entry‑point
     * ---------------------------------------------------------------- */
    public static void main(String[] args) {

        /* ── 1) standalone BENCH mode ─────────────────────────── */
        if (args.length > 0 && "bench".equalsIgnoreCase(args[0])) {

            int depth = 4; // default depth 4
            runPerftBench(depth);
            return; // do not enter the interactive loop
        }

        System.out.println("Helios Chess Engine");

        // --- Stateless services & singletons ---
        PositionFactory pf = new PositionFactoryImpl();
        MoveGenerator mg = new MoveGeneratorImpl();
        Evaluator ev = new EvaluatorImpl();
        TranspositionTable tt = new TranspositionTableImpl(64);

        // --- Options Handler (depends on TT)---
        UciOptionsImpl opts = new UciOptionsImpl(null, tt);

        // --- DI-compatible Factory Injection ---
        // 1. Create the TimeManagerFactory. It depends on UciOptions.
        TimeManagerFactory tmf = new TimeManagerFactoryImpl(opts);

        // 2. Create the WorkerPool
        SearchWorkerFactory swf = (isMain, pool) -> new LazySmpSearchWorkerImpl(isMain, (LazySmpWorkerPoolImpl) pool);
        WorkerPool pool = new LazySmpWorkerPoolImpl(swf);
        pool.setParallelism(1);

        // 3. Create the Search service, injecting the factory
        Search search = new SearchImpl(pf, mg, ev, pool, tmf);
        search.setTranspositionTable(tt);

        // 4. Finalize wiring by giving the options handler a reference to the search
        opts.attachSearch(search);

        // 5. Create and run the UCI front-end
        UciHandler uci = new UciHandlerImpl(search, pf, opts, mg);
        try {
            uci.runLoop();
        } finally {
            search.close();
        }
    }

    private static void runPerftBench(int depth) {
        PositionFactory pf = new PositionFactoryImpl();
        MoveGenerator   mg = new MoveGeneratorImpl();
        List<String> FENS  = core.impl.UciHandlerImpl.BENCH_FENS; // reuse existing suite

        long totalNodes = 0, totalTimeMs = 0;

        for (int i = 0; i < FENS.size(); i++) {
            long[] root = pf.fromFen(FENS.get(i));

            long t0 = System.nanoTime();
            long nodes = perft(root, depth, 0, pf, mg);
            long ms    = (System.nanoTime() - t0) / 1_000_000;

            long nps = ms > 0 ? (1000L * nodes) / ms : 0;

            totalNodes += nodes;
            totalTimeMs += ms;
        }

        long totalNps = totalTimeMs > 0 ? (1000L * totalNodes) / totalTimeMs : 0;
        System.out.printf("Nodes searched: %d%n", totalNodes);
        System.out.printf("nps: %d%n",             totalNps);
        System.out.println("benchok");
    }

    /* --------------------------------------------------------- */
    private static final int MAX_PLY  = 64;
    private static final int LIST_CAP = 256;
    private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

    private static long perft(long[] bb, int depth, int ply,
                              PositionFactory pf, MoveGenerator mg) {
        if (depth == 0) return 1;

        int[] list = MOVES[ply];
        boolean white = PositionFactory.whiteToMove(bb[PositionFactory.META]);
        int nMoves = (mg.kingAttacked(bb, white))
                ? mg.generateEvasions(bb, list, 0)
                : mg.generateQuiets(bb, list,
                mg.generateCaptures(bb, list, 0));

        long nodes = 0;
        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue; // illegal
            nodes += perft(bb, depth - 1, ply + 1, pf, mg);
            pf.undoMoveInPlace(bb);
        }
        return nodes;
    }
}
