package core;

import static core.MoveGeneratorImpl.FILE_A;
import static core.MoveGeneratorImpl.FILE_H;
import static core.contracts.PositionFactory.*;

import core.contracts.*;

public final class PositionFactoryImpl implements PositionFactory {
  /* piece indices (mirror interface) */
  /* piece indices (kept) … */
  private static final int WP = 0, WN = 1, WB = 2, WR = 3, WQ = 4, WK = 5,
          BP = 6, BN = 7, BB = 8, BR = 9, BQ = 10, BK = 11,
          META = 12;

  /* NEW: stored aggregates */
  public  static final int ALL   = 13;          // all white+black pieces
  public  static final int WHITE = 14;          // all white pieces
  public  static final int BLACK = 15;          // all black pieces

  /* DIFF cookie gets bumped three slots down --------------------- */
  private static final int DIFF_META = 16;
  private static final int DIFF_INFO = 17;

  /* cookie stack pointer & base follow right after                 */
  private static final int COOKIE_SP   = 18;    // stack pointer
  private static final int COOKIE_BASE = 19;    // first stored cookie
  private static final int COOKIE_CAP  = 128;   // ≥ max ply
  private static final int BB_LEN      = COOKIE_BASE + (COOKIE_CAP << 1);

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

  static {
    java.util.Arrays.fill(CR_MASK_LOST_FROM, (short) 0b1111);
    java.util.Arrays.fill(CR_MASK_LOST_TO,   (short) 0b1111);

    /* king moves → lose both rights of that side */
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
  }

  private static void xorAgg(long[] bb, long bits, boolean white) {
    bb[ALL] ^= bits;
    if (white) bb[WHITE] ^= bits;
    else       bb[BLACK] ^= bits;
  }

