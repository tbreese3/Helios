package core.impl;

import static core.impl.PreCompMoveGenTables.*;

import core.contracts.*;

public final class PositionFactoryImpl implements PositionFactory {

  /* piece indices (mirror interface) */
  private static final int WP = 0, WN = 1, WB = 2, WR = 3, WQ = 4, WK = 5,
          BP = 6, BN = 7, BB = 8, BR = 9, BQ = 10, BK = 11, META = 12;

  /* META layout (unchanged) */
  private static final long STM_MASK = 1L;
  private static final int  CR_SHIFT = 1;     private static final long CR_MASK = 0b1111L << CR_SHIFT;
  private static final int  EP_SHIFT = 5;     private static final long EP_MASK = 0x3FL  << EP_SHIFT;
  private static final int  HC_SHIFT = 11;    private static final long HC_MASK = 0x7FL  << HC_SHIFT;
  private static final int  FM_SHIFT = 18;    private static final long FM_MASK = 0x1FFL << FM_SHIFT;

  private static final short[] CR_MASK_LOST_FROM = new short[64];
  private static final short[] CR_MASK_LOST_TO   = new short[64];

  private static final int CR_BITS = (int) CR_MASK;
  private static final int EP_BITS = (int) EP_MASK;
  private static final int HC_BITS = (int) HC_MASK;

  /* ===== Reference Zobrist tables ===== */
  private static final long Z_TEMP;
  private static final long[][] Z_PSQ = new long[12][64];
  private static final long[]   Z_EP_FILE = new long[8];
  private static final long[]   Z_CASTLING = new long[16];
  private static final long[] Z_50 = new long[120];

  // legacy fields kept so other classes compile; not used for hashing
  @Deprecated public  static final long[]   CASTLING  = new long[16];
  @Deprecated public  static final long[]   EP_FILE   = new long[8];
  @Deprecated public  static final long     SIDE_TO_MOVE = 0L;

  /* cookie stack & misc are part of the shared API via PositionFactory */
  private static final class Random {
    private static final int NN = 312;
    private static final int MM = 156;
    private static final long MATRIX_A = 0xB5026F5AA96619E9L;
    private static final long UM = 0xFFFFFFFF80000000L; /* Most significant 33 bits */
    private static final long LM = 0x7FFFFFFFL;         /* Least significant 31 bits */

    private final long[] mt = new long[NN];
    private int mti = NN + 1;

    Random(long seed) { seed(seed); }

    void seed(long seed) {
      mt[0] = seed;
      for (mti = 1; mti < NN; mti++)
        mt[mti] = 6364136223846793005L * (mt[mti-1] ^ (mt[mti-1] >>> 62)) + mti;
    }

    long nextLong() {
      long x;
      int i;

      if (mti >= NN) { /* generate NN words at one time */
        for (i = 0; i < NN - MM; i++) {
          x = (mt[i] & UM) | (mt[i + 1] & LM);
          mt[i] = mt[i + MM] ^ (x >>> 1) ^ ((x & 1L) != 0 ? MATRIX_A : 0L);
        }
        for (; i < NN - 1; i++) {
          x = (mt[i] & UM) | (mt[i + 1] & LM);
          mt[i] = mt[i + (MM - NN)] ^ (x >>> 1) ^ ((x & 1L) != 0 ? MATRIX_A : 0L);
        }
        x = (mt[NN - 1] & UM) | (mt[0] & LM);
        mt[NN - 1] = mt[MM - 1] ^ (x >>> 1) ^ ((x & 1L) != 0 ? MATRIX_A : 0L);
        mti = 0;
      }

      x = mt[mti++];

      /* Tempering */
      x ^= (x >>> 29) & 0x5555555555555555L;
      x ^= (x << 17)  & 0x71D67FFFEDA60000L;
      x ^= (x << 37)  & 0xFFF7EEE000000000L;
      x ^= (x >>> 43);
      return x;
    }
  }

