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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Perft regression + speed benchmark for {@link MoveGeneratorHQ} and {@link MoveGeneratorMagic}.
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("perft")
public class MoveGeneratorPerftTest {

  /* — factories + generators — */
  private static final PackedPositionFactory POS_FACTORY = new PackedPositionFactoryImpl();
  private static final MoveGenerator HQ = new MoveGeneratorHQ();
  private static final MoveGenerator MAGIC = new MoveGeneratorMagic();

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
  private long magNodes = 0, magTimeNs = 0;

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
    long nodes = perft(root, tc.depth, HQ);
    long dt = System.nanoTime() - t0;
    hqNodes += nodes;
    hqTimeNs += dt;
    assertEquals(tc.expected, nodes, () -> "HQ mismatch  depth=" + tc.depth + "  FEN=" + tc.fen);
  }

  /* ───────────────── MAGIC generator ──────────────────── */
  @ParameterizedTest(name = "MAGIC {index}")
  @MethodSource("caseStream")
  void perftMagic(TestCase tc) {
    long[] root = POS_FACTORY.toBitboards(new FenPosition(tc.fen));
    long t0 = System.nanoTime();
    long nodes = perft(root, tc.depth, MAGIC);
    long dt = System.nanoTime() - t0;
    magNodes += nodes;
    magTimeNs += dt;
    assertEquals(tc.expected, nodes, () -> "MAGIC mismatch  depth=" + tc.depth + "  FEN=" + tc.fen);
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
    report("MAGIC", magNodes, magTimeNs);
    System.out.println("────────────────────────────── ");
  }

  private static void report(String name, long nodes, long timeNs) {
    double secs = timeNs / 1_000_000_000.0;
    long nps = (long) (nodes / Math.max(1e-9, secs));
    System.out.printf("%6s : %,d nodes  %.3f s  %,d NPS", name, nodes, secs, nps);
  }

  /* ───────────────── miniature perft engine ───────────────── */
  private static long perft(long[] pos, int depth, MoveGenerator gen) {
    if (depth == 0) return 1;
    int[] moves = new int[256];
    int count = gen.generate(pos, moves, MoveGenerator.GenMode.ALL);
    long nodes = 0;
    for (int i = 0; i < count; ++i) {
      long[] child = pos.clone();
      if (POS_FACTORY.makeLegalMoveInPlace(child, moves[i], gen))
        nodes += perft(child, depth - 1, gen);
    }
    return nodes;
  }

  /** Recursively compares HQ and MAGIC – aborts on the first divergence. */
  private static void assertGeneratorsEqual(long[] pos, int depth, java.util.List<Integer> path) {

    int[] hqMv = new int[256], mgMv = new int[256];
    int nHQ = HQ.generate(pos, hqMv, MoveGenerator.GenMode.ALL);
    int nMG = MAGIC.generate(pos, mgMv, MoveGenerator.GenMode.ALL);

    // keep only legal moves (king-safe) and put into a TreeSet for order-agnostic compare
    java.util.Set<Integer> hqSet = new java.util.TreeSet<>();
    java.util.Set<Integer> mgSet = new java.util.TreeSet<>();

    for (int i = 0; i < nHQ; i++) {
      long[] c = pos.clone();
      if (POS_FACTORY.makeLegalMoveInPlace(c, hqMv[i], HQ)) hqSet.add(hqMv[i]);
    }
    for (int i = 0; i < nMG; i++) {
      long[] c = pos.clone();
      if (POS_FACTORY.makeLegalMoveInPlace(c, mgMv[i], MAGIC)) mgSet.add(mgMv[i]);
    }

    if (!hqSet.equals(mgSet)) {
      // ---- mismatch found – build a helpful assertion message
      String fen = POS_FACTORY.fromBitboards(pos).toFen();
      fail("Generators diverged at FEN:" + fen);
    }

    if (depth == 0) return; // finished search

    // continue recursively (DFS) – order does not matter thanks to TreeSet
    for (int mv : hqSet) {
      long[] child = pos.clone();
      if (!POS_FACTORY.makeLegalMoveInPlace(child, mv, HQ)) continue; // should not happen

      path.add(mv);
      assertGeneratorsEqual(child, depth - 1, path); // recurse
      path.remove(path.size() - 1);
    }
  }
}
