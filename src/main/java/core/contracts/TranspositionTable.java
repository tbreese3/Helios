package core.contracts;

import static core.constants.CoreConstants.*;

/**
 * Lock-free transposition-table abstraction – **zero allocations** on the fast path.
 *
 * Lifetime                      ────────────────────────────────────────────────
 *   • call {@link #resize(int)} once at start-up or on a ‘setoption Hash’ change
 *   • call {@link #clear()} at the beginning of every *game*
 *   • call {@link #newSearch()} once per *root* search (increments generation)
 *
 * Probe / store                 ────────────────────────────────────────────────
 *   • {@link #probe(long)} returns either a live hit or the replacement victim
 *   • use {@link #wasHit(Entry,long)} on the returned slot to test for a hit
 */
public interface TranspositionTable {

    /* ---------- compile-time parameters ---------- */

    /** Set-associativity (matches Stockfish & Obsidian). */
    int ENTRIES_PER_BUCKET = 3;
    /** Number of generation “buckets”; must be power-of-two. */
    int MAX_GENERATION     = 32;          // 5 bits

    /* ---------- bound flags (two low bits) ---------- */

    int FLAG_NONE  = 0;
    int FLAG_LOWER = 1;
    int FLAG_UPPER = 2;
    int FLAG_EXACT = FLAG_LOWER | FLAG_UPPER;

    /* ---------- entry view ---------- */

    interface Entry {
        /* meta */
        boolean matches(long zKey16);           // 16-bit tag hit
        boolean isEmpty();                      // never written before
        int     getGeneration();                // 0-31 current table generation
        int     ageDistance(byte tableGen);     // helper for heuristics

        /* payload */
        int  getDepth();                        // 0-255 plies (unsigned byte)
        int  getBound();                        // FLAG_LOWER / UPPER / EXACT
        int  getMove();                         // 0 ⇒ none
        int  getStaticEval();
        int  getScore(int ply);                 // mate-distance corrected
        boolean wasPv();                        // PV-node flag

        /* write-back */
        void store(long zobrist, int flag, int depth, int move,
                   int score, int staticEval, boolean isPv,
                   int ply, byte tableGen);
    }

    /* ---------- primary ops ---------- */

    /** Returns either a live hit or a replacement victim. */
    Entry probe(long zobristKey);

    /** Convenience helper. */
    default boolean wasHit(Entry e, long zobristKey) {
        return e != null && e.matches(zobristKey) && !e.isEmpty();
    }

    /* ---------- life-cycle ---------- */

    void resize(int megaBytes);   // destroys all contents
    void clear();                 // zero the table (keeps size)
    void newSearch();             // bump generation (call per root search)
    int  hashfull();              // 0–1000 ‰ saturation
    byte getCurrentAge();
}
