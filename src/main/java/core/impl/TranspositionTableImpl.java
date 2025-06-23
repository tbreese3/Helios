package core.impl;

import core.contracts.TranspositionTable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

import static core.constants.CoreConstants.*;
import static core.contracts.TranspositionTable.*;

/**
 * Lock-free, bucketed, 16-byte entries – inspired by Stockfish (bit-packing)
 * and Obsidian (simple replacement quality).
 *
 * Layout per entry  ─────────────────────────────────────────────────────────
 *   key16         16 b   (low 16 bits of Zobrist)                     ─┐
 *   depth8         8 b   (unsigned,   search depth – no offset)        │
 *   genBound8      8 b   (5 bits generation | 1 PV | 2 bits bound) <───┤ cache-hot
 *   move          32 b   best move (0 ⇒ none)                          │
 *   score         16 b   TT score (mate-distance encoded)              │
 *   staticEval    16 b   stand-pat evaluation                          ┘
 *
 * Total:  16 bytes – perfect for three-entry 48 byte cache-lines.
 */
public final class TranspositionTableImpl implements TranspositionTable {

    /* ── addressing ─────────────────────────────────────────────── */

    private static final int INDEX_BITS = 30;                    // up to 1 Gi buckets
    private static final int INDEX_MASK = (1 << INDEX_BITS) - 1;

    private static int tagOf(long zKey) { return (int) zKey & 0xFFFF; }

    /* ── storage ────────────────────────────────────────────────── */

    private EntryImpl[] slots;               // flat array, bucketed view
    private int         bucketCount;         // (#slots / ENTRIES_PER_BUCKET)
    private byte        generation;          // 0–31, wraps via newSearch()

    private static final VarHandle VH;       // acquire loads for lock-free reads
    static { VH = MethodHandles.arrayElementVarHandle(EntryImpl[].class); }

    /* ── ctors & life-cycle ─────────────────────────────────────── */

    public TranspositionTableImpl(int megaBytes) { resize(megaBytes); }

    @Override public synchronized void resize(int megaBytes) {
        long bytes     = (long) megaBytes * 1_048_576;
        long wantBuck  = bytes / (ENTRIES_PER_BUCKET * 16L);
        long maxBuck   = INDEX_MASK / ENTRIES_PER_BUCKET;
        if (wantBuck == 0 || wantBuck > maxBuck)
            throw new IllegalArgumentException("illegal TT size: " + megaBytes + " MB");

        bucketCount = (int) wantBuck;
        int slotsN  = bucketCount * ENTRIES_PER_BUCKET;

        slots = new EntryImpl[slotsN];
        for (int i = 0; i < slotsN; i++) slots[i] = new EntryImpl();
        clear();
    }

    @Override public void clear() { Arrays.stream(slots).forEach(EntryImpl::clear); generation = 0; }
    @Override public void newSearch()  { generation = (byte) ((generation + 1) & (MAX_GENERATION - 1)); }

    /* ── bucket math ────────────────────────────────────────────── */

    private int bucketBase(long zKey) {
        long hi = Math.unsignedMultiplyHigh(zKey, Integer.toUnsignedLong(bucketCount));
        return (int) (hi * ENTRIES_PER_BUCKET);      // 0 … slots-ENTRIES_PER_BUCKET
    }

    /* ── main API ──────────────────────────────────────────────── */

    @Override
    public Entry probe(long zKey) {
        int base = bucketBase(zKey);
        int tag  = tagOf(zKey);

        /* 1) exact hit? */
        for (int i = 0; i < ENTRIES_PER_BUCKET; i++) {
            EntryImpl e = (EntryImpl) VH.getAcquire(slots, base + i);
            if (e.key16 == tag && !e.isEmpty()) return e;
        }

        /* 2) pick the worst replacement candidate */
        EntryImpl victim = (EntryImpl) VH.get(slots, base);   // relaxed load OK
        int worstQ       = qualityOf(victim);

        for (int i = 1; i < ENTRIES_PER_BUCKET; i++) {
            EntryImpl e = (EntryImpl) VH.get(slots, base + i);
            int q       = qualityOf(e);
            if (q < worstQ) { worstQ = q; victim = e; }
        }
        return victim;
    }