  @Override
  public long[] fromFen(String fen) {
    long[] bb = fenToBitboards(fen);

    long wOcc =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK];
    long bOcc =  bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];

    bb[WHITE] = wOcc;
    bb[BLACK] = bOcc;
    bb[ALL]   = wOcc | bOcc;

    bb[COOKIE_SP] = 0;          // empty undo stack
    bb[DIFF_META] = bb[META];   // no previous move yet
    bb[DIFF_INFO] = 0;
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

    int from  = (mv >>>  6) & 0x3F;
    int to    =  mv         & 0x3F;
    int type  = (mv >>> 14) & 0x3;   // 0 nrm · 1 promo · 2 EP · 3 castle
    int promo = (mv >>> 12) & 0x3;
    int mover = (mv >>> 16) & 0xF;

    boolean white = mover < 6;
    long fromBit  = 1L << from,
            toBit    = 1L << to,
            moveBits = fromBit | toBit;

    /* 1. reject illegal castle up-front */
    if (type == 3 && !gen.castleLegal(bb, from, to)) return false;

    /* 2. push undo cookie (DIFF already holds previous ply) */
    int sp  = (int) bb[COOKIE_SP];
    int off = COOKIE_BASE + (sp << 1);
    bb[off]     = bb[DIFF_META];
    bb[off + 1] = bb[DIFF_INFO];
    bb[COOKIE_SP] = ++sp;

    /* 3. locate & remove captured piece (constant-time cascade) */
    int  captured = 15;
    long capMask  = 0L;

    if (type == 2)          capMask = 1L << (white ? to - 8 : to + 8);
    else if (type <= 1)     capMask = toBit;

    if (capMask != 0) {
      if      ((bb[white ? BP : WP] & capMask) != 0) captured = white ? BP : WP;
      else if ((bb[white ? BN : WN] & capMask) != 0) captured = white ? BN : WN;
      else if ((bb[white ? BB : WB] & capMask) != 0) captured = white ? BB : WB;
      else if ((bb[white ? BR : WR] & capMask) != 0) captured = white ? BR : WR;
      else if ((bb[white ? BQ : WQ] & capMask) != 0) captured = white ? BQ : WQ;
      else if ((bb[white ? BK : WK] & capMask) != 0) captured = white ? BK : WK;

      if (captured == (white ? BK : WK)) {           // king capture ⇒ illegal
        sp = (int) bb[COOKIE_SP] - 1;         // drop one frame
        bb[COOKIE_SP] = sp;
        off = COOKIE_BASE + (sp << 1);
        bb[DIFF_META] = bb[off];
        bb[DIFF_INFO] = bb[off + 1];
        return false;
      }
      if (captured != 15) {
        bb[captured] &= ~capMask;
        /* inline aggregate update – captured piece removed */
        bb[ALL]   ^= capMask;
        if (captured < 6) bb[WHITE] ^= capMask;
        else               bb[BLACK] ^= capMask;
      }
    }

    /* 4. move / promote the mover */
    if (type == 1) {                                // promotion
      bb[mover] ^= fromBit;                       // erase pawn
      int dst = (white ? WN : BN) + promo;        // add new piece
      bb[dst] |= toBit;
      /* aggregates */
      bb[ALL]   ^= moveBits;
      if (white) bb[WHITE] ^= moveBits; else bb[BLACK] ^= moveBits;
    } else {                                        // normal / EP / castle src-dst
      bb[mover] ^= moveBits;
      bb[ALL]   ^= moveBits;
      if (white) bb[WHITE] ^= moveBits; else bb[BLACK] ^= moveBits;
    }

    /* 5. rook shuffle for castling */
    if (type == 3) {
      long rookBits = (to == 6  ? (1L<<7)|(1L<<5)   :     // w 0-0
              to == 2  ? (1L<<0)|(1L<<3)   :     // w 0-0-0
                      to == 62 ? (1L<<63)|(1L<<61) :     // b 0-0
                              (1L<<56)|(1L<<59));    // b 0-0-0

      bb[ white ? WR : BR ] ^= rookBits;
      bb[ALL]   ^= rookBits;
      if (white) bb[WHITE] ^= rookBits; else bb[BLACK] ^= rookBits;
    }

    /* 6. META update (unchanged algorithm, just write once) */
    long metaOld = bb[META], meta = metaOld;

    meta = (meta & ~EP_MASK)
            | (((type == 0 && (mover == WP || mover == BP) && ((to ^ from) == 16))
            ? (long)(white ? from + 8 : from - 8)
            : EP_NONE) << EP_SHIFT);

    long cr = (meta & CR_MASK) >>> CR_SHIFT;
    cr = updateCastling(cr, from, to);
    meta = (meta & ~CR_MASK) | (cr << CR_SHIFT);

    long hc = (meta & HC_MASK) >>> HC_SHIFT;
    hc = ((mover == WP || mover == BP) || captured != 15) ? 0 : Math.min(127, hc + 1);
    meta = (meta & ~HC_MASK) | (hc << HC_SHIFT);

    long fm = (meta & FM_MASK) >>> FM_SHIFT;
    if (!white) fm = Math.min(511, fm + 1);
    meta ^= STM_MASK;
    meta = (meta & ~FM_MASK) | (fm << FM_SHIFT);

    bb[META]      = meta;
    bb[DIFF_META] = metaOld;
    bb[DIFF_INFO] = packDiff(from, to, captured, mover, type, promo);

    /* 7. legality: own king must be safe */
    if (gen.kingAttacked(bb, white)) {
      fastUndo(bb);
      sp = (int) bb[COOKIE_SP] - 1;         // drop one frame
      bb[COOKIE_SP] = sp;
      off = COOKIE_BASE + (sp << 1);
      bb[DIFF_META] = bb[off];
      bb[DIFF_INFO] = bb[off + 1];
      return false;
    }
    return true;
  }

  /* ────────────────────────────────────────────────────────────
   *  undoMoveInPlace  – cookie lives in bb[]
   * ──────────────────────────────────────────────────────────── */
  @Override
  public void undoMoveInPlace(long[] bb) {
    fastUndo(bb);
    int sp = (int) bb[COOKIE_SP] - 1;
    bb[COOKIE_SP] = sp;
    int off = COOKIE_BASE + (sp << 1);
    bb[DIFF_META] = bb[off];
    bb[DIFF_INFO] = bb[off + 1];
  }


  /* —— shared undo helper —— */
  private static void fastUndo(long[] bb) {

    long d   = bb[DIFF_INFO];
    int from = dfFrom(d), to = dfTo(d),
            cap  = dfCap(d),  mov = dfMover(d),
            typ  = dfType(d), pro  = dfPromo(d);

    boolean white = mov < 6;
    long fromBit  = 1L << from,
            toBit    = 1L << to,
            moveBits = fromBit | toBit;

    /* 1. mover back */
    if (typ == 1) {                                // promotion
      int dst = (white ? WN : BN) + pro;
      bb[dst] ^= toBit;
      bb[mov] |= fromBit;
      bb[ALL]   ^= moveBits;
      if (white) bb[WHITE] ^= moveBits; else bb[BLACK] ^= moveBits;
    } else {
      bb[mov] ^= moveBits;
      bb[ALL]   ^= moveBits;
      if (white) bb[WHITE] ^= moveBits; else bb[BLACK] ^= moveBits;
    }

    /* 2. undo rook shuffle */
    if (typ == 3) {
      long rookBits = (to == 6  ? (1L<<7)|(1L<<5) :
              to == 2  ? (1L<<0)|(1L<<3) :
                      to == 62 ? (1L<<63)|(1L<<61) :
                              (1L<<56)|(1L<<59));
      bb[ white ? WR : BR ] ^= rookBits;
      bb[ALL]   ^= rookBits;
      if (white) bb[WHITE] ^= rookBits; else bb[BLACK] ^= rookBits;
    }

    /* 3. restore capture */
    if (cap != 15) {
      long capBit = (typ == 2) ? 1L << (white ? to - 8 : to + 8) : toBit;
      bb[cap] |= capBit;
      bb[ALL]   ^= capBit;
      if (cap < 6) bb[WHITE] ^= capBit; else bb[BLACK] ^= capBit;
    }

    /* 4. META back */
    bb[META] = bb[DIFF_META];
  }


  /* ═════════════  helpers & FEN parsing (unchanged)  ═════════════ */
  private static long updateCastling(long cr, int fromSq, int toSq) {
    return cr & CR_MASK_LOST_FROM[fromSq] & CR_MASK_LOST_TO[toSq];
  }

  private static void popCookie(long[] bb) {
    int sp = (int) bb[COOKIE_SP] - 1;
    bb[COOKIE_SP] = sp;
    int off = COOKIE_BASE + (sp << 1);
    bb[DIFF_META] = bb[off];
    bb[DIFF_INFO] = bb[off + 1];
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
}
