package engine.internal.search.internal;

import static org.junit.jupiter.api.Assertions.*;

import engine.Position;
import engine.internal.search.MoveGenerator;
import engine.internal.search.PackedPositionFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Perft regression + speed benchmark for {@link MoveGeneratorHQ}.
 *
 * <p>Test vectors are loaded from <code>src/test/resources/perft/qbb.txt</code>.<br>
 * Each entry is executed for both generators, the node count is asserted against the reference, and
 * execution time is accumulated so the suite prints overall <b>nodes‑per‑second (NPS)</b> figures
 * when finished.
 *
 * <p>Run with:
 *
 * <pre>  ./gradlew test  # executes all tests, incl. this perft suite</pre>
 *
 * Use Gradle/JUnit tags to include or exclude the slower perft group if needed:
 *
 * <pre>
 *   ./gradlew test -PexcludeTags=perft   # skip
 *   ./gradlew test -PincludeTags=perft   # run only perft
 * </pre>
 */
@Tag("perft")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorPerftTest {

  /* — factories + generators — */
  private static final PackedPositionFactory POS_FACTORY = new PackedPositionFactoryImpl();
  private static final MoveGenerator HQ = new MoveGeneratorHQ();

  /* miniature Position wrapper – only FEN is used by the factory        */
  private static final class FenPosition implements Position {
    private final String fen;

    FenPosition(String fen) {
      this.fen = fen;
    }

    @Override
    public String toFen() {
      return fen;
    }

    @Override
    public boolean whiteToMove() {
      return false;
    }

    @Override
    public int halfmoveClock() {
      return 0;
    }

    @Override
    public int fullmoveNumber() {
      return 1;
    }

    @Override
    public int castlingRights() {
      return 0;
    }

    @Override
    public int enPassantSquare() {
      return -1;
    }
  }

  /* simple record holding one test‑vector */
  private record TestCase(String fen, int depth, long expected) {}

  private List<TestCase> cases; // loaded once

  private long hqNodes = 0, hqTimeNs = 0; // accumulators

  /* ──────────────────────────── LOAD VECTORS ───────────────────────── */
  @BeforeAll
  void loadCases() throws IOException {
    cases = new ArrayList<>();
    try (InputStream is = getClass().getResourceAsStream("/perft/qbb.txt");
         BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] parts = line.split(";");
        if (parts.length < 3) continue; // malformed
        String fen = parts[0].trim();
        int depth = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
        long ref = Long.parseLong(parts[2].replaceAll("[^0-9]", ""));
        cases.add(new TestCase(fen, depth, ref));
      }
    }
    assertFalse(cases.isEmpty(), "No perft vectors loaded – check resource path");
  }

  /* ───────────────── HQ generator ─────────────────────── */
  @ParameterizedTest(name = "HQ {index}")
  @MethodSource("caseStream")
  void perftHQ(TestCase tc) {
    long[] root = POS_FACTORY.toBitboards(new FenPosition(tc.fen));
    long t0 = System.nanoTime();
    long nodes = perft(root, tc.depth, 0, HQ);
    long dt = System.nanoTime() - t0;
    hqNodes += nodes;
    hqTimeNs += dt;
    assertEquals(tc.expected, nodes, () -> "HQ mismatch  depth=" + tc.depth + "  FEN=" + tc.fen);
  }

  /* single source feeds both parameterised tests */
  Stream<TestCase> caseStream() {
    return cases.stream();
  }

  /* ───────────────── FINAL REPORT ─────────────────────── */
  @AfterAll
  void printSpeedSummary() {
    System.out.println("──── Perft speed summary ────");
    report("HQ", hqNodes, hqTimeNs);
    System.out.println("────────────────────────────── ");
  }

  private static void report(String name, long nodes, long timeNs) {
    double secs = timeNs / 1_000_000_000.0;
    long nps = (long) (nodes / Math.max(1e-9, secs));
    System.out.printf("%6s : %,d nodes  %.3f s  %,d NPS", name, nodes, secs, nps);
  }

  /* ───────────────── miniature perft engine ───────────────── */
  private static final int  MAX_PLY   = 64;
  private static final int  LIST_CAP  = 256;

  /* one independent move buffer per ply */
  private static final int[][] MOVES  = new int[MAX_PLY][LIST_CAP];
  private static final long[]  COOKIE = new long[MAX_PLY];

  private static long perft(long[] pos, int depth, int ply, MoveGenerator g) {
    if (depth == 0) return 1;

    int[] list = MOVES[ply];                      // <-- **unique buffer**
    int   cnt  = g.generate(pos, list, MoveGenerator.GenMode.ALL);
    long  nodes = 0;

    for (int i = 0; i < cnt; ++i) {
      int  mv     = list[i];                    // stable copy
      long cookie = POS_FACTORY.pushLegal(pos, mv, g);
      if (cookie >= 0) {
        COOKIE[ply] = cookie;                 // store undo info for this ply
        nodes += perft(pos, depth - 1, ply + 1, g);
        POS_FACTORY.undoMoveInPlace(pos, mv, cookie);
      }
    }
    return nodes;
  }
}