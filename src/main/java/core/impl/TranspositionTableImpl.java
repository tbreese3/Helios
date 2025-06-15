package core.impl;

import core.contracts.TranspositionTable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * A lock-free, thread-safe Transposition Table using VarHandle.
 */
public final class TranspositionTableImpl implements TranspositionTable {

    // --- VarHandle setup for atomic memory access ---
    private static final VarHandle SHORT_ARRAY_HANDLE;
    private static final VarHandle LONG_ARRAY_HANDLE;

    static {
        try {
            SHORT_ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(short[].class);
            LONG_ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
        } catch (Exception e) {
            throw new RuntimeException("VarHandle setup failed", e);
        }
    }

    private short[] key16;
    private long[] data;
    private int bucketCount;
    private byte age; // Equivalent to C++ tableAge

    /* ―――――――――― Life-cycle ―――――――――― */

    public TranspositionTableImpl(int megaBytes) {
        resize(megaBytes);
    }

    @Override
    public synchronized void resize(int mb) {
        long bytes = (long) mb * 1024 * 1024;
        bucketCount = (int) Math.max(1, bytes / BUCKET_BYTES);
        key16 = new short[bucketCount * ENTRIES_PER_BUCKET];
        data  = new long [bucketCount * ENTRIES_PER_BUCKET];
        clear();
    }

    @Override
    public void clear() {
        age = 0;
        Arrays.fill(key16, (short) 0);
        Arrays.fill(data,  0L);
    }

    @Override
    public void close() {
        key16 = null;
        data  = null;
    }

    @Override
    public void nextSearch() {
        // C++: tableAge = (tableAge+1) % MAX_AGE;
        age = (byte) ((age + 1) & (MAX_AGE - 1));
    }


    /* ―――――――――― Indexing (matches C++ getBucket) ―――――――――― */
    private int getBucketBaseIndex(long key) {
        // This is a 64x64 -> high 64 multiplication, equivalent to the C++ version
        // using unsigned __int128.
        long buckets = Integer.toUnsignedLong(bucketCount);
        long hi;
        long loPart = (key & 0xFFFF_FFFFL) * buckets;
        long hiPart = (key >>> 32)       * buckets;
        hi = hiPart + (loPart >>> 32);
        return (int) hi * ENTRIES_PER_BUCKET;
    }


    /* ―――――――――― Probe & Store (matches C++ logic) ―――――――――― */

    @Override
    public long probe(long zobrist) {
        int base = getBucketBaseIndex(zobrist);
        short k16  = (short) zobrist;

        for (int i = 0; i < ENTRIES_PER_BUCKET; ++i) {
            int idx = base + i;
            // Volatile read of key first to prevent data race with store().
            // C++: if (entries[i].matches(key)) ...
            if ((short) SHORT_ARRAY_HANDLE.getVolatile(key16, idx) == k16) {
                // If key matches, the data is consistent.
                return (long) LONG_ARRAY_HANDLE.getVolatile(data, idx);
            }
        }
        return MISS;
    }

    /**
     * Finds the best slot for a new entry, exactly replicating the C++ probe logic
     * for finding a replacement candidate.
     *
     * @return The index of the slot (either a hit or the chosen victim).
     */
    private int findSlot(long zobrist, short k16) {
        int base = getBucketBaseIndex(zobrist);
        int worstSlot = base; // Default to first entry

        // First pass: look for an exact key match, same as probe().
        for (int i = 0; i < ENTRIES_PER_BUCKET; ++i) {
            if ((short) SHORT_ARRAY_HANDLE.getVolatile(key16, base + i) == k16) {
                return base + i;
            }
        }

        // Second pass: no match found, find the worst entry to replace.
        // C++: for (int i = 1; ... ) if (qualityOf(...) < qualityOf(worst)) ...
        long worstData = (long) LONG_ARRAY_HANDLE.getVolatile(data, base);
        int worstQ = quality(worstData);

        for (int i = 1; i < ENTRIES_PER_BUCKET; ++i) {
            int currentSlot = base + i;
            long currentData = (long) LONG_ARRAY_HANDLE.getVolatile(data, currentSlot);
            int q = quality(currentData);
            if (q < worstQ) {
                worstQ = q;
                worstSlot = currentSlot;
            }
        }
        return worstSlot;
    }

    private int quality(long packedData) {
        // C++: return e->getDepth() - 8 * e->getAgeDistance();
        int d = unpackDepth(packedData);
        int storedAge = unpackAge(packedData);
        int ageDist = (MAX_AGE + this.age - storedAge) & (MAX_AGE - 1);
        return d - 8 * ageDist;
    }

    @Override
    public void store(long zobrist, int depth, int score, int flag,
                      int move, int staticEval, boolean isPv, int ply) {

        short newKey = (short) zobrist;
        int idx = findSlot(zobrist, newKey);

        short currentKey = (short) SHORT_ARRAY_HANDLE.getVolatile(key16, idx);
        long currentData = (long) LONG_ARRAY_HANDLE.getVolatile(data, idx);
        boolean isMatch = (currentKey == newKey);

        // --- Overwrite logic from C++ Entry::store() ---
        if (isMatch) {
            int ageDist = (MAX_AGE + this.age - unpackAge(currentData)) & (MAX_AGE - 1);
            int currentDepth = unpackDepth(currentData);

            // C++: if ( _bound == FLAG_EXACT || !matches(_key) || getAgeDistance() || _depth + 4 + 2*isPV > this->depth)
            // The !matches(_key) case is handled by isMatch=false.
            boolean shouldOverwrite = (flag == FLAG_EXACT)
                    || (ageDist != 0)
                    || (depth + (isPv ? 6 : 4) > currentDepth); // Simplified from +4+2*isPV

            if (!shouldOverwrite) {
                return; // Do not overwrite existing entry
            }
        }

        // C++: if (!matches(_key) || _move) this->move = _move;
        // If it's a new entry (no match), or if a valid new move is given, use the new move.
        // Otherwise, preserve the old move from the existing entry.
        int newMove = move;
        if (move == 0 && isMatch) {
            newMove = unpackMove(currentData);
        }

        // C++: Adjust mate scores based on ply
        int newScore = score;
        if (newScore != SCORE_NONE) {
            if (newScore >= SCORE_TB_WIN_IN_MAX_PLY) newScore += ply;
            else if (newScore <= SCORE_TB_LOSS_IN_MAX_PLY) newScore -= ply;
        }

        long newData = pack(newScore, staticEval, depth, flag, isPv, this.age, newMove);

        // --- Thread-safe write: data first, then key ---
        LONG_ARRAY_HANDLE.setVolatile(data, idx, newData);
        SHORT_ARRAY_HANDLE.setVolatile(key16, idx, newKey);
    }


    /* ―――――――――― Packing & Hashfull (matches C++ logic) ―――――――――― */

    /**
     * Packs entry data into a 64-bit long, matching the exact C++ layout.
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
        int sampleSize = Math.min(1000 * ENTRIES_PER_BUCKET, key16.length);

        for (int i = 0; i < sampleSize; ++i) {
            long d = (long) LONG_ARRAY_HANDLE.getVolatile(data, i);
            // C++: if (entry->getAge() == tableAge && !entry->isEmpty())
            // We check for current age and non-empty (data is not 0).
            if (!isEmpty(d) && unpackAge(d) == this.age) {
                count++;
            }
        }
        // C++ returns entryCount / EntriesPerBucket, which is permill.
        return (count * 1000) / sampleSize;
    }
}