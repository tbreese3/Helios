package core.records;

import java.util.Collections;
import java.util.List;

/**
 * Immutable set of <em>search limits / directives</em>.
 *
 * Use the nested {@link Builder} to construct an instance of this record.
 *
 * @param depth            Fixed search depth in plies.
 * @param nodes            Hard node budget (0 = unlimited).
 * @param moveTimeMs       Exact move-time (0 = unused).
 * @param wTimeMs          White’s remaining clock time.
 * @param wIncMs           White’s increment per move.
 * @param bTimeMs          Black’s remaining clock time.
 * @param bIncMs           Black’s increment per move.
 * @param movesToGo        Moves until the next time control (0 = unknown).
 * @param infinite         Run until {@code stop()} – <i>ignores</i> other limits.
 * @param ponder           {@code true} ⇢ GUI clock still ticking.
 * @param history          List of Zobrist keys from previous positions in the game.
 */
public record SearchSpec(
        int   depth,
        long  nodes,
        long  moveTimeMs,
        long  wTimeMs, long wIncMs,
        long  bTimeMs, long bIncMs,
        int   movesToGo,
        boolean infinite,
        boolean ponder,
        List<Long> history
) {
    /**
     * A builder for creating {@link SearchSpec} instances. This provides a fluent API
     * for setting search parameters and is more readable than a large constructor.
     */
    public static class Builder {
        private int depth = 0;
        private long nodes = 0;
        private long moveTimeMs = 0;
        private long wTimeMs = 0;
        private long wIncMs = 0;
        private long bTimeMs = 0;
        private long bIncMs = 0;
        private int movesToGo = 0;
        private boolean infinite = false;
        private boolean ponder = false;
        private List<Long> history = Collections.emptyList();

        public Builder depth(int depth) { this.depth = depth; return this; }
        public Builder nodes(long nodes) { this.nodes = nodes; return this; }
        public Builder moveTimeMs(long moveTimeMs) { this.moveTimeMs = moveTimeMs; return this; }
        public Builder wTimeMs(long wTimeMs) { this.wTimeMs = wTimeMs; return this; }
        public Builder wIncMs(long wIncMs) { this.wIncMs = wIncMs; return this; }
        public Builder bTimeMs(long bTimeMs) { this.bTimeMs = bTimeMs; return this; }
        public Builder bIncMs(long bIncMs) { this.bIncMs = bIncMs; return this; }
        public Builder movesToGo(int movesToGo) { this.movesToGo = movesToGo; return this; }
        public Builder infinite(boolean infinite) { this.infinite = infinite; return this; }
        public Builder ponder(boolean ponder) { this.ponder = ponder; return this; }
        public Builder history(List<Long> history) { this.history = history; return this; }

        public SearchSpec build() {
            return new SearchSpec(depth, nodes, moveTimeMs, wTimeMs, wIncMs, bTimeMs, bIncMs,
                    movesToGo, infinite, ponder, history);
        }
    }
}