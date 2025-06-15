package core.contracts;

/**
 * A compact, bucket-based Transposition Table
 *
 * Each table element consists of a 16-bit key and a 64-bit packed data word.
 * The data word has this exact layout (lowest bit = 0, highest = 63):
 *
 * Bits  | Content
 *-------|----------------------------------------------------------------
 * 0–15 | signed static evaluation       (16 bits, short)
 * 16–23 | age/pv/bound data block        (8 bits, byte)
 * |   16-17: bound flag (1=LOWER, 2=UPPER, 3=EXACT)
 * |   18:    PV node flag (1=isPV)
 * |   19-23: age (5 bits, 0-31)
 * 24–31 | search depth in plies          (8 bits, byte)
 * 32–47 | best move                      (16 bits, short, 0=NONE)
 * 48–63 | signed score (adjusted for mate) (16 bits, short)
 */
public interface TranspositionTable extends AutoCloseable {

    /** Return value signalling a "miss" in the table. */
    long MISS = 0L;

    /* --- Constants matching types.h and tt.h --- */
    int ENTRIES_PER_BUCKET = 3; //
    int BUCKET_BYTES = 32;
    int MAX_AGE = 32;           // 1 << 5, for 5 bits of age
    int MAX_PLY = 127;          //

    /* Score constants, matching types.h */
    int SCORE_MATE = 32000;                     //
    int SCORE_MATE_IN_MAX_PLY = SCORE_MATE - MAX_PLY; //
    int SCORE_TB_WIN = SCORE_MATE_IN_MAX_PLY - 1; //
    int SCORE_TB_WIN_IN_MAX_PLY = SCORE_TB_WIN - MAX_PLY; //
    int SCORE_TB_LOSS_IN_MAX_PLY = -SCORE_TB_WIN_IN_MAX_PLY; //
    int SCORE_NONE = 32002;                     //

    /** Flag constants, matching tt.h */
    int FLAG_NONE = 0;   //
    int FLAG_LOWER = 1;  //
    int FLAG_UPPER = 2;  //
    int FLAG_EXACT = FLAG_LOWER | FLAG_UPPER; //


    /* ───── Primary Operations ───── */

    /**
     * Probes the table for a given Zobrist key.
     * @param zobrist The 64-bit key.
     * @return The packed 64-bit data entry if found, otherwise {@link #MISS}.
     */
    long probe(long zobrist);

    /**
     * @param zobrist The 64-bit key.
     * @param depth Search depth in plies.
     * @param score Score (mate scores will be adjusted).
     * @param flag The bound type (LOWER, UPPER, EXACT).
     * @param move The best move found.
     * @param staticEval The static evaluation of the position.
     * @param isPv True if the entry is from a Principal Variation search.
     * @param ply The current ply from the root, used for mate score adjustment.
     */
    void store(long zobrist, int depth, int score, int flag,
               int move, int staticEval, boolean isPv, int ply);

    /** Resizes the table (in megabytes), discarding all current data. */
    void resize(int megaBytes);

    /** Clears all entries in the table. */
    void clear();

    /** Increments the table's current age for the new search. */
    void nextSearch();

    /**
     * Calculates the table's usage based on a sample of 1000 buckets.
     * @return A "hashfull" value in permill (parts per thousand).
     */
    int hashfull();

    @Override
    void close();


    /* ───── Bit-twiddling Unpack Helpers (Corrected Layout) ───── */

    /** 0-15: signed static evaluation (short) */
    default int unpackEval(long p) { return (short) (p & 0xFFFF); }

    /** 16-17: bound flag */
    default int unpackFlag(long p)  { return (int) ((p >>> 16) & 0x3); }

    /** 18: PV flag */
    default boolean unpackPv(long p){ return ((p >>> 18) & 1L) != 0; }

    /** 19-23: age */
    default int unpackAge(long p)   { return (int) ((p >>> 19) & 0x1F); }

    /** 24-31: depth (byte) */
    default int unpackDepth(long p) { return (int) ((p >>> 24) & 0xFF); }

    /** 32-47: best move (short) */
    default int unpackMove(long p) { return (int) ((p >>> 32) & 0xFFFF); }

    /**
     * 48-63: signed score (short), adjusted by ply for mate distance.
     */
    default int unpackScore(long p, int ply) {
        int s = (short) (p >>> 48);
        if (s == SCORE_NONE) return SCORE_NONE; //
        if (s >= SCORE_TB_WIN_IN_MAX_PLY) return s - ply; //
        if (s <= SCORE_TB_LOSS_IN_MAX_PLY) return s + ply; //
        return s;
    }

    /** Checks if the entry is empty (all zero). */
    default boolean isEmpty(long p) { return p == 0; }
}