  static {
    java.util.Arrays.fill(CR_MASK_LOST_FROM, (short)0b1111);
    java.util.Arrays.fill(CR_MASK_LOST_TO,   (short)0b1111);

    CR_MASK_LOST_FROM[ 4]  = 0b1100; // e1  white king
    CR_MASK_LOST_FROM[60]  = 0b0011; // e8  black king
    CR_MASK_LOST_FROM[ 7] &= ~0b0001; // h1 -> clear white-K
    CR_MASK_LOST_FROM[ 0] &= ~0b0010; // a1 -> clear white-Q
    CR_MASK_LOST_FROM[63] &= ~0b0100; // h8 -> clear black-k
    CR_MASK_LOST_FROM[56] &= ~0b1000; // a8 -> clear black-q
    CR_MASK_LOST_TO[ 7]  &= ~0b0001;
    CR_MASK_LOST_TO[ 0]  &= ~0b0010;
    CR_MASK_LOST_TO[63]  &= ~0b0100;
    CR_MASK_LOST_TO[56]  &= ~0b1000;

    Random mt = new Random(48932L);

    Z_TEMP = mt.nextLong();

    for (int pc = 0; pc < 12; ++pc)
      for (int sq = 0; sq < 64; ++sq)
        Z_PSQ[pc][sq] = mt.nextLong();

    for (int f = 0; f < 8; ++f) Z_EP_FILE[f] = mt.nextLong();

    // Single-right castling keys
    final int WHITE_OO  = 0b0001;
    final int WHITE_OOO = 0b0010;
    final int BLACK_OO  = 0b0100;
    final int BLACK_OOO = 0b1000;

    Z_CASTLING[0] = 0L;
    Z_CASTLING[WHITE_OO]  = mt.nextLong();
    Z_CASTLING[WHITE_OOO] = mt.nextLong();
    Z_CASTLING[BLACK_OO]  = mt.nextLong();
    Z_CASTLING[BLACK_OOO] = mt.nextLong();

    for (int i = 1; i < 16; i++) {
      if (Integer.bitCount(i) < 2) continue;
      long delta = 0L;
      if ((i & WHITE_OO)  != 0) delta ^= Z_CASTLING[WHITE_OO];
      if ((i & WHITE_OOO) != 0) delta ^= Z_CASTLING[WHITE_OOO];
      if ((i & BLACK_OO)  != 0) delta ^= Z_CASTLING[BLACK_OO];
      if ((i & BLACK_OOO) != 0) delta ^= Z_CASTLING[BLACK_OOO];
      Z_CASTLING[i] = delta;
    }

    // 50-move rule block keys
    java.util.Arrays.fill(Z_50, 0L);
    for (int i = 14; i <= 100; i += 8) {
      long key = mt.nextLong();
      for (int j = 0; j < 8; ++j) Z_50[i + j] = key;
    }
  }

  @Override
  public long zobrist50(long[] bb) {
    long key = bb[HASH];
    int hc   = (int)((bb[META] & HC_MASK) >>> HC_SHIFT); // 0..119
    return key ^ Z_50[hc];
  }

  @Override
  public long[] fromFen(String fen) {
    long[] bb = fenToBitboards(fen);
    bb[COOKIE_SP] = 0;
    bb[DIFF_META] = bb[META];
    bb[DIFF_INFO] = 0;
    bb[HASH] = fullHash(bb); // reference-compatible
    return bb;
  }

  @Override
  public String toFen(long[] bb) {
    StringBuilder sb = new StringBuilder(64);
    for (int rank = 7; rank >= 0; --rank) {
      int empty = 0;
      for (int file = 0; file < 8; ++file) {
        int sq = rank * 8 + file;
        char pc = pieceCharAt(bb, sq);
        if (pc == 0) { empty++; continue; }
        if (empty != 0) { sb.append(empty); empty = 0; }
        sb.append(pc);
      }
      if (empty != 0) sb.append(empty);
      if (rank != 0) sb.append('/');
    }
    sb.append(whiteToMove(bb) ? " w " : " b ");

    int cr = castlingRights(bb);
    sb.append(cr == 0 ? "-" : "")
            .append((cr & 1) != 0 ? "K" : "")
            .append((cr & 2) != 0 ? "Q" : "")
            .append((cr & 4) != 0 ? "k" : "")
            .append((cr & 8) != 0 ? "q" : "");
    sb.append(' ');

    int ep = enPassantSquare(bb);
    if (ep != -1) sb.append((char)('a' + (ep & 7))).append(1 + (ep >>> 3));
    else          sb.append('-');

    sb.append(' ').append(halfmoveClock(bb)).append(' ').append(fullmoveNumber(bb));
    return sb.toString();
  }

