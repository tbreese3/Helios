// C:/dev/Helios/src/main/java/core/impl/MovePicker.java
package core.impl;

import core.contracts.MoveGenerator;
import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;

/**
 * A move iterator that provides moves in stages to improve search efficiency.
 * It follows the order: TT Move, Good Captures, Killers, Quiets, Bad Captures.
 * This allows the search to prune branches early without generating all moves.
 */
final class MovePicker {

    private enum Stage {
        TT_MOVE,
        CAPTURES,
        KILLER_1,
        KILLER_2,
        QUIETS,
        DONE
    }

    private final long[] bb;
    private final MoveGenerator mg;
    private final MoveOrderer orderer;
    private final int ttMove;
    private final int[] killers;
    private final boolean quiescent;

    private Stage stage;
    private final int[] moveList = new int[256];
    private int count = 0;
    private int index = 0;
    private boolean tactical;

    MovePicker(long[] bb, MoveGenerator mg, MoveOrderer orderer, int ttMove, int[] killers, boolean quiescent) {
        this.bb = bb;
        this.mg = mg;
        this.orderer = orderer;
        this.ttMove = ttMove;
        this.killers = killers;
        this.quiescent = quiescent;
        this.stage = quiescent ? Stage.CAPTURES : Stage.TT_MOVE; // Q-search starts with captures
    }

    /**
     * @return The next move to search, or 0 if no moves remain.
     */
    int next() {
        while (true) {
            // If there are moves left in the current list, return the next one.
            if (index < count) {
                int move = moveList[index++];
                // Skip moves that might have been processed in a prior stage.
                if (move == ttMove || (stage == Stage.QUIETS && (move == killers[0] || move == killers[1]))) {
                    continue;
                }
                return move;
            }

            // Current list is exhausted, advance to the next stage.
            count = index = 0;

            switch (stage) {
                case TT_MOVE:
                    stage = Stage.CAPTURES;
                    if (ttMove != 0) {
                        tactical = isCaptureOrPromotion(bb, ttMove);
                        return ttMove;
                    }
                    // Fall-through if no TT move

                case CAPTURES:
                    tactical = true;
                    count = mg.generateCaptures(bb, moveList, 0);
                    if (quiescent) {
                        count = orderer.seePrune(bb, moveList, count);
                    }
                    orderer.orderMoves(bb, moveList, count, 0, null);
                    stage = quiescent ? Stage.DONE : Stage.KILLER_1;
                    break; // Re-enter loop to start processing the new list

                case KILLER_1:
                    tactical = false;
                    stage = Stage.KILLER_2;
                    if (killers[0] != 0 && killers[0] != ttMove && isNotCapture(bb, killers[0])) {
                        return killers[0];
                    }
                    // Fall-through

                case KILLER_2:
                    stage = Stage.QUIETS;
                    if (killers[1] != 0 && killers[1] != ttMove && killers[1] != killers[0] && isNotCapture(bb, killers[1])) {
                        return killers[1];
                    }
                    // Fall-through

                case QUIETS:
                    tactical = false;
                    count = mg.generateQuiets(bb, moveList, 0);
                    // Killers are not passed here because they've already been handled.
                    orderer.orderMoves(bb, moveList, count, 0, null);
                    stage = Stage.DONE;
                    break;

                case DONE:
                    return 0;
            }
        }
    }

    boolean isTactical() {
        return tactical;
    }

    private static boolean isCaptureOrPromotion(long[] bb, int move) {
        if (((move >>> 14) & 0x3) == 1) return true; // Promotion is tactical
        return !isNotCapture(bb, move);
    }

    private static boolean isNotCapture(long[] bb, int move) {
        long toBit = 1L << (move & 0x3F);
        boolean whiteToMove = PositionFactory.whiteToMove(bb[PositionFactory.META]);
        long enemyPieces = whiteToMove
                ? (bb[PositionFactory.BP] | bb[PositionFactory.BN] | bb[PositionFactory.BB] | bb[PositionFactory.BR] | bb[PositionFactory.BQ])
                : (bb[PositionFactory.WP] | bb[PositionFactory.WN] | bb[PositionFactory.WB] | bb[PositionFactory.WR] | bb[PositionFactory.WQ]);
        return (enemyPieces & toBit) == 0;
    }
}