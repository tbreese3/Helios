package core.impl;

import core.contracts.Evaluator;
import core.contracts.PositionFactory;

/**
 * Very small but noticeably stronger evaluation function.
 * Completely stateless, allocation-free and thread-safe.
 */
public final class EvaluatorImpl implements Evaluator {

    /* ----------------------------------------------------------------------
     *                         Static pre-computed data
     * ------------------------------------------------------------------- */

    /** Material values in centipawns, aligned with {@link PositionFactory} indices. */
    private static final int[] VALUE = {
            100, 320, 330, 500, 900, 0,   // White P, N, B, R, Q, K
            100, 320, 330, 500, 900, 0    // Black p, n, b, r, q, k
    };

    /** Bishop-pair bonus (per side). */
    private static final int BISHOP_PAIR_BONUS = 30;

    /** Pawn-structure penalties. */
    private static final int DOUBLED_PAWN_PENALTY   = 15;
    private static final int ISOLATED_PAWN_PENALTY  = 10;

    /** Central-square bonus. */
    private static final int KNIGHT_CENTER_BONUS = 10;
    private static final int ROOK_CENTER_BONUS   = 10;
    private static final int OTHER_CENTER_BONUS  = 5;

    /** Masks for the eight files (a-file = 0). */
    private static final long[] FILE_MASKS = {
            0x0101010101010101L, 0x0202020202020202L,
            0x0404040404040404L, 0x0808080808080808L,
            0x1010101010101010L, 0x2020202020202020L,
            0x4040404040404040L, 0x8080808080808080L
    };

    /** 16 central squares: c-d-e-f files on ranks 3-6 (0-based). */
    private static final long CENTER_16 =
            0x00003C3C3C3C0000L; //   0b0000000000000000001111001111001111000000000000000000000000000000

    /* ────────────────────────────────────────────────────────────────────
     *                Very small Piece-Square Tables (PST)
     *
     *  Tables are given from White’s point of view, starting at a1 = 0.
     *  For Black we mirror the square index with (sq ^ 56).
     *  Source: Slightly smoothed version of the classical “Simplified
     *          Evaluation Function” PSTs by Tomasz Michniewski.
     * ─────────────────────────────────────────────────────────────────── */

    private static final int[] PST_PAWN = {
            0,  0,  0,  0,  0,  0,  0,  0,
            5, 10, 10,-20,-20, 10, 10,  5,
            5, -5,-10,  0,  0,-10, -5,  5,
            0,  0,  0, 20, 20,  0,  0,  0,
            5,  5, 10, 25, 25, 10,  5,  5,
            10, 10, 20, 30, 30, 20, 10, 10,
            50, 50, 50, 50, 50, 50, 50, 50,
            0,  0,  0,  0,  0,  0,  0,  0
    };

    private static final int[] PST_KNIGHT = {
            -50,-40,-30,-30,-30,-30,-40,-50,
            -40,-20,  0,  5,  5,  0,-20,-40,
            -30,  5, 10, 15, 15, 10,  5,-30,
            -30,  0, 15, 20, 20, 15,  0,-30,
            -30,  5, 15, 20, 20, 15,  5,-30,
            -30,  0, 10, 15, 15, 10,  0,-30,
            -40,-20,  0,  0,  0,  0,-20,-40,
            -50,-40,-30,-30,-30,-30,-40,-50
    };

    private static final int[] PST_BISHOP = {
            -20,-10,-10,-10,-10,-10,-10,-20,
            -10,  5,  0,  0,  0,  0,  5,-10,
            -10, 10, 10, 10, 10, 10, 10,-10,
            -10,  0, 10, 10, 10, 10,  0,-10,
            -10,  5,  5, 10, 10,  5,  5,-10,
            -10,  0,  5, 10, 10,  5,  0,-10,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -20,-10,-10,-10,-10,-10,-10,-20
    };

    private static final int[] PST_ROOK = {
            0,  0,  5, 10, 10,  5,  0,  0,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            5, 10, 10, 10, 10, 10, 10,  5,
            0,  0,  0,  0,  0,  0,  0,  0
    };