  @Override
  public long zobrist(long[] bb) { return bb[HASH]; }

  private char pieceCharAt(long bb[], int sq) {
    for (int i = 0; i < 12; ++i)
      if ((bb[i] & (1L << sq)) != 0) return "PNBRQKpnbrqk".charAt(i);
    return 0;
  }

  private static boolean whiteToMove(long[] bb) { return (bb[META] & STM_MASK) == 0; }
  private int halfmoveClock(long[] bb) { return (int)((bb[META] & HC_MASK) >>> HC_SHIFT); }
  private int fullmoveNumber(long[] bb) { return 1 + (int)((bb[META] & FM_MASK) >>> FM_SHIFT); }
  private int castlingRights(long[] bb) { return (int)((bb[META] & CR_MASK) >>> CR_SHIFT); }
  private int enPassantSquare(long[] bb) { int e=(int)((bb[META] & EP_MASK) >>> EP_SHIFT); return e==EP_NONE?-1:e; }

  /* ===== helpers to map CR bitmask -> unchanged (kept for compatibility) ===== */
  private static int whiteCrOrdinal(int crMask) {
    int k = ((crMask & 0b0001) != 0) ? 1 : 0;
    int q = ((crMask & 0b0010) != 0) ? 2 : 0;
    return k | q; // NONE=0, K=1, Q=2, KQ=3
  }
  private static int blackCrOrdinal(int crMask) {
    int k = ((crMask & 0b0100) != 0) ? 1 : 0;
    int q = ((crMask & 0b1000) != 0) ? 2 : 0;
    return k | q;
  }

