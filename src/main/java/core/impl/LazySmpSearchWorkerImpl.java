// File: LazySmpSearchWorkerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static core.constants.CoreConstants.*;

/**
 * A persistent search worker thread based on the model used in high-performance
 * chess engines like Stockfish. The thread remains alive in an idle loop,
 * waiting on condition variables to start or stop searching.
 */
public final class LazySmpSearchWorkerImpl implements Runnable, SearchWorker {

    /* ── immutable ctor params ────────── */
    private final LazySmpWorkerPoolImpl pool;
    final boolean isMainThread;

    /* ── threading primitives ─────────── */
    private final Lock mutex = new ReentrantLock();
    private final Condition startCondition = mutex.newCondition();
    private final Condition finishedCondition = mutex.newCondition();
    private volatile boolean searching = false;
    private volatile boolean quit = false;

    /* ── per-search state ────────── */
    private long[] rootBoard;
    private SearchSpec spec;
    private PositionFactory pf;
    private MoveGenerator mg;
    private Evaluator eval;
    private TimeManager tm;
    private InfoHandler ih;
    private TranspositionTable tt;

    private int lastScore;
    private boolean mateScore;
    private long elapsedMs;
    private int completedDepth;
    private long nodes;
    private int bestMove;
    private int ponderMove;
    private List<Integer> pv = new ArrayList<>();

    /* ── scratch buffers ─────────────── */
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private final int[][] moves = new int[MAX_PLY + 2][LIST_CAP];
    private static final int LIST_CAP = 256;

    /* LMR Constants */
    private static final int LMR_MIN_DEPTH = 3;
    private static final int LMR_MIN_MOVE_COUNT = 2;

    private static final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int len;

