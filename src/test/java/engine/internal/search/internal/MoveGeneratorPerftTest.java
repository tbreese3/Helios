package engine.internal.search.internal;

import static org.junit.jupiter.api.Assertions.*;

import engine.Position;
import engine.internal.search.MoveGenerator;
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
 * Perft regression + speed check that drives the allocation-free pipeline (piece-map + {@link
 * PackedPositionFactoryImpl.FastDiff}).
 */
@Tag("perft")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorPerftTest {

  /* ── collaborators ─────────────────────────────────────────── */
  private static final PackedPositionFactoryImpl POS_FACTORY = new PackedPositionFactoryImpl();
  private static final MoveGenerator GEN = new MoveGeneratorHQ();

  /* Tiny Position wrapper (FEN only) */
  private static final class FenPos implements Position {
    private final String fen;

    FenPos(String f) {
      fen = f;
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

  /* immutable test vector */
  private record TestCase(String fen, int depth, long expected) {}

  private List<TestCase> cases;
  private long nodes = 0, timeNs = 0;

  /* ── load qbb.txt once ─────────────────────────────────────── */
  @BeforeAll
  void load() throws IOException {
    cases = new ArrayList<>();
    try (InputStream is = getClass().getResourceAsStream("/perft/qbb.txt");
        BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {

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
    assertFalse(cases.isEmpty(), "No perft vectors loaded");

    /* pre-allocate one FastDiff per ply */
    for (int i = 0; i < MAX_PLY; ++i) DIFFS[i] = new PackedPositionFactoryImpl.FastDiff();
  }

  /* ── parameterised perft test ──────────────────────────────── */
  @ParameterizedTest(name = "fast-HQ {index}")
  @MethodSource("caseStream")
  void perft(TestCase tc) {
    long[] bb = POS_FACTORY.toBitboards(new FenPos(tc.fen));
    byte[] board = BOARDS[0];
    POS_FACTORY.initPieceMap(bb, board);

    long t0 = System.nanoTime();
    long got = perft(bb, board, tc.depth, 0);
    timeNs += System.nanoTime() - t0;
    nodes += got;

    assertEquals(tc.expected, got, () -> "mismatch depth=" + tc.depth + " FEN=" + tc.fen);
  }

  Stream<TestCase> caseStream() {
    return cases.stream();
  }

  /* ── final aggregate report ────────────────────────────────── */
  @AfterAll
  void report() {
    double s = timeNs / 1_000_000_000.0;
    System.out.printf(
        "FAST  : %,d nodes  %.3f s  %,d NPS%n", nodes, s, (long) (nodes / Math.max(1e-9, s)));
  }

  /* ── perft core using FastDiff + piece-map ─────────────────── */
  private static final int MAX_PLY = 64;
  private static final int LIST_CAP = 256;
  private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];
  private static final PackedPositionFactoryImpl.FastDiff[] DIFFS =
      new PackedPositionFactoryImpl.FastDiff[MAX_PLY];
  private static final byte[][] BOARDS = new byte[MAX_PLY][64];

  private static long perft(long[] bb, byte[] board, int depth, int ply) {
    if (depth == 0) return 1;

    int[] list = MOVES[ply];
    int cnt = GEN.generate(bb, list, MoveGenerator.GenMode.ALL);
    long sum = 0;

    for (int i = 0; i < cnt; i++) {
      int mv = list[i];
      PackedPositionFactoryImpl.FastDiff d = DIFFS[ply];
      if (!POS_FACTORY.makeMoveInPlace(bb, board, mv, GEN, d)) continue; // illegal
      sum += perft(bb, board, depth - 1, ply + 1);
      POS_FACTORY.undoMoveInPlace(bb, board, d);
    }
    return sum;
  }
}
