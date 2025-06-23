package core.contracts;

import static core.constants.CoreConstants.*;

/**
 * Transposition-table abstraction – **zero allocations** on the hot path.
 *
 *  • {@link #probe(long)} returns the matching slot *or* the replacement victim.
 *  • Use {@link #wasHit(Entry,long)} to see whether it contained a live entry.
 */
public interface TranspositionTable {

    /* ---------- public constants ---------- */

    int ENTRIES_PER_BUCKET = 3;     // 3-way set-associative
    int MAX_AGE            = 32;    // must be power of two (5 bits)

    int FLAG_NONE  = 0;
    int FLAG_LOWER = 1;
    int FLAG_UPPER = 2;
    int FLAG_EXACT = FLAG_LOWER | FLAG_UPPER;   // == 3

    /* ---------- entry view ---------- */

    interface Entry {

        /* meta --------------------------------------------------- */
        boolean matches(long zobrist);         // 16-bit tag hit
        boolean isEmpty();                     // completely unused
        int     getAge();                      // 0-31
        int     getAgeDistance(byte tableAge);// helper for quality()

        /* packed payload ----------------------------------------- */
        int  getDepth();                       // search depth in plies
        int  getBound();                       // FLAG_LOWER / UPPER / EXACT
        int  getMove();                        // best move (0 => none)
        int  getStaticEval();                  // static evaluation
        int  getScore(int ply);                // score adjusted for mate dist
        boolean wasPv();                       // PV-node flag

        /* write-back --------------------------------------------- */
        void store(long zobrist, int flag, int depth, int move,
                   int score, int staticEval, boolean isPv,
                   int ply, byte tableAge);
    }

    /* ---------- primary ops ---------- */

    /** Returns either a live hit or the replacement victim. */
    Entry probe(long zobrist);

    /** True *iff* {@code e} is a live hit for that key. */
    default boolean wasHit(Entry e, long zobrist) {
        return e != null && e.matches(zobrist) && !e.isEmpty();
    }

    /* ---------- life-cycle ---------- */

    void resize(int megaBytes);   // discards everything
    void clear();                 // zero all buckets
    void incrementAge();          // call once per new root search
    int  hashfull();              // 0-1000 ‰
}
