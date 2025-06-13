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
  private static final int COOKIE_CAP  = 128;          // ≥ max depth
  private static final int COOKIE_SP   = 15;           // bb[15] = stack-pointer
  private static final int COOKIE_BASE = 16;           // first cookie
  /** size of a freshly-allocated board array */
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

    /* 1) unpack the move word (unchanged) */
    int from  = (mv >>>  6) & 0x3F;
    int to    =  mv         & 0x3F;
    int type  = (mv >>> 14) & 0x3;   // 0=nrm 1=promo 2=EP 3=castle
    int promo = (mv >>> 12) & 0x3;   // 0=Q 1=R 2=B 3=N
    int mover = (mv >>> 16) & 0xF;   // 0-11 piece index

    boolean white   = mover < 6;
    long    fromBit = 1L << from;
    long    toBit   = 1L << to;

    if (type == 3 && !gen.castleLegal(bb, from, to))
      return false;                                // illegal castle

    /* ── PUSH the caller’s cookie (32 + 32 bits in ONE slot) ───────────── */
    int sp = (int) bb[COOKIE_SP];
    if (sp >= COOKIE_CAP) throw new AssertionError("cookie stack overflow");

    long packed =
            (bb[DIFF_META] & 0xFFFF_FFFFL) << 32     // hi = caller’s metaΔ
                    | (bb[DIFF_INFO] & 0xFFFF_FFFFL);          // lo = caller’s diffInfo
    bb[COOKIE_BASE + sp] = packed;
    bb[COOKIE_SP] = sp + 1;

    /* 2) capture handling (unchanged) ----------------------------------- */
    int captured = 15;                               // 15 = no capture
    if (type == 0 || type == 1) {                    // ordinary / promotion
      long mask = toBit;
      if      ((bb[white ? BP : WP] & mask) != 0) captured = white ? BP : WP;
      else if ((bb[white ? BN : WN] & mask) != 0) captured = white ? BN : WN;
      else if ((bb[white ? BB : WB] & mask) != 0) captured = white ? BB : WB;
      else if ((bb[white ? BR : WR] & mask) != 0) captured = white ? BR : WR;
      else if ((bb[white ? BQ : WQ] & mask) != 0) captured = white ? BQ : WQ;
      else if ((bb[white ? BK : WK] & mask) != 0) captured = white ? BK : WK;

      /* king capture → illegal pseudo move */
      if (captured == (white ? BK : WK)) {
        bb[COOKIE_SP] = sp;          // pop again
        return false;
      }
      if (captured != 15) bb[captured] &= ~mask;
    } else if (type == 2) {                          // en-passant
      int capSq  = white ? to - 8 : to + 8;
      long capBit = 1L << capSq;
      captured = white ? BP : WP;
      bb[captured] &= ~capBit;
    }

    /* 3) move or promote the moving piece (unchanged) ------------------- */
    bb[mover] ^= fromBit;                            // clear source square
    if (type == 1) {                                 // promotion
      bb[(white ? WN : BN) + promo] |= toBit;
    } else {
      bb[mover] |= toBit;
    }

    /* 4) rook shuffle for castling (unchanged) -------------------------- */
    if (type == 3) switch (to) {
      case  6 -> bb[WR] ^= (1L << 7)  | (1L << 5);
      case  2 -> bb[WR] ^= (1L << 0)  | (1L << 3);
      case 62 -> bb[BR] ^= (1L << 63) | (1L << 61);
      case 58 -> bb[BR] ^= (1L << 56) | (1L << 59);
    }

    /* 5) META update (unchanged algorithm) ------------------------------ */
    long metaBefore = bb[META];
    long meta = metaBefore;

    /* 5a) en-passant */
    if ((mover == WP || mover == BP) && Math.abs(to - from) == 16) {
      int epSq = white ? from + 8 : from - 8;
      meta = (meta & ~EP_MASK) | ((long) epSq << EP_SHIFT);
    } else {
      meta = (meta & ~EP_MASK) | (EP_NONE << EP_SHIFT);
    }

    /* 5b) castling rights */
    long cr = (meta & CR_MASK) >>> CR_SHIFT;
    cr = updateCastling(cr, from, to);
    meta = (meta & ~CR_MASK) | (cr << CR_SHIFT);

    /* 5c) half-move clock */
    long hc = (meta & HC_MASK) >>> HC_SHIFT;
    boolean pawnMove = (mover == WP || mover == BP);
    boolean took     = captured != 15;
    hc = (pawnMove || took) ? 0 : Math.min(127, hc + 1);
    meta = (meta & ~HC_MASK) | (hc << HC_SHIFT);

    /* 5d) full-move number & stm bit */
    long fm = (meta & FM_MASK) >>> FM_SHIFT;
    if (!white) fm = Math.min(511, fm + 1);
    meta ^= STM_MASK;
    meta = (meta & ~FM_MASK) | (fm << FM_SHIFT);

    /* ---------- store *current* DIFF_* for this ply ------------------- */
    bb[DIFF_INFO] = (int) packDiff(from, to, captured, mover, type, promo);
    bb[DIFF_META] = (int) (metaBefore ^ meta);       // xor-delta only
    bb[META]      = meta;                            // commit

    /* 6) legality: is our own king now in check? ------------------------ */
    if (gen.kingAttacked(bb, white)) {               // illegal → rollback
      fastUndo(bb);
      bb[COOKIE_SP] = sp;                          // pop
      long prev = bb[COOKIE_BASE + sp];
      bb[DIFF_INFO] =  prev         & 0xFFFF_FFFFL;
      bb[DIFF_META] = (prev >>> 32) & 0xFFFF_FFFFL;
      return false;
    }
    return true;                                     // legal move
  }

  /* ────────────────────────────────────────────────────────────
   *  undoMoveInPlace  – cookie lives in bb[]
   * ──────────────────────────────────────────────────────────── */
  @Override
  public void undoMoveInPlace(long[] bb) {

    fastUndo(bb);                                    // uses live DIFF_* cells

    int sp  = (int) bb[COOKIE_SP] - 1;               // pop
    long ck = bb[COOKIE_BASE + sp];                  // caller’s cookie
    bb[COOKIE_SP] = sp;

    bb[DIFF_INFO] =  ck         & 0xFFFF_FFFFL;      // restore caller snapshot
    bb[DIFF_META] = (ck >>> 32) & 0xFFFF_FFFFL;
  }

  /* —— shared low-level undo —— */
  private static void fastUndo(long[] bb) {

    long d        = bb[DIFF_INFO];                   // 32-bit packed diff
    long metaΔ     = bb[DIFF_META];                  // xor-mask (32 bit safe)
    long metaNow   = bb[META];                       // META *after* the move
    long metaOld   = metaNow ^ metaΔ;                // META *before* the move

    int from   = dfFrom(d);
    int to     = dfTo(d);
    int capIdx = dfCap(d);
    int mover  = dfMover(d);
    int type   = dfType(d);                          // 0 nrm | 1 prom | 2 EP | 3 castle
    int promo  = dfPromo(d);

    long fromBit = 1L << from;
    long toBit   = 1L << to;

    /* 1) restore the moving piece (incl. undo promotion) -------------- */
    if (type == 1) {                                 // promotion
      int dst = (mover < 6 ? WN : BN) + promo;
      bb[dst] ^= toBit;                            // remove promoted piece
      int pawnIdx = mover < 6 ? WP : BP;
      bb[pawnIdx] |= fromBit;                      // put pawn back
    } else {
      bb[mover] ^= fromBit | toBit;                // normal / EP / castle
    }

    /* 2) undo rook shuffle for castling ------------------------------- */
    if (type == 3) switch (to) {
      case  6 -> bb[WR] ^= (1L << 7)  | (1L << 5);
      case  2 -> bb[WR] ^= (1L << 0)  | (1L << 3);
      case 62 -> bb[BR] ^= (1L << 63) | (1L << 61);
      case 58 -> bb[BR] ^= (1L << 56) | (1L << 59);
    }

    /* 3) restore captured piece --------------------------------------- */
    if (capIdx != 15) {
      long capMask =
              (type == 2)
                      ? 1L << ((mover < 6) ? to - 8 : to + 8)    // EP capture
                      : toBit;
      bb[capIdx] |= capMask;
    }

    /* 4) restore META exactly ----------------------------------------- */
    bb[META] = metaOld;
  }

  /* ═════════════  helpers & FEN parsing (unchanged)  ═════════════ */
  private static long updateCastling(long cr, int fromSq, int toSq) {
    return cr & CR_MASK_LOST_FROM[fromSq] & CR_MASK_LOST_TO[toSq];
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
