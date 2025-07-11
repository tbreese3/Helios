package core.impl;

import static core.contracts.PositionFactory.*;
import static core.impl.MoveGeneratorImpl.FILE_A;
import static core.impl.MoveGeneratorImpl.FILE_H;

import core.contracts.*;
import java.util.Random;

public final class PositionFactoryImpl implements PositionFactory {
  /* piece indices (mirror interface) */
  private static final int WP = 0,
          WN = 1,
          WB = 2,
          WR = 3,
          WQ = 4,
          WK = 5,
          BP = 6,
          BN = 7,
          BB = 8,
          BR = 9,
          BQ = 10,
          BK = 11,
          META = 12;

  /* ───────── Zobrist tables ───────── */
  public static final long[][] PIECE_SQUARE = new long[12][64];
  public static final long[]   CASTLING     = new long[16];
  public static final long[]   EP_FILE      = new long[8]; // One for each file
  public static final long     SIDE_TO_MOVE;
  private static final long[] ZOBRIST_50MR = new long[120]; // 0‥119 half-moves

  /* META layout (duplicated locally for speed) */
  private static final long STM_MASK = 1L;
  private static final int CR_SHIFT = 1;
  private static final long CR_MASK = 0b1111L << CR_SHIFT;
  private static final int EP_SHIFT = 5;
  private static final long EP_MASK = 0x3FL << EP_SHIFT;
  private static final int HC_SHIFT = 11;
  private static final long HC_MASK = 0x7FL << HC_SHIFT;
  private static final int FM_SHIFT = 18;
  private static final long FM_MASK = 0x1FFL << FM_SHIFT;

  /* Precomputed masks for castling rights updates */
  private static final short[] CR_MASK_LOST_FROM = new short[64];
  private static final short[] CR_MASK_LOST_TO   = new short[64];
  private static final int CR_BITS = (int) CR_MASK;
  private static final int EP_BITS = (int) EP_MASK;
  private static final int HC_BITS = (int) HC_MASK;

  static {
    java.util.Arrays.fill(CR_MASK_LOST_FROM, (short) 0b1111);
    java.util.Arrays.fill(CR_MASK_LOST_TO,   (short) 0b1111);

    CR_MASK_LOST_FROM[ 4]  = 0b1100; // e1  white king
    CR_MASK_LOST_FROM[60]  = 0b0011; // e8  black king
    CR_MASK_LOST_FROM[ 7] &= ~0b0001; // h1  → clear white-K
    CR_MASK_LOST_FROM[ 0] &= ~0b0010; // a1  → clear white-Q
    CR_MASK_LOST_FROM[63] &= ~0b0100; // h8  → clear black-k
    CR_MASK_LOST_FROM[56] &= ~0b1000; // a8  → clear black-q
    CR_MASK_LOST_TO[ 7]  &= ~0b0001;
    CR_MASK_LOST_TO[ 0]  &= ~0b0010;
    CR_MASK_LOST_TO[63]  &= ~0b0100;
    CR_MASK_LOST_TO[56]  &= ~0b1000;

    Random rnd = new Random(0xCAFEBABE);
    for (int p = 0; p < 12; ++p)
      for (int sq = 0; sq < 64; ++sq)
        PIECE_SQUARE[p][sq] = rnd.nextLong();

    final int CR_W_K = 1, CR_W_Q = 2, CR_B_K = 4, CR_B_Q = 8;
    CASTLING[0] = 0L;
    CASTLING[CR_W_K] = rnd.nextLong();
    CASTLING[CR_W_Q] = rnd.nextLong();
    CASTLING[CR_B_K] = rnd.nextLong();
    CASTLING[CR_B_Q] = rnd.nextLong();

    for (int i = 1; i < 16; i++) {
      if (Integer.bitCount(i) < 2) continue;
      long combinedKey = 0L;
      if ((i & CR_W_K) != 0) combinedKey ^= CASTLING[CR_W_K];
      if ((i & CR_W_Q) != 0) combinedKey ^= CASTLING[CR_W_Q];
      if ((i & CR_B_K) != 0) combinedKey ^= CASTLING[CR_B_K];
      if ((i & CR_B_Q) != 0) combinedKey ^= CASTLING[CR_B_Q];
      CASTLING[i] = combinedKey;
    }

    for (int f = 0; f < 8; ++f) EP_FILE[f] = rnd.nextLong();
    SIDE_TO_MOVE = rnd.nextLong();
    for (int i = 0; i < ZOBRIST_50MR.length; ++i)
      ZOBRIST_50MR[i] = rnd.nextLong();
  }

  @Override
  public long zobrist50(long[] bb) {
    long key = bb[HASH];
    int hc   = (int) ((bb[META] & HC_MASK) >>> HC_SHIFT); // 0‥119, clamped by makeMove()
    return key ^ ZOBRIST_50MR[hc];
  }

