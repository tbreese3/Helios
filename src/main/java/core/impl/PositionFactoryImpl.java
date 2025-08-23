package core.impl;

import static core.contracts.PositionFactory.*;
import static core.impl.MoveGeneratorImpl.FILE_A;
import static core.impl.MoveGeneratorImpl.FILE_H;
// add: we use your precomputed attacks to mirror chesslib's EP "pinned" check
import static core.impl.PreCompMoveGenTables.*;

import core.contracts.*;
import java.util.Random;

public final class PositionFactoryImpl implements PositionFactory {

  /* piece indices (mirror interface) */
  private static final int WP = 0, WN = 1, WB = 2, WR = 3, WQ = 4, WK = 5,
          BP = 6, BN = 7, BB = 8, BR = 9, BQ = 10, BK = 11, META = 12;
  private static final int STM_BIT = 1; // same bit as STM_MASK but int-typed

  /* ───────── Chesslib-compatible Zobrist ─────────
     chesslib keys[] := XORSHIFT(49109794719L), size=2000
     piece/sq  : keys[57*piece + 13*sq]
     castle    : keys[300 + 3*side + 3*right]   (right: NONE=0,K=1,Q=2,KQ=3)
     ep target : keys[400 + 3*sq]               (sq is the *target pawn* square)
     side      : keys[500 + 3*side]             (side: WHITE=0, BLACK=1)
  */
  private static final long[] KEYS = new long[2000];
  public  static final long[][] PIECE_SQUARE = new long[12][64];
  private static final long[]   SIDE_KEY     = new long[2];     // WHITE=0, BLACK=1
  private static final long[][] CR_KEY       = new long[2][4];  // [side][right]
  private static final long[]   EP_SQ_KEY    = new long[64];    // per *target pawn* square

  // legacy fields kept so other classes compile; no longer used for hashing
  @Deprecated public  static final long[]   CASTLING  = new long[16];
  @Deprecated public  static final long[]   EP_FILE   = new long[8];
  @Deprecated public  static final long     SIDE_TO_MOVE = 0L;

  private static final long[] ZOBRIST_50MR = new long[120]; // unchanged API

  /* META layout */
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

  /* ===== xorshift identical to chesslib's XorShiftRandom ===== */
  private static long RNG_SEED = 49109794719L;
  // Corrected code
  private static long nextKey() {
    RNG_SEED ^= RNG_SEED >>> 12;
    RNG_SEED ^= RNG_SEED << 25;
    RNG_SEED ^= RNG_SEED >>> 27;
    return RNG_SEED * 0x2545F4914F6CDD1DL;
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

    // --- build chesslib's KEYS[] table
    for (int i = 0; i < KEYS.length; i++) KEYS[i] = nextKey();

    // --- piece-square keys
    for (int p = 0; p < 12; ++p)
      for (int sq = 0; sq < 64; ++sq)
        PIECE_SQUARE[p][sq] = KEYS[57 * (p + 1) + 13 * sq];

    // --- side keys
    SIDE_KEY[0] = KEYS[500 + 3*0]; // WHITE
    SIDE_KEY[1] = KEYS[500 + 3*1]; // BLACK

    // --- castle keys per side/per right (NONE=0, K=1, Q=2, KQ=3)
    for (int side = 0; side < 2; side++) {
      for (int right = 0; right < 4; right++) {
        if (right == 0) { CR_KEY[side][right] = 0L; continue; }
        CR_KEY[side][right] = KEYS[300 + 3*side + 3*right];
      }
    }

    // --- EP target-square keys (per square, chesslib uses 3*sq + 400)
    for (int sq = 0; sq < 64; ++sq) EP_SQ_KEY[sq] = KEYS[400 + 3*sq];

    // keep 50MR table seeded (not used by chesslib but preserved in your API)
    Random rnd = new Random(0x50F7505AL);
    for (int i = 0; i < ZOBRIST_50MR.length; ++i) ZOBRIST_50MR[i] = rnd.nextLong();
  }

  @Override
  public long zobrist50(long[] bb) {
    long key = bb[HASH];
    int hc   = (int)((bb[META] & HC_MASK) >>> HC_SHIFT); // 0..119
    return key ^ ZOBRIST_50MR[hc];
  }

