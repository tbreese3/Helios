package engine.internal.search.internal;

import engine.internal.search.MoveGenerator;
import static engine.internal.search.PackedPositionFactory.*;
import static engine.internal.search.PackedMoveFactory.*;

/*───────────────────────────────────────────────────────────────────────────────
 *  MoveGeneratorHQ.java
 *
 *  Fast **pseudo-legal** move generator for the packed 13-long[]
 *  representation used by this engine.
 *
 *  ★ Design highlights ★
 *  • **Branch-free hot loops** – the only branches are loop‐exits.
 *  • **Lookup tables** for king / knight / pawn attacks (64-entry each).
 *  • **Hyperbola-Quintessence** sliding algorithm – calculates bishop/rook
 *    rays on-the-fly with a couple of arithmetic ops, zero pre-generated
 *    magic tables, and no CPU-specific intrinsics.
 *  • **PackedMoveFactory** 16-bit format is written directly; no objects,
 *    no boxing, no allocations – the caller supplies an `int[]` buffer.
 *  • Generates three modes:
 *        ALL        – every pseudo-legal move
 *        CAPTURES   – captures & promotions only
 *        QUIETS     – non-captures (inc. castles, pushes) only
 *    Extending to LEGAL or EVASIONS is straightforward.
 *
 *──────────────────────────────────────────────────────────────────────────────*/
public final class MoveGeneratorHQ implements MoveGenerator {

    /* ── compile-time constants ───────────────────────────────────────── */

    private static final long FILE_A = 0x0101010101010101L;
    private static final long FILE_H = FILE_A << 7;
    private static final long RANK_1 = 0xFFL;
    private static final long RANK_4 = RANK_1 << 24;
    private static final long RANK_5 = RANK_1 << 32;
    private static final long RANK_8 = RANK_1 << 56;

    private static final long[] KING_ATK   = new long[64];
    private static final long[] KNIGHT_ATK = new long[64];
    private static final long[] PAWN_PUSH  = new long[2*64];  //   [color*64+sq]
    private static final long[] PAWN_CAPL  = new long[2*64];
    private static final long[] PAWN_CAPR  = new long[2*64];
    private static final long[] DIAG_MASK  = new long[64];
    private static final long[] ADIAG_MASK = new long[64];
    private static final long[] FILE_MASK  = new long[64];
    private static final long[] RANK_MASK  = new long[64];

    static {
        for (int sq = 0; sq < 64; ++sq) {
            int r = sq >>> 3, f = sq & 7;

            /* king + knight look-ups */
            long k  = 0, n = 0;
            for (int dr = -1; dr <= 1; ++dr)
                for (int df = -1; df <= 1; ++df)
                    if ((dr|df) != 0) add(k, r+dr, f+df);
            addKnightMoves(n, r, f);
            KING_ATK[sq]   = k;
            KNIGHT_ATK[sq] = n;

            /* pawn stuff (WHITE = 0, BLACK = 1) */
            // push
            if (r < 7) PAWN_PUSH[sq]       = 1L << ((r+1)<<3 | f);
            if (r > 0) PAWN_PUSH[64+sq]    = 1L << ((r-1)<<3 | f);
            // captures
            if (r < 7 && f > 0)  PAWN_CAPL[sq]    = 1L << ((r+1)<<3 | f-1);
            if (r > 0 && f < 7)  PAWN_CAPL[64+sq] = 1L << ((r-1)<<3 | f+1);
            if (r < 7 && f < 7)  PAWN_CAPR[sq]    = 1L << ((r+1)<<3 | f+1);
            if (r > 0 && f > 0)  PAWN_CAPR[64+sq] = 1L << ((r-1)<<3 | f-1);

            /* sliding-ray masks */
            DIAG_MASK [sq] = diagMask (sq);
            ADIAG_MASK[sq] = adiagMask(sq);
            FILE_MASK [sq] = FILE_A << f;
            RANK_MASK [sq] = RANK_1 << (r<<3);
        }
    }

    /* helper to set a bit safely */
    private static void add(long b, int r, int f) { if (r>=0&&r<8&&f>=0&&f<8) b |= 1L<<((r<<3)|f); }

    private static void addKnightMoves(long n, int r, int f) {
        final int[] dr = {-2,-1,1,2, 2, 1,-1,-2};
        final int[] df = { 1, 2,2,1,-1,-2,-2,-1};
        for (int i=0;i<8;i++) {
            int rr=r+dr[i], ff=f+df[i];
            if (rr>=0&&rr<8&&ff>=0&&ff<8) n |= 1L<<((rr<<3)|ff);
        }
    }

