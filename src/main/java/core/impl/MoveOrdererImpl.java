// File: core/impl/MoveOrdererImpl.java
package core.impl;

import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;

import static core.contracts.PositionFactory.META;

public final class MoveOrdererImpl implements MoveOrderer {

    // --- Score bands (compatible with your previous scales) ---
    private static final int SCORE_TT_MOVE     = 100_000;
    private static final int SCORE_GOOD_NOISY  =  85_000;   // SEE >= 0
    private static final int SCORE_KILLER_1    =  75_000;
    private static final int SCORE_KILLER_2    =  74_999;
    private static final int SCORE_QPROMO      =  90_000;
    private static final int SCORE_UNDERPROMO  =  70_000;
    private static final int SCORE_BAD_NOISY   =  10_000;   // SEE < 0 baseline

    // SEE piece values (centipawns) – light tweaks but close to standard
    private static final int[] SEE_VAL = {100, 320, 330, 500, 900, 10000}; // P,N,B,R,Q,K

    private final int[]     scores = new int[256];
    private final int[][]   history;

    public MoveOrdererImpl(int[][] history) {
        this.history = history;
    }

    @Override
    public void orderMoves(long[] bb, int[] moves, int count, int ttMove, int[] killers) {
        final boolean whiteToMove = PositionFactory.whiteToMove(bb[META]);

        for (int i = 0; i < count; i++) {
            final int mv = moves[i];

            if (mv == ttMove) { scores[i] = SCORE_TT_MOVE; continue; }

            final int type  = (mv >>> 14) & 0x3;
            final int from  = (mv >>> 6) & 0x3F;
            final int to    =  mv        & 0x3F;

            // Promotions get a big bump (queen highest)
            if (type == 1) {
                final int promo = (mv >>> 12) & 0x3;
                scores[i] = (promo == 3) ? SCORE_QPROMO : SCORE_UNDERPROMO;
                continue;
            }

            // Tactical?
            final int victimType = capturedType(bb, to, whiteToMove, mv);
            if (victimType != -1) {
                final int seeGain = see(bb, mv);
                scores[i] = (seeGain >= 0 ? SCORE_GOOD_NOISY : SCORE_BAD_NOISY) + clampSEE(seeGain);
                continue;
            }

            // Quiet → killers then history
            int s = 0;
            if (killers != null) {
                if (mv == killers[0]) s = SCORE_KILLER_1;
                else if (mv == killers[1]) s = SCORE_KILLER_2;
            }
            if (s == 0) s = history[from][to]; // raw history (already scaled externally)
            scores[i] = s;
        }

        // Stable insertion sort (descending by score)
        for (int i = 1; i < count; i++) {
            int ms = scores[i], mv = moves[i], j = i - 1;
            while (j >= 0 && scores[j] < ms) {
                scores[j + 1] = scores[j];
                moves[j + 1]  = moves[j];
                j--;
            }
            scores[j + 1] = ms;
            moves[j + 1]  = mv;
        }
    }

    @Override
    public int seePrune(long[] bb, int[] moves, int count) {
        int w = 0;
        for (int i = 0; i < count; i++) {
            if (see(bb, moves[i]) >= 0) moves[w++] = moves[i];
        }
        return w;
    }

    /**
     * Static Exchange Evaluation: swap-based, least-valuable-attacker (LVA).
     * Returns net material gain (centipawns) from executing the capture sequence.
     */
    @Override
    public int see(long[] bb, int move) {
        final int from = (move >>> 6) & 0x3F;
        final int to   =  move        & 0x3F;
        final boolean stmStart = PositionFactory.whiteToMove(bb[META]);

        int moverType = ((move >>> 16) & 0xF) % 6;
        int victim    = capturedType(bb, to, stmStart, move);
        if (victim == -1) return 0;

        // occupancy snapshot
        long occ = 0L;
        for (int p = PositionFactory.WP; p <= PositionFactory.BK; p++) occ |= bb[p];

        // remove first attacker from occupancy
        occ ^= (1L << from);

        final int[] gain = new int[32];
        int d = 0;
        gain[d] = SEE_VAL[victim];

        boolean stm = !stmStart;
        while (true) {
            d++;
            gain[d] = SEE_VAL[moverType] - gain[d - 1];

            long[] out = new long[1];
            moverType = lvaOnSquare(bb, to, stm, occ, out);
            if (moverType == -1) break;

            occ ^= out[0];     // remove that attacker
            stm = !stm;
        }

        // Negamax fold back
        while (--d > 0) gain[d - 1] = -Math.max(-gain[d - 1], gain[d]);
        return gain[0];
    }

    private int clampSEE(int val) {
        if (val >  9999) return  9999;
        if (val < -9999) return -9999;
        return val;
    }

    private int capturedType(long[] bb, int toSq, boolean whiteToMove, int move) {
        final long toBit = 1L << toSq;
        final int moveType = (move >>> 14) & 0x3;
        final boolean whiteMover = (((move >>> 16) & 0xF) < 6);

        // En passant
        if (moveType == 2) return 0;

        // Opponent piece on 'to'
        final int start = whiteMover ? PositionFactory.BP : PositionFactory.WP;
        final int end   = whiteMover ? PositionFactory.BK : PositionFactory.WK;
        for (int p = start; p <= end; p++) if ((bb[p] & toBit) != 0) return p % 6;

        // Also allow EP square implied capture (safety)
        final int ep = (int) PositionFactory.epSquare(bb[META]);
        if (ep == toSq) return 0;

        return -1;
    }

    /**
     * Find least valuable attacker for side 'stm' that hits 'to', given occupancy 'occ'.
     * Returns piece type 0..5, or -1. Also returns the attacker's bit in out[0].
     */
    private int lvaOnSquare(long[] bb, int to, boolean stm, long occ, long[] out) {
        final long toBB = 1L << to;

        // Pawns
        long pawns;
        if (stm) {
            pawns = (((toBB & ~0x8080808080808080L) >>> 7) | ((toBB & ~0x0101010101010101L) >>> 9)) & bb[PositionFactory.WP] & occ;
        } else {
            pawns = (((toBB & ~0x0101010101010101L) << 7) | ((toBB & ~0x8080808080808080L) << 9)) & bb[PositionFactory.BP] & occ;
        }
        if (pawns != 0) { out[0] = pawns & -pawns; return 0; }

        // Knights
        long atk = PreCompMoveGenTables.KNIGHT_ATK[to] & (stm ? bb[PositionFactory.WN] : bb[PositionFactory.BN]) & occ;
        if (atk != 0) { out[0] = atk & -atk; return 1; }

        // Bishops
        atk = MoveGeneratorImpl.bishopAtt(occ, to) & (stm ? bb[PositionFactory.WB] : bb[PositionFactory.BB]) & occ;
        if (atk != 0) { out[0] = atk & -atk; return 2; }

        // Rooks
        atk = MoveGeneratorImpl.rookAtt(occ, to) & (stm ? bb[PositionFactory.WR] : bb[PositionFactory.BR]) & occ;
        if (atk != 0) { out[0] = atk & -atk; return 3; }

        // Queens
        atk = MoveGeneratorImpl.queenAtt(occ, to) & (stm ? bb[PositionFactory.WQ] : bb[PositionFactory.BQ]) & occ;
        if (atk != 0) { out[0] = atk & -atk; return 4; }

        // King
        atk = PreCompMoveGenTables.KING_ATK[to] & (stm ? bb[PositionFactory.WK] : bb[PositionFactory.BK]) & occ;
        if (atk != 0) { out[0] = atk & -atk; return 5; }

        out[0] = 0L;
        return -1;
    }
}
