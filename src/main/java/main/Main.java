// File: Main.java
package main;

import core.contracts.*;
import core.impl.*;

import java.io.InputStream;
import java.util.List;

/**
 * Wire everything together and run the UCI loop.
 * The threading model now uses persistent worker threads coordinated via
 * condition variables, matching the design of the Lizard reference engine.
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length > 0 && "bench".equalsIgnoreCase(args[0])) {
            int depth = (args.length > 3) ? Integer.parseInt(args[3]) : 4;
            runPerftBench(depth);
            return;
        }

        System.out.println("Helios Chess Engine");

        PositionFactory pf = new PositionFactoryImpl();
        MoveGenerator mg = new MoveGeneratorImpl();
        Evaluator ev = new EvaluatorImpl();
        TranspositionTable tt = new TranspositionTableImpl(64);

        // This factory now creates our new persistent worker threads
        SearchWorkerFactory swf = (isMain, pool) ->
                new LazySmpSearchWorkerImpl(isMain, (LazySmpWorkerPoolImpl) pool);

        // The new pool manages persistent threads
        WorkerPool pool = new LazySmpWorkerPoolImpl(1, swf);

        UciOptionsImpl opts = new UciOptionsImpl(null, tt);
        TimeManager tm = new TimeManagerImpl();

        Search search = new SearchImpl(pf, mg, ev, pool, tm);
        search.setTranspositionTable(tt);
        opts.attachSearch(search);

        UciHandler uci = new UciHandlerImpl(search, pf, opts, mg);

        try {
            uci.runLoop();
        } finally {
            search.close();
        }
    }

    // The perft bench logic remains unchanged as it's single-threaded.
    private static void runPerftBench(int depth) {
        PositionFactory pf = new PositionFactoryImpl();
        MoveGenerator mg = new MoveGeneratorImpl();
        List<String> FENS = core.impl.UciHandlerImpl.BENCH_FENS;

        long totalNodes = 0, totalTimeMs = 0;

        for (String fen : FENS) {
            long[] root = pf.fromFen(fen);
            long t0 = System.nanoTime();
            long nodes = perft(root, depth, 0, pf, mg);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            totalNodes += nodes;
            totalTimeMs += ms;
        }

        long totalNps = totalTimeMs > 0 ? (1000L * totalNodes) / totalTimeMs : 0;
        System.out.printf("Nodes searched: %d%n", totalNodes);
        System.out.printf("nps: %d%n", totalNps);
        System.out.println("benchok");
    }

    private static final int MAX_PLY = 64;
    private static final int LIST_CAP = 256;
    private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

    private static long perft(long[] bb, int depth, int ply, PositionFactory pf, MoveGenerator mg) {
        if (depth == 0) return 1;

        int[] list = MOVES[ply];
        boolean white = PositionFactory.whiteToMove(bb[PositionFactory.META]);
        int nMoves = mg.kingAttacked(bb, white)
                ? mg.generateEvasions(bb, list, 0)
                : mg.generateQuiets(bb, list, mg.generateCaptures(bb, list, 0));

        long nodes = 0;
        for (int i = 0; i < nMoves; i++) {
            if (!pf.makeMoveInPlace(bb, list[i], mg)) continue;
            nodes += perft(bb, depth - 1, ply + 1, pf, mg);
            pf.undoMoveInPlace(bb);
        }
        return nodes;
    }
}