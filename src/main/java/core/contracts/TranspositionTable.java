package core.contracts;

import static core.constants.CoreConstants.*;

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
public interface TranspositionTable {

    /** Return value signalling a "miss" in the table. */
    long MISS = 0L;

    int ENTRIES_PER_BUCKET = 3; //
    int BUCKET_BYTES = 32;
    int MAX_AGE = 32; // Must be a power of 2, see nextSearch

    /** Flag constants */
    int FLAG_NONE = 0;   //
    int FLAG_LOWER = 1;  //
    int FLAG_UPPER = 2;  //
    int FLAG_EXACT = FLAG_LOWER | FLAG_UPPER; //


    /* ───── Primary Operations ───── */
    /**
     * Scans exactly one bucket and returns the absolute slot index that
     * either already holds {@code zobrist} or is the replacement victim.
     */
    int indexFor(long zobrist);

    /** Returns {@code true} if the {@code slot} value returned by
     *  {@link #indexFor} represents a key hit. */
    boolean wasHit(int slot);

    /**
     * Loads the packed entry that belongs to the {@code slot} returned by
     * {@link #indexFor}.  Must be called before another thread might have
     * overwritten the bucket if you need guaranteed consistency.
     */
    long dataAt(int slot);

    /**
     * Stores / updates the entry directly at {@code slot}.  Overwrite
     * rules are identical to the original implementation.
     */
    void store(int slot, long zobrist,
               int depth, int score, int flag,
               int move, int staticEval, boolean isPv, int ply);

    /** Resizes the table (in megabytes), discarding all current data. */
    void resize(int megaBytes);

    /** Clears all entries in the table. */
    void clear();

    /** Increments the table's current age for a new search. */
    void incrementAge();

    /**
     * Calculates the table's usage based on a sample of 1000 buckets.
     * @return A "hashfull" value in permill (parts per thousand).
     */
    int hashfull();

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

    /** Checks if the entry is empty (all zero). */
    default boolean isEmpty(long p) { return p == 0; }

    /**
     * 48-63 bits: signed score (short), *decoded* to caller’s ply.
     */
    default int unpackScore(long p, int ply) {
        int s = (short) (p >>> 48);
        if (s == SCORE_NONE) return SCORE_NONE;
        if (s >= SCORE_TB_WIN_IN_MAX_PLY)      return s - ply; // winning
        if (s <= SCORE_TB_LOSS_IN_MAX_PLY)     return s + ply; // losing
        return s;                                              // normal eval
    }
}