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
                                 Evaluator ev, TranspositionTable ignored, // kept in signature, ignored
                                 TimeManager tm) {
        this.rootBoard = bb.clone();
        this.spec      = spec;
        this.pf        = pf;
        this.mg        = mg;
        this.eval      = ev;
        this.tm        = tm;
        this.optimumMs = ((LazySmpWorkerPoolImpl) pool).getOptimumMs();
        this.maximumMs = ((LazySmpWorkerPoolImpl) pool).getMaximumMs();
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

                ih.onInfo(new SearchInfo(
                        depth, 0, 1,
                        score, mate,
                        nodesSoFar, nps, msSoFar,
                        pv,
                        0,                   // hashfull → always 0 (no TT)
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
     *  Depth-first α/β  –  NO TRANSPOSITION TABLE VERSION
     * ------------------------------------------------------------------ */
    private int alphaBeta(long[] bb,
                          int depth,
                          int alpha,
                          int beta,
                          int ply,
                          AtomicBoolean stop)
    {
        /* 0. quick periodic wall-clock check (every 128 nodes) */
        nodes++;
        if ((nodes & 127) == 0 && timeUp(stop, ply, /*seen*/ 0))
            return (ply == 0 ? 0 : alpha);              // root returns “0”, interior returns α

        /* 2. leaf → quiescence ------------------------------------------ */
        if (depth == 0)
            return eval.evaluate(bb);

        /* 3. move generation -------------------------------------------- */
        int[] list = moves[ply];
        boolean inCheck = PositionFactory.whiteToMove(bb[PositionFactory.META])
                ? mg.kingAttacked(bb, true)
                : mg.kingAttacked(bb, false);

        int nMoves = inCheck
                ? mg.generateEvasions(bb, list, 0)
                : mg.generateQuiets(bb, list,
                mg.generateCaptures(bb, list, 0));

        /* 3a. no legal moves → mate or stalemate (depth>0, so ply parity ok) */
        if (nMoves == 0)
            return inCheck ? -MATE_SCORE + ply : 0;

        /* 4. main search loop ------------------------------------------- */
        int bestScore = -MATE_SCORE;
        int bestMove  = 0;
        int legal     = 0;                               // count for mate/stalemate + soft time

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg))
                continue;                               // illegal (king in check)

            /* root: obey soft time limit *before* diving deeper           */
            if (ply == 0 && timeUp(stop, ply, legal)) {
                pf.undoMoveInPlace(bb);
                break;
            }

            legal++;

            int score = -alphaBeta(bb, depth - 1, -beta, -alpha,
                    ply + 1, stop);

            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;                   // someone else timed out

            if (score > bestScore) {
                bestScore = score;
                bestMove  = mv;

                // copy child PV up to this ply
                frames[ply].set(frames[ply + 1].pv,
                        frames[ply + 1].len,
                        mv);

                if (score > alpha) {
                    alpha = score;
                    if (alpha >= beta)               // β-cut
                        break;
                }
            }

            /* periodic soft-limit check (cheap) */
            if ((nodes & 127) == 0 && timeUp(stop, ply, legal))
                break;
        }

        /* 5. fallback when *all* generated moves were illegal ------------ */
        if (legal == 0)
            return inCheck ? -MATE_SCORE + ply : 0;

        /* 6. TT store – removed ----------------------------------------- */

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
