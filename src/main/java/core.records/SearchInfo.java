package core.records;

import java.util.List;

/**
 * Immutable snapshot of a *single* “info” line that the searcher feeds to
 * a {@link core.contracts.InfoHandler}.
 *
 * <p>The object is intentionally **flat and final** so UCI front-ends
 * (CuteChess, Arena, Banksia, …) or test harnesses can consume it without any
 * parsing of text streams.</p>
 *
 * @param depth           Principal depth (in plies) reached so far.
 * @param selDepth        Maximum selective depth (extensions, checks, etc.).
 * @param multiPvIdx      1-based index when {@code multiPV &gt; 1}.
 * @param scoreCp         Evaluation in centipawns from the root mover’s
 *                        perspective, or <i>(MateValue ± ply)</i> when a mate
 *                        is found.  **No unit conversion** is performed.
 * @param nodes           Total nodes searched so far.
 * @param nps             Average speed in nodes per second.
 * @param timeMs          Wall-clock time elapsed (milliseconds).
 * @param pv              Principal variation as a list of encoded moves.
 * @param hashFullPermil  Transposition-table saturation in ‰
 *                        (0 – 1000, −1 if unknown).
 * @param tbHits          Number of Syzygy WDL/DTZ probes done so far.
 */
public record SearchInfo(
        int           depth,
        int           selDepth,
        int           multiPvIdx,
        int           scoreCp,
        long          nodes,
        long          nps,
        long          timeMs,
        List<Integer> pv,
        /* new fields */
        int           hashFullPermil,
        long          tbHits
) {}
