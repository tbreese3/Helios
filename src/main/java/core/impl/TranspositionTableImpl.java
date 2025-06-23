// File: core/impl/TranspositionTableImpl.java
package core.impl;

import core.contracts.TranspositionTable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

import static core.constants.CoreConstants.*;

/** Lock-free, bucketed transposition table – zero object allocation. */
public final class TranspositionTableImpl implements TranspositionTable {

    /* ── addressing & meta ───────────────────────────────────────── */

    private static final int INDEX_BITS = 30;                 // 2ⁿ buckets ≤ int
    private static final int INDEX_MASK = (1 << INDEX_BITS) - 1;

    private static int tagOf(long zobrist) {                  // 16-bit tag
        return (int) zobrist & 0xFFFF;
    }

    /* ── storage ─────────────────────────────────────────────────── */

    private EntryImpl[] entries;              // flat array (bucketed view)
    private int         bucketCount;          // (#entries / ENTRIES_PER_BUCKET)
    private byte        age;                  // 0-31, wraps every root search

    /* VarHandle for cheap volatile / acquire access */
    private static final VarHandle ENTRY_H;
    static {
        ENTRY_H = MethodHandles.arrayElementVarHandle(EntryImpl[].class);
    }

    /* ── life-cycle ─────────────────────────────────────────────── */

    public TranspositionTableImpl(int megaBytes) { resize(megaBytes); }

    /** Public helper so search workers can read the live age. */
    public byte getCurrentAge() { return age; }               // ← added

    @Override public synchronized void resize(int megaBytes) {
        long bytesRequested = (long) megaBytes * 1_048_576;
        long wantedBuckets  = bytesRequested / Bucket.BYTES;
        long maxBuckets     = INDEX_MASK / ENTRIES_PER_BUCKET;
        if (wantedBuckets == 0 || wantedBuckets > maxBuckets)
            throw new IllegalArgumentException("illegal TT size: " + megaBytes + " MB");

        bucketCount = (int) wantedBuckets;
        int slots   = bucketCount * ENTRIES_PER_BUCKET;
        entries     = new EntryImpl[slots];
        for (int i = 0; i < slots; i++) entries[i] = new EntryImpl();
        clear();
    }

    @Override public void clear()        { Arrays.stream(entries).forEach(EntryImpl::clear); age = 0; }
    @Override public void incrementAge() { age = (byte) ((age + 1) & (MAX_AGE - 1)); }

    /* ── bucket addressing ───────────────────────────────────────── */

    private int bucketBase(long zKey) {
        long hi = Math.unsignedMultiplyHigh(zKey, Integer.toUnsignedLong(bucketCount));
        return (int) (hi * ENTRIES_PER_BUCKET);               // 0 … slots-ENTRIES
    }

    /* ── primary API ────────────────────────────────────────────── */

    @Override public Entry probe(long zKey) {

        int base = bucketBase(zKey);
        int tag  = tagOf(zKey);

        /* 1) exact hit? */
        for (int i = 0; i < ENTRIES_PER_BUCKET; i++) {
            EntryImpl e = (EntryImpl) ENTRY_H.getAcquire(entries, base + i);
            if (e.key16 == tag && !e.isEmpty()) return e;      // live hit
        }

        /* 2) choose worst victim in the bucket */
        EntryImpl victim = (EntryImpl) ENTRY_H.get(entries, base);
        int worstQ       = qualityOf(victim);

        for (int i = 1; i < ENTRIES_PER_BUCKET; i++) {
            EntryImpl e = (EntryImpl) ENTRY_H.get(entries, base + i);
            int q       = qualityOf(e);
            if (q < worstQ) { worstQ = q; victim = e; }
        }
        return victim;
    }

    /* ── helpers ───────────────────────────────────────────────── */

    private int qualityOf(EntryImpl e) {
        if (e.isEmpty()) return Integer.MIN_VALUE;
        int ageDist = e.getAgeDistance(age);
        return e.depth - 8 * ageDist;                          // Stockfish heuristic
    }

    @Override
    public int hashfull() {
        int filled = 0, sample = Math.min(1000 * ENTRIES_PER_BUCKET, entries.length);
        for (int i = 0; i < sample; i++) {
            EntryImpl e = entries[i];
            if (!e.isEmpty() && e.getAge() == age) ++filled;
        }
        return (filled * 1000) / sample;
    }

    /* ── internal entry impl ───────────────────────────────────── */

    private static final class EntryImpl implements TranspositionTable.Entry {

        /* layout matches Stockfish (12 bytes, padded to 16) */
        short key16;
        short staticEval;
        byte  agePvBound;
        byte  depth;
        int   move;
        short score;

        private static final byte PV_MASK    = 4;
        private static final byte BOUND_MASK = 3;

        @Override public boolean matches(long z) { return key16 == (short) z; }
        @Override public boolean isEmpty()       { return score == 0 && agePvBound == 0; }
        @Override public int  getAge()           { return (agePvBound >>> 3) & 0x1F; }
        @Override public int  getAgeDistance(byte tblAge){
            return (MAX_AGE + tblAge - getAge()) & (MAX_AGE - 1);
        }
        @Override public int  getDepth()      { return depth & 0xFF; }
        @Override public int  getBound()      { return agePvBound & BOUND_MASK; }
        @Override public int  getMove()       { return move; }
        @Override public int  getStaticEval() { return staticEval; }
        @Override public boolean wasPv()      { return (agePvBound & PV_MASK) != 0; }

        @Override public int getScore(int ply) {
            int s = score;
            if (s == SCORE_NONE) return SCORE_NONE;
            if (s >= SCORE_TB_WIN_IN_MAX_PLY)  return s - ply;
            if (s <= SCORE_TB_LOSS_IN_MAX_PLY) return s + ply;
            return s;
        }

        @Override
        public void store(long zobrist, int flag, int depth, int move,
                          int score, int staticEval, boolean isPv,
                          int ply, byte tblAge) {

            boolean hit     = matches(zobrist);
            int curDepth    = hit ? this.depth & 0xFF : 0;
            int ageDist     = hit ? getAgeDistance(tblAge) : 0;

            boolean overwrite = (flag == FLAG_EXACT)
                    || !hit
                    || ageDist != 0
                    || depth + (isPv ? 6 : 4) > curDepth;
            if (!overwrite) return;

            if (move == 0 && hit) move = this.move;           // keep old best

            if (score != SCORE_NONE) {                        // mate-distance encoding
                if (score >= SCORE_TB_WIN_IN_MAX_PLY)      score += ply;
                else if (score <= SCORE_TB_LOSS_IN_MAX_PLY) score -= ply;
            }

            this.key16      = (short) zobrist;
            this.staticEval = (short) staticEval;
            this.agePvBound = (byte) (flag | (isPv ? PV_MASK : 0) | (tblAge << 3));
            this.depth      = (byte) depth;
            this.move       = move;
            this.score      = (short) score;
        }

        void clear() { key16 = staticEval = score = 0; agePvBound = depth = 0; move = 0; }
    }

    /* dummy struct for bucket size (16 B * 3) */
    private static final class Bucket { static final int BYTES = ENTRIES_PER_BUCKET * 16; }
}
