package core.contracts;

import static core.constants.CoreConstants.*;

/**
 * Lock-free transposition-table abstraction – **zero allocations** on the hot path.
 *
 *  • Call {@link #resize(int)} once at start-up or after a “setoption Hash” change.
 *  • Call {@link #clear()} at the beginning of every *game*.
 *  • Call {@link #incrementAge()} exactly once at the start of each *root* search
 *    (a.k.a. “new search”) so replacement heuristics can age-out old entries.
 *
 * {@link #probe(long)} returns either a live hit or the replacement victim; test
 * the returned slot with {@link #wasHit(Entry,long)} to know which one it is.
 */
public interface TranspositionTable {
    /* ─────────── bound flags (stored in the two LSBs) ────────── */

    int FLAG_NONE  = 0;
    int FLAG_LOWER = 1;
    int FLAG_UPPER = 2;
    int FLAG_EXACT = FLAG_LOWER | FLAG_UPPER;

    /* ─────────── view on a single 16-byte slot ─────────── */

    interface Entry {

        /* meta */
        boolean matches(long zobrist);            // 16-bit tag match
        boolean isEmpty();                        // never written before
        int     getAge();                         // 0-31  (5-bit wrap-around)
        int     getAgeDistance(byte tableAge);    // helper for replacement

        /* payload */
        int  getDepth();                          // 0-255 plies (unsigned byte)
        int  getBound();                          // one of FLAG_*
        int  getMove();                           // 0 ⇒ none
        int  getStaticEval();
        int  getScore(int ply);                   // mate-distance corrected
        boolean wasPv();                          // PV-node flag

        /* write-back */
        void store(long zobrist, int flag, int depth, int move,
                   int score, int staticEval, boolean isPv,
                   int ply, byte tableAge);
    }

    /* ─────────── primary ops ─────────── */

    /** Returns either a live hit or a replacement victim in the same bucket. */
    Entry probe(long zobrist);

    /** Convenience helper. */
    default boolean wasHit(Entry e, long zobrist) {
        return e != null && e.matches(zobrist) && !e.isEmpty();
    }

    /* ─────────── life-cycle ─────────── */

    void resize(int megaBytes);     // destroys all contents, keeps generations
    void clear();                   // zero the whole table (keeps size)
    void incrementAge();            // bump generation once per *root* search
    byte getCurrentAge();           // expose current generation to workers
    int  hashfull();                // 0–1000 ‰ saturation indicator
}
