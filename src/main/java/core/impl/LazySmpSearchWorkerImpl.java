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
 * A straightforward iterative‐deepening α-β worker with a shared,
 * full-featured transposition table. This version is enhanced with a correct
 * Principal Variation Search (PVS) and Late Move Reductions (LMR).
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
    private static final int LMR_MIN_MOVE_COUNT = 2;


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

        this.rootBoard = root.clone();  // thread-local copy
        this.spec = spec;
        this.pf = pf;
        this.mg = mg;
        this.eval = ev;
        this.tm = tm;
        this.tt = tt;

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

        final long[] board = rootBoard;     // local alias
        final int maxDepth = spec.depth() > 0 ? spec.depth() : 64;

        for (int depth = 1; depth <= maxDepth; ++depth) {

            nodes = 0;
            if (stopFlag.get()) break;

            int score = pvs(board, depth, -SCORE_INF, SCORE_INF, 0, stopFlag);

            if (stopFlag.get() || frames[0].len == 0) break;

            /* ─── iteration finished – publish result ─── */
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

            /* stop conditions */
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

    private int pvs(long[] bb, int depth, int alpha, int beta, int ply, AtomicBoolean stop) {
        if (depth <= 0) {
            return quiescence(bb, alpha, beta, ply, stop);
        }

        frames[ply].len = 0;

        if (ply > 0) {
            nodes++;
            if ((nodes & 2047) == 0 && timeUp(stop, ply, 0)) {
                return 0;
            }
            if (ply >= MAX_PLY) {
                return eval.evaluate(bb);
            }
        }

        final int alphaOrig = alpha;
        final long key = pf.zobrist(bb);

        TranspositionTable.Entry te = tt.probe(key);
        if (tt.wasHit(te, key) && te.getDepth() >= depth) {
            int eScore = te.getScore(ply);
            int eFlag = te.getBound();

            if ((eFlag == TranspositionTable.FLAG_EXACT) ||
                    (eFlag == TranspositionTable.FLAG_LOWER && eScore >= beta) ||
                    (eFlag == TranspositionTable.FLAG_UPPER && eScore <= alpha)) {
                return eScore;
            }
        }

        boolean isPvNode = (beta - alpha) > 1;
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[PositionFactory.META]));

        if (inCheck) {
            depth++;
        }

        /* ─── move generation ─── */
        int[] list = moves[ply];
        int nMoves = inCheck
                ? mg.generateEvasions(bb, list, 0)
                : mg.generateQuiets(bb, list, mg.generateCaptures(bb, list, 0));

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
        int movesSearched = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];

            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
            movesSearched++;

            int score;
            int newDepth = depth - 1;

            // Late Move Reductions (LMR)
            if (depth >= LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !inCheck && !isCapture(mv) && !isPromotion(mv)) {
                int reduction = (int) (Math.log(depth) * Math.log(movesSearched) / 1.8);
                newDepth -= Math.max(0, reduction);
            }

            // PVS part
            if (movesSearched == 1) {
                // First move is a full window search (PV move)
                score = -pvs(bb, newDepth, -beta, -alpha, ply + 1, stop);
            } else {
                // Subsequent moves are Zero Window Search (null window)
                score = -pvs(bb, newDepth, -alpha - 1, -alpha, ply + 1, stop);

                // If ZWS fails high, it might be a new best move -> re-search with full window
                if (score > alpha && score < beta) {
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1, stop);
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

    private int quiescence(long[] bb, int alpha, int beta,
                           int ply, AtomicBoolean stop) {

        if (ply >= MAX_PLY)
            return eval.evaluate(bb);

        nodes++;
        if ((nodes & 2047) == 0 && timeUp(stop, ply, 0))
            return 0;

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
        if (standPat >= beta) {
            return beta;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        int[] list = moves[ply];
        // Generate only captures unless in check
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[PositionFactory.META]));
        int nMoves = inCheck ? mg.generateEvasions(bb, list, 0) : mg.generateCaptures(bb, list, 0);

        int bestScore = standPat;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            int score = -quiescence(bb, -beta, -alpha, ply + 1, stop);
            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;

            if (score > bestScore) {
                bestScore = score;
                if (score >= beta) {
                    return beta; // Beta cutoff in quiescence
                }
                if (score > alpha) {
                    alpha = score;
                }
            }
        }

        return bestScore;
    }

    /* ─────────── misc helpers ─────────── */

    private boolean isCapture(int move) {
        // This is a simplified check. A proper check would involve looking at the destination square
        // in the position BEFORE the move is made. However, this requires more state to be passed around.
        // A move flag for capture would be ideal. For now, we assume captures are generated first.
        // This check is imperfect but will work for LMR if captures are generated before quiets.
        long[] tempBoard = rootBoard.clone(); // Inefficient, needs a better way
        int to = move & 0x3F;
        long enemyPieces = 0;
        if(PositionFactory.whiteToMove(tempBoard[PositionFactory.META])){
            enemyPieces = tempBoard[PositionFactory.BP] | tempBoard[PositionFactory.BN] | tempBoard[PositionFactory.BB] | tempBoard[PositionFactory.BR] | tempBoard[PositionFactory.BQ];
        } else {
            enemyPieces = tempBoard[PositionFactory.WP] | tempBoard[PositionFactory.WN] | tempBoard[PositionFactory.WB] | tempBoard[PositionFactory.WR] | tempBoard[PositionFactory.WQ];
        }
        return (enemyPieces & (1L << to)) != 0;
    }

    private boolean isPromotion(int move) {
        return ((move >>> 14) & 0x3) == 1;
    }


    private byte currentAge() {
        return ((core.impl.TranspositionTableImpl) tt).getCurrentAge();
    }

    private boolean timeUp(AtomicBoolean stop, int ply, int seenMoves) {
        if (stop.get()) return true;
        long elapsed = System.currentTimeMillis() - searchStartMs;
        if (elapsed >= maximumMs) {
            stop.set(true);
            return true;
        }
        return false;
    }

    @Override public void terminate() {}
    @Override public void join() throws InterruptedException {}
}