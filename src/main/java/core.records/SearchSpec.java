package core.records;

import java.util.List;

/**
 * Immutable set of <em>search limits / directives</em>.
 *
 * <p>Leave a field at its “zero” value to disable that constraint.
 * The driver follows typical Stockfish precedence:
 * <ol>
 *   <li>{@code moveTimeMs} overrides {@code depth} and {@code nodes}.</li>
 *   <li>Ponder searches use a slower time-manager.</li>
 *   <li>When both clock times and {@code depth} are given, the clock wins.</li>
 * </ol></p>
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
 * @param multiPV          Number of principal variations to keep/report (≥ 1).
 * @param ponder           {@code true} ⇢ GUI clock still ticking
 *                         (time-manager stays conservative).
 * @param matePlies        Restrict root to lines that deliver mate ≤ N plies
 *                         (implements UCI “mate N”).
 * @param searchMoves      Subset of legal root moves to explore
 *                         (null/empty ⇢ explore all).
 * @param hashLimitPermil  Stop when TT saturation ≥ X ‰ (0–1000, 0 = ignore).
 * @param useSyzygy        Probe Syzygy WDL/DTZ tablebases when available.
 */
public record SearchSpec(
        /* classic limits */
        int   depth,
        long  nodes,
        long  moveTimeMs,

        /* incremental clock */
        long  wTimeMs, long wIncMs,
        long  bTimeMs, long bIncMs,
        int   movesToGo,

        /* UCI flags */
        boolean infinite,
        int   multiPV,
        boolean ponder,
        int   matePlies,
        List<Integer> searchMoves,

        /* optional engine hints */
        int   hashLimitPermil,
        boolean useSyzygy
) {}
