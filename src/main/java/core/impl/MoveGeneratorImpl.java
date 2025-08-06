package core.impl;

import static core.impl.PreCompMoveGenTables.*;
import static core.contracts.PositionFactory.*;

import core.contracts.*;

public final class MoveGeneratorImpl implements MoveGenerator {
  /* ── bit-board constants ─────────────────────────────────────── */
  static final long FILE_A = 0x0101_0101_0101_0101L;
  static final long FILE_H = FILE_A << 7;

  private static final long RANK_1 = 0xFFL;
  private static final long RANK_2 = RANK_1 << 8;
  private static final long RANK_3 = RANK_1 << 16;
  private static final long RANK_6 = RANK_1 << 40;
  private static final long RANK_7 = RANK_1 << 48;
  private static final long RANK_8 = RANK_1 << 56;

  private static final int MOVER_SHIFT = 16;

  @Override
  public int generateCaptures(long[] bb, int[] mv, int n) {
    return whiteToMove(bb[META])
            ? genCapturesWhite(bb, mv, n)
            : genCapturesBlack(bb, mv, n);
  }

  /* -------- WHITE to move -------------------------------------- */
  private static int genCapturesWhite(long[] bb, int[] mv, int n) {

    /* inlined piece IDs – now compile-time constants */
    final int usP = WP, usN = WN, usB = WB, usR = WR, usQ = WQ, usK = WK;
    /* aggregate bitboards (all constant folds, no “?:” branches) */
    final long own   = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
    final long enemy = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    final long occ   = own | enemy;
    final long captMask = enemy;
    final long allCapt  = captMask;          // alias (keeps old code unchanged)

    n  = addPawnCaptures(bb, /*white=*/true,  occ, enemy, mv, n, usP);
    n  = addPawnPushes   (bb[usP], true, occ, mv, n, usP,
            /*Q?*/true, /*RBN?*/false,
            /*quiet?*/false, /*double?*/false);
    n  = addKnightMoves(bb[usN], allCapt, mv, n, usN);

    long bishops = bb[usB];
    while (bishops != 0) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usB);
    }

    long rooks = bb[usR];
    while (rooks != 0) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usR);
    }

    long queens = bb[usQ];
    while (queens != 0) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usQ);
    }

    n  = addKingMovesAndCastle(bb, /*white=*/true,  occ,
            /*enemySeen=*/0L, captMask, 0L,
            mv, n, usK);
    return n;
  }

  /* -------- BLACK to move -------------------------------------- */
  private static int genCapturesBlack(long[] bb, int[] mv, int n) {

    final int usP = BP, usN = BN, usB = BB, usR = BR, usQ = BQ, usK = BK;
    final long own   = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    final long enemy = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ];
    final long occ   = own | enemy;
    final long captMask = enemy;
    final long allCapt  = captMask;

    n  = addPawnCaptures(bb, /*white=*/false, occ, enemy, mv, n, usP);
    n  = addPawnPushes   (bb[usP], false, occ, mv, n, usP,
            /*Q?*/true, /*RBN?*/false,
            /*quiet?*/false, /*double?*/false);
    n  = addKnightMoves(bb[usN], allCapt, mv, n, usN);

    long bishops = bb[usB];
    while (bishops != 0) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usB);
    }

    long rooks = bb[usR];
    while (rooks != 0) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usR);
    }

    long queens = bb[usQ];
    while (queens != 0) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt, usQ);
    }

    n  = addKingMovesAndCastle(bb, /*white=*/false, occ,
            /*enemySeen=*/0L, captMask, 0L,
            mv, n, usK);
    return n;
  }

  @Override
  public int generateQuiets(long[] bb, int[] mv, int n) {
    return whiteToMove(bb[META])
            ? genQuietsWhite(bb, mv, n)
            : genQuietsBlack(bb, mv, n);
  }

  private static int genQuietsWhite(long[] bb, int[] mv, int n) {

    final int usP = WP, usN = WN, usB = WB, usR = WR, usQ = WQ, usK = WK;
    final long own   = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
    final long enemy = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    final long occ   = own | enemy;

    long quietMask = ~occ;
    long allQuiet  = quietMask;

    n = addPawnPushes(bb[usP], /*white=*/true,  occ, mv, n, usP,
            /*Q?*/false, /*RBN?*/true,
            /*quiet?*/true,  /*double?*/true);
    n = addKnightMoves(bb[usN], allQuiet, mv, n, usN);

    for (long bishops = bb[usB]; bishops != 0; ) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usB);
    }
    for (long rooks = bb[usR]; rooks != 0; ) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usR);
    }
    for (long queens = bb[usQ]; queens != 0; ) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usQ);
    }

    n = addKingMovesAndCastle(bb, /*white=*/true,  occ,
            /*enemySeen*/0L, /*captMask*/0L,
            quietMask, mv, n, usK);
    return n;
  }

  private static int genQuietsBlack(long[] bb, int[] mv, int n) {

    final int usP = BP, usN = BN, usB = BB, usR = BR, usQ = BQ, usK = BK;
    final long own   = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    final long enemy = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
    final long occ   = own | enemy;

    long quietMask = ~occ;
    long allQuiet  = quietMask;

    n = addPawnPushes(bb[usP], /*white=*/false, occ, mv, n, usP,
            /*Q?*/false, /*RBN?*/true,
            /*quiet?*/true,  /*double?*/true);
    n = addKnightMoves(bb[usN], allQuiet, mv, n, usN);

    for (long bishops = bb[usB]; bishops != 0; ) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usB);
    }
    for (long rooks = bb[usR]; rooks != 0; ) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usR);
    }
    for (long queens = bb[usQ]; queens != 0; ) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt, usQ);
    }

    n = addKingMovesAndCastle(bb, /*white=*/false, occ,
            /*enemySeen*/0L, /*captMask*/0L,
            quietMask, mv, n, usK);
    return n;
  }

  @Override
  public int generateEvasions(long[] bb, int[] mv, int n) {
    return whiteToMove(bb[META])
            ? genEvasionsWhite(bb, mv, n)
            : genEvasionsBlack(bb, mv, n);
  }

  private static int genEvasionsWhite(long[] bb, int[] mv, int n) {

    final int usP = WP, usN = WN, usB = WB, usR = WR, usQ = WQ, usK = WK;
    final long own   = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
    final long enemy = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    final long occ   = own | enemy;

    int  kSq      = Long.numberOfTrailingZeros(bb[usK]);
    long checkers = attackersToSquare(bb, occ, kSq, /*usIsWhite=*/true);
    long dblCheck = checkers & (checkers - 1);

    if (dblCheck != 0) {                           // king must move
      long kingMoves = KING_ATK[kSq] & ~own;
      while (kingMoves != 0) {
        int to = Long.numberOfTrailingZeros(kingMoves);
        kingMoves &= kingMoves - 1;
        mv[n++] = mv(kSq, to, 0, usK);
      }
      return n;
    }

    int  checkerSq = Long.numberOfTrailingZeros(checkers);
    long target    = checkers | between(kSq, checkerSq);

    long kingTgt = KING_ATK[kSq] & ~own;
    while (kingTgt != 0) {
      int to = Long.numberOfTrailingZeros(kingTgt);
      kingTgt &= kingTgt - 1;
      mv[n++] = mv(kSq, to, 0, usK);
    }

    n = addPawnCapturesTarget(bb, /*white=*/true,  occ, enemy, mv, n, usP, target);
    n = addPawnPushBlocks  (bb[usP], /*white=*/true,  occ, mv, n, usP, target);

    n = addKnightEvasions(bb[usN], target, mv, n, usN);

    for (long sliders = bb[usB] | bb[usR] | bb[usQ]; sliders != 0; ) {
      int from = Long.numberOfTrailingZeros(sliders);
      sliders &= sliders - 1;

      long ray, piece;
      if ((bb[usB] & (1L << from)) != 0) {         // bishop
        ray   = bishopAtt(occ, from);
        piece = usB;
      } else if ((bb[usR] & (1L << from)) != 0) {  // rook
        ray   = rookAtt(occ, from);
        piece = usR;
      } else {                                     // queen
        ray   = queenAtt(occ, from);
        piece = usQ;
      }
      long tgt = ray & target;
      n = emitSliderMoves(mv, n, from, tgt, (int) piece);
    }

    n = addEnPassantEvasions(bb, /*white=*/true,  mv, n, usP, checkerSq, checkers);
    return n;
  }

  private static int genEvasionsBlack(long[] bb, int[] mv, int n) {

    final int usP = BP, usN = BN, usB = BB, usR = BR, usQ = BQ, usK = BK;
    final long own   = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    final long enemy = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
    final long occ   = own | enemy;

    int  kSq      = Long.numberOfTrailingZeros(bb[usK]);
    long checkers = attackersToSquare(bb, occ, kSq, /*usIsWhite=*/false);
    long dblCheck = checkers & (checkers - 1);

    if (dblCheck != 0) {
      long kingMoves = KING_ATK[kSq] & ~own;
      while (kingMoves != 0) {
        int to = Long.numberOfTrailingZeros(kingMoves);
        kingMoves &= kingMoves - 1;
        mv[n++] = mv(kSq, to, 0, usK);
      }
      return n;
    }

    int  checkerSq = Long.numberOfTrailingZeros(checkers);
    long target    = checkers | between(kSq, checkerSq);

    long kingTgt = KING_ATK[kSq] & ~own;
    while (kingTgt != 0) {
      int to = Long.numberOfTrailingZeros(kingTgt);
      kingTgt &= kingTgt - 1;
      mv[n++] = mv(kSq, to, 0, usK);
    }

    n = addPawnCapturesTarget(bb, /*white=*/false, occ, enemy, mv, n, usP, target);
    n = addPawnPushBlocks  (bb[usP], /*white=*/false, occ, mv, n, usP, target);

    n = addKnightEvasions(bb[usN], target, mv, n, usN);

    for (long sliders = bb[usB] | bb[usR] | bb[usQ]; sliders != 0; ) {
      int from = Long.numberOfTrailingZeros(sliders);
      sliders &= sliders - 1;

      long ray, piece;
      if ((bb[usB] & (1L << from)) != 0) {
        ray   = bishopAtt(occ, from);
        piece = usB;
      } else if ((bb[usR] & (1L << from)) != 0) {
        ray   = rookAtt(occ, from);
        piece = usR;
      } else {
        ray   = queenAtt(occ, from);
        piece = usQ;
      }
      long tgt = ray & target;
      n = emitSliderMoves(mv, n, from, tgt, (int) piece);
    }

    n = addEnPassantEvasions(bb, /*white=*/false, mv, n, usP, checkerSq, checkers);
    return n;
  }

  @Override
  public boolean castleLegal(long[] bb, int from, int to) {

    // 1) side & pre-computed constants ------------------------------
    boolean white = from == 4; // 4→e1 / 60→e8
    int rookFrom  = white
            ? (to == 6 ? 7  : 0)          // h1  : a1
            : (to == 62 ? 63 : 56);       // h8  : a8
    long pathMask = to == 6 || to == 62
            ? /* king-side  */ (1L << (from+1)) | (1L << (from+2))
            : /* queen-side */ (1L << (from-1)) | (1L << (from-2)) | (1L << (from-3));

    // 2) required castling right still set? -------------------------
    int rights = (int)((bb[META] & CR_MASK) >>> CR_SHIFT);
    int need   = white
            ? (to == 6 ? 1 : 2)             // K / Q
            : (to == 62 ? 4 : 8);           // k / q
    if ( (rights & need) == 0 )
      return false; // right already lost

    // 3) path empty? -------------------------------------------------
    long occ =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
            | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];

    if ( (occ & pathMask) != 0 || (occ & (1L<<rookFrom)) == 0 )
      return false; // something blocks

    // 4) none of origin / transit / target squares may be attacked --
    int  transit = (to == 6 || to == 62) ? from+1 : from-1;

    if (squareAttacked(bb, !white, from) ||
            squareAttacked(bb, !white, transit) ||
            squareAttacked(bb, !white, to))
      return false;

    return true;
  }

  /** EP capture that removes the checking pawn. */
  private static int addEnPassantEvasions(long[] bb, boolean white,
                                          int[] mv, int n, int usP,
                                          int checkerSq, long checkers) {

    int epSq = (int)((bb[META] & EP_MASK) >>> EP_SHIFT);
    if (epSq == EP_NONE) return n; // no EP square
    if ( (checkers & (white ? bb[BP] : bb[WP])) == 0 )   // checker isn’t a pawn
      return n;

    int victim = white ? epSq - 8 : epSq + 8; // pawn behind EP
    if (victim != checkerSq) return n; // not the checker

    long epBit = 1L << epSq;
    long pawns =
            white
                    ? (((epBit & ~FILE_A) >>> 9) | ((epBit & ~FILE_H) >>> 7)) & bb[WP]
                    : (((epBit & ~FILE_H) <<  9) | ((epBit & ~FILE_A) <<  7)) & bb[BP];

    while (pawns != 0) {
      int from = Long.numberOfTrailingZeros(pawns);
      pawns &= pawns - 1;
      mv[n++] = mv(from, epSq, 2, usP);                // flag 2 = EP
    }
    return n;
  }

  /* thin public wrapper around the private attackersToSquare() */
  private boolean squareAttacked(long[] bb, boolean byWhite, int sq) {
    long occ =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
            | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];

    return attackersToSquare(bb, occ, sq, /*usIsWhite=*/!byWhite) != 0;
  }

  private static long between(int from, int to) {
    return BETWEEN[from * 64 + to];
  }

  private static int emitSliderMoves(int[] mv, int n, int from, long tgt, int piece) {
    while (tgt != 0) {
      int to = Long.numberOfTrailingZeros(tgt);
      tgt &= tgt - 1;
      mv[n++] = mv(from, to, 0, piece);
    }
    return n;
  }

  /* pawn pushes, promotions & double-pushes — never emits captures */
  private static int addPawnPushes(
          long pawns,
          boolean white,
          long occ,
          int[] mv,
          int n,
          int usP,
          boolean includeQueenPromo, // emit Q?
          boolean includeUnderPromo, // emit R/B/N?
          boolean includeQuietPush, // 1-square non-promo push
          boolean includeDoublePush) // 2-square push
  {
    final int dir = white ? 8 : -8;
    final long one = white ? ((pawns << 8) & ~occ) : ((pawns >>> 8) & ~occ);
    final long PROMO = white ? RANK_8 : RANK_1;

    /* ── promotions on push ─────────────────────────────────────── */
    long promo = one & PROMO;
    while (promo != 0L) {
      int to = Long.numberOfTrailingZeros(promo);
      promo &= promo - 1;
      if (includeQueenPromo && includeUnderPromo)
        n = emitPromotions(mv, n, to - dir, to, usP); // Q R B N
      else if (includeQueenPromo) n = emitQueenPromotion(mv, n, to - dir, to, usP); // Q
      else if (includeUnderPromo) n = emitUnderPromotions(mv, n, to - dir, to, usP); // R B N
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
      long two = white ? (((one & rank3) << 8) & ~occ) : (((one & rank3) >>> 8) & ~occ);
      while (two != 0L) {
        int to = Long.numberOfTrailingZeros(two);
        two &= two - 1;
        mv[n++] = mv(to - 2 * dir, to, 0, usP);
      }
    }
    return n;
  }

  private static int addPawnCaptures(
          long[] bb,
          boolean white,
          long occ, // current occupancy (unused but kept for signature parity)
          long enemy, // every enemy piece
          int[] mv,
          int n,
          int usP) {

    /* ------------------------------------------------------------------
     * Filter out any square that is occupied by one of **our** pieces.
     * This guarantees the generator never outputs a capture onto
     * a friendly man – even if ‘enemy’ is corrupted (e.g. by a bug
     * elsewhere) and happens to include friendly bits.
     * ------------------------------------------------------------------ */
    long own =
            white
                    ? (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK])
                    : (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK]);
    long legalTargets = enemy & ~own; // enemy squares *only*

    long pawns = bb[usP];
    long PROMO = white ? RANK_8 : RANK_1;

    long capL = white ? ((pawns & ~FILE_A) << 7) : ((pawns & ~FILE_H) >>> 7);
    long capR = white ? ((pawns & ~FILE_H) << 9) : ((pawns & ~FILE_A) >>> 9);

    long promoL = capL & legalTargets & PROMO;
    long promoR = capR & legalTargets & PROMO;
    capL &= legalTargets & ~PROMO;
    capR &= legalTargets & ~PROMO;

    final int dL = white ? -7 : 7;
    final int dR = white ? -9 : 9;

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

    /* ---- en-passant (unchanged) ------------------------------------ */
    long epSqRaw = (bb[META] & EP_MASK) >>> EP_SHIFT;
    if (epSqRaw != 63) {
      long epBit = 1L << epSqRaw; // empty EP square
      long behind =
              white
                      ? epBit >>> 8 // pawn to be taken
                      : epBit << 8;
      if ((enemy & behind) != 0) { // real enemy pawn?

        long epL = white ? ((pawns & ~FILE_A) << 7) & epBit : ((pawns & ~FILE_H) >>> 7) & epBit;
        long epR = white ? ((pawns & ~FILE_H) << 9) & epBit : ((pawns & ~FILE_A) >>> 9) & epBit;

        while (epL != 0) {
          int to = Long.numberOfTrailingZeros(epL);
          epL &= epL - 1;
          mv[n++] = mv(to + (white ? -7 : 7), to, 2, usP);
        }
        while (epR != 0) {
          int to = Long.numberOfTrailingZeros(epR);
          epR &= epR - 1;
          mv[n++] = mv(to + (white ? -9 : 9), to, 2, usP);
        }
      }
    }
    return n;
  }

  private static int addKnightMoves(long knights, long targetMask, int[] mv, int n, int usN) {
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

  private static int addKingMovesAndCastle(
          long[] bb,
          boolean white,
          long occ,
          long enemySeen,
          long captMask,
          long quietMask,
          int[] mv,
          int n,
          int usK) {

    int kSq = Long.numberOfTrailingZeros(bb[usK]);
    /* own pieces only – enemy squares stay available for capture */
    long own = occ & ~captMask;
    long moves = KING_ATK[kSq] & ~own; // legal destinations

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
        if ((rights & 1) != 0
                && ((bb[WR] & (1L << 7)) != 0)
                && ((occ & 0x60L) == 0)) mv[n++] = mv(4, 6, 3, WK);
        /* 0-0-0 */
        if ((rights & 2) != 0
                && ((bb[WR] & (1L << 0)) != 0)
                && ((occ & 0x0EL) == 0)) mv[n++] = mv(4, 2, 3, WK);
      } else {
        /* 0-0 */
        if ((rights & 4) != 0
                && ((bb[BR] & (1L << 63)) != 0)
                && ((occ & 0x6000_0000_0000_0000L) == 0)) mv[n++] = mv(60, 62, 3, BK);
        /* 0-0-0 */
        if ((rights & 8) != 0
                && ((bb[BR] & (1L << 56)) != 0)
                && ((occ & 0x0E00_0000_0000_0000L) == 0)) mv[n++] = mv(60, 58, 3, BK);
      }
    }
    return n;
  }

  /** all enemy pieces that attack ‘sq’ (used to locate checkers) */
  private static long attackersToSquare(long[] bb, long occ, int sq, boolean usIsWhite) {
    boolean enemyWhite = !usIsWhite;
    long atk = 0L, sqBit = 1L << sq;

    /* pawns */
    atk |= enemyWhite
            ? bb[WP] & (((sqBit & ~FILE_H) >>> 7) | ((sqBit & ~FILE_A) >>> 9))
            : bb[BP] & (((sqBit & ~FILE_H) << 9) | ((sqBit & ~FILE_A) << 7));
    /* knights */
    atk |= KNIGHT_ATK[sq] & (enemyWhite ? bb[WN] : bb[BN]);
    /* bishops/queens */
    atk |= bishopAtt(occ, sq) & (enemyWhite ? (bb[WB] | bb[WQ]) : (bb[BB] | bb[BQ]));
    /* rooks/queens */
    atk |= rookAtt(occ, sq) & (enemyWhite ? (bb[WR] | bb[WQ]) : (bb[BR] | bb[BQ]));
    /* king */
    atk |= KING_ATK[sq] & (enemyWhite ? bb[WK] : bb[BK]);

    return atk;
  }

  /* pawn captures to any square in ‘target’ (no EP) */
  private static int addPawnCapturesTarget(
          long[] bb, boolean white, long occ, long enemy, int[] mv, int n, int usP, long target) {

    long pawns = bb[usP];
    final long PROMO = white ? RANK_8 : RANK_1;

    /* ---------- left-diagonal captures (from the pawn’s point of view) */
    long capL =
            white
                    ? ((pawns & ~FILE_A) << 7) // ⭡⭠  for White
                    : ((pawns & ~FILE_H) >>> 7); // ⭣⭢  for Black
    int dL = white ? 7 : -7; // dest − from

    for (long m = capL & enemy & target; m != 0; m &= m - 1) {
      int to = Long.numberOfTrailingZeros(m);
      int from = to - dL; // pawn is dL behind ‘to’
      if ((PROMO & (1L << to)) != 0) n = emitPromotions(mv, n, from, to, usP); // Q R B N
      else mv[n++] = mv(from, to, 0, usP);
    }

    /* ---------- right-diagonal captures */
    long capR =
            white
                    ? ((pawns & ~FILE_H) << 9) // ⭡⭢  for White
                    : ((pawns & ~FILE_A) >>> 9); // ⭣⭠  for Black
    int dR = white ? 9 : -9;
    for (long m = capR & enemy & target; m != 0; m &= m - 1) {
      int to = Long.numberOfTrailingZeros(m);
      int from = to - dR;
      if ((PROMO & (1L << to)) != 0) n = emitPromotions(mv, n, from, to, usP);
      else mv[n++] = mv(from, to, 0, usP);
    }
    return n;
  }

  /** Bug Fix: Correctly filter double pushes by the target mask for check evasions. */
  private static int addPawnPushBlocks(
          long pawns, boolean white, long occ, int[] mv, int n, int usP, long target) {
    final int dir = white ? 8 : -8;
    final long one = white ? ((pawns << 8) & ~occ) : ((pawns >>> 8) & ~occ);
    long singleBlocks = one & target;

    final long PROMO = white ? RANK_8 : RANK_1;
    long promo = singleBlocks & PROMO;
    long quiet = singleBlocks & ~PROMO;

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

    final long startRankForDoublePush = white ? RANK_3 : RANK_6;
    long doublePushDestinations =
            white
                    ? (((one & startRankForDoublePush) << 8) & ~occ)
                    : (((one & startRankForDoublePush) >>> 8) & ~occ);
    doublePushDestinations &= target; // BUG FIX: Filter double pushes by the target mask.

    while (doublePushDestinations != 0) {
      int to = Long.numberOfTrailingZeros(doublePushDestinations);
      doublePushDestinations &= doublePushDestinations - 1;
      mv[n++] = mv(to - 2 * dir, to, 0, usP);
    }

    return n;
  }


  /* knights that jump onto ‘target’ */
  private static int addKnightEvasions(long knights, long target, int[] mv, int n, int usN) {
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
  public boolean kingAttacked(long[] bb, boolean whiteSide) {
    // delegate to the colour-specific fast path
    return whiteSide ? kingAttackedWhite(bb)    // we just moved → our king is WHITE
            : kingAttackedBlack(bb);   // we just moved → our king is BLACK
  }

  /* our king = WHITE, attackers = BLACK */
  private static boolean kingAttackedWhite(long[] bb) {
    int kSq = Long.numberOfTrailingZeros(bb[WK]);
    /* 1) pawn & knight checks – cheap fast exits */
    if ( (bb[BP] & PAWN_ATK_W[kSq]) != 0 ) return true;
    if ( (bb[BN] & KNIGHT_ATK[kSq]) != 0 ) return true;
    /* 2) build occupancy once (12 ORs instead of 6+6+1) */
    long occ = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
            | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];

    /* 3) sliders */
    if ( (bishopAtt(occ, kSq) & (bb[BB] | bb[BQ])) != 0 ) return true;
    if ( (rookAtt  (occ, kSq) & (bb[BR] | bb[BQ])) != 0 ) return true;
    /* 4) opposing king */
    return (bb[BK] & KING_ATK[kSq]) != 0;
  }

  /* our king = BLACK, attackers = WHITE */
  private static boolean kingAttackedBlack(long[] bb) {
    int kSq = Long.numberOfTrailingZeros(bb[BK]);
    if ( (bb[WP] & PAWN_ATK_B[kSq]) != 0 ) return true;
    if ( (bb[WN] & KNIGHT_ATK[kSq]) != 0 ) return true;
    long occ = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
            | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
    if ( (bishopAtt(occ, kSq) & (bb[WB] | bb[WQ])) != 0 ) return true;
    if ( (rookAtt  (occ, kSq) & (bb[WR] | bb[WQ])) != 0 ) return true;
    return (bb[WK] & KING_ATK[kSq]) != 0;
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

  private static int emitQueenPromotion(int[] mv, int n, int from, int to, int mover) {
    int base = mv(from, to, 1, mover); // flag 1 = promotion
    mv[n++] = base | (3 << 12); // Q only
    return n;
  }

  private static int emitUnderPromotions(int[] mv, int n, int from, int to, int mover) {
    int base = mv(from, to, 1, mover); // R / B / N only
    mv[n++] = base | (2 << 12); // R
    mv[n++] = base | (1 << 12); // B
    mv[n++] = base; // N
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
    int idx = (int) (((occ & R_MASK[sq]) * R_HASH[sq]) >>> 52);
    return LOOKUP_TABLE[R_BASE[sq] + idx];
  }

  private static long bishopAttMagic(long occ, int sq) {
    int idx = (int) (((occ & B_MASK[sq]) * B_HASH[sq]) >>> 55);
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
    return rookAttPext(sq, occ) | bishopAttPext(sq, occ);
  }

  private static int mv(int from, int to, int flags, int mover) {
    return (from << 6) | to | (flags << 14) | (mover << MOVER_SHIFT);
  }
}