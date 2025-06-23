package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static core.constants.CoreConstants.*;
import static core.impl.PreCompTables.RED_TABLE;

/**
 * A straightforward iterative‐deepening α-β worker with a shared,
 * full-featured transposition table.  Designed for the “lazy SMP”
 * thread pool in {@link LazySmpWorkerPoolImpl}.
 *
 * <p><b>Important implementation detail:</b> the TT’s *age* must be
 * incremented exactly once per root search – the front-end already
 * does this, therefore the worker must <em>not</em> touch it here in
 * {@link #prepareForSearch}.</p>
 */
public final class LazySmpSearchWorkerImpl implements Runnable, SearchWorker {

    /* ────────── immutable ctor params ────────── */
    private final LazySmpWorkerPoolImpl pool;
    private final boolean               isMainThread;

    /* ────────── per-search state ────────── */
    private long[]               rootBoard;
    private SearchSpec           spec;
    private PositionFactory      pf;
    private MoveGenerator        mg;
    private Evaluator            eval;
    private TimeManager          tm;
    private InfoHandler          ih;
    private TranspositionTable   tt;

    private int     lastScore;
    private boolean mateScore;
    private long    elapsedMs;
    private int     completedDepth;

    /* scratch */
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private final int[][]       moves  = new int[MAX_PLY + 2][LIST_CAP];
    private long                nodes;

    private int      bestMove;
    private int      ponderMove;
    private List<Integer> pv = new ArrayList<>();

    private long searchStartMs;
    private long optimumMs;
    private long maximumMs;

    private static final int LIST_CAP = 256;

    /* small helper struct to keep local PVs */
    private static final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int   len;

