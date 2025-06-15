package core.impl;

import core.contracts.TranspositionTable;

/**
 * Table =  { keys[], data[] }  … three packed-long entries per bucket.          <br>
 * Memory per entry ≈ 16 bytes → bucketCount = bytes / (3 × 16).
 */
public final class TranspositionTableImpl implements TranspositionTable {

    /* Engine constants */
    private static final int ENTRIES_PER_BUCKET = 3;
    private static final int MAX_AGE            = 32;        // 5 bits
    private static final int MAX_PLY            = 127;

    /* Backing arrays */
    private static final int SCORE_MATE                = 32000;
    private static final int SCORE_MATE_IN_MAX_PLY     = SCORE_MATE - MAX_PLY;          // 31873
    private static final int SCORE_TB_WIN              = SCORE_MATE_IN_MAX_PLY - 1;     // 31872
    private static final int SCORE_TB_WIN_IN_MAX_PLY   = SCORE_TB_WIN - MAX_PLY;        // 31745
    private static final int SCORE_TB_LOSS_IN_MAX_PLY  = -SCORE_TB_WIN_IN_MAX_PLY;      //-31745
    private static final int BUCKET_BYTES = 32;
    private short[] key16;   // lower-16 bits of the Zobrist key
    private long[] data;   // packed entry (see layout in interface)

    private int   bucketCount;      // #buckets  (always ≥ 1)
    private byte  age;              // current search age 0‥31

    /* ―――――――――― life-cycle ―――――――――― */

    public TranspositionTableImpl(int megaBytes) { resize(megaBytes); }

    @Override
    public void resize(int mb) {
        long bytes = (long) mb * 1024 * 1024;
        bucketCount = (int) Math.max(1, bytes / BUCKET_BYTES);

        key16 = new short[bucketCount * ENTRIES_PER_BUCKET];
        data  = new long [key16.length];
        clear();
    }

    @Override
    public void clear() {
        age = 0;
        java.util.Arrays.fill(key16, (short) 0);
        java.util.Arrays.fill(data,  0L);
    }
    @Override
    public void close() {
        key16 = null;
        data  = null;
    }
    @Override public void nextSearch() { age = (byte) ((age + 1) & (MAX_AGE - 1)); }

    /* ―――――――――― indexing helper ―――――――――― */

    /** High 64 bits of key × bucketCount  (bucketCount < 2³²). */
    private int index(long key) {
        long buckets = Integer.toUnsignedLong(bucketCount);
        long hi;
        long loPart = (key & 0xFFFF_FFFFL) * buckets;         // 32×32 = 64
        long hiPart = (key >>> 32)       * buckets;           // ditto
        hi = hiPart + (loPart >>> 32);                        // exact high 64
        return (int) hi;          // hi is already in [0, bucketCount – 1]
    }

    /* ―――――――――― probe ―――――――――― */

    @Override
    public long probe(long zobrist) {
        int   base = index(zobrist) * ENTRIES_PER_BUCKET;
        short k16  = (short) zobrist;            // lower-16 bits

        for (int s = 0; s < ENTRIES_PER_BUCKET; ++s)
            if (key16[base + s] == k16)
                return data[base + s];

        return MISS;
    }

    /* internal: locate best slot (hit or replacement candidate) */
    private int slotFor(long zobrist) {
        int   base = index(zobrist) * ENTRIES_PER_BUCKET;
        short k16  = (short) zobrist;

        int worst  = 0;
        int worstQ = quality(data[base]);        // slot-0 as baseline

        for (int s = 0; s < ENTRIES_PER_BUCKET; ++s) {
            if (key16[base + s] == k16)
                return base + s;                 // exact hit

            int q = quality(data[base + s]);
            if (q < worstQ) { worstQ = q; worst = s; }
        }
        return base + worst;                     // victim slot
    }

    /* quality = depth – 8 × ageDistance (same as native) */
    private int quality(long packed) {
        int d = unpackDepth(packed);
        int storedAge = unpackAge(packed);
        int ageDist = (MAX_AGE + age - storedAge) & (MAX_AGE - 1);
        return d - 8 * ageDist;
    }

    /* ―――――――――― store ―――――――――― */

    @Override
    public void store(long zobrist, int depth, int score, int flag,
                      int move, int staticEval, boolean isPv, int ply) {

        int   idx  = slotFor(zobrist);
        long  cur  = data[idx];
        short k16  = (short) zobrist;
        boolean hit = key16[idx] == k16;

        /* keep old move only on an exact hit and when caller gave none */
        int moveToStore = (hit && move == 0) ? unpackMove(cur) : move;

        /* mate / TB rebasing – mirrors C++ exactly */
        if (score >= SCORE_TB_WIN_IN_MAX_PLY)
            score += ply;
        else if (score <= SCORE_TB_LOSS_IN_MAX_PLY)
            score -= ply;

        boolean replace =
                !hit
                        || flag == FLAG_EXACT
                        || unpackAge(cur) != age
                        || depth + 4 + (isPv ? 2 : 0) > unpackDepth(cur);

        if (!replace) return;

        key16[idx] = k16;
        data [idx] = pack(score, staticEval, depth, flag, isPv, age, moveToStore);
    }

    /* ―――――――――― hashfull ―――――――――― */

    @Override
    public int hashfull() {
        int cnt = 0;
        int bucketsToScan = Math.min(1000, bucketCount);

        for (int i = 0; i < bucketsToScan * ENTRIES_PER_BUCKET; ++i)
            if (unpackDepth(data[i]) != 0)
                ++cnt;

        /* C++ returns the number of *clusters* that are at least half filled */
        return cnt / ENTRIES_PER_BUCKET;          // 0 .. 1000
    }

    /* ―――――――――― packing helpers ―――――――――― */

    private static long pack(int score, int eval, int depth, int flag,
                             boolean pv, int age, int move) {

        long agePv =  (flag & 0x3L)              // bits 0-1
                | ((pv  ? 1L : 0L) << 2)     // bit  2
                | ((age & 0x1FL) << 3);      // bits 3-7

        return   (eval  & 0xFFFFL)                         //  0-15
                | (agePv            << 16)                  // 16-23
                | ((depth & 0xFFL)  << 24)                  // 24-31
                | ((move  & 0xFFFFL) << 32)                 // 32-47
                | ((score & 0xFFFFL) << 48);                // 48-63
    }
}
