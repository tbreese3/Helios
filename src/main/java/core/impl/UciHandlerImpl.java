package core.impl;

import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

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

    /* ── engine singletons ─────────────────────────────────────── */
    private final Search          search;
    private final PositionFactory pf;
    private final UciOptions      opts;

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
                          UciOptions opts) {
        this.search = search;
        this.pf     = pf;
        this.opts   = opts;

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
