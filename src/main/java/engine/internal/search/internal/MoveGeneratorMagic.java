package engine.internal.search.internal;

import static engine.internal.search.PackedMoveFactory.*;
import static engine.internal.search.PackedPositionFactory.*;

import engine.internal.search.MoveGenerator;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * High-performance pseudo-legal move generator that uses “fancy-magic-bitboard” implementation
 * – *without* using any hard-coded magic constants. All magic numbers and attack tables are
 * discovered at start-up (≈ 40 ms on a modern JVM) and are thereafter read-only.
 */
public final class MoveGeneratorMagic implements MoveGenerator {

  /* ───────────────────────── geometry / helpers ───────────────────────── */

  private static final int N  =  8,  S  = -8,  E  =  1,  W  = -1,
          NE =  9,  NW =  7,  SE = -7, SW = -9;

  private static boolean onBoard(int r, int f) { return r >= 0 && r < 8 && f >= 0 && f < 8; }
  private static boolean onBoard(int sq)       { return (sq & ~63) == 0; }

  private static int lsb(long b) { return Long.numberOfTrailingZeros(b); }
  private static long pop(long b){ return b & (b - 1); }

  /* ───────────────────────── basic constant masks ─────────────────────── */

  private static final long FILE_A = 0x0101_0101_0101_0101L;
  private static final long FILE_H = FILE_A << 7;
  private static final long RANK_1 = 0xFFL;
  private static final long RANK_2 = RANK_1 << 8;
  private static final long RANK_3 = RANK_1 << 16;
  private static final long RANK_6 = RANK_1 << 40;
  private static final long RANK_7 = RANK_1 << 48;
  private static final long RANK_8 = RANK_1 << 56;

  private static long rankBB(int sq) { return 0xFFL << (sq & 56); }
  private static long fileBB(int sq) { return FILE_A << (sq & 7); }

  /* directions: 0-3 rook (N,S,E,W) – 4-7 bishop (NE,NW,SE,SW) */
  private static final int[] DR = { 1,-1, 0, 0, 1, 1,-1,-1 };
  private static final int[] DF = { 0, 0, 1,-1, 1,-1, 1,-1 };

  /* ───────────────────────── “static” tables ──────────────────────────── */

  private static final long[] KING_ATK   = new long[64];
  private static final long[] KNIGHT_ATK = new long[64];
  private static final long[][] RAY_MASK = new long[8][64];     // directional rays
  private static final long[][] LINE     = new long[64][64];    // line a↔b

  /* ───────────── magic infrastructure (generated at start-up) ─────────── */

  private static final long[] ROOK_MASK   = new long[64];
  private static final long[] BISHOP_MASK = new long[64];

  private static final int[]  ROOK_SHIFT   = new int[64];
  private static final int[]  BISHOP_SHIFT = new int[64];

  private static final long[] ROOK_MAGIC   = new long[64];
  private static final long[] BISHOP_MAGIC = new long[64];

  private static final long[][] ROOK_ATTACK   = new long[64][];
  private static final long[][] BISHOP_ATTACK = new long[64][];

  /* ───────────────────────── initialisation block ─────────────────────── */

  static {
    /* king + knight attack look-ups, rays and LINE[][] table */
    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, f = sq & 7;

      long k = 0, n = 0;
      for (int d = 0; d < 8; ++d) {
        int rr = r + DR[d], ff = f + DF[d];
        if (onBoard(rr, ff)) k |= 1L << (rr * 8 + ff);
      }
      KING_ATK[sq] = k;

      int[] kr = {-2,-1, 1, 2, 2, 1,-1,-2};
      int[] kf = { 1, 2, 2, 1,-1,-2,-2,-1};
      for (int i = 0; i < 8; ++i)
        if (onBoard(r + kr[i], f + kf[i]))
          n |= 1L << ((r + kr[i]) * 8 + (f + kf[i]));
      KNIGHT_ATK[sq] = n;

      for (int d = 0; d < 8; ++d) {
        long ray = 0;
        int rr = r + DR[d], ff = f + DF[d];
        while (onBoard(rr, ff)) {
          ray |= 1L << (rr * 8 + ff);
          rr += DR[d]; ff += DF[d];
        }
        RAY_MASK[d][sq] = ray;
      }
    }