  @Override
  public long[] fromFen(String fen) {
    long[] bb = fenToBitboards(fen);
    bb[COOKIE_SP] = 0;
    bb[DIFF_META] = bb[META];
    bb[DIFF_INFO] = 0;
    bb[HASH] = fullHash(bb);
    return bb;
  }

  @Override
  public String toFen(long[] bb)
  {
    StringBuilder sb = new StringBuilder(64);
    for (int rank = 7; rank >= 0; --rank) {
      int empty = 0;
      for (int file = 0; file < 8; ++file) {
        int sq = rank * 8 + file;
        char pc = pieceCharAt(bb, sq);
        if (pc == 0) {
          empty++;
          continue;
        }
        if (empty != 0) {
          sb.append(empty);
          empty = 0;
        }
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
    if (ep != -1) {
      sb.append((char) ('a' + (ep & 7))).append(1 + (ep >>> 3));
    } else {
      sb.append('-');
    }

    sb.append(' ');
    sb.append(halfmoveClock(bb)).append(' ').append(fullmoveNumber(bb));
    return sb.toString();
  }

  @Override
  public long zobrist(long[] bb)
  {
    return bb[HASH];
  }

  private char pieceCharAt(long bb[], int sq) {
    for (int i = 0; i < 12; ++i) if ((bb[i] & (1L << sq)) != 0) return "PNBRQKpnbrqk".charAt(i);
    return 0;
  }

  private boolean whiteToMove(long[] bb) {
    return (bb[META] & STM_MASK) == 0;
  }

  private int halfmoveClock(long[] bb) {
    return (int) ((bb[META] & HC_MASK) >>> HC_SHIFT);
  }

  private int fullmoveNumber(long[] bb) {
    return 1 + (int) ((bb[META] & FM_MASK) >>> FM_SHIFT);
  }

  private int castlingRights(long[] bb) {
    return (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
  }

  private int enPassantSquare(long[] bb) {
    int e = (int) ((bb[META] & EP_MASK) >>> EP_SHIFT);
    return e == EP_NONE ? -1 : e;
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

    if (type == 3 && !gen.castleLegal(bb, from, to))
      return false;

    long h        = bb[HASH];
    long oldHash  = h;
    int  metaOld  = (int) bb[META];
    int  oldCR    = (metaOld & CR_BITS) >>> CR_SHIFT;
    int  oldEP    = (metaOld & EP_BITS) >>> EP_SHIFT;

    int sp = (int) bb[COOKIE_SP];
    bb[COOKIE_BASE + sp] =
            (bb[DIFF_META] & 0xFFFF_FFFFL) << 32 |
                    (bb[DIFF_INFO] & 0xFFFF_FFFFL);
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
        h ^= PIECE_SQUARE[captured][to];
      }
    } else if (type == 2) {
      int capSq   = white ? to - 8 : to + 8;
      captured    = white ? BP : WP;
      bb[captured] &= ~(1L << capSq);
      h ^= PIECE_SQUARE[captured][capSq];
    }

    bb[mover] ^= fromBit;
    h ^= PIECE_SQUARE[mover][from];

    if (type == 1) {
      int promIdx = (white ? WN : BN) + promo;
      bb[promIdx] |= toBit;
      h ^= PIECE_SQUARE[promIdx][to];
    } else {
      bb[mover]   |= toBit;
      h ^= PIECE_SQUARE[mover][to];
    }

    if (type == 3) switch (to) {
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5); h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3); h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61);h ^= PIECE_SQUARE[BR][63] ^ PIECE_SQUARE[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59);h ^= PIECE_SQUARE[BR][56] ^ PIECE_SQUARE[BR][59];}
    }

    int meta = metaOld;
    int ep = (int) EP_NONE;
    if ((mover == WP || mover == BP) && ((from ^ to) == 16))
      ep = white ? from + 8 : from - 8;

    if (ep != oldEP) {
      meta = (meta & ~EP_BITS) | (ep << EP_SHIFT);
      if (oldEP != EP_NONE) h ^= EP_FILE[oldEP & 7];
      if (ep != EP_NONE)    h ^= EP_FILE[ep & 7];
    }

    int cr = oldCR & CR_MASK_LOST_FROM[from] & CR_MASK_LOST_TO[to];
    if (cr != oldCR) {
      meta = (meta & ~CR_BITS) | (cr << CR_SHIFT);
      h ^= CASTLING[oldCR] ^ CASTLING[cr];
    }

    int newHC = ((mover == WP || mover == BP) || captured != 15)
            ? 0
            : ((metaOld & HC_BITS) >>> HC_SHIFT) + 1;
    meta = (meta & ~HC_BITS) | (newHC << HC_SHIFT);

    int fm = (meta >>> FM_SHIFT) & 0x1FF;
    if (!white) fm++;
    meta ^= STM_MASK;
    meta = (meta & ~((int)FM_MASK)) | (fm << FM_SHIFT);
    h ^= SIDE_TO_MOVE;

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
    int  crAfter    = (metaAfter & CR_BITS) >>> CR_SHIFT;
    int  epAfter    = (metaAfter & EP_BITS) >>> EP_SHIFT;

    bb[META] ^= metaΔ;
    int metaBefore = (int) bb[META];
    int crBefore   = (metaBefore & CR_BITS) >>> CR_SHIFT;
    int epBefore   = (metaBefore & EP_BITS) >>> EP_SHIFT;

    h ^= SIDE_TO_MOVE;
    if (crAfter != crBefore) h ^= CASTLING[crAfter] ^ CASTLING[crBefore];

    int from   = dfFrom(diff);
    int to     = dfTo(diff);
    int capIdx = dfCap(diff);
    int mover  = dfMover(diff);
    int type   = dfType(diff);
    int promo  = dfPromo(diff);

    long fromBit = 1L << from;
    long toBit   = 1L << to;

    if (type == 1) {
      int promIdx = (mover < 6 ? WN : BN) + promo;
      bb[promIdx] ^= toBit;
      bb[mover]   |= fromBit;
      h ^= PIECE_SQUARE[promIdx][to] ^ PIECE_SQUARE[mover][from];
    } else {
      bb[mover] ^= fromBit | toBit;
      h ^= PIECE_SQUARE[mover][to] ^ PIECE_SQUARE[mover][from];
    }

    if (type == 3) switch (to) {
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5); h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3); h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61);h ^= PIECE_SQUARE[BR][63] ^ PIECE_SQUARE[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59);h ^= PIECE_SQUARE[BR][56] ^ PIECE_SQUARE[BR][59];}
    }

    if (capIdx != 15) {
      int capSq = (type == 2) ? ((mover < 6) ? to - 8 : to + 8) : to;
      bb[capIdx] |= 1L << capSq;
      h ^= PIECE_SQUARE[capIdx][capSq];
    }

    int sp = (int) bb[COOKIE_SP] - 1;
    long ck = bb[COOKIE_BASE + sp];
    bb[COOKIE_SP] = sp;
    bb[DIFF_INFO] = (int)  ck;
    bb[DIFF_META] = (int) (ck >>> 32);

    if (epAfter != epBefore) {
      if (epAfter != EP_NONE)  h ^= EP_FILE[epAfter & 7];
      if (epBefore != EP_NONE) h ^= EP_FILE[epBefore & 7];
    }

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
      if (c == '/') {
        rank--;
        file = 0;
        continue;
      }
      if (Character.isDigit(c)) {
        file += c - '0';
        continue;
      }
      int sq = rank * 8 + file++;
      int idx =
              switch (c) {
                case 'P' -> WP; case 'N' -> WN; case 'B' -> WB; case 'R' -> WR; case 'Q' -> WQ; case 'K' -> WK;
                case 'p' -> BP; case 'n' -> BN; case 'b' -> BB; case 'r' -> BR; case 'q' -> BQ; case 'k' -> BK;
                default -> throw new IllegalArgumentException("bad fen piece: " + c);
              };
      bb[idx] |= 1L << sq;
    }
    long meta = parts[1].equals("b") ? 1L : 0L;
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
      epSq = r * 8 + f;
    }
    meta |= (long) epSq << EP_SHIFT;

    int hc = (parts.length > 4) ? Integer.parseInt(parts[4]) : 0;
    int fm = (parts.length > 5) ? Integer.parseInt(parts[5]) - 1 : 0;
    meta |= (long) hc << HC_SHIFT;
    meta |= (long) fm << FM_SHIFT;

    bb[META] = meta;
    return bb;
  }

  public long fullHash(long[] bb) {
    long k = 0;
    for (int pc = WP; pc <= BK; ++pc) {
      long bits = bb[pc];
      while (bits != 0) {
        int sq = Long.numberOfTrailingZeros(bits);
        k ^= PIECE_SQUARE[pc][sq];
        bits &= bits - 1;
      }
    }

    if ((bb[META] & STM_MASK) != 0)
      k ^= SIDE_TO_MOVE;

    int cr = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
    k ^= CASTLING[cr];

    int ep = (int) ((bb[META] & EP_MASK) >>> EP_SHIFT);
    if (ep != EP_NONE)
      k ^= EP_FILE[ep & 7];

    return k;
  }

  private static long packDiff(int from, int to, int cap, int mover, int typ, int pro) {
    return (from) | ((long) to << 6) | ((long) cap << 12) | ((long) mover << 16) | ((long) typ << 20) | ((long) pro << 22);
  }

  private static int dfFrom(long d) { return (int) (d & 0x3F); }
  private static int dfTo(long d) { return (int) ((d >>> 6) & 0x3F); }
  private static int dfCap(long d) { return (int) ((d >>> 12) & 0x0F); }
  private static int dfMover(long d) { return (int) ((d >>> 16) & 0x0F); }
  private static int dfType(long d) { return (int) ((d >>> 20) & 0x03); }
  private static int dfPromo(long d) { return (int) ((d >>> 22) & 0x03); }
}