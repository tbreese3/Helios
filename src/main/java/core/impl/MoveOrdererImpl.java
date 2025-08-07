package core.impl;

import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;
import core.contracts.PositionFactory;

import static core.contracts.PositionFactory.META;

/**
 * An improved, reusable move orderer that sorts a move list in-place.
 * It uses a fast Quicksort algorithm to order moves based on a refined scoring system.
 */
public final class MoveOrdererImpl implements MoveOrderer {

    // --- Score Constants ---
    private static final int SCORE_TT_MOVE = 100_000;
    private static final int SCORE_QUEEN_PROMO = 90_000;
    private static final int SCORE_CAPTURE_BASE = 80_000;
    private static final int SCORE_KILLER = 75_000;
    private static final int SCORE_UNDER_PROMO = 70_000;

    // --- Piece Values for SEE ---
    private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 10000}; // P,N,B,R,Q,K

    // --- Scratch Buffers ---
    private final int[] moveScores = new int[256]; // Assumes max 256 moves
    private final int[][] history;

    public MoveOrdererImpl(int[][] history) {
        this.history = history;
    }

    @Override
    public void orderMoves(long[] bb, int[] moves, int count, int ttMove, int[] killers) {
        boolean whiteToMove = PositionFactory.whiteToMove(bb[META]);

        // 1. Score every move
        for (int i = 0; i < count; i++) {
            int move = moves[i];
            if (move == ttMove) {
                moveScores[i] = SCORE_TT_MOVE;
                continue;
            }

            int moveType = (move >>> 14) & 0x3;
            int toSquare = move & 0x3F;
            int fromSquare = (move >>> 6) & 0x3F;

            // BUG FIX: Correctly identify and score en passant as a capture.
            if (moveType == 2) { // En Passant
                moveScores[i] = SCORE_CAPTURE_BASE + see(bb, move);
                continue;
            }

            if (moveType == 1) { // Promotion
                int promoType = (move >>> 12) & 0x3;
                moveScores[i] = (promoType == 3) ? SCORE_QUEEN_PROMO : SCORE_UNDER_PROMO;
                // Add capture bonus to promotions that are also captures
                if (getCapturedPieceType(bb, toSquare, whiteToMove) != -1) {
                    moveScores[i] += SCORE_CAPTURE_BASE;
                }
            } else {
                int capturedPieceType = getCapturedPieceType(bb, toSquare, whiteToMove);
                if (capturedPieceType != -1) { // Normal Capture
                    moveScores[i] = SCORE_CAPTURE_BASE + see(bb, move);
                } else { // Quiet move
                    int score = 0;
                    if (killers != null) {
                        if (move == killers[0]) score = SCORE_KILLER;
                        else if (move == killers[1]) score = SCORE_KILLER - 1;
                    }
                    if (score == 0) {
                        score = history[fromSquare][toSquare]; // Raw history score
                    }
                    moveScores[i] = score;
                }
            }
        }

        // 2. Sort the move list in-place using a fast Quicksort algorithm
        if (count > 1) {
            quickSort(moves, moveScores, 0, count - 1);
        }
    }

    /**
     * Sorts the moves and scores arrays in-place using Quicksort (descending).
     */
    private void quickSort(int[] moves, int[] scores, int low, int high) {
        if (low < high) {
            int pi = partition(moves, scores, low, high);
            quickSort(moves, scores, low, pi - 1);
            quickSort(moves, scores, pi + 1, high);
        }
    }

    /**
     * Lomuto partition scheme for Quicksort. Partitions arrays around a pivot.
     */
    private int partition(int[] moves, int[] scores, int low, int high) {
        int pivotScore = scores[high];
        int i = (low - 1); // Index of smaller element

        for (int j = low; j < high; j++) {
            // For descending sort, if current score is greater than or equal to pivot
            if (scores[j] >= pivotScore) {
                i++;
                swap(moves, scores, i, j);
            }
        }
        swap(moves, scores, i + 1, high);
        return i + 1;
    }

    /**
     * Swaps elements at two indices in both the moves and scores arrays.
     */
    private void swap(int[] moves, int[] scores, int i, int j) {
        int tempScore = scores[i];
        scores[i] = scores[j];
        scores[j] = tempScore;

        int tempMove = moves[i];
        moves[i] = moves[j];
        moves[j] = tempMove;
    }

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

        // The original EP check was buggy and is now handled by checking the move type directly.
        // This is left here for the SEE logic which doesn't have the move type readily available.
        if (toSquare == (int)PositionFactory.epSquare(bb[META])) {
            return 0; // Assume it's an EP capture if the 'to' square is the EP square.
        }

        return -1; // Not a capture
    }
}