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

    /* side setup ----------------------------------------------------------- */
    final boolean white = whiteToMove(bb[META]);
    final int usP = white ? WP : BP;
    final int usKn = white ? WN : BN;
    final int usB = white ? WB : BB;
    final int usR = white ? WR : BR;
    final int usQ = white ? WQ : BQ;
    final int usK = white ? WK : BK;

    /* aggregate sets ------------------------------------------------------- */
    long whitePieces = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
    long blackPieces = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    long own = white ? whitePieces : blackPieces;
    long enemy = white ? blackPieces : whitePieces;
    long occ = own | enemy;

    boolean wantCapt = mode != GenMode.QUIETS;
    boolean wantQuiet = mode != GenMode.CAPTURES;
    long captMask = wantCapt ? enemy : 0;
    long quietMask = wantQuiet ? ~occ : 0;

    int n = 0; // output cursor

    /* =====================================================================
     *  Pawns
     * =================================================================== */
    long pawns = bb[usP];
    int pushDir = white ? 8 : -8;
    long PROMO_RANK = white ? RANK_8 : RANK_1;

    /* -- single pushes (incl. promotions) -------------------------------- */
    long one = white ? (pawns << 8) & ~occ : (pawns >>> 8) & ~occ;

    if (wantQuiet) {
      long promoPush = one & PROMO_RANK;
      while (promoPush != 0) {
        int to = Long.numberOfTrailingZeros(promoPush);
        promoPush &= promoPush - 1;
        n = emitPromotions(moves, n, to - pushDir, to, usP);
      }

      long quiet = one & ~PROMO_RANK;
      while (quiet != 0) {
        int to = Long.numberOfTrailingZeros(quiet);
        quiet &= quiet - 1;
        moves[n++] = mv(to - pushDir, to, 0, usP);
      }

      long two = white ? (((one & RANK_3) << 8) & ~occ) : (((one & RANK_6) >>> 8) & ~occ);
      while (two != 0) {
        int to = Long.numberOfTrailingZeros(two);
        two &= two - 1;
        moves[n++] = mv(to - 2 * pushDir, to, 0, usP);
      }
    }

    /* -- captures (incl. promo captures) --------------------------------- */
    final int deltaL = white ? -7 : 7;
    final int deltaR = white ? -9 : 9;

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
      capL = (capL & enemy) & ~PROMO_RANK;
      capR = (capR & enemy) & ~PROMO_RANK;

      while (capL != 0) {
        int to = Long.numberOfTrailingZeros(capL);
        capL &= capL - 1;
        moves[n++] = mv(to + deltaL, to, 0, usP);
      }
      while (capR != 0) {
        int to = Long.numberOfTrailingZeros(capR);
        capR &= capR - 1;
        moves[n++] = mv(to + deltaR, to, 0, usP);
      }

      while (promoL != 0) {
        int to = Long.numberOfTrailingZeros(promoL);
        promoL &= promoL - 1;
        n = emitPromotions(moves, n, to + deltaL, to, usP);
      }
      while (promoR != 0) {
        int to = Long.numberOfTrailingZeros(promoR);
        promoR &= promoR - 1;
        n = emitPromotions(moves, n, to + deltaR, to, usP);
      }
    }

    /* -- en-passant -------------------------------------------------- */
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

      while (epL != 0) {
        int to = Long.numberOfTrailingZeros(epL);
        epL &= epL - 1;
        int mv = mv(to + deltaL, to, 2, usP); // flag 2 = EP
        int capSq = white ? to - 8 : to + 8;

        if (epKingSafe(bb, to + deltaL, to, capSq, white, occ)) moves[n++] = mv;
      }
      while (epR != 0) {
        int to = Long.numberOfTrailingZeros(epR);
        epR &= epR - 1;
        int mv = mv(to + deltaR, to, 2, usP);
        int capSq = white ? to - 8 : to + 8;

        if (epKingSafe(bb, to + deltaR, to, capSq, white, occ)) moves[n++] = mv;
      }
    }

    /* =====================================================================
     *  Knights
     * =================================================================== */
    long knights = bb[usKn];
    while (knights != 0) {
      int from = Long.numberOfTrailingZeros(knights);
      knights &= knights - 1;
      long tgt = KNIGHT_ATK[from] & (captMask | quietMask);
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        moves[n++] = mv(from, to, 0, usKn);
      }
    }

    /* =====================================================================
     *  Bishops, Rooks, Queens
     * =================================================================== */
    long sliders = bb[usB] | bb[usR] | bb[usQ];

    while (sliders != 0) {
      int from = Long.numberOfTrailingZeros(sliders);
      sliders &= sliders - 1;
      long bitFrom = 1L << from;

      int pieceMover = ((bitFrom & bb[usB]) != 0) ? usB : ((bitFrom & bb[usR]) != 0) ? usR : usQ;

      long tgt =
          (pieceMover == usB)
              ? bishopAtt(occ, from)
              : (pieceMover == usR) ? rookAtt(occ, from) : queenAtt(occ, from);

      tgt &= (captMask | quietMask);
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        moves[n++] = mv(from, to, 0, pieceMover);
      }
    }

    /* =====================================================================
     *  King  (pseudo-legal: can step into check)
     * =================================================================== */
    int kingSq = Long.numberOfTrailingZeros(bb[usK]);
    long kingMoves = KING_ATK[kingSq] & ~own;

    if (wantQuiet) {
      long qs = kingMoves & quietMask;
      while (qs != 0) {
        int to = Long.numberOfTrailingZeros(qs);
        qs &= qs - 1;
        moves[n++] = mv(kingSq, to, 0, usK);
      }
    }
    if (wantCapt) {
      long cs = kingMoves & captMask;
      while (cs != 0) {
        int to = Long.numberOfTrailingZeros(cs);
        cs &= cs - 1;
        moves[n++] = mv(kingSq, to, 0, usK);
      }
    }

    /* ── castling ---------------------------------------------------- */
    if (wantQuiet) {
      int rights = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
      long kBit = 1L << kingSq; // king is on e1 / e8 right now
      long occNoK = occ ^ kBit; // occupancy AFTER king lifts
      boolean enemySide = !white;

      if (white) {
        /* 0-0 : e1-f1-g1 must be safe, f1 & g1 empty, rook on h1 */
        if ((rights & 1) != 0
            && (bb[WR] & (1L << 7)) != 0
            && (occ & 0x60L) == 0
            && !squareAttacked(enemySide, bb, occ, 4) // e1 (king still there)
            && !squareAttacked(enemySide, bb, occNoK, 5) // f1
            && !squareAttacked(enemySide, bb, occNoK, 6)) // g1
        moves[n++] = mv(4, 6, 3, WK);

        /* 0-0-0 : e1-d1-c1 safe, b1 c1 d1 empty, rook on a1      */
        if ((rights & 2) != 0
            && (bb[WR] & (1L << 0)) != 0
            && (occ & 0x0EL) == 0
            && !squareAttacked(enemySide, bb, occ, 4) // e1
            && !squareAttacked(enemySide, bb, occNoK, 3) // d1
            && !squareAttacked(enemySide, bb, occNoK, 2)) // c1
        moves[n++] = mv(4, 2, 3, WK);

      } else {
        /* 0-0 : e8-f8-g8 */
        if ((rights & 4) != 0
            && (bb[BR] & (1L << 63)) != 0
            && (occ & 0x6000_0000_0000_0000L) == 0
            && !squareAttacked(enemySide, bb, occ, 60) // e8
            && !squareAttacked(enemySide, bb, occNoK, 61) // f8
            && !squareAttacked(enemySide, bb, occNoK, 62)) // g8
        moves[n++] = mv(60, 62, 3, BK);

        /* 0-0-0 : e8-d8-c8 */
        if ((rights & 8) != 0
            && (bb[BR] & (1L << 56)) != 0
            && (occ & 0x0E00_0000_0000_0000L) == 0
            && !squareAttacked(enemySide, bb, occ, 60) // e8
            && !squareAttacked(enemySide, bb, occNoK, 59) // d8
            && !squareAttacked(enemySide, bb, occNoK, 58)) // c8
        moves[n++] = mv(60, 58, 3, BK);
      }
    }

    return n;
  }

  @Override
  public boolean kingAttacked(long[] bb, boolean moverWasWhite) {

    /* king square ---------------------------------------------------- */
    int kIdx = moverWasWhite ? WK : BK;
    int kSq = Long.numberOfTrailingZeros(bb[kIdx]);
    long kBit = 1L << kSq;

    /* occupancy for slider look-ups --------------------------------- */
    long occ = 0;
    for (int i = WP; i <= BK; ++i) occ |= bb[i];

    /* pawns ---------------------------------------------------------- */
    if (moverWasWhite) { // check BLACK pawns attacking White king ↓
      long bp = bb[BP];
      // south-west  (kingBit << 9) –– wrap only from file H ⇒ mask FILE_A
      if (((kBit << 9) & ~FILE_A & bp) != 0) return true;
      // south-east  (kingBit << 7) –– wrap only from file A ⇒ mask FILE_H
      if (((kBit << 7) & ~FILE_H & bp) != 0) return true;

    } else { // check WHITE pawns attacking Black king ↑
      long wp = bb[WP];
      // north-west  (kingBit >>> 7) –– wrap only from file H ⇒ mask FILE_A
      if (((kBit >>> 7) & ~FILE_A & wp) != 0) return true;
      // north-east  (kingBit >>> 9) –– wrap only from file A ⇒ mask FILE_H
      if (((kBit >>> 9) & ~FILE_H & wp) != 0) return true;
    }

    /* knights -------------------------------------------------------- */
    long kn = moverWasWhite ? bb[BN] : bb[WN];
    if ((KNIGHT_ATK[kSq] & kn) != 0) return true;

    /* bishops / queens (diagonals) ---------------------------------- */
    long bq = moverWasWhite ? (bb[BB] | bb[BQ]) : (bb[WB] | bb[WQ]);
    if ((bishopAtt(occ, kSq) & bq) != 0) return true;

    /* rooks / queens (orthogonals) ---------------------------------- */
    long rq = moverWasWhite ? (bb[BR] | bb[BQ]) : (bb[WR] | bb[WQ]);
    if ((rookAtt(occ, kSq) & rq) != 0) return true;

    /* opposing king (cheap, rare) ----------------------------------- */
    long theirK = moverWasWhite ? bb[BK] : bb[WK];
    return (KING_ATK[kSq] & theirK) != 0;
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

  /* ── LOOKUP helpers (runtime) ─────────────────────────────────── */
  private static long rookAtt(long occ, int sq) {
    int idx = (int)(((occ | R_MASK[sq]) * R_HASH[sq]) >>> 52);
    return LOOKUP_TABLE[R_BASE[sq] + idx];
  }
  private static long bishopAtt(long occ, int sq) {
    int idx = (int)(((occ | B_MASK[sq]) * B_HASH[sq]) >>> 55);
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

  /* ─────────────────────────  strict EP legality  ───────────────────────── */
  private static boolean epKingSafe(
      long[] bb, int from, int to, int capSq, boolean white, long occ) {
    /* build occupancy after the *capture* (three bit-toggles) */
    long occAfter =
        occ
            ^ (1L << from) // our pawn leaves “from”
            ^ (1L << to) // … appears on “to”
            ^ (1L << capSq); // captured pawn disappears

    /* flip the two pawn bit-boards in the real position ------------------ */
    int usP = white ? WP : BP;
    int themP = white ? BP : WP;

    long saveUs = bb[usP];
    long saveThem = bb[themP];

    bb[usP] ^= (1L << from) | (1L << to); // move our pawn
    bb[themP] ^= (1L << capSq); // erase captured pawn

    /* ----------  OCCUPANCY **AFTER** THE FLIPS (accurate) --------------- */
    long occTrue = 0;
    for (int i = WP; i <= BK; ++i) occTrue |= bb[i];

    /* is our king in check? ---------------------------------------------- */
    int kSq = Long.numberOfTrailingZeros(bb[white ? WK : BK]);
    boolean safe = !squareAttacked(!white, bb, occTrue, kSq);

    /* restore the two pawn boards ---------------------------------------- */
    bb[usP] = saveUs;
    bb[themP] = saveThem;

    return safe;
  }

  private static boolean squareAttacked(boolean byWhite, long[] bb, long occ, int sq) {
    long b = 1L << sq;

    /* pawns */
    if (byWhite) {
      long wp = bb[WP];
      if (((b >>> 7) & ~FILE_H & wp) != 0) return true;
      if (((b >>> 9) & ~FILE_A & wp) != 0) return true;
    } else {
      long bp = bb[BP];
      if (((b << 7) & ~FILE_A & bp) != 0) return true;
      if (((b << 9) & ~FILE_H & bp) != 0) return true;
    }

    /* knights */
    long n = byWhite ? bb[WN] : bb[BN];
    if ((KNIGHT_ATK[sq] & n) != 0) return true;

    /* bishops / queens */
    long bq = byWhite ? (bb[WB] | bb[WQ]) : (bb[BB] | bb[BQ]);
    if ((bishopAtt(occ, sq) & bq) != 0) return true;

    /* rooks / queens */
    long rq = byWhite ? (bb[WR] | bb[WQ]) : (bb[BR] | bb[BQ]);
    if ((rookAtt(occ, sq) & rq) != 0) return true;

    /* king */
    long k = byWhite ? bb[WK] : bb[BK];
    return (KING_ATK[sq] & k) != 0;
  }
}
