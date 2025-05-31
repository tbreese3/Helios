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

  /* ── public entry point ───────────────────────────────────────── */
  @Override
  public int generate(long[] bb, int[] moves, GenMode mode) {

    /* --------------------------------------------------------------------
     *  side set-up
     * ------------------------------------------------------------------ */
    final boolean white = whiteToMove(bb[META]);

    final int usP = white ? WP : BP;
    final int usN = white ? WN : BN;
    final int usB = white ? WB : BB;
    final int usR = white ? WR : BR;
    final int usQ = white ? WQ : BQ;
    final int usK = white ? WK : BK;

    long whitePieces = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
    long blackPieces = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    long own = white ? whitePieces : blackPieces;
    long enemy = white ? blackPieces : whitePieces;
    long occ = own | enemy;

    /* --------------------------------------------------------------------
     *  one-time enemy attack mask  (kills all squareAttacked() calls)
     * ------------------------------------------------------------------ */
    long enemySeen = buildEnemyAttacks(bb, white, occ);

    boolean wantCapt = mode != GenMode.QUIETS;
    boolean wantQuiet = mode != GenMode.CAPTURES;

    long captMask = wantCapt ? enemy : 0L;
    long quietMask = wantQuiet ? ~occ : 0L;

    int n = 0; // write cursor in <moves>

    /* ====================================================================
     *  PAWNS
     * ================================================================== */
    long pawns = bb[usP];
    int pushDir = white ? 8 : -8;
    long PROMO_RANK = white ? RANK_8 : RANK_1;

    /* single pushes (incl. promotions) --------------------------------- */
    long one = white ? ((pawns << 8) & ~occ) : ((pawns >>> 8) & ~occ);

    if (wantQuiet) {
      /* promotions on push */
      long promoPush = one & PROMO_RANK;
      while (promoPush != 0L) {
        int to = Long.numberOfTrailingZeros(promoPush);
        promoPush &= promoPush - 1;
        n = emitPromotions(moves, n, to - pushDir, to, usP);
      }

      /* quiet non-promo pushes */
      long quiet = one & ~PROMO_RANK;
      while (quiet != 0L) {
        int to = Long.numberOfTrailingZeros(quiet);
        quiet &= quiet - 1;
        moves[n++] = mv(to - pushDir, to, 0, usP);
      }

      /* double pushes */
      long two = white ? (((one & RANK_3) << 8) & ~occ) : (((one & RANK_6) >>> 8) & ~occ);
      while (two != 0L) {
        int to = Long.numberOfTrailingZeros(two);
        two &= two - 1;
        moves[n++] = mv(to - 2 * pushDir, to, 0, usP);
      }
    }

    /* captures (incl. promo captures) ---------------------------------- */
    if (wantCapt) {
      long capL, capR;
      if (white) {
        capL = ((pawns & ~FILE_A) << 7);
        capR = ((pawns & ~FILE_H) << 9);
      } else {
        capL = ((pawns & ~FILE_H) >>> 7);
        capR = ((pawns & ~FILE_A) >>> 9);
      }

      long promoL = capL & enemy & PROMO_RANK;
      long promoR = capR & enemy & PROMO_RANK;
      capL &= enemy & ~PROMO_RANK;
      capR &= enemy & ~PROMO_RANK;

      final int deltaL = white ? -7 : 7;
      final int deltaR = white ? -9 : 9;

      while (capL != 0L) {
        int to = Long.numberOfTrailingZeros(capL);
        capL &= capL - 1;
        moves[n++] = mv(to + deltaL, to, 0, usP);
      }
      while (capR != 0L) {
        int to = Long.numberOfTrailingZeros(capR);
        capR &= capR - 1;
        moves[n++] = mv(to + deltaR, to, 0, usP);
      }
      while (promoL != 0L) {
        int to = Long.numberOfTrailingZeros(promoL);
        promoL &= promoL - 1;
        n = emitPromotions(moves, n, to + deltaL, to, usP);
      }
      while (promoR != 0L) {
        int to = Long.numberOfTrailingZeros(promoR);
        promoR &= promoR - 1;
        n = emitPromotions(moves, n, to + deltaR, to, usP);
      }
    }

    /* en-passant -------------------------------------------------------- */
    long epSqRaw = (bb[META] & EP_MASK) >>> EP_SHIFT;
    if (wantCapt && epSqRaw != 63) {
      long epBit = 1L << epSqRaw;

      long epL, epR;
      if (white) {
        epL = ((pawns & ~FILE_A) << 7) & epBit;
        epR = ((pawns & ~FILE_H) << 9) & epBit;
      } else {
        epL = ((pawns & ~FILE_H) >>> 7) & epBit;
        epR = ((pawns & ~FILE_A) >>> 9) & epBit;
      }

      final int deltaL = white ? -7 : 7;
      final int deltaR = white ? -9 : 9;

      while (epL != 0L) {
        int to = Long.numberOfTrailingZeros(epL);
        epL &= epL - 1;
        int capSq = white ? to - 8 : to + 8;
        if (epKingSafeFast(bb, to + deltaL, to, capSq, white, occ))
          moves[n++] = mv(to + deltaL, to, 2, usP);
      }
      while (epR != 0L) {
        int to = Long.numberOfTrailingZeros(epR);
        epR &= epR - 1;
        int capSq = white ? to - 8 : to + 8;
        if (epKingSafeFast(bb, to + deltaR, to, capSq, white, occ))
          moves[n++] = mv(to + deltaR, to, 2, usP);
      }
    }

    /* ====================================================================
     *  KNIGHTS
     * ================================================================== */
    long knights = bb[usN];
    while (knights != 0L) {
      int from = Long.numberOfTrailingZeros(knights);
      knights &= knights - 1;
      long tgt = KNIGHT_ATK[from] & (captMask | quietMask);
      while (tgt != 0L) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        moves[n++] = mv(from, to, 0, usN);
      }
    }

    /* ====================================================================
     *  BISHOPS  –  ROOKS  –  QUEENS   (branch-free slider loops)
     * ================================================================== */

    /* bishops ----------------------------------------------------------- */
    for (long b = bb[usB]; b != 0; b &= b - 1) {
      int from = Long.numberOfTrailingZeros(b);
      int idxB = (int)(((occ | B_MASK[from]) * B_HASH[from]) >>> 55);
      long tgt = LOOKUP_TABLE[B_BASE[from] + idxB] & (captMask | quietMask);
      if (tgt == 0) continue;
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        moves[n++] = mv(from, to, 0, usB);
      }
    }

    /* rooks ------------------------------------------------------------- */
    for (long r = bb[usR]; r != 0; r &= r - 1) {
      int from = Long.numberOfTrailingZeros(r);
      int idxR = (int)(((occ | R_MASK[from]) * R_HASH[from]) >>> 52);
      long tgt = LOOKUP_TABLE[R_BASE[from] + idxR] & (captMask | quietMask);
      if (tgt == 0) continue;
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        moves[n++] = mv(from, to, 0, usR);
      }
    }

    /* queens ------------------------------------------------------------ */
    for (long q = bb[usQ]; q != 0; q &= q - 1) {
      int from  = Long.numberOfTrailingZeros(q);

      /* rook component */
      int idxR  = (int)(((occ | R_MASK[from]) * R_HASH[from]) >>> 52);
      long rookT   = LOOKUP_TABLE[R_BASE[from] + idxR];

      /* bishop component */
      int idxB  = (int)(((occ | B_MASK[from]) * B_HASH[from]) >>> 55);
      long bishopT = LOOKUP_TABLE[B_BASE[from] + idxB];

      long tgt = (rookT | bishopT) & (captMask | quietMask);
      if (tgt == 0) continue;
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        moves[n++] = mv(from, to, 0, usQ);
      }
    }

    /* ====================================================================
     *  KING  (legalised by enemySeen mask)
     * ================================================================== */
    int kingSq = Long.numberOfTrailingZeros(bb[usK]);
    long kingMovesAll = KING_ATK[kingSq] & ~own & ~enemySeen;

    if (wantQuiet) {
      long qs = kingMovesAll & quietMask;
      while (qs != 0L) {
        int to = Long.numberOfTrailingZeros(qs);
        qs &= qs - 1;
        moves[n++] = mv(kingSq, to, 0, usK);
      }
    }
    if (wantCapt) {
      long cs = kingMovesAll & captMask;
      while (cs != 0L) {
        int to = Long.numberOfTrailingZeros(cs);
        cs &= cs - 1;
        moves[n++] = mv(kingSq, to, 0, usK);
      }
    }

    /* ====================================================================
     *  CASTLING   (enemySeen replaces three squareAttacked() calls)
     * ================================================================== */
    if (wantQuiet) {
      int rights = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);

      if (white) {
        /* 0-0  (e1-f1-g1) */
        if ((rights & 1) != 0
                && ((bb[WR] & (1L << 7)) != 0)
                && ((occ & 0x60L) == 0)
                && (enemySeen & 0x70L) == 0) moves[n++] = mv(4, 6, 3, WK);

        /* 0-0-0 (e1-d1-c1) */
        if ((rights & 2) != 0
                && ((bb[WR] & (1L << 0)) != 0)
                && ((occ & 0x0EL) == 0)
                && (enemySeen & 0x1CL) == 0) moves[n++] = mv(4, 2, 3, WK);

      } else {
        /* 0-0  (e8-f8-g8) */
        if ((rights & 4) != 0
                && ((bb[BR] & (1L << 63)) != 0)
                && ((occ & 0x6000_0000_0000_0000L) == 0)
                && (enemySeen & 0x7000_0000_0000_0000L) == 0) moves[n++] = mv(60, 62, 3, BK);

        /* 0-0-0 (e8-d8-c8) */
        if ((rights & 8) != 0
                && ((bb[BR] & (1L << 56)) != 0)
                && ((occ & 0x0E00_0000_0000_0000L) == 0)
                && (enemySeen & 0x1C00_0000_0000_0000L) == 0) moves[n++] = mv(60, 58, 3, BK);
      }
    }

    return n; // total number of moves emitted
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
    int idx = (int) (((occ | B_MASK[kSq]) * B_HASH[kSq]) >>> 55);
    if ((LOOKUP_TABLE[B_BASE[kSq] + idx] & bishopsOrQueens) != 0) return true;

    /* 4) rook / queen files & ranks ----------------------------------- */
    idx = (int) (((occ | R_MASK[kSq]) * R_HASH[kSq]) >>> 52);
    if ((LOOKUP_TABLE[R_BASE[kSq] + idx] & rooksOrQueens) != 0) return true;

    /* 5) opposing king ------------------------------------------------- */
    return (KING_ATK[kSq] & theirKing) != 0;
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
      int idx = (int) (((occ | R_MASK[sq]) * R_HASH[sq]) >>> 52);
      rays |= LOOKUP_TABLE[R_BASE[sq] + idx];
    }

    long bishops = white ? (bb[BB] | bb[BQ]) : (bb[WB] | bb[WQ]);
    while (bishops != 0L) {
      int sq = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      int idx = (int) (((occ | B_MASK[sq]) * B_HASH[sq]) >>> 55);
      rays |= LOOKUP_TABLE[B_BASE[sq] + idx];
    }
    return rays;
  }

  /* strict — but *light-weight* — EP legality (rook/ bishop discover only) */
  private static boolean epKingSafeFast(
          long[] bb, int from, int to, int capSq, boolean white, long occ) {

    long occNew = occ ^ (1L << from) ^ (1L << to) ^ (1L << capSq);
    int kSq = Long.numberOfTrailingZeros(bb[white ? WK : BK]);

    long rookOrQ = white ? (bb[BR] | bb[BQ]) : (bb[WR] | bb[WQ]);
    int idx = (int) (((occNew | R_MASK[kSq]) * R_HASH[kSq]) >>> 52);
    if ((LOOKUP_TABLE[R_BASE[kSq] + idx] & rookOrQ) != 0L) return false;

    long bishopOrQ = white ? (bb[BB] | bb[BQ]) : (bb[WB] | bb[WQ]);
    idx = (int) (((occNew | B_MASK[kSq]) * B_HASH[kSq]) >>> 55);
    return (LOOKUP_TABLE[B_BASE[kSq] + idx] & bishopOrQ) == 0L;
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