        void set(int[] childPv, int childLen, int move) {
            pv[0] = move;
            System.arraycopy(childPv, 0, pv, 1, childLen);
            len = childLen + 1;
        }
    }

    /* ────────── ctor ────────── */
    public LazySmpSearchWorkerImpl(boolean isMainThread,
                                   LazySmpWorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool         = pool;
        for (int i = 0; i < frames.length; ++i)
            frames[i] = new SearchFrame();
    }

    /* ═════════════════════════ SearchWorker API ═════════════════════ */

    @Override
    public void prepareForSearch(long[] root,
                                 SearchSpec spec,
                                 PositionFactory pf,
                                 MoveGenerator mg,
                                 Evaluator ev,
                                 TranspositionTable tt,
                                 TimeManager tm) {

        this.rootBoard = root.clone();  // thread-local copy
        this.spec      = spec;
        this.pf        = pf;
        this.mg        = mg;
        this.eval      = ev;
        this.tm        = tm;
        this.tt        = tt;

        this.optimumMs = pool.getOptimumMs();
        this.maximumMs = pool.getMaximumMs();

        /* fresh per-search state */
        bestMove = ponderMove = 0;
        lastScore = 0;
        mateScore = false;
        completedDepth = 0;
        nodes = 0;
        pv.clear();
    }

    @Override public void setInfoHandler(InfoHandler ih) { this.ih = ih; }

    @Override
    public SearchResult getSearchResult() {
        return new SearchResult(
                bestMove, ponderMove, pv,
                lastScore, mateScore,
                completedDepth, nodes, elapsedMs);
    }

    /* ═════════════════════════ Runnable ═════════════════════════════ */

    @Override
    public void run() {

        searchStartMs = System.currentTimeMillis();
        AtomicBoolean stopFlag = pool.getStopFlag();

        final long[] board = rootBoard;          // local alias
        final int maxDepth = spec.depth() > 0 ? spec.depth() : 64;

        for (int depth = 1; depth <= maxDepth; ++depth) {

            nodes = 0;
            if (stopFlag.get()) break;

            int score = alphaBeta(board, depth, -SCORE_INF, SCORE_INF, 0, stopFlag);

            if (stopFlag.get() || frames[0].len == 0) break;

            /* ─── iteration finished – publish result ─── */
            lastScore      = score;
            mateScore      = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY - 100;
            elapsedMs      = System.currentTimeMillis() - searchStartMs;
            completedDepth = depth;

            pv = new ArrayList<>(frames[0].len);
            for (int i = 0; i < frames[0].len; ++i)
                pv.add(frames[0].pv[i]);

            bestMove   = pv.isEmpty() ? 0 : pv.get(0);
            ponderMove = pv.size()   > 1 ? pv.get(1) : 0;

            pool.report(this);

            if (isMainThread && ih != null) {
                long totNodes = pool.nodes.get();
                long ms       = Math.max(1, elapsedMs);
                long nps      = totNodes * 1000 / ms;

                ih.onInfo(new SearchInfo(
                        depth, 0, 1,
                        score, mateScore,
                        totNodes, nps, ms,
                        pv,
                        tt.hashfull(),
                        0));
            }

            /* stop conditions */
            if (mateScore) break;
            long now = System.currentTimeMillis();
            if (now - searchStartMs >= maximumMs)      stopFlag.set(true);
            else if (now - searchStartMs >= optimumMs) stopFlag.set(true);
        }
    }

    public long getNodes() {
        return nodes;
    }

    /* ═════════════════════ α-β + quiescence ════════════════════════ */

    /* ────────────────────────────────────────────────────────────────
     *  Principal-Variation Search  +  Late-Move-Reductions (LMR)
     * ─────────────────────────────────────────────────────────────── */
    private int alphaBeta(long[]        bb,
                          int           depth,
                          int           alpha,
                          int           beta,
                          int           ply,
                          AtomicBoolean stop)
    {
        /* 0) root of new subtree – clear any leftover PV            */
        frames[ply].len = 0;

        /* ---------------------------------------------------------- */
        /* 1) TRANSPOSITION-TABLE PROBE & EARLY CUTS                 */
        /* ---------------------------------------------------------- */
        final int   alphaOrig = alpha;
        final long  key       = pf.zobrist(bb);

        TranspositionTable.Entry te = tt.probe(key);
        boolean ttHit   = tt.wasHit(te, key);
        int     ttMove  = 0;

        if (ttHit) {
            int eDepth = te.getDepth();
            int eFlag  = te.getBound();
            int eScore = te.getScore(ply);
            ttMove     = te.getMove();

            if (eDepth >= depth) {                       // usable hit
                switch (eFlag) {
                    case TranspositionTable.FLAG_EXACT -> {
                        if (ply == 0 && ttMove != 0) {   // bring PV for GUI
                            frames[0].pv[0] = ttMove;
                            frames[0].len   = 1;
                        }
                        return eScore;
                    }
                    case TranspositionTable.FLAG_LOWER -> alpha = Math.max(alpha, eScore);
                    case TranspositionTable.FLAG_UPPER -> beta  = Math.min(beta,  eScore);
                }
                if (alpha >= beta) return eScore;
            }
        }

        /* ---------------------------------------------------------- */
        /* 2) BASE CASE – depth ≤ 0  →  quiescence search            */
        /* ---------------------------------------------------------- */
        if (depth <= 0)
            return quiescence(bb, alpha, beta, ply, stop);

        /* ---------------------------------------------------------- */
        /* 3) NODE ACCOUNTING & TIME-CHECK                           */
        /* ---------------------------------------------------------- */
        nodes++;
        if ((nodes & 127) == 0 && timeUp(stop, ply, 0))
            return alpha;                       // caller will see stop-flag

        /* ---------------------------------------------------------- */
        /* 4) MOVE GENERATION                                         */
        /* ---------------------------------------------------------- */
        int[] list      = moves[ply];
        boolean inCheck = PositionFactory.whiteToMove(bb[PositionFactory.META])
                ? mg.kingAttacked(bb, true)
                : mg.kingAttacked(bb, false);

        int moveCnt = inCheck
                ? mg.generateEvasions(bb, list, 0)
                : mg.generateQuiets(bb, list,
                mg.generateCaptures(bb, list, 0));

        /* Push TT move to front for perfect ordering                 */
        if (ttMove != 0) {
            for (int i = 0; i < moveCnt; ++i)
                if (list[i] == ttMove) {
                    list[i]  = list[0];
                    list[0]  = ttMove;
                    break;
                }
        }

        /* ---------------------------------------------------------- */
        /* 5) PVS + LMR MAIN LOOP                                    */
        /* ---------------------------------------------------------- */
        boolean   pvNode   = beta - alpha > 1;       // true if node is on the PV
        int       bestVal  = -SCORE_INF;
        int       bestMove = 0;

        for (int idx = 0; idx < moveCnt; ++idx) {

            int mv = list[idx];
            if (!pf.makeMoveInPlace(bb, mv, mg))     // illegal (left own king in check)
                continue;

            /* ---- Late-Move reduction heuristic ------------------- */
            int reduction = 0;
            if (!inCheck && !pvNode && idx > 0 && depth >= 3) {
                int d = Math.min(depth, 63);
                int m = Math.min(idx,  63);
                reduction = RED_TABLE[d][m];
            }

            int score;

            /* ---- 5a. Reduced search with narrow window ---------- */
            if (reduction > 0) {
                score = -alphaBeta(bb,
                        depth - 1 - reduction,
                        -alpha - 1, -alpha,
                        ply + 1, stop);

                /* 5b.  Re-search at full depth if it improved       */
                if (score > alpha) {
                    score = -alphaBeta(bb,
                            depth - 1,
                            -beta, -alpha,
                            ply + 1, stop);
                }
            } else {
                /* First move OR PV node → full PVS window search    */
                score = -alphaBeta(bb,
                        depth - 1,
                        -beta, -alpha,
                        ply + 1, stop);
            }

            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;

            /* ---- 5c. Update best, PV, alpha/beta ---------------- */
            if (score > bestVal) {
                bestVal  = score;
                bestMove = mv;

                /* store local PV                                   */
                frames[ply].set(frames[ply + 1].pv, frames[ply + 1].len, mv);

                if (score > alpha) {
                    alpha   = score;
                    pvNode  = true;                // now definitely a PV node
                    if (alpha >= beta) break;      // beta-cutoff
                }
            }

            /* periodic time check inside loop                      */
            if ((nodes & 127) == 0 && timeUp(stop, ply, idx + 1))
                break;
        }

        /* ---------------------------------------------------------- */
        /* 6) NO-MOVE CASE – check-mate or stalemate                 */
        /* ---------------------------------------------------------- */
        if (bestVal == -SCORE_INF)
            return inCheck ? -(SCORE_MATE_IN_MAX_PLY - ply)
                    : SCORE_STALEMATE;

        /* ---------------------------------------------------------- */
        /* 7) TRANSPOSITION-TABLE STORE                              */
        /* ---------------------------------------------------------- */
        int flag = (bestVal >= beta)        ? TranspositionTable.FLAG_LOWER
                : (bestVal <= alphaOrig)   ? TranspositionTable.FLAG_UPPER
                : TranspositionTable.FLAG_EXACT;

        te.store(key, flag, depth, bestMove, bestVal,
                SCORE_NONE, pvNode, ply, currentAge());

        return bestVal;
    }

    /* ─────────────────────────────────────────────────────────────── */

    private int quiescence(long[] bb, int alpha, int beta,
                           int ply, AtomicBoolean stop) {

        if (ply >= QSEARCH_MAX_PLY)
            return eval.evaluate(bb);

        nodes++;
        if ((nodes & 127) == 0 && timeUp(stop, ply, 0))
            return alpha;

        final long key = pf.zobrist(bb);
        TranspositionTable.Entry te = tt.probe(key);
        if (tt.wasHit(te, key) && te.getDepth() == 0) {
            int s = te.getScore(ply);
            switch (te.getBound()) {
                case TranspositionTable.FLAG_EXACT  -> { return s; }
                case TranspositionTable.FLAG_LOWER  -> alpha = Math.max(alpha, s);
                case TranspositionTable.FLAG_UPPER  -> beta  = Math.min(beta,  s);
            }
            if (alpha >= beta) return s;
        }

        int standPat = eval.evaluate(bb);
        if (standPat >= beta) {
            te.store(key, TranspositionTable.FLAG_LOWER, 0, 0,
                    standPat, SCORE_NONE, false, ply, currentAge());
            return standPat;
        }
        if (standPat > alpha) alpha = standPat;

        int[] list = moves[ply];
        boolean inCheck = PositionFactory.whiteToMove(bb[PositionFactory.META])
                ? mg.kingAttacked(bb, true)
                : mg.kingAttacked(bb, false);

        int nMoves = inCheck
                ? mg.generateEvasions(bb, list, 0)
                : mg.generateCaptures(bb, list, 0);

        int bestScore = standPat;
        int bestMove  = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            int score = -quiescence(bb, -beta, -alpha, ply + 1, stop);
            pf.undoMoveInPlace(bb);
            if (stop.get()) return alpha;

            if (score > bestScore) {
                bestScore = score;
                bestMove  = mv;
            }
            if (score >= beta) {
                te.store(key, TranspositionTable.FLAG_LOWER, 0, bestMove,
                        score, SCORE_NONE, false, ply, currentAge());
                return score;
            }
            if (score > alpha) alpha = score;
        }

        int flag = (bestScore > standPat)
                ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        te.store(key, flag, 0, bestMove, bestScore,
                SCORE_NONE, false, ply, currentAge());

        return bestScore;
    }

    /* ─────────── misc helpers ─────────── */

    private byte currentAge() {
        return ((core.impl.TranspositionTableImpl) tt).getCurrentAge();
    }

    private boolean timeUp(AtomicBoolean stop, int ply, int seenMoves) {
        if (stop.get()) return true;
        long elapsed = System.currentTimeMillis() - searchStartMs;
        if (elapsed >= maximumMs) { stop.set(true); return true; }
        return ply == 0 && seenMoves > 0 && elapsed >= optimumMs;
    }

    /* unused stubs – required by interface but handled elsewhere */
    @Override public void terminate() {}
    @Override public void join() throws InterruptedException {}
}
