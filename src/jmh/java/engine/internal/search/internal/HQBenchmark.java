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

/** Compare MoveGeneratorHQ on a bundle of small perft positions.
 *  Fixed to always load only the first 10 test‑cases and report *nodes per second* via JMH AuxCounters. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class HQBenchmark {

  private static final PackedPositionFactory FACT = new PackedPositionFactoryImpl();
  private static final int MAX_CASES = 10;        // benchmark size is fixed

  @Param({"HQ"})
  private String impl;                            // keeps the param machinery active

  private MoveGenerator gen;                      // generator under test

  private long[][] roots;                         // start positions (bitboards)
  private int[]    depths;                        // per‑position depths

  /* ───────── auxiliary counter: shows nodes/sec directly in JMH output ───────── */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Metrics {
    public long nodes;        // incremented by the benchmark method
  }

  /* ---------- load everything once per fork ---------- */
  @Setup(Level.Trial)
  public void init() throws Exception {

    gen = new MoveGeneratorHQ();

    List<long[]>  posTmp   = new ArrayList<>();
    List<Integer> depthTmp = new ArrayList<>();

    try (var is = getClass().getResourceAsStream("/perft/qbb.txt");
         var br = new BufferedReader(new InputStreamReader(is))) {

      br.lines()
              .map(String::trim)
              .filter(l -> !(l.isEmpty() || l.startsWith("#")))
              .limit(MAX_CASES)
              .forEach(l -> {
                String[] p = l.split(";");
                String   fen = p[0].trim();
                int      d   = Integer.parseInt(p[1].replaceAll("[^0-9]", ""));
                posTmp.add(FACT.toBitboards(new FenPos(fen)));
                depthTmp.add(d);
              });
    }

    roots  = posTmp.toArray(long[][]::new);
    depths = depthTmp.stream().mapToInt(Integer::intValue).toArray();
  }

  /* ---------- benchmark: counts nodes then updates the metric ---------- */
  @Benchmark
  public void perftNodes(Metrics m) {
    long nodes = 0;
    for (int i = 0; i < roots.length; i++) {
      nodes += perft(roots[i], depths[i], gen);
    }
    m.nodes += nodes; // report to JMH
  }

  /* depth‑limited, allocation‑light perft implementation */
  private static long perft(long[] pos, int d, MoveGenerator g) {
    if (d == 0) return 1L;

    int[] moves = new int[256];
    int   cnt   = g.generate(pos, moves, MoveGenerator.GenMode.ALL);

    long nodes = 0;
    for (int i = 0; i < cnt; i++) {
      long[] child = pos.clone();
      if (FACT.makeLegalMoveInPlace(child, moves[i], g)) {
        nodes += perft(child, d - 1, g);
      }
    }
    return nodes;
  }

  /* tiny Position wrapper – only FEN is needed */
  private record FenPos(String fen) implements Position {
    @Override public String toFen()          { return fen; }
    @Override public boolean whiteToMove()   { return false; }
    @Override public int       halfmoveClock()   { return 0; }
    @Override public int       fullmoveNumber()  { return 1; }
    @Override public int       castlingRights()  { return 0; }
    @Override public int       enPassantSquare() { return -1; }
  }
}