  @Override
  public boolean makeMoveInPlace(long[] bb, int mv, MoveGenerator gen) {
    int from  = (mv >>>  6) & 0x3F;
    int to    =  mv         & 0x3F;
    int type  = (mv >>> 14) & 0x3;
    int promo = (mv >>> 12) & 0x3;
    int mover = (mv >>> 16) & 0xF;

    boolean white   = mover < 6;
    long    fromBit = 1L << from;
    long    toBit   = 1L << to;

    if (type == 3 && !gen.castleLegal(bb, from, to)) return false;

    long h        = bb[HASH];
    long oldHash  = h;
    int  metaOld  = (int) bb[META];
    int  oldCR    = (metaOld & CR_BITS) >>> CR_SHIFT;
    int  oldEP    = (metaOld & EP_BITS) >>> EP_SHIFT;

    // Remove EP FILE key from previous state (if any)
    if (oldEP != (int)EP_NONE) h ^= Z_EP_FILE[oldEP & 7];

    int sp = (int) bb[COOKIE_SP];
    bb[COOKIE_BASE + sp] = (bb[DIFF_META] & 0xFFFF_FFFFL) << 32 | (bb[DIFF_INFO] & 0xFFFF_FFFFL);
    bb[COOKIE_SP] = sp + 1;

    int captured = 15;

    if (type <= 1) {
      long enemy = white
              ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
              : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]);
      if ((enemy & toBit) != 0) {
        captured = (bb[white?BP:WP] & toBit)!=0 ? (white?BP:WP) :
                (bb[white?BN:WN] & toBit)!=0 ? (white?BN:WN) :
                        (bb[white?BB:WB] & toBit)!=0 ? (white?BB:WB) :
                                (bb[white?BR:WR] & toBit)!=0 ? (white?BR:WR) :
                                        (bb[white?BQ:WQ] & toBit)!=0 ? (white?BQ:WQ) :
                                                (white?BK:WK);
        bb[captured] &= ~toBit;
        h ^= Z_PSQ[captured][to];
      }
    } else if (type == 2) { // en-passant capture
      int capSq   = white ? to - 8 : to + 8;
      captured    = white ? BP : WP;
      bb[captured] &= ~(1L << capSq);
      h ^= Z_PSQ[captured][capSq];
    }

    // move the mover
    bb[mover] ^= fromBit;
    h ^= Z_PSQ[mover][from];

    if (type == 1) { // promotion
      int promIdx = (white ? WN : BN) + promo;
      bb[promIdx] |= toBit;
      h ^= Z_PSQ[promIdx][to];
    } else {
      bb[mover]   |= toBit;
      h ^= Z_PSQ[mover][to];
    }

    // castle rook moves (hash via PSQ too)
    if (type == 3) switch (to) {
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5);  h ^= Z_PSQ[WR][7] ^ Z_PSQ[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3);  h ^= Z_PSQ[WR][0] ^ Z_PSQ[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61); h ^= Z_PSQ[BR][63]^ Z_PSQ[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59); h ^= Z_PSQ[BR][56]^ Z_PSQ[BR][59];}
    }

    int meta = metaOld;

    // New EP square (by rule — not by legality/pins). Only set if opponent pawn could capture midpoint.
    int ep = (int) EP_NONE;
    if ((mover == WP || mover == BP) && ((from ^ to) == 16)) {
      int mid = white ? from + 8 : from - 8;
      if (white) {
        if ((bb[BP] & PAWN_ATK_W[mid]) != 0) ep = mid;
      } else {
        if ((bb[WP] & PAWN_ATK_B[mid]) != 0) ep = mid;
      }
    }

    // Update EP bits in META
    if (ep != oldEP) meta = (meta & ~EP_BITS) | (ep << EP_SHIFT);

    // Castling rights update + delta hash
    int newCR = ((meta & CR_BITS) >>> CR_SHIFT) & CR_MASK_LOST_FROM[from] & CR_MASK_LOST_TO[to];
    if (newCR != oldCR) {
      h ^= Z_CASTLING[oldCR ^ newCR];
      meta = (meta & ~CR_BITS) | (newCR << CR_SHIFT);
    }

    // Halfmove clock
    int newHC = ((mover == WP || mover == BP) || captured != 15) ? 0 : ((metaOld & HC_BITS) >>> HC_SHIFT) + 1;
    meta = (meta & ~HC_BITS) | (newHC << HC_SHIFT);

    // Fullmove & side flip (tempo key toggled)
    int fm = (meta >>> FM_SHIFT) & 0x1FF;
    if (!white) fm++;
    meta ^= STM_MASK; // flip side
    h ^= Z_TEMP;     // toggle tempo key

    // Add EP FILE key for new state (if any)
    if (ep != (int)EP_NONE) h ^= Z_EP_FILE[ep & 7];

    meta = (meta & ~((int)FM_MASK)) | (fm << FM_SHIFT);

    bb[DIFF_INFO] = (int) packDiff(from, to, captured, mover, type, promo);
    bb[DIFF_META] = (int) (bb[META] ^ meta);
    bb[META]      = meta;
    bb[HASH]      = h;

    if (gen.kingAttacked(bb, white)) {
      bb[HASH] = oldHash;
      fastUndo(bb);
      bb[COOKIE_SP] = sp;
      long prev = bb[COOKIE_BASE + sp];
      bb[DIFF_INFO] = (int)  prev;
      bb[DIFF_META] = (int) (prev >>> 32);
      return false;
    }
    return true;
  }

  @Override
  public void undoMoveInPlace(long[] bb) {
    long diff   = bb[DIFF_INFO];
    long metaΔ  = bb[DIFF_META];

    long h          = bb[HASH];
    int  metaAfter  = (int) bb[META];

    int epAfter = (metaAfter & EP_BITS) >>> EP_SHIFT;
    if (epAfter != (int)EP_NONE) h ^= Z_EP_FILE[epAfter & 7]; // remove EP of "after"

    // flip tempo back
    h ^= Z_TEMP;

    // toggle castle keys back using BEFORE/AFTER masks via delta
    int crAfter = (metaAfter & CR_BITS) >>> CR_SHIFT;
    bb[META] ^= metaΔ; // META becomes before
    int metaBefore = (int) bb[META];
    int crBefore   = (metaBefore & CR_BITS) >>> CR_SHIFT;
    if (crAfter != crBefore) h ^= Z_CASTLING[crAfter ^ crBefore];

    int from   = dfFrom(diff);
    int to     = dfTo(diff);
    int capIdx = dfCap(diff);
    int mover  = dfMover(diff);
    int type   = dfType(diff);
    int promo  = dfPromo(diff);

    long fromBit = 1L << from;
    long toBit   = 1L << to;

    if (type == 1) { // promotion
      int promIdx = (mover < 6 ? WN : BN) + promo;
      bb[promIdx] ^= toBit;
      bb[mover]   |= fromBit;
      h ^= Z_PSQ[promIdx][to] ^ Z_PSQ[mover][from];
    } else {
      bb[mover] ^= fromBit | toBit;
      h ^= Z_PSQ[mover][to] ^ Z_PSQ[mover][from];
    }

    if (type == 3) switch (to) {
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5);  h ^= Z_PSQ[WR][7] ^ Z_PSQ[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3);  h ^= Z_PSQ[WR][0] ^ Z_PSQ[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61); h ^= Z_PSQ[BR][63]^ Z_PSQ[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59); h ^= Z_PSQ[BR][56]^ Z_PSQ[BR][59];}
    }

    if (capIdx != 15) {
      int capSq = (type == 2) ? ((mover < 6) ? to - 8 : to + 8) : to;
      bb[capIdx] |= (1L << capSq);
      h ^= Z_PSQ[capIdx][capSq];
    }

    int sp = (int) bb[COOKIE_SP] - 1;
    long ck = bb[COOKIE_BASE + sp];
    bb[COOKIE_BASE + sp] = 0L;
    bb[COOKIE_SP] = sp;
    bb[DIFF_INFO] = (int)  ck;
    bb[DIFF_META] = (int) (ck >>> 32);

    // add EP FILE key for "before" if any
    int epBefore = (metaBefore & EP_BITS) >>> EP_SHIFT;
    if (epBefore != (int)EP_NONE) h ^= Z_EP_FILE[epBefore & 7];

    bb[HASH] = h;
  }

  public boolean hasNonPawnMaterial(long[] bb) {
    boolean whiteToMove = PositionFactory.whiteToMove(bb[META]);
    if (whiteToMove) {
      return (bb[PositionFactory.WN] | bb[PositionFactory.WB] | bb[PositionFactory.WR] | bb[PositionFactory.WQ]) != 0;
    } else {
      return (bb[PositionFactory.BN] | bb[PositionFactory.BB] | bb[PositionFactory.BR] | bb[PositionFactory.BQ]) != 0;
    }
  }

  private static void fastUndo(long[] bb) {
    long diff  = bb[DIFF_INFO];
    long metaΔ = bb[DIFF_META];
    bb[META]  ^= metaΔ;

    int from   = dfFrom(diff);
    int to     = dfTo(diff);
    int capIdx = dfCap(diff);
    int mover  = dfMover(diff);
    int type   = dfType(diff);
    int promo  = dfPromo(diff);

    long fromBit = 1L << from;
    long toBit   = 1L << to;

    if (type == 1) {
      bb[(mover < 6 ? WN : BN) + promo] ^= toBit;
      bb[(mover < 6) ? WP : BP]        |= fromBit;
    } else {
      bb[mover] ^= fromBit | toBit;
    }

    if (type == 3) switch (to) {
      case  6 -> bb[WR] ^= (1L<<7)  | (1L<<5);
      case  2 -> bb[WR] ^= (1L<<0)  | (1L<<3);
      case 62 -> bb[BR] ^= (1L<<63) | (1L<<61);
      case 58 -> bb[BR] ^= (1L<<56) | (1L<<59);
    }

    if (capIdx != 15) {
      long capMask = (type == 2) ? 1L << ((mover < 6) ? to - 8 : to + 8) : toBit;
      bb[capIdx] |= capMask;
    }
  }

  private static long[] fenToBitboards(String fen) {
    long[] bb = new long[BB_LEN];
    String[] parts = fen.trim().split("\\s+");
    String board = parts[0];
    int rank = 7, file = 0;
    for (char c : board.toCharArray()) {
      if (c == '/') { rank--; file = 0; continue; }
      if (Character.isDigit(c)) { file += c - '0'; continue; }
      int sq = rank * 8 + file++;
      int idx = switch (c) {
        case 'P' -> WP; case 'N' -> WN; case 'B' -> WB; case 'R' -> WR; case 'Q' -> WQ; case 'K' -> WK;
        case 'p' -> BP; case 'n' -> BN; case 'b' -> BB; case 'r' -> BR; case 'q' -> BQ; case 'k' -> BK;
        default -> throw new IllegalArgumentException("bad fen piece: " + c);
      };
      bb[idx] |= 1L << sq;
    }
    long meta = parts[1].equals("b") ? 1L : 0L; // STM: 0 = white, 1 = black
    int cr = 0;
    if (parts[2].indexOf('K') >= 0) cr |= 0b0001;
    if (parts[2].indexOf('Q') >= 0) cr |= 0b0010;
    if (parts[2].indexOf('k') >= 0) cr |= 0b0100;
    if (parts[2].indexOf('q') >= 0) cr |= 0b1000;
    meta |= (long) cr << CR_SHIFT;

    int epSq = (int) EP_NONE;
    if (!parts[3].equals("-")) {
      int f = parts[3].charAt(0) - 'a';
      int r = parts[3].charAt(1) - '1';
      epSq = r * 8 + f; // FEN EP destination square
    }
    // Drop EP if side-to-move has no pawn that could capture the EP square (ignoring pins)
    boolean stmWhite = (meta & STM_MASK) == 0;
    if (epSq != (int)EP_NONE) {
      boolean ok = stmWhite ? ((bb[WP] & PAWN_ATK_B[epSq]) != 0)
              : ((bb[BP] & PAWN_ATK_W[epSq]) != 0);
      if (!ok) epSq = (int)EP_NONE;
    }
    meta |= (long) epSq << EP_SHIFT;

    int hc = (parts.length > 4) ? Integer.parseInt(parts[4]) : 0;
    int fm = (parts.length > 5) ? Integer.parseInt(parts[5]) - 1 : 0;
    meta |= (long) hc << HC_SHIFT;
    meta |= (long) fm << FM_SHIFT;

    bb[META] = meta;
    return bb;
  }

  /** reference-compatible recomputation */
  public long fullHash(long[] bb) {
    long k = 0;

    for (int pc = WP; pc <= BK; ++pc) {
      long bits = bb[pc];
      while (bits != 0) {
        int sq = Long.numberOfTrailingZeros(bits);
        k ^= Z_PSQ[pc][sq];
        bits &= bits - 1;
      }
    }

    int crMask = (int)((bb[META] & CR_MASK) >>> CR_SHIFT);
    k ^= Z_CASTLING[crMask];

    int ep = (int)((bb[META] & EP_MASK) >>> EP_SHIFT);
    if (ep != (int)EP_NONE) k ^= Z_EP_FILE[ep & 7];

    if (whiteToMove(bb)) k ^= Z_TEMP;

    return k;
  }

  private static long packDiff(int from, int to, int cap, int mover, int typ, int pro) {
    return (from) | ((long)to << 6) | ((long)cap << 12) | ((long)mover << 16) | ((long)typ << 20) | ((long)pro << 22);
  }

  private static int dfFrom(long d){ return (int)(d & 0x3F); }
  private static int dfTo(long d){ return (int)((d >>> 6) & 0x3F); }
  private static int dfCap(long d){ return (int)((d >>> 12) & 0x0F); }
  private static int dfMover(long d){ return (int)((d >>> 16) & 0x0F); }
  private static int dfType(long d){ return (int)((d >>> 20) & 0x03); }
  private static int dfPromo(long d){ return (int)((d >>> 22) & 0x03); }
}
