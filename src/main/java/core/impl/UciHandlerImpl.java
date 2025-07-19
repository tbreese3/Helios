// C:\dev\Helios\src\main\java\core\impl\UciHandlerImpl.java
package core.impl;

import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class UciHandlerImpl implements UciHandler {
    public static final List<String> BENCH_FENS = List.of(
            // ... FEN strings remain the same ...
            "8/8/1p4p1/p1p2k1p/P2npP1P/4K1P1/1P6/3R4 w - - 6 54"
    );

    /* ── engine components ─────────────────────────────────────── */
    private final WorkerPool workerPool;
    private final PositionFactory positionFactory;
    private final MoveGenerator moveGenerator;
    private final Evaluator evaluator;
    private final TranspositionTable transpositionTable;
    private final TimeManager timeManager;
    private final UciOptions opts;

    /* ── mutable engine state (guarded by searchLock) ──────────── */
    private final Object searchLock = new Object();
    private long[] currentPos;
    private final List<Long> history = new ArrayList<>();
    private CompletableFuture<SearchResult> searchFuture;
    private int searchId = 0;

    /* ── construction ──────────────────────────────────────────── */
    public UciHandlerImpl(WorkerPool pool, PositionFactory pf, MoveGenerator mg,
                          Evaluator ev, TranspositionTable tt, TimeManager tm, UciOptions options) {
        this.workerPool = pool;
        this.positionFactory = pf;
        this.moveGenerator = mg;
        this.evaluator = ev;
        this.transpositionTable = tt;
        this.timeManager = tm;
        this.opts = options;

        this.currentPos = positionFactory.fromFen(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    @Override
    public void runLoop() {
        try (Scanner in = new Scanner(System.in)) {
            while (in.hasNextLine()) {
                String line = in.nextLine().trim();
                if (!line.isEmpty() && handle(line)) break;
            }
        }
    }

    private boolean handle(String cmd) {
        String[] t = cmd.split("\\s+");
        return switch (t[0]) {
            case "uci" -> { cmdUci(); yield false; }
            case "isready" -> { System.out.println("readyok"); yield false; }
            case "ucinewgame" -> { cmdNewGame(); yield false; }
            case "setoption" -> { opts.setOption(cmd); yield false; }
            case "position" -> { cmdPosition(t); yield false; }
            case "go" -> { cmdGo(t); yield false; }
            case "stop" -> { cmdStop(); yield false; }
            case "ponderhit" -> { /* not implemented */ yield false; }
            case "quit" -> { cmdStop(); yield true; }
            default -> {
                System.out.println("info string Unknown command: " + cmd);
                yield false;
            }
        };
    }

    private void cmdUci() {
        System.out.println("id name Helios");
        System.out.println("id author Your Name");
        opts.printOptions();
        System.out.println("uciok");
    }

    private void cmdNewGame() {
        synchronized (searchLock) {
            cancelRunningSearch();
            transpositionTable.clear();
            history.clear();
        }
    }

    private void cmdStop() {
        synchronized (searchLock) {
            cancelRunningSearch();
        }
    }

    private void cmdPosition(String[] t) {
        synchronized (searchLock) {
            cancelRunningSearch();

            int i = 1;
            if ("startpos".equals(t[i])) {
                currentPos = positionFactory.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
                i++;
            } else if ("fen".equals(t[i])) {
                StringBuilder fen = new StringBuilder();
                while (++i < t.length && !"moves".equals(t[i]))
                    fen.append(t[i]).append(' ');
                currentPos = positionFactory.fromFen(fen.toString().trim());
            } else {
                return;
            }

            history.clear();
            history.add(currentPos[PositionFactory.HASH]);

            if (i < t.length && "moves".equals(t[i])) {
                for (int k = i + 1; k < t.length; k++) {
                    int mv = UciMove.stringToMove(currentPos, t[k], moveGenerator);
                    if (mv != 0 && positionFactory.makeMoveInPlace(currentPos, mv, moveGenerator))
                        history.add(currentPos[PositionFactory.HASH]);
                }
                history.remove(history.size() - 1);
            }
        }
    }

    private void cmdGo(String[] t) {
        SearchSpec.Builder b = new SearchSpec.Builder();
        for (int i = 1; i < t.length; i++)
            switch (t[i]) {
                case "wtime" -> b.wTimeMs(Long.parseLong(t[++i]));
                case "btime" -> b.bTimeMs(Long.parseLong(t[++i]));
                case "winc" -> b.wIncMs(Long.parseLong(t[++i]));
                case "binc" -> b.bIncMs(Long.parseLong(t[++i]));
                case "movestogo" -> b.movesToGo(Integer.parseInt(t[++i]));
                case "depth" -> b.depth(Integer.parseInt(t[++i]));
                case "nodes" -> b.nodes(Long.parseLong(t[++i]));
                case "movetime" -> b.moveTimeMs(Long.parseLong(t[++i]));
                case "infinite" -> b.infinite(true);
                case "ponder" -> b.ponder(true);
            }

        final int myId;
        synchronized (searchLock) {
            cancelRunningSearch();
            myId = ++searchId;
            b.history(new ArrayList<>(history));
            transpositionTable.incrementAge();

            searchFuture = workerPool.startSearch(
                    currentPos,
                    b.build(),
                    positionFactory,
                    moveGenerator,
                    evaluator,
                    transpositionTable,
                    timeManager,
                    info -> {
                        if (myId == searchId) printInfo(info);
                    });
        }

        searchFuture.thenAccept(r -> {
            synchronized (searchLock) {
                if (myId == searchId) printResult(r);
            }
        }).exceptionally(ex -> {
            System.out.println("info string search error: " + ex);
            return null;
        });
    }

    private void cancelRunningSearch() {
        if (searchFuture != null && !searchFuture.isDone()) {
            workerPool.stopSearch();
            searchFuture.join();
        }
        searchFuture = null;
    }

    // ... Other private methods (printInfo, UciMove, etc.) remain unchanged ...
    private void printInfo(SearchInfo si) {
        StringBuilder sb = new StringBuilder("info");
        sb.append(" depth ").append(si.depth());
        if (si.selDepth() > 0) sb.append(" seldepth ").append(si.selDepth());
        sb.append(" score ").append(UciScore.format(si.scoreCp(), si.isMate()));
        sb.append(" nodes ").append(si.nodes());
        sb.append(" nps ").append(si.nps());
        sb.append(" time ").append(si.timeMs());
        if (si.hashFullPermil() >= 0) sb.append(" hashfull ").append(si.hashFullPermil());
        if (!si.pv().isEmpty()) {
            sb.append(" pv");
            si.pv().forEach(mv -> sb.append(' ').append(UciMove.moveToUci(mv)));
        }
        System.out.println(sb);
    }

    private void printResult(SearchResult r) {
        String best = UciMove.moveToUci(r.bestMove());
        String ponder = r.ponderMove() == 0 ? "" :
                " ponder " + UciMove.moveToUci(r.ponderMove());
        System.out.println("bestmove " + best + ponder);
    }

    private static final class UciMove {
        static String moveToUci(int m) {
            if (m == 0) return "0000";
            int from = (m >>> 6) & 63, to = m & 63;
            String res = sq(from) + sq(to);
            if (((m >>> 14) & 3) == 1) { // promotion
                res += "nbrq".charAt((m >>> 12) & 3);
            }
            return res;
        }

        static int stringToMove(long[] pos, String s, MoveGenerator mg) {
            int[] list = new int[256];
            boolean isWhite = PositionFactory.whiteToMove(pos[PositionFactory.META]);
            boolean inCheck = mg.kingAttacked(pos, isWhite);

            int n = inCheck ? mg.generateEvasions(pos, list, 0)
                    : mg.generateQuiets(pos, list, mg.generateCaptures(pos, list, 0));

            for (int i = 0; i < n; i++) {
                if (moveToUci(list[i]).equals(s)) return list[i];
            }
            return 0;
        }

        private static String sq(int s) {
            return "" + (char) ('a' + (s & 7)) + (1 + (s >>> 3));
        }
    }

    private static final class UciScore {
        static String format(int cp, boolean mate) {
            if (mate) {
                int mateIn = (cp > 0) ? (32000 - cp + 1) / 2 : -(32000 + cp) / 2;
                return "mate " + mateIn;
            }
            return "cp " + cp;
        }
    }
}