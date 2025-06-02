package engine.internal.search.internal;

import static engine.internal.search.PackedPositionFactory.*;

import engine.Position;
import engine.internal.search.MoveGenerator;
import engine.internal.search.PackedPositionFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Perft regression + speed benchmark that uses the
 * <em>cookie-in-bitboard</em> fast make/undo helpers of
 * {@link PackedPositionFactoryImpl}.<br>
 * <br>
 * The only difference to the old implementation is that we cache the two
 * cookie longs (<code>DIFF_META</code> &amp; <code>DIFF_INFO</code>)
 * per ply so that every recursive call gets the right values back when it
 * calls {@code undoMoveInPlace}.
 */
@Tag("perft")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorPerftTest {

  /* ── wiring ───────────────────────────────────────────────────── */
  private static final PackedPositionFactory POS_FACTORY =
          new PackedPositionFactoryImpl();
  private static final MoveGenerator         GEN         =
          new MoveGeneratorHQ();

  /* ── minimal immutable Position wrapper (only FEN needed) ─────── */
  private static final class FenPosition implements Position {
    private final String fen;
    FenPosition(String f) { fen = f; }
    @Override public String  toFen()            { return fen; }
    @Override public boolean whiteToMove()      { return false; }
    @Override public int     halfmoveClock()    { return 0; }
    @Override public int     fullmoveNumber()   { return 1; }
    @Override public int     castlingRights()   { return 0; }
    @Override public int     enPassantSquare()  { return -1; }
  }

  /* ── per-test-case record ─────────────────────────────────────── */
  private record TestCase(String fen, int depth, long expected) {}

  private List<TestCase> cases;
  private long nodes = 0, timeNs = 0;

  /* ── load the perft vectors once (from /perft/qbb.txt) ────────── */
  @BeforeAll
  void loadVectors() throws Exception {
    cases = new ArrayList<>();
    try (InputStream is = getClass().getResourceAsStream("/perft/qbb.txt");
         BufferedReader br = new BufferedReader(
                 new InputStreamReader(Objects.requireNonNull(is)))) {

      br.lines()
              .map(String::trim)
              .filter(l -> !(l.isEmpty() || l.startsWith("#")))
              .forEach(l -> {
                String[] p = l.split(";");
                if (p.length < 3) return;
                cases.add(new TestCase(
                        p[0].trim(),
                        Integer.parseInt(p[1].replaceAll("[^0-9]", "")),
                        Long.parseLong(p[2].replaceAll("[^0-9]", ""))));
              });
    }
    Assertions.assertFalse(cases.isEmpty(), "no perft vectors found");
  }

  /* ── JUnit parameter source ───────────────────────────────────── */
  Stream<TestCase> caseStream() { return cases.stream(); }

  /* ── the actual test method ───────────────────────────────────── */
  @ParameterizedTest(name = "fast-HQ {index}")
  @MethodSource("caseStream")
  void perft(TestCase tc) {
    long[] root = POS_FACTORY.toBitboards(new FenPosition(tc.fen));
    long t0 = System.nanoTime();
    long got = perft(root, tc.depth, 0);
    timeNs += System.nanoTime() - t0;
    nodes  += got;
    Assertions.assertEquals(tc.expected, got,
            () -> "mismatch depth=" + tc.depth + " FEN=" + tc.fen);
  }

  /* ── aggregate speed report ───────────────────────────────────── */
  @AfterAll
  void report() {
    double s = timeNs / 1_000_000_000.0;
    System.out.printf("FAST : %,d nodes  %.3f s  %,d NPS%n",
            nodes, s, (long)(nodes / Math.max(1e-9, s)));
  }

  /* ===================================================================
   *  Recursive perft with cookie-stack
   * =================================================================== */
  private static final int MAX_PLY  = 64;
  private static final int LIST_CAP = 256;

  private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

  private static long perft(long[] bb, int depth, int ply) {
    if (depth == 0) return 1;

    int[] moves = MOVES[ply];
    int   cnt   = GEN.generateAll(bb, moves);

    long parentMeta = bb[DIFF_META];   // one save per call
    long parentInfo = bb[DIFF_INFO];
    long nodes      = 0;

    for (int i = 0; i < cnt; ++i) {
      if (!POS_FACTORY.makeMoveInPlace(bb, moves[i], GEN))
        continue;                  // illegal -> parent cookie intact

      nodes += perft(bb, depth - 1, ply + 1);
      POS_FACTORY.undoMoveInPlace(bb);   // restores board + cookie
    }
    // not strictly needed – already correct – but keeps the invariant
    bb[DIFF_META] = parentMeta;
    bb[DIFF_INFO] = parentInfo;
    return nodes;
  }
}
