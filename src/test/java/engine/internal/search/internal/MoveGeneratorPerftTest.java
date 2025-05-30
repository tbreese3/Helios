package engine.internal.search.internal;

import static org.junit.jupiter.api.Assertions.*;

import engine.Position;
import engine.internal.search.MoveGenerator;
import engine.internal.search.PackedPositionFactory;
import engine.internal.search.PackedPositionFactory.Diff;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Single-threaded perft regression + speed benchmark that uses the zero-alloc
 * {@link PackedPositionFactory} <code>Diff</code> stack.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorPerftTest {

  private static final PackedPositionFactory POS_FACTORY = new PackedPositionFactoryImpl();
  private static final MoveGenerator         GEN         = new MoveGeneratorGiga();

  /* Lightweight Position wrapper (only FEN string is required) */
  private static final class FenPosition implements Position {
    private final String fen;
    FenPosition(String fen) { this.fen = fen; }

    @Override public String toFen()          { return fen; }
    @Override public boolean whiteToMove()   { return false; }
    @Override public int  halfmoveClock()    { return 0; }
    @Override public int  fullmoveNumber()   { return 1; }
    @Override public int  castlingRights()   { return 0; }
    @Override public int  enPassantSquare()  { return -1; }
  }

  /* immutable test vector */
  private record TestCase(String fen, int depth, long expected) {}

  private List<TestCase> cases;
  private long nodes = 0, timeNs = 0;

  // ──────────────────────────────────────────────────────────────
  //  Load qbb.txt once
  // ──────────────────────────────────────────────────────────────
  @BeforeAll
  void load() throws IOException {
    cases = new ArrayList<>();
    try (InputStream is = getClass().getResourceAsStream("/perft/qbb.txt");
         BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {

      br.lines()
              .map(String::trim)
              .filter(l -> !(l.isEmpty() || l.startsWith("#")))
              .forEach(l -> {
                String[] p = l.split(";");
                if (p.length < 3) return;
                cases.add(new TestCase(
                        p[0].trim(),
                        Integer.parseInt(p[1].replaceAll("\\D", "")),
                        Long.parseLong (p[2].replaceAll("\\D", ""))
                ));
              });
    }
    assertFalse(cases.isEmpty(), "No perft vectors loaded");
  }

  // ──────────────────────────────────────────────────────────────
  //  Parameterised perft test
  // ──────────────────────────────────────────────────────────────
  @ParameterizedTest(name = "fast-HQ {index}")
  @MethodSource("caseStream")
  void perft(TestCase tc) {
    long[] root = POS_FACTORY.toBitboards(new FenPosition(tc.fen));
    long t0 = System.nanoTime();
    long got = perft(root, tc.depth, 0);
    timeNs += System.nanoTime() - t0;
    nodes  += got;
    assertEquals(tc.expected, got,
            () -> "mismatch depth=" + tc.depth + " FEN=" + tc.fen);
  }

  Stream<TestCase> caseStream() { return cases.stream(); }

  // ──────────────────────────────────────────────────────────────
  //  Final aggregate report
  // ──────────────────────────────────────────────────────────────
  @AfterAll
  void report() {
    double s = timeNs / 1_000_000_000.0;
    System.out.printf("FAST : %,d nodes  %.3f s  %,d NPS%n",
            nodes, s, (long)(nodes / Math.max(1e-9, s)));
  }

  // ──────────────────────────────────────────────────────────────
  //  Core recursive perft using Diff stack
  // ──────────────────────────────────────────────────────────────
  private static final int MAX_PLY  = 64;
  private static final int LIST_CAP = 256;
  private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];
  private static final Diff[]  DIFFS = new Diff[MAX_PLY];

  private static long perft(long[] bb, int depth, int ply) {
    if (depth == 0) return 1;

    int[] list = MOVES[ply];
    int cnt = GEN.generate(bb, list, MoveGenerator.GenMode.ALL);
    long sum = 0;

    for (int i = 0; i < cnt; i++) {
      int mv = list[i];

      // The generator is fully legal – makeMove may still flip ‘null’ if
      // the PackedPositionFactory considers the move structurally invalid
      // for that position (should never happen, but let’s keep the guard).
      Diff d = POS_FACTORY.makeMoveInPlace(bb, mv, GEN);
      assertNotNull(d, "illegal move surfaced!?");

      DIFFS[ply] = d;
      sum += perft(bb, depth - 1, ply + 1);
      POS_FACTORY.undoMoveInPlace(bb, DIFFS[ply]);
    }
    return sum;
  }
}
