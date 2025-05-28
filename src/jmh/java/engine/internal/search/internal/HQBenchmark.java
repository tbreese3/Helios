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

/** Compare MoveGeneratorHQ vs HQ2 on a bundle of small perft positions. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class HQBenchmark {
  private static final PackedPositionFactory FACT = new PackedPositionFactoryImpl();

  @Param({"HQ"}) // ← only the PR implementation
  private String impl; // which generator to test

  private MoveGenerator gen;

  private long[][] roots; // all start positions (bitboards)
  private int[] depths; // matching perft depth for each root

  /* ---------- load everything once per fork ---------- */
  @Setup(Level.Trial)
  public void init() throws Exception {

    gen = new MoveGeneratorHQ(); // only one choice now
    int maxCases = 10;

    List<long[]> posTmp = new ArrayList<>();
    List<Integer> depthTmp = new ArrayList<>();

    try (var is = getClass().getResourceAsStream("/perft/qbb.txt");
        var br = new BufferedReader(new InputStreamReader(is))) {

      br.lines()
          .map(String::trim)
          .filter(l -> !(l.isEmpty() || l.startsWith("#")))
          .limit(maxCases) // NEW — apply limit
          .forEach(
              l -> {
                String[] p = l.split(";");
                String fen = p[0].trim();
                int d = Integer.parseInt(p[1].replaceAll("[^0-9]", ""));
                posTmp.add(FACT.toBitboards(new FenPos(fen)));
                depthTmp.add(d);
              });
    }

    roots = posTmp.toArray(long[][]::new);
    depths = depthTmp.stream().mapToInt(Integer::intValue).toArray();
  }

  /* ---------- benchmark ---------- */
  @Benchmark
  public long totalNodes() {
    long sum = 0;

    for (int i = 0; i < roots.length; i++) {
      sum += perft(roots[i], depths[i], gen); // ← 3 parameters only
    }
    return sum; // keep JVM from DCE-ing the loop
  }

  private static long perft(long[] pos, int d, MoveGenerator g) {
    if (d == 0) return 1L;

    int[] moves = new int[256]; // fresh every level
    int cnt = g.generate(pos, moves, MoveGenerator.GenMode.ALL);

    long nodes = 0;
    for (int i = 0; i < cnt; i++) {
      long[] child = pos.clone();
      if (FACT.makeLegalMoveInPlace(child, moves[i], g)) nodes += perft(child, d - 1, g);
    }
    return nodes;
  }

  /* tiny Position wrapper – only FEN is needed */
  private record FenPos(String fen) implements Position {
    public String toFen() {
      return fen;
    }

    public boolean whiteToMove() {
      return false;
    }

    public int halfmoveClock() {
      return 0;
    }

    public int fullmoveNumber() {
      return 1;
    }

    public int castlingRights() {
      return 0;
    }

    public int enPassantSquare() {
      return -1;
    }
  }
}
