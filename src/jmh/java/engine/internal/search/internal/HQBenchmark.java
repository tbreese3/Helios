package engine.internal.search.internal;

import engine.Position;
import engine.internal.search.MoveGenerator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * JMH throughput benchmark that exercises the allocation-free make/undo pipeline (piece-map +
 * {@link PackedPositionFactoryImpl.FastDiff}).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class HQBenchmark {

  /* ── collaborators ──────────────────────────────────────────── */
  private static final PackedPositionFactoryImpl FACT = new PackedPositionFactoryImpl();
  private static final MoveGenerator GEN = new MoveGeneratorHQ();

  /* ── scratch buffers (no GC while running) ───────────────────── */
  private static final int MAX_PLY = 64;
  private static final int LIST_CAP = 256;

  private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];
  private static final PackedPositionFactoryImpl.FastDiff[] DIFFS =
      new PackedPositionFactoryImpl.FastDiff[MAX_PLY];
  private static final byte[] BOARD = new byte[64]; // one shared piece-map

  /* ── bench inputs: first 10 qbb positions ───────────────────── */
  private static final int CASES = 10;
  private long[][] roots;
  private int[] depths;

  /* ── aggregate counter for JMH output ───────────────────────── */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Metrics {
    public long nodes;
  }

  /* ── one-time setup ─────────────────────────────────────────── */
  @Setup(Level.Trial)
  public void init() throws Exception {
    for (int i = 0; i < MAX_PLY; ++i) DIFFS[i] = new PackedPositionFactoryImpl.FastDiff();

    List<long[]> pos = new ArrayList<>();
    List<Integer> dep = new ArrayList<>();

    try (var is = getClass().getResourceAsStream("/perft/qbb.txt");
        var br = new BufferedReader(new InputStreamReader(is))) {

      br.lines()
          .map(String::trim)
          .filter(l -> !(l.isEmpty() || l.startsWith("#")))
          .limit(CASES)
          .forEach(
              l -> {
                String[] p = l.split(";");
                pos.add(FACT.toBitboards(new FenPos(p[0].trim())));
                dep.add(Integer.parseInt(p[1].replaceAll("[^0-9]", "")));
              });
    }
    roots = pos.toArray(long[][]::new);
    depths = dep.stream().mapToInt(Integer::intValue).toArray();
  }

  /* ── benchmark body ─────────────────────────────────────────── */
  @Benchmark
  public void perftNodes(Metrics m) {
    long nodes = 0;
    for (int i = 0; i < roots.length; ++i) {
      long[] bb = roots[i].clone(); // private copy
      FACT.initPieceMap(bb, BOARD); // reset piece-map
      nodes += perft(bb, depths[i], 0);
    }
    m.nodes += nodes;
  }

  /* ── GC-free recursive perft ---------------------------------- */
  private static long perft(long[] bb, int depth, int ply) {
    if (depth == 0) return 1;

    int[] list = MOVES[ply];
    int cnt = GEN.generate(bb, list, MoveGenerator.GenMode.ALL);
    long sum = 0;

    for (int i = 0; i < cnt; ++i) {
      int mv = list[i];
      PackedPositionFactoryImpl.FastDiff d = DIFFS[ply];
      if (!FACT.makeMoveInPlace(bb, BOARD, mv, GEN, d)) continue; // illegal
      sum += perft(bb, depth - 1, ply + 1);
      FACT.undoMoveInPlace(bb, BOARD, d);
    }
    return sum;
  }

  /* ── minimal Position wrapper (FEN only) ────────────────────── */
  private record FenPos(String fen) implements Position {
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
}
