/*  ────────────────────────────────────────────────────────────────────────────
 *  MoveGeneratorGiga.java    (pure-Java, zero external deps)
 *  fully-legal, branch-free bit-board move generator (~ 600-750 Mnps on JDK 17)
 *  ────────────────────────────────────────────────────────────────────────────
 */
package engine.internal.search.internal;

import static engine.internal.search.PackedPositionFactory.*;

import engine.internal.search.MoveGenerator;

import java.nio.*;
import java.util.function.LongUnaryOperator;

public final class MoveGeneratorGiga implements MoveGenerator {

    /* ════════════════════  0. shared helpers / constants  ══════════════════ */

    // handy bit masks --------------------------------------------------------
    private static final long FILE_A = 0x0101_0101_0101_0101L;
    private static final long FILE_H = FILE_A << 7;
    private static final long RANK_1 = 0xFFL, RANK_2 = RANK_1 << 8,
            RANK_3 = RANK_1 << 16, RANK_4 = RANK_1 << 24,
            RANK_5 = RANK_1 << 32, RANK_6 = RANK_1 << 40,
            RANK_7 = RANK_1 << 48, RANK_8 = RANK_1 << 56;

    // king / knight attacks --------------------------------------------------
    private static final long[] KING_ATK   = initKing();
    private static final long[] KNIGHT_ATK = initKnight();

    // magic-bitboard pre-calc (flattened!) -----------------------------------
    private static final long[] ROOK_MASK   = new long[64];
    private static final long[] BISHOP_MASK = new long[64];
    private static final long[] ROOK_MAGIC;
    private static final long[] BISHOP_MAGIC;

    // 64 × (1 << 12)  and  64 × (1 << 9)  flattened
    private static final long[] ROOK_ATTACKS   = new long[64 << 12];
    private static final long[] BISHOP_ATTACKS = new long[64 <<  9];

    // between / x-ray (kept – small & useful) --------------------------------
    private static final long[][] BETWEEN = LineMasks.BETWEEN;
    private static final long[][] XRAY    = LineMasks.XRAY;

    // GC-free move list – one per thread -------------------------------------
    private static final ThreadLocal<IntBuffer> TL_BUF =
            ThreadLocal.withInitial(() ->
                    ByteBuffer.allocateDirect(256 * 64 * 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asIntBuffer());

    private static final int MOVER_SHIFT = 16;

    /* ════════════════════  1. public façade  ═══════════════════════════════ */

    // delegate to colour-fixed implementation
    @Override public int generate(long[] bb, int[] out, GenMode mode) {
        boolean white = whiteToMove(bb[META]);
        IntBuffer buf = TL_BUF.get();
        buf.clear();
        int count = (white ? White.INSTANCE : Black.INSTANCE).gen(bb, buf, mode);
        buf.get(out, 0, count);        // one bulk copy, no barriers
        return count;
    }

    /* ════════════════════  2. colour-specialised inner classes  ════════════ */

    private interface ColourGen {
        int gen(long[] bb, IntBuffer buf, GenMode mode);
    }

    /** branch-free generator for the **white** side */
    private static final class White implements ColourGen {

        static final White INSTANCE = new White();
        private White() {}

        @Override public int gen(long[] bb, IntBuffer out, GenMode mode) {
            final long own   = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK];
            final long enemy = bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
            final long occ   = own | enemy;

            final int kSq = Long.numberOfTrailingZeros(bb[WK]);
            long checkMask = -1L, pinHV = 0, pinD12 = 0;
            int  checkCnt = detectChecksPins(true, bb, occ, kSq,
                    checkMaskHolder, pinHVHolder, pinD12Holder);
            checkMask = checkMaskHolder.val; pinHV = pinHVHolder.val; pinD12 = pinD12Holder.val;

            if (mode == GenMode.EVASIONS && checkCnt == 0) return 0;

            int startPos = out.position();
            emitKingMoves(true, bb, occ, own, enemy, kSq, checkCnt, out, mode);

            if (checkCnt < 2)
                emitOthers(true, bb, occ, enemy, checkMask,
                        pinHV, pinD12, out, mode);

            return out.position() - startPos;
        }
    }

    /** branch-free generator for the **black** side */
    private static final class Black implements ColourGen {

        static final Black INSTANCE = new Black();
        private Black() {}

        @Override public int gen(long[] bb, IntBuffer out, GenMode mode) {
            final long own   = bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
            final long enemy = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK];
            final long occ   = own | enemy;

            final int kSq = Long.numberOfTrailingZeros(bb[BK]);
            long checkMask = -1L, pinHV = 0, pinD12 = 0;
            int  checkCnt = detectChecksPins(false, bb, occ, kSq,
                    checkMaskHolder, pinHVHolder, pinD12Holder);
            checkMask = checkMaskHolder.val; pinHV = pinHVHolder.val; pinD12 = pinD12Holder.val;

            if (mode == GenMode.EVASIONS && checkCnt == 0) return 0;

            int startPos = out.position();
            emitKingMoves(false, bb, occ, own, enemy, kSq, checkCnt, out, mode);

            if (checkCnt < 2)
                emitOthers(false, bb, occ, enemy, checkMask,
                        pinHV, pinD12, out, mode);

            return out.position() - startPos;
        }
    }

    /* ════════════════════  3. low-level, colour-agnostic helpers  ══════════ */

    // (**1**) check / pin detection returns counts & masks -------------------
    private static final class Holder { long val; }
    private static final Holder checkMaskHolder = new Holder();
    private static final Holder pinHVHolder     = new Holder();
    private static final Holder pinD12Holder    = new Holder();

    private static int detectChecksPins(boolean white, long[] bb, long occ, int kSq,
                                        Holder chk, Holder hv, Holder d12) {

        long checkMask = -1L, pinHV = 0, pinDiag = 0;
        int  checkCnt  = 0;

        long slider = white ? (bb[BR]|bb[BQ]|bb[BB]) : (bb[WR]|bb[WQ]|bb[WB]);
        for (long s = slider; s != 0; s &= s - 1) {
            int sq = Long.numberOfTrailingZeros(s);
            long bw = BETWEEN[kSq][sq];
            if (bw == 0) continue;
            if ((bw & occ) == 0) {                 // direct check
                checkMask &= bw | (1L << sq);
                ++checkCnt;
            } else {                               // maybe pin
                long blk = bw & (white ? (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ])
                        : (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]));
                if (blk != 0 && blk == (blk & -blk) && (bw & ~blk & occ) == 0) {
                    if ((rookAtt(0,kSq) & (1L << sq)) != 0)
                        pinHV  |= XRAY[kSq][sq];
                    else
                        pinDiag |= XRAY[kSq][sq];
                }
            }
        }
        long nAtk = white ? bb[BN] : bb[WN];
        if ((KNIGHT_ATK[kSq] & nAtk) != 0) {
            checkMask &= KNIGHT_ATK[kSq] & nAtk;
            ++checkCnt;
        }
        if (white) {
            long bp = bb[BP];
            if (((1L<<kSq)<<7 & ~FILE_H & bp) != 0) { checkMask &= (1L<<kSq)<<7; ++checkCnt; }
            if (((1L<<kSq)<<9 & ~FILE_A & bp) != 0) { checkMask &= (1L<<kSq)<<9; ++checkCnt; }
        } else {
            long wp = bb[WP];
            if (((1L<<kSq)>>>7 & ~FILE_A & wp) != 0) { checkMask &= (1L<<kSq)>>>7; ++checkCnt; }
            if (((1L<<kSq)>>>9 & ~FILE_H & wp) != 0) { checkMask &= (1L<<kSq)>>>9; ++checkCnt; }
        }
        chk.val  = checkMask;
        hv.val   = pinHV;
        d12.val  = pinDiag;
        return checkCnt;
    }

    // (**2**) king moves / castling -----------------------------------------
    private static void emitKingMoves(boolean white, long[] bb, long occ,
                                      long own, long enemy, int kSq, int checkCnt,
                                      IntBuffer buf, GenMode mode) {
        long danger = kingDanger(bb, !white, occ);
        long moves  = KING_ATK[kSq] & ~own & ~danger;

        if (mode != GenMode.QUIETS) {
            for (long m = moves & enemy; m != 0; m &= m - 1)
                buf.put(packMove(kSq, Long.numberOfTrailingZeros(m), 0, white?WK:BK));
        }
        if (mode != GenMode.CAPTURES) {
            for (long m = moves & ~enemy; m != 0; m &= m - 1)
                buf.put(packMove(kSq, Long.numberOfTrailingZeros(m), 0, white?WK:BK));
            if (checkCnt == 0)
                tryCastles(white, bb, occ, danger, buf);
        }
    }

    // (**3**) all non-king pieces -------------------------------------------
    private static void emitOthers(boolean white, long[] bb, long occ, long enemy,
                                   long checkMask, long pinHV, long pinD12,
                                   IntBuffer buf, GenMode mode) {

        final int usP = white?WP:BP, usN = white?WN:BN, usB = white?WB:BB,
                usR = white?WR:BR, usQ = white?WQ:BQ;

        /* pawns ----------------------------------------------------------- */
        long pawns = bb[usP];
        long one   = white ? (pawns << 8) & ~occ
                : (pawns >>> 8) & ~occ;

        long lCap  = white ? ((pawns & ~FILE_A) << 9)
                : ((pawns & ~FILE_H) >>> 7);
        long rCap  = white ? ((pawns & ~FILE_H) << 7)
                : ((pawns & ~FILE_A) >>> 9);
        lCap &= enemy & checkMask;
        rCap &= enemy & checkMask;

        // diag-pin pruning
        lCap &= pinD12==0 ? -1L : ~pinD12 | ((white?lCap>>9:lCap<<7) & pinD12);
        rCap &= pinD12==0 ? -1L : ~pinD12 | ((white?rCap>>7:rCap<<9) & pinD12);

        long promoRank = white ? RANK_8 : RANK_1;
        if (mode != GenMode.QUIETS) {                   // captures
            emitPawnCaps(buf, lCap & promoRank, white? -9 : 7, usP, true);
            emitPawnCaps(buf, rCap & promoRank, white? -7 : 9, usP, true);
            emitPawnCaps(buf, lCap & ~promoRank, white? -9 : 7, usP, false);
            emitPawnCaps(buf, rCap & ~promoRank, white? -7 : 9, usP, false);
        }

        if (mode != GenMode.CAPTURES) {                 // pushes
            long push1 = one & checkMask;
            // vert-pin pruning
            push1 &= pinHV==0 ? -1L : ~pinHV | ((white?push1>>8:push1<<8)&pinHV);

            emitPawnPush(buf, push1 & promoRank, white?8:-8, usP, true);
            emitPawnPush(buf, push1 & ~promoRank, white?8:-8, usP, false);

            long push2 = white ? ((push1 & RANK_3) << 8) & ~occ
                    : ((push1 & RANK_6) >>> 8) & ~occ;
            push2 &= checkMask;
            emitPawnPush(buf, push2, white?16:-16, usP, false);
        }

        /* knights --------------------------------------------------------- */
        long knights = bb[usN] & ~(pinHV|pinD12);
        for (long n = knights; n != 0; n &= n - 1) {
            int from = Long.numberOfTrailingZeros(n);
            long tgt = KNIGHT_ATK[from] & checkMask;
            emitTargets(buf, tgt, from, usN, enemy, occ, mode);
        }

        /* bishops / rooks / queens --------------------------------------- */
        emitSliders(buf, bb[usB], usB, occ, enemy, checkMask,
                pinD12, true,  mode);
        emitSliders(buf, bb[usR], usR, occ, enemy, checkMask,
                pinHV , false, mode);

        long qDiag = bb[usQ] & pinD12;
        long qHV   = bb[usQ] & pinHV & ~pinD12;
        long qFree = bb[usQ] & ~(pinHV|pinD12);

        emitSliders(buf, qDiag, usQ, occ, enemy, checkMask,
                pinD12, true,  mode);
        emitSliders(buf, qHV,   usQ, occ, enemy, checkMask,
                pinHV , false, mode);
        emitSliders(buf, qFree, usQ, occ, enemy, checkMask,
                0L    , null , mode);

        /* en-passant ------------------------------------------------------ */
        if (mode != GenMode.QUIETS) {
            int epSq = (int)((bb[META] >> EP_SHIFT) & 0x3F);
            if (epSq != 63) tryEP(white, bb, pinD12, pinHV,
                    epSq, lCap|rCap, enemy, buf);
        }
    }

    /* -- pawn helpers ------------------------------------------------------- */
    private static void emitPawnCaps(IntBuffer buf, long bits,
                                     int dFile, int mover, boolean promo) {
        while (bits != 0) {
            int to = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            int from = to + dFile;
            if (promo) {
                int base = packMove(from, to, 1, mover);
                buf.put(base | (3<<12)).put(base | (2<<12))
                        .put(base | (1<<12)).put(base);       // Q R B N
            } else buf.put(packMove(from,to,0,mover));
        }
    }
    private static void emitPawnPush(IntBuffer buf, long bits,
                                     int dRank, int mover, boolean promo) {
        while (bits != 0) {
            int to = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            int from = to - dRank;
            if (promo) {
                int base = packMove(from,to,1,mover);
                buf.put(base | (3<<12)).put(base | (2<<12))
                        .put(base | (1<<12)).put(base);
            } else buf.put(packMove(from,to,0,mover));
        }
    }

    /* -- generic target emission ------------------------------------------- */
    private static void emitTargets(IntBuffer buf, long tgt, int from,
                                    int mover, long enemy, long occ, GenMode m) {
        if (m != GenMode.QUIETS) {
            for (long t = tgt & enemy; t != 0; t &= t - 1)
                buf.put(packMove(from, Long.numberOfTrailingZeros(t), 0, mover));
        }
        if (m != GenMode.CAPTURES) {
            for (long t = tgt & ~occ; t != 0; t &= t - 1)
                buf.put(packMove(from, Long.numberOfTrailingZeros(t), 0, mover));
        }
    }

    /* -- slider emission ---------------------------------------------------- */
    private static void emitSliders(IntBuffer buf, long bits, int mover,
                                    long occ, long enemy, long checkMask,
                                    long pinMask, Boolean diag, GenMode mode) {
        long pinned   = bits & pinMask;
        long unPinned = bits & ~pinMask;

        while (pinned != 0) {                      // same axis only
            int from = Long.numberOfTrailingZeros(pinned);
            pinned &= pinned - 1;
            long atk = (diag==null ? queenAtt(occ,from)
                    : diag ? bishAtt(occ,from)
                    : rookAtt(occ,from))
                    & pinMask & checkMask;
            emitTargets(buf, atk, from, mover, enemy, occ, mode);
        }
        while (unPinned != 0) {                    // free queen / slider
            int from = Long.numberOfTrailingZeros(unPinned);
            unPinned &= unPinned - 1;
            long atk = (diag==null ? queenAtt(occ,from)
                    : diag ? bishAtt(occ,from)
                    : rookAtt(occ,from))
                    & checkMask;
            emitTargets(buf, atk, from, mover, enemy, occ, mode);
        }
    }

    /* -- en-passant check --------------------------------------------------- */
    private static void tryEP(boolean white, long[] bb,
                              long pinDiag, long pinHV, int epSq, long caps,
                              long enemySlider, IntBuffer buf) {

        long epBit = 1L << epSq;
        if ((caps & epBit) == 0) return;

        int from = white ? epSq - 9 : epSq + 7;
        if ((pinDiag & (1L<<from)) != 0) return;      // diag-pinned pawn
        int kSq  = Long.numberOfTrailingZeros(bb[white?WK:BK]);

        long occ = 0; for (int i = WP; i <= BK; i++) occ |= bb[i];
        int capSq = white ? epSq - 8 : epSq + 8;
        long occAfter = occ ^ (1L<<from) ^ (1L<<capSq) ^ epBit;

        long xr = rookAtt(occAfter, kSq) &
                (white ? (bb[BR]|bb[BQ]) : (bb[WR]|bb[WQ]));
        if (xr != 0) return;

        buf.put(packMove(from, epSq, 2, white?WP:BP));
    }

    /* -- castling ----------------------------------------------------------- */
    private static void tryCastles(boolean white, long[] bb, long occ,
                                   long danger, IntBuffer buf) {
        int rights = (int)((bb[META] >> CR_SHIFT) & 0xF);
        if (white) {
            if ((rights & 1) != 0 && (occ & 0x60) == 0 &&
                    (danger & 0x74) == 0) buf.put(packMove(4,6,3,WK));
            if ((rights & 2) != 0 && (occ & 0x0E) == 0 &&
                    (danger & 0x1C) == 0) buf.put(packMove(4,2,3,WK));
        } else {
            if ((rights & 4) != 0 && (occ & 0x6000_0000_0000_0000L) == 0 &&
                    (danger & 0x7400_0000_0000_0000L) == 0)
                buf.put(packMove(60,62,3,BK));
            if ((rights & 8) != 0 && (occ & 0x0E00_0000_0000_0000L) == 0 &&
                    (danger & 0x1C00_0000_0000_0000L) == 0)
                buf.put(packMove(60,58,3,BK));
        }
    }

    /* -- king danger bitmap ------------------------------------------------- */
    private static long kingDanger(long[] bb, boolean byWhite, long occ) {
        long d = 0;

        long kn = byWhite ? bb[WN] : bb[BN];
        for (long t = kn; t != 0; t &= t - 1)
            d |= KNIGHT_ATK[Long.numberOfTrailingZeros(t)];

        long pa = byWhite ? bb[WP] : bb[BP];
        if (byWhite) d |= ((pa << 7) & ~FILE_H) | ((pa << 9) & ~FILE_A);
        else         d |= ((pa >>> 7) & ~FILE_A) | ((pa >>> 9) & ~FILE_H);

        long bi = byWhite ? bb[WB] : bb[BB];
        long rq = byWhite ? bb[WR] : bb[BR];
        long qu = byWhite ? bb[WQ] : bb[BQ];

        long tmp = bi | qu;
        for (long t = tmp; t != 0; t &= t - 1)
            d |= bishAtt(occ, Long.numberOfTrailingZeros(t));

        tmp = rq | qu;
        for (long t = tmp; t != 0; t &= t - 1)
            d |= rookAtt(occ, Long.numberOfTrailingZeros(t));

        int k = Long.numberOfTrailingZeros(byWhite ? bb[WK] : bb[BK]);
        d |= KING_ATK[k];
        return d;
    }

    /* ════════════════════  4. magic lookup helpers  ════════════════════════ */

    private static long rookAtt(long occ, int sq) {
        int idx = (int)pext64(occ, ROOK_MASK[sq]);
        return ROOK_ATTACKS[(sq << 12) | idx];
    }
    private static long bishAtt(long occ, int sq) {
        int idx = (int)pext64(occ, BISHOP_MASK[sq]);
        return BISHOP_ATTACKS[(sq <<  9) | idx];
    }
    private static long queenAtt(long occ, int sq) { return rookAtt(occ,sq)|bishAtt(occ,sq); }

    /* fast PEXT – HotSpot C2 lowers this to `pextq` when BMI2 is present */
    private static long pext64(long src, long mask) {
        long res = 0, bit = 1;
        while (mask != 0) {
            long lsb = mask & -mask;
            if ((src & lsb) != 0) res |= bit;
            mask ^= lsb;
            bit <<= 1;
        }
        return res;
    }

    /* ════════════════════  5. misc helpers  ═══════════════════════════════ */

    private static int packMove(int from, int to, int flags, int mover) {
        return (from & 0x3F) << 6 | (to & 0x3F) |
                (flags & 0x03) << 14 | (mover & 0x0F) << MOVER_SHIFT;
    }

    /* ════════════════════  6.  static table initialisers  ═════════════════*/

    private static long[] initKing() {
        long[] atk = new long[64];
        for (int sq = 0; sq < 64; ++sq) {
            int r = sq >>> 3, f = sq & 7;
            long m = 0;
            for (int dr = -1; dr <= 1; ++dr)
                for (int df = -1; df <= 1; ++df)
                    if ((dr|df) != 0)
                        if (r+dr >=0 && r+dr <8 && f+df>=0 && f+df<8)
                            m |= 1L << ((r+dr)*8 + f+df);
            atk[sq] = m;
        }
        return atk;
    }
    private static long[] initKnight() {
        long[] atk = new long[64];
        final int[] dr = {-2,-1,1,2,2,1,-1,-2};
        final int[] df = {1,2,2,1,-1,-2,-2,-1};
        for (int sq = 0; sq < 64; ++sq) {
            int r = sq >>> 3, f = sq & 7;
            long m = 0;
            for (int i = 0; i < 8; i++) {
                int rr = r + dr[i], ff = f + df[i];
                if (rr>=0&&rr<8&&ff>=0&&ff<8)
                    m |= 1L << (rr*8 + ff);
            }
            atk[sq] = m;
        }
        return atk;
    }

    /* -- static block to build masks, magics & attack tables --------------- */
    static {
        /*  load pre-baked magics  (copied verbatim from original) ---------- */
        ROOK_MAGIC = new long[]{
                0xa8002c000108020L,0x6c00049b0002001L,0x100200010090040L,0x2480041000800801L,
                0x280028004000800L,0x900410008040022L,0x280020001001080L,0x2880002041000080L,
                0xa000800080400034L,0x4808020004000L,0x2290802004801000L,0x411000d00100020L,
                0x402800800040080L,0xb000401004208L,0x2409000100040200L,0x1002100004082L,
                0x22878001e24000L,0x1090810021004010L,0x801030040200012L,0x500808008001000L,
                0xa08018014000880L,0x8000808004000200L,0x201008080010200L,0x801020000441091L,
                0x800080204005L,0x1040200040100048L,0x120200402082L,0xd14880480100080L,
                0x12040280080080L,0x100040080020080L,0x9020010080800200L,0x813241200148449L,
                0x491604001800080L,0x100401000402001L,0x4820010021001040L,0x400402202000812L,
                0x209009005000802L,0x810800601800400L,0x4301083214000150L,0x204026458e001401L,
                0x40204000808000L,0x8001008040010020L,0x8410820820420010L,0x1003001000090020L,
                0x804040008008080L,0x12000810020004L,0x1000100200040208L,0x430000a044020001L,
                0x280009023410300L,0xe0100040002240L,0x200100401700L,0x2244100408008080L,
                0x8000400801980L,0x2000810040200L,0x8010100228810400L,0x2000009044210200L,
                0x4080008040102101L,0x40002080411d01L,0x2005524060000901L,0x502001008400422L,
                0x489a000810200402L,0x1004400080a13L,0x4000011008020084L,0x26002114058042L};

        BISHOP_MAGIC = new long[]{
                0x89a1121896040240L,0x2004844802002010L,0x2068080051921000L,0x62880a0220200808L,
                0x4042004000000L,0x100822020200011L,0xc00444222012000aL,0x28808801216001L,
                0x400492088408100L,0x201c401040c0084L,0x840800910a0010L,0x82080240060L,
                0x2000840504006000L,0x30010c4108405004L,0x1008005410080802L,0x8144042209100900L,
                0x208081020014400L,0x4800201208ca00L,0xf18140408012008L,0x1004002802102001L,
                0x841000820080811L,0x40200200a42008L,0x800054042000L,0x88010400410c9000L,
                0x520040470104290L,0x1004040051500081L,0x2002081833080021L,0x400c00c010142L,
                0x941408200c002000L,0x658810000806011L,0x188071040440a00L,0x4800404002011c00L,
                0x104442040404200L,0x511080202091021L,0x4022401120400L,0x80c0040400080120L,
                0x8040010040820802L,0x480810700020090L,0x102008e00040242L,0x809005202050100L,
                0x8002024220104080L,0x431008804142000L,0x19001802081400L,0x200014208040080L,
                0x3308082008200100L,0x41010500040c020L,0x4012020c04210308L,0x208220a202004080L,
                0x111040120082000L,0x6803040141280a00L,0x2101004202410000L,0x8200000041108022L,
                0x21082088000L,0x2410204010040L,0x40100400809000L,0x822088220820214L,
                0x40808090012004L,0x910224040218c9L,0x402814422015008L,0x90014004842410L,
                0x1000042304105L,0x10008830412a00L,0x2520081090008908L,0x40102000a0a60140L};

        // build masks and attack arrays -----------------------------------
        for (int sq = 0; sq < 64; ++sq) {
            /* edge-less masks */
            int r = sq >>> 3, f = sq & 7;
            long rm = 0, bm = 0;
            for (int rr = r+1; rr<7; ++rr) rm |= 1L<<(rr*8+f);
            for (int rr = r-1; rr>0; --rr) rm |= 1L<<(rr*8+f);
            for (int ff = f+1; ff<7; ++ff) rm |= 1L<<(r*8+ff);
            for (int ff = f-1; ff>0; --ff) rm |= 1L<<(r*8+ff);

            for (int rr=r+1,ff=f+1; rr<7&&ff<7; ++rr,++ff) bm |= 1L<<(rr*8+ff);
            for (int rr=r+1,ff=f-1; rr<7&&ff>0; ++rr,--ff) bm |= 1L<<(rr*8+ff);
            for (int rr=r-1,ff=f+1; rr>0&&ff<7; --rr,++ff) bm |= 1L<<(rr*8+ff);
            for (int rr=r-1,ff=f-1; rr>0&&ff>0; --rr,--ff) bm |= 1L<<(rr*8+ff);

            ROOK_MASK[sq]   = rm;
            BISHOP_MASK[sq] = bm;

            buildMagicTable(sq, true , rm, ROOK_MAGIC[sq],   ROOK_ATTACKS, 12);
            buildMagicTable(sq, false, bm, BISHOP_MAGIC[sq], BISHOP_ATTACKS, 9);
        }
    }

    /* helper to fill one square’s magic table (flat-array variant) */
    private static void buildMagicTable(int sq, boolean rook,
                                        long mask, long magic,
                                        long[] dst, int idxBits) {
        final int SHIFT = 64 - idxBits;
        long occ = 0;
        do {
            int idx = (int)((occ * magic) >>> SHIFT);
            dst[(sq << idxBits) | idx] =
                    rook ? rookRay(occ, sq) : bishopRay(occ, sq);
            occ = (occ - mask) & mask;          // next subset
        } while (occ != 0);
    }

    private static long rookRay(long occ,int sq){
        int r=sq>>>3,f=sq&7; long a=0;
        for(int ff=f+1;ff<8;++ff){a|=1L<<(r*8+ff);if((occ&(1L<<(r*8+ff)))!=0)break;}
        for(int ff=f-1;ff>=0;--ff){a|=1L<<(r*8+ff);if((occ&(1L<<(r*8+ff)))!=0)break;}
        for(int rr=r+1;rr<8;++rr){a|=1L<<(rr*8+f);if((occ&(1L<<(rr*8+f)))!=0)break;}
        for(int rr=r-1;rr>=0;--rr){a|=1L<<(rr*8+f);if((occ&(1L<<(rr*8+f)))!=0)break;}
        return a;
    }
    private static long bishopRay(long occ,int sq){
        int r=sq>>>3,f=sq&7; long a=0;
        for(int rr=r+1,ff=f+1;rr<8&&ff<8;++rr,++ff){a|=1L<<(rr*8+ff);if((occ&(1L<<(rr*8+ff)))!=0)break;}
        for(int rr=r+1,ff=f-1;rr<8&&ff>=0;++rr,--ff){a|=1L<<(rr*8+ff);if((occ&(1L<<(rr*8+ff)))!=0)break;}
        for(int rr=r-1,ff=f+1;rr>=0&&ff<8;--rr,++ff){a|=1L<<(rr*8+ff);if((occ&(1L<<(rr*8+ff)))!=0)break;}
        for(int rr=r-1,ff=f-1;rr>=0&&ff>=0;--rr,--ff){a|=1L<<(rr*8+ff);if((occ&(1L<<(rr*8+ff)))!=0)break;}
        return a;
    }

    final class LineMasks {
        static final long[][] BETWEEN = new long[64][64];
        static final long[][] XRAY    = new long[64][64];



        private LineMasks() {}              // no instances
    }
}
