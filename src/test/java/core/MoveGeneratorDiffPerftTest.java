package core;

import static core.contracts.PositionFactory.*;

import core.contracts.MoveGenerator;
import core.contracts.PositionFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Diff‑perft regression that cross‑checks <em>exactly</em> the four move buckets Stockfish
 * prints in <code>dperft</code> mode – <strong>Captures, Quiets, Evasions and Legal</strong> –
 * against the engine’s {@link MoveGenerator}.<br>
 * <br>
 * Stockfish no longer emits a standalone “Pseudo” line, so this harness no longer tries to
 * track that category.  Instead we compare the individual move sets for each bucket plus the
 * total count of legal moves.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorDiffPerftTest {

    /* ═══════════ engine plumbing ═══════════ */
    private static final String          STOCKFISH_CMD = "C:\\Users\\tyler\\Documents\\HeliosMisc\\stockfish.exe";
    private static final PositionFactory PF            = new PositionFactoryImpl();
    private static final MoveGenerator   GEN           = new MoveGeneratorImpl();
    private static final int MAX_PLY = 64;
    private static final int LIST_CAP = 256;

    private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

    /* ═══════════ progress bar helper ═══════════ */
    private static final class ProgressBar {
        private static final int BAR_WIDTH = 40;
        private static final int UPDATE_MASK = 0xFFFF;
        private final long total;
        private final long start = System.nanoTime();
        private final AtomicLong done = new AtomicLong();
        private final PrintStream out = System.out;
        ProgressBar(long total) { this.total = total; }
        ProgressBar() { this(-1); }
        void tick() {
            long d = done.incrementAndGet();
            if ((d & UPDATE_MASK) != 0 && d != total) return;
            long elapsed = Math.max(1, (System.nanoTime() - start) / 1_000_000_000L);
            double nps = d / (double) elapsed;
            if (total > 0) {
                double pct = d * 100.0 / total;
                int filled = (int) Math.round(pct / 100 * BAR_WIDTH);
                String bar = "=".repeat(filled) + " ".repeat(BAR_WIDTH - filled);
                out.printf("\r[%s] %5.1f%%  %,d / %,d  (%,.0f nps)", bar, pct, d, total, nps);
                if (d >= total) out.println();
            } else {
                char[] spin = {'|','/','-','\\'};
                out.printf("\r%c  %,d nodes  (%,.0f nps)", spin[(int)(d & 3)], d, nps);
            }
            out.flush();
        }
    }

    /* ═══════════ per‑FEN container ═══════════ */
    private record Vec(String fen, int depth, long nodes) {
        boolean hasNodes()      { return nodes >= 0; }
        long    expectedNodes() { return nodes;      }
    }

    private List<Vec> vecs;

    /* ── load test vectors ───────────────────────────────────────────── */
    @BeforeAll
    void load() throws Exception {
        vecs = new ArrayList<>();
        try (var in = getClass().getResourceAsStream("/perft/qbb.txt");
             var br = new BufferedReader(new InputStreamReader(
                     Objects.requireNonNull(in, "qbb.txt not on classpath")))) {
            br.lines()
                    .map(String::trim)
                    .filter(l -> !(l.isEmpty() || l.startsWith("#")))
                    .forEach(l -> {
                        String[] parts = l.split(";");
                        if (parts.length < 3)
                            throw new IllegalArgumentException("Bad line: " + l);
                        String fen   = parts[0].trim();
                        int    depth = Integer.parseInt(parts[1].trim());
                        long   nodes = Long.parseLong(parts[2].trim());
                        vecs.add(new Vec(fen, depth, nodes));
                    });
        }
    }

    Stream<Vec> vecStream() { return vecs.stream(); }

    /* ═══════════ parameterised test ═══════════ */
    @ParameterizedTest(name = "diff‑perft {index}")
    @MethodSource("vecStream")
    void diffPerft(Vec v) throws Exception {
        ProgressBar pg = v.hasNodes() ? new ProgressBar(v.expectedNodes()) : new ProgressBar();

        Process sf = new ProcessBuilder(STOCKFISH_CMD).redirectErrorStream(true).start();
        try (var sin = new BufferedReader(new InputStreamReader(sf.getInputStream()));
             var sout = sf.getOutputStream()) {

            send(sout, "uci");          await(sin, "uciok");
            send(sout, "ucinewgame");
            send(sout, "position fen " + v.fen());

            long[] root = PF.fromFen(v.fen());
            walk(root, sf, sin, sout, new ArrayDeque<>(), v.depth(), pg);
        } finally {
            sf.destroy();
        }
    }

    /* ═══════════ recursive walker ═══════════ */
    private void walk(long[] pos,
                      Process sf, BufferedReader sin, OutputStream sout,
                      Deque<String> path, int depth, ProgressBar pg) throws Exception {

        pg.tick();

        /* 1) ask Stockfish for bucket counts at this node (depth 1) */
        send(sout, "dperft 1");
        SfDump sfDump = parseDump(sin); // waits for dperftok

        /* 2) our pseudo‑legal buckets */
        int ply = path.size();
        int[] buf = MOVES[ply];

        boolean inCheck = GEN.kingAttacked(pos, (pos[META] & 1L) == 0);

        int capCnt   = !inCheck ? GEN.generateCaptures(pos, buf, 0) : 0;
        int totalCnt = !inCheck ? GEN.generateQuiets  (pos, buf, capCnt) : 0; // generator returns new size
        int quietCnt = totalCnt - capCnt;                                     // true quiet-only count
        int evasCnt  =  inCheck ? GEN.generateEvasions(pos, buf, 0) : 0;

        /* 3) bucket‑wise move strings */
        List<String> capMy   = new ArrayList<>(capCnt);
        List<String> quietMy = new ArrayList<>(quietCnt);
        List<String> evasMy  = new ArrayList<>(evasCnt);

        for (int i = 0; i < capCnt; ++i)   capMy.add(moveToUci(buf[i]));
        for (int i = 0; i < quietCnt; ++i) quietMy.add(moveToUci(buf[capCnt + i]));
        for (int i = 0; i < evasCnt; ++i)  evasMy.add(moveToUci(buf[i]));           // evas overwrote buf

        Collections.sort(capMy);
        Collections.sort(quietMy);
        Collections.sort(evasMy);

        /* 4) filter to legal moves */
        List<String> legalMy = new ArrayList<>();
        int myTotal = inCheck ? evasCnt : totalCnt;
        for (int i = 0; i < myTotal; ++i) {
            int m = buf[i];
            if (PF.makeMoveInPlace(pos, m, GEN)) {
                legalMy.add(moveToUci(m));
                PF.undoMoveInPlace(pos);
            }
        }
        Collections.sort(legalMy);

        /* 5) compare buckets & legal list */
        boolean mismatch =
                sfDump.captures   != capCnt   ||
                        sfDump.quiets     != quietCnt ||
                        sfDump.evasions   != evasCnt  ||
                        sfDump.legalCount != legalMy.size() ||
                        !sfDump.captureMoves.equals(capMy) ||
                        !sfDump.quietMoves.equals(quietMy) ||
                        !sfDump.evasionMoves.equals(evasMy) ||
                        !sfDump.legalMoves.equals(legalMy);

        if (mismatch) {
            Assertions.fail("\nMismatch at ply " + path.size() +
                    "\nPath      : " + String.join(" ", path) +
                    "\nFEN       : " + PF.toFen(pos) +
                    "\nStockfish  cap=" + sfDump.captures + " q=" + sfDump.quiets + " ev=" + sfDump.evasions + " legal=" + sfDump.legalCount +
                    "\nMine       cap=" + capCnt + " q=" + quietCnt + " ev=" + evasCnt + " legal=" + legalMy.size() +
                    "\nSF caps    : " + sfDump.captureMoves +
                    "\nMY caps    : " + capMy +
                    "\nSF quiets  : " + sfDump.quietMoves +
                    "\nMY quiets  : " + quietMy +
                    "\nSF evas    : " + sfDump.evasionMoves +
                    "\nMY evas    : " + evasMy +
                    "\nSF legal   : " + sfDump.legalMoves +
                    "\nMY legal   : " + legalMy);
        }

        /* 6) done? */
        if (depth == 0) return;

        /* 7) recurse through legal moves */
        for (String uci : sfDump.legalMoves) {
            int mv;
            try { mv = uciToInt(pos, uci); }
            catch (Exception e) { continue; }
            if (!PF.makeMoveInPlace(pos, mv, GEN)) continue;
            path.addLast(uci);
            send(sout, "position fen " + PF.toFen(pos));
            walk(pos, sf, sin, sout, path, depth - 1, pg);
            path.removeLast();
            PF.undoMoveInPlace(pos);
            send(sout, "position fen " + PF.toFen(pos));
        }
    }

    /* ═══════════ Stockfish dump parser ═══════════ */
    private record SfDump(long captures, long quiets, long evasions, long legalCount,
                          List<String> captureMoves, List<String> quietMoves,
                          List<String> evasionMoves, List<String> legalMoves) {}

    private static final Pattern CATEGORY_LINE =
            Pattern.compile("^(Captures|Quiets|Evasions|Legal)\\s*\\((\\d+)\\):");

    private SfDump parseDump(BufferedReader in) throws IOException {
        long captures = 0, quiets = 0, evasions = 0, legal = 0;
        List<String> capMoves = new ArrayList<>();
        List<String> quietMoves = new ArrayList<>();
        List<String> evasMoves = new ArrayList<>();
        List<String> legalMoves = new ArrayList<>();

        String line;
        String currentCat = null;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("dperftok")) break;

            Matcher m = CATEGORY_LINE.matcher(line);
            if (m.find()) {
                currentCat = m.group(1);
                long v = Long.parseLong(m.group(2));
                switch (currentCat) {
                    case "Captures" -> captures = v;
                    case "Quiets"   -> quiets   = v;
                    case "Evasions" -> evasions = v;
                    case "Legal"    -> legal    = v;
                }
                /* extract any moves on the same line (after colon) */
                int idx = line.indexOf(':');
                if (idx >= 0 && idx + 1 < line.length())
                    appendMoves(destFor(currentCat, capMoves, quietMoves, evasMoves, legalMoves),
                            line.substring(idx + 1));
                continue;
            }

            /* continuation lines start with the category name again */
            if (currentCat != null && line.startsWith(currentCat)) {
                int idx = line.indexOf(':');
                if (idx >= 0 && idx + 1 < line.length())
                    appendMoves(destFor(currentCat, capMoves, quietMoves, evasMoves, legalMoves),
                            line.substring(idx + 1));
            }
        }

        capMoves.sort(null);
        quietMoves.sort(null);
        evasMoves.sort(null);
        legalMoves.sort(null);
        return new SfDump(captures, quiets, evasions, legal, capMoves, quietMoves, evasMoves, legalMoves);
    }

    private static List<String> destFor(String cat, List<String> cap, List<String> q,
                                        List<String> ev, List<String> le) {
        return switch (cat) {
            case "Captures" -> cap;
            case "Quiets"   -> q;
            case "Evasions" -> ev;
            default -> le; // "Legal"
        };
    }

    private static void appendMoves(List<String> dest, String tail) {
        for (String s : tail.trim().split("\\s+"))
            if (!s.isEmpty()) dest.add(s);
    }

    /* ═══════════ helpers ═══════════ */
    private static void send(OutputStream out, String cmd) throws IOException {
        out.write((cmd + '\n').getBytes());
        out.flush();
    }
    private static void await(BufferedReader in, String tok) throws IOException {
        String l; while ((l = in.readLine()) != null && !l.contains(tok)) {/* spin */}
    }

    private static String moveToUci(int mv) {
        int f = (mv >>> 6) & 63, t = mv & 63;
        int type = (mv >>> 14) & 3, promo = (mv >>> 12) & 3;
        String s = "" + (char)('a'+(f&7)) + (char)('1'+(f>>>3))
                + (char)('a'+(t&7)) + (char)('1'+(t>>>3));
        // promo index: 0=N 1=B 2=R 3=Q  (see emitPromotions)
        if (type == 1) s += "nbrq".charAt(promo);
        return s;
    }

    /** Converts a UCI move string to packed int move assuming the piece types in <code>bb</code>. */
    private static int uciToInt(long[] bb, String uci) {
        int from  = (uci.charAt(0)-'a') | ((uci.charAt(1)-'1') << 3);
        int to    = (uci.charAt(2)-'a') | ((uci.charAt(3)-'1') << 3);
        int promo = uci.length() == 5 ? "nbrq".indexOf(uci.charAt(4)) : -1;

        int mover = -1;
        for (int p = 0; p < 12; ++p)
            if ((bb[p] & (1L << from)) != 0) { mover = p; break; }
        if (mover == -1) throw new IllegalStateException("No piece on source square for " + uci);

        int type = 0, promoIdx = 0;
        if (promo >= 0) { type = 1; promoIdx = promo; }
        else if ((mover == WK || mover == BK) && Math.abs(from - to) == 2) type = 3; // castles
        else if (mover == WP || mover == BP) {
            long meta = bb[META];
            int epSq  = (int)((meta & EP_MASK) >>> EP_SHIFT);
            boolean isEP = epSq != EP_NONE && to == epSq;
            if (isEP && ((mover == WP && (from>>>3) == 4 && (to == from+7 || to == from+9)) ||
                    (mover == BP && (from>>>3) == 3 && (to == from-7 || to == from-9))))
                type = 2;
        }
        return (from<<6) | to | (type<<14) | (promoIdx<<12) | (mover<<16);
    }
}
