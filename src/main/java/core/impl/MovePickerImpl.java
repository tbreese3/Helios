package core.impl;

import core.contracts.MoveGenerator;
import core.contracts.MovePicker;
import core.contracts.PositionFactory;

import java.util.Arrays;

import static core.contracts.PositionFactory.META;

/**
 * Implements the MovePicker interface with a staged approach to move selection.
 */
public final class MovePickerImpl implements MovePicker {

    // --- Injected Dependencies ---
    private final MoveGenerator moveGenerator;
    private final long[] board;

    // --- Move Lists and Scores ---
    private final int[] moves = new int[256];
    private final int[] scores = new int[256];
    private int moveCount = 0;
    private int currentIndex = 0;

    // --- Scoring Constants ---
    private static final int SCORE_TT_MOVE = 1_000_000;
    private static final int SCORE_GOOD_CAPTURE_BASE = 800_000;
    private static final int SCORE_KILLER_1 = 700_000;
    private static final int SCORE_KILLER_2 = 690_000;
    private static final int SCORE_QUIET_BASE = 0; // History scores are added to this
    private static final int SCORE_BAD_CAPTURE_BASE = -200_000;

    // Piece values for MVV-LVA and SEE
    private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 10000}; // P,N,B,R,Q,K
    private static final int[][] MVV_LVA_SCORES = new int[6][6];

    static {
        // Pre-compute MVV-LVA scores
        for (int victim = 0; victim < 6; victim++) {
            for (int attacker = 0; attacker < 6; attacker++) {
                MVV_LVA_SCORES[victim][attacker] = PIECE_VALUES[victim] - (PIECE_VALUES[attacker] / 100);
            }
        }
    }

    public MovePickerImpl(MoveGenerator moveGenerator, long[] board) {
        this.moveGenerator = moveGenerator;
        this.board = board;
    }

    @Override
    public void scoreAndPrepare(long[] bb, int ttMove, int[] killers, int[][] history, boolean capturesOnly) {
        // Step 1: Generate the appropriate set of moves
        if (capturesOnly) {
            moveCount = moveGenerator.generateCaptures(bb, this.moves, 0);
        } else {
            boolean inCheck = moveGenerator.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
            if (inCheck) {
                moveCount = moveGenerator.generateEvasions(bb, this.moves, 0);
            } else {
                int captureCount = moveGenerator.generateCaptures(bb, this.moves, 0);
                moveCount = moveGenerator.generateQuiets(bb, this.moves, captureCount);
            }
        }

        // Step 2: Score the generated moves
        boolean whiteToMove = PositionFactory.whiteToMove(bb[META]);
        for (int i = 0; i < moveCount; i++) {
            int move = this.moves[i];

            if (move == ttMove) {
                this.scores[i] = SCORE_TT_MOVE;
                continue;
            }

            int moverType = ((move >>> 16) & 0xF) % 6;
            int toSquare = move & 0x3F;
            int fromSquare = (move >>> 6) & 0x3F;
            int capturedPieceType = getCapturedPieceType(bb, toSquare, whiteToMove);

            if (capturedPieceType != -1) { // It's a capture
                if (see(bb, move) >= 0) {
                    this.scores[i] = SCORE_GOOD_CAPTURE_BASE + MVV_LVA_SCORES[capturedPieceType][moverType];
                } else {
                    this.scores[i] = SCORE_BAD_CAPTURE_BASE + MVV_LVA_SCORES[capturedPieceType][moverType];
                }
            } else { // It's a quiet move
                this.scores[i] = SCORE_QUIET_BASE;
                if (killers != null) {
                    if (move == killers[0]) {
                        this.scores[i] = SCORE_KILLER_1;
                        continue; // Skip history heuristic if it's a killer
                    }
                    if (move == killers[1]) {
                        this.scores[i] = SCORE_KILLER_2;
                        continue; // Skip history heuristic if it's a killer
                    }
                }
                if (history != null) {
                    this.scores[i] += history[fromSquare][toSquare];
                }
            }
        }
    }


    @Override
    public int nextMove() {
        if (currentIndex >= moveCount) {
            return 0; // No moves left
        }

        // Find the best-scoring move from the remaining ones
        int bestIndex = currentIndex;
        int bestScore = scores[bestIndex];
        for (int i = currentIndex + 1; i < moveCount; i++) {
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                bestIndex = i;
            }
        }

        // Swap the best move to the current position
        int tempMove = moves[currentIndex];
        moves[currentIndex] = moves[bestIndex];
        moves[bestIndex] = tempMove;

        int tempScore = scores[currentIndex];
        scores[currentIndex] = scores[bestIndex];
        scores[bestIndex] = tempScore;

        // Return the move and advance the index
        return moves[currentIndex++];
    }

    /**
     * Calculates the Static Exchange Evaluation for a move.
     * This is the exact same logic from your old MoveOrderer.
     */
    private int see(long[] bb, int move) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moverType = ((move >>> 16) & 0xF) % 6;
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
            if (Math.max(-gain[d - 1], gain[d]) < 0) break; // Optimization

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

    private int getLeastValuableAttacker(long[] bb, int to, boolean stm, long occ, long[] outAttackerBit) {
        long toBB = 1L << to;
        long attackers;
        int stmOffset = stm ? 0 : 6;

        // Pawns
        attackers = (stm ? PreCompMoveGenTables.PAWN_ATK_B[to] : PreCompMoveGenTables.PAWN_ATK_W[to]) & bb[PositionFactory.WP + stmOffset] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 0;
        }
        // Knights
        attackers = PreCompMoveGenTables.KNIGHT_ATK[to] & bb[PositionFactory.WN + stmOffset] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 1;
        }
        // Bishops
        attackers = MoveGeneratorImpl.bishopAtt(occ, to) & bb[PositionFactory.WB + stmOffset] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 2;
        }
        // Rooks
        attackers = MoveGeneratorImpl.rookAtt(occ, to) & bb[PositionFactory.WR + stmOffset] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 3;
        }
        // Queens
        attackers = MoveGeneratorImpl.queenAtt(occ, to) & bb[PositionFactory.WQ + stmOffset] & occ;
        if (attackers != 0) {
            outAttackerBit[0] = attackers & -attackers;
            return 4;
        }
        // King
        attackers = PreCompMoveGenTables.KING_ATK[to] & bb[PositionFactory.WK + stmOffset] & occ;
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
        int epSquare = (int) PositionFactory.epSquare(bb[META]);
        if (toSquare == epSquare) {
            return 0; // Pawn
        }

        return -1; // Not a capture
    }
}