    private static final int[] PST_QUEEN = {
            -20,-10,-10, -5, -5,-10,-10,-20,
            -10,  0,  5,  0,  0,  0,  0,-10,
            -10,  5,  5,  5,  5,  5,  0,-10,
            0,  0,  5,  5,  5,  5,  0, -5,
            -5,  0,  5,  5,  5,  5,  0, -5,
            -10,  0,  5,  5,  5,  5,  0,-10,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -20,-10,-10, -5, -5,-10,-10,-20
    };

    private static final int[] PST_KING = {
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -20,-30,-30,-40,-40,-30,-30,-20,
            -10,-20,-20,-20,-20,-20,-20,-10,
            20, 20,  0,  0,  0,  0, 20, 20,
            20, 30, 10,  0,  0, 10, 30, 20
    };

    /* For easy PST lookup. */
    private static final int[][] PST = {
            PST_PAWN,      // P
            PST_KNIGHT,    // N
            PST_BISHOP,    // B
            PST_ROOK,      // R
            PST_QUEEN,     // Q
            PST_KING       // K
    };

    /* ----------------------------------------------------------------------
     *                               Evaluation
     * ------------------------------------------------------------------- */

    @Override
    public int evaluate(long[] bb) {

        int white = 0;
        int black = 0;

        /* ========= 1. Material + Piece-Square tables ========= */
        for (int p = PositionFactory.WP; p <= PositionFactory.WK; ++p) {
            long pieces = bb[p];
            int type = p - PositionFactory.WP;           // 0-5
            while (pieces != 0) {
                long sqBit = pieces & -pieces;           // extract LS1B
                int sq = Long.numberOfTrailingZeros(sqBit);
                pieces -= sqBit;

                white += VALUE[p] + PST[type][sq];
                /* Central bonus */
                if ((CENTER_16 & sqBit) != 0) {
                    white += (type == 1 || type == 3) ? KNIGHT_CENTER_BONUS : OTHER_CENTER_BONUS; // N,R = 10, else 5
                }
            }
        }

        for (int p = PositionFactory.BP; p <= PositionFactory.BK; ++p) {
            long pieces = bb[p];
            int type = p - PositionFactory.BP;           // 0-5
            while (pieces != 0) {
                long sqBit = pieces & -pieces;
                int sq = Long.numberOfTrailingZeros(sqBit);
                pieces -= sqBit;

                /* Mirror square for PST lookup */
                int mirrorSq = sq ^ 56;
                black += VALUE[p] + PST[type][mirrorSq];

                if ((CENTER_16 & sqBit) != 0) {
                    black += (type == 1 || type == 3) ? KNIGHT_CENTER_BONUS : OTHER_CENTER_BONUS;
                }
            }
        }

        /* ========= 2. Bishop pair ========= */
        if (Long.bitCount(bb[PositionFactory.WB]) >= 2) white += BISHOP_PAIR_BONUS;
        if (Long.bitCount(bb[PositionFactory.BB]) >= 2) black += BISHOP_PAIR_BONUS;

        /* ========= 3. Pawn structure (doubled / isolated) ========= */
        white -= pawnStructurePenalty(bb[PositionFactory.WP]);
        black -= pawnStructurePenalty(bb[PositionFactory.BP]);

        /* ========= 4. Side to move ========= */
        int diff = white - black;            // >0: White is better
        long meta = bb[PositionFactory.META];
        return PositionFactory.whiteToMove(meta) ? diff : -diff;
    }

    /**
     * Very small pawn-structure assessment.
     * Counts doubled and isolated pawns only – cheap yet effective.
     */
    private static int pawnStructurePenalty(long pawns) {
        int penalty = 0;

        for (int file = 0; file < 8; ++file) {
            long pawnsOnFile = pawns & FILE_MASKS[file];
            int cnt = Long.bitCount(pawnsOnFile);

            if (cnt > 1) {
                penalty += DOUBLED_PAWN_PENALTY * (cnt - 1);
            }
            if (cnt == 1) {
                boolean left  = file > 0 && (pawns & FILE_MASKS[file - 1]) != 0;
                boolean right = file < 7 && (pawns & FILE_MASKS[file + 1]) != 0;
                if (!left && !right) {
                    penalty += ISOLATED_PAWN_PENALTY;
                }
            }
        }
        return penalty;
    }

    @Override
    public void reset() {
        /* Stateless – nothing to clear. */
    }
}
