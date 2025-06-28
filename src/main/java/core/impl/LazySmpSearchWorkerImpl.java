// File: LazySmpSearchWorkerImpl.java
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

/**
 * A PVS search worker with Late Move Reductions (LMR).
 * This version corrects previous bugs related to PVS and move type detection,
 * ensuring an efficient and strong search.
 */
public final class LazySmpSearchWorkerImpl implements Runnable, SearchWorker {

    /* ── immutable ctor params ────────── */
    private final LazySmpWorkerPoolImpl pool;
    private final boolean isMainThread;

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

    /* scratch */
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private final int[][] moves = new int[MAX_PLY + 2][LIST_CAP];
    private long nodes;

    private int bestMove;
    private int ponderMove;
    private List<Integer> pv = new ArrayList<>();

    private long searchStartMs;
    private long optimumMs;
    private long maximumMs;

    private static final int LIST_CAP = 256;

    /* LMR Constants */
    private static final int LMR_MIN_DEPTH = 3;
    private static final int LMR_MIN_MOVE_COUNT = 2; // <-- FIX: Renamed from LMR_MIN_MOVE_NUM to match usage


    /* small helper struct to keep local PVs */
    private static final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int len;

        void set(int[] childPv, int childLen, int move) {
            pv[0] = move;
            System.arraycopy(childPv, 0, pv, 1, childLen);
            len = childLen + 1;
        }
    }

    /* ── ctor ────────── */
    public LazySmpSearchWorkerImpl(boolean isMainThread,
                                   LazySmpWorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
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

        this.rootBoard = root.clone();
        this.spec = spec;
        this.pf = pf;
        this.mg = mg;
        this.eval = ev;
        this.tm = tm;
        this.tt = tt;

        this.optimumMs = pool.getOptimumMs();
        this.maximumMs = pool.getMaximumMs();

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

        final long[] board = rootBoard;
        final int maxDepth = spec.depth() > 0 ? spec.depth() : 64;

        for (int depth = 1; depth <= maxDepth; ++depth) {
            nodes = 0;
            if (stopFlag.get()) break;

            int score = pvs(board, depth, -SCORE_INF, SCORE_INF, 0, true, stopFlag);

            if (stopFlag.get() || frames[0].len == 0) break;

            lastScore = score;
            mateScore = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY - 100;
            elapsedMs = System.currentTimeMillis() - searchStartMs;
            completedDepth = depth;

            pv = new ArrayList<>(frames[0].len);
            for (int i = 0; i < frames[0].len; ++i)
                pv.add(frames[0].pv[i]);

            bestMove = pv.isEmpty() ? 0 : pv.get(0);
            ponderMove = pv.size() > 1 ? pv.get(1) : 0;

            pool.report(this);

            if (isMainThread && ih != null) {
                long totNodes = pool.nodes.get();
                long ms = Math.max(1, elapsedMs);
                long nps = totNodes * 1000 / ms;

                ih.onInfo(new SearchInfo(
                        depth, 0, 1,
                        score, mateScore,
                        totNodes, nps, ms,
                        pv,
                        tt.hashfull(),
                        0));
            }

            if (mateScore) break;
            long now = System.currentTimeMillis();
            if (now - searchStartMs >= maximumMs) stopFlag.set(true);
            else if (now - searchStartMs >= optimumMs) stopFlag.set(true);
        }
    }

    public long getNodes() {
        return nodes;
    }

    /* ═════════════════════ Principal Variation Search (PVS) + LMR ════════════════════════ */

    private int pvs(long[] bb, int depth, int alpha, int beta, int ply, boolean isPvNode, AtomicBoolean stop) {
        if (depth <= 0) {
            return quiescence(bb, alpha, beta, ply, stop);
        }

        frames[ply].len = 0;
        if (ply > 0) {
            nodes++;
            if ((nodes & 2047) == 0 && timeUp(stop, ply)) return 0;
            if (ply >= MAX_PLY) return eval.evaluate(bb);
        }

        final int alphaOrig = alpha;
        final long key = pf.zobrist(bb);

        TranspositionTable.Entry te = tt.probe(key);
        if (tt.wasHit(te, key) && te.getDepth() >= depth && ply > 0) {
            int eScore = te.getScore(ply);
            int eFlag = te.getBound();

            if (eFlag == TranspositionTable.FLAG_EXACT ||
                    (eFlag == TranspositionTable.FLAG_LOWER && eScore >= beta) ||
                    (eFlag == TranspositionTable.FLAG_UPPER && eScore <= alpha)) {
                return eScore;
            }
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[PositionFactory.META]));
        if (inCheck) {
            depth++;
        }

        /* ─── move generation ─── */
        int[] list = moves[ply];
        int capturesEnd;
        int nMoves;
        if (inCheck) {
            nMoves = mg.generateEvasions(bb, list, 0);
            capturesEnd = nMoves; // Treat all evasions as tactical
        } else {
            capturesEnd = mg.generateCaptures(bb, list, 0);
            nMoves = mg.generateQuiets(bb, list, capturesEnd);
        }

        if (nMoves == 0) {
            return inCheck ? -(SCORE_MATE_IN_MAX_PLY - ply) : SCORE_STALEMATE;
        }

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

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];

            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            boolean isCapture = (i < capturesEnd); // Captures/Evasions are at the start of the list
            boolean isPromotion = ((mv >>> 14) & 0x3) == 1;
            boolean isTactical = isCapture || isPromotion;

            int score;
            if (i == 0) { // First move: Full PV Search
                score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1, true, stop);
            } else {
                int lmrDepth = depth - 1;
                // Apply LMR for quiet moves
                if (depth >= LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !isTactical && !inCheck) {
                    int reduction = (int) (0.75 + Math.log(depth) * Math.log(i) / 2.0);
                    lmrDepth -= Math.max(0, reduction);
                }

                // Search with a null window (Zero Window Search)
                score = -pvs(bb, lmrDepth, -alpha - 1, -alpha, ply + 1, false, stop);

                // If the reduced search was promising, re-search at full depth
                if (score > alpha && lmrDepth < depth - 1) {
                    score = -pvs(bb, depth - 1, -alpha - 1, -alpha, ply + 1, false, stop);
                }

                // If ZWS still fails high and we are in a PV node, do a full re-search
                if (score > alpha && isPvNode) {
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1, true, stop);
                }
            }

            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;

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

        /* ─── store in TT ─── */
        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestScore > alphaOrig) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        te.store(key, flag, depth, localBestMove, bestScore, SCORE_NONE, isPvNode, ply, currentAge());

        return bestScore;
    }

    /* ─────────────────────────────────────────────────────────────── */

    private int quiescence(long[] bb, int alpha, int beta, int ply, AtomicBoolean stop) {

        if (ply >= MAX_PLY) return eval.evaluate(bb);

        nodes++;
        if ((nodes & 2047) == 0 && timeUp(stop, ply)) return 0;

        final long key = pf.zobrist(bb);
        TranspositionTable.Entry te = tt.probe(key);
        if (tt.wasHit(te, key)) {
            int eScore = te.getScore(ply);
            int eFlag = te.getBound();
            if (eFlag == TranspositionTable.FLAG_EXACT ||
                    (eFlag == TranspositionTable.FLAG_LOWER && eScore >= beta) ||
                    (eFlag == TranspositionTable.FLAG_UPPER && eScore <= alpha)) {
                return eScore;
            }
        }

        int standPat = eval.evaluate(bb);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        int[] list = moves[ply];
        // Generate only tactical moves unless in check
        int nMoves = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[PositionFactory.META]))
                ? mg.generateEvasions(bb, list, 0)
                : mg.generateCaptures(bb, list, 0);

        int bestScore = standPat;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            int score = -quiescence(bb, -beta, -alpha, ply + 1, stop);
            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;

            if (score > bestScore) {
                bestScore = score;
                if (score >= beta) return beta;
                if (score > alpha) alpha = score;
            }
        }

        return bestScore;
    }

    /* ─────────── misc helpers ─────────── */
    private byte currentAge() {
        return ((core.impl.TranspositionTableImpl) tt).getCurrentAge();
    }

    private boolean timeUp(AtomicBoolean stop, int ply) {
        if (stop.get()) return true;
        if (isMainThread) {
            long elapsed = System.currentTimeMillis() - searchStartMs;
            if (elapsed >= maximumMs) {
                stop.set(true);
                return true;
            }
        }
        return false;
    }

    @Override public void terminate() {}
    @Override public void join() throws InterruptedException {}
}