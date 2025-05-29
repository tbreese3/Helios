package engine.internal.search.internal;

import engine.Position;
import engine.internal.search.MoveGenerator;
import engine.internal.search.PackedPositionFactory;

/** Packed position implementation with Diff-based fast make/undo. */
public final class PackedPositionFactoryImpl implements PackedPositionFactory {

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

  /* META layout (unchanged) */
  private static final long STM_MASK = 1L;
  private static final int CR_SHIFT = 1;
  private static final long CR_MASK = 0b1111L << CR_SHIFT;
  private static final int EP_SHIFT = 5;
  private static final long EP_MASK = 0x3FL << EP_SHIFT;
  private static final int HC_SHIFT = 11;
  private static final long HC_MASK = 0x7FL << HC_SHIFT;
  private static final int FM_SHIFT = 18;
  private static final long FM_MASK = 0x1FFL << FM_SHIFT;

  /* ═════════════  public helpers for the piece-map  ═════════════ */
  /** Fill {@code board} with 0 (=empty) or 1-based piece ids. */
  public void initPieceMap(long[] bb, byte[] board) {
    java.util.Arrays.fill(board, (byte) 0);
    for (int p = WP; p <= BK; ++p) {
      long bits = bb[p];
      while (bits != 0) {
        int sq = Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        board[sq] = (byte) (p + 1);
      }
    }
  }

  /* ═════════════  allocation-free make / undo  ═════════════ */
  public boolean makeMoveInPlace(
      long[] bb, byte[] board, int mv, MoveGenerator gen, FastDiff diff) {
    int from = (mv >>> 6) & 0x3F;
    int to = mv & 0x3F;
    int type = (mv >>> 14) & 0x3; // 0=norm 1=promo 2=EP 3=castle
    int promo = (mv >>> 12) & 0x3;

    int mover = (board[from] & 0xFF) - 1; // 0-based id
    int captured = 15; // sentinel “none”

    long metaBefore = bb[META]; // snapshot META

    long fromMask = 1L << from;
    long toMask = 1L << to;
    boolean white = mover < 6;

    /* ---- capture / en-passant ---------------------------------- */
    if (type == 0 || type == 1) { // normal capture
      int tmp = (board[to] & 0xFF) - 1;
      if (tmp >= 0) {
        captured = tmp;
        bb[captured] ^= toMask;
      }
    } else if (type == 2) { // EP
      int capSq = white ? to - 8 : to + 8;
      long capBit = 1L << capSq;
      captured = white ? BP : WP;
      bb[captured] ^= capBit;
      board[capSq] = 0;
    }

    /* ---- move / promotion -------------------------------------- */
    bb[mover] ^= fromMask;
    board[from] = 0;
    if (type == 1) { // promotion
      int dstIdx = (white ? WN : BN) + promo;
      bb[dstIdx] |= toMask;
      board[to] = (byte) (dstIdx + 1);
    } else {
      bb[mover] |= toMask;
      board[to] = (byte) (mover + 1);
    }

    /* ---- rook shuffle on castling ------------------------------ */
    if (type == 3)
      switch (to) {
        case 6 -> {
          bb[WR] ^= (1L << 7) | (1L << 5);
          board[7] = 0;
          board[5] = (byte) (WR + 1);
        }
        case 2 -> {
          bb[WR] ^= (1L << 0) | (1L << 3);
          board[0] = 0;
          board[3] = (byte) (WR + 1);
        }
        case 62 -> {
          bb[BR] ^= (1L << 63) | (1L << 61);
          board[63] = 0;
          board[61] = (byte) (BR + 1);
        }
        case 58 -> {
          bb[BR] ^= (1L << 56) | (1L << 59);
          board[56] = 0;
          board[59] = (byte) (BR + 1);
        }
      }

    /* ---- META update (unchanged logic) ------------------------- */
    long meta = metaBefore;
    boolean pawnMove = mover == WP || mover == BP;
    boolean took = captured != 15;

    // en-passant target
    if (pawnMove && Math.abs(to - from) == 16) {
      long epSq = white ? from + 8 : from - 8;
      meta = (meta & ~EP_MASK) | (epSq << EP_SHIFT);
    } else {
      meta = (meta & ~EP_MASK) | (EP_NONE << EP_SHIFT);
    }

    // castling rights
    long cr = (meta & CR_MASK) >>> CR_SHIFT;
    cr = updateCastling(cr, mover, from, to);
    meta = (meta & ~CR_MASK) | (cr << CR_SHIFT);

    // half-move clock
    long hc = (meta & HC_MASK) >>> HC_SHIFT;
    hc = (pawnMove || took) ? 0 : Math.min(127, hc + 1);
    meta = (meta & ~HC_MASK) | (hc << HC_SHIFT);

    long fm = (meta & FM_MASK) >>> FM_SHIFT;
    if (!white) fm = Math.min(511, fm + 1);
    meta ^= STM_MASK;
    meta = (meta & ~FM_MASK) | (fm << FM_SHIFT);

    bb[META] = meta;

    /* ---- legality check ---------------------------------------- */
    bb[META] ^= STM_MASK;
    boolean illegal = gen.inCheck(bb);
    bb[META] ^= STM_MASK;
    if (illegal) {
      fastUndo(bb, board, mv, from, to, mover, captured, metaBefore);
      return false;
    }

    /* ---- fill diff for caller ---------------------------------- */
    diff.move = mv;
    diff.from = from;
    diff.to = to;
    diff.mover = mover;
    diff.capturedIdx = captured;
    diff.oldMeta = metaBefore;
    return true;
  }

