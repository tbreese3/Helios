package core.impl;

import static core.contracts.PositionFactory.*;

import core.contracts.*;
import java.util.Arrays;

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
    Arrays.fill(CR_MASK_LOST_FROM, (short) 0b1111);
    Arrays.fill(CR_MASK_LOST_TO,   (short) 0b1111);

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

    MersenneTwister32 rnd = new MersenneTwister32(934572);

    for (int p = 0; p < 12; ++p) {
      for (int sq = 0; sq < 64; ++sq) {
        PIECE_SQUARE[p][sq] = rnd.nextLong();
      }
    }

    SIDE_TO_MOVE = rnd.nextLong();

    rnd.nextLong();

    for (int i = 0; i < 16; i++) {
      CASTLING[i] = rnd.nextLong();
    }

    for (int f = 0; f < 8; ++f) {
      EP_FILE[f] = rnd.nextLong();
    }
  }

  /**
   * Implementation of the 32-bit Mersenne Twister (MT19937) as defined in C++11.
   */
  private static final class MersenneTwister32 {
    private static final int N = 624;
    private static final int M = 397;
    // Use long for constants and internal state to handle 32-bit unsigned values correctly in Java.
    private static final long MATRIX_A = 0x9908b0dfL;
    private static final long UPPER_MASK = 0x80000000L;
    private static final long LOWER_MASK = 0x7fffffffL;

    private final long[] mt = new long[N];
    private int mti = N + 1;

    public MersenneTwister32(long seed) {
      initGenRand(seed);
    }

    private void initGenRand(long s) {
      // std::mt19937 uses a 32-bit seed.
      s &= 0xFFFFFFFFL;
      mt[0] = s;
      for (mti = 1; mti < N; mti++) {
        // Standard initialization multiplier: 1812433253
        mt[mti] = (1812433253L * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti);
        mt[mti] &= 0xffffffffL; // Keep results within 32 bits
      }
    }

    private long next32() {
      long y;
      long[] mag01 = {0x0L, MATRIX_A};

      if (mti >= N) {
        int kk;
        if (mti == N + 1)
          initGenRand(5489L); // Default seed if not initialized

        for (kk = 0; kk < N - M; kk++) {
          y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
          mt[kk] = mt[kk + M] ^ (y >>> 1) ^ mag01[(int) (y & 1L)];
        }
        for (; kk < N - 1; kk++) {
          y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
          mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ mag01[(int) (y & 1L)];
        }
        y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
        mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ mag01[(int) (y & 1L)];

        mti = 0;
      }

      y = mt[mti++];

      // Tempering
      y ^= (y >>> 11);
      y ^= (y << 7) & 0x9d2c5680L;
      y ^= (y << 15) & 0xefc60000L;
      y ^= (y >>> 18);

      return y & 0xFFFFFFFFL;
    }

    /**
     * Generates a 64-bit long by combining two 32-bit outputs.
     */
    public long nextLong() {
      long lower = next32();
      long upper = next32();
      return (upper << 32) | lower;
    }
  }

  @Override
  public long zobrist50(long[] bb) {
    return bb[HASH];
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

    // En Passant square.
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

    // Save state for undo
    int sp = (int) bb[COOKIE_SP];
    bb[COOKIE_BASE + sp] =
            (bb[DIFF_META] & 0xFFFF_FFFFL) << 32 |
                    (bb[DIFF_INFO] & 0xFFFF_FFFFL);
    bb[COOKIE_SP] = sp + 1;

    // --- Execute Move and Update Piece Hashes ---
    int captured = 15;
    if (type <= 1) {
      // Determine if a piece is captured (standard capture)
      long enemy = white
              ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
              : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]);
      if ((enemy & toBit) != 0) {
        // Identify captured piece type
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
      // En passant capture
      int capSq   = white ? to - 8 : to + 8;
      captured    = white ? BP : WP;
      bb[captured] &= ~(1L << capSq);
      h ^= PIECE_SQUARE[captured][capSq];
    }

    // Move the piece (remove from origin)
    bb[mover] ^= fromBit;
    h ^= PIECE_SQUARE[mover][from];

    // Place the piece (or promotion piece) at destination
    if (type == 1) {
      int promIdx = (white ? WN : BN) + promo;
      bb[promIdx] |= toBit;
      h ^= PIECE_SQUARE[promIdx][to];
    } else {
      bb[mover]   |= toBit;
      h ^= PIECE_SQUARE[mover][to];
    }

    // Handle castling rook moves
    if (type == 3) switch (to) {
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5); h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3); h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61);h ^= PIECE_SQUARE[BR][63] ^ PIECE_SQUARE[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59);h ^= PIECE_SQUARE[BR][56] ^ PIECE_SQUARE[BR][59];}
    }

    // --- Update State (META) and associated Hashes ---
    int meta = metaOld;
    int ep = (int) EP_NONE;

    // Handle EP square update
    if ((mover == WP || mover == BP) && ((from ^ to) == 16)) {
      // Double pawn push. Check if the opponent can capture immediately.
      long opponentPawns = white ? bb[BP] : bb[WP];
      boolean canCapture = false;

      // Check adjacent files to the destination square 'to'.
      // (to & 7) gives the file index.

      // Check left adjacent (File - 1). Ensure not on File A.
      if (((to & 7) > 0) && ((opponentPawns & (1L << (to - 1))) != 0)) {
        canCapture = true;
      }
      // Check right adjacent (File + 1). Ensure not on File H.
      if (!canCapture && ((to & 7) < 7) && ((opponentPawns & (1L << (to + 1))) != 0)) {
        canCapture = true;
      }

      // Only set the EP target if capture is possible.
      if (canCapture) {
        ep = white ? from + 8 : from - 8;
      }
    }

    if (ep != oldEP) {
      meta = (meta & ~EP_BITS) | (ep << EP_SHIFT);

      // Update EP Hash. We maintain the invariant: EP square is set IFF EP hash key is included.
      if (oldEP != EP_NONE) h ^= EP_FILE[oldEP & 7];
      if (ep != EP_NONE)    h ^= EP_FILE[ep & 7];
    }

    // Update Castling Rights
    int cr = oldCR & CR_MASK_LOST_FROM[from] & CR_MASK_LOST_TO[to];
    if (cr != oldCR) {
      meta = (meta & ~CR_BITS) | (cr << CR_SHIFT);
      h ^= CASTLING[oldCR] ^ CASTLING[cr];
    }

    // Update HC/FM/STM
    int newHC = ((mover == WP || mover == BP) || captured != 15)
            ? 0
            : ((metaOld & HC_BITS) >>> HC_SHIFT) + 1;
    meta = (meta & ~HC_BITS) | (newHC << HC_SHIFT);

    int fm = (meta >>> FM_SHIFT) & 0x1FF;
    if (!white) fm++;
    meta ^= STM_MASK;
    meta = (meta & ~((int)FM_MASK)) | (fm << FM_SHIFT);
    h ^= SIDE_TO_MOVE; // Flip STM hash key

    bb[DIFF_INFO] = (int) packDiff(from, to, captured, mover, type, promo);
    bb[DIFF_META] = (int) (bb[META] ^ meta);
    bb[META]      = meta;
    bb[HASH]      = h;

    // Legality check (ensure own king is not in check)
    if (gen.kingAttacked(bb, white)) {
      // Move is illegal, revert state.
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

    // Restore META
    bb[META] ^= metaΔ;
    int metaBefore = (int) bb[META];
    int crBefore   = (metaBefore & CR_BITS) >>> CR_SHIFT;
    int epBefore   = (metaBefore & EP_BITS) >>> EP_SHIFT;

    // Revert STM and CR hashes
    h ^= SIDE_TO_MOVE;
    if (crAfter != crBefore) h ^= CASTLING[crAfter] ^ CASTLING[crBefore];

    // Decode move
    int from   = dfFrom(diff);
    int to     = dfTo(diff);
    int capIdx = dfCap(diff);
    int mover  = dfMover(diff);
    int type   = dfType(diff);
    int promo  = dfPromo(diff);

    long fromBit = 1L << from;
    long toBit   = 1L << to;

    // Revert piece movements and hashes
    if (type == 1) { // Promotion undo
      int promIdx = (mover < 6 ? WN : BN) + promo;
      bb[promIdx] ^= toBit;
      bb[mover]   |= fromBit;
      h ^= PIECE_SQUARE[promIdx][to] ^ PIECE_SQUARE[mover][from];
    } else {
      bb[mover] ^= fromBit | toBit;
      h ^= PIECE_SQUARE[mover][to] ^ PIECE_SQUARE[mover][from];
    }

    if (type == 3) { // Castle undo
      switch (to) {
        case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5); h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
        case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3); h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
        case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61);h ^= PIECE_SQUARE[BR][63] ^ PIECE_SQUARE[BR][61];}
        case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59);h ^= PIECE_SQUARE[BR][56] ^ PIECE_SQUARE[BR][59];}
      }
    }

    if (capIdx != 15) { // Capture undo
      int capSq = (type == 2) ? ((mover < 6) ? to - 8 : to + 8) : to;
      bb[capIdx] |= 1L << capSq;
      h ^= PIECE_SQUARE[capIdx][capSq];
    }

    // Restore cookies
    int sp = (int) bb[COOKIE_SP] - 1;
    long ck = bb[COOKIE_BASE + sp];
    bb[COOKIE_SP] = sp;
    bb[DIFF_INFO] = (int)  ck;
    bb[DIFF_META] = (int) (ck >>> 32);

    // Revert EP Hash. Relies on the invariant (Hash has key IFF EP square is set).
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

  // Used only when makeMoveInPlace detects an illegal move.
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
    // HASH is restored by the caller.
  }

  // Helper to check if an EP capture is possible, used in fenToBitboards.
  private static boolean hasEpCaptureStatic(long[] bb, int epSq, boolean whiteToMove) {
    // whiteToMove is the side that can potentially capture EP.

    long capturingPawns;

    if (whiteToMove) {
      // If White is moving, the EP square must be on Rank 6 (index 5).
      if ((epSq >>> 3) != 5) return false;
      capturingPawns = bb[WP];
    } else {
      // If Black is moving, the EP square must be on Rank 3 (index 2).
      if ((epSq >>> 3) != 2) return false;
      capturingPawns = bb[BP];
    }

    int epFile = epSq & 7;

    // Check capture from File - 1 (Not FILE_A)
    if (epFile > 0) {
      // WTM: capture towards -9 (e.g. d5->e6). BTM: capture towards +7 (e.g. d4->e3).
      int sourceSq = whiteToMove ? (epSq - 9) : (epSq + 7);
      if ((capturingPawns & (1L << sourceSq)) != 0) return true;
    }

    // Check capture from File + 1 (Not FILE_H)
    if (epFile < 7) {
      // WTM: capture towards -7 (e.g. f5->e6). BTM: capture towards +9 (e.g. f4->e3).
      int sourceSq = whiteToMove ? (epSq - 7) : (epSq + 9);
      if ((capturingPawns & (1L << sourceSq)) != 0) return true;
    }

    return false;
  }

  private static long[] fenToBitboards(String fen) {
    long[] bb = new long[BB_LEN];
    String[] parts = fen.trim().split("\\s+");

    // 1. Board layout
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

    // 2. Side to move
    boolean whiteToMove = parts[1].equals("w");
    long meta = whiteToMove ? 0L : 1L;

    // 3. Castling rights
    int cr = 0;
    if (parts[2].indexOf('K') >= 0) cr |= 0b0001;
    if (parts[2].indexOf('Q') >= 0) cr |= 0b0010;
    if (parts[2].indexOf('k') >= 0) cr |= 0b0100;
    if (parts[2].indexOf('q') >= 0) cr |= 0b1000;
    meta |= (long) cr << CR_SHIFT;

    // 4. En passant square
    int epSq = (int) EP_NONE;
    if (!parts[3].equals("-")) {
      int f = parts[3].charAt(0) - 'a';
      int r = parts[3].charAt(1) - '1';
      int potentialEpSq = r * 8 + f;

      // The EP target is only set if a capture is possible. We enforce this invariant here.
      if (hasEpCaptureStatic(bb, potentialEpSq, whiteToMove)) {
        epSq = potentialEpSq;
      }
    }
    meta |= (long) epSq << EP_SHIFT;

    // 5. Halfmove clock and 6. Fullmove number
    int hc = (parts.length > 4) ? Integer.parseInt(parts[4]) : 0;
    int fm = (parts.length > 5) ? Integer.parseInt(parts[5]) - 1 : 0;
    if (fm < 0) fm = 0;
    meta |= (long) hc << HC_SHIFT;
    meta |= (long) fm << FM_SHIFT;

    bb[META] = meta;
    return bb;
  }

  public long fullHash(long[] bb) {
    long k = 0;
    // 1. Pieces
    for (int pc = WP; pc <= BK; ++pc) {
      long bits = bb[pc];
      while (bits != 0) {
        int sq = Long.numberOfTrailingZeros(bits);
        k ^= PIECE_SQUARE[pc][sq];
        bits &= bits - 1;
      }
    }

    // 2. Side to move
    if ((bb[META] & STM_MASK) != 0)
      k ^= SIDE_TO_MOVE;

    // 3. Castling Rights
    int cr = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
    k ^= CASTLING[cr];

    // 4. En Passant
    // EP square is set in META IFF capture is possible.
    int ep = (int) ((bb[META] & EP_MASK) >>> EP_SHIFT);
    if (ep != EP_NONE)
      k ^= EP_FILE[ep & 7];

    return k;
  }

  // Packing/Unpacking DIFF_INFO
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