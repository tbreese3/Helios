package core.impl;

import core.contracts.TranspositionTable;
import java.util.Arrays;
import static core.constants.CoreConstants.*;

public final class TranspositionTableImpl implements TranspositionTable {

    /* Each entry occupies 2 consecutive longs (16 bytes) in the table array.
     *
     * long 1 (data):
     * - 32 bits: zobrist check (upper 32 bits of the key)
     * - 32 bits: move
     *
     * long 2 (meta):
     * - 16 bits: static eval
     * - 16 bits: score
     * - 8 bits:  depth
     * - 5 bits:  age
     * - 2 bits:  bound type
     * - 1 bit:   isPV
     */
    private static final int LONGS_PER_ENTRY = 2;

    private static final int TT_MAX_AGE = 32;   // 5 bits for age (0-31)
    private static final int TT_AGE_WEIGHT = 8;
    private static final int TT_BUCKET_SIZE = 3; // 3-way set associative

    private long[] table;
    private int entryCount;
    private int bucketMask; // (entryCount / BUCKET_SIZE) - 1

    private volatile byte generation; // Current table age, kept volatile for visibility

    public TranspositionTableImpl(int megaBytes) {
        resize(megaBytes);
    }

    /* ── Bit-packing/Unpacking ──────────────────────── */
    private static int checkFromKey(long z)      { return (int) (z >>> 32); }
    private static int checkFromData(long data)  { return (int) (data >>> 32); }
    private static int moveFromData(long data)   { return (int) data; }

    private static short evalFromMeta(long meta) { return (short) meta; }
    private static short scoreFromMeta(long meta){ return (short) (meta >>> 16); }
    private static int depthFromMeta(long meta)  { return (int) ((meta >>> 32) & 0xFF); }
    private static int ageFromMeta(long meta)    { return (int) ((meta >>> 40) & 0x1F); }
    private static int boundFromMeta(long meta)  { return (int) ((meta >>> 45) & 0x3); }
    private static boolean pvFromMeta(long meta) { return ((meta >>> 47) & 1) == 1; }

    private int getAgeDistance(long meta) {
        return (generation - ageFromMeta(meta) + TT_MAX_AGE) & (TT_MAX_AGE - 1);
    }

    private int worth(int entryIndex) {
        long meta = table[entryIndex + 1];
        if (meta == 0) return Integer.MIN_VALUE; // Empty slots are the best victims
        return depthFromMeta(meta) - TT_AGE_WEIGHT * getAgeDistance(meta);
    }

    private boolean isEmpty(int entryIndex) {
        return table[entryIndex] == 0 && table[entryIndex + 1] == 0;
    }

    private int bucketBase(long z) {
        int bucketIndex = (int) z & bucketMask;
        return bucketIndex * TT_BUCKET_SIZE * LONGS_PER_ENTRY;
    }

    /* ── Life-cycle ──────────────────────────────── */
    @Override
    public synchronized void resize(int mb) {
        long bytes = (long) mb * 1_048_576L;
        long numEntries = bytes / (LONGS_PER_ENTRY * 8); // 16 bytes per entry
        if (numEntries < TT_BUCKET_SIZE) throw new IllegalArgumentException("TT size too small");

        long numBuckets = numEntries / TT_BUCKET_SIZE;
        int pow2Buckets = Integer.highestOneBit((int) Math.min(numBuckets, 1 << 30));

        this.entryCount = pow2Buckets * TT_BUCKET_SIZE;
        this.bucketMask = pow2Buckets - 1;
        this.table = new long[this.entryCount * LONGS_PER_ENTRY];
        this.generation = 0;
    }

    @Override public void clear() { Arrays.fill(table, 0L); }
    @Override public void incrementAge() { generation = (byte) ((generation + 1) & (TT_MAX_AGE - 1)); }
    @Override public byte getCurrentAge() { return generation; }

    /* ── Core API ──────────────────────────────── */
    @Override
    public int probe(long zKey) {
        int keyCheck = checkFromKey(zKey);
        int baseIndex = bucketBase(zKey);

        // 1. Look for an exact match
        for (int i = 0; i < TT_BUCKET_SIZE; ++i) {
            int entryIndex = baseIndex + i * LONGS_PER_ENTRY;
            if (checkFromData(table[entryIndex]) == keyCheck) {
                return entryIndex;
            }
        }

        // 2. No hit, find the best victim for replacement
        int victimIndex = baseIndex;
        int worstWorth = worth(victimIndex);

        for (int i = 1; i < TT_BUCKET_SIZE; ++i) {
            int entryIndex = baseIndex + i * LONGS_PER_ENTRY;
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
        return checkFromData(table[entryIndex]) == checkFromKey(zobrist) && !isEmpty(entryIndex);
    }

    @Override
    public void store(int entryIndex, long zobrist, int bound, int depth, int move, int score, int staticEval, boolean isPv, int ply) {
        boolean isHit = wasHit(entryIndex, zobrist);
        long oldMeta = isHit ? table[entryIndex + 1] : 0;

        // Overwrite policy
        boolean replace;
        if (!isHit) {
            replace = true;
        } else {
            int ageDist = getAgeDistance(oldMeta);
            int currentDepth = depthFromMeta(oldMeta);
            replace = (bound == FLAG_EXACT)
                    || ageDist != 0
                    || depth + (isPv ? 6 : 4) > currentDepth;
        }

        if (!replace) return;

        // Keep the existing move if the new move is null
        if (move == 0 && isHit) {
            move = moveFromData(table[entryIndex]);
        }

        // Encode mate scores
        if (score != SCORE_NONE) {
            if (score >= SCORE_TB_WIN_IN_MAX_PLY) score += ply;
            else if (score <= SCORE_TB_LOSS_IN_MAX_PLY) score -= ply;
        }

        // Pack data into two longs
        long newData = ((long) checkFromKey(zobrist) << 32) | (move & 0xFFFFFFFFL);
        long newMeta = (staticEval & 0xFFFFL)
                | ((long) (score & 0xFFFFL) << 16)
                | ((long) depth << 32)
                | ((long) generation << 40)
                | ((long) bound << 45)
                | ((isPv ? 1L : 0L) << 47);

        // Standard array write
        table[entryIndex + 1] = newMeta;
        table[entryIndex] = newData;
    }

    /* ── Accessors ──────────────────────────────── */
    @Override public int getDepth(int entryIndex) { return depthFromMeta(table[entryIndex + 1]); }
    @Override public int getBound(int entryIndex) { return boundFromMeta(table[entryIndex + 1]); }
    @Override public int getMove(int entryIndex) { return moveFromData(table[entryIndex]); }
    @Override public int getStaticEval(int entryIndex) { return evalFromMeta(table[entryIndex + 1]); }
    @Override public int getRawScore(int entryIndex) { return scoreFromMeta(table[entryIndex + 1]); }
    @Override public boolean wasPv(int entryIndex) { return pvFromMeta(table[entryIndex + 1]); }

    @Override
    public int hashfull() {
        int filled = 0;
        int sampleSize = Math.min(1000, entryCount);
        if (sampleSize == 0) return 0;

        for (int i = 0; i < sampleSize; ++i) {
            int entryIndex = i * LONGS_PER_ENTRY;
            if (!isEmpty(entryIndex) && ageFromMeta(table[entryIndex + 1]) == generation) {
                filled++;
            }
        }
        return (filled * 1000) / sampleSize;
    }
}