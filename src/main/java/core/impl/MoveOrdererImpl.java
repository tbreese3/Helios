package core.impl;

import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;

import java.util.Arrays; // Import Arrays for sorting

import static core.contracts.PositionFactory.META;

/**
 * An improved, reusable move orderer that sorts a move list in-place.
 * This version uses a faster sorting algorithm for improved performance.
 */
public final class MoveOrdererImpl implements MoveOrderer {

    // --- Score Constants (unchanged) ---
    private static final int SCORE_TT_MOVE = 100_000;
    private static final int SCORE_QUEEN_PROMO = 90_000;
    private static final int SCORE_CAPTURE_BASE = 80_000;
    private static final int SCORE_KILLER = 75_000;
    private static final int SCORE_UNDER_PROMO = 70_000;

    // --- Piece Values for SEE (unchanged) ---
    private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 10000}; // P,N,B,R,Q,K

    // --- Scratch Buffers ---
    // A new buffer to hold packed score-and-move longs for fast sorting.
    private final long[] packedScores = new long[256];
    private final int[][] history;

    public MoveOrdererImpl(int[][] history) {
        this.history = history;
    }

    @Override
    public void orderMoves(long[] bb, int[] moves, int count, int ttMove, int[] killers) {
        boolean whiteToMove = PositionFactory.whiteToMove(bb[META]);

        // 1. Score every move and pack it with its score into a long.
        for (int i = 0; i < count; i++) {
            int move = moves[i];
            int score;

            if (move == ttMove) {
                score = SCORE_TT_MOVE;
            } else {
                int moveType = (move >>> 14) & 0x3;
                int toSquare = move & 0x3F;
                int fromSquare = (move >>> 6) & 0x3F;

                if (moveType == 1) { // Promotion
                    int promoType = (move >>> 12) & 0x3;
                    score = (promoType == 3) ? SCORE_QUEEN_PROMO : SCORE_UNDER_PROMO;
                } else {
                    int capturedPieceType = getCapturedPieceType(bb, toSquare, whiteToMove);
                    if (capturedPieceType != -1) { // Capture
                        score = SCORE_CAPTURE_BASE + see(bb, move);
                    } else { // Quiet move
                        if (killers != null && move == killers[0]) {
                            score = SCORE_KILLER;
                        } else if (killers != null && move == killers[1]) {
                            score = SCORE_KILLER - 1;
                        } else {
                            score = history[fromSquare][toSquare]; // Raw history score
                        }
                    }
                }
            }
            // Pack the score (upper 32 bits) and move (lower 32 bits) into a single long.
            packedScores[i] = ((long) score << 32) | (move & 0xFFFFFFFFL);
        }

        // 2. Sort the packed longs. This is much faster than insertion sort.
        // We only sort the portion of the array that contains moves.
        Arrays.sort(packedScores, 0, count);

        // 3. Unpack the sorted moves back into the original array in descending order of score.
        for (int i = 0; i < count; i++) {
            // packedScores is sorted ascending, so we read it from the end
            // to get the moves with the highest scores first.
            moves[i] = (int) packedScores[count - 1 - i];
        }
    }

    // ... rest of the file (see, seePrune, helpers) is unchanged ...

    @Override
    public int seePrune(long[] bb, int[] moves, int count) {
        int goodMovesCount = 0;
        for (int i = 0; i < count; i++) {
            if (see(bb, moves[i]) >= 0) {
                moves[goodMovesCount++] = moves[i];
            }
        }
        return goodMovesCount;
    }

    @Override
    public int see(long[] bb, int move) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moverType = getMoverPieceType(move);
        boolean initialStm = PositionFactory.whiteToMove(bb[META]);

        int victimType = getCapturedPieceType(bb, to, initialStm);
        if (victimType == -1) {
            if (((move >>> 14) & 0x3) == 2) victimType = 0; // En passant captures a pawn
            else return 0; // Not a capture
        }

        int[] gain = new int[32];
        int d = 0;
        gain[d] = PIECE_VALUES[victimType];

        long occ = (bb[PositionFactory.WP] | bb[PositionFactory.WN] | bb[PositionFactory.WB] | bb[PositionFactory.WR] | bb[PositionFactory.WQ] | bb[PositionFactory.WK] |
                bb[PositionFactory.BP] | bb[PositionFactory.BN] | bb[PositionFactory.BB] | bb[PositionFactory.BR] | bb[PositionFactory.BQ] | bb[PositionFactory.BK]);

        occ ^= (1L << from); // Remove the first attacker
        boolean stm = !initialStm;

        while (true) {
            d++;
            gain[d] = PIECE_VALUES[moverType] - gain[d - 1];

            long[] outAttackerBit = new long[1];
            moverType = getLeastValuableAttacker(bb, to, stm, occ, outAttackerBit);

            if (moverType == -1) break;

            occ ^= outAttackerBit[0]; // Remove the next attacker
            stm = !stm;
        }

        // Negamax unrolling of the capture sequence
        while (--d > 0) {
            gain[d - 1] = -Math.max(-gain[d - 1], gain[d]);
        }
        return gain[0];
    }

    private int getMoverPieceType(int move) {
        return ((move >>> 16) & 0xF) % 6;
    }

    private int getLeastValuableAttacker(long[] bb, int to, boolean stm, long occ, long[] outAttackerBit) {
        long toBB = 1L << to;
        long attackers;

        // Pawns
        if (stm) { // White attackers
            attackers = ((toBB & ~0x8080808080808080L) >>> 7 | (toBB & ~0x0101010101010101L) >>> 9) & bb[PositionFactory.WP] & occ;
        } else { // Black attackers
            attackers = ((toBB & ~0x0101010101010101L) << 7 | (toBB & ~0x8080808080808080L) << 9) & bb[PositionFactory.BP] & occ;
        }
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 0;
        }

        // Knights
        attackers = PreCompMoveGenTables.KNIGHT_ATK[to] & bb[stm ? PositionFactory.WN : PositionFactory.BN] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 1;
        }

        // Bishops
        attackers = MoveGeneratorImpl.bishopAtt(occ, to) & bb[stm ? PositionFactory.WB : PositionFactory.BB] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 2;
        }

        // Rooks
        attackers = MoveGeneratorImpl.rookAtt(occ, to) & bb[stm ? PositionFactory.WR : PositionFactory.BR] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 3;
        }

        // Queens
        attackers = MoveGeneratorImpl.queenAtt(occ, to) & bb[stm ? PositionFactory.WQ : PositionFactory.BQ] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 4;
        }

        // King
        attackers = PreCompMoveGenTables.KING_ATK[to] & bb[stm ? PositionFactory.WK : PositionFactory.BK] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 5;
        }

        outAttackerBit[0] = 0L;
        return -1;
    }

    private int getCapturedPieceType(long[] bb, int toSquare, boolean whiteToMove) {
        long toBit = 1L << toSquare;
        int start = whiteToMove ? PositionFactory.BP : PositionFactory.WP;
        int end = whiteToMove ? PositionFactory.BK : PositionFactory.WK;

        for (int pieceType = start; pieceType <= end; pieceType++) {
            if ((bb[pieceType] & toBit) != 0) {
                return pieceType % 6; // Return 0-5
            }
        }
        // Check for en-passant capture
        if (toSquare == (int)PositionFactory.epSquare(bb[META])) {
            int mover = whiteToMove ? PositionFactory.WP : PositionFactory.BP;
            int fromSquare = -1;
            long fromL = (1L << (toSquare + (whiteToMove ? -7 : 7)));
            long fromR = (1L << (toSquare + (whiteToMove ? -9 : 9)));

            if((bb[mover] & fromL) != 0 && (Math.abs(((toSquare + (whiteToMove ? -7 : 7)) % 8) - toSquare % 8) == 1)) fromSquare = 1;
            if((bb[mover] & fromR) != 0 && (Math.abs(((toSquare + (whiteToMove ? -9 : 9)) % 8) - toSquare % 8) == 1)) fromSquare = 1;

            if (fromSquare != -1) {
                return 0; // It's an en-passant, capturing a pawn
            }
        }

        return -1; // Not a capture
    }
}