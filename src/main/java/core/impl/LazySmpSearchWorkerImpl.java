package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static core.constants.CoreConstants.MAX_PLY;

/**
 * Same as the original LazySmpSearchWorkerImpl, except:
 *
 *  • ALL transposition-table code has been stripped.
 *  • hash-table statistics are now reported as 0.
 *
 * This lets you benchmark the engine without any TT influence.
 */
public final class LazySmpSearchWorkerImpl implements Runnable, SearchWorker {

    /* immutable ctor params */
    private final LazySmpWorkerPoolImpl pool;   // back-link for result hand-off
    private final boolean               isMain; // thread 0 == main (only prints info)

    /* per-search state */
    private long[]           rootBoard;
    private SearchSpec       spec;
    private PositionFactory  pf;
    private MoveGenerator    mg;
    private Evaluator        eval;
    private TimeManager      tm;
    private InfoHandler      ih;
    private TranspositionTable tt;       // shared, thread-safe TT
    private int               hashFull; // last hashfull sample (for “info”)

    private int   lastScore  = 0;
    private long  elapsedMs  = 0;
    private boolean mate     = false;
    private long  searchStartMs;
    private long  optimumMs;
    private long  maximumMs;
    private static final int MATE_SCORE      = CoreConstants.SCORE_MATE;
    private static final int MATE_WIN_BOUND  = CoreConstants.SCORE_TB_WIN_IN_MAX_PLY;
    private static final int MATE_LOSS_BOUND = CoreConstants.SCORE_TB_LOSS_IN_MAX_PLY;

    /* scratch */
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    long                nodes;
    int                 completedDepth;
    private int                 bestMove;
    private int                 ponderMove;
    private List<Integer>       pv = new ArrayList<>();

