package engine.internal.search.internal;

import static engine.internal.search.PackedMoveFactory.*;
import static engine.internal.search.PackedPositionFactory.*;

import engine.internal.search.MoveGenerator;

/**
 * High-performance **pseudo-legal** move generator using magic-bitboard lookup tables.
 *
 * <p>Fully corrected version – fixes king-adjacency, en-passant legality, pinned-pawn EP, and
 * missing under-promotions in *CAPTURES* mode.
 */
public final class MoveGeneratorMagic implements MoveGenerator {

  /* ══════════════════ basic masks ════════════════════════════════ */
  private static final long FILE_A = 0x0101_0101_0101_0101L;
  private static final long FILE_H = FILE_A << 7;
  private static final long RANK_1 = 0xFFL;
  private static final long RANK_2 = RANK_1 << 8;
  private static final long RANK_3 = RANK_1 << 16;
  private static final long RANK_6 = RANK_1 << 40;
  private static final long RANK_7 = RANK_1 << 48;
  private static final long RANK_8 = RANK_1 << 56;

  /* directions: 0-3 rook-like (N,S,E,W) – 4-7 bishop-like (NE,NW,SE,SW) */
  private static final int[] DR = {1, -1, 0, 0, 1, 1, -1, -1};
  private static final int[] DF = {0, 0, 1, -1, 1, -1, 1, -1};

  /* ═════════════ king / knight / geometry ════════════════════════ */
  private static final long[] KING_ATK = new long[64];
  private static final long[] KNIGHT_ATK = new long[64];
  private static final long[][] RAY_MASK = new long[8][64]; // forward rays
  private static final long[][] LINE = new long[64][64]; // whole line

  /* ═══════════════════  magic data  ═══════════════════════════════ */
  private static final long[] ROOK_MAGIC = { /* 64 numbers */
    0x8a80104000800020L, 0x140002000100040L, 0x2801880a0017001L, 0x100081001000420L,
    0x200020010080420L, 0x3001c0002010008L, 0x8480008002000100L, 0x2080088004402900L,
    0x800098204000L, 0x2024401000200040L, 0x100802000801000L, 0x120800800801000L,
    0x208808088000400L, 0x2802200800400L, 0x2200800100020080L, 0x801000060821100L,
    0x80044006422000L, 0x100808020004000L, 0x12108a0010204200L, 0x140848010000802L,
    0x481828014002800L, 0x8094004002004100L, 0x4010040010010802L, 0x20008806104L,
    0x100400080208000L, 0x2040002120081000L, 0x21200680100081L, 0x20100080080080L,
    0x2000a00200410L, 0x20080800400L, 0x80088400100102L, 0x80004600042881L,
    0x4040008040800020L, 0x440003000200801L, 0x4200011004500L, 0x188020010100100L,
    0x14800401802800L, 0x2080040080800200L, 0x124080204001001L, 0x200046502000484L,
    0x480400080088020L, 0x1000422010034000L, 0x30200100110040L, 0x100021010009L,
    0x2002080100110004L, 0x202008004008002L, 0x20020004010100L, 0x2048440040820001L,
    0x101002200408200L, 0x40802000401080L, 0x4008142004410100L, 0x2060820c0120200L,
    0x1001004080100L, 0x20c020080040080L, 0x2935610830022400L, 0x44440041009200L,
    0x280001040802101L, 0x2100190040002085L, 0x80c0084100102001L, 0x4024081001000421L,
    0x20030a0244872L, 0x12001008414402L, 0x2006104900a0804L, 0x1004081002402L
  };
  private static final long[] BISHOP_MAGIC = { /* 64 numbers */
    0x40040844404084L, 0x2004208a004208L, 0x10190041080202L, 0x108060845042010L,
    0x581104180800210L, 0x2112080446200010L, 0x1080820820060210L, 0x3c0808410220200L,
    0x4050404440404L, 0x21001420088L, 0x24d0080801082102L, 0x1020a0a020400L,
    0x40308200402L, 0x4011002100800L, 0x401484104104005L, 0x801010402020200L,
    0x400210c3880100L, 0x404022024108200L, 0x810018200204102L, 0x4002801a02003L,
    0x85040820080400L, 0x810102c808880400L, 0xe900410884800L, 0x8002020480840102L,
    0x220200865090201L, 0x2010100a02021202L, 0x152048408022401L, 0x20080002081110L,
    0x4001001021004000L, 0x800040400a011002L, 0xe4004081011002L, 0x1c004001012080L,
    0x8004200962a00220L, 0x8422100208500202L, 0x2000402200300c08L, 0x8646020080080080L,
    0x80020a0200100808L, 0x2010004880111000L, 0x623000a080011400L, 0x42008c0340209202L,
    0x209188240001000L, 0x400408a884001800L, 0x110400a6080400L, 0x1840060a44020800L,
    0x90080104000041L, 0x201011000808101L, 0x1a2208080504f080L, 0x8012020600211212L,
    0x500861011240000L, 0x180806108200800L, 0x4000020e01040044L, 0x300000261044000aL,
    0x802241102020002L, 0x20906061210001L, 0x5a84841004010310L, 0x4010801011c04L,
    0xa010109502200L, 0x4a02012000L, 0x500201010098b028L, 0x8040002811040900L,
    0x28000010020204L, 0x6000020202d0240L, 0x8918844842082200L, 0x4010011029020020L
  };

