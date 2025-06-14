package core.records;

import java.util.List;

/**
 * Final result produced when a search completes normally or is stopped.
 *
 * <p>All fields are immutable so callers can freely cache or forward
 * the object across threads.</p>
 *
 * @param bestMove   Engine-chosen move to play (0 ⇒ none legal).
 * @param ponderMove Expected reply by the opponent (0 ⇒ unknown).
 * @param pv         Full principal variation starting with {@code bestMove}.
 * @param scoreCp    Centipawn score, or <i>(MateValue ± ply)</i>
 *                   if {@code mateFound} is {@code true}.
 * @param mateFound  Indicates the score encodes a forced mate.
 * @param depth      Principal depth actually completed.
 * @param nodes      Total nodes searched.
 * @param timeMs     Wall-clock time consumed by the search.
 */
public record SearchResult(
        int              bestMove,
        int              ponderMove,
        List<Integer>    pv,
        int              scoreCp,
        boolean          mateFound,
        int              depth,
        long             nodes,
        long             timeMs
) {}
