package engine.internal.search.internal;

import static engine.internal.search.PackedPositionFactory.*;
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
  private static final long[] DIAG_MASK_REV  = new long[64];
  private static final long[] ADIAG_MASK_REV = new long[64];
  private static final long[] FILE_MASK_REV  = new long[64];
  private static final long[] RANK_MASK_REV  = new long[64];
  private static final long[] FROM_REV       = new long[64];

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

      DIAG_MASK_REV [sq] = Long.reverse(DIAG_MASK [sq]);
      ADIAG_MASK_REV[sq] = Long.reverse(ADIAG_MASK[sq]);
      FILE_MASK_REV [sq] = Long.reverse(FILE_MASK [sq]);
      RANK_MASK_REV [sq] = Long.reverse(RANK_MASK [sq]);
      FROM_REV      [sq] = Long.reverse(1L << sq);
    }
  }

  private static long ray(long occ, long occRev, int sq,
                          long mask, long maskRev) {
    long from    = 1L << sq;
    long fromRev = FROM_REV[sq];

    long fwd = (occ     & mask)     - (from    << 1);
    long rev = (occRev  & maskRev)  - (fromRev << 1);
    return (fwd ^ Long.reverse(rev)) & mask;
  }

  /* ── public entry point ───────────────────────────────────────── */
  @Override
  public int generate(long[] bb, int[] moves, GenMode mode) {

    final boolean white = whiteToMove(bb[META]);
    final int usP = white ? WP : BP;

    /* aggregate sets */
    long whitePieces = bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK];
    long blackPieces = bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK];
    long own = white ? whitePieces : blackPieces;
    long enemy = white ? blackPieces : whitePieces;
    long occ = own | enemy;
    long occRev  = Long.reverse(occ);

    long enemyAtk = attacksOf(!white, bb, occ, occRev);

    boolean wantCapt = mode != GenMode.QUIETS;
    boolean wantQuiet = mode != GenMode.CAPTURES;
    long captMask = wantCapt ? enemy : 0;
    long quietMask = wantQuiet ? ~occ : 0;

    int n = 0; // output cursor

    /* ============================================================ */
    /* Pawns                                                        */
    /* ============================================================ */
    long pawns = bb[usP];
    int pushDir = white ? 8 : -8;
    long PROMO_RANK = white ? RANK_8 : RANK_1;

    /* ---- single push to 8th/1st (promotions – always, for perft) */
    long one = white ? (pawns << 8) & ~occ : (pawns >>> 8) & ~occ;

    // Emit promotion pushes only when the caller asked for QUIETS or ALL
    if (wantQuiet) {
      long promoPush = one & PROMO_RANK;
      while (promoPush != 0) {
        int to = lsb(promoPush);
        promoPush = popLsb(promoPush);
        n = emitPromotions(moves, n, (to - pushDir), to);
      }
    }

    /* ---- quiet single & double pushes (non-promo) --------------- */
    if (wantQuiet) {
      long quiet = one & ~PROMO_RANK;
      while (quiet != 0) {
        int to = lsb(quiet);
        quiet = popLsb(quiet);
        moves[n++] = ((to - pushDir) << 6) | to;
      }
      long two = white ? (((one & RANK_3) << 8) & ~occ) : (((one & RANK_6) >>> 8) & ~occ);
      while (two != 0) {
        int to = lsb(two);
        two = popLsb(two);
        moves[n++] = ((to - 2 * pushDir) << 6) | to;
      }
    }

    /* ---- captures (incl. promo captures) ------------------------ */
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
        int to = lsb(capL);
        capL = popLsb(capL);
        moves[n++] = ((to + deltaL) << 6) | to;
      }
      while (capR != 0) {
        int to = lsb(capR);
        capR = popLsb(capR);
        moves[n++] = ((to + deltaR) << 6) | to;
      }

      while (promoL != 0) {
        int to = lsb(promoL);
        promoL = popLsb(promoL);
        n = emitPromotions(moves, n, (to + deltaL), to);
      }
      while (promoR != 0) {
        int to = lsb(promoR);
        promoR = popLsb(promoR);
        n = emitPromotions(moves, n, (to + deltaR), to);
      }
    }

    /* ---- en-passant (with legality check) ----------------------- */
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
        int to = lsb(epL);
        epL = popLsb(epL);
        int mv = ((to + deltaL) << 6) | to | (2 << 14);
        if (kingSafeAfter(bb, mv, white)) moves[n++] = mv;
      }
      while (epR != 0) {
        int to = lsb(epR);
        epR = popLsb(epR);
        int mv = ((to + deltaR) << 6) | to | (2 << 14);
        if (kingSafeAfter(bb, mv, white)) moves[n++] = mv;
      }
    }

    /* ============================================================ */
    /* Knights                                                      */
    /* ============================================================ */
    long knights = white ? bb[WN] : bb[BN];
    while (knights != 0) {
      int from = lsb(knights);
      knights = pop(knights);
      long tgt = KNIGHT_ATK[from] & (captMask | quietMask);
      while (tgt != 0) {
        int to = lsb(tgt);
        tgt = pop(tgt);
        moves[n++] = (from << 6) | to;
      }
    }

    /* ============================================================ */
    /* Bishops, Rooks, Queens                                       */
    /* ============================================================ */
    long pieces = white ? (bb[WB] | bb[WR] | bb[WQ]) : (bb[BB] | bb[BR] | bb[BQ]);
    final int usB = white ? WB : BB;
    final int usR = white ? WR : BR;

    while (pieces != 0) {
      int from = lsb(pieces);
      pieces = pop(pieces);
      long bitFrom = 1L << from;
      long tgt =
              ((bitFrom & bb[usB]) != 0)
                      ? bishopAtt(occ, occRev, from)
                      : ((bitFrom & bb[usR]) != 0) ? rookAtt(occ, occRev, from) : queenAtt(occ, occRev, from);
      tgt &= (captMask | quietMask);
      while (tgt != 0) {
        int to = lsb(tgt);
        tgt = pop(tgt);
        moves[n++] = (from << 6) | to;
      }
    }

    /* ============================================================ */
    /* King & castling                                              */
    /* ============================================================ */
    int kingIdx = white ? WK : BK;
    int kingSq = lsb(bb[kingIdx]);
    long safe = KING_ATK[kingSq] & ~own & ~enemyAtk;

    if (wantQuiet) {
      long qs = safe & ~enemy;
      while (qs != 0) {
        int to = lsb(qs);
        qs = popLsb(qs);
        moves[n++] = (kingSq << 6) | to;
      }
    }
    if (wantCapt) {
      long cs = safe & enemy;
      while (cs != 0) {
        int to = lsb(cs);
        cs = popLsb(cs);
        moves[n++] = (kingSq << 6) | to;
      }
    }

    boolean kingInCheck = (enemyAtk & (1L << kingSq)) != 0;
    if (wantQuiet && !kingInCheck) {
      int rights = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
      if (white) {
        if ((rights & 1) != 0 && (occ & 0x60L) == 0 && (enemyAtk & 0x70L) == 0)
          moves[n++] = (4 << 6) | 6 | (3 << 14);
        if ((rights & 2) != 0 && (occ & 0x0EL) == 0 && (enemyAtk & 0x1CL) == 0)
          moves[n++] = (4 << 6) | 2 | (3 << 14);
      } else {
        long ksMask = 0x7000_0000_0000_0000L;
        if ((rights & 4) != 0 && (occ & 0x6000_0000_0000_0000L) == 0 && (enemyAtk & ksMask) == 0)
          moves[n++] = (60 << 6) | 62 | (3 << 14);
        long qsMask = 0x1C00_0000_0000_0000L;
        if ((rights & 8) != 0 && (occ & 0x0E00_0000_0000_0000L) == 0 && (enemyAtk & qsMask) == 0)
          moves[n++] = (60 << 6) | 58 | (3 << 14);
      }
    }

    return n;
  }

  /* ───────────────── helper to emit the 4 promotion types ─────── */
  private static int emitPromotions(int[] moves, int n, int from, int to) {
    int base = (from << 6) | to | (1 << 14);
    moves[n++] = base | (3 << 12); // Q
    moves[n++] = base | (2 << 12); // R
    moves[n++] = base | (1 << 12); // B
    moves[n++] = base; // N
    return n;
  }

  /** play <move> on a clone of <bb>; return true iff our king is still safe */
  private static boolean kingSafeAfter(long[] bb, int move, boolean white) {
    long[] c = bb.clone();

    int from = (move >>> 6) & 0x3F;
    int to = move & 0x3F;
    int prom = (move >>> 12) & 0x7; // 0-3 = N,B,R,Q
    int flag = (move >>> 14) & 0x3; // 0=normal 1=promo 2=EP 3=castle

    int usP = white ? WP : BP;
    int themP = white ? BP : WP;

    long fromBit = 1L << from, toBit = 1L << to;

    if (flag == 3) { // castling: move rook too
      if (white) {
        if (to == 6) c[WR] ^= 0xA0L; // h1->f1
        else c[WR] ^= 0x9L; // a1->d1
      } else {
        if (to == 62) c[BR] ^= 0xA000000000000000L; // h8->f8
        else c[BR] ^= 0x900000000000000L; // a8->d8
      }
    } else if (flag == 2) { // en-passant: remove captured pawn
      int capSq = white ? (to - 8) : (to + 8);
      c[themP] ^= 1L << capSq;
    }

    /* move the piece itself (any type) */
    for (int i = WP; i <= BK; i++) {
      if ((c[i] & fromBit) != 0) {
        c[i] ^= fromBit | toBit; // move our piece
        if (flag == 1) { // promotion
          c[usP] ^= toBit; // remove pawn
          int dst =
                  prom == 3
                          ? (white ? WQ : BQ)
                          : prom == 2
                          ? (white ? WR : BR)
                          : prom == 1 ? (white ? WB : BB) : (white ? WN : BN);
          c[dst] ^= toBit; // add promoted piece
        }
        break;
      }
    }

    /* remove any captured piece that was on the destination square           */
    /* (there is none for en-passant)                                         */
    if (flag != 2) {
      int themStart = white ? BP : WP; // BP..BK  or  WP..WK
      int themEnd = themStart + 5; // inclusive
      for (int i = themStart; i <= themEnd; i++) {
        if ((c[i] & toBit) != 0) {
          c[i] ^= toBit;
          break;
        }
      }
    }

    long occ = 0;
    for (int i = WP; i <= BK; i++) occ |= c[i];
    int kSq = lsb(c[white ? WK : BK]);
    long occRev = Long.reverse(occ);

    return (attacksOf(!white, c, occ, occRev) & (1L << kSq)) == 0;
  }

  @Override
  public boolean inCheck(long[] bb) {
    boolean white = whiteToMove(bb[META]);
    int kSq = lsb(bb[white ? WK : BK]);

    long occ = 0;
    for (int i = WP; i <= BK; ++i) occ |= bb[i];
    long occRev = Long.reverse(occ);

    return (attacksOf(!white, bb, occ, occRev) & (1L << kSq)) != 0;
  }

  /* ───────────────── enemy attacks, HQ helpers & misc ─────────── */
  private static long attacksOf(boolean white, long[] bb, long occ, long occRev) {
    long atk = 0;

    /* pawns */
    long p = white ? bb[WP] : bb[BP];
    atk |=
            white
                    ? ((p << 7) & ~FILE_H) | ((p << 9) & ~FILE_A)
                    : ((p >>> 7) & ~FILE_A) | ((p >>> 9) & ~FILE_H);

    /* knights */
    long n = white ? bb[WN] : bb[BN];
    while (n != 0) {
      int s = lsb(n);
      n = pop(n);
      atk |= KNIGHT_ATK[s];
    }

    /* bishops */
    long b = white ? bb[WB] : bb[BB];
    while (b != 0) {
      int s = lsb(b);
      b = pop(b);
      atk |= bishopAtt(occ, occRev, s);
    }

    /* rooks */
    long r = white ? bb[WR] : bb[BR];
    while (r != 0) {
      int s = lsb(r);
      r = pop(r);
      atk |= rookAtt(occ, occRev, s);
    }

    /* queens */
    long q = white ? bb[WQ] : bb[BQ];
    while (q != 0) {
      int s = lsb(q);
      q = pop(q);
      atk |= queenAtt(occ, occRev, s);
    }

    /* king */
    int kSq = lsb(white ? bb[WK] : bb[BK]);
    return atk | KING_ATK[kSq];
  }

  private static long rookAtt(long occ, long occRev, int sq) {
    return ray(occ, occRev, sq, FILE_MASK[sq], FILE_MASK_REV[sq]) |
            ray(occ, occRev, sq, RANK_MASK[sq], RANK_MASK_REV[sq]);
  }

  private static long bishopAtt(long occ, long occRev, int sq) {
    return ray(occ, occRev, sq, DIAG_MASK[sq],  DIAG_MASK_REV[sq])  |
            ray(occ, occRev, sq, ADIAG_MASK[sq], ADIAG_MASK_REV[sq]);
  }

  private static long queenAtt(long occ, long occRev, int sq) {
    return rookAtt(occ, occRev, sq) | bishopAtt(occ, occRev, sq);
  }

  /* ───────────────── misc small helpers ───────────────────────── */
  private static long popLsb(long b) {
    return b & (b - 1);
  }

  private static int lsb(long b) {
    return Long.numberOfTrailingZeros(b);
  }

  private static long pop(long b) {
    return b & (b - 1);
  }

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
}