  @Override
  public long[] fromFen(String fen) {
    long[] bb = fenToBitboards(fen);
    bb[COOKIE_SP] = 0;
    bb[DIFF_META] = bb[META];
    bb[DIFF_INFO] = 0;
    bb[HASH] = fullHash(bb); // chesslib-compatible
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

  /* ===== helpers to map CR bitmask -> chesslib CastleRight ordinal ===== */
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

  /* ===== EP target detection with "not pinned" legality (chesslib) =====
     Returns target pawn square (the pawn that can be taken EP), or EP_NONE.
     Uses current META (side-to-move and EP destination square). */
  private static int epTargetIfLegal(long[] bb, int meta, long occ) {
    // EP destination square from META (int-typed)
    int epDest = (meta & EP_BITS) >>> EP_SHIFT; // 0..63
    if (epDest == (int) EP_NONE) return (int) EP_NONE;

    boolean stmWhite = (meta & STM_BIT) == 0; // side to move (int-safe)
    long epBit = 1L << epDest;

    long pawns;
    int targetSq, kingSq;
    boolean captWhite;

    if (stmWhite) {
      // white to capture: white pawns from ep-9 / ep-7; target pawn square is ep-8
      pawns = bb[WP] & (((epBit & ~FILE_A) >>> 9) | ((epBit & ~FILE_H) >>> 7));
      if (pawns == 0L) return (int) EP_NONE;
      targetSq = epDest - 8;
      kingSq = Long.numberOfTrailingZeros(bb[WK]);
      captWhite = true;
    } else {
      // black to capture: black pawns from ep+9 / ep+7; target pawn square is ep+8
      pawns = bb[BP] & (((epBit & ~FILE_H) <<  9) | ((epBit & ~FILE_A) <<  7));
      if (pawns == 0L) return (int) EP_NONE;
      targetSq = epDest + 8;
      kingSq = Long.numberOfTrailingZeros(bb[BK]);
      captWhite = false;
    }

    long p = pawns;
    while (p != 0L) {
      int from = Long.numberOfTrailingZeros(p);
      p &= p - 1;

      long occ2 = occ;
      occ2 ^= (1L << from);     // remove capturing pawn from origin
      occ2 ^= (1L << targetSq); // remove the target pawn
      occ2 |= epBit;            // place capturing pawn on EP destination

      if (!kingAttackedWithOcc(bb, captWhite, kingSq, occ2)) {
        return targetSq; // at least one legal EP capture exists
      }
    }
    return (int) EP_NONE;
  }

  private static boolean kingAttackedWithOcc(long[] bb, boolean whiteKing, int kSq, long occ) {
    if (whiteKing) {
      if ( (bb[BP] & PAWN_ATK_W[kSq]) != 0 ) return true;
      if ( (bb[BN] & KNIGHT_ATK[kSq]) != 0 ) return true;
      if ( (MoveGeneratorImpl.bishopAtt(occ, kSq) & (bb[BB] | bb[BQ])) != 0 ) return true;
      if ( (MoveGeneratorImpl.rookAtt  (occ, kSq) & (bb[BR] | bb[BQ])) != 0 ) return true;
      return (bb[BK] & KING_ATK[kSq]) != 0;
    } else {
      if ( (bb[WP] & PAWN_ATK_B[kSq]) != 0 ) return true;
      if ( (bb[WN] & KNIGHT_ATK[kSq]) != 0 ) return true;
      if ( (MoveGeneratorImpl.bishopAtt(occ, kSq) & (bb[WB] | bb[WQ])) != 0 ) return true;
      if ( (MoveGeneratorImpl.rookAtt  (occ, kSq) & (bb[WR] | bb[WQ])) != 0 ) return true;
      return (bb[WK] & KING_ATK[kSq]) != 0;
    }
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

    {
      long occBefore =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
              | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
      int epTgtBefore = epTargetIfLegal(bb, metaOld, occBefore);
      if (epTgtBefore != EP_NONE) h ^= EP_SQ_KEY[epTgtBefore];
    }

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
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5);  h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3);  h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61); h ^= PIECE_SQUARE[BR][63]^ PIECE_SQUARE[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59); h ^= PIECE_SQUARE[BR][56]^ PIECE_SQUARE[BR][59];}
    }

    int meta = metaOld;

    // set new EP destination (FEN square). Hash for EP target will be added later if legal.
    int ep = (int) EP_NONE;
    if ((mover == WP || mover == BP) && ((from ^ to) == 16))
      ep = white ? from + 8 : from - 8;

    // update EP bits in META (hashing handled separately)
    if (ep != oldEP) meta = (meta & ~EP_BITS) | (ep << EP_SHIFT);

    // castling rights update + hash like chesslib (per side state)
    int newCR = ((meta & CR_BITS) >>> CR_SHIFT) & CR_MASK_LOST_FROM[from] & CR_MASK_LOST_TO[to];
    if (newCR != ((meta & CR_BITS) >>> CR_SHIFT)) {
      int oldCrMask = (meta & CR_BITS) >>> CR_SHIFT;
      int oldW = whiteCrOrdinal(oldCrMask), oldB = blackCrOrdinal(oldCrMask);
      int newW = whiteCrOrdinal(newCR),    newB = blackCrOrdinal(newCR);
      h ^= CR_KEY[0][oldW] ^ CR_KEY[0][newW];
      h ^= CR_KEY[1][oldB] ^ CR_KEY[1][newB];
      meta = (meta & ~CR_BITS) | (newCR << CR_SHIFT);
    }

    int newHC = ((mover == WP || mover == BP) || captured != 15)
            ? 0
            : ((metaOld & HC_BITS) >>> HC_SHIFT) + 1;
    meta = (meta & ~HC_BITS) | (newHC << HC_SHIFT);

    int fm = (meta >>> FM_SHIFT) & 0x1FF;
    if (!white) fm++;
    meta ^= STM_MASK;

    // side key toggle (remove old side/add new side)
    h ^= SIDE_KEY[white ? 0 : 1] ^ SIDE_KEY[white ? 1 : 0];

    // add new EP target hash (if any and legal for the *current* side-to-move before flip, i.e., opponent)
    {
      long occ =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
              | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
      // meta currently still has side flipped already (we flipped STM above), but chesslib adds EP target
      // BEFORE flipping side. So emulate chesslib by constructing a temporary meta where STM is old side:
      int metaForEp = meta ^ STM_BIT; // flip back to mover side for EP legality test
      metaForEp = (metaForEp & ~EP_BITS) | (ep << EP_SHIFT);
      int epTgt = epTargetIfLegal(bb, metaForEp, occ);
      if (epTgt != EP_NONE) h ^= EP_SQ_KEY[epTgt];
    }

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
    int  crAfter    = (metaAfter & CR_BITS) >>> CR_SHIFT;

    // remove EP key from "after" if present/legal (chesslib removes at the start of doMove; for undo we invert)
    {
      long occAfter =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
              | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
      int epTgtAfter = epTargetIfLegal(bb, metaAfter, occAfter);
      if (epTgtAfter != EP_NONE) h ^= EP_SQ_KEY[epTgtAfter];
    }

    // flip side key back (we had flipped in makeMove)
    boolean stmAfterWhite = (metaAfter & STM_MASK) == 0;
    h ^= SIDE_KEY[stmAfterWhite ? 0 : 1] ^ SIDE_KEY[stmAfterWhite ? 1 : 0];

    // toggle castle keys back using BEFORE/AFTER ordinals
    bb[META] ^= metaΔ; // now META = metaBefore
    int metaBefore = (int) bb[META];
    int crBefore   = (metaBefore & CR_BITS) >>> CR_SHIFT;

    int wA = whiteCrOrdinal(crAfter),  bA = blackCrOrdinal(crAfter);
    int wB = whiteCrOrdinal(crBefore), bB = blackCrOrdinal(crBefore);
    if (wA != wB) h ^= CR_KEY[0][wA] ^ CR_KEY[0][wB];
    if (bA != bB) h ^= CR_KEY[1][bA] ^ CR_KEY[1][bB];

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
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5);  h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3);  h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61); h ^= PIECE_SQUARE[BR][63]^ PIECE_SQUARE[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59); h ^= PIECE_SQUARE[BR][56]^ PIECE_SQUARE[BR][59];}
    }

    if (capIdx != 15) {
      int capSq = (type == 2) ? ((mover < 6) ? to - 8 : to + 8) : to;
      bb[capIdx] |= (1L << capSq);
      h ^= PIECE_SQUARE[capIdx][capSq];
    }

    // Corrected code
    int sp = (int) bb[COOKIE_SP] - 1;
    long ck = bb[COOKIE_BASE + sp];
    bb[COOKIE_BASE + sp] = 0L; // <-- Add this line to clear the stale cookie
    bb[COOKIE_SP] = sp;
    bb[DIFF_INFO] = (int)  ck;
    bb[DIFF_META] = (int) (ck >>> 32);

    // add EP key for "before" if present/legal
    {
      long occBefore =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
              | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
      int epTgtBefore = epTargetIfLegal(bb, metaBefore, occBefore);
      if (epTgtBefore != EP_NONE) h ^= EP_SQ_KEY[epTgtBefore];
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
      epSq = r * 8 + f; // FEN EP destination square
    }
    meta |= (long) epSq << EP_SHIFT;

    int hc = (parts.length > 4) ? Integer.parseInt(parts[4]) : 0;
    int fm = (parts.length > 5) ? Integer.parseInt(parts[5]) - 1 : 0;
    meta |= (long) hc << HC_SHIFT;
    meta |= (long) fm << FM_SHIFT;

    bb[META] = meta;
    return bb;
  }

  /** chesslib-compatible recomputation */
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

    // per-side castling state
    int crMask = (int)((bb[META] & CR_MASK) >>> CR_SHIFT);
    // Uses the updated whiteCrOrdinal/blackCrOrdinal functions
    k ^= CR_KEY[0][whiteCrOrdinal(crMask)];
    k ^= CR_KEY[1][blackCrOrdinal(crMask)];

    // side to move
    k ^= SIDE_KEY[ whiteToMove(bb) ? 0 : 1 ];

    // EP target square only if EP capture is legal for side-to-move (like chesslib)
    long occ = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]
            | bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
    int epTgt = epTargetIfLegal(bb, (int)bb[META], occ);
    if (epTgt != EP_NONE) k ^= EP_SQ_KEY[epTgt];

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