  /* shift = 64 – relevant_bits */
  private static final int[] ROOK_SHIFT = {
    52, 53, 53, 53, 53, 53, 53, 52, 53, 54, 54, 54, 54, 54, 54, 53,
    53, 54, 54, 54, 54, 54, 54, 53, 53, 54, 54, 54, 54, 54, 54, 53,
    53, 54, 54, 54, 54, 54, 54, 53, 53, 54, 54, 54, 54, 54, 54, 53,
    53, 54, 54, 54, 54, 54, 54, 53, 53, 54, 54, 54, 54, 54, 54, 53,
    52, 53, 53, 53, 53, 53, 53, 52
  };
  private static final int[] BISHOP_SHIFT = {
    58, 59, 59, 59, 59, 59, 59, 58, 59, 59, 59, 59, 59, 59, 59, 59,
    59, 59, 57, 57, 57, 57, 59, 59, 59, 59, 57, 55, 55, 57, 59, 59,
    59, 59, 57, 55, 55, 57, 59, 59, 59, 59, 57, 57, 57, 57, 59, 59,
    59, 59, 59, 59, 59, 59, 59, 59, 58, 59, 59, 59, 59, 59, 59, 58
  };

  private static final long[] ROOK_MASK = new long[64];
  private static final long[] BISHOP_MASK = new long[64];
  private static final long[][] ROOK_ATTACK = new long[64][];
  private static final long[][] BISHOP_ATTACK = new long[64][];

  /* ═════════════ static initialisation ═══════════════════════════ */
  static {
    /* king / knight look-ups + directional rays */
    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, f = sq & 7;

      long k = 0, n = 0;
      for (int d = 0; d < 8; ++d) {
        int rr = r + DR[d], ff = f + DF[d];
        if (in(rr, ff)) k |= 1L << (rr * 8 + ff);
      }
      KING_ATK[sq] = k;

      int[] kr = {-2, -1, 1, 2, 2, 1, -1, -2};
      int[] kf = {1, 2, 2, 1, -1, -2, -2, -1};
      for (int i = 0; i < 8; ++i)
        if (in(r + kr[i], f + kf[i])) n |= 1L << ((r + kr[i]) * 8 + (f + kf[i]));
      KNIGHT_ATK[sq] = n;

      for (int d = 0; d < 8; ++d) {
        long ray = 0;
        int rr = r + DR[d], ff = f + DF[d];
        while (in(rr, ff)) {
          ray |= 1L << (rr * 8 + ff);
          rr += DR[d];
          ff += DF[d];
        }
        RAY_MASK[d][sq] = ray;
      }
    }