    /** tiny helper to keep PVs */
    private static final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int   len;
        void set(int[] child, int clen, int move){
            pv[0] = move;
            System.arraycopy(child, 0, pv, 1, clen);
            len = clen + 1;
        }
    }

    /* move-list scratch (one list per ply) */
    private static final int LIST_CAP = 256;
    private final int[][] moves = new int[MAX_PLY + 2][LIST_CAP];

    /* ── life-cycle ─────────────────────────────────────────── */

    public LazySmpSearchWorkerImpl(boolean isMain,
                                   LazySmpWorkerPoolImpl pool) {
        this.isMain = isMain;
        this.pool   = pool;
        for (int i = 0; i < frames.length; ++i)
            frames[i] = new SearchFrame();
    }

    /** Interface: set up everything for a new search */
    @Override
    public void prepareForSearch(long[] bb, SearchSpec spec,
                                 PositionFactory pf, MoveGenerator mg,
                                 Evaluator ev, TranspositionTable tt,
                                 TimeManager tm) {

        this.rootBoard = bb.clone();
        this.spec      = spec;
        this.pf        = pf;
        this.mg        = mg;
        this.eval      = ev;
        this.tm        = tm;
        this.tt        = tt;
        this.optimumMs = ((LazySmpWorkerPoolImpl) pool).getOptimumMs();
        this.maximumMs = ((LazySmpWorkerPoolImpl) pool).getMaximumMs();

        /* ──────── NEW: hard-reset any leftovers from a previous game ──────── */
        bestMove        = 0;
        ponderMove      = 0;
        lastScore       = 0;
        mate            = false;
        completedDepth  = 0;
        nodes           = 0;
        pv.clear();
    }

    @Override public void setInfoHandler(InfoHandler ih){ this.ih = ih; }

    /* public snapshot of the latest finished iteration */
    @Override
    public SearchResult getSearchResult() {
        return new SearchResult(
                bestMove,
                ponderMove,
                pv,
                lastScore,           // real evaluation
                mate,                // mate flag
                completedDepth,
                nodes,               // counted nodes
                elapsedMs);          // elapsed time
    }

    @Override
    public void run() {

        searchStartMs = System.currentTimeMillis();
        AtomicBoolean stop  = pool.getStopFlag();
        long          t0    = searchStartMs;
        int           limit = spec.depth() > 0 ? spec.depth() : 64;

        long[] board = rootBoard;            // alias to avoid extra indirection

        for (int depth = 1; depth <= limit; ++depth) {

            nodes = 0;                       // fresh “nodes” for this depth

            /* external abort before we even start this iteration */
            if (stop.get()) break;

            int score = alphaBeta(board, depth, -32000, 32000, 0, stop);

            /* did the recursive search bail out before building a PV? */
            if (stop.get() || frames[0].len == 0)
                break;                       // keep the *previous* finished result

            /* ---------- iteration completed: update public fields ---------- */
            lastScore      = score;
            mate           = Math.abs(score) >= 32000 - 100;
            elapsedMs      = System.currentTimeMillis() - t0;
            completedDepth = depth;

            pv = new ArrayList<>(frames[0].len);
            for (int i = 0; i < frames[0].len; ++i)
                pv.add(frames[0].pv[i]);

            bestMove   = pv.isEmpty() ? 0 : pv.get(0);
            ponderMove = pv.size()  > 1 ? pv.get(1) : 0;

            pool.report(this);               // hand nodes + score to the pool

            /* optional “info …” for the main thread */
            if (isMain && ih != null) {
                long nodesSoFar = pool.nodes.get();
                long msSoFar    = System.currentTimeMillis() - t0;
                long nps        = msSoFar > 0 ? nodesSoFar * 1000 / msSoFar : 0;
                hashFull        = tt.hashfull();                 // <-- sample once

                ih.onInfo(new SearchInfo(
                        depth, 0, 1,
                        score, mate,
                        nodesSoFar, nps, msSoFar,
                        pv,
                        hashFull,                      // << was 0
                        0));
            }

            /* stop conditions ------------------------------------------------ */
            if (mate) break;                 // found a forced mate
            long elapsed = System.currentTimeMillis() - t0;

            if (elapsed >= maximumMs)                 // never overrun the hard limit
                stop.set(true);
            else if (elapsed >= optimumMs)            // stop after finishing this depth
                stop.set(true);
        }
    }

    /* ------------------------------------------------------------------
     *  Depth-first α/β – TT-aware
     * ------------------------------------------------------------------ */
    private int alphaBeta(long[] bb, int depth,
                          int alpha, int beta,
                          int ply,
                          AtomicBoolean stop) {

        /* --- ALWAYS clear the PV frame first ---------------------- */
        frames[ply].len = 0;

        final int alphaOrig = alpha;
        final long key      = pf.zobrist(bb);   // 64-bit Zobrist

        /* ── 1. Transposition-table probe ─────────────────────────── */
        int  ttSlot = tt.indexFor(key);
        boolean hit = tt.wasHit(ttSlot);
        int  ttMove = 0;

        if (hit) {
            long eData  = tt.dataAt(ttSlot);
            int  eDepth = tt.unpackDepth(eData);
            int  eFlag  = tt.unpackFlag(eData);
            int  eScore = tt.unpackScore(eData, ply);

            ttMove = tt.unpackMove(eData);

            if (eDepth >= depth) {
                switch (eFlag) {
                    case TranspositionTable.FLAG_EXACT -> {
                        /* ── NEW: make sure root has a legal PV ── */
                        if (ply == 0 && ttMove != 0) {
                            frames[ply].pv[0] = ttMove;
                            frames[ply].len   = 1;
                        }
                        return eScore;
                    }
                    case TranspositionTable.FLAG_LOWER -> alpha = Math.max(alpha, eScore);
                    case TranspositionTable.FLAG_UPPER -> beta  = Math.min(beta,  eScore);
                }
                if (alpha >= beta) {
                    return eScore;
                }
            }
        }

        /* ── 2. Usual node bookkeeping ───────────────────────────── */
        nodes++;
        if ((nodes & 127) == 0 && timeUp(stop, ply, /*seen*/ 0))
            return (ply == 0 ? 0 : alpha);      // root → 0, interior → α

        /* ── 3. Leaf? → quiescence ───────────────────────────────── */
        if (depth == 0)
            return quiescence(bb, alpha, beta, ply, stop);

        /* ── 4. Move generation ------------------------------------ */
        int[] list   = moves[ply];
        boolean inCheck = PositionFactory.whiteToMove(bb[PositionFactory.META])
                ? mg.kingAttacked(bb, true)
                : mg.kingAttacked(bb, false);

        int nMoves = inCheck
                ? mg.generateEvasions(bb, list, 0)
                : mg.generateQuiets(bb, list,
                mg.generateCaptures(bb, list, 0));

        /*  Put TT best-move to front for better ordering              */
        if (ttMove != 0) {
            for (int i = 0; i < nMoves; i++)
                if (list[i] == ttMove) { list[i] = list[0]; list[0] = ttMove; break; }
        }

        /* ── 5. DFS ------------------------------------------------- */
        int bestScore = -MATE_SCORE;
        int bestMove  = 0;
        int legal     = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;   // illegal

            if (ply == 0 && timeUp(stop, ply, legal)) { pf.undoMoveInPlace(bb); break; }
            legal++;

            int score = -alphaBeta(bb, depth - 1, -beta, -alpha, ply + 1, stop);
            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove  = mv;
                frames[ply].set(frames[ply + 1].pv, frames[ply + 1].len, mv);

                if (score > alpha) {
                    alpha = score;
                    if (alpha >= beta) break;             // β-cut
                }
            }

            if ((nodes & 127) == 0 && timeUp(stop, ply, legal)) break;
        }

        if (legal == 0) return inCheck ? -MATE_SCORE + ply : 0;

        /* ── 6. Store in TT ---------------------------------------- */
        int flag = (bestScore <= alphaOrig)             ? TranspositionTable.FLAG_UPPER
                : (bestScore >= beta)                  ? TranspositionTable.FLAG_LOWER
                : TranspositionTable.FLAG_EXACT;

        tt.store(ttSlot, key, depth, bestScore, flag,
                bestMove, CoreConstants.SCORE_NONE, /*staticEval*/ false, ply);

        return bestScore;
    }

    /* ------------------------------------------------------------------
     *  Quiescence search  –  fully TT-aware
     * ------------------------------------------------------------------ */
    private int quiescence(long[] bb,
                           int alpha,
                           int beta,
                           int ply,
                           AtomicBoolean stop) {

        /* ── 0. horizon guard & soft-time check ─────────────────────── */
        if (ply >= CoreConstants.QSEARCH_MAX_PLY)
            return eval.evaluate(bb);

        nodes++;
        if ((nodes & 127) == 0 && timeUp(stop, ply, /*seen*/ 0))
            return alpha;                                   // bail-out

        /* ── 1. TT probe (depth == 0 entry) ─────────────────────────── */
        final long key  = pf.zobrist(bb);
        int slot       = tt.indexFor(key);
        if (tt.wasHit(slot)) {
            long e   = tt.dataAt(slot);
            if (tt.unpackDepth(e) == 0) {                   // stored q-node
                int s = tt.unpackScore(e, ply);
                switch (tt.unpackFlag(e)) {
                    case TranspositionTable.FLAG_EXACT  -> { return s; }
                    case TranspositionTable.FLAG_LOWER  -> alpha = Math.max(alpha, s);
                    case TranspositionTable.FLAG_UPPER  -> beta  = Math.min(beta,  s);
                }
                if (alpha >= beta) return s;
            }
        }

        /* ── 2. stand-pat ------------------------------------------------ */
        int standPat = eval.evaluate(bb);
        if (standPat >= beta) {
            tt.store(slot, key, 0, standPat,
                    TranspositionTable.FLAG_LOWER,
                    0, CoreConstants.SCORE_NONE, false, ply);
            return standPat;                                // β-cut
        }
        if (standPat > alpha) alpha = standPat;

        /* ── 3. generate noisy moves ------------------------------------ */
        int[] list = moves[ply];
        boolean inCheck = PositionFactory.whiteToMove(bb[PositionFactory.META])
                ? mg.kingAttacked(bb, true)
                : mg.kingAttacked(bb, false);

        int nMoves = inCheck
                ? mg.generateEvasions(bb, list, 0)          // full set if in check
                : mg.generateCaptures(bb, list, 0);         // captures only

        /* ── 4. recursive search over those moves ----------------------- */
        int bestScore = standPat;
        int bestMove  = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;  // illegal

            int score = -quiescence(bb, -beta, -alpha, ply + 1, stop);
            pf.undoMoveInPlace(bb);
            if (stop.get()) return alpha;

            if (score > bestScore) {
                bestScore = score;
                bestMove  = mv;

                if (score >= beta) {                        // fail-high
                    tt.store(slot, key, 0, score,
                            TranspositionTable.FLAG_LOWER,
                            bestMove, CoreConstants.SCORE_NONE, false, ply);
                    return score;
                }
                if (score > alpha) alpha = score;
            }
        }

        /* ── 5. store as EXACT (improved) or UPPER (stand-pat) ---------- */
        int flag = (bestScore > standPat)
                ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        tt.store(slot, key, 0, bestScore, flag,
                bestMove, CoreConstants.SCORE_NONE, false, ply);

        return bestScore;
    }

    /* -------------------------------------------------------------
     *  Time guards  (unchanged – TT not involved)
     * ------------------------------------------------------------- */
    private boolean timeUp(AtomicBoolean stop, int ply, int seenMoves) {

        if (stop.get())                                   // someone else hit hard cap
            return true;

        long elapsed = System.currentTimeMillis() - searchStartMs;

        /* hard ceiling – kill search everywhere                               */
        if (elapsed >= maximumMs) {
            stop.set(true);
            return true;
        }

        /* soft limit – root thread only, after *some* work was done           */
        return (ply == 0 && seenMoves > 0 && elapsed >= optimumMs);
    }



    /* unused interface stubs */
    @Override public void terminate(){}
    @Override public void join() throws InterruptedException{}
}
