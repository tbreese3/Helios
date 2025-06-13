package core;

import static core.MoveGeneratorImpl.FILE_A;
import static core.MoveGeneratorImpl.FILE_H;
import static core.contracts.PositionFactory.*;

import core.contracts.*;

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
  private static final int COOKIE_SP   = 15;                 // stack pointer
  private static final int COOKIE_BASE = 16;                 // first stored cookie
  private static final int COOKIE_CAP  = 128;                // ≥ max ply
  /* piece-at-square array lives *after* the cookie area -------------- */
  private static final int PIECE_MAP_BASE = COOKIE_BASE + (COOKIE_CAP << 1); // 272
  private static final int EMPTY          = 15;              // marker for “no piece”

  /* bb.length must be at least COOKIE_BASE + COOKIE_CAP*2 + 64 */
  private static final int BB_LEN = PIECE_MAP_BASE + 64;      // 336

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

  @Override
  public long[] fromFen(String fen) {
    // 1) parse the FEN string into a brand-new bit-board array
    long[] bb = fenToBitboards(fen);

    // 2) initialise transient bookkeeping fields -------------------------
    // cookie-stack pointer (bb[15]) is already zero from the new array,
    // but writing it out makes the intent explicit.
    bb[COOKIE_SP] = 0L; // empty cookie stack

    // DIFF_META / DIFF_INFO are “last move” snapshots.
    // At the root position there is no previous move, so
    //   – DIFF_META mirrors the current META,
    //   – DIFF_INFO is left 0 (no diff yet).
    bb[DIFF_META] = bb[META];
    bb[DIFF_INFO] = 0L;

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

    /* 1 ── decode */
    int from  = (mv >>>  6) & 0x3F,
            to    =  mv         & 0x3F,
            type  = (mv >>> 14) & 0x3,          // 0 nrm /1 promo /2 EP /3 castle
            promo = (mv >>> 12) & 0x3,
            mover = (mv >>> 16) & 0xF;

    boolean white   = mover < 6;
    long fromBit    = 1L << from,
            toBit      = 1L << to;

    /* 2 ── castle legality (cheap) */
    if (type == 3 && !gen.castleLegal(bb, from, to)) return false;

    /* 3 ── push parent diff onto cookie stack */
    int sp  = (int) bb[COOKIE_SP],
            off = COOKIE_BASE + (sp << 1);
    bb[off]     = bb[DIFF_META];
    bb[off + 1] = bb[DIFF_INFO];
    bb[COOKIE_SP] = ++sp;

    /* 4 ── capture via piece-map (single load) */
    int captured = (int) bb[PIECE_MAP_BASE + to];           // 0-11 or 15
    if (type == 2) {                                        // en-passant
      captured              = white ? BP : WP;
      int capSq             = white ? to - 8 : to + 8;
      bb[captured]         &= ~(1L << capSq);
      bb[PIECE_MAP_BASE + capSq] = EMPTY;
    } else if (captured != EMPTY) {                         // normal capture
      bb[captured] &= ~toBit;
      /* no need to blank PIECE_MAP[to] – we'll overwrite it below */
    }

    /* 5 ── move / promotion */
    bb[mover] ^= fromBit;                                   // clear ‘from’
    bb[PIECE_MAP_BASE + from] = EMPTY;

    if (type == 1) {                                        // promotion
      int dst = (white ? WN : BN) + promo;
      bb[dst] |= toBit;
      bb[PIECE_MAP_BASE + to] = dst;
    } else {
      bb[mover] |= toBit;
      bb[PIECE_MAP_BASE + to] = mover;
    }

    /* 6 ── rook shuffle for castling */
    if (type == 3) switch (to) {
      case  6 -> { bb[WR]^=(1L<<7)|(1L<<5);
        bb[PIECE_MAP_BASE+5]=WR; bb[PIECE_MAP_BASE+7]=EMPTY; }
      case  2 -> { bb[WR]^=(1L<<0)|(1L<<3);
        bb[PIECE_MAP_BASE+3]=WR; bb[PIECE_MAP_BASE+0]=EMPTY; }
      case 62 -> { bb[BR]^=(1L<<63)|(1L<<61);
        bb[PIECE_MAP_BASE+61]=BR; bb[PIECE_MAP_BASE+63]=EMPTY; }
      case 58 -> { bb[BR]^=(1L<<56)|(1L<<59);
        bb[PIECE_MAP_BASE+59]=BR; bb[PIECE_MAP_BASE+56]=EMPTY; }
    }

    /* 7 ── META bookkeeping (unchanged) */
    long meta0 = bb[META], meta = meta0;

    if ((mover == WP || mover == BP) && Math.abs(to - from) == 16)
      meta = (meta & ~EP_MASK) | ((long)(white ? from + 8 : from - 8) << EP_SHIFT);
    else  meta = (meta & ~EP_MASK) | (EP_NONE << EP_SHIFT);

    long cr = (meta & CR_MASK) >>> CR_SHIFT;
    meta = (meta & ~CR_MASK) | (updateCastling(cr, from, to) << CR_SHIFT);

    long hc = (meta & HC_MASK) >>> HC_SHIFT;
    meta = (meta & ~HC_MASK)
            | (((mover == WP || mover == BP) || captured != EMPTY) ? 0
            : Math.min(127, hc + 1)) << HC_SHIFT;

    long fm = (meta & FM_MASK) >>> FM_SHIFT;
    if (!white) fm = Math.min(511, fm + 1);
    meta ^= STM_MASK;
    meta = (meta & ~FM_MASK) | (fm << FM_SHIFT);

    /* 8 ── commit + 24-bit diff cookie */
    bb[META]      = meta;
    bb[DIFF_META] = meta0;
    bb[DIFF_INFO] = packDiff(from, to, captured, mover, type, promo);

    /* 9 ── legality: own king safe? */
    if (gen.kingAttacked(bb, white)) {
      fastUndo(bb);                     // instant rollback
      bb[COOKIE_SP] = sp - 1;           // pop stack
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

    /* cookie-stack constants (compile-time) */
    final int SP_IDX = 15;
    final int COOKIE_BASE = 16;

    fastUndo(bb); // restore pieces + META

    int sp = (int) bb[SP_IDX] - 1; // pop
    bb[SP_IDX] = sp;
    int off = COOKIE_BASE + (sp << 1);
    bb[DIFF_META] = bb[off];
    bb[DIFF_INFO] = bb[off + 1];
  }

  /* —— shared undo helper —— */
  private static void fastUndo(long[] bb) {

    long d      = bb[DIFF_INFO];         // compact diff cookie
    long oldM   = bb[DIFF_META];         // previous META

    int from    = dfFrom(d),   to = dfTo(d);
    int capIdx  = dfCap(d),    mover = dfMover(d);
    int type    = dfType(d),   promo  = dfPromo(d);

    long fromBit = 1L << from,
            toBit   = 1L << to;

    /* 1 ── revert pieces on the bit-boards (and rook shuffle) */
    switch (type) {
      case 0 ->                       /* normal move */
              bb[mover] ^= fromBit | toBit;

      case 1 -> {                     /* promotion */
        int dst = (mover < 6 ? WN : BN) + promo;
        bb[dst]   ^= toBit;           // remove promoted piece
        bb[mover] |= fromBit;         // restore pawn
      }

      case 2 -> {                     /* en-passant */
        bb[mover] ^= fromBit | toBit;
        int capSq        = mover < 6 ? to - 8 : to + 8;
        bb[capIdx]      |= 1L << capSq;
        bb[PIECE_MAP_BASE + capSq] = capIdx;
      }

      case 3 -> {                     /* castling */
        bb[mover] ^= fromBit | toBit;
        switch (to) {
          case  6 -> {                // white 0-0
            bb[WR] ^= (1L<<7)|(1L<<5);
            bb[PIECE_MAP_BASE+7] = WR;
            bb[PIECE_MAP_BASE+5] = EMPTY;
          }
          case  2 -> {                // white 0-0-0
            bb[WR] ^= (1L<<0)|(1L<<3);
            bb[PIECE_MAP_BASE+0] = WR;
            bb[PIECE_MAP_BASE+3] = EMPTY;
          }
          case 62 -> {                // black 0-0
            bb[BR] ^= (1L<<63)|(1L<<61);
            bb[PIECE_MAP_BASE+63] = BR;
            bb[PIECE_MAP_BASE+61] = EMPTY;
          }
          case 58 -> {                // black 0-0-0
            bb[BR] ^= (1L<<56)|(1L<<59);
            bb[PIECE_MAP_BASE+56] = BR;
            bb[PIECE_MAP_BASE+59] = EMPTY;
          }
        }
      }
    }

    /* 2 ── restore captured piece for non-EP captures */
    if (type != 2 && capIdx != EMPTY) {
      bb[capIdx] |= toBit;
    }

    /* 3 ── piece-map: only the squares that changed */
    bb[PIECE_MAP_BASE + from] = mover;
    bb[PIECE_MAP_BASE + to]   =
            (type == 2)               ? EMPTY
                    : (capIdx != EMPTY)         ? capIdx
                    : EMPTY;

    /* 4 ── META exactly as before the move */
    bb[META] = oldM;
  }

  /* ═════════════  helpers & FEN parsing (unchanged)  ═════════════ */
  private static long updateCastling(long cr, int fromSq, int toSq) {
    return cr & CR_MASK_LOST_FROM[fromSq] & CR_MASK_LOST_TO[toSq];
  }

  private static long[] fenToBitboards(String fen) {

    long[] bb = new long[BB_LEN];

    /* 0) mark every board square as EMPTY (=15) in the piece-map */
    for (int sq = 0; sq < 64; ++sq)
      bb[PIECE_MAP_BASE + sq] = EMPTY;

    String[] parts = fen.trim().split("\\s+");

    /* 1) piece placement ------------------------------------------------ */
    String board = parts[0];
    int rank = 7, file = 0;
    for (char c : board.toCharArray()) {
      if (c == '/')                 { rank--; file = 0; continue; }
      if (Character.isDigit(c))     { file += c - '0'; continue; }

      int sq  = rank * 8 + file++;
      int idx = switch (c) {
        case 'P' -> WP; case 'N' -> WN; case 'B' -> WB;
        case 'R' -> WR; case 'Q' -> WQ; case 'K' -> WK;
        case 'p' -> BP; case 'n' -> BN; case 'b' -> BB;
        case 'r' -> BR; case 'q' -> BQ; case 'k' -> BK;
        default  -> throw new IllegalArgumentException("bad fen piece: " + c);
      };
      bb[idx]                 |= 1L << sq;   // set bit-board bit
      bb[PIECE_MAP_BASE + sq]  = idx;        // write piece-map
    }

    /* 2) META construction --------------------------------------------- */
    long meta = parts[1].equals("b") ? 1L : 0L;          // side to move

    int cr = 0;
    if (parts[2].indexOf('K') >= 0) cr |= 0b0001;
    if (parts[2].indexOf('Q') >= 0) cr |= 0b0010;
    if (parts[2].indexOf('k') >= 0) cr |= 0b0100;
    if (parts[2].indexOf('q') >= 0) cr |= 0b1000;
    meta |= (long) cr << CR_SHIFT;

    int epSq = (int)EP_NONE;
    if (!parts[3].equals("-")) {
      int f = parts[3].charAt(0) - 'a';
      int r = parts[3].charAt(1) - '1';
      epSq  = r * 8 + f;
    }
    meta |= (long) epSq << EP_SHIFT;

    int hc = Integer.parseInt(parts[4]);
    int fm = Integer.parseInt(parts[5]) - 1;             // store 0-based
    meta |= (long) Math.min(127, hc) << HC_SHIFT;
    meta |= (long) Math.min(511, fm) << FM_SHIFT;

    bb[META]       = meta;
    bb[DIFF_META]  = meta;   // root position “diff” mirrors META
    bb[DIFF_INFO]  = 0L;

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
  private static long packDiff(int f, int t, int c, int m,
                               int typ, int pro) {
    return  (f)
            | ((long)t   <<  6)
            | ((long)c   << 12)
            | ((long)m   << 16)
            | ((long)typ << 20)
            | ((long)pro << 22);
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