  @Override
  public void undoMoveInPlace(long[] bb, byte[] board, FastDiff d) {
    fastUndo(bb, board, d.move, d.from, d.to, d.mover, d.capturedIdx, d.oldMeta);
  }

  /* ---- shared undo helper -------------------------------------- */
  private static void fastUndo(
      long[] bb, byte[] board, int mv, int from, int to, int mover, int capIdx, long oldMeta) {
    int type = (mv >>> 14) & 0x3; // 0-norm | 1-promo | 2-EP | 3-castle
    int promo = (mv >>> 12) & 0x3;

    long fromMask = 1L << from;
    long toMask = 1L << to;

    /* 1 ─ restore the moving piece --------------------------------*/
    if (type == 1) { // promotion
      int dstIdx = (mover < 6 ? WN : BN) + promo;
      bb[dstIdx] ^= toMask;
      board[to] = 0;
      int pawnIdx = mover < 6 ? WP : BP;
      bb[pawnIdx] |= fromMask;
      board[from] = (byte) (pawnIdx + 1);
    } else { // normal / EP / castle
      bb[mover] ^= fromMask | toMask;
      board[from] = (byte) (mover + 1);
      board[to] = 0;
    }

    /* 2 ─ undo rook shuffle on castling ---------------------------*/
    if (type == 3)
      switch (to) {
        case 6 -> {
          bb[WR] ^= (1L << 7) | (1L << 5);
          board[7] = (byte) (WR + 1);
          board[5] = 0;
        }
        case 2 -> {
          bb[WR] ^= (1L << 0) | (1L << 3);
          board[0] = (byte) (WR + 1);
          board[3] = 0;
        }
        case 62 -> {
          bb[BR] ^= (1L << 63) | (1L << 61);
          board[63] = (byte) (BR + 1);
          board[61] = 0;
        }
        case 58 -> {
          bb[BR] ^= (1L << 56) | (1L << 59);
          board[56] = (byte) (BR + 1);
          board[59] = 0;
        }
      }

    /* 3 ─ restore captured piece ----------------------------------*/
    if (capIdx != 15) {
      long capMask =
          (type == 2)
              ? 1L << ((mover < 6) ? to - 8 : to + 8) // EP square differs
              : toMask;
      bb[capIdx] |= capMask;
      int capSq = Long.numberOfTrailingZeros(capMask);
      board[capSq] = (byte) (capIdx + 1);
    }

    /* 4 ─ restore META exactly ------------------------------------*/
    bb[META] = oldMeta;
  }

  /* ═════════════  immutable snapshot helpers (unchanged) ════════ */
  @Override
  public Position fromBitboards(long[] bb) {
    return new PackedPosition(bb.clone());
  }

  @Override
  public long[] toBitboards(Position p) {
    return (p instanceof PackedPosition pp) ? pp.copy() : fenToBitboards(p.toFen());
  }

  /* ═════════════  helper struct for diff  ═════════════ */
  public static final class FastDiff {
    public int move, from, to, mover, capturedIdx;
    public long oldMeta;
  }

  /* ═════════════  helpers & FEN parsing (unchanged)  ═════════════ */
  private static long updateCastling(long cr, int m, int from, int to) {
    /* ... */
    switch (m) {
      case WK -> cr &= ~0b0011;
      case BK -> cr &= ~0b1100;
      case WR -> {
        if (from == 7) cr &= ~1;
        else if (from == 0) cr &= ~2;
      }
      case BR -> {
        if (from == 63) cr &= ~4;
        else if (from == 56) cr &= ~8;
      }
    }
    if (to == 7) cr &= ~1;
    if (to == 0) cr &= ~2;
    if (to == 63) cr &= ~4;
    if (to == 56) cr &= ~8;
    return cr;
  }

  /*  parse a FEN string into a 13-long[]  */
  private static long[] fenToBitboards(String fen) {
    long[] bb = new long[13];
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

  /* ══════════════════  Bit tricks for external callers  ═════════════════ */

  public static int lsb(long b) {
    return Long.numberOfTrailingZeros(b);
  }

  public static long popLsb(long b) {
    return b & (b - 1);
  }

  /* ═════════════  inner snapshot  ═════════════ */
  private static final class PackedPosition implements Position {
    private final long[] bb;

    PackedPosition(long[] bb) {
      this.bb = bb;
    }

    @Override
    public boolean whiteToMove() {
      return (bb[META] & STM_MASK) == 0;
    }

    @Override
    public int halfmoveClock() {
      return (int) ((bb[META] & HC_MASK) >>> HC_SHIFT);
    }

    @Override
    public int fullmoveNumber() {
      return 1 + (int) ((bb[META] & FM_MASK) >>> FM_SHIFT);
    }

    @Override
    public int castlingRights() {
      return (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
    }

    @Override
    public int enPassantSquare() {
      int e = (int) ((bb[META] & EP_MASK) >>> EP_SHIFT);
      return e == EP_NONE ? -1 : e;
    }

    @Override
    public String toFen() {
      return toFenString();
    }

    /* — helpers — */
    private String toFenString() {
      StringBuilder sb = new StringBuilder(64);
      for (int rank = 7; rank >= 0; --rank) {
        int empty = 0;
        for (int file = 0; file < 8; ++file) {
          int sq = rank * 8 + file;
          char pc = pieceCharAt(sq);
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
      sb.append(whiteToMove() ? " w " : " b ");

      int cr = castlingRights();
      sb.append(
          cr == 0
              ? "-"
              : ""
                  + ((cr & 1) != 0 ? 'K' : "")
                  + ((cr & 2) != 0 ? 'Q' : "")
                  + ((cr & 4) != 0 ? 'k' : "")
                  + ((cr & 8) != 0 ? 'q' : ""));

      sb.append(' ');
      int ep = enPassantSquare();
      sb.append(ep == -1 ? "-" : "" + (char) ('a' + (ep & 7)) + (1 + (ep >>> 3)));
      sb.append(' ');
      sb.append(halfmoveClock()).append(' ').append(fullmoveNumber());
      return sb.toString();
    }

    private char pieceCharAt(int sq) {
      for (int i = 0; i < 12; ++i) if ((bb[i] & (1L << sq)) != 0) return "PNBRQKpnbrqk".charAt(i);
      return 0;
    }

    long[] copy() {
      return bb.clone();
    }
  }
}
