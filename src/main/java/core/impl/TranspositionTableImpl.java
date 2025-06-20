package core.impl;

import core.contracts.TranspositionTable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

import static core.constants.CoreConstants.*;

/**
 * A lock-free, thread-safe Transposition Table using VarHandle.
 */
public final class TranspositionTableImpl implements TranspositionTable {
    private static final int INDEX_BITS = 30;                  // usable bits
    private static final int INDEX_MASK = (1 << INDEX_BITS) - 1;   // 0x3FFF_FFFF
    private static final int HIT_MASK   = 1 << INDEX_BITS;         // 0x4000_0000

    private short[] keys;
    private long[] data;
    private int bucketCount;
    private byte age;

    // one VarHandle per primitive array
    private static final VarHandle DATA_H;   // long[]
    private static final VarHandle KEY_H;    // short[]

    static {
        try {
            DATA_H = MethodHandles.arrayElementVarHandle(long[].class);
            KEY_H  = MethodHandles.arrayElementVarHandle(short[].class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /* ―――――――――― Life-cycle ―――――――――― */

    public TranspositionTableImpl(int megaBytes) {
        resize(megaBytes);
    }

    @Override
    public synchronized void resize(int megaBytes) {

        long bytes        = (long) megaBytes * 1_048_576;   // MB → bytes
        long bucketsLong  = bytes / BUCKET_BYTES;           // desired bucket count

        /* The total number of *slots* (bucketCount × ENTRIES_PER_BUCKET)
        must fit into 30 bits. */
        long maxBuckets = INDEX_MASK / ENTRIES_PER_BUCKET;  // 30-bit ceiling
        if (bucketsLong > maxBuckets) {
            throw new IllegalArgumentException(
                    "Requested TT (" + megaBytes + " MB) exceeds 30-bit slot range.");
        }

        bucketCount  = (int) bucketsLong;                   // safe cast
        int slots    = bucketCount * ENTRIES_PER_BUCKET;    // ≤ INDEX_MASK

        keys = new short[slots];
        data = new long [slots];
        clear();
    }

    @Override
    public void clear() {
        age = 0;
        keys = new short[bucketCount * ENTRIES_PER_BUCKET];
        data  = new long [bucketCount * ENTRIES_PER_BUCKET];
    }

    @Override
    public void incrementAge() {
        age = (byte) ((age + 1) & (MAX_AGE - 1));
    }


    /* ―――――――――― Indexing ―――――――――― */
    private int getBucketBaseIndex(long key) {
        long hi = Math.unsignedMultiplyHigh(key, Integer.toUnsignedLong(bucketCount));
        return (int) (hi * ENTRIES_PER_BUCKET);
    }


    /* ―――――――――― Probe & Store  ―――――――――― */
    @Override
    public int indexFor(long zobrist) {

        int base   = getBucketBaseIndex(zobrist);
        short key  = (short) zobrist;

        int  victim = base;
        int  worstQ = Integer.MAX_VALUE;

        for (int i = 0; i < ENTRIES_PER_BUCKET; ++i) {
            int idx = base + i;

            if ((short) KEY_H.getVolatile(keys, idx) == key) {
                return idx | HIT_MASK;                   // ← hit ➜ flag set
            }
            /* track worst candidate */
            int q = quality(data[idx]);
            if (q < worstQ) { worstQ = q; victim = idx; }
        }
        return victim;                                   // ← miss ➜ flag clear
    }

    @Override
    public boolean wasHit(int slot)
    {
        return (slot & HIT_MASK) != 0;
    }

    @Override
    public long dataAt(int slot) {
        int realIdx = slot & INDEX_MASK;
        return (long) DATA_H.getAcquire(data, realIdx);   // 64-bit acquire read
    }

    @Override
    public void store(int slot, long zobrist,
                      int depth, int score, int flag,
                      int move, int staticEval, boolean isPv, int ply) {

        /* 0. strip hit-bit & fetch current entry */
        int   idx    = slot & INDEX_MASK;
        short newKey = (short) zobrist;

        short curKey  = keys[idx];
        long  curData = data[idx];                 // relaxed read is fine
        boolean isHit = (curKey == newKey);

        /* 1. helpers for overwrite test */
        int ageDist  = isHit ? (MAX_AGE + age - unpackAge(curData)) & (MAX_AGE - 1) : 0;
        int curDepth = isHit ? unpackDepth(curData) : 0;

        /* 2. exact overwrite rule */
        boolean overwrite =
                (flag == FLAG_EXACT) ||
                        (!isHit)             ||
                        (ageDist != 0)       ||
                        (depth + (isPv ? 6 : 4) > curDepth);

        if (!overwrite) return;                   // keep old entry

        /* 3. choose move */
        int bestMove = (move != 0 || !isHit) ? move : unpackMove(curData);

        /* 4. Stockfish mate-score encoding (push **away** from zero) */
        int adjScore = score;
        if (adjScore != SCORE_NONE) {
            if (adjScore >= SCORE_TB_WIN_IN_MAX_PLY)       // winning side
                adjScore += ply;
            else if (adjScore <= SCORE_TB_LOSS_IN_MAX_PLY) // losing side
                adjScore -= ply;
        }

        /* 5. pack & publish */
        long newData = pack(adjScore, staticEval, depth,
                flag, isPv, age, bestMove);

        DATA_H.setRelease(data, idx, newData);    // data first (release)
        KEY_H.setVolatile(keys, idx, newKey);     // key  then  (volatile)
    }

    private int quality(long packedData) {
        int d = unpackDepth(packedData);
        int storedAge = unpackAge(packedData);
        int ageDist = (MAX_AGE + this.age - storedAge) & (MAX_AGE - 1);
        return d - 8 * ageDist;
    }

    /* ―――――――――― Packing & Hashfull  ―――――――――― */
    /**
     * Packs entry data into a 64-bit long
     */
    private static long pack(int score, int eval, int depth, int flag,
                             boolean pv, int age, int move) {
        return ((long)(short)eval  & 0xFFFFL)
                | (((long)(flag & 0x3)) << 16)
                | ((pv ? 1L : 0L) << 18)
                | (((long)(age & 0x1F)) << 19)
                | (((long)(depth & 0xFF)) << 24)
                | (((long)(move  & 0xFFFF)) << 32)
                | ((long)(short)score << 48);
    }

    @Override
    public int hashfull() {
        int count = 0;
        int sampleSize = Math.min(1000 * ENTRIES_PER_BUCKET, keys.length);

        for (int i = 0; i < sampleSize; ++i) {
            long d = data[i];
            // We check for current age and non-empty (data is not 0).
            if (!isEmpty(d) && unpackAge(d) == this.age) {
                count++;
            }
        }

        return (count * 1000) / sampleSize;
    }
}