package engine.internal.search.internal;

import static engine.internal.search.PackedPositionFactory.*;
import static engine.internal.search.internal.PrecomputedTables.*;

import engine.internal.search.MoveGenerator;

/**
 * High-throughput pseudo-legal move generator based on the Hyperbola-Quintessence algorithm.
 *
 * <p>• Emits full promotion set Q/R/B/N<br>
 * • Filters out illegal en-passant captures<br>
 */
public final class MoveGeneratorHQ implements MoveGenerator {
  /* ── bit-board constants ─────────────────────────────────────── */
  private static final long FILE_A = 0x0101_0101_0101_0101L;
  private static final long FILE_H = FILE_A << 7;

  private static final long RANK_1 = 0xFFL;
  private static final long RANK_2 = RANK_1 << 8;
  private static final long RANK_3 = RANK_1 << 16;
  private static final long RANK_6 = RANK_1 << 40;
  private static final long RANK_7 = RANK_1 << 48;
  private static final long RANK_8 = RANK_1 << 56;

  /* ── lookup tables ───────────────────────────────────────────── */
  private static final long[] KING_ATK = new long[64];
  private static final long[] KNIGHT_ATK = new long[64];

  /* optional pawn tables – not used by the generator itself        */
  private static final long[] PAWN_PUSH = new long[2 * 64];
  private static final long[] PAWN_CAPL = new long[2 * 64];
  private static final long[] PAWN_CAPR = new long[2 * 64];

  private static final long[] DIAG_MASK = new long[64];
  private static final long[] ADIAG_MASK = new long[64];
  private static final long[] FILE_MASK = new long[64];
  private static final long[] RANK_MASK = new long[64];
  private static final long[] DIAG_MASK_REV = new long[64];
  private static final long[] ADIAG_MASK_REV = new long[64];
  private static final long[] FILE_MASK_REV = new long[64];
  private static final long[] RANK_MASK_REV = new long[64];
  private static final long[] FROM_REV = new long[64];
  private static final int MOVER_SHIFT = 16;

  /* ─────────────────────────────  scratch  ─────────────────────────── */
  private static final int[]  TMP_PIN_FROM = new int[8];
  private static final long[] TMP_PIN_RAY  = new long[8];
  private static final long[] TMP_PINNED   = new long[1];
  private static final long[] TMP_BLOCK    = new long[1];
  private static final int[]  PIN_FROM = new int [8];
  private static final long[] PIN_RAY  = new long[64];
  private static final long[] TMP_ENEMY = new long[1];


  /* ── static initialisation ───────────────────────────────────── */
  static {
    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, f = sq & 7;

      /* king & knight tables */
      long k = 0;
      for (int dr = -1; dr <= 1; ++dr)
        for (int df = -1; df <= 1; ++df) if ((dr | df) != 0) k = addToMask(k, r + dr, f + df);
      KING_ATK[sq] = k;
      KNIGHT_ATK[sq] = knightMask(r, f);

      /* optional pawn tables */
      if (r < 7) PAWN_PUSH[sq] = 1L << ((r + 1) * 8 + f);
      if (r > 0) PAWN_PUSH[64 + sq] = 1L << ((r - 1) * 8 + f);

      if (r < 7 && f > 0) PAWN_CAPL[sq] = 1L << ((r + 1) * 8 + f - 1);
      if (r > 0 && f < 7) PAWN_CAPL[64 + sq] = 1L << ((r - 1) * 8 + f + 1);
      if (r < 7 && f < 7) PAWN_CAPR[sq] = 1L << ((r + 1) * 8 + f + 1);
      if (r > 0 && f > 0) PAWN_CAPR[64 + sq] = 1L << ((r - 1) * 8 + f - 1);

      /* HQ slider masks */
      DIAG_MASK[sq] = diagMask(sq);
      ADIAG_MASK[sq] = adiagMask(sq);
      FILE_MASK[sq] = FILE_A << f;
      RANK_MASK[sq] = RANK_1 << (r * 8);

      DIAG_MASK_REV[sq] = Long.reverse(DIAG_MASK[sq]);
      ADIAG_MASK_REV[sq] = Long.reverse(ADIAG_MASK[sq]);
      FILE_MASK_REV[sq] = Long.reverse(FILE_MASK[sq]);
      RANK_MASK_REV[sq] = Long.reverse(RANK_MASK[sq]);
      FROM_REV[sq] = Long.reverse(1L << sq);
    }

