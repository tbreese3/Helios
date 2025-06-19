package core.records;

import java.util.List;

/**
 * Immutable snapshot of a *single* “info” line that the searcher feeds to
 * a {@link core.contracts.InfoHandler}.
 *
 * @param depth           Principal depth (in plies) reached so far.
 * @param selDepth        Maximum selective depth (extensions, checks, etc.).
 * @param multiPvIdx      1-based index when {@code multiPV > 1}.
 * @param scoreCp         Evaluation in centipawns from the root mover’s
 * perspective, or <i>(MateValue ± ply)</i>.
 * @param isMate          True if the score represents a forced mate.
 * @param nodes           Total nodes searched so far.
 * @param nps             Average speed in nodes per second.
 * @param timeMs          Wall-clock time elapsed (milliseconds).
 * @param pv              Principal variation as a list of encoded moves.
 * @param hashFullPermil  Transposition-table saturation in ‰.
 * @param tbHits          Number of Syzygy WDL/DTZ probes done so far.
 */
public record SearchInfo(
        int           depth,
        int           selDepth,
        int           multiPvIdx,
        int           scoreCp,
        boolean       isMate,
        long          nodes,
        long          nps,
        long          timeMs,
        List<Integer> pv,
        int           hashFullPermil,
        long          tbHits
) {}