package engine.internal.search.internal;

import static engine.internal.search.PackedPositionFactory.*;

import engine.Position;
import engine.internal.search.PackedMoveFactory;

public final class PackedPositionFactoryImpl
    implements engine.internal.search.PackedPositionFactory {

  /* ── piece-bitboard indices ─────────────────────────────────────────── */
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

  /* ══════════════════  factory API  ════════════════════════════════════ */

  @Override
  public Position fromBitboards(long[] bitboards) {
    return new PackedPosition(bitboards.clone()); // defensive snapshot
  }

  @Override
  public long[] toBitboards(Position p) {
    if (p instanceof PackedPosition pp) return pp.copy(); // fast path
    return fenToBitboards(p.toFen()); // generic
  }

  @Override
  public void makeMoveInPlace(long[] packed, int move) {
    int from = PackedMoveFactory.from(move);
    int to = PackedMoveFactory.to(move);
    int type = PackedMoveFactory.type(move);
    int promoIdx = PackedMoveFactory.promoIndex(move);

    long fromMask = 1L << from;
    long toMask = 1L << to;

    /* — locate moving piece — */
    int mover = -1;
    for (int i = 0; i < 12; ++i) {
      if ((packed[i] & fromMask) != 0) {
        mover = i;
        break;
      }
    }

    if (mover < 0) throw new IllegalStateException("no piece on from-square");

    boolean isWhite = mover < 6;
    boolean captured = false;

    /* — capture (incl. EP) — */
    if (type == 0 || type == 1) { // normal / promotion
      for (int i = 0; i < 12; ++i)
        if ((packed[i] & toMask) != 0) {
          packed[i] ^= toMask;
          captured = true;
          break;
        }
    } else if (type == 2) { // en-passant
      int capSq = isWhite ? to - 8 : to + 8;
      long capBit = 1L << capSq;
      packed[isWhite ? BP : WP] ^= capBit;
      captured = true;
    }

    /* — move piece — */
    packed[mover] ^= fromMask; // remove
    if (type == 1) { // promotion
      int dstIdx = (isWhite ? WN : BN) + promoIdx; // +0N,+1B,+2R,+3Q
      packed[dstIdx] |= toMask;
    } else {
      packed[mover] |= toMask;
    }

    /* — castling rook move — */
    if (type == 3) {
      switch (to) {
        case 6 -> { // white O-O  (e1→g1)
          packed[WR] &= ~(1L << 7); // clear h1
          packed[WR] |= 1L << 5; // set   f1
        }
        case 2 -> { // white O-O-O (e1→c1)
          packed[WR] &= ~(1L << 0); // clear a1
          packed[WR] |= 1L << 3; // set   d1
        }
        case 62 -> { // black O-O  (e8→g8)
          packed[BR] &= ~(1L << 63); // clear h8
          packed[BR] |= 1L << 61; // set   f8
        }
        case 58 -> { // black O-O-O (e8→c8)
          packed[BR] &= ~(1L << 56); // clear a8
          packed[BR] |= 1L << 59; // set   d8
        }
      }
    }

    /* — update META — */
    long meta = packed[META];
    boolean pawnMove = (mover == WP || mover == BP);

    /*  ep square  */
    if (pawnMove && Math.abs(to - from) == 16) {
      long epSq = isWhite ? from + 8 : from - 8;
      meta = (meta & ~EP_MASK) | (epSq << EP_SHIFT);
    } else meta = (meta & ~EP_MASK) | (63L << EP_SHIFT); // none

    /*  castling rights  */
    long cr = (meta & CR_MASK) >>> CR_SHIFT;
    cr = updateCastling(cr, mover, from, to);
    meta = (meta & ~CR_MASK) | (cr << CR_SHIFT);

    /*  half-move clock  */
    long hc = ((meta & HC_MASK) >>> HC_SHIFT);
    hc = (pawnMove || captured) ? 0 : Math.min(127, hc + 1);
    meta = (meta & ~HC_MASK) | (hc << HC_SHIFT);

    /*  full-move # and side-to-move  */
    long fm = ((meta & FM_MASK) >>> FM_SHIFT);
    if (!isWhite) fm = Math.min(511, fm + 1);
    meta ^= STM_MASK; // toggle stm
    meta = (meta & ~FM_MASK) | (fm << FM_SHIFT);

    packed[META] = meta;
  }

  /* ══════════════════  helper utilities  ═══════════════════════════════ */

  private static long updateCastling(long cr, int mover, int from, int to) {
    switch (mover) {
      case WK -> cr &= ~0b0011; // white king moved
      case BK -> cr &= ~0b1100; // black king moved
      case WR -> {
        if (from == 7) cr &= ~0b0001;
        else if (from == 0) cr &= ~0b0010;
      }
      case BR -> {
        if (from == 63) cr &= ~0b0100;
        else if (from == 56) cr &= ~0b1000;
      }
    }
    // rook captured?
    if (to == 7) cr &= ~0b0001;
    if (to == 0) cr &= ~0b0010;
    if (to == 63) cr &= ~0b0100;
    if (to == 56) cr &= ~0b1000;
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

  /* ══════════════════  inner immutable snapshot  ═══════════════════════ */

  private static final class PackedPosition implements Position {
    private final long[] bb; // length = 13

    PackedPosition(long[] bb) {
      this.bb = bb;
    }

    /* — Position interface — */
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
      int v = (int) ((bb[META] & EP_MASK) >>> EP_SHIFT);
      return v == 63 ? -1 : v;
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
