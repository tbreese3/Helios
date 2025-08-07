package core.impl;

import core.contracts.TranspositionTable;
import java.util.Arrays;
import static core.constants.CoreConstants.*;

public final class TranspositionTableImpl implements TranspositionTable {

    /*
     * Each entry occupies 12 bytes, stored across two parallel arrays.
     *
     * int meta (4 bytes):
     * - 16 bits: zobrist check (upper 16 bits of the key)
     * - 5 bits:  age
     * - 11 bits: unused
     *
     * long data (8 bytes):
     * - 20 bits: move
     * - 16 bits: static eval
     * - 16 bits: score
     * - 8 bits:  depth
     * - 2 bits:  bound type
     * - 1 bit:   isPV
     * - 1 bit:   unused
     */
    private static final int BYTES_PER_ENTRY = 12;
    private static final int TT_MAX_AGE = 32;   // 5 bits for age (0-31)
    private static final int TT_AGE_WEIGHT = 8;
    private static final int TT_BUCKET_SIZE = 3; // 3-way set associative

    private long[] dataTable;
    private int[] metaTable;
    private int entryCount;
    private int bucketMask; // (entryCount / BUCKET_SIZE) - 1

    private volatile byte generation; // Current table age, kept volatile for visibility

    public TranspositionTableImpl(int megaBytes) {
        resize(megaBytes);
    }

    /* ── Bit-packing/Unpacking ──────────────────────── */
    private static int checkFromKey(long z)      { return (int) (z >>> 48); }
    private static int checkFromMeta(int meta)   { return meta & 0xFFFF; }
    private static int ageFromMeta(int meta)     { return (meta >>> 16) & 0x1F; }

    private static int   moveFromData(long data)   { return (int) (data & 0xFFFFF); }
    private static short evalFromData(long data)   { return (short) ((data >>> 20) & 0xFFFF); }
    private static short scoreFromData(long data)  { return (short) ((data >>> 36) & 0xFFFF); }
    private static int   depthFromData(long data)  { return (int) ((data >>> 52) & 0xFF); }
    private static int   boundFromData(long data)  { return (int) ((data >>> 60) & 0x3); }
    private static boolean pvFromData(long data)   { return ((data >>> 62) & 1) == 1; }

    private int getAgeDistance(int meta) {
        return (generation - ageFromMeta(meta) + TT_MAX_AGE) & (TT_MAX_AGE - 1);
    }

    private int worth(int entryIndex) {
        if (isEmpty(entryIndex)) return Integer.MIN_VALUE; // Empty slots are the best victims
        return depthFromData(dataTable[entryIndex]) - TT_AGE_WEIGHT * getAgeDistance(metaTable[entryIndex]);
    }

    private boolean isEmpty(int entryIndex) {
        // Checking only meta is sufficient if clear() is used, as 0 is an invalid check/age combo.
        return metaTable[entryIndex] == 0;
    }

    /* ── Addressing ──────────────────────────────── */
    private int bucketBase(long z) {
        // Simple direct mapping without mixing, as requested.
        int bucketIndex = (int) z & bucketMask;
        return bucketIndex * TT_BUCKET_SIZE;
    }

    /* ── Life-cycle ──────────────────────────────── */
    @Override
    public synchronized void resize(int mb) {
        long bytes = (long) mb * 1_048_576L;
        long numEntries = bytes / BYTES_PER_ENTRY;
        if (numEntries < TT_BUCKET_SIZE) {
            throw new IllegalArgumentException("TT size too small for even one bucket");
        }

        long numBuckets = numEntries / TT_BUCKET_SIZE;
        // Find the nearest power of 2 for the number of buckets
        int pow2Buckets = numBuckets > 0 ? Integer.highestOneBit((int) Math.min(numBuckets, 1 << 30)) : 0;
        if (pow2Buckets == 0 && numEntries >= TT_BUCKET_SIZE) {
            pow2Buckets = 1;
        }


        this.entryCount = pow2Buckets * TT_BUCKET_SIZE;
        if (this.entryCount == 0) {
            // Handle case where mb is too small, create minimal valid table to avoid crashes
            this.entryCount = TT_BUCKET_SIZE;
            pow2Buckets = 1;
        }

        this.bucketMask = pow2Buckets - 1;
        this.dataTable = new long[this.entryCount];
        this.metaTable = new int[this.entryCount];
        this.generation = 0;
    }

    @Override public void clear() {
        Arrays.fill(dataTable, 0L);
        Arrays.fill(metaTable, 0);
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
            if (checkFromMeta(metaTable[entryIndex]) == keyCheck) {
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
        return checkFromMeta(metaTable[entryIndex]) == checkFromKey(zobrist) && !isEmpty(entryIndex);
    }

    @Override
    public void store(int entryIndex, long zobrist, int bound, int depth, int move, int score, int staticEval, boolean isPv, int ply) {
        boolean isHit = wasHit(entryIndex, zobrist);

        // Overwrite policy
        if (isHit) {
            int currentDepth = depthFromData(dataTable[entryIndex]);
            int ageDist = getAgeDistance(metaTable[entryIndex]);
            // Always-replace scheme with a depth and PV bonus
            boolean shouldReplace = (bound == FLAG_EXACT)
                    || (ageDist != 0)
                    || (depth + (isPv ? 4 : 2) > currentDepth);
            if (!shouldReplace) return;
        }

        // Keep the existing move if the new move is null
        if (move == 0 && isHit) {
            move = moveFromData(dataTable[entryIndex]);
        }

        // Encode mate scores relative to the root
        if (score != SCORE_NONE) {
            if (score >= SCORE_TB_WIN_IN_MAX_PLY) score += ply;
            else if (score <= SCORE_TB_LOSS_IN_MAX_PLY) score -= ply;
        }

        // Pack data into the int and long
        int keyCheck = checkFromKey(zobrist);
        int newMeta = keyCheck | (generation << 16);

        long newData = (move & 0xFFFFF)
                | ((long) (staticEval & 0xFFFF) << 20)
                | ((long) (score & 0xFFFF) << 36)
                | ((long) depth << 52)
                | ((long) bound << 60)
                | ((isPv ? 1L : 0L) << 62);

        // Standard array writes
        dataTable[entryIndex] = newData;
        metaTable[entryIndex] = newMeta;
    }

    /* ── Accessors ──────────────────────────────── */
    @Override public int getDepth(int entryIndex) { return depthFromData(dataTable[entryIndex]); }
    @Override public int getBound(int entryIndex) { return boundFromData(dataTable[entryIndex]); }
    @Override public int getMove(int entryIndex) { return moveFromData(dataTable[entryIndex]); }
    @Override public int getStaticEval(int entryIndex) { return evalFromData(dataTable[entryIndex]); }
    @Override public int getRawScore(int entryIndex) { return scoreFromData(dataTable[entryIndex]); }
    @Override public boolean wasPv(int entryIndex) { return pvFromData(dataTable[entryIndex]); }

    @Override
    public int hashfull() {
        int filled = 0;
        int sampleSize = Math.min(1000, entryCount);
        if (sampleSize == 0) return 0;

        for (int i = 0; i < sampleSize; ++i) {
            // Check for a non-empty entry from the current search generation
            if (!isEmpty(i) && ageFromMeta(metaTable[i]) == generation) {
                filled++;
            }
        }
        return (filled * 1000) / sampleSize;
    }
}