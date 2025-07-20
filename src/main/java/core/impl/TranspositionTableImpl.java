package core.impl;

import core.contracts.TranspositionTable;
import java.util.Arrays;
import static core.constants.CoreConstants.*;

/**
 * A lock-free, 3-way set-associative transposition table.
 * Each entry is 12 bytes, split across two parallel arrays for better memory layout.
 * - int[] keys: Stores the upper 32 bits of the Zobrist key for collision checks.
 * - long[] data: Stores all other entry information packed into a single 64-bit long.
 */
public final class TranspositionTableImpl implements TranspositionTable {

    // --- New Entry Layout (12 bytes total) ---
    // int key:   [ 32-bit Zobrist check ]
    // long data: [ 16b move | 16b score | 16b staticEval | 8b depth | 5b age | 2b bound | 1b pv ]

    private static final int TT_MAX_AGE = 32;   // 5 bits for age (0-31)
    private static final int TT_AGE_WEIGHT = 8;
    private static final int TT_BUCKET_SIZE = 3; // 3-way set associative

    private int[] keys;
    private long[] data;
    private int entryCount;
    private int bucketMask; // (entryCount / BUCKET_SIZE) - 1

    private volatile byte generation; // Current table age, kept volatile for visibility

    public TranspositionTableImpl(int megaBytes) {
        resize(megaBytes);
    }

    /* ── Bit-packing/Unpacking Helpers ──────────────────────── */
    private static int checkFromKey(long z) { return (int) (z >>> 32); }

    // Unpacking from the single 'long data' entry
    private static short moveFromData(long d)       { return (short) (d); }
    private static short scoreFromData(long d)      { return (short) (d >>> 16); }
    private static short evalFromData(long d)       { return (short) (d >>> 32); }
    private static int depthFromData(long d)        { return (int)   ((d >>> 48) & 0xFF); }
    private static int ageFromMeta(long d)          { return (int)   ((d >>> 56) & 0x1F); }
    private static int boundFromMeta(long d)        { return (int)   ((d >>> 61) & 0x3); }
    private static boolean pvFromMeta(long d)       { return ((d >>> 63) & 1) == 1; }

    private int getAgeDistance(long d) {
        return (generation - ageFromMeta(d) + TT_MAX_AGE) & (TT_MAX_AGE - 1);
    }

    private int worth(int entryIndex) {
        long d = data[entryIndex];
        if (d == 0) return Integer.MIN_VALUE; // Empty slots are the best victims
        return depthFromData(d) - TT_AGE_WEIGHT * getAgeDistance(d);
    }

    private boolean isEmpty(int entryIndex) {
        // Only need to check one, since they are written together.
        return data[entryIndex] == 0;
    }

    /* ── Addressing ──────────────────────────────── */
    private static long splitmix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private int bucketBase(long z) {
        int bucketIndex = (int) splitmix64(z) & bucketMask;
        return bucketIndex * TT_BUCKET_SIZE;
    }

    /* ── Life-cycle ──────────────────────────────── */
    @Override
    public synchronized void resize(int mb) {
        long bytes = (long) mb * 1_048_576L;
        long numEntries = bytes / 12; // 12 bytes per entry (4 for key, 8 for data)
        if (numEntries < TT_BUCKET_SIZE) throw new IllegalArgumentException("TT size too small");

        long numBuckets = numEntries / TT_BUCKET_SIZE;
        int pow2Buckets = Integer.highestOneBit((int) Math.min(numBuckets, 1 << 30));

        this.entryCount = pow2Buckets * TT_BUCKET_SIZE;
        this.bucketMask = pow2Buckets - 1;
        this.keys = new int[this.entryCount];
        this.data = new long[this.entryCount];
        this.generation = 0;
    }

    @Override public void clear() {
        Arrays.fill(keys, 0);
        Arrays.fill(data, 0L);
    }
    @Override public void incrementAge() { generation = (byte) ((generation + 1) & (TT_MAX_AGE - 1)); }
    @Override public byte getCurrentAge() { return generation; }

    /* ── Core API ──────────────────────────────── */
    @Override
    public int probe(long zKey) {
        int keyCheck = checkFromKey(zKey);
        int baseIndex = bucketBase(zKey);

        // 1. Look for an exact match
        for (int i = 0; i < TT_BUCKET_SIZE; ++i) {
            int entryIndex = baseIndex + i;
            if (keys[entryIndex] == keyCheck) {
                return entryIndex;
            }
        }

        // 2. No hit, find the best victim for replacement
        int victimIndex = baseIndex;
        int worstWorth = worth(victimIndex);

        for (int i = 1; i < TT_BUCKET_SIZE; ++i) {
            int entryIndex = baseIndex + i;
            int w = worth(entryIndex);
            if (w < worstWorth) {
                worstWorth = w;
                victimIndex = entryIndex;
            }
        }
        return victimIndex;
    }

    @Override
    public boolean wasHit(int entryIndex, long zobrist) {
        return keys[entryIndex] == checkFromKey(zobrist) && !isEmpty(entryIndex);
    }

    @Override
    public void store(int entryIndex, long zobrist, int bound, int depth, int move, int score, int staticEval, boolean isPv, int ply) {
        boolean isHit = wasHit(entryIndex, zobrist);
        long oldData = isHit ? data[entryIndex] : 0;

        // Overwrite policy
        boolean replace;
        if (!isHit) {
            replace = true;
        } else {
            int ageDist = getAgeDistance(oldData);
            int currentDepth = depthFromData(oldData);
            replace = (bound == FLAG_EXACT)
                    || ageDist != 0
                    || depth + (isPv ? 6 : 4) > currentDepth;
        }

        if (!replace) return;

        // Keep the existing move if the new move is null
        if (move == 0 && isHit) {
            move = getMove(entryIndex);
        }

        // Encode mate scores
        if (score != SCORE_NONE) {
            if (score >= SCORE_TB_WIN_IN_MAX_PLY) score += ply;
            else if (score <= SCORE_TB_LOSS_IN_MAX_PLY) score -= ply;
        }

        // Pack all data into a single long
        long newData = (move & 0xFFFFL)
                | ((long) (score & 0xFFFFL) << 16)
                | ((long) (staticEval & 0xFFFFL) << 32)
                | ((long) depth << 48)
                | ((long) generation << 56)
                | ((long) bound << 61)
                | ((isPv ? 1L : 0L) << 63);

        // Atomic write: data first, then key to validate the entry
        data[entryIndex] = newData;
        keys[entryIndex] = checkFromKey(zobrist);
    }

    /* ── Accessors ──────────────────────────────── */
    @Override public int getDepth(int entryIndex) { return depthFromData(data[entryIndex]); }
    @Override public int getBound(int entryIndex) { return boundFromMeta(data[entryIndex]); }
    @Override public int getMove(int entryIndex) { return moveFromData(data[entryIndex]); }
    @Override public int getStaticEval(int entryIndex) { return evalFromData(data[entryIndex]); }
    @Override public int getRawScore(int entryIndex) { return scoreFromData(data[entryIndex]); }
    @Override public boolean wasPv(int entryIndex) { return pvFromMeta(data[entryIndex]); }

    @Override
    public int hashfull() {
        int filled = 0;
        int sampleSize = Math.min(1000, entryCount);
        if (sampleSize == 0) return 0;

        for (int i = 0; i < sampleSize; ++i) {
            if (!isEmpty(i) && ageFromMeta(data[i]) == generation) {
                filled++;
            }
        }
        return (filled * 1000) / sampleSize;
    }
}
