package core.impl;

import core.contracts.TranspositionTable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

import static core.constants.CoreConstants.*;

public final class TranspositionTableImpl implements TranspositionTable {

    /* ── addressing ─────────────────────────── */

    /** power-of-two bucket count - 1 (for a single &-mask) */
    private int bucketMask;

    private static final int TT_MAX_AGE     = 32;   // must be 2^n  – kept from before
    private static final int TT_AGE_WEIGHT  = 8;    // depth – (AGE_WEIGHT × ageDist)
    private static final int TT_BUCKET_SIZE = 3;    // 3-way set associative

    /** splitmix scrambler – keeps collisions low with Java long keys */
    private static long splitmix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z  = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z  = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
    private int bucketBase(long z) {
        int idx = (int) splitmix64(z) & bucketMask;   // 0 … bucketCount-1
        return idx * TT_BUCKET_SIZE;
    }

    private static int tagOf(long z) {   // low 16 bits
        return (int) z & 0xFFFF;
    }

    /* ── storage ────────────────────────────── */

    private Entry[] slots;                   // flat view of buckets
    private volatile byte generation;        // external get() for workers

    private static final VarHandle SLOT_H =
            MethodHandles.arrayElementVarHandle(Entry[].class);

    /* ── life-cycle ─────────────────────────── */

    public TranspositionTableImpl(int megaBytes) { resize(megaBytes); }

    @Override public synchronized void resize(int mb) {

        long bytes  = (long) mb * 1_048_576L;
        long buckets= bytes / (TT_BUCKET_SIZE * 16);          // 16 B per slot
        if (buckets < 1) throw new IllegalArgumentException("tiny TT");

        // round *down* to power-of-two
        int pow2 = Integer.highestOneBit((int) Math.min(buckets, 1 << 30));
        bucketMask = pow2 - 1;

        slots = new Entry[pow2 * TT_BUCKET_SIZE];
        for (int i = 0; i < slots.length; i++) slots[i] = new Entry();

        generation = 0;
        clear();
    }

    @Override public void clear()  { Arrays.stream(slots).forEach(Entry::wipe); }
    @Override public void incrementAge() { generation = (byte) ((generation + 1) & (TT_MAX_AGE - 1)); }
    public byte  getCurrentAge()   { return generation; }

    /* ── core API ───────────────────────────── */

    @Override
    public Entry probe(long zKey) {

        int base = bucketBase(zKey);
        int tag  = tagOf(zKey);

        /* 1 — exact hit? */
        for (int i = 0; i < TT_BUCKET_SIZE; i++) {
            Entry e = (Entry) SLOT_H.getAcquire(slots, base + i);
            if (e.tag16 == tag && !e.isEmpty()) return e;
        }

        /* 2 — pick worst replacement victim */
        Entry victim = slots[base];
        int worstQ   = victim.worth(generation);

        for (int i = 1; i < TT_BUCKET_SIZE; i++) {
            Entry e = slots[base + i];
            int q   = e.worth(generation);
            if (q < worstQ) { worstQ = q; victim = e; }
        }
        return victim;
    }

    @Override
    public int hashfull() {
        int filled = 0, sample = Math.min(1000 * TT_BUCKET_SIZE, slots.length);
        for (int i = 0; i < sample; i++)
            if (!slots[i].isEmpty() && slots[i].age() == generation) ++filled;
        return (filled * 1000) / sample;
    }

    /* ── internal entry layout (16 B) ───────── */

    private static final class Entry implements TranspositionTable.Entry {

        /* field order differs from SF/Obsidian */
        byte  depth;          //  0  : search depth 0-255
        byte  flagAgePv;      //  1  : [7-5]=age  [4]=pv  [1-0]=bound
        short tag16;          //  2-3: low bits of zobrist
        int   move;           //  4-7
        short score;          //  8-9
        short staticEval;     // 10-11
        /* 4 bytes padding from JVM object header align to 16 */

        /* bit masks */
        private static final byte BOUND_MASK = 0x3;
        private static final byte PV_MASK    = 0x10;
        private static final byte AGE_MASK   = (byte) 0xE0;

        /* ------- helpers ------- */

        int worth(byte curAge) {
            if (isEmpty()) return Integer.MIN_VALUE;
            int dist = ((curAge - age()) & (TT_MAX_AGE - 1));
            return (depth & 0xFF) - TT_AGE_WEIGHT * dist;
        }
        byte age()              { return (byte) ((flagAgePv & AGE_MASK) >>> 5); }
        void setAge(byte a)     { flagAgePv = (byte) ((flagAgePv & ~AGE_MASK) | (a << 5)); }

        /* ------- TranspositionTable.Entry impl ------- */

        @Override public boolean matches(long z) { return tag16 == (short) z; }
        @Override public boolean isEmpty()       { return score == 0 && flagAgePv == 0; }
        @Override public int     getAge()        { return age(); }
        @Override public int     getAgeDistance(byte tblAge){
            return (TT_MAX_AGE + tblAge - age()) & (TT_MAX_AGE - 1);
        }
        @Override public int     getDepth()      { return depth & 0xFF; }
        @Override public int     getBound()      { return flagAgePv & BOUND_MASK; }
        @Override public int     getMove()       { return move; }
        @Override public int     getStaticEval() { return staticEval; }
        @Override public boolean wasPv()         { return (flagAgePv & PV_MASK) != 0; }

        @Override public int getScore(int ply) {
            int s = score;
            if (s == SCORE_NONE) return SCORE_NONE;
            if (s >= SCORE_TB_WIN_IN_MAX_PLY)  return s - ply;
            if (s <= SCORE_TB_LOSS_IN_MAX_PLY) return s + ply;
            return s;
        }

        @Override
        public void store(long z, int bound, int d, int m,
                          int s, int eval, boolean pv,
                          int ply, byte tblAge) {

            boolean hit      = matches(z);
            int     curDepth = hit ? (depth & 0xFF) : 0;
            int     ageDist  = hit ? getAgeDistance(tblAge) : 0;

            /* overwrite policy is identical to SF, but clearer */
            boolean replace = (bound == FLAG_EXACT)
                    || !hit
                    || ageDist != 0
                    || d + (pv ? 6 : 4) > curDepth;
            if (!replace) return;

            if (m == 0 && hit) m = move;   // keep old best move

            /* mate-distance encoding */
            if (s != SCORE_NONE) {
                if (s >= SCORE_TB_WIN_IN_MAX_PLY)      s += ply;
                else if (s <= SCORE_TB_LOSS_IN_MAX_PLY) s -= ply;
            }

            depth       = (byte) d;
            flagAgePv   = (byte) (bound | (pv ? PV_MASK : 0));
            setAge(tblAge);
            tag16       = (short) z;
            move        = m;
            score       = (short) s;
            staticEval  = (short) eval;
        }

        void wipe() { depth = flagAgePv = 0; tag16 = 0; move = score = staticEval = 0; }
    }
}
