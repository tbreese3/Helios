package engine.internal.search.internal;

import engine.Position;
import engine.internal.search.MoveGenerator;
import engine.internal.search.PackedPositionFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Compare MoveGeneratorHQ on a bundle of small perft positions. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class HQBenchmark {

  private static final PackedPositionFactory FACT = new PackedPositionFactoryImpl();
  private static final MoveGenerator         GEN  = new MoveGeneratorHQ();

  /* ─────── fixed scratch buffers (no GC) ──────────────────────────── */
  private static final int  MAX_PLY   = 64;
  private static final int  LIST_CAP  = 256;

  private static final int[][] MOVES  = new int[MAX_PLY][LIST_CAP];
  private static final long[]  COOKIE = new long[MAX_PLY];

  private long[][] roots;         // start positions (bitboards)
  private int[]    depths;        // matching perft depths

  /* ---------- load positions once per fork ---------- */
  @Setup(Level.Trial)
  public void init() throws Exception {
    int cases = 10;
    List<long[]> posTmp   = new ArrayList<>();
    List<Integer> depthTmp = new ArrayList<>();

    try (var is = getClass().getResourceAsStream("/perft/qbb.txt");
         var br = new BufferedReader(new InputStreamReader(is))) {

      br.lines()
              .map(String::trim)
              .filter(l -> !(l.isEmpty() || l.startsWith("#")))
              .limit(cases)
              .forEach(l -> {
                String[] p = l.split(";");
                String fen = p[0].trim();
                int    d   = Integer.parseInt(p[1].replaceAll("[^0-9]", ""));
                posTmp.add(FACT.toBitboards(new FenPos(fen)));
                depthTmp.add(d);
              });
    }
    roots  = posTmp.toArray(long[][]::new);
    depths = depthTmp.stream().mapToInt(Integer::intValue).toArray();
  }

  /* ---------- benchmark body ---------- */
  @Benchmark
  public long totalNodes() {
    long sum = 0;

    for (int i = 0; i < roots.length; i++) {
      // work on a private copy of the root position
      long[] root = roots[i].clone();
      sum += perft(root, depths[i], 0);
    }
    return sum; // keep JVM from DCE-ing the loop
  }

  /* ---------- allocation-free perft ---------- */
  private static long perft(long[] pos, int depth, int ply) {
    if (depth == 0) return 1L;

    int[] list = MOVES[ply];
    int   cnt  = GEN.generate(pos, list, MoveGenerator.GenMode.ALL);

    long nodes = 0;
    for (int i = 0; i < cnt; i++) {
      int  mv     = list[i];
      long cookie = FACT.pushLegal(pos, mv, GEN);
      if (cookie >= 0) {
        COOKIE[ply] = cookie;
        nodes += perft(pos, depth - 1, ply + 1);
        FACT.undoMoveInPlace(pos, mv, cookie);
      }
    }
    return nodes;
  }

  /* tiny Position wrapper – only FEN is needed */
  private record FenPos(String fen) implements Position {
    @Override public String toFen()            { return fen; }
    @Override public boolean whiteToMove()     { return false; }
    @Override public int  halfmoveClock()      { return 0; }
    @Override public int  fullmoveNumber()     { return 1; }
    @Override public int  castlingRights()     { return 0; }
    @Override public int  enPassantSquare()    { return -1; }
  }
}
