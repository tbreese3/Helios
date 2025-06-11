package core;

import core.contracts.MoveGenerator;
import core.contracts.PositionFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Throughput benchmark that performs exactly the same perft
 * as {@link MoveGeneratorPerftTest}, but under the JMH harness.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class HQBenchmark {

  /* ── engine wiring ─────────────────────────────────────────── */
  private static final PositionFactory FACT = new PositionFactoryImpl();
  private static final MoveGenerator  GEN  = new MoveGeneratorImpl();

  /* scratch buffers (no GC) ------------------------------------ */
  private static final int MAX_PLY  = 64;
  private static final int LIST_CAP = 256;
  private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

  /* per-ft cases loaded once per fork -------------------------- */
  private record Case(long[] root, boolean moverIsWhite, int depth) {}
  private List<Case> cases;

  /* simple node counter so JMH can report throughput ----------- */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Metrics { public long nodes; }

  /* ── load /perft/qbb.txt at trial start ─────────────────────- */
  @Setup(Level.Trial)
  public void init() throws Exception {
    cases = new ArrayList<>();

    try (var is = getClass().getResourceAsStream("/perft/qbb.txt");
         var br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {

      br.lines()
              .map(String::trim)
              .filter(l -> !(l.isEmpty() || l.startsWith("#")))
              .forEach(l -> {
                String[] p = l.split(";");
                String fen   = p[0].trim();
                int    depth = Integer.parseInt(p[1].replaceAll("[^0-9]", ""));
                long[] root  = FACT.fromFen(fen);
                boolean white = fen.split("\\s+")[1].charAt(0) == 'w';
                cases.add(new Case(root, white, depth));
              });
    }
    if (cases.isEmpty())
      throw new IllegalStateException("no perft vectors found");
  }

  /* ── benchmark body ------------------------------------------ */
  @Benchmark
  public void perftNodes(Metrics m) {
    long total = 0;
    for (Case c : cases)
      total += perft(c.root.clone(), c.moverIsWhite, c.depth, 0);
    m.nodes += total;
  }

  /* =============================================================
   *  Recursive perft identical to MoveGeneratorPerftTest
   * ============================================================= */
  private static long perft(long[] bb, boolean moverIsWhite, int depth, int ply) {
    if (depth == 0) return 1;

    int[] moves = MOVES[ply];
    int cnt;

    /* 1) choose which move list to generate */
    if (GEN.kingAttacked(bb, moverIsWhite)) {
      cnt = GEN.generateEvasions(bb, moves, 0);
    } else {
      cnt = GEN.generateCaptures(bb, moves, 0);   // noisy first
      cnt = GEN.generateQuiets  (bb, moves, cnt); // quiets appended
    }

    long nodes = 0;

    /* 2) depth-first search over that list */
    for (int i = 0; i < cnt; ++i) {
      if (!FACT.makeMoveInPlace(bb, moves[i], GEN)) continue; // illegal
      nodes += perft(bb, !moverIsWhite, depth - 1, ply + 1);
      FACT.undoMoveInPlace(bb);                                // restore
    }
    return nodes;
  }
}