        void set(int[] childPv, int childLen, int move) {
            pv[0] = move;
            if (childLen > 0) {
                System.arraycopy(childPv, 0, pv, 1, childLen);
            }
            len = childLen + 1;
        }
    }

    public LazySmpSearchWorkerImpl(boolean isMainThread, LazySmpWorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        for (int i = 0; i < frames.length; ++i) {
            frames[i] = new SearchFrame();
        }
    }

    @Override
    public void run() {
        idleLoop();
    }

    private void idleLoop() {
        while (true) {
            mutex.lock();
            try {
                searching = false;
                finishedCondition.signal(); // Signal that we are done

                while (!searching && !quit) {
                    try {
                        startCondition.await();
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }

                if (quit) {
                    return;
                }
            } finally {
                mutex.unlock();
            }

            if (isMainThread) {
                mainThreadSearch();
            } else {
                search();
            }
        }
    }

    private void mainThreadSearch() {
        tt.incrementAge();
        pool.startHelpers();
        search();
        pool.waitForHelpersFinished();
        pool.finalizeSearch(getSearchResult());
    }

    private void search() {
        // Reset local counters for the new search
        this.nodes = 0;
        this.completedDepth = 0;
        this.lastScore = 0;
        this.mateScore = false;
        this.elapsedMs = 0;
        this.pv.clear();

        long searchStartMs = System.currentTimeMillis();
        int maxDepth = spec.depth() > 0 ? spec.depth() : CoreConstants.MAX_PLY;

        for (int depth = 1; depth <= maxDepth; ++depth) {
            if (pool.isStopped()) break;

            int score = pvs(rootBoard, depth, -SCORE_INF, SCORE_INF, 0);

            if (pool.isStopped()) break; // Check again, PVS might have been interrupted

            lastScore = score;
            mateScore = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY - 100;
            completedDepth = depth;

            if (frames[0].len > 0) {
                pv = new ArrayList<>(frames[0].len);
                for (int i = 0; i < frames[0].len; i++) {
                    pv.add(frames[0].pv[i]);
                }
                bestMove = pv.get(0);
                ponderMove = pv.size() > 1 ? pv.get(1) : 0;
            }

            elapsedMs = System.currentTimeMillis() - searchStartMs;

            if (isMainThread && ih != null) {
                pool.reportNodeCount(this.nodes); // Report nodes for this iteration
                long totalNodes = pool.totalNodes();
                long nps = elapsedMs > 0 ? (totalNodes * 1000) / elapsedMs : 0;

                ih.onInfo(new SearchInfo(
                        depth, completedDepth, 1, score, mateScore, totalNodes,
                        nps, elapsedMs, pv, tt.hashfull(), 0));
            }

            if (isMainThread && pool.shouldStop(searchStartMs, mateScore)) {
                pool.stopSearch();
            }
        }
    }

    private int pvs(long[] bb, int depth, int alpha, int beta, int ply) {
        if (depth <= 0) return quiescence(bb, alpha, beta, ply);

        frames[ply].len = 0;
        if (ply > 0) {
            nodes++;
            if ((nodes & 2047) == 0 && pool.isStopped()) return 0;
            if (ply >= MAX_PLY) return eval.evaluate(bb);
        }

        boolean isPvNode = (beta - alpha) > 1;
        long key = pf.zobrist(bb);
        TranspositionTable.Entry te = tt.probe(key);

        if (tt.wasHit(te, key) && te.getDepth() >= depth && ply > 0) {
            int score = te.getScore(ply);
            int flag = te.getBound();
            if (flag == TranspositionTable.FLAG_EXACT ||
                    (flag == TranspositionTable.FLAG_LOWER && score >= beta) ||
                    (flag == TranspositionTable.FLAG_UPPER && score <= alpha)) {
                return score;
            }
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[PositionFactory.META]));
        if (inCheck) depth++;

        int[] list = moves[ply];
        int nMoves;
        int capturesEnd;

        if (inCheck) {
            nMoves = mg.generateEvasions(bb, list, 0);
            capturesEnd = nMoves;
        } else {
            capturesEnd = mg.generateCaptures(bb, list, 0);
            nMoves = mg.generateQuiets(bb, list, capturesEnd);
        }

        if (nMoves == 0) return inCheck ? -(SCORE_MATE_IN_MAX_PLY - ply) : SCORE_STALEMATE;

        if (tt.wasHit(te, key) && te.getMove() != 0) {
            int ttMove = te.getMove();
            for (int i = 0; i < nMoves; i++) {
                if (list[i] == ttMove) {
                    list[i] = list[0];
                    list[0] = ttMove;
                    break;
                }
            }
        }

        int bestScore = -SCORE_INF;
        int localBestMove = 0;
        int originalAlpha = alpha;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            int score;
            if (i == 0) {
                score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
            } else {
                int lmrDepth = depth - 1;
                boolean isCapture = (i < capturesEnd);
                boolean isPromotion = ((mv >>> 14) & 0x3) == 1;
                boolean isTactical = isCapture || isPromotion;

                if (depth >= LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !isTactical && !inCheck) {
                    int reduction = (int) (0.75 + Math.log(depth) * Math.log(i) / 2.0);
                    lmrDepth -= Math.max(0, reduction);
                }

                score = -pvs(bb, lmrDepth, -alpha - 1, -alpha, ply + 1);

                if (score > alpha && lmrDepth < depth - 1) {
                    score = -pvs(bb, depth - 1, -alpha - 1, -alpha, ply + 1);
                }
                if (score > alpha && isPvNode) {
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
                }
            }

            pf.undoMoveInPlace(bb);
            if (pool.isStopped()) return 0;

            if (score > bestScore) {
                bestScore = score;
                localBestMove = mv;
                if (score > alpha) {
                    alpha = score;
                    if (isPvNode) {
                        frames[ply].set(frames[ply + 1].pv, frames[ply + 1].len, mv);
                    }
                    if (score >= beta) {
                        break; // Beta cutoff
                    }
                }
            }
        }

        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestScore > originalAlpha) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;
        te.store(key, flag, depth, localBestMove, bestScore, SCORE_NONE, isPvNode, ply, tt.getCurrentAge());

        return bestScore;
    }

    private int quiescence(long[] bb, int alpha, int beta, int ply) {
        if (ply >= MAX_PLY) return eval.evaluate(bb);

        nodes++;
        if ((nodes & 2047) == 0 && pool.isStopped()) return 0;

        int standPat = eval.evaluate(bb);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        int[] list = moves[ply];
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[PositionFactory.META]));
        int nMoves = inCheck ? mg.generateEvasions(bb, list, 0) : mg.generateCaptures(bb, list, 0);

        int bestScore = standPat;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            int score = -quiescence(bb, -beta, -alpha, ply + 1);
            pf.undoMoveInPlace(bb);
            if (pool.isStopped()) return 0;

            if (score > bestScore) {
                bestScore = score;
                if (score >= beta) return beta;
                if (score > alpha) alpha = score;
            }
        }

        return bestScore;
    }

    @Override
    public void prepareForSearch(long[] root, SearchSpec s, PositionFactory p, MoveGenerator m, Evaluator e, TranspositionTable t, TimeManager timeMgr) {
        this.rootBoard = root.clone();
        this.spec = s;
        this.pf = p;
        this.mg = m;
        this.eval = e;
        this.tt = t;
        this.tm = timeMgr;
    }

    public void startWorkerSearch() {
        mutex.lock();
        try {
            searching = true;
            startCondition.signal();
        } finally {
            mutex.unlock();
        }
    }

    public void waitWorkerFinished() {
        mutex.lock();
        try {
            while (searching) {
                try {
                    finishedCondition.await();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        } finally {
            mutex.unlock();
        }
    }

    @Override public void setInfoHandler(InfoHandler handler) { this.ih = handler; }
    @Override public SearchResult getSearchResult() {
        return new SearchResult(bestMove, ponderMove, pv, lastScore, mateScore, completedDepth, nodes, elapsedMs);
    }
    @Override public long getNodes() { return nodes; }
    @Override public void terminate() {
        mutex.lock();
        try {
            quit = true;
            searching = true; // To unblock the idle loop wait
            startCondition.signal();
        } finally {
            mutex.unlock();
        }
    }
    @Override public void join() throws InterruptedException { /* Handled by pool */ }
}