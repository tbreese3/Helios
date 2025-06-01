package engine.internal.search.internal;

import static engine.internal.search.PackedPositionFactory.*;
import static engine.internal.search.internal.PrecomputedTables.*;

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

  /** True iff Long.compress (→ PEXT) is available *and* not disabled by property. */
  public static final boolean USE_PEXT = initUsePext();

  /* ── static initialisation ───────────────────────────────────── */
  static {

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
     *  BISHOPS / ROOKS / QUEENS
     * ================================================================== */
    long sliders = bb[usB] | bb[usR] | bb[usQ];
    while (sliders != 0L) {
      int from = Long.numberOfTrailingZeros(sliders);
      sliders &= sliders - 1;
      long bitFrom = 1L << from;

      int pieceMover = ((bitFrom & bb[usB]) != 0) ? usB : ((bitFrom & bb[usR]) != 0) ? usR : usQ;

      long tgt =
          (pieceMover == usB)
              ? bishopAtt(occ, from)
              : (pieceMover == usR) ? rookAtt(occ, from) : queenAtt(occ, from);

      tgt &= (captMask | quietMask);
      while (tgt != 0L) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        moves[n++] = mv(from, to, 0, pieceMover);
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
    int base = PrecomputedTables.ROOKOFFSET_PEXT[sq];
    long mask = PrecomputedTables.ROOKMASK_PEXT[sq];
    int idx = (int) Long.compress(occ, mask);
    return PrecomputedTables.SLIDER_PEXT[base + idx];
  }

  public static long bishopAttPext(int sq, long occ) {
    int base = PrecomputedTables.BISHOPOFFSET_PEXT[sq];
    long mask = PrecomputedTables.BISHOPMASK_PEXT[sq];
    int idx = (int) Long.compress(occ, mask);
    return PrecomputedTables.SLIDER_PEXT[base + idx];
  }

  public static long queenAttPext(int sq, long occ) {
    /* unchanged offsets, just call the fixed helpers */
    return rookAttPext(sq, occ) | bishopAttPext(sq, occ);
  }

  private static int mv(int from, int to, int flags, int mover) {
    return (from << 6) | to | (flags << 14) | (mover << MOVER_SHIFT);
  }

  private static boolean initUsePext() {
    /* 1) platform screen ─ JDK 21+ on x86-64 only ──────────────────── */
    if (Runtime.version().feature() < 21) return false;

    String archRaw = System.getProperty("os.arch");
    String arch = (archRaw == null) ? "" : archRaw.toLowerCase(Locale.ROOT);

    // Accept all typical spellings: “x86_64”, “amd64”, “x64”, “x86-64”
    boolean isX86_64 =
        arch.equals("x86_64")
            || arch.equals("amd64")
            || arch.equals("x64")
            || arch.equals("x86-64");

    if (!isX86_64) return false;

    /* 2) was BMI2 explicitly disabled? ─────────────────────────────── */
    try {
      // 2a) command-line flags
      var rtArgs = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
      for (String a : rtArgs) if (a.equals("-XX:-UseBMI2Instructions")) return false;

      // 2b) live VM option (HotSpot only, optional module)
      Class<?> raw =
          Class.forName(
              "com.sun.management.HotSpotDiagnosticMXBean",
              false,
              ClassLoader.getSystemClassLoader());

      var bean =
          java.lang.management.ManagementFactory.getPlatformMXBean(
              raw.asSubclass(java.lang.management.PlatformManagedObject.class));

      if (bean != null) {
        Object opt = raw.getMethod("getVMOption", String.class).invoke(bean, "UseBMI2Instructions");
        String val = (String) opt.getClass().getMethod("getValue").invoke(opt);
        if (!Boolean.parseBoolean(val)) // BMI2 turned off
        return false;
      }
    } catch (Throwable ignored) {
      /* Non-HotSpot VM or jdk.management absent → fall through        */
    }

    /* All checks passed → enable PEXT path */
    return true;
  }
}
