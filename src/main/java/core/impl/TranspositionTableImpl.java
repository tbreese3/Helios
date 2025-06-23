package core.impl;

import core.contracts.TranspositionTable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

import static core.constants.CoreConstants.*;

/**
 * Lock-free, bucketed transposition table.
 * <p>
 * Differences to the original version:
 * <ul>
 *   <li><b>32-bit tags</b> – use the high half of the Zobrist key to
 *       slash accidental collisions (1 : 4 294 967 296 vs 1 : 65 536).</li>
 *   <li>Keys array is now <code>int[]</code>; all logic is otherwise
 *       unchanged.</li>
 * </ul>
 */
public final class TranspositionTableImpl implements TranspositionTable {

    /* ── addressing & meta ─────────────────────────────────────── */

    private static final int INDEX_BITS = 30;              // usable bits
    private static final int INDEX_MASK = (1 << INDEX_BITS) - 1;
    private static final int HIT_MASK   = 1 << INDEX_BITS; // mark “key hit”

    private static int tag(long zobrist) {                 // high 32 bits
        return (int) (zobrist >>> 32);
    }

    /* ── storage ──────────────────────────────────────────────── */

    private int[]  keys;          // 32-bit tags
    private long[] data;          // packed payload

    private int  bucketCount;
    private byte age;             // 0-31, wraps mod 32

    /* one VarHandle per primitive array */
    private static final VarHandle KEY_H;   // int[ ]
    private static final VarHandle DATA_H;  // long[ ]

    static {
        try {
            KEY_H  = MethodHandles.arrayElementVarHandle(int[].class);
            DATA_H = MethodHandles.arrayElementVarHandle(long[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /* ── ctor ─────────────────────────────────────────────────── */

    public TranspositionTableImpl(int megaBytes) {
        resize(megaBytes);
    }

    /* ── life-cycle ───────────────────────────────────────────── */

    @Override
    public synchronized void resize(int megaBytes) {
        long bytesRequested = (long) megaBytes * 1_048_576;
        long bucketsWanted  = bytesRequested / BUCKET_BYTES;

        long maxBuckets = INDEX_MASK / ENTRIES_PER_BUCKET;
        if (bucketsWanted > maxBuckets)
            throw new IllegalArgumentException(
                    "TT size (" + megaBytes + " MB) exceeds 30-bit slot range");

        bucketCount = (int) bucketsWanted;
        int slots   = bucketCount * ENTRIES_PER_BUCKET;

        keys = new int [slots];
        data = new long[slots];
        clear();
    }

    @Override public void clear()      { Arrays.fill(keys, 0); Arrays.fill(data, 0); age = 0; }
    @Override public void incrementAge(){ age = (byte) ((age + 1) & (MAX_AGE - 1)); }

    /* ── hashing helpers ─────────────────────────────────────── */

    private int bucketBase(long zobrist) {
        long hi = Math.unsignedMultiplyHigh(zobrist, Integer.toUnsignedLong(bucketCount));
        return (int) (hi * ENTRIES_PER_BUCKET);            // 0   … slots-ENTRIES
    }

    /* ── core API ─────────────────────────────────────────────── */

    @Override
    public int indexFor(long zobrist) {

        int wantedTag = tag(zobrist);
        int base      = bucketBase(zobrist);

        int victim    = base;
        int worstQ    = Integer.MAX_VALUE;

        for (int i = 0; i < ENTRIES_PER_BUCKET; i++) {
            int idx   = base + i;
            int k     = (int) KEY_H.getAcquire(keys, idx);

            if (k == wantedTag) {                          // tag hit
                long e = (long) DATA_H.getVolatile(data, idx);
                if (e != 0L)                               // entry live?
                    return idx | HIT_MASK;                 // hit!
            }

            /* choose replacement victim by “quality” score */
            int q = quality((long) DATA_H.getOpaque(data, idx));
            if (q < worstQ) { worstQ = q; victim = idx; }
        }
        return victim;                                     // miss → victim slot
    }

    @Override public boolean wasHit(int slot) {
        if ((slot & HIT_MASK) == 0) return false;
        int idx = slot & INDEX_MASK;
        return ((long) DATA_H.getAcquire(data, idx)) != 0L;
    }

    @Override public long dataAt(int slot) {
        return (long) DATA_H.getAcquire(data, slot & INDEX_MASK);
    }

    @Override
    public void store(int slot, long zobrist,
                      int depth, int score, int flag,
                      int move, int staticEval,
                      boolean isPv, int ply) {

        int idx     = slot & INDEX_MASK;
        int newTag  = tag(zobrist);

        int curTag  = (int) KEY_H.getAcquire(keys, idx);
        long curDat = (long) DATA_H.getOpaque(data, idx);
        boolean hit = (curTag == newTag);

        /* quality / overwrite test (Stockfish rules) */
        int curDepth = hit ? unpackDepth(curDat) : 0;
        int ageDist  = hit ? (MAX_AGE + age - unpackAge(curDat)) & (MAX_AGE - 1) : 0;

        boolean overwrite = (flag == FLAG_EXACT)
                || (!hit)
                || (ageDist != 0)
                || (depth + (isPv ? 6 : 4) > curDepth);

        if (!overwrite) return;

        /* keep previous best-move when this store has none */
        int bestMove = (move != 0 || !hit) ? move : unpackMove(curDat);

        /* SF mate-distance encoding */
        int adjScore = score;
        if (adjScore != SCORE_NONE) {
            if (adjScore >= SCORE_TB_WIN_IN_MAX_PLY)      adjScore += ply;
            else if (adjScore <= SCORE_TB_LOSS_IN_MAX_PLY) adjScore -= ply;
        }

        long packed = pack(adjScore, staticEval, depth, flag, isPv, age, bestMove);

        DATA_H.setRelease(data, idx, packed);             // payload first
        KEY_H.setVolatile(keys, idx, newTag);             // then tag
    }

    /* ── misc helpers ─────────────────────────────────────────── */

    private int quality(long packed) {
        if (packed == 0) return Integer.MIN_VALUE;        // empty slot
        int d = unpackDepth(packed);
        int storedAge = unpackAge(packed);
        int ageDist   = (MAX_AGE + this.age - storedAge) & (MAX_AGE - 1);
        return d - 8 * ageDist;                           // SF heuristic
    }

    @Override
    public int hashfull() {
        int cnt        = 0;
        int sampleSize = Math.min(1000 * ENTRIES_PER_BUCKET, keys.length);

        for (int i = 0; i < sampleSize; i++) {
            long e = data[i];
            if (e != 0 && unpackAge(e) == age) cnt++;
        }
        return (cnt * 1000) / sampleSize;                 // per-mill
    }

    /* ── pack & unpack helpers (unchanged) ────────────────────── */

    private static long pack(int score, int eval, int depth, int flag,
                             boolean pv, int age, int move) {
        return ((long) (short) eval & 0xFFFFL)
                | (((long) (flag & 0x3)) << 16)
                | ((pv ? 1L : 0L) << 18)
                | (((long) (age & 0x1F)) << 19)
                | (((long) (depth & 0xFF)) << 24)
                | (((long) (move  & 0xFFFF)) << 32)
                | ((long) (short) score << 48);
    }
}
