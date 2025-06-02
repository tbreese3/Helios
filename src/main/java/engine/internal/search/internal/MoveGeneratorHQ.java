package engine.internal.search.internal;

import static engine.internal.search.PackedPositionFactory.*;
import static engine.internal.search.internal.PreCompMoveGenTables.*;

import engine.internal.search.MoveGenerator;
import java.util.Locale;

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

  private static final int MOVER_SHIFT = 16;

  /* ── public entry point ───────────────────────────────────────── */
  public int generateAll(long[] bb, int[] mv)
  {
    final boolean white = whiteToMove(bb[META]);

    /* side-dependant indexes once ------------------------------------ */
    final int usP = white ? WP : BP,  usN = white ? WN : BN,
            usB = white ? WB : BB,  usR = white ? WR : BR,
            usQ = white ? WQ : BQ,  usK = white ? WK : BK;

    /* board masks ---------------------------------------------------- */
    long own   = white ? (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK])
            : (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK]);
    long enemy = white ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
            : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]);
    long occ         = own | enemy;
    long captMask    = enemy;
    long quietMask   = ~occ;
    long allTargets  = captMask | quietMask;     // reused in 3 loops

    int n = 0;

    /* 1 ───────── PAWNS (pushes + captures + EP) ──────────────────── */
    n = addPawnPushes(bb[usP], white, occ, mv, n, usP,
            /*Q?*/ true, /*RBN?*/ true,
            /*quiet?*/ true, /*double?*/ true);
    n = addPawnCaptures(bb,      white, occ, enemy, mv, n, usP);

    /* 2 ───────── KNIGHTS ─────────────────────────────────────────── */
    n = addKnightMoves(bb[usN], allTargets, mv, n, usN);

    /* 3 ───────── BISHOPS / ROOKS / QUEENS (branch-free) ──────────── */
    long bishops = bb[usB];
    while (bishops != 0) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allTargets;
      n = emitSliderMoves(mv, n, from, tgt, usB);
    }

    long rooks = bb[usR];
    while (rooks != 0) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allTargets;
      n = emitSliderMoves(mv, n, from, tgt, usR);
    }

    long queens = bb[usQ];
    while (queens != 0) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allTargets;
      n = emitSliderMoves(mv, n, from, tgt, usQ);
    }

    /* 4 ───────── KING & CASTLING  (enemy map needed **now**) ─────── */
    long enemySeen = buildEnemyAttacks(bb, white, occ);
    n = addKingMovesAndCastle(bb, white, occ, enemySeen,
            captMask, quietMask, mv, n, usK);

    return n;
  }

  public int generateCaptures(long[] bb, int[] mv) {
    final boolean white = whiteToMove(bb[META]);

    final int usP = white ? WP : BP,  usN = white ? WN : BN,
            usB = white ? WB : BB,  usR = white ? WR : BR,
            usQ = white ? WQ : BQ,  usK = white ? WK : BK;

    long own   = white ? (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK])
            : (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK]);
    long enemy = white ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
            : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]);
    long occ        = own | enemy;
    long captMask   = enemy;          // captures only
    long allCapt    = captMask;       // alias, used in loops

    int n = 0;

    /* pawns (incl. promo-captures & EP) */
    n = addPawnCaptures(bb, white, occ, enemy, mv, n, usP);
    n = addPawnPushes(bb[usP], white, occ, mv, n, usP,
            /*Q?*/ true,  /*RBN?*/ false,   // queen-promo only
            /*quiet?*/ false, /*double?*/ false);


    /* knights */
    n = addKnightMoves(bb[usN], allCapt, mv, n, usN);

    /* bishops */
    long bishops = bb[usB];
    while (bishops != 0) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usB);
    }

    /* rooks */
    long rooks = bb[usR];
    while (rooks != 0) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usR);
    }

    /* queens */
    long queens = bb[usQ];
    while (queens != 0) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usQ);
    }

    /* king captures (no quiets, no castle) */
    long enemySeen = buildEnemyAttacks(bb, white, occ);
    n = addKingMovesAndCastle(bb, white, occ, enemySeen,
            captMask, 0L, mv, n, usK);
    return n;
  }

  public int generateQuiets(long[] bb, int[] mv) {
    final boolean white = whiteToMove(bb[META]);

    final int usP = white ? WP : BP,  usN = white ? WN : BN,
            usB = white ? WB : BB,  usR = white ? WR : BR,
            usQ = white ? WQ : BQ,  usK = white ? WK : BK;

    long own   = white ? (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK])
            : (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK]);
    long occ   = own |
            (white ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
                    : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]));
    long quietMask  = ~occ;
    long allQuiet   = quietMask;      // alias

    int n = 0;

    /* pawns: pushes, promos, doubles */
    n = addPawnPushes(bb[usP], white, occ, mv, n, usP,
            /*Q?*/ false, /*RBN?*/ true,
            /*quiet?*/ true,  /*double?*/ true);

    /* knights */
    n = addKnightMoves(bb[usN], allQuiet, mv, n, usN);

    /* bishops */
    long bishops = bb[usB];
    while (bishops != 0) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usB);
    }

    /* rooks */
    long rooks = bb[usR];
    while (rooks != 0) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usR);
    }

    /* queens */
    long queens = bb[usQ];
    while (queens != 0) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usQ);
    }

    /* king quiets + castling */
    long enemySeen = buildEnemyAttacks(bb, white, occ);
    n = addKingMovesAndCastle(bb, white, occ, enemySeen,
            0L, quietMask, mv, n, usK);
    return n;
  }

  public int generateEvasions(long[] bb, int[] mv) {
    final boolean white = whiteToMove(bb[META]);
    final int usP = white ? WP : BP,  usN = white ? WN : BN,
            usB = white ? WB : BB,  usR = white ? WR : BR,
            usQ = white ? WQ : BQ,  usK = white ? WK : BK;

    long own   = white ? (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK])
            : (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK]);
    long enemy = white ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
            : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]);
    long occ   = own | enemy;

    int  kSq        = Long.numberOfTrailingZeros(bb[usK]);
    long checkers   = attackersToSquare(bb, occ, kSq, white);
    long doubleChk  = checkers & (checkers - 1);

    int n = 0;

    /* 1 ───────── double check  → king only -- enemySeen needed here */
    if (doubleChk != 0) {
      long enemySeen = buildEnemyAttacks(bb, white, occ);
      long kingMoves = KING_ATK[kSq] & ~own & ~enemySeen;
      while (kingMoves != 0) {
        int to = Long.numberOfTrailingZeros(kingMoves);
        kingMoves &= kingMoves - 1;
        mv[n++] = mv(kSq, to, 0, usK);
      }
      return n;
    }

    /* 2 ───────── single check: target squares to capture/block */
    int  checkerSq = Long.numberOfTrailingZeros(checkers);
    long target    = checkers | betweenBB(kSq, checkerSq);

    /* 2a – king moves (need enemySeen) */
    long enemySeen = buildEnemyAttacks(bb, white, occ);
    long kingTgt   = KING_ATK[kSq] & ~own & ~enemySeen;
    while (kingTgt != 0) {
      int to = Long.numberOfTrailingZeros(kingTgt);
      kingTgt &= kingTgt - 1;
      mv[n++] = mv(kSq, to, 0, usK);
    }

    /* 2b – other pieces that hit ‘target’ */
    n = addPawnCapturesTarget(bb, white, occ, enemy, mv, n, usP, target);
    n = addPawnPushBlocks   (bb[usP], white, occ, mv, n, usP, target);
    n = addKnightEvasions   (bb[usN],           target, mv, n, usN);

    long bishops = bb[usB];
    while (bishops != 0) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long ray = bishopAtt(occ, from) & target;
      n = emitSliderMoves(mv, n, from, ray, usB);
    }

    long rooks = bb[usR];
    while (rooks != 0) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long ray = rookAtt(occ, from) & target;
      n = emitSliderMoves(mv, n, from, ray, usR);
    }

    long queens = bb[usQ];
    while (queens != 0) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long ray = queenAtt(occ, from) & target;
      n = emitSliderMoves(mv, n, from, ray, usQ);
    }
    return n;
  }

  private static int emitSliderMoves(int[] mv, int n, int from,
                                     long tgt, int piece) {
    while (tgt != 0) {
      int to = Long.numberOfTrailingZeros(tgt);
      tgt &= tgt - 1;
      mv[n++] = mv(from, to, 0, piece);
    }
    return n;
  }

  /* pawn pushes, promotions & double-pushes — never emits captures */
  private static int addPawnPushes(
          long pawns, boolean white, long occ,
          int[] mv, int n, int usP,
          boolean includeQueenPromo,   // emit Q?
          boolean includeUnderPromo,   // emit R/B/N?
          boolean includeQuietPush,    // 1-square non-promo push
          boolean includeDoublePush)   // 2-square push
  {
    final int dir  = white ? 8 : -8;
    final long one = white ? ((pawns << 8) & ~occ)
            : ((pawns >>> 8) & ~occ);
    final long PROMO = white ? RANK_8 : RANK_1;

    /* ── promotions on push ─────────────────────────────────────── */
    long promo = one & PROMO;
    while (promo != 0L) {
      int to = Long.numberOfTrailingZeros(promo);
      promo &= promo - 1;

      if (includeQueenPromo && includeUnderPromo)
        n = emitPromotions      (mv, n, to - dir, to, usP);      // Q R B N
      else if (includeQueenPromo)
        n = emitQueenPromotion  (mv, n, to - dir, to, usP);      // Q
      else if (includeUnderPromo)
        n = emitUnderPromotions (mv, n, to - dir, to, usP);      // R B N
    }

    /* ── quiet non-promo single pushes ──────────────────────────── */
    if (includeQuietPush) {
      long quiet = one & ~PROMO;
      while (quiet != 0L) {
        int to = Long.numberOfTrailingZeros(quiet);
        quiet &= quiet - 1;
        mv[n++] = mv(to - dir, to, 0, usP);
      }
    }

    /* ── double pushes ──────────────────────────────────────────── */
    if (includeDoublePush) {
      long rank3 = white ? RANK_3 : RANK_6;
      long two   = white ? (((one & rank3) << 8)  & ~occ)
              : (((one & rank3) >>> 8) & ~occ);
      while (two != 0L) {
        int to = Long.numberOfTrailingZeros(two);
        two &= two - 1;
        mv[n++] = mv(to - 2 * dir, to, 0, usP);
      }
    }
    return n;
  }

  /* pawn captures, promo-captures & en-passant — never emits pushes */
  private static int addPawnCaptures(long[] bb, boolean white, long occ,
                                     long enemy, int[] mv, int n, int usP) {

    long pawns = bb[usP];
    long PROMO = white ? RANK_8 : RANK_1;

    long capL = white ? ((pawns & ~FILE_A) << 7)
            : ((pawns & ~FILE_H) >>> 7);
    long capR = white ? ((pawns & ~FILE_H) << 9)
            : ((pawns & ~FILE_A) >>> 9);

    long promoL = capL & enemy & PROMO;
    long promoR = capR & enemy & PROMO;
    capL &= enemy & ~PROMO;
    capR &= enemy & ~PROMO;

    final int dL = white ? -7 : 7,  dR = white ? -9 : 9;

    while (capL != 0) {
      int to = Long.numberOfTrailingZeros(capL);
      capL &= capL - 1;
      mv[n++] = mv(to + dL, to, 0, usP);
    }
    while (capR != 0) {
      int to = Long.numberOfTrailingZeros(capR);
      capR &= capR - 1;
      mv[n++] = mv(to + dR, to, 0, usP);
    }
    while (promoL != 0) {
      int to = Long.numberOfTrailingZeros(promoL);
      promoL &= promoL - 1;
      n = emitPromotions(mv, n, to + dL, to, usP);
    }
    while (promoR != 0) {
      int to = Long.numberOfTrailingZeros(promoR);
      promoR &= promoR - 1;
      n = emitPromotions(mv, n, to + dR, to, usP);
    }

    /* en-passant (needs a quick legality check) */
    long epSqRaw = (bb[META] & EP_MASK) >>> EP_SHIFT;
    if (epSqRaw != 63) {
      long epBit = 1L << epSqRaw;
      long epL = white ? ((pawns & ~FILE_A) << 7) & epBit
              : ((pawns & ~FILE_H) >>> 7) & epBit;
      long epR = white ? ((pawns & ~FILE_H) << 9) & epBit
              : ((pawns & ~FILE_A) >>> 9) & epBit;
      while (epL != 0) {
        int to = Long.numberOfTrailingZeros(epL);
        epL &= epL - 1;
        int capSq = white ? to - 8 : to + 8;
        mv[n++] = mv(to+dL, to, 2, usP);
      }
      while (epR != 0) {
        int to = Long.numberOfTrailingZeros(epR);
        epR &= epR - 1;
        int capSq = white ? to - 8 : to + 8;
        mv[n++] = mv(to+dR, to, 2, usP);
      }
    }
    return n;
  }

  private static int addKnightMoves(long knights, long targetMask,
                                    int[] mv, int n, int usN) {
    while (knights != 0) {
      int from = Long.numberOfTrailingZeros(knights);
      knights &= knights - 1;
      long tgt = KNIGHT_ATK[from] & targetMask;
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        mv[n++] = mv(from, to, 0, usN);
      }
    }
    return n;
  }

  private static int addKingMovesAndCastle(long[] bb, boolean white,
                                           long occ, long enemySeen,
                                           long captMask, long quietMask,
                                           int[] mv, int n, int usK) {

    int  kSq       = Long.numberOfTrailingZeros(bb[usK]);

    /* own pieces only – enemy squares stay available for capture */
    long own       = occ & ~captMask;

    long moves     = KING_ATK[kSq] & ~own & ~enemySeen;   // legal destinations

    /* ---- quiet king moves ----------------------------------------- */
    long qs = moves & quietMask;
    while (qs != 0) {
      int to = Long.numberOfTrailingZeros(qs);
      qs &= qs - 1;
      mv[n++] = mv(kSq, to, 0, usK);
    }

    /* ---- king captures -------------------------------------------- */
    long cs = moves & captMask;
    while (cs != 0) {
      int to = Long.numberOfTrailingZeros(cs);
      cs &= cs - 1;
      mv[n++] = mv(kSq, to, 0, usK);
    }

    /* ---- castling (only if we were asked for quiet moves) ---------- */
    if (quietMask != 0) {
      int rights = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);

      if (white) {
        /* 0-0 */
        if ((rights & 1) != 0 && ((bb[WR] & (1L<<7))!=0)
                && ((occ & 0x60L)==0) && (enemySeen & 0x70L)==0)
          mv[n++] = mv(4,6,3,WK);
        /* 0-0-0 */
        if ((rights & 2) != 0 && ((bb[WR] & (1L<<0))!=0)
                && ((occ & 0x0EL)==0) && (enemySeen & 0x1CL)==0)
          mv[n++] = mv(4,2,3,WK);
      }
      else {
        /* 0-0 */
        if ((rights & 4) != 0 && ((bb[BR] & (1L<<63))!=0)
                && ((occ & 0x6000_0000_0000_0000L)==0)
                && (enemySeen & 0x7000_0000_0000_0000L)==0)
          mv[n++] = mv(60,62,3,BK);
        /* 0-0-0 */
        if ((rights & 8) != 0 && ((bb[BR] & (1L<<56))!=0)
                && ((occ & 0x0E00_0000_0000_0000L)==0)
                && (enemySeen & 0x1C00_0000_0000_0000L)==0)
          mv[n++] = mv(60,58,3,BK);
      }
    }
    return n;
  }

  /** squares strictly between two aligned squares (0 if not on same ray) */
  private static long betweenBB(int a, int b) {
    int fa = a & 7,  ra = a >>> 3;
    int fb = b & 7,  rb = b >>> 3;
    int df = Integer.compare(fb, fa),  dr = Integer.compare(rb, ra);
    if (df != 0 && dr != 0 && Math.abs(df) != Math.abs(dr)) return 0L;
    long m = 0L;
    for (int f = fa + df, r = ra + dr; f != fb || r != rb; f += df, r += dr)
      m |= 1L << (r * 8 + f);
    return m;
  }

  /** all enemy pieces that attack ‘sq’ (used to locate checkers) */
  private static long attackersToSquare(long[] bb, long occ, int sq, boolean usIsWhite) {
    boolean enemyWhite = !usIsWhite;
    long atk = 0L, sqBit = 1L << sq;

    /* pawns */
    atk |= enemyWhite
            ? bb[WP] & (((sqBit & ~FILE_A) >>> 7) | ((sqBit & ~FILE_H) >>> 9))
            : bb[BP] & (((sqBit & ~FILE_H) << 7) | ((sqBit & ~FILE_A) << 9));

    /* knights */
    atk |= KNIGHT_ATK[sq] & (enemyWhite ? bb[WN] : bb[BN]);

    /* bishops/queens */
    atk |= bishopAtt(occ, sq) & (enemyWhite ? (bb[WB]|bb[WQ])
            : (bb[BB]|bb[BQ]));
    /* rooks/queens */
    atk |= rookAtt(occ, sq)   & (enemyWhite ? (bb[WR]|bb[WQ])
            : (bb[BR]|bb[BQ]));

    /* king */
    atk |= KING_ATK[sq] & (enemyWhite ? bb[WK] : bb[BK]);

    return atk;
  }

  /* pawn captures to any square in ‘target’ (no EP) */
  private static int addPawnCapturesTarget(long[] bb, boolean white, long occ,
                                           long enemy, int[] mv, int n,
                                           int usP, long target) {

    long pawns = bb[usP];
    long capL  = white ? ((pawns & ~FILE_A) << 7)
            : ((pawns & ~FILE_H) >>> 7);
    long capR  = white ? ((pawns & ~FILE_H) << 9)
            : ((pawns & ~FILE_A) >>> 9);
    long caps  = (capL | capR) & enemy & target;
    int  dL = white ? -7 : 7, dR = white ? -9 : 9;

    while (caps != 0) {
      int to = Long.numberOfTrailingZeros(caps);
      caps &= caps - 1;
      int from = (to == (to - dL)) ? to + dR : ((to & 7) == ((to - dL) & 7) ? to + dL : to + dR);
      mv[n++] = mv(from, to, 0, usP);
    }
    /* promo-captures are already included above because target ⊆ 8th rank when needed */
    return n;
  }

  /* pawn single pushes that land on ‘target’ (incl. promotions, no doubles) */
  private static int addPawnPushBlocks(long pawns, boolean white, long occ,
                                       int[] mv, int n, int usP, long target) {
    int dir = white ? 8 : -8;
    long one = white ? ((pawns << 8) & ~occ)
            : ((pawns >>> 8) & ~occ);
    one &= target;

    long PROMO = white ? RANK_8 : RANK_1;
    long promo = one & PROMO, quiet = one & ~PROMO;

    while (promo != 0) {
      int to = Long.numberOfTrailingZeros(promo);
      promo &= promo - 1;
      n = emitPromotions(mv, n, to - dir, to, usP);
    }
    while (quiet != 0) {
      int to = Long.numberOfTrailingZeros(quiet);
      quiet &= quiet - 1;
      mv[n++] = mv(to - dir, to, 0, usP);
    }
    return n;
  }

  /* knights that jump onto ‘target’ */
  private static int addKnightEvasions(long knights, long target,
                                       int[] mv, int n, int usN) {
    while (knights != 0) {
      int from = Long.numberOfTrailingZeros(knights);
      knights &= knights - 1;
      long tgt = KNIGHT_ATK[from] & target;
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        mv[n++] = mv(from, to, 0, usN);
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

  private static int emitQueenPromotion(int[] mv, int n, int from, int to, int mover) {
    int base = mv(from, to, 1, mover);          // flag 1 = promotion
    mv[n++]  = base | (3 << 12);                // Q only
    return n;
  }

  private static int emitUnderPromotions(int[] mv, int n, int from, int to, int mover) {
    int base = mv(from, to, 1, mover);          // R / B / N only
    mv[n++]  = base | (2 << 12);                // R
    mv[n++]  = base | (1 << 12);                // B
    mv[n++]  = base;                            // N
    return n;
  }


  /* ── LOOKUP helpers (runtime) ─────────────────────────────────── */
  public static long rookAtt(long occ, int sq) {
    if (USE_PEXT) return rookAttPext(sq, occ);
    return rookAttMagic(occ, sq);
  }

  public static long bishopAtt(long occ, int sq) {
    if (USE_PEXT) return bishopAttPext(sq, occ);
    return bishopAttMagic(occ, sq);
  }

  public static long queenAtt(long occ, int sq) {
    if (USE_PEXT) return queenAttPext(sq, occ);
    return queenAttMagic(occ, sq);
  }

  private static long rookAttMagic(long occ, int sq) {
    int idx = (int) (((occ | R_MASK[sq]) * R_HASH[sq]) >>> 52);
    return LOOKUP_TABLE[R_BASE[sq] + idx];
  }

  private static long bishopAttMagic(long occ, int sq) {
    int idx = (int) (((occ | B_MASK[sq]) * B_HASH[sq]) >>> 55);
    return LOOKUP_TABLE[B_BASE[sq] + idx];
  }

  private static long queenAttMagic(long occ, int sq) {
    return rookAttMagic(occ, sq) | bishopAttMagic(occ, sq);
  }

  public static long rookAttPext(int sq, long occ) {
    int base = PreCompMoveGenTables.ROOKOFFSET_PEXT[sq];
    long mask = PreCompMoveGenTables.ROOKMASK_PEXT[sq];
    int idx = (int) Long.compress(occ, mask);
    return PreCompMoveGenTables.SLIDER_PEXT[base + idx];
  }

  public static long bishopAttPext(int sq, long occ) {
    int base = PreCompMoveGenTables.BISHOPOFFSET_PEXT[sq];
    long mask = PreCompMoveGenTables.BISHOPMASK_PEXT[sq];
    int idx = (int) Long.compress(occ, mask);
    return PreCompMoveGenTables.SLIDER_PEXT[base + idx];
  }

  public static long queenAttPext(int sq, long occ) {
    /* unchanged offsets, just call the fixed helpers */
    return rookAttPext(sq, occ) | bishopAttPext(sq, occ);
  }

  private static int mv(int from, int to, int flags, int mover) {
    return (from << 6) | to | (flags << 14) | (mover << MOVER_SHIFT);
  }
}