    /* LINE table */
    for (int a = 0; a < 64; ++a) {
      int ar = a >>> 3, af = a & 7;
      for (int b = 0; b < 64; ++b)
        if (a != b) {
          int br = b >>> 3, bf = b & 7;
          long mask = 0;
          if (ar == br) mask = RANK_1 << (ar * 8);
          else if (af == bf) mask = FILE_A << af;
          else if (ar - af == br - bf) mask = mainDiagMask(ar, af);
          else if (ar + af == br + bf) mask = antiDiagMask(ar, af);
          LINE[a][b] = mask;
        }
    }

    /* magic masks + attack tables */
    for (int sq = 0; sq < 64; ++sq) {
      ROOK_MASK[sq] = rookMask(sq);
      BISHOP_MASK[sq] = bishopMask(sq);
    }
    for (int sq = 0; sq < 64; ++sq) {
      buildTable(sq, true);
      buildTable(sq, false);
    }
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
      int firstSq = lsb(blockers);
      long first = 1L << firstSq;
      long sliders = d < 4 ? (eR | eQ) : (eB | eQ);

      if ((first & sliders) != 0) { // direct check
        checkers |= first;
        blockMask |= between(kSq, firstSq);
      } else if ((first & own) != 0) { // maybe a pin
        long behind = RAY_MASK[d][firstSq];
        if ((behind & sliders) != 0) pinMask |= first;
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

  /* ═══════ helpers / magic / geometry (unchanged) ═══════════════ */
  private static long rookMagic(long occ, int sq) {
    long idx = ((occ & ROOK_MASK[sq]) * ROOK_MAGIC[sq]) >>> ROOK_SHIFT[sq];
    return ROOK_ATTACK[sq][(int) idx];
  }

  private static long bishopMagic(long occ, int sq) {
    long idx = ((occ & BISHOP_MASK[sq]) * BISHOP_MAGIC[sq]) >>> BISHOP_SHIFT[sq];
    return BISHOP_ATTACK[sq][(int) idx];
  }

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
  private static int lsb(long b) {
    return Long.numberOfTrailingZeros(b);
  }

  private static long pop(long b) {
    return b & (b - 1);
  }

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

  private static long rookAttacks(int sq, long occ) {
    long atk = 0;
    int r = sq >>> 3, f = sq & 7;
    for (int d = 0; d < 4; ++d) {
      int rr = r + DR[d], ff = f + DF[d];
      while (in(rr, ff)) {
        int s = rr * 8 + ff;
        atk |= 1L << s;
        if (((occ >> s) & 1) != 0) break;
        rr += DR[d];
        ff += DF[d];
      }
    }
    return atk;
  }

  private static long bishopAttacks(int sq, long occ) {
    long atk = 0;
    int r = sq >>> 3, f = sq & 7;
    for (int d = 4; d < 8; ++d) {
      int rr = r + DR[d], ff = f + DF[d];
      while (in(rr, ff)) {
        int s = rr * 8 + ff;
        atk |= 1L << s;
        if (((occ >> s) & 1) != 0) break;
        rr += DR[d];
        ff += DF[d];
      }
    }
    return atk;
  }

  private static long rookMask(int sq) {
    long m = 0;
    int r = sq >>> 3, f = sq & 7;
    for (int ff = f + 1; ff <= 6; ++ff) m |= 1L << (r * 8 + ff);
    for (int ff = f - 1; ff >= 1; --ff) m |= 1L << (r * 8 + ff);
    for (int rr = r + 1; rr <= 6; ++rr) m |= 1L << (rr * 8 + f);
    for (int rr = r - 1; rr >= 1; --rr) m |= 1L << (rr * 8 + f);
    return m;
  }

  private static long bishopMask(int sq) {
    long m = 0;
    int r = sq >>> 3, f = sq & 7;
    for (int d = 4; d < 8; ++d) {
      int rr = r + DR[d], ff = f + DF[d];
      while (in(rr, ff) && rr >= 1 && rr <= 6 && ff >= 1 && ff <= 6) {
        m |= 1L << (rr * 8 + ff);
        rr += DR[d];
        ff += DF[d];
      }
    }
    return m;
  }
}
