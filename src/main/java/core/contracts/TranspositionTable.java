package core.contracts;

/**
 * Compact, bucket-based Transposition Table.
 *
 * Each table element is one 64-bit word with this exact layout
 * (lowest bit = 0, highest = 63):
 *
 *    0‒15   : signed score             (16 bits)
 *   16‒31   : signed static eval       (16 bits)
 *   32‒39   : search depth in plies     (8 bits)
 *   40‒41   : bound flag 0=NONE 1=L 2=U 3=EX  (2 bits)
 *      42   : PV flag (1 bit)
 *   43‒47   : age ring (5 bits, wraps at 32)
 *   48‒63   : best move (16 bits, 0 ⇢ MOVE_NONE)
 *
 * A parallel long[] keeps the 64-bit Zobrist key for collision checking.
 *
 * No allocations in {@link #probe}; the caller receives a pure value.
 */
public interface TranspositionTable extends AutoCloseable {

    /* Return value signalling “miss” (all bits zero is impossible for a hit,
       because either depth > 0 or a flag bit is set).                         */
    long MISS = 0L;

    /** Flag constants */
    int FLAG_LOWER = 1, FLAG_UPPER = 2, FLAG_EXACT = FLAG_LOWER | FLAG_UPPER;

    /* ───── basic operations ───── */

    /** Look-up; returns packed 64-bit entry or {@link #MISS}. */
    long probe(long zobrist);

    /** Store / update an entry using native replacement policy. */
    void store(long zobrist, int depth, int score, int flag,
               int bestMove, int staticEval, boolean isPv, int ply);

    /** Resize (in MB); discards all data. */
    void resize(int megaBytes);

    void clear();
    void nextSearch();          // age++
    int  hashfull();

    @Override void close();

    /* ───── bit-twiddling helpers  ───── */
    /*  0-15  signed static evaluation (int16) */
    default int unpackEval (long p) { return (short) (p        & 0xFFFF); }

    /* 16-23  age|pv|flag (see below) */
    default int unpackFlag(long p)  { return (int)  (p >>> 16) & 0x3; }          // bits 0-1
    default boolean unpackPv(long p){ return        ((p >>> 18) & 1L) != 0; }    // bit 2
    default int unpackAge (long p)  { return (int) ((p >>> 19) & 0x1F); }        // bits 3-7

    /* 24-31  depth (uint8) */
    default int unpackDepth(long p) { return (int) ((p >>> 24) & 0xFF); }

    /* 32-47  best move (uint16) */
    default int unpackMove (long p) { return (int) ((p >>> 32) & 0xFFFF); }

    /* 48-63  signed score (int16) – ply-corrected helper */
    default int unpackScore(long p, int ply) {
        int s = (short) ((p >>> 48) & 0xFFFF);

        final int MAX_PLY                 = 127;
        final int SCORE_TB_WIN_IN_MAX_PLY = 31745;

        if (s >=  SCORE_TB_WIN_IN_MAX_PLY) return s - ply;
        if (s <= -SCORE_TB_WIN_IN_MAX_PLY) return s + ply;
        return s;
    }
}
