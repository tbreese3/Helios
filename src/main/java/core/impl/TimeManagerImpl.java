// C:\dev\Helios\src\main\java\core\impl\TimeManagerImpl.java
package core.impl;

import core.contracts.TimeManager;
import core.contracts.UciOptions;
import core.records.RootMove;
import core.records.SearchSpec;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static core.constants.CoreConstants.SCORE_INF;
import static core.constants.CoreConstants.SCORE_MATE_IN_MAX_PLY;

public class TimeManagerImpl implements TimeManager {
    private static final int TIMER_BUFFER = 50;
    private static final int MOVE_TIME_BUFFER = 5;
    private static final int DEFAULT_MOVES_TO_GO = 20;
    private static final double[] STABILITY_COEFFICIENTS = {2.2, 1.6, 1.4, 1.1, 1.0, 0.95, 0.9};
    private static final int STABILITY_MAX = STABILITY_COEFFICIENTS.length - 1;

    private final long searchStartTime;
    private final boolean hasMoveTime;
    private final long hardTimeLimit;
    private final double softTimeLimit;
    private final boolean hasSoftTime;

    public TimeManagerImpl(UciOptions options, SearchSpec spec, boolean isWhiteToMove) {
        this.searchStartTime = System.currentTimeMillis();
        int moveOverhead = getMoveOverhead(options);

        if (spec.moveTimeMs() > 0) {
            this.hasMoveTime = true;
            this.hardTimeLimit = spec.moveTimeMs() - MOVE_TIME_BUFFER;
            this.softTimeLimit = -1;
            this.hasSoftTime = false;
            return;
        }

        if (spec.infinite()) {
            this.hasMoveTime = false;
            this.hardTimeLimit = Long.MAX_VALUE / 2;
            this.softTimeLimit = -1;
            this.hasSoftTime = false;
            return;
        }

        this.hasMoveTime = false;
        long playerTime = isWhiteToMove ? spec.wTimeMs() : spec.bTimeMs();
        long playerInc = isWhiteToMove ? spec.wIncMs() : spec.bIncMs();
        int movesToGo = (spec.movesToGo() > 0) ? spec.movesToGo() : DEFAULT_MOVES_TO_GO;

        long allocatedTime = playerInc + Math.max(playerTime / 2, playerTime / movesToGo);
        this.hardTimeLimit = Math.max(1, Math.min(allocatedTime, playerTime) - moveOverhead - TIMER_BUFFER);
        this.softTimeLimit = 0.65 * ((double) playerTime / movesToGo + (double) playerInc * 3 / 4);
        this.hasSoftTime = this.softTimeLimit > 0;
    }

    @Override
    public boolean isTimeUp(List<RootMove> rootMoves, int rootDepth, int stability, List<Integer> scoreHistory, AtomicLong totalNodes, long[][] nodeTable) {
        long elapsed = System.currentTimeMillis() - searchStartTime;

        if (elapsed >= hardTimeLimit) return true;
        if (!hasSoftTime || rootDepth <= 7) return false;

        double multFactor = 1.0;
        if (!rootMoves.isEmpty()) {
            RootMove bestRootMove = rootMoves.get(0);
            long nodesForBestMove = nodeTable[bestRootMove.move >>> 6][bestRootMove.move & 0x3F];
            long totalNodesValue = totalNodes.get();
            if (totalNodesValue == 0) totalNodesValue = 1;

            double nodeTM = (1.5 - (double) nodesForBestMove / totalNodesValue) * 1.75;
            double bmStability = STABILITY_COEFFICIENTS[Math.min(stability, STABILITY_MAX)];
            double scoreStability = 1.0;

            if (scoreHistory.size() >= 4) {
                int currentScore = scoreHistory.get(scoreHistory.size() - 1);
                int olderScore = scoreHistory.get(scoreHistory.size() - 4);
                if (currentScore > -SCORE_INF && olderScore > -SCORE_INF && Math.abs(currentScore) < SCORE_MATE_IN_MAX_PLY && Math.abs(olderScore) < SCORE_MATE_IN_MAX_PLY) {
                    scoreStability = Math.max(0.85, Math.min(1.15, 0.034 * (olderScore - currentScore)));
                }
            }
            multFactor = nodeTM * bmStability * scoreStability;
        }

        return elapsed >= (softTimeLimit * multFactor);
    }

    private int getMoveOverhead(UciOptions options) {
        try {
            String v = options.getOptionValue("Move Overhead");
            return v != null ? Integer.parseInt(v) : 50;
        } catch (Exception e) {
            return 50;
        }
    }
}