package core.impl;

import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;
import main.Main;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Universal-Chess-Interface front-end.
 *
 * <p>All interaction with {@link Search} happens behind a single
 * monitor ({@code searchLock}) so that:</p>
 * <ul>
 *   <li>only <em>one</em> search can run at a time</li>
 *   <li>“info …” and “bestmove …” from an <em>old</em> search are
 *       never printed after a new position / search has started</li>
 * </ul>
 */
public final class UciHandlerImpl implements UciHandler {
    public static final List<String> BENCH_FENS = List.of(
            "r3k2r/2pb1ppp/2pp1q2/p7/1nP1B3/1P2P3/P2N1PPP/R2QK2R w KQkq a6 0 14",
            "4rrk1/2p1b1p1/p1p3q1/4p3/2P2n1p/1P1NR2P/PB3PP1/3R1QK1 b - - 2 24",
            "r3qbrk/6p1/2b2pPp/p3pP1Q/PpPpP2P/3P1B2/2PB3K/R5R1 w - - 16 42",
            "6k1/1R3p2/6p1/2Bp3p/3P2q1/P7/1P2rQ1K/5R2 b - - 4 44",
            "8/8/1p2k1p1/3p3p/1p1P1P1P/1P2PK2/8/8 w - - 3 54",
            "7r/2p3k1/1p1p1qp1/1P1Bp3/p1P2r1P/P7/4R3/Q4RK1 w - - 0 36",
            "r1bq1rk1/pp2b1pp/n1pp1n2/3P1p2/2P1p3/2N1P2N/PP2BPPP/R1BQ1RK1 b - - 2 10",
            "3r3k/2r4p/1p1b3q/p4P2/P2Pp3/1B2P3/3BQ1RP/6K1 w - - 3 87",
            "2r4r/1p4k1/1Pnp4/3Qb1pq/8/4BpPp/5P2/2RR1BK1 w - - 0 42",
            "4q1bk/6b1/7p/p1p4p/PNPpP2P/KN4P1/3Q4/4R3 b - - 0 37",
            "2q3r1/1r2pk2/pp3pp1/2pP3p/P1Pb1BbP/1P4Q1/R3NPP1/4R1K1 w - - 2 34",
            "1r2r2k/1b4q1/pp5p/2pPp1p1/P3Pn2/1P1B1Q1P/2R3P1/4BR1K b - - 1 37",
            "r3kbbr/pp1n1p1P/3ppnp1/q5N1/1P1pP3/P1N1B3/2P1QP2/R3KB1R b KQkq b3 0 17",
            "8/6pk/2b1Rp2/3r4/1R1B2PP/P5K1/8/2r5 b - - 16 42",
            "1r4k1/4ppb1/2n1b1qp/pB4p1/1n1BP1P1/7P/2PNQPK1/3RN3 w - - 8 29",
            "8/p2B4/PkP5/4p1pK/4Pb1p/5P2/8/8 w - - 29 68",
            "3r4/ppq1ppkp/4bnp1/2pN4/2P1P3/1P4P1/PQ3PBP/R4K2 b - - 2 20",
            "5rr1/4n2k/4q2P/P1P2n2/3B1p2/4pP2/2N1P3/1RR1K2Q w - - 1 49",
            "1r5k/2pq2p1/3p3p/p1pP4/4QP2/PP1R3P/6PK/8 w - - 1 51",
            "q5k1/5ppp/1r3bn1/1B6/P1N2P2/BQ2P1P1/5K1P/8 b - - 2 34",
            "r1b2k1r/5n2/p4q2/1ppn1Pp1/3pp1p1/NP2P3/P1PPBK2/1RQN2R1 w - - 0 22",
            "r1bqk2r/pppp1ppp/5n2/4b3/4P3/P1N5/1PP2PPP/R1BQKB1R w KQkq - 0 5",
            "r1bqr1k1/pp1p1ppp/2p5/8/3N1Q2/P2BB3/1PP2PPP/R3K2n b Q - 1 12",
            "r1bq2k1/p4r1p/1pp2pp1/3p4/1P1B3Q/P2B1N2/2P3PP/4R1K1 b - - 2 19",
            "r4qk1/6r1/1p4p1/2ppBbN1/1p5Q/P7/2P3PP/5RK1 w - - 2 25",
            "r7/6k1/1p6/2pp1p2/7Q/8/p1P2K1P/8 w - - 0 32",
            "r3k2r/ppp1pp1p/2nqb1pn/3p4/4P3/2PP4/PP1NBPPP/R2QK1NR w KQkq - 1 5",
            "3r1rk1/1pp1pn1p/p1n1q1p1/3p4/Q3P3/2P5/PP1NBPPP/4RRK1 w - - 0 12",
            "5rk1/1pp1pn1p/p3Brp1/8/1n6/5N2/PP3PPP/2R2RK1 w - - 2 20",
            "8/1p2pk1p/p1p1r1p1/3n4/8/5R2/PP3PPP/4R1K1 b - - 3 27",
            "8/4pk2/1p1r2p1/p1p4p/Pn5P/3R4/1P3PP1/4RK2 w - - 1 33",
            "8/5k2/1pnrp1p1/p1p4p/P6P/4R1PK/1P3P2/4R3 b - - 1 38",
            "8/8/1p1kp1p1/p1pr1n1p/P6P/1R4P1/1P3PK1/1R6 b - - 15 45",
            "8/8/1p1k2p1/p1prp2p/P2n3P/6P1/1P1R1PK1/4R3 b - - 5 49",
            "8/8/1p4p1/p1p2k1p/P2npP1P/4K1P1/1P6/3R4 w - - 6 54",
            "8/8/1p4p1/p1p2k1p/P2n1P1P/4K1P1/1P6/6R1 b - - 6 59",
            "8/5k2/1p4p1/p1pK3p/P2n1P1P/6P1/1P6/4R3 b - - 14 63",
            "8/1R6/1p1K1kp1/p6p/P1p2P1P/6P1/1Pn5/8 w - - 0 67",
            "1rb1rn1k/p3q1bp/2p3p1/2p1p3/2P1P2N/PP1RQNP1/1B3P2/4R1K1 b - - 4 23",
            "4rrk1/pp1n1pp1/q5p1/P1pP4/2n3P1/7P/1P3PB1/R1BQ1RK1 w - - 3 22",
            "r2qr1k1/pb1nbppp/1pn1p3/2ppP3/3P4/2PB1NN1/PP3PPP/R1BQR1K1 w - - 4 12",
            "2r2k2/8/4P1R1/1p6/8/P4K1N/7b/2B5 b - - 0 55",
            "6k1/5pp1/8/2bKP2P/2P5/p4PNb/B7/8 b - - 1 44",
            "2rqr1k1/1p3p1p/p2p2p1/P1nPb3/2B1P3/5P2/1PQ2NPP/R1R4K w - - 3 25",
            "r1b2rk1/p1q1ppbp/6p1/2Q5/8/4BP2/PPP3PP/2KR1B1R b - - 2 14",
            "6r1/5k2/p1b1r2p/1pB1p1p1/1Pp3PP/2P1R1K1/2P2P2/3R4 w - - 1 36",
            "rnbqkb1r/pppppppp/5n2/8/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2",
            "2rr2k1/1p4bp/p1q1p1p1/4Pp1n/2PB4/1PN3P1/P3Q2P/2RR2K1 w - f6 0 20",
            "3br1k1/p1pn3p/1p3n2/5pNq/2P1p3/1PN3PP/P2Q1PB1/4R1K1 w - - 0 23",
            "2r2b2/5p2/5k2/p1r1pP2/P2pB3/1P3P2/K1P3R1/7R w - - 23 93"
    );
    /* ── engine singletons ─────────────────────────────────────── */
    private final Search          search;
    private final PositionFactory pf;
    private final UciOptions      opts;
    private final MoveGenerator mg;

    /* ── mutable engine state (guarded by searchLock) ──────────── */
    private final Object searchLock = new Object();

    /** side effect-free copy of the current position */
    private long[] currentPos;
    /** all previous Zobrist keys (for 3-fold repetition) */
    private final List<Long> history = new ArrayList<>();

    /** handle of the search currently in flight (nullable) */
    private CompletableFuture<SearchResult> searchFuture;
    /** incremented for every new “go”, used to ignore stale callbacks */
    private int searchId = 0;

    /* ── construction ──────────────────────────────────────────── */
    public UciHandlerImpl(Search search,
                          PositionFactory pf,
                          UciOptions opts,
                          MoveGenerator mg) {
        this.search = search;
        this.pf     = pf;
        this.opts   = opts;
        this.mg    = mg;

        // start-pos
        this.currentPos = pf.fromFen(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    /* ── main loop ─────────────────────────────────────────────── */
    @Override public void runLoop() {
        try (Scanner in = new Scanner(System.in)) {
            while (in.hasNextLine()) {
                String line = in.nextLine().trim();
                if (!line.isEmpty() && handle(line)) break;   // “quit” → exit
            }
        }
    }

    /* ── router ───────────────────────────────────────────────── */
    private boolean handle(String cmd) {
        String[] t = cmd.split("\\s+");

        return switch (t[0]) {
            case "uci"          -> { cmdUci();        yield false; }
            case "isready"      -> { System.out.println("readyok"); yield false; }
            case "ucinewgame"   -> { cmdNewGame();    yield false; }
            case "setoption"    -> { opts.setOption(cmd); yield false; }
            case "position"     -> { cmdPosition(t);  yield false; }
            case "go"           -> { cmdGo(t);        yield false; }
            case "stop"         -> { cmdStop();       yield false; }
            case "ponderhit"    -> { search.ponderHit(); yield false; }
            case "quit"         -> { cmdStop();       yield true;  }
            case "bench" -> { cmdBench(t); yield false; }
            default             -> { // unknown
                System.out.println("info string Unknown command: " + cmd);
                yield false;
            }
        };
    }

    /* ── UCI commands ─────────────────────────────────────────── */

    private void cmdUci() {
        System.out.println("id name Helios");
        System.out.println("id author Your Name");
        opts.printOptions();
        System.out.println("uciok");
    }

    private void cmdNewGame() {
        synchronized (searchLock) {
            cancelRunningSearch();
            opts.getTranspositionTable().clear();
            history.clear();
        }
    }

    private void cmdStop() {
        synchronized (searchLock) { cancelRunningSearch(); }
    }

    private void cmdPosition(String[] t) {
        synchronized (searchLock) {
            cancelRunningSearch();

            int i = 1;
            if ("startpos".equals(t[i])) {                       // startpos
                currentPos = pf.fromFen(
                        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
                i++;
            } else if ("fen".equals(t[i])) {                     // FEN …
                StringBuilder fen = new StringBuilder();
                while (++i < t.length && !"moves".equals(t[i]))
                    fen.append(t[i]).append(' ');
                currentPos = pf.fromFen(fen.toString().trim());
            } else {
                return;                                          // malformed
            }

            history.clear();
            history.add(currentPos[PositionFactory.HASH]);

            /* optional move list */
            if (i < t.length && "moves".equals(t[i])) {
                MoveGenerator mg = new MoveGeneratorImpl();
                for (int k = i + 1; k < t.length; k++) {
                    int mv = UciMove.stringToMove(currentPos, t[k], mg);
                    if (mv != 0 && pf.makeMoveInPlace(currentPos, mv, mg))
                        history.add(currentPos[PositionFactory.HASH]);
                }
                history.remove(history.size() - 1);              // last == current
            }
        }
    }

    private void cmdGo(String[] t) {
        /* 1) build SearchSpec ---------------------------------- */
        SearchSpec.Builder b = new SearchSpec.Builder();
        for (int i = 1; i < t.length; i++)
            switch (t[i]) {
                case "wtime"     -> b.wTimeMs(Long.parseLong(t[++i]));
                case "btime"     -> b.bTimeMs(Long.parseLong(t[++i]));
                case "winc"      -> b.wIncMs(Long.parseLong(t[++i]));
                case "binc"      -> b.bIncMs(Long.parseLong(t[++i]));
                case "movestogo" -> b.movesToGo(Integer.parseInt(t[++i]));
                case "depth"     -> b.depth(Integer.parseInt(t[++i]));
                case "nodes"     -> b.nodes(Long.parseLong(t[++i]));
                case "movetime"  -> b.moveTimeMs(Long.parseLong(t[++i]));
                case "infinite"  -> b.infinite(true);
                case "ponder"    -> b.ponder(true);
            }

        final int myId;
        synchronized (searchLock) {
            cancelRunningSearch();

            myId = ++searchId;
            b.history(new ArrayList<>(history));

            opts.getTranspositionTable().incrementAge();

            searchFuture = search.searchAsync(
                    currentPos.clone(),
                    b.build(),
                    info -> { if (myId == searchId) printInfo(info); });
        }

        /* handle completion asynchronously */
        searchFuture.thenAccept(r -> {
            synchronized (searchLock) {
                if (myId == searchId) printResult(r);
            }
        }).exceptionally(ex -> {
            System.out.println("info string search error: " + ex);
            return null;
        });
    }


    private void cmdBench(String[] ignored) {
        final int ttSizeMb = 64;                       // resize TT once
        final int threads  = 3;
        final int depth    = 6;                        // hard-wired

        runBench(ttSizeMb, threads, depth);            // <── now exists!
    }

    /* ── NEW helper ─────────────────────────────────────────────── */
    private void runBench(int ttMb, int threads, int depth) {
        /* correct: ‘ttMb’ is already megabytes */
        opts.getTranspositionTable().resize(ttMb);
        opts.getTranspositionTable().clear();

        long totalNodes = 0, totalTimeMs = 0;

        for (int idx = 0; idx < BENCH_FENS.size(); idx++) {
            long[] pos = pf.fromFen(BENCH_FENS.get(idx));

            SearchSpec spec = new SearchSpec.Builder()
                    .depth(depth)
                    .history(List.of(pos[PositionFactory.HASH]))
                    .infinite(true)
                    .build();

            long t0 = System.nanoTime();
            SearchResult res = search.searchAsync(pos, spec, info -> {}).join();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            long nps = ms > 0 ? (1000L * res.nodes()) / ms : 0;

            totalNodes += res.nodes();
            totalTimeMs += ms;
        }

        long totalNps = totalTimeMs > 0 ? (1000L * totalNodes) / totalTimeMs : 0;

        System.out.println("==========================");
        System.out.printf("Total time (ms) : %d%n", totalTimeMs);   // ← NEW (required by OB)
        System.out.printf("Nodes searched  : %d%n", totalNodes);
        System.out.printf("Nodes/second    : %d%n", totalNps);
        System.out.println("==========================");
        System.out.println("benchok");
    }

    /* ── local helpers ────────────────────────────────────────── */
    private static List<String> readAllLinesSilently(String file) {
        try { return Files.readAllLines(Paths.get(file)); }
        catch (IOException ex) {
            System.out.printf("info string bench: cannot read %s (%s)%n", file, ex);
            return List.of();
        }
    }

    private static List<String> loadResourceLines(String r) {     // <-- unused now
        try (var in = UciHandlerImpl.class.getResourceAsStream(r);
             var br = new java.io.BufferedReader(new InputStreamReader(in))) {
            return br.lines().filter(l -> !l.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int  toInt (String s, int  d) { try { return Integer.parseInt(s);} catch(Exception e){return d;}}
    private static long toLong(String s, long d) { try { return Long.parseLong(s);} catch(Exception e){return d;}}



    /* ── helpers ─────────────────────────────────────────────── */

    /** stop & wait for any running search */
    private void cancelRunningSearch() {
        if (searchFuture != null && !searchFuture.isDone()) {
            search.stop();
            searchFuture.join();     // wait until fully stopped
        }
        searchFuture = null;
    }

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
        String best   = UciMove.moveToUci(r.bestMove());
        String ponder = r.ponderMove() == 0 ? "" :
                " ponder " + UciMove.moveToUci(r.ponderMove());
        System.out.println("bestmove " + best + ponder);
    }

    /* ── tiny utility helpers ────────────────────────────────── */

    private static final class UciMove {

        static String moveToUci(int m) {
            if (m == 0) return "0000";
            int from = (m >>> 6) & 63, to = m & 63;
            String res = sq(from) + sq(to);
            if (((m >>> 14) & 3) == 1)                       // promotion
                res += "nbrq".charAt((m >>> 12) & 3);
            return res;
        }

        static int stringToMove(long[] pos, String s, MoveGenerator mg) {
            int[] list = new int[256];
            boolean inCheck = PositionFactory.whiteToMove(
                    pos[PositionFactory.META])
                    ? mg.kingAttacked(pos, true)
                    : mg.kingAttacked(pos, false);

            int n = inCheck ? mg.generateEvasions(pos, list, 0)
                    : mg.generateQuiets(pos, list,
                    mg.generateCaptures(pos, list, 0));

            for (int i = 0; i < n; i++)
                if (moveToUci(list[i]).equals(s)) return list[i];
            return 0;
        }

        private static String sq(int s) {
            return "" + (char) ('a' + (s & 7)) + (1 + (s >>> 3));
        }
    }

    private static final class UciScore {
        static String format(int cp, boolean mate) {
            return mate
                    ? "mate " + (cp > 0 ? (32000 - cp + 1) / 2
                    : -(32000 + cp) / 2)
                    : "cp " + cp;
        }
    }
}
