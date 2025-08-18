package core.contracts;

import static core.constants.CoreConstants.SCORE_MATE_IN_MAX_PLY;
import static core.constants.CoreConstants.SCORE_TB_LOSS_IN_MAX_PLY;
import static core.constants.CoreConstants.SCORE_TB_WIN_IN_MAX_PLY;
import static core.constants.CoreConstants.SCORE_NONE;

/**
 * Lock-free transposition-table abstraction using a flat long[] for entries.
 * This design achieves **zero allocations** on the hot path.
 *
 * Each entry is 16 bytes (2 longs).
 *
 * • Call {@link #resize(int)} once at start-up or after a "setoption Hash" change.
 * • Call {@link #clear()} at the beginning of every *game*.
 * • Call {@link #incrementAge()} exactly once at the start of each *root* search.
 *
 * {@link #probe(long)} returns an index to either a live hit or a replacement victim.
 * Test the returned index with {@link #wasHit(int, long)} to know which one it is.
 */
public interface TranspositionTable {
    /* ─────────── Bound Flags (Lower 2 bits of packed meta-data) ────────── */

    int FLAG_NONE = 0;
    int FLAG_LOWER = 1;
    int FLAG_UPPER = 2;
    int FLAG_EXACT = FLAG_LOWER | FLAG_UPPER;

    /* ─────────── Primary Ops ─────────── */

    /**
     * Probes the table for a given Zobrist key.
     *
     * @param zobrist The Zobrist key of the position.
     * @return The array index for a matching entry or for a victim entry to be replaced.
     * The index always points to the first of two longs representing the entry.
     */
    int probe(long zobrist);

    /**
     * Checks if the probe resulted in a successful hit.
     *
     * @param entryIndex The index returned by {@link #probe(long)}.
     * @param zobrist    The Zobrist key used for the probe.
     * @return {@code true} if the entry at the index matches the key and is not empty.
     */
    boolean wasHit(int entryIndex, long zobrist);


    /* ─────────── Entry Data Accessors (operating on an entry index) ─────────── */

    int getDepth(int entryIndex);
    int getBound(int entryIndex);
    int getMove(int entryIndex);
    int getStaticEval(int entryIndex);
    boolean wasPv(int entryIndex);

    /**
     * Retrieves the score, adjusting for mate distance from the current ply.
     *
     * @param entryIndex The entry index.
     * @param ply        The current search depth (from the root).
     * @return The mate-adjusted score.
     */
    int getScore(int entryIndex, int ply);


    /**
     * Stores a new or updated entry in the transposition table.
     * The replacement policy is handled internally.
     *
     * @param entryIndex The index returned by {@link #probe(long)}.
     * @param zobrist    The Zobrist key of the position.
     * @param bound      The bound type (FLAG_EXACT, FLAG_LOWER, FLAG_UPPER).
     * @param depth      The search depth for this entry.
     * @param move       The best move found.
     * @param score      The score of the position.
     * @param staticEval The static evaluation of the position.
     * @param isPv       Whether this was a PV node.
     * @param ply        The current search ply.
     */
    void store(int entryIndex, long zobrist, int bound, int depth, int move,
               int score, int staticEval, boolean isPv, int ply);

    /* ─────────── Life-cycle ─────────── */

    void resize(int megaBytes);
    void clear();
    void incrementAge();
    byte getCurrentAge();
    int hashfull();
}