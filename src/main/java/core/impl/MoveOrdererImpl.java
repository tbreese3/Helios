package core.impl;

import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;

import static core.contracts.PositionFactory.META;

/**
 * A reusable move orderer that sorts moves based on:
 * 1. Transposition Table Move
 * 2. Winning/Equal Captures (scored by SEE)
 * 3. Queen Promotions
 * 4. Killer Moves
 * 5. History Heuristic
 * 6. Under-promotions
 * 7. Losing Captures (scored by SEE)
 */
public final class MoveOrdererImpl implements MoveOrderer {

    // --- Score Constants ---
    private static final int SCORE_TT_MOVE = 200_000;         // Highest priority
    private static final int SCORE_QUEEN_PROMO = 190_000;     // Just below TT move
    private static final int SCORE_CAPTURE_BASE = 180_000;    // Base for SEE scores
    private static final int SCORE_KILLER = 170_000;          // Killers are high-priority quiet moves
    private static final int SCORE_UNDER_PROMO = 160_000;     // Lower than killers
    private static final int SCORE_QUIET_BASE = 100_000;      // Base for history scores to keep them separate

    // --- Piece Values for SEE ---
    private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 10000}; // P,N,B,R,Q,K

    // --- The MVV_LVA_SCORES table is no longer needed ---
    //-    private static final int[][] MVV_LVA_SCORES = new int[6][6];

    // --- Scratch Buffers ---
    private final int[] moveScores = new int[256]; // Assumes max 256 moves
    private final int[][] history;

    // --- The static initializer for MVV_LVA is no longer needed ---
    //-    static {
    //-        // Pre-compute MVV-LVA scores
    //-        for (int victim = 0; victim < 6; victim++) {
    //-            for (int attacker = 0; attacker < 6; attacker++) {
    //-                MVV_LVA_SCORES[victim][attacker] = PIECE_VALUES[victim] - (PIECE_VALUES[attacker] / 100);
    //-            }
    //-        }
    //-    }

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

            // Check for captures first, including en-passant
            int capturedPieceType = getCapturedPieceType(bb, toSquare, whiteToMove);
            boolean isCapture = capturedPieceType != -1 || moveType == 2; // Type 2 is En Passant

            if (isCapture) {
                // *** THE KEY CHANGE ***
                // Score captures using their Static Exchange Evaluation (SEE) value.
                // This is far more accurate than MVV-LVA.
                // Winning and equal captures get a high score. Losing captures get a lower score
                // but are still ordered above most quiet moves.
                moveScores[i] = SCORE_CAPTURE_BASE + see(bb, move);
            } else if (moveType == 1) { // Promotion (must be a quiet promotion now)
                int promoType = (move >>> 12) & 0x3;
                moveScores[i] = (promoType == 3) ? SCORE_QUEEN_PROMO : SCORE_UNDER_PROMO;
            } else { // Quiet move
                int score = 0;
                if (killers != null) {
                    if (move == killers[0]) score = SCORE_KILLER;
                    else if (move == killers[1]) score = SCORE_KILLER - 1; // Give secondary killer a slightly lower score
                }

                // If not a killer, use the history score.
                // We add a base to keep history scores distinct from capture/killer scores.
                if (score == 0) {
                    score = SCORE_QUIET_BASE + history[fromSquare][toSquare];
                }
                moveScores[i] = score;
            }
        }

        // 2. Sort the move list in-place using insertion sort
        for (int i = 1; i < count; i++) {
            int currentMove = moves[i];
            int currentScore = moveScores[i];
            int j = i - 1;

            // Shift elements to the right until the correct spot for the current move is found
            while (j >= 0 && moveScores[j] < currentScore) {
                moves[j + 1] = moves[j];
                moveScores[j + 1] = moveScores[j];
                j--;
            }
            moves[j + 1] = currentMove;
            moveScores[j + 1] = currentScore;
        }
    }

    // ... The rest of the class (see, seePrune, etc.) remains the same ...
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

    /**
     * Calculates the Static Exchange Evaluation for a move.
     * A non-negative score means the exchange is not losing material.
     */
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

    /**
     * Finds the piece type of the least valuable attacker to a square.
     *
     * @param bb             The board state.
     * @param to             The target square.
     * @param stm            The side to find an attacker for.
     * @param occ            The current board occupancy.
     * @param outAttackerBit A 1-element array to store the bitboard of the found attacker.
     * @return The piece type (0-5) of the least valuable attacker, or -1 if no attacker is found.
     */
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

    /**
     * Determines the type of piece on a given square.
     * @return The piece type (0-5 for P,N,B,R,Q,K), or -1 if the square is empty.
     */
    private int getCapturedPieceType(long[] bb, int toSquare, boolean whiteToMove) {
        long toBit = 1L << toSquare;
        int start = whiteToMove ? PositionFactory.BP : PositionFactory.WP;
        int end = whiteToMove ? PositionFactory.BK : PositionFactory.WK;

        for (int pieceType = start; pieceType <= end; pieceType++) {
            if ((bb[pieceType] & toBit) != 0) {
                return pieceType % 6; // Return 0-5
            }
        }
        return -1; // Not a capture
    }
}