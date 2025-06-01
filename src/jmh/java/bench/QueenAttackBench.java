package bench;

import engine.internal.search.internal.MoveGeneratorHQ;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/** Micro-benchmark: PEXT vs. magic-multiplication */
@BenchmarkMode(Mode.Throughput)            // higher = better
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5,  time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgs = {
        "--add-modules", "jdk.incubator.vector",
        "-Dengine.usePext=true",           // flip on if you want to force PEXT
        "-XX:+UseBMI2Instructions"
})
public class QueenAttackBench {

    /** Random but *stable* test data so the two methods see identical inputs. */
    @State(Scope.Thread)
    public static class TestData {
        // 64 random occupancies, one per square, generated once per thread
        long[] occ = new long[64];

        @Setup(Level.Trial)
        public void init() {
            RandomGenerator rng = RandomGenerator.getDefault();
            for (int i = 0; i < 64; i++) {
                // keep all pawns set but only some pieces to mimic “average” middlegame density
                long bits = rng.nextLong();
                long pawns = 0x00FF_0000FF00L;            // ranks 2 & 7
                long sparse = bits & 0x7E7E_7E7E_7E7E_7E7EL;
                occ[i] = pawns | sparse;
            }
        }
    }

    @Benchmark
    public long magic(TestData td) {
        long sum = 0;
        for (int sq = 0; sq < 64; sq++) {
            sum += MoveGeneratorHQ.queenAttMagic(td.occ[sq], sq);
        }
        return sum;
    }

    @Benchmark
    public long pext(TestData td) {
        long sum = 0;
        for (int sq = 0; sq < 64; sq++) {
            sum += MoveGeneratorHQ.queenAttPext(sq, td.occ[sq]);
        }
        return sum;
    }
}