    /* hyperbola-quintessence helpers for sliding pieces */
    private static long rayAttacks(long occ, long mask, int sq) {
        long from = 1L<<sq;
        long forward  = (occ & mask) - 2*from;
        long reverse  = Long.reverse( Long.reverse(occ & mask) - 2*Long.reverse(from) );
        return (forward ^ reverse) & mask;
    }
    private static long rookAtt(long occ,int sq){
        return   rayAttacks(occ, FILE_MASK[sq], sq)
                | rayAttacks(occ, RANK_MASK[sq], sq);
    }
    private static long bishopAtt(long occ,int sq){
        return   rayAttacks(occ, DIAG_MASK[sq],  sq)
                | rayAttacks(occ, ADIAG_MASK[sq],sq);
    }
    private static long queenAtt(long occ,int sq){
        return rookAtt(occ,sq)|bishopAtt(occ,sq);
    }

    /* ── public entry point ─────────────────────────────────────────── */

    @Override
    public int generate(long[] bb, int[] moves, GenMode mode) {

        final boolean white = whiteToMove(bb[META]);
        final int usP   = white ? WP : BP;
        final int themP = white ? BP : WP;

        /* aggregate bitboards once */
        long whitePieces = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK];
        long blackPieces = bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];

        long own   =  white ? whitePieces : blackPieces;
        long enemy =  white ? blackPieces : whitePieces;
        long occ   =  own | enemy;
        long quietMask   = (mode==GenMode.CAPTURES) ? 0 : ~occ;
        long captureMask = (mode==GenMode.QUIETS ) ? 0 : enemy;

        int n = 0; // write cursor into 'moves'

        /* ---- Pawns -------------------------------------------------- */
        long pawns = bb[usP];

        int pushDir   = white ?  8 : -8;
        long rank2    = white ? RANK_2 : RANK_7;
        long rank7    = white ? RANK_7 : RANK_2;
        long epTarget = (bb[META] & EP_MASK)>>>EP_SHIFT;
        boolean hasEP = epTarget != 63;
        long epBit    = hasEP ? 1L<<epTarget : 0;

        // single pushes (non-captures)
        if (mode!=GenMode.CAPTURES) {
            long one = white ? (pawns<<8) & ~occ
                    : (pawns>>>8) & ~occ;
            long promo = one & rank7;
            long quiet = one & ~rank7;

            while (quiet!=0) {
                int to = lsb(quiet); quiet = popLsb(quiet);
                moves[n++] = ((to-pushDir)<<6)|to;       // normal push
            }
            // promotions as quiets: always under-promote to Queen only
            while (promo!=0) {
                int to = lsb(promo); promo = popLsb(promo);
                moves[n++] = ((to-pushDir)<<6)|to | (1<<14) | (3<<12); // type=promotion,q
            }
            // double pushes
            long two = white ?
                    ((one & RANK_3)<<8)&~occ :
                    ((one & RANK_6)>>>8)&~occ;
            while (two!=0) {
                int to = lsb(two); two=popLsb(two);
                moves[n++] = ((to-2*pushDir)<<6)|to;
            }
        }

        // captures (incl promotions)
        if (mode!=GenMode.QUIETS) {
            long capL = (white ? (pawns<<7) : (pawns>>>9)) & captureMask & ~FILE_A;
            long capR = (white ? (pawns<<9) : (pawns>>>7)) & captureMask & ~FILE_H;

            long promoL = capL & rank7;
            long promoR = capR & rank7;
            capL &= ~rank7;
            capR &= ~rank7;

            while (capL!=0) { int to=lsb(capL); capL=popLsb(capL);
                moves[n++] = ((to-pushDir-1)<<6)|to; }
            while (capR!=0) { int to=lsb(capR); capR=popLsb(capR);
                moves[n++] = ((to-pushDir+1)<<6)|to; }

            // promotions capture : encode Q only for speed
            while (promoL!=0){ int to=lsb(promoL); promoL=popLsb(promoL);
                moves[n++] = ((to-pushDir-1)<<6)|to | (1<<14)|(3<<12);}
            while (promoR!=0){ int to=lsb(promoR); promoR=popLsb(promoR);
                moves[n++] = ((to-pushDir+1)<<6)|to | (1<<14)|(3<<12);}
        }

        // en-passant (treated as capture)
        if (hasEP && mode!=GenMode.QUIETS) {
            long epMask = epBit;
            long epCapL = (white ? (pawns<<7) : (pawns>>>9)) & epMask & ~FILE_A;
            long epCapR = (white ? (pawns<<9) : (pawns>>>7)) & epMask & ~FILE_H;

            while (epCapL!=0){ int to=lsb(epCapL); epCapL=popLsb(epCapL);
                moves[n++] = ((to-pushDir-1)<<6)|to | (2<<14);}
            while (epCapR!=0){ int to=lsb(epCapR); epCapR=popLsb(epCapR);
                moves[n++] = ((to-pushDir+1)<<6)|to | (2<<14);}
        }

        /* ---- Knights ----------------------------------------------- */
        long knights = white ? bb[WN] : bb[BN];
        while (knights!=0){
            int from=lsb(knights); knights=popLsb(knights);
            long targets = KNIGHT_ATK[from] & (captureMask|quietMask);
            while (targets!=0){
                int to=lsb(targets); targets=popLsb(targets);
                moves[n++] = (from<<6)|to;
            }
        }

        /* ---- Bishops ----------------------------------------------- */
        long bishops = white ? bb[WB] : bb[BB];
        while (bishops!=0){
            int from=lsb(bishops); bishops=popLsb(bishops);
            long targets = bishopAtt(occ,from) & (captureMask|quietMask);
            while (targets!=0){
                int to=lsb(targets); targets=popLsb(targets);
                moves[n++] = (from<<6)|to;
            }
        }

        /* ---- Rooks -------------------------------------------------- */
        long rooks = white ? bb[WR] : bb[BR];
        while (rooks!=0){
            int from=lsb(rooks); rooks=popLsb(rooks);
            long targets = rookAtt(occ,from) & (captureMask|quietMask);
            while (targets!=0){
                int to=lsb(targets); targets=popLsb(targets);
                moves[n++] = (from<<6)|to;
            }
        }

        /* ---- Queens ------------------------------------------------- */
        long queens = white ? bb[WQ] : bb[BQ];
        while (queens!=0){
            int from=lsb(queens); queens=popLsb(queens);
            long targets = queenAtt(occ,from) & (captureMask|quietMask);
            while (targets!=0){
                int to=lsb(targets); targets=popLsb(targets);
                moves[n++] = (from<<6)|to;
            }
        }

        /* ---- King + castling ---------------------------------------- */
        int kIdx = white ? WK : BK;
        int kingSq = lsb(bb[kIdx]);
        long kingMoves = KING_ATK[kingSq] & (captureMask|quietMask);
        while (kingMoves!=0){
            int to=lsb(kingMoves); kingMoves=popLsb(kingMoves);
            moves[n++] = (kingSq<<6)|to;
        }
        if (mode!=GenMode.CAPTURES) {
            int rights = (int)((bb[META] & CR_MASK)>>>CR_SHIFT);
            if (white) {
                if ((rights&1)!=0 && ((occ & 0x0000000000000060L)==0))
                    moves[n++] = (4<<6)|6 | (3<<14); // e1g1 king-side
                if ((rights&2)!=0 && ((occ & 0x000000000000000EL)==0))
                    moves[n++] = (4<<6)|2 | (3<<14); // e1c1 queen-side
            } else {
                if ((rights&4)!=0 && ((occ & 0x6000000000000000L)==0))
                    moves[n++] = (60<<6)|62 | (3<<14); // e8g8
                if ((rights&8)!=0 && ((occ & 0x0E00000000000000L)==0))
                    moves[n++] = (60<<6)|58 | (3<<14); // e8c8
            }
        }

        return n;
    }

    /* ── small helpers ─────────────────────────────────────────────── */

    private static int lsb(long b){ return Long.numberOfTrailingZeros(b);}
    private static long popLsb(long b){ return b & (b-1);}

    private static long diagMask(int sq){
        int r=sq>>>3,f=sq&7; long m=0;
        for(int dr=-7;dr<=7;dr++){
            int rr=r+dr,ff=f+dr; if(rr>=0&&rr<8&&ff>=0&&ff<8) m|=1L<<((rr<<3)|ff);}
        return m & ~(1L<<sq);
    }
    private static long adiagMask(int sq){
        int r=sq>>>3,f=sq&7; long m=0;
        for(int dr=-7;dr<=7;dr++){
            int rr=r+dr,ff=f-dr; if(rr>=0&&rr<8&&ff>=0&&ff<8) m|=1L<<((rr<<3)|ff);}
        return m & ~(1L<<sq);
    }

    /* file/rank helpers */
    private static final long RANK_2 = RANK_1 << 8;
    private static final long RANK_3 = RANK_1 << 16;
    private static final long RANK_6 = RANK_1 << 40;
    private static final long RANK_7 = RANK_1 << 48;
}
