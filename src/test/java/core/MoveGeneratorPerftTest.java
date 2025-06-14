package core;

import core.contracts.MoveGenerator;
import core.contracts.PositionFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Stream;

import core.impl.MoveGeneratorImpl;
import core.impl.PositionFactoryImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorPerftTest {

  /* ── wiring ───────────────────────────────────────────────────── */
  private static final PositionFactory POS_FACTORY = new PositionFactoryImpl();
  private static final MoveGenerator GEN = new MoveGeneratorImpl();

  /* ── per‑test‑case record ─────────────────────────────────────── */
  private record TestCase(String fen, int depth, long expected) {}

  private List<TestCase> cases;
  private long nodes = 0, timeNs = 0;

  /* ── load the perft vectors once (from /perft/qbb.txt) ────────── */
  @BeforeAll
  void loadVectors() throws Exception {
    cases = new ArrayList<>();
    try (InputStream is = getClass().getResourceAsStream("/perft/qbb.txt");
         BufferedReader br =
                 new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {

      br.lines()
              .map(String::trim)
              .filter(l -> !(l.isEmpty() || l.startsWith("#")))
              .forEach(
                      l -> {
                        String[] p = l.split(";");
                        if (p.length < 3) return;
                        cases.add(
                                new TestCase(
                                        p[0].trim(),
                                        Integer.parseInt(p[1].replaceAll("[^0-9]", "")),
                                        Long.parseLong(p[2].replaceAll("[^0-9]", ""))));
                      });
    }
    Assertions.assertFalse(cases.isEmpty(), "no perft vectors found");
  }

  /* ── JUnit parameter source ───────────────────────────────────── */
  Stream<TestCase> caseStream() {
    return cases.stream();
  }

  /* ── the actual test method ───────────────────────────────────── */
  @ParameterizedTest(name = "fast‑HQ {index}")
  @MethodSource("caseStream")
  void perft(TestCase tc) {
    long[] root = POS_FACTORY.fromFen(tc.fen);
    boolean moverIsWhite = tc.fen.split("\\s+")[1].charAt(0) == 'w';

    long t0 = System.nanoTime();
    long got = perft(root, moverIsWhite, tc.depth, 0);
    timeNs += System.nanoTime() - t0;
    nodes += got;
    Assertions.assertEquals(tc.expected, got, () -> "mismatch depth=" + tc.depth + " FEN=" + tc.fen);
  }

  /* ── aggregate speed report ───────────────────────────────────── */
  @AfterAll
  void report() {
    double s = timeNs / 1_000_000_000.0;
    System.out.printf("FAST : %,d nodes  %.3f s  %,d NPS%n", nodes, s, (long) (nodes / Math.max(1e-9, s)));
  }

  /* ===================================================================
   *  Recursive perft with cookie‑stack and Stockfish‑style move ordering
   * =================================================================== */
  private static final int MAX_PLY = 64;
  private static final int LIST_CAP = 256;

  private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

  private static long perft(long[] bb, boolean moverIsWhite, int depth, int ply) {
    if (depth == 0) return 1;

    int[] moves = MOVES[ply];
    int cnt;

    // 1. Decide which move list to generate depending on check state
    if (GEN.kingAttacked(bb, moverIsWhite)) {
      cnt = GEN.generateEvasions(bb, moves, 0);
    } else {
      cnt = GEN.generateCaptures(bb, moves, 0);          // noisy first
      cnt = GEN.generateQuiets(bb, moves, cnt);          // then quiets appended
    }

    long nodes = 0;

    // 2. DFS over the move list
    for (int i = 0; i < cnt; ++i) {
      if (!POS_FACTORY.makeMoveInPlace(bb, moves[i], GEN)) {
        continue; // illegal → parent position (and its cookies) remain intact
      }

      nodes += perft(bb, !moverIsWhite, depth - 1, ply + 1);
      POS_FACTORY.undoMoveInPlace(bb); // restores board & per‑ply cookie snapshots
    }

    return nodes;
  }
}