    for (int a = 0; a < 64; ++a) {
      int ar = a >>> 3, af = a & 7;
      for (int b = 0; b < 64; ++b) if (a != b) {
        int br = b >>> 3, bf = b & 7;
        long m = 0;
        if (ar == br)               m = rankBB(a);
        else if (af == bf)          m = fileBB(a);
        else if (ar - af == br - bf) m = mainDiagMask(ar, af);
        else if (ar + af == br + bf) m = antiDiagMask(ar, af);
        LINE[a][b] = m;
      }
    }

    /* masks + shifts */
    for (int sq = 0; sq < 64; ++sq) {
      ROOK_MASK  [sq] = rookMask  (sq);
      BISHOP_MASK[sq] = bishopMask(sq);

      ROOK_SHIFT  [sq] = 64 - Long.bitCount(ROOK_MASK  [sq]);
      BISHOP_SHIFT[sq] = 64 - Long.bitCount(BISHOP_MASK[sq]);
    }

    /* finally: discover magics and build attack tables */
    initSliderTables();
  }

  /* ───────────────────────── magic generation ─────────────────────────── */

  private static void initSliderTables() {

    Random rng = ThreadLocalRandom.current();

    for (int sq = 0; sq < 64; ++sq)
      for (boolean rook : new boolean[]{false, true}) {  // bishop first (like SF)

        long   mask  = rook ? ROOK_MASK[sq] : BISHOP_MASK[sq];
        int    bits  = Long.bitCount(mask);
        int    size  = 1 << bits;
        long[] occ   = new long[size];
        long[] ref   = new long[size];

        /* enumerate all subsets of mask (“carry-rippler” trick) */
        int   entry  = 0;
        long  subset = 0;
        do {
          occ[entry] = subset;
          ref[entry] = rook ? rookAttacks(sq, subset)
                  : bishopAttacks(sq, subset);
          entry++;
          subset = (subset - mask) & mask;
        } while (subset != 0);

        /* search for a working magic */
        long magic;
        int  shift = rook ? ROOK_SHIFT[sq] : BISHOP_SHIFT[sq];
        long[] attack = new long[1 << (64 - shift)];

        search:
        while (true) {

          magic = randomSparse64(rng);

          /* magic must have at least 6 bits set in its top byte */
          if (Long.bitCount((magic * mask) & 0xFF00_0000_0000_0000L) < 6) continue;

          Arrays.fill(attack, 0);

          for (int i = 0; i < size; ++i) {
            int idx = (int)((occ[i] * magic) >>> shift);

            if (attack[idx] == 0)
              attack[idx] = ref[i];
            else if (attack[idx] != ref[i])   // collision → try next magic
              continue search;
          }
          break; // found!
        }

        if (rook) {
          ROOK_MAGIC  [sq] = magic;
          ROOK_ATTACK [sq] = attack;
        } else {
          BISHOP_MAGIC[sq] = magic;
          BISHOP_ATTACK[sq] = attack;
        }
      }
  }

  /** random 64-bit number with ~ few bits set (“sparse”) */
  private static long randomSparse64(Random rng) {
    return rng.nextLong() & rng.nextLong() & rng.nextLong();
  }

  /* ─────────────────────── masks & empty-board attacks ────────────────── */

  private static long slidingEmpty(int sq, int dr, int df) {
    long b = 0;
    int r = sq >>> 3, f = sq & 7;
    int rr = r + dr, ff = f + df;
    while (onBoard(rr, ff)) {
      b |= 1L << (rr * 8 + ff);
      rr += dr; ff += df;
    }
    return b;
  }

  private static long rookMask(int sq) {
    long m=0; int r=sq>>>3,f=sq&7;
    for (int ff=f+1; ff<7; ++ff) m|=1L<<(r*8+ff);
    for (int ff=f-1; ff>0; --ff) m|=1L<<(r*8+ff);
    for (int rr=r+1; rr<7; ++rr) m|=1L<<(rr*8+f);
    for (int rr=r-1; rr>0; --rr) m|=1L<<(rr*8+f);
    return m;
  }

  private static long bishopMask(int sq) {
    long edges = ((RANK_1|RANK_8)&~rankBB(sq)) | ((FILE_A|FILE_H)&~fileBB(sq));
    long d = bishopAttacksEmpty(sq);
    return d & ~edges;
  }

  private static long bishopAttacksEmpty(int sq) {
    long b=0; int r=sq>>>3,f=sq&7;
    for (int d=4; d<8; ++d) b |= slidingEmpty(sq, DR[d], DF[d]);
    return b;
  }

  /* run-time sliding attacks (with blockers) */

  private static long sliding(int sq, int dr, int df, long occ) {
    long b = 0;
    int r = sq>>>3, f=sq&7;
    int rr = r + dr, ff = f + df;
    while (onBoard(rr,ff)) {
      int s = rr*8+ff;
      b |= 1L<<s;
      if ((occ & (1L<<s)) != 0) break;
      rr += dr; ff += df;
    }
    return b;
  }

  private static long rookAttacks(int sq,long occ){
    return sliding(sq, 1,0,occ)|sliding(sq,-1,0,occ)|
            sliding(sq,0,1,occ)|sliding(sq,0,-1,occ);
  }
  private static long bishopAttacks(int sq,long occ){
    return sliding(sq,1,1,occ)|sliding(sq,1,-1,occ)|
            sliding(sq,-1,1,occ)|sliding(sq,-1,-1,occ);
  }

  /* magic look-ups */
  private static long rookMagic(long occ,int sq){
    long idx = ((occ & ROOK_MASK[sq]) * ROOK_MAGIC[sq]) >>> ROOK_SHIFT[sq];
    return ROOK_ATTACK[sq][(int)idx];
  }
  private static long bishopMagic(long occ,int sq){
    long idx = ((occ & BISHOP_MASK[sq]) * BISHOP_MAGIC[sq]) >>> BISHOP_SHIFT[sq];
    return BISHOP_ATTACK[sq][(int)idx];
  }

  /* ═══════════════ public entry-point ═════════════════════════════ */
  @Override
  public int generate(long[] packed, int[] moves, GenMode mode) {

    final boolean white = whiteToMove(packed[META]);
    final long own =
            white
                    ? (packed[WP] | packed[WN] | packed[WB] | packed[WR] | packed[WQ] | packed[WK])
                    : (packed[BP] | packed[BN] | packed[BB] | packed[BR] | packed[BQ] | packed[BK]);
    final long enemy =
            white
                    ? (packed[BP] | packed[BN] | packed[BB] | packed[BR] | packed[BQ] | packed[BK])
                    : (packed[WP] | packed[WN] | packed[WB] | packed[WR] | packed[WQ] | packed[WK]);
    final long occ = own | enemy;

    final boolean wantCapt = mode != GenMode.QUIETS;
    final boolean wantQuiet = mode != GenMode.CAPTURES;
    final long captMask = wantCapt ? enemy : 0;
    final long quietMask = wantQuiet ? ~occ : 0;

    final int kIdx = white ? WK : BK;
    final int kSq = lsb(packed[kIdx]);

    final CheckInfo ci = scanForPinsAndChecks(white, kSq, packed, own, occ);

    /* cursor for lambda (must be effectively-final) */
    final int[] out = {0};
    final java.util.function.IntConsumer PUSH = mv -> moves[out[0]++] = mv;

    /* ── KING moves ─────────────────────────────────────────────── */
    long safeSquares = KING_ATK[kSq] & ~own & ~ci.enemyAtk;
    long kingTargetMask = 0;
    if (wantQuiet) kingTargetMask |= safeSquares & ~enemy;
    if (wantCapt) kingTargetMask |= safeSquares & enemy;
    while (kingTargetMask != 0) {
      int to = lsb(kingTargetMask);
      kingTargetMask = pop(kingTargetMask);
      PUSH.accept((kSq << 6) | to);
    }

    /* ── EVASIONS ──────────────────────────────────────────────── */
    if (mode == GenMode.EVASIONS) {
      if (ci.doubleCheck) return out[0];
      long legalMask = ci.blockMask | ci.checkers;
      generatePieces(
              white,
              kSq,
              packed,
              occ,
              captMask,
              quietMask,
              legalMask,
              ci.pinMask,
              ci.checkers,
              mode,
              PUSH);
      return out[0];
    }

    /* ── normal generation ─────────────────────────────────────── */
    generatePieces(
            white, kSq, packed, occ, captMask, quietMask, ~0L, ci.pinMask, ci.checkers, mode, PUSH);

    if (wantQuiet && !ci.inCheck) // castling
      emitCastles(white, packed, occ, ci.enemyAtk, PUSH);

    return out[0];
  }

  /* ═════════════ piece move generation (fully fixed) ════════════ */
  private static void generatePieces(
          boolean white,
          int kSq,
          long[] bb,
          long occ,
          long captMask,
          long quietMask,
          long legalMask,
          long pinMask,
          long checkersMask,
          GenMode mode,
          java.util.function.IntConsumer PUSH) {

    /* ░░░ PAWNS ░░░ */
    long pawns = white ? bb[WP] : bb[BP];
    int push = white ? 8 : -8;
    final int deltaL = white ? -7 : 7; // from = to + delta
    final int deltaR = white ? -9 : 9;

    long unpinned = pawns & ~pinMask;
    long pinned = pawns & pinMask;

    /* un-pinned pawns (fast bitboard path) */
    if (unpinned != 0) {
      long single = white ? ((unpinned << 8) & ~occ) : ((unpinned >>> 8) & ~occ);
      long promo = single & (white ? RANK_8 : RANK_1);
      long quiet = single & ~promo & quietMask & legalMask;

      while (quiet != 0) {
        int to = lsb(quiet);
        quiet = pop(quiet);
        PUSH.accept(((to - push) << 6) | to);
      }
      while (promo != 0) {
        int to = lsb(promo);
        promo = pop(promo);
        addPromotions(PUSH, to - push, to, false, mode);
      }

      long dbl = white ? (((single & RANK_3) << 8) & ~occ) : (((single & RANK_6) >>> 8) & ~occ);
      dbl &= quietMask & legalMask;
      while (dbl != 0) {
        int to = lsb(dbl);
        dbl = pop(dbl);
        PUSH.accept(((to - 2 * push) << 6) | to);
      }

      /* captures (inc. promo by capture) */
      long capL = white ? ((unpinned & ~FILE_A) << 7) : ((unpinned & ~FILE_H) >>> 7);
      long capR = white ? ((unpinned & ~FILE_H) << 9) : ((unpinned & ~FILE_A) >>> 9);

      long left = capL & captMask & legalMask;
      long right = capR & captMask & legalMask;
      long promoL = left & (white ? RANK_8 : RANK_1);
      long promoR = right & (white ? RANK_8 : RANK_1);
      left &= ~promoL;
      right &= ~promoR;

      while (left != 0) {
        int to = lsb(left);
        left = pop(left);
        PUSH.accept(((to + deltaL) << 6) | to);
      }
      while (right != 0) {
        int to = lsb(right);
        right = pop(right);
        PUSH.accept(((to + deltaR) << 6) | to);
      }

      while (promoL != 0) {
        int to = lsb(promoL);
        promoL = pop(promoL);
        addPromotions(PUSH, to + deltaL, to, true, mode);
      }
      while (promoR != 0) {
        int to = lsb(promoR);
        promoR = pop(promoR);
        addPromotions(PUSH, to + deltaR, to, true, mode);
      }
    }

    /* pinned pawns (individual checks) */
    while (pinned != 0) {
      int from = lsb(pinned);
      pinned = pop(pinned);
      long pinLine = LINE[kSq][from];

      /* vertical push */
      if ((pinLine & (FILE_A << (from & 7))) != 0) {
        int to = from + push;
        if (inBoard(to) && ((occ >>> to) & 1) == 0 && ((1L << to) & quietMask & legalMask) != 0) {
          if (((1L << to) & (white ? RANK_8 : RANK_1)) != 0)
            addPromotions(PUSH, from, to, false, mode);
          else PUSH.accept((from << 6) | to);

          /* double */
          int to2 = from + 2 * push;
          if ((from / 8) == (white ? 1 : 6)
                  && ((occ >>> to2) & 1) == 0
                  && ((1L << to2) & quietMask & legalMask) != 0) PUSH.accept((from << 6) | to2);
        }
      }

      /* captures along pin-line */
      int lShift = white ? 7 : -9;
      int rShift = white ? 9 : -7;

      int toL = from + lShift;
      if (inBoard(toL)
              && ((1L << toL) & pinLine) != 0
              && ((1L << toL) & captMask & legalMask) != 0) {
        if (((1L << toL) & (white ? RANK_8 : RANK_1)) != 0)
          addPromotions(PUSH, from, toL, true, mode);
        else PUSH.accept((from << 6) | toL);
      }

      int toR = from + rShift;
      if (inBoard(toR)
              && ((1L << toR) & pinLine) != 0
              && ((1L << toR) & captMask & legalMask) != 0) {
        if (((1L << toR) & (white ? RANK_8 : RANK_1)) != 0)
          addPromotions(PUSH, from, toR, true, mode);
        else PUSH.accept((from << 6) | toR);
      }
    }

    /* ─── en-passant (with discovered-check test) ─────────────────── */
    long epSqRaw = (bb[META] & EP_MASK) >>> EP_SHIFT;
    if (epSqRaw != 63 && captMask != 0) {
      int epSq = (int) epSqRaw; // target square
      long epBit = 1L << epSq;

      long fromMask;
      if (white) {
        fromMask = ((epBit >>> 9) & pawns & ~FILE_H) | ((epBit >>> 7) & pawns & ~FILE_A);
      } else {
        fromMask = ((epBit << 9) & pawns & ~FILE_A) | ((epBit << 7) & pawns & ~FILE_H);
      }

      /* examine every candidate EP capture */
      while (fromMask != 0) {
        int from = lsb(fromMask);
        fromMask = pop(fromMask);

        /* --- pin legality as before --- */
        boolean pinOk = ((1L << from) & pinMask) == 0 || ((1L << epSq) & LINE[kSq][from]) != 0;

        if (!pinOk) continue;

        long occ2 =
                occ
                        ^ (1L << from) // remove our pawn
                        ^ (1L << (white ? epSq - 8 : epSq + 8)) // remove captured pawn
                        | (1L << epSq); // pawn appears on epSq

        long rookAtk = rookMagic(occ2, kSq);
        long bishopAtk = bishopMagic(occ2, kSq);

        long enemyR = white ? bb[BR] : bb[WR];
        long enemyB = white ? bb[BB] : bb[WB];
        long enemyQ = white ? bb[BQ] : bb[WQ];

        boolean discovered =
                (rookAtk & (enemyR | enemyQ)) != 0 || (bishopAtk & (enemyB | enemyQ)) != 0;

        // If we are in EVASIONS mode, the captured pawn itself might be the only checker.
        // That makes the EP legal even though epSq is *not* in legalMask.
        boolean capturesChecker =
                (mode == GenMode.EVASIONS)
                        && (((1L << (white ? epSq - 8 : epSq + 8)) & checkersMask) != 0);

        if (!discovered && (((1L << epSq) & legalMask) != 0 || capturesChecker)) {
          PUSH.accept((from << 6) | epSq | (2 << 14));
        }
      }
    }

    /* ░░░ Knights ░░░ */
    long knights = white ? bb[WN] : bb[BN];
    while (knights != 0) {
      int from = lsb(knights);
      knights = pop(knights);
      if (((1L << from) & pinMask) != 0) continue; // pinned knights can't move
      long tgt = KNIGHT_ATK[from] & (captMask | quietMask) & legalMask;
      while (tgt != 0) {
        int to = lsb(tgt);
        tgt = pop(tgt);
        PUSH.accept((from << 6) | to);
      }
    }

    /* ░░░ Bishops ░░░ */
    long bishops = white ? bb[WB] : bb[BB];
    while (bishops != 0) {
      int from = lsb(bishops);
      bishops = pop(bishops);
      long pinLine = ((1L << from) & pinMask) != 0 ? LINE[kSq][from] : ~0L;
      long tgt = bishopMagic(occ, from) & (captMask | quietMask) & legalMask & pinLine;
      while (tgt != 0) {
        int to = lsb(tgt);
        tgt = pop(tgt);
        PUSH.accept((from << 6) | to);
      }
    }

    /* ░░░ Rooks ░░░ */
    long rooks = white ? bb[WR] : bb[BR];
    while (rooks != 0) {
      int from = lsb(rooks);
      rooks = pop(rooks);
      long pinLine = ((1L << from) & pinMask) != 0 ? LINE[kSq][from] : ~0L;
      long tgt = rookMagic(occ, from) & (captMask | quietMask) & legalMask & pinLine;
      while (tgt != 0) {
        int to = lsb(tgt);
        tgt = pop(tgt);
        PUSH.accept((from << 6) | to);
      }
    }

    /* ░░░ Queens ░░░ */
    long queens = white ? bb[WQ] : bb[BQ];
    while (queens != 0) {
      int from = lsb(queens);
      queens = pop(queens);
      long pinLine = ((1L << from) & pinMask) != 0 ? LINE[kSq][from] : ~0L;
      long tgt =
              (rookMagic(occ, from) | bishopMagic(occ, from))
                      & (captMask | quietMask)
                      & legalMask
                      & pinLine;
      while (tgt != 0) {
        int to = lsb(tgt);
        tgt = pop(tgt);
        PUSH.accept((from << 6) | to);
      }
    }
  }

  @Override
  public boolean inCheck(long[] bb) {
    boolean white = whiteToMove(bb[META]);
    int kSq = lsb(bb[white ? WK : BK]);

    /* reuse the existing check-scanner */
    long own =
            white
                    ? (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK])
                    : (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK]);
    long occ =
            own
                    | (white
                    ? /* enemy */ (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK])
                    : (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK]));

    return scanForPinsAndChecks(white, kSq, bb, own, occ).inCheck;
  }

  /* ═════════════ promotions helper (all modes) ═══════════════════ */
  private static void addPromotions(
          java.util.function.IntConsumer PUSH, int from, int to, boolean cap, GenMode mode) {
    PUSH.accept((from << 6) | to | (1 << 14) | (3 << 12)); // Q
    boolean others = (mode != GenMode.QUIETS) || !cap; // always in CAPTURES / EVASIONS
    if (!others) return;
    int base = (from << 6) | to | (1 << 14);
    PUSH.accept(base | (2 << 12)); // R
    PUSH.accept(base | (1 << 12)); // B
    PUSH.accept(base); // N
  }

  /* ═════════════ castling (unchanged) ═══════════════════════════ */
  private static void emitCastles(
          boolean white, long[] bb, long occ, long enemyAtk, java.util.function.IntConsumer PUSH) {
    int rights = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
    if (white) {
      if ((rights & 1) != 0 && (occ & 0x60L) == 0 && (enemyAtk & 0x70L) == 0)
        PUSH.accept((4 << 6) | 6 | (3 << 14)); // K-side
      if ((rights & 2) != 0 && (occ & 0x0EL) == 0 && (enemyAtk & 0x1CL) == 0)
        PUSH.accept((4 << 6) | 2 | (3 << 14)); // Q-side
    } else {
      long ksMask = 0x7000_0000_0000_0000L;
      if ((rights & 4) != 0 && (occ & 0x6000_0000_0000_0000L) == 0 && (enemyAtk & ksMask) == 0)
        PUSH.accept((60 << 6) | 62 | (3 << 14)); // k-side
      if ((rights & 8) != 0
              && (occ & 0x0E00_0000_0000_0000L) == 0
              && (enemyAtk & 0x1C00_0000_0000_0000L) == 0)
        PUSH.accept((60 << 6) | 58 | (3 << 14)); // q-side
    }
  }

  /* ═════════════ check / pin detection (king ring fixed) ════════ */
  private record CheckInfo(
          long enemyAtk,
          long checkers,
          long blockMask,
          long pinMask,
          boolean doubleCheck,
          boolean inCheck) {}

  private static CheckInfo scanForPinsAndChecks(
          boolean white, int kSq, long[] bb, long own, long occ) {

    long eP = white ? bb[BP] : bb[WP];
    long eN = white ? bb[BN] : bb[WN];
    long eB = white ? bb[BB] : bb[WB];
    long eR = white ? bb[BR] : bb[WR];
    long eQ = white ? bb[BQ] : bb[WQ];

    long checkers = 0, pinMask = 0, blockMask = 0;

    /* pawn + knight checks */
    long pawnAtk =
            white
                    ? (((eP & ~FILE_H) >>> 7) | ((eP & ~FILE_A) >>> 9))
                    : (((eP & ~FILE_A) << 7) | ((eP & ~FILE_H) << 9));

    if (white) pawnAtk &= ~(RANK_8 | RANK_7);
    else pawnAtk &= ~(RANK_1 | RANK_2);

    checkers |= attackerBitsPawn(eP, kSq, white);
    checkers |= KNIGHT_ATK[kSq] & eN;

    /* sliders */
    for (int d = 0; d < 8; ++d) {
      long ray = RAY_MASK[d][kSq];
      long blockers = ray & occ;
      if (blockers == 0) continue;

      // Use MSB on the four “south-going” rays
      long firstBit = (d == 1 || d == 3 || d == 6 || d == 7)   // S, W, SE, SW
              ? Long.highestOneBit(blockers)
              : Long.lowestOneBit(blockers);          // N, E, NE, NW

      int  firstSq  = Long.numberOfTrailingZeros(firstBit);
      long first = 1L << firstSq;
      long sliders = d < 4 ? (eR | eQ) : (eB | eQ);

      if ((first & sliders) != 0) { // direct check
        checkers |= first;
        blockMask |= between(kSq, firstSq);
      } else if ((first & own) != 0) {          // maybe a pin
        long behind = blockers & ~first;    // all pieces behind the candidate
        if (behind != 0) {
          long nearest = (d == 1 || d == 3 || d == 6 || d == 7)   // S, W, SE, SW
                  ? Long.highestOneBit(behind)               //   → MSB
                  : Long.lowestOneBit(behind);               //   → LSB

          // no other blockers between, and the nearest one is a slider
          if ((nearest & sliders) != 0 && (behind & ~nearest) == 0)
            pinMask |= first;                                   // real pin
        }
      }
    }

    long enemyAtk = pawnAtk | knightRays(eN);

    long tmp = eR;
    while (tmp != 0) {
      int s = lsb(tmp);
      tmp = pop(tmp);
      enemyAtk |= rookMagic(occ, s);
    }
    tmp = eB;
    while (tmp != 0) {
      int s = lsb(tmp);
      tmp = pop(tmp);
      enemyAtk |= bishopMagic(occ, s);
    }
    tmp = eQ;
    while (tmp != 0) {
      int s = lsb(tmp);
      tmp = pop(tmp);
      enemyAtk |= rookMagic(occ, s) | bishopMagic(occ, s);
    }

    /* king ring – forbid adjacent kings */
    int enemyKingSq = lsb(white ? bb[BK] : bb[WK]);
    enemyAtk |= KING_ATK[enemyKingSq];

    boolean dbl = Long.bitCount(checkers) > 1;
    boolean inC = checkers != 0;
    return new CheckInfo(enemyAtk, checkers, blockMask, pinMask, dbl, inC);
  }

  /* ═══════ helpers / geometry ═══════════════ */
  private static long knightRays(long knights) {
    long atk = 0;
    while (knights != 0) {
      int s = lsb(knights);
      knights = pop(knights);
      atk |= KNIGHT_ATK[s];
    }
    return atk;
  }

  private static long between(int a, int b) {
    return LINE[a][b] & ~(1L << a) & ~(1L << b);
  }

  private static long mainDiagMask(int r, int f) {
    long m = 0;
    for (int rr = 0, ff = f - r; rr < 8; ++rr, ++ff)
      if (ff >= 0 && ff < 8) m |= 1L << (rr * 8 + ff);
    return m;
  }

  private static long antiDiagMask(int r, int f) {
    long m = 0;
    for (int rr = 0, ff = f + r; rr < 8; ++rr, --ff)
      if (ff >= 0 && ff < 8) m |= 1L << (rr * 8 + ff);
    return m;
  }

  /* magic-table builders, attack generators, masks … unchanged from original */

  /* —— tiny bit tricks —— */
  private static boolean in(int r, int f) {
    return r >= 0 && r < 8 && f >= 0 && f < 8;
  }

  private static boolean inBoard(int sq) {
    return (sq & ~63) == 0;
  }

  private static long attackerBitsPawn(long enemyPawns, int kSq, boolean whiteKing) {
    long res = 0;
    int file = kSq & 7; // 0 = a-file, 7 = h-file

    if (whiteKing) { // black pawns attack from the south
      if (file != 0) { // NW attack only if king not on a-file
        int s = kSq + 7;
        if (s < 64 && ((enemyPawns >>> s) & 1) != 0) res |= 1L << s;
      }
      if (file != 7) { // NE attack only if king not on h-file
        int s = kSq + 9;
        if (s < 64 && ((enemyPawns >>> s) & 1) != 0) res |= 1L << s;
      }
    } else { // white pawns attack from the north
      if (file != 7) {
        int s = kSq - 7;
        if (s >= 0 && ((enemyPawns >>> s) & 1) != 0) res |= 1L << s;
      }
      if (file != 0) {
        int s = kSq - 9;
        if (s >= 0 && ((enemyPawns >>> s) & 1) != 0) res |= 1L << s;
      }
    }
    return res;
  }

  /* ---------- magic-table construction (unchanged) --------------- */
  private static void buildTable(int sq, boolean rook) {
    long mask = rook ? ROOK_MASK[sq] : BISHOP_MASK[sq];
    long magic = rook ? ROOK_MAGIC[sq] : BISHOP_MAGIC[sq];
    int shift = rook ? ROOK_SHIFT[sq] : BISHOP_SHIFT[sq];

    int entries = 1 << (64 - shift);
    long[] table = new long[entries];

    int bits = Long.bitCount(mask);
    int max = 1 << bits;

    for (int subset = 0; subset < max; ++subset) {
      long occ = subsetToBits(mask, subset);
      int idx = (int) ((occ * magic) >>> shift);
      table[idx] = rook ? rookAttacks(sq, occ) : bishopAttacks(sq, occ);
    }
    if (rook) ROOK_ATTACK[sq] = table;
    else BISHOP_ATTACK[sq] = table;
  }

  private static long subsetToBits(long mask, int subset) {
    long bits = 0;
    for (int i = 0; i < 64; ++i)
      if (((mask >> i) & 1) != 0) {
        if ((subset & 1) != 0) bits |= 1L << i;
        subset >>= 1;
      }
    return bits;
  }

  private static long slidingAttack(int sq, int d, long occ) {
    long attacks = 0;
    int s = sq;

    while (true) {
      int to = s + d;                       // next square in that direction
      // off board? -> stop
      int rDiff = Math.abs((to >>> 3) - (s >>> 3));
      int fDiff = Math.abs((to & 7)     - (s & 7));
      if (to < 0 || to > 63 || Math.max(rDiff, fDiff) != 1)
        break;

      s = to;                               // square is on board
      attacks |= 1L << s;                   // include it
      if ((occ & (1L << s)) != 0)           // stop behind a blocker
        break;
    }
    return attacks;
  }
}
