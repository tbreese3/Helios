package engine.internal.search.internal;

import static engine.internal.search.PackedPositionFactory.*;

import engine.Position;
import engine.internal.search.MoveGenerator;
import engine.internal.search.PackedPositionFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * JMH throughput benchmark for {@link MoveGeneratorHQ} that uses the Diff-based
 * fast make/undo supplied by {@link PackedPositionFactory}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class HQBenchmark {

  /* collaborators */
  private static final PackedPositionFactory FACT = new PackedPositionFactoryImpl();
  private static final MoveGenerator         GEN  = new MoveGeneratorHQ();

  /* scratch buffers (no GC) */
  private static final int MAX_PLY  = 64;
  private static final int LIST_CAP = 256;
  private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

  /* bench inputs: first 10 perft cases from qbb.txt */
  private static final int CASES = 10;
  private long[][] roots;
  private int[]    depths;

  /* report nodes/sec */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Metrics { public long nodes; }

  /* ── load test positions once per fork ───────────────────────── */
  @Setup(Level.Trial)
  public void init() throws Exception {
    List<long[]> pos  = new ArrayList<>();
    List<Integer> dep = new ArrayList<>();

    try (var is = getClass().getResourceAsStream("/perft/qbb.txt");
         var br = new BufferedReader(new InputStreamReader(is))) {

      br.lines()
              .map(String::trim)
              .filter(l -> !(l.isEmpty() || l.startsWith("#")))
              .limit(CASES)
              .forEach(l -> {
                String[] p = l.split(";");
                pos.add(FACT.toBitboards(new FenPos(p[0].trim())));
                dep.add(Integer.parseInt(p[1].replaceAll("[^0-9]", "")));
              });
    }
    roots  = pos.toArray(long[][]::new);
    depths = dep.stream().mapToInt(Integer::intValue).toArray();
  }

  /* ── benchmark body ──────────────────────────────────────────── */
  @Benchmark
  public void perftNodes(Metrics m) {
    long nodes = 0;
    for (int i = 0; i < roots.length; i++) {
      long[] root = roots[i].clone();            // private copy for thread-safety
      nodes += perft(root, depths[i], 0);
    }
    m.nodes += nodes;
  }

  /* ── GC-free perft using Diff stack, identical to the JUnit version ── */
  private static long perft(long[] bb, int depth, int ply) {
    if (depth == 0) return 1;

    int[] list = MOVES[ply];
    int   cnt  = GEN.generateAll(bb, list);

    long parentMeta = bb[DIFF_META];   // save once per call
    long parentInfo = bb[DIFF_INFO];
    long nodes      = 0;

    for (int i = 0; i < cnt; ++i) {
      int mv = list[i];

      /* try the move */
      if (!FACT.makeMoveInPlace(bb, mv, GEN)) {
        // illegal -> restore cookie for next sibling
        bb[DIFF_META] = parentMeta;
        bb[DIFF_INFO] = parentInfo;
        continue;
      }

      nodes += perft(bb, depth - 1, ply + 1);

      FACT.undoMoveInPlace(bb);        // restores board + child cookie
      bb[DIFF_META] = parentMeta;      // restore parent cookie
      bb[DIFF_INFO] = parentInfo;
    }
    return nodes;
  }

  /* ── minimal Position wrapper (FEN only) ─────────────────────── */
  private record FenPos(String fen) implements Position {
    @Override public String toFen()               { return fen; }
    @Override public boolean whiteToMove()        { return false; }
    @Override public int  halfmoveClock()         { return 0; }
    @Override public int  fullmoveNumber()        { return 1; }
    @Override public int  castlingRights()        { return 0; }
    @Override public int  enPassantSquare()       { return -1; }
  }
}
