package core.impl;

import static core.impl.MoveGeneratorImpl.FILE_A;
import static core.impl.MoveGeneratorImpl.FILE_H;
import static core.contracts.PositionFactory.*;

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

  /* ── indices for the in-board cookie stack ───────────────────────── */
  /* ────────── slot indices ────────── */
  private static final int HASH        = 15;   // 64‑bit Zobrist key
  private static final int COOKIE_SP   = 16;   // stack pointer
  private static final int COOKIE_CAP  = 128;
  private static final int COOKIE_BASE = COOKIE_SP + 1;
  private static final int BB_LEN      = COOKIE_BASE + COOKIE_CAP;


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

  private static final short[] CR_MASK_LOST_FROM = new short[64];
  private static final short[] CR_MASK_LOST_TO   = new short[64];
  /* ───────── Zobrist tables ───────── */
  public static final long[][] PIECE_SQUARE = new long[12][64];
  public static final long[]   CASTLING     = new long[16];
  public static final long[]   EP_FILE      = new long[9];
  public static final long     SIDE_TO_MOVE;
  private static final int CR_BITS = (int) CR_MASK;
  private static final int EP_BITS = (int) EP_MASK;
  private static final int HC_BITS = (int) HC_MASK;

  static {
    java.util.Arrays.fill(CR_MASK_LOST_FROM, (short) 0b1111);
    java.util.Arrays.fill(CR_MASK_LOST_TO,   (short) 0b1111);

    /* king moves, lose both rights of that side */
    CR_MASK_LOST_FROM[ 4]  = 0b1100;   // e1  white king
    CR_MASK_LOST_FROM[60]  = 0b0011;   // e8  black king

    /* rook moves --------------------------------------------------- */
    CR_MASK_LOST_FROM[ 7] &= ~0b0001;  // h1  → clear white-K
    CR_MASK_LOST_FROM[ 0] &= ~0b0010;  // a1  → clear white-Q
    CR_MASK_LOST_FROM[63] &= ~0b0100;  // h8  → clear black-k
    CR_MASK_LOST_FROM[56] &= ~0b1000;  // a8  → clear black-q

    /* rook captured ------------------------------------------------- */
    CR_MASK_LOST_TO[ 7]  &= ~0b0001;
    CR_MASK_LOST_TO[ 0]  &= ~0b0010;
    CR_MASK_LOST_TO[63]  &= ~0b0100;
    CR_MASK_LOST_TO[56]  &= ~0b1000;

    Random rnd = new Random(0xCAFEBABE);
    for (int p = 0; p < 12; ++p)
      for (int sq = 0; sq < 64; ++sq)
        PIECE_SQUARE[p][sq] = rnd.nextLong();
    for (int i = 0; i < 16; ++i) CASTLING[i] = rnd.nextLong();
    for (int i = 0; i < 9;  ++i) EP_FILE[i]  = rnd.nextLong();
    SIDE_TO_MOVE = rnd.nextLong();
  }

  @Override
  public long[] fromFen(String fen) {
    long[] bb = fenToBitboards(fen);
    bb[COOKIE_SP] = 0; bb[DIFF_META] = bb[META]; bb[DIFF_INFO] = 0;
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
    sb.append(
            cr == 0
                    ? "-"
                    : ""
                    + ((cr & 1) != 0 ? 'K' : "")
                    + ((cr & 2) != 0 ? 'Q' : "")
                    + ((cr & 4) != 0 ? 'k' : "")
                    + ((cr & 8) != 0 ? 'q' : ""));

    sb.append(' ');
    int ep = enPassantSquare(bb);
    if (ep != -1 && epSquareIsCapturable(bb, ep)) {
      sb.append((char) ('a' + (ep & 7))).append(1 + (ep >>> 3));
    } else {
      sb.append('-');            // show “-” when not legally usable
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

  /* ═══════════ fast make / undo ═══════════ */
  @Override
  public boolean makeMoveInPlace(long[] bb, int mv, MoveGenerator gen) {

    /* ── 1) unpack -------------------------------------------------- */
    int from  = (mv >>>  6) & 0x3F;
    int to    =  mv         & 0x3F;
    int type  = (mv >>> 14) & 0x3;        // 0 norm | 1 promo | 2 EP | 3 castle
    int promo = (mv >>> 12) & 0x3;
    int mover = (mv >>> 16) & 0xF;        // 0-11

    boolean white   = mover < 6;
    long    fromBit = 1L << from,
            toBit   = 1L << to;

    if (type == 3 && !gen.castleLegal(bb, from, to)) return false;

    /* ── 1a) Zobrist prep ----------------------------------------- */
    long oldHash = bb[HASH], h = oldHash;

    int metaOld = (int) bb[META];
    int oldCR   = (metaOld & CR_BITS) >>> CR_SHIFT;
    int oldEP   = (metaOld & EP_BITS) >>> EP_SHIFT;

    /* ── 2) push cookie (1 write) ---------------------------------- */
    int sp = (int) bb[COOKIE_SP];
    bb[COOKIE_BASE + sp] =
            (bb[DIFF_META] & 0xFFFF_FFFFL) << 32 |
                    (bb[DIFF_INFO] & 0xFFFF_FFFFL);
    bb[COOKIE_SP] = sp + 1;

    /* ── 3) capture removal  + hash -------------------------------- */
    int captured = 15;                    // 15 = EMPTY
    if (type <= 1) {                      // normal / promo
      long enemy = white ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
              : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]);
      if ((enemy & toBit) != 0) {
        captured =
                (bb[white?BP:WP] & toBit) != 0 ? (white?BP:WP) :
                        (bb[white?BN:WN] & toBit) != 0 ? (white?BN:WN) :
                                (bb[white?BB:WB] & toBit) != 0 ? (white?BB:WB) :
                                        (bb[white?BR:WR] & toBit) != 0 ? (white?BR:WR) :
                                                (bb[white?BQ:WQ] & toBit) != 0 ? (white?BQ:WQ) :
                                                        (white?BK:WK);
        if (captured == (white ? BK : WK)) {        // self-mate
          bb[COOKIE_SP] = sp;
          return false;
        }
        bb[captured] &= ~toBit;
        h ^= PIECE_SQUARE[captured][to];
      }
    } else if (type == 2) {              // en-passant
      int capSq = white ? to - 8 : to + 8;
      captured  = white ? BP : WP;
      bb[captured] &= ~(1L << capSq);
      h ^= PIECE_SQUARE[captured][capSq];
    }

    /* ── 4) move / promote piece ----------------------------------- */
    bb[mover] ^= fromBit;                // clear source
    h ^= PIECE_SQUARE[mover][from];

    if (type == 1) {                      // promotion
      int promIdx = (white ? WN : BN) + promo;
      bb[promIdx] |= toBit;
      h ^= PIECE_SQUARE[promIdx][to];
    } else {
      bb[mover]   |= toBit;
      h ^= PIECE_SQUARE[mover][to];
    }

    /* castle rook shuffle (+ hash) */
    if (type == 3) switch (to) {
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5);
        h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3);
        h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61);
        h ^= PIECE_SQUARE[BR][63] ^ PIECE_SQUARE[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59);
        h ^= PIECE_SQUARE[BR][56] ^ PIECE_SQUARE[BR][59];}
    }

    /* ── 5) incremental META update ------------------------------- */
    int meta = metaOld;

    /* 5a) EN-PASSANT */
    int ep = (int)EP_NONE;
    if ((mover == WP || mover == BP) && ((from ^ to) == 16))
      ep = white ? from + 8 : from - 8;
    if (ep != oldEP) {
      meta = (meta & ~EP_BITS) | (ep << EP_SHIFT);
      /* Zobrist */
      if (oldEP != EP_NONE) h ^= EP_FILE[oldEP & 7];
      if (ep    != EP_NONE) h ^= EP_FILE[ep    & 7];
    }

    /* 5b) CASTLING rights */
    int cr = oldCR & CR_MASK_LOST_FROM[from] & CR_MASK_LOST_TO[to];
    if (cr != oldCR) {
      meta = (meta & ~CR_BITS) | (cr << CR_SHIFT);
      h ^= CASTLING[oldCR] ^ CASTLING[cr];
    }

    /* 5c) HALF-MOVE clock (7-bit sat) */
    if ((mover == WP || mover == BP) || captured != 15) {
      meta &= ~HC_BITS;                       // reset to 0
    } else {
      int hcBits = (meta & HC_BITS);
      if (hcBits != HC_BITS) meta += 1 << HC_SHIFT;  // +1 up to 127
    }

    /* 5d) FULL-MOVE & side-to-move (unchanged logic) */
    int fm = (int)((meta & FM_MASK) >>> FM_SHIFT);
    fm += white ? 0 : 1;           // increment on black’s move
    fm |= fm >> 9;                 // saturate 511
    fm &= 0x1FF;
    meta ^= STM_MASK;              // flip STM
    meta = (meta & ~(int)FM_MASK) | (fm << FM_SHIFT);
    h ^= SIDE_TO_MOVE;             // Zobrist side toggle

    /* ── 6) commit DIFF / META / HASH ----------------------------- */
    bb[DIFF_INFO] = (int) packDiff(from, to, captured, mover, type, promo);
    bb[DIFF_META] = (int) (bb[META] ^ meta);
    bb[META]      = meta;
    bb[HASH]      = h;

    /* ── 7) legality --------------------------------------------- */
    if (gen.kingAttacked(bb, white)) {        // illegal ⇒ rollback
      bb[HASH] = oldHash;
      fastUndo(bb);
      bb[COOKIE_SP] = sp;
      long prev = bb[COOKIE_BASE + sp];
      bb[DIFF_INFO] = (int) prev;
      bb[DIFF_META] = (int)(prev >>> 32);
      return false;
    }
    return true;
  }

  @Override
  public void undoMoveInPlace(long[] bb) {

    long diff  = bb[DIFF_INFO];
    long metaΔ = bb[DIFF_META];

    /* ── hash pre-work: gather META before & after ───────────── */
    long h          = bb[HASH];
    int metaAfter   = (int) bb[META];          // state *after* the move
    int crAfter     = (metaAfter & (int)CR_MASK) >>> CR_SHIFT;
    int epAfter     = (metaAfter & (int)EP_MASK) >>> EP_SHIFT;

    bb[META] ^= metaΔ;                         // restore META
    int metaBefore  = (int) bb[META];          // state *before* the move
    int crBefore    = (metaBefore & (int)CR_MASK) >>> CR_SHIFT;
    int epBefore    = (metaBefore & (int)EP_MASK) >>> EP_SHIFT;

    h ^= SIDE_TO_MOVE;                         // side toggled back
    if (crAfter != crBefore)
      h ^= CASTLING[crAfter] ^ CASTLING[crBefore];
    if (epAfter != epBefore) {
      if (epAfter  != EP_NONE) h ^= EP_FILE[epAfter  & 7];
      if (epBefore != EP_NONE) h ^= EP_FILE[epBefore & 7];
    }

    /* ── unpack DIFF for board / hash operations ─────────────── */
    int from   = dfFrom(diff);
    int to     = dfTo(diff);
    int capIdx = dfCap(diff);       // 0-11 or 15
    int mover  = dfMover(diff);
    int type   = dfType(diff);      // 0 nrm | 1 promo | 2 EP | 3 castle
    int promo  = dfPromo(diff);

    long fromBit = 1L << from,
            toBit   = 1L << to;

    /* 1) revert mover (handles promo) -------------------------- */
    if (type == 1) {                            // promotion
      int promIdx = (mover < 6 ? WN : BN) + promo;
      bb[promIdx] ^= toBit;
      bb[mover]   |= fromBit;
      h ^= PIECE_SQUARE[promIdx][to] ^ PIECE_SQUARE[mover][from];
    } else {
      bb[mover] ^= fromBit | toBit;
      h ^= PIECE_SQUARE[mover][to] ^ PIECE_SQUARE[mover][from];
    }

    /* 2) castle rook shuffle (if any) -------------------------- */
    if (type == 3) switch (to) {
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5);
        h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3);
        h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61);
        h ^= PIECE_SQUARE[BR][63] ^ PIECE_SQUARE[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59);
        h ^= PIECE_SQUARE[BR][56] ^ PIECE_SQUARE[BR][59];}
    }

    /* 3) restore captured piece -------------------------------- */
    if (capIdx != 15) {
      int capSq = (type == 2)
              ? ((mover < 6) ? to - 8 : to + 8)
              : to;
      bb[capIdx] |= 1L << capSq;
      h ^= PIECE_SQUARE[capIdx][capSq];         // add back captured
    }

    /* 4) pop cookie & DIFF fields ------------------------------ */
    int sp = (int) bb[COOKIE_SP] - 1;
    long ck = bb[COOKIE_BASE + sp];
    bb[COOKIE_SP] = sp;
    bb[DIFF_INFO] = (int) ck;
    bb[DIFF_META] = (int)(ck >>> 32);

    /* 5) write final hash -------------------------------------- */
    bb[HASH] = h;
  }

  private static void fastUndo(long[] bb) {

    long diff  = bb[DIFF_INFO];
    long metaΔ = bb[DIFF_META];
    bb[META]  ^= metaΔ;                        // restore META first

    int from   = dfFrom(diff);
    int to     = dfTo(diff);
    int capIdx = dfCap(diff);                 // 0-11 or 15
    int mover  = dfMover(diff);
    int type   = dfType(diff);                // 0 norm | 1 promo | 2 EP | 3 castle
    int promo  = dfPromo(diff);

    long fromBit = 1L << from;
    long toBit   = 1L << to;

    /* 1) revert mover (handles promotion) */
    if (type == 1) {
      bb[(mover < 6 ? WN : BN) + promo] ^= toBit;
      bb[(mover < 6) ? WP : BP]        |= fromBit;
    } else {
      bb[mover] ^= fromBit | toBit;
    }

    /* 2) castle rook shuffle */
    if (type == 3) switch (to) {
      case  6 -> bb[WR] ^= (1L<<7)  | (1L<<5);
      case  2 -> bb[WR] ^= (1L<<0)  | (1L<<3);
      case 62 -> bb[BR] ^= (1L<<63) | (1L<<61);
      case 58 -> bb[BR] ^= (1L<<56) | (1L<<59);
    }

    /* 3) restore capture */
    if (capIdx != 15) {
      long capMask = (type == 2)
              ? 1L << ((mover < 6) ? to - 8 : to + 8)
              : toBit;
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
                case 'P' -> WP;
                case 'N' -> WN;
                case 'B' -> WB;
                case 'R' -> WR;
                case 'Q' -> WQ;
                case 'K' -> WK;
                case 'p' -> BP;
                case 'n' -> BN;
                case 'b' -> BB;
                case 'r' -> BR;
                case 'q' -> BQ;
                case 'k' -> BK;
                default -> throw new IllegalArgumentException("bad fen piece: " + c);
              };
      bb[idx] |= 1L << sq;
    }
    /* side to move */
    long meta = parts[1].equals("b") ? 1L : 0L;

    /* castling */
    int cr = 0;
    if (parts[2].indexOf('K') >= 0) cr |= 0b0001;
    if (parts[2].indexOf('Q') >= 0) cr |= 0b0010;
    if (parts[2].indexOf('k') >= 0) cr |= 0b0100;
    if (parts[2].indexOf('q') >= 0) cr |= 0b1000;
    meta |= (long) cr << CR_SHIFT;

    /* en-passant */
    int epSq = 63;
    if (!parts[3].equals("-")) {
      int f = parts[3].charAt(0) - 'a';
      int r = parts[3].charAt(1) - '1';
      epSq = r * 8 + f;
    }
    meta |= (long) epSq << EP_SHIFT;

    /* clocks */
    int hc = Integer.parseInt(parts[4]);
    int fm = Integer.parseInt(parts[5]) - 1; // store 0-based internal
    meta |= (long) Math.min(127, hc) << HC_SHIFT;
    meta |= (long) Math.min(511, fm) << FM_SHIFT;

    bb[META] = meta;
    return bb;
  }

  /** Mid square the king passes during castling (e1 → g1 ⇒ f1, e8 → c8 ⇒ d8) */
  private static int castleTransit(int from, int to) {
    return (from + to) >> 1;          // same rank ⇒ simple midpoint
  }

  /* ══════════════════  Bit tricks for external callers  ═════════════════ */

  /*  DIFF_INFO layout (little-endian) – all fits in 32 bits
   *  ┌───────┬───────┬─────────┬──────────┬─────────┐
   *  │ bits  │ name  │ range   │ comment  │ used by │
   *  ├───────┼───────┼─────────┼──────────┼─────────┤
   *  │  0-5  │ from  │ 0-63    │ source   │ undo    │
   *  │  6-11 │  to   │ 0-63    │ target   │ undo    │
   *  │ 12-15 │ cap   │ 0-15    │ captured │ undo    │
   *  │ 16-19 │ mov   │ 0-15    │ mover    │ undo    │
   *  │ 20-21 │ typ   │ 0-3     │ 0 nrm / 1 prom /   │
   *  │       │       │         │           2 ep / 3 castle │
   *  │ 22-23 │ pro   │ 0-3     │ promo idx│ undo    │
   *  └───────┴───────┴─────────┴──────────┴─────────┘            */
  private static long packDiff(int from, int to, int cap, int mover, int typ, int pro) {
    return (from)
            | ((long) to << 6)
            | ((long) cap << 12)
            | ((long) mover << 16)
            | ((long) typ << 20)
            | ((long) pro << 22);
  }

  private static int dfFrom(long d) {
    return (int) (d & 0x3F);
  }

  private static int dfTo(long d) {
    return (int) ((d >>> 6) & 0x3F);
  }

  private static int dfCap(long d) {
    return (int) ((d >>> 12) & 0x0F);
  }

  private static int dfMover(long d) {
    return (int) ((d >>> 16) & 0x0F);
  }

  private static int dfType(long d) {
    return (int) ((d >>> 20) & 0x03);
  }

  private static int dfPromo(long d) {
    return (int) ((d >>> 22) & 0x03);
  }

  private static boolean epSquareIsCapturable(long[] bb, int epSq) {
    if (epSq == -1) return false;                 // no EP at all

    long epBit = 1L << epSq;
    boolean epOnRank3 = (epSq >>> 3) == 2;       // white pawn could capture ↓
    boolean epOnRank6 = (epSq >>> 3) == 5;       // black pawn could capture ↑

    if (epOnRank3) { // white just pushed → black may capture
      long blkLeft  = (epBit & ~FILE_A) <<  7; // from epSq-7
      long blkRight = (epBit & ~FILE_H) <<  9; // from epSq-9
      return ((bb[BP] & (blkLeft | blkRight)) != 0);
    }
    if (epOnRank6) { // black just pushed → white may capture
      long whtLeft  = (epBit & ~FILE_H) >>> 9; // from epSq+9
      long whtRight = (epBit & ~FILE_A) >>> 7; // from epSq+7
      return ((bb[WP] & (whtLeft | whtRight)) != 0);
    }
    return false;                                // any other rank ⇒ impossible
  }

  public long fullHash(long[] bb) {
    long k=0;
    for(int pc=WP;pc<=BK;++pc) {
      long bits=bb[pc];
      while(bits!=0) {
        int sq=Long.numberOfTrailingZeros(bits);
        k^=PIECE_SQUARE[pc][sq];
        bits&=bits-1;
      }
    }
    if ((bb[META]&STM_MASK)!=0)
      k^=SIDE_TO_MOVE;
    int cr= (int)((bb[META]&CR_MASK)>>>CR_SHIFT);
    k^=CASTLING[cr];
    int ep= (int)((bb[META]&EP_MASK)>>>EP_SHIFT);
    if(ep!=EP_NONE) k^=EP_FILE[ep&7];
    return k;
  }

}