    /* build masks first (edge-less) */
    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, f = sq & 7;
      long rookMask = 0, bishopMask = 0;
      for (int rr = r + 1; rr < 7; ++rr) rookMask |= 1L << (rr * 8 + f);
      for (int rr = r - 1; rr > 0; --rr) rookMask |= 1L << (rr * 8 + f);
      for (int ff = f + 1; ff < 7; ++ff) rookMask |= 1L << (r * 8 + ff);
      for (int ff = f - 1; ff > 0; --ff) rookMask |= 1L << (r * 8 + ff);
      for (int rr = r + 1, ff = f + 1; rr < 7 && ff < 7; ++rr, ++ff)
        bishopMask |= 1L << (rr * 8 + ff);
      for (int rr = r + 1, ff = f - 1; rr < 7 && ff > 0; ++rr, --ff)
        bishopMask |= 1L << (rr * 8 + ff);
      for (int rr = r - 1, ff = f + 1; rr > 0 && ff < 7; --rr, ++ff)
        bishopMask |= 1L << (rr * 8 + ff);
      for (int rr = r - 1, ff = f - 1; rr > 0 && ff > 0; --rr, --ff)
        bishopMask |= 1L << (rr * 8 + ff);
    }
  }

  @Override
  public int generate(long[] bb, int[] moves, GenMode mode) {

    /* ------------------------------------------------------------------
     *  side-to-move and bit-boards
     * ---------------------------------------------------------------- */
    final boolean white = whiteToMove(bb[META]);

    final int usP = white ? WP : BP;
    final int usN = white ? WN : BN;
    final int usB = white ? WB : BB;
    final int usR = white ? WR : BR;
    final int usQ = white ? WQ : BQ;
    final int usK = white ? WK : BK;

    long own   = white ? (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK])
            : (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK]);
    long enemy = white ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
            : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]);
    long occ   = own | enemy;

    /* ------------------------------------------------------------------
     *  pins, checks, enemySeen  (all in one pass)
     * ---------------------------------------------------------------- */
    int hdr = detectPinsChecksFast(
            bb, white, own, occ,
            TMP_PIN_FROM, TMP_PIN_RAY,
            TMP_PINNED, TMP_BLOCK, TMP_ENEMY);

    int  nCheck    =  hdr & 3;
    int  pinCnt    = (hdr >>> 2) & 0xF;
    long pinned    = TMP_PINNED[0];
    long blockMask = TMP_BLOCK [0];
    long enemySeen = TMP_ENEMY [0];

    boolean wantCapt  = (mode != GenMode.QUIETS);
    boolean wantQuiet = (mode != GenMode.CAPTURES);

    long captMask  = wantCapt  ? enemy : 0L;
    long quietMask = wantQuiet ? ~occ  : 0L;

    int n = 0;                     // cursor inside <moves> array

    /* ==================================================================
     *  PAWNS
     * =================================================================*/
    long pawns = bb[usP];
    int  dir   = white ?  8 : -8;
    long PROMO = white ? RANK_8 : RANK_1;

    /* -- single pushes ------------------------------------------------ */
    long oneAll = white ? ((pawns << 8) & ~occ)
            : ((pawns >>> 8) & ~occ);
    long one = oneAll & blockMask;

    // promotions
    for (long pp = one & PROMO; pp != 0; pp &= pp - 1) {
      int to   = Long.numberOfTrailingZeros(pp);
      int from = to - dir;
      if (!pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) continue;
      n = emitPromotions(moves, n, from, to, usP);
    }
    // quiet non-promos
    for (long q = one & ~PROMO; q != 0; q &= q - 1) {
      int to   = Long.numberOfTrailingZeros(q);
      int from = to - dir;
      if (!pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) continue;
      moves[n++] = mv(from, to, 0, usP);
    }

    /* -- double pushes (only if not double-check) --------------------- */
    if (wantQuiet && nCheck < 2) {
      long two = white ? (((oneAll & RANK_3) << 8) & ~occ)
              : (((oneAll & RANK_6) >>> 8) & ~occ);
      for (; two != 0; two &= two - 1) {
        int to   = Long.numberOfTrailingZeros(two);
        if ((blockMask & (1L<<to)) == 0) continue;
        int from = to - 2 * dir;
        if (!pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) continue;
        moves[n++] = mv(from, to, 0, usP);
      }
    }

    /* -- pawn captures ------------------------------------------------ */
    if (wantCapt) {
      long capL = white ? ((pawns & ~FILE_A) << 7)
              : ((pawns & ~FILE_H) >>> 7);
      long capR = white ? ((pawns & ~FILE_H) << 9)
              : ((pawns & ~FILE_A) >>> 9);

      capL &= enemy & blockMask;
      capR &= enemy & blockMask;

      long promL = capL & PROMO, promR = capR & PROMO;
      capL &= ~PROMO;  capR &= ~PROMO;

      final int dL = white ? -7 : 7;
      final int dR = white ? -9 : 9;

      for (; capL != 0; capL &= capL - 1) {
        int to   = Long.numberOfTrailingZeros(capL);
        int from = to + dL;
        if (!pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) continue;
        moves[n++] = mv(from, to, 0, usP);
      }
      for (; capR != 0; capR &= capR - 1) {
        int to   = Long.numberOfTrailingZeros(capR);
        int from = to + dR;
        if (!pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) continue;
        moves[n++] = mv(from, to, 0, usP);
      }
      for (; promL != 0; promL &= promL - 1) {
        int to   = Long.numberOfTrailingZeros(promL);
        int from = to + dL;
        if (!pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) continue;
        n = emitPromotions(moves, n, from, to, usP);
      }
      for (; promR != 0; promR &= promR - 1) {
        int to   = Long.numberOfTrailingZeros(promR);
        int from = to + dR;
        if (!pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) continue;
        n = emitPromotions(moves, n, from, to, usP);
      }
    }

    /* -- en-passant (legal even in single-check) ---------------------- */
    long epSq = (bb[META] & EP_MASK) >>> EP_SHIFT;
    if (wantCapt && epSq != 63 && nCheck <= 1) {
      long epBit = 1L << epSq;
      long epL   = white ? ((pawns & ~FILE_A) << 7) & epBit
              : ((pawns & ~FILE_H) >>> 7) & epBit;
      long epR   = white ? ((pawns & ~FILE_H) << 9) & epBit
              : ((pawns & ~FILE_A) >>> 9) & epBit;
      final int dL = white ? -7 : 7, dR = white ? -9 : 9;

      if (epL != 0) {
        int to = (int) epSq, from = to + dL;
        if (pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) {
          int capSq = white ? to - 8 : to + 8;
          if (epKingSafeFast(bb, from, to, capSq, white, occ))
            moves[n++] = mv(from, to, 2, usP);
        }
      }
      if (epR != 0) {
        int to = (int) epSq, from = to + dR;
        if (pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) {
          int capSq = white ? to - 8 : to + 8;
          if (epKingSafeFast(bb, from, to, capSq, white, occ))
            moves[n++] = mv(from, to, 2, usP);
        }
      }
    }

    /* ==================================================================
     *  KNIGHTS   (cannot block double-check)
     * =================================================================*/
    if (nCheck < 2) {
      long knights = bb[usN] & ~pinned;
      while (knights!=0) {
        int from = Long.numberOfTrailingZeros(knights); knights &= knights - 1;
        long tgt = KNIGHT_ATK[from] & (captMask | quietMask) & blockMask;
        while (tgt!=0) {
          int to = Long.numberOfTrailingZeros(tgt); tgt &= tgt - 1;
          moves[n++] = mv(from, to, 0, usN);
        }
      }
    }

    /* ==================================================================
     *  BISHOPS · ROOKS · QUEENS
     * =================================================================*/
    long sliders = bb[usB] | bb[usR] | bb[usQ];
    while (sliders != 0) {
      int  from    = Long.numberOfTrailingZeros(sliders); sliders &= sliders - 1;
      long fromBit = 1L << from;
      int  mover   = (fromBit & bb[usB]) != 0 ? usB
              : (fromBit & bb[usR]) != 0 ? usR
              : usQ;

      long att = (mover==usB) ? bishopAtt(occ, from)
              : (mover==usR) ? rookAtt  (occ, from)
              : queenAtt (occ, from);

      long tgt = att & (captMask | quietMask) & blockMask;
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt); tgt &= tgt - 1;
        if (!pinOk(from, 1L<<to, pinCnt, TMP_PIN_FROM, TMP_PIN_RAY, pinned)) continue;
        moves[n++] = mv(from, to, 0, mover);
      }
    }

    /* ==================================================================
     *  KING
     * =================================================================*/
    int  kSq     = Long.numberOfTrailingZeros(bb[usK]);
    long kingTgt = KING_ATK[kSq] & ~own & ~enemySeen;

    if (wantQuiet) {
      long qs = kingTgt & quietMask;
      while (qs != 0) {
        int to = Long.numberOfTrailingZeros(qs); qs &= qs - 1;
        moves[n++] = mv(kSq, to, 0, usK);
      }
    }
    if (wantCapt) {
      long cs = kingTgt & captMask;
      while (cs != 0) {
        int to = Long.numberOfTrailingZeros(cs); cs &= cs - 1;
        moves[n++] = mv(kSq, to, 0, usK);
      }
    }

    /* ==================================================================
     *  CASTLING  (only if not in check)
     * =================================================================*/
    if (wantQuiet && nCheck == 0) {
      int cr = (int)((bb[META] & CR_MASK) >>> CR_SHIFT);
      if (white) {
        if ((cr&1)!=0 && (bb[WR]&(1L<<7))!=0 &&
                (occ&0x60L)==0 && (enemySeen&0x70L)==0)
          moves[n++] = mv(4,6,3,WK);
        if ((cr&2)!=0 && (bb[WR]&(1L<<0))!=0 &&
                (occ&0x0EL)==0 && (enemySeen&0x1CL)==0)
          moves[n++] = mv(4,2,3,WK);
      } else {
        if ((cr&4)!=0 && (bb[BR]&(1L<<63))!=0 &&
                (occ&0x6000_0000_0000_0000L)==0 &&
                (enemySeen&0x7000_0000_0000_0000L)==0)
          moves[n++] = mv(60,62,3,BK);
        if ((cr&8)!=0 && (bb[BR]&(1L<<56))!=0 &&
                (occ&0x0E00_0000_0000_0000L)==0 &&
                (enemySeen&0x1C00_0000_0000_0000L)==0)
          moves[n++] = mv(60,58,3,BK);
      }
    }

    return n;
  }




  @Override
  public boolean kingAttacked(long[] bb, boolean moverWasWhite) {

    /* king square ------------------------------------------------------ */
    int kSq = Long.numberOfTrailingZeros(bb[moverWasWhite ? WK : BK]); // our king
    long kBit = 1L << kSq;

    /* aggregate once --------------------------------------------------- */
    long occ =
        bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK] | bb[BP] | bb[BN] | bb[BB] | bb[BR]
            | bb[BQ] | bb[BK];

    long pawns = moverWasWhite ? bb[BP] : bb[WP]; // attackers!
    long knights = moverWasWhite ? bb[BN] : bb[WN];
    long bishopsOrQueens = moverWasWhite ? (bb[BB] | bb[BQ]) : (bb[WB] | bb[WQ]);
    long rooksOrQueens = moverWasWhite ? (bb[BR] | bb[BQ]) : (bb[WR] | bb[WQ]);
    long theirKing = moverWasWhite ? bb[BK] : bb[WK];

    /* 1) pawn attacks -------------------------------------------------- */
    if ((pawns & (moverWasWhite ? PAWN_ATK_W[kSq] : PAWN_ATK_B[kSq])) != 0) return true;

    /* 2) knight attacks ------------------------------------------------ */
    if ((KNIGHT_ATK[kSq] & knights) != 0) return true;

    /* 3) bishop / queen diagonals ------------------------------------- */
    if ((bishopAtt(occ, kSq) & bishopsOrQueens) != 0) return true;

    /* 4) rook / queen files & ranks ----------------------------------- */
    if ((rookAtt(occ, kSq) & rooksOrQueens) != 0) return true;

    /* 5) opposing king ------------------------------------------------- */
    return (KING_ATK[kSq] & theirKing) != 0;
  }

  private static int detectPinsChecksFast(
          long[] bb, boolean white, long own, long occ,
          int[]  pinFrom, long[] pinRay,          // OUT  max 8
          long[] pinnedOut, long[] blockOut,      // OUT  size 1
          long[] enemySeenOut                     // OUT  size 1
  ) {
    final int kSq = Long.numberOfTrailingZeros(bb[white ? WK : BK]);

    /* ---- direct checkers ------------------------------------------- */
    long checkers = white ? (PAWN_ATK_W[kSq] & bb[BP])
            : (PAWN_ATK_B[kSq] & bb[WP]);

    checkers |= KNIGHT_ATK[kSq] & (white ? bb[BN] : bb[WN]);

    /* ---- slider groups --------------------------------------------- */
    long eb = white ? bb[BB] : bb[WB];
    long er = white ? bb[BR] : bb[WR];
    long eq = white ? bb[BQ] : bb[WQ];

    long bishopsOrQ = eb | eq;
    long rooksOrQ   = er | eq;
    long sliders    = bishopsOrQ | rooksOrQ;

    checkers |= bishopAtt(occ, kSq) & bishopsOrQ;
    checkers |= rookAtt  (occ, kSq) & rooksOrQ;

    /* ---- pins ------------------------------------------------------- */
    long pinnedMask = 0L;
    int  pinCnt     = 0;

    for (long s = sliders; s != 0; s &= s - 1) {
      int  sq  = Long.numberOfTrailingZeros(s);
      long btw = BETWEEN[kSq][sq];
      if (btw == 0) continue;                     // not on a line

      long blk = btw & occ;
      if ((blk & (blk - 1)) != 0 || (blk & own) == 0) continue; // 0/≥2 blockers or theirs

      /* slider kind must match the ray -------------------------------- */
      boolean diag = (Math.abs((kSq>>>3) - (sq>>>3)) ==
              Math.abs((kSq & 7)   - (sq & 7)));
      if (diag ? ((bishopsOrQ & (1L<<sq))==0)
              : ((rooksOrQ   & (1L<<sq))==0))   continue;

      pinnedMask |= blk;
      if (pinCnt < 8) {
        pinFrom[pinCnt] = Long.numberOfTrailingZeros(blk);
        pinRay [pinCnt] = btw | (1L<<sq);      // legal squares for the pinned piece
        ++pinCnt;
      }
    }

    /* ---- block mask ------------------------------------------------- */
    int  nCheck    = Long.bitCount(checkers);
    long blockMask = (nCheck==0) ? -1L
            : (nCheck==1) ? (BETWEEN[kSq][Long.numberOfTrailingZeros(checkers)] | checkers)
            : 0L;

    /* ---- enemy attack map (for king moves) -------------------------- */
    long seen = 0L;

    long n = white ? bb[BN] : bb[WN];
    while (n!=0) { int s = Long.numberOfTrailingZeros(n); n&=n-1; seen |= KNIGHT_ATK[s]; }

    long p = white ? bb[BP] : bb[WP];
    seen |= white ? ((p & ~FILE_A) >>> 9) | ((p & ~FILE_H) >>> 7)
            : ((p & ~FILE_H) <<  9) | ((p & ~FILE_A) <<  7);

    long ok = white ? bb[BK] : bb[WK];
    if (ok!=0) seen |= KING_ATK[Long.numberOfTrailingZeros(ok)] | ok;

    seen |= allSliderAttacks(bb, occ, white);

    /* ---- write OUT params ------------------------------------------- */
    pinnedOut   [0] = pinnedMask;
    blockOut    [0] = blockMask;
    enemySeenOut[0] = seen;

    return (nCheck & 3) | ((pinCnt & 0xF) << 2);
  }

  private static boolean pinOk(int from, long toBit,
                               int pinCnt, int[] pinFrom, long[] pinRay,
                               long pinnedMask) {
    if ((pinnedMask & (1L<<from)) == 0) return true;        // piece is not pinned
    for (int i = 0; i < pinCnt; i++)
      if (pinFrom[i] == from)
        return (pinRay[i] & toBit) != 0;
    return false;                                           // pinned & illegal target
  }

  /* ───────────────── helper to emit the 4 promotion types ─────── */
  private static int emitPromotions(int[] moves, int n, int from, int to, int mover) {
    int base = mv(from, to, 1, mover); // flag 1 = promotion
    moves[n++] = base | (3 << 12); // Q
    moves[n++] = base | (2 << 12); // R
    moves[n++] = base | (1 << 12); // B
    moves[n++] = base; // N
    return n;
  }

  /* =======================================================================
   *  Aggregated enemy-attack map
   * ===================================================================== */
  private static long buildEnemyAttacks(long[] bb, boolean white, long occ) {

    long atk = 0L;

    /* knights */
    long n = white ? bb[BN] : bb[WN];
    while (n != 0L) {
      int sq = Long.numberOfTrailingZeros(n);
      n &= n - 1;
      atk |= KNIGHT_ATK[sq];
    }

    /* pawns */
    long p = white ? bb[BP] : bb[WP];
    atk |=
        white
            ? ((p & ~FILE_A) >>> 9) | ((p & ~FILE_H) >>> 7) // black pawn attacks ↙ ↘
            : ((p & ~FILE_H) << 9) | ((p & ~FILE_A) << 7); // white pawn attacks ↗ ↖

    /* enemy king “zone” */
    long oppK = white ? bb[BK] : bb[WK];
    if (oppK != 0L) atk |= KING_ATK[Long.numberOfTrailingZeros(oppK)] | oppK;

    /* slider rays */
    atk |= allSliderAttacks(bb, occ, white);

    return atk;
  }

  /* rooks / bishops / queens — all attacked squares (excl. the slider itself) */
  private static long allSliderAttacks(long[] bb, long occ, boolean white) {

    long rays = 0L;

    long rooks = white ? (bb[BR] | bb[BQ]) : (bb[WR] | bb[WQ]);
    while (rooks != 0L) {
      int sq = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      rays |= rookAtt(occ, sq);
    }

    long bishops = white ? (bb[BB] | bb[BQ]) : (bb[WB] | bb[WQ]);
    while (bishops != 0L) {
      int sq = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      rays |= bishopAtt(occ, sq);
    }
    return rays;
  }

  /* strict — but *light-weight* — EP legality (rook/ bishop discover only) */
  private static boolean epKingSafeFast(
      long[] bb, int from, int to, int capSq, boolean white, long occ) {

    long occNew = occ ^ (1L << from) ^ (1L << to) ^ (1L << capSq);
    int kSq = Long.numberOfTrailingZeros(bb[white ? WK : BK]);

    long rookOrQ = white ? (bb[BR] | bb[BQ]) : (bb[WR] | bb[WQ]);
    if ((rookAtt(occNew, kSq) & rookOrQ) != 0L) return false;

    long bishopOrQ = white ? (bb[BB] | bb[BQ]) : (bb[WB] | bb[WQ]);
    return (bishopAtt(occNew, kSq) & bishopOrQ) == 0L;
  }

  /* ── LOOKUP helpers (runtime) ─────────────────────────────────── */
  private static long rookAtt(long occ, int sq) {
    int idx = (int) (((occ | R_MASK[sq]) * R_HASH[sq]) >>> 52);
    return LOOKUP_TABLE[R_BASE[sq] + idx];
  }

  private static long bishopAtt(long occ, int sq) {
    int idx = (int) (((occ | B_MASK[sq]) * B_HASH[sq]) >>> 55);
    return LOOKUP_TABLE[B_BASE[sq] + idx];
  }

  private static long queenAtt(long occ, int sq) {
    return rookAtt(occ, sq) | bishopAtt(occ, sq);
  }

  /* ───────────────── misc small helpers ───────────────────────── */
  private static long addToMask(long m, int r, int f) {
    return (r >= 0 && r < 8 && f >= 0 && f < 8) ? m | (1L << ((r << 3) | f)) : m;
  }

  private static long knightMask(int r, int f) {
    long m = 0;
    int[] dr = {-2, -1, 1, 2, 2, 1, -1, -2};
    int[] df = {1, 2, 2, 1, -1, -2, -2, -1};
    for (int i = 0; i < 8; i++) m = addToMask(m, r + dr[i], f + df[i]);
    return m;
  }

  private static long diagMask(int sq) {
    int r = sq >>> 3, f = sq & 7;
    long m = 0;
    for (int d = -7; d <= 7; d++) m = addToMask(m, r + d, f + d);
    return m;
  }

  private static long adiagMask(int sq) {
    int r = sq >>> 3, f = sq & 7;
    long m = 0;
    for (int d = -7; d <= 7; d++) m = addToMask(m, r + d, f - d);
    return m;
  }

  private static int mv(int from, int to, int flags, int mover) {
    return (from << 6) | to | (flags << 14) | (mover << MOVER_SHIFT);
  }
}