    @Override
    public int hashfull() {
        int sample = Math.min(ENTRIES_PER_BUCKET * 1000, slots.length);
        int filled = 0;
        for (int i = 0; i < sample; i++) {
            EntryImpl e = slots[i];
            if (!e.isEmpty() && e.getGeneration() == generation) ++filled;
        }
        return (filled * 1000) / sample;
    }

    /* ── helpers ───────────────────────────────────────────────── */

    private int qualityOf(EntryImpl e) {
        if (e.isEmpty()) return Integer.MIN_VALUE;
        int age = e.ageDistance(generation);
        return e.getDepth() - 8 * age;
    }

    public byte getCurrentAge() {                 //  ← **new**
        return generation;
    }

    /* ── internal entry impl ──────────────────────────────────── */

    private static final class EntryImpl implements Entry {

        /* bit-packed fields (see file header) */
        short key16;
        byte  depth8;
        byte  genBound8;        // gggggPVbb (g=5 bit gen, PV=1, bb=2 bit bound)
        int   move;
        short score;
        short staticEval;

        /* bit masks */
        private static final byte BOUND_MASK = 0b00000011;
        private static final byte PV_MASK    = 0b00000100;
        private static final byte GEN_MASK   = (byte) 0b11111000;

        /* -------- interface impl -------- */

        @Override public boolean matches(long zKey) { return key16 == (short) zKey; }
        @Override public boolean isEmpty()          { return score == 0 && genBound8 == 0; }

        @Override public int getGeneration() { return (genBound8 & GEN_MASK) >>> 3; }

        @Override public int ageDistance(byte tblGen) {
            return (MAX_GENERATION + tblGen - getGeneration()) & (MAX_GENERATION - 1);
        }

        @Override public int getDepth()      { return depth8 & 0xFF; }
        @Override public int getBound()      { return genBound8 & BOUND_MASK; }
        @Override public int getMove()       { return move; }
        @Override public int getStaticEval() { return staticEval; }
        @Override public boolean wasPv()     { return (genBound8 & PV_MASK) != 0; }

        @Override public int getScore(int ply) {
            int s = score;
            if (s == SCORE_NONE) return SCORE_NONE;
            if (s >= SCORE_TB_WIN_IN_MAX_PLY)  return s - ply;
            if (s <= SCORE_TB_LOSS_IN_MAX_PLY) return s + ply;
            return s;
        }

        @Override
        public void store(long zKey, int flag, int depth, int move,
                          int score, int staticEval, boolean isPv,
                          int ply, byte tblGen) {

            boolean hit        = matches(zKey);
            int     prevDepth  = hit ? (depth8 & 0xFF) : 0;
            int     ageDist    = hit ? ageDistance(tblGen) : MAX_GENERATION; // huge if empty

            /* replacement rule (hybrid – Stockfish’s PV bonus, Obsidian’s depth bonus) */
            boolean overwrite = (flag == FLAG_EXACT)
                    || !hit
                    || ageDist != 0
                    || depth + (isPv ? 6 : 4) > prevDepth;

            if (!overwrite) return;

            if (move == 0 && hit) move = this.move;      // keep old best if none supplied

            /* mate-distance encoding before we commit */
            int encScore = score;
            if (encScore != SCORE_NONE) {
                if (encScore >= SCORE_TB_WIN_IN_MAX_PLY)  encScore += ply;
                else if (encScore <= SCORE_TB_LOSS_IN_MAX_PLY) encScore -= ply;
            }

            this.key16      = (short) zKey;
            this.depth8     = (byte) depth;
            this.genBound8  = (byte) (flag | (isPv ? PV_MASK : 0) | (tblGen << 3));
            this.move       = move;
            this.score      = (short) encScore;
            this.staticEval = (short) staticEval;
        }

        void clear() { key16 = depth8 = genBound8 = 0; move = score = staticEval = 0; }
    }
}
