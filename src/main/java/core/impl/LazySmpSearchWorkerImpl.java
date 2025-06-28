package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static core.constants.CoreConstants.*;
import static core.contracts.PositionFactory.META;
import static core.contracts.TranspositionTable.*;

/**
 * A PVS search worker with Late Move Reductions (LMR), aspiration windows,
 * and advanced time management, matching the logic of the C++ reference engine.
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
    private List<RootMove> rootMoves = new ArrayList<>();

    private long searchStartMs;
    private long optimumMs;
    private long maximumMs;
    private int searchPrevScore;

    /* Time Management State */
    private int idPrevMove = 0;
    private int idPrevScore = SCORE_NONE;
    private int searchStability = 0;

    /* Time Management Parameters from C++ Reference */
    private static final double TM_NODES_FACTOR_NOT_BEST_SCALE = 2.00;
    private static final double TM_NODES_FACTOR_BASE = 0.63;
    private static final double TM_STABILITY_FACTOR_BASE = 1.71;
    private static final double TM_STABILITY_FACTOR_DECAY = 0.08;
    private static final double TM_SCORE_LOSS_BASE = 0.86;
    private static final double TM_SCORE_LOSS_PREV_ITER_SCALE = 0.01;
    private static final double TM_SCORE_LOSS_PREV_SEARCH_SCALE = 0.025;
    private static final double TM_SCORE_FACTOR_MIN = 0.81;
    private static final double TM_SCORE_FACTOR_MAX = 1.50;

    private static final int LIST_CAP = 256;

    /* LMR Constants */
    private static final int LMR_MIN_DEPTH = 3;
    private static final int LMR_MIN_MOVE_COUNT = 2;

    private static class RootMove {
        int move;
        int score = -SCORE_INF;
        int averageScore = SCORE_NONE;
        long nodes = 0;
        List<Integer> pv = new ArrayList<>();

        RootMove(int move) {
            this.move = move;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (o instanceof Integer) return move == (Integer) o;
            RootMove other = (RootMove) o;
            return move == other.move;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(move);
        }
    }

    private static final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int len;

        void set(int[] childPv, int childLen, int move) {
            pv[0] = move;
            System.arraycopy(childPv, 0, pv, 1, childLen);
            len = childLen + 1;
        }
    }

    public LazySmpSearchWorkerImpl(boolean isMainThread, LazySmpWorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        for (int i = 0; i < frames.length; ++i)
            frames[i] = new SearchFrame();
    }

    @Override
    public void prepareForSearch(long[] root, SearchSpec spec, PositionFactory pf,
                                 MoveGenerator mg, Evaluator ev, TranspositionTable tt,
                                 TimeManager tm, LazySmpWorkerPoolImpl pool) {
        this.rootBoard = root.clone();
        this.spec = spec;
        this.pf = pf;
        this.mg = mg;
        this.eval = ev;
        this.tm = tm;
        this.tt = tt;

        this.optimumMs = pool.getOptimumMs();
        this.maximumMs = pool.getMaximumMs();
        this.searchPrevScore = pool.getSearchPrevScore();

        bestMove = ponderMove = 0;
        lastScore = 0;
        mateScore = false;
        completedDepth = 0;
        nodes = 0;
        pv.clear();

        // Initialize root moves
        rootMoves.clear();
        int[] legalMoves = new int[LIST_CAP];
        int nMoves;
        boolean isWhite = PositionFactory.whiteToMove(root[META]);
        boolean inCheck = mg.kingAttacked(root, isWhite);
        if (inCheck) {
            nMoves = mg.generateEvasions(root, legalMoves, 0);
        } else {
            nMoves = mg.generateCaptures(root, legalMoves, 0);
            nMoves = mg.generateQuiets(root, legalMoves, nMoves);
        }
        for (int i = 0; i < nMoves; i++) {
            if (pf.makeMoveInPlace(root, legalMoves[i], mg)) {
                rootMoves.add(new RootMove(legalMoves[i]));
                pf.undoMoveInPlace(root);
            }
        }

        // Reset time management state
        idPrevMove = 0;
        idPrevScore = SCORE_NONE;
        searchStability = 0;
    }

    @Override
    public void setInfoHandler(InfoHandler ih) {
        this.ih = ih;
    }

    @Override
    public SearchResult getSearchResult() {
        if (rootMoves.isEmpty()) {
            return new SearchResult(0, 0, List.of(), 0, false, 0, nodes, elapsedMs);
        }
        RootMove best = rootMoves.get(0);
        int finalPonderMove = best.pv.size() > 1 ? best.pv.get(1) : 0;
        return new SearchResult(
                best.move, finalPonderMove, best.pv,
                best.score, Math.abs(best.score) >= SCORE_MATE_IN_MAX_PLY,
                completedDepth, nodes, elapsedMs);
    }

    @Override
    public void run() {
        searchStartMs = System.currentTimeMillis();
        AtomicBoolean stopFlag = pool.getStopFlag();

        final int maxDepth = spec.depth() > 0 ? spec.depth() : 64;
        if (rootMoves.isEmpty()) return;

        for (int depth = 1; depth <= maxDepth; ++depth) {
            if (stopFlag.get()) break;

            // Aspiration Window logic from the reference engine
            int alpha = -SCORE_INF;
            int beta = SCORE_INF;
            int delta = 15; // Initial aspiration window delta

            if (depth >= ASP_WINDOW_START_DEPTH && !rootMoves.isEmpty()) {
                int prevScore = rootMoves.get(0).score;
                delta += prevScore * prevScore / 13000;
                alpha = Math.max(-SCORE_INF, prevScore - delta);
                beta = Math.min(SCORE_INF, prevScore + delta);
            }

            int failHighCount = 0;
            while (true) {
                nodes = 0; // Reset node count for the search at this depth
                int adjustedDepth = Math.max(1, depth - failHighCount);
                int score = pvs(rootBoard, adjustedDepth, alpha, beta, 0, true, stopFlag);

                if (stopFlag.get()) break;

                // Sort root moves based on the new scores
                rootMoves.sort(Comparator.comparingInt(m -> -m.score));

                if (score <= alpha) { // Fail low
                    beta = (alpha + beta) / 2;
                    alpha = Math.max(-SCORE_INF, score - delta);
                    failHighCount = 0;
                } else if (score >= beta) { // Fail high
                    beta = Math.min(SCORE_INF, score + delta);
                    if (score < 2000) failHighCount++;
                } else {
                    break; // Success
                }
                delta += delta / 3;
            }

            if (stopFlag.get() || rootMoves.isEmpty()) break;

            // Update state after completing a depth
            completedDepth = depth;
            pool.reportNodes(this.nodes);

            RootMove best = rootMoves.get(0);
            lastScore = best.score;
            bestMove = best.move;
            pv = best.pv;
            ponderMove = pv.size() > 1 ? pv.get(1) : 0;
            mateScore = Math.abs(lastScore) >= SCORE_MATE_IN_MAX_PLY;
            elapsedMs = System.currentTimeMillis() - searchStartMs;

            // Send info to GUI if main thread
            if (isMainThread && ih != null) {
                long totNodes = pool.totalNodes();
                long ms = Math.max(1, elapsedMs);
                long nps = totNodes * 1000 / ms;
                ih.onInfo(new SearchInfo(depth, 0, 1, lastScore, mateScore,
                        totNodes, nps, ms, pv, tt.hashfull(), 0));
            }

            // --- Advanced Time Management Check (port from C++) ---
            if (elapsedMs >= maximumMs) {
                stopFlag.set(true);
            } else if (spec.moveTimeMs() == 0 && !spec.infinite() && depth >= 4) {
                if (bestMove == idPrevMove) {
                    searchStability = Math.min(searchStability + 1, 8);
                } else {
                    searchStability = 0;
                }

                long bestMoveNodes = best.nodes;
                double notBestNodes = 1.0 - (bestMoveNodes / (double) Math.max(1, this.nodes));
                double nodesFactor = TM_NODES_FACTOR_BASE + notBestNodes * TM_NODES_FACTOR_NOT_BEST_SCALE;
                double stabilityFactor = TM_STABILITY_FACTOR_BASE - searchStability * TM_STABILITY_FACTOR_DECAY;

                double scoreLoss = TM_SCORE_LOSS_BASE
                        + TM_SCORE_LOSS_PREV_ITER_SCALE * (idPrevScore - lastScore)
                        + TM_SCORE_LOSS_PREV_SEARCH_SCALE * (searchPrevScore != SCORE_NONE ? (searchPrevScore - lastScore) : 0);

                double scoreFactor = Math.max(TM_SCORE_FACTOR_MIN, Math.min(TM_SCORE_FACTOR_MAX, scoreLoss));

                if (elapsedMs > stabilityFactor * nodesFactor * scoreFactor * optimumMs) {
                    stopFlag.set(true);
                }
            }

            idPrevMove = bestMove;
            idPrevScore = lastScore;

            if (mateScore) break;
        }
    }

    public long getNodes() {
        return nodes;
    }

    private int pvs(long[] bb, int depth, int alpha, int beta, int ply, boolean isPvNode, AtomicBoolean stop) {
        boolean isRoot = (ply == 0);

        if (!isRoot) {
            if (depth <= 0) return quiescence(bb, alpha, beta, ply, stop);
            if (stop.get() || (nodes & 2047) == 0 && timeUp(stop, ply)) return 0;
            if (ply >= MAX_PLY) return eval.evaluate(bb);
        }

        frames[ply].len = 0;
        nodes++;

        final int alphaOrig = alpha;
        final long key = pf.zobrist(bb);
        TranspositionTable.Entry te = tt.probe(key);

        if (tt.wasHit(te, key) && te.getDepth() >= depth && !isRoot) {
            int eScore = te.getScore(ply);
            int eFlag = te.getBound();
            if (eFlag == FLAG_EXACT ||
                    (eFlag == FLAG_LOWER && eScore >= beta) ||
                    (eFlag == FLAG_UPPER && eScore <= alpha)) {
                return eScore;
            }
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        if (inCheck) depth++;

        int[] list = moves[ply];
        int nMoves;
        if (isRoot) {
            nMoves = rootMoves.size();
        } else {
            int capturesEnd;
            if (inCheck) {
                nMoves = mg.generateEvasions(bb, list, 0);
            } else {
                capturesEnd = mg.generateCaptures(bb, list, 0);
                nMoves = mg.generateQuiets(bb, list, capturesEnd);
            }
            if (nMoves == 0) return inCheck ? -(SCORE_MATE_IN_MAX_PLY - ply) : SCORE_STALEMATE;
        }

        int bestScore = -SCORE_INF;
        int localBestMove = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = isRoot ? rootMoves.get(i).move : list[i];

            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;

            long nodesBefore = this.nodes;
            int score;
            if (i == 0 || isPvNode) { // Full PV Search
                score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1, true, stop);
            } else {
                int lmrDepth = depth - 1;
                // LMR
                if (depth >= LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !inCheck) {
                    int reduction = (int) (0.75 + Math.log(depth) * Math.log(i) / 2.0);
                    lmrDepth -= Math.max(0, reduction);
                }
                // Zero Window Search
                score = -pvs(bb, lmrDepth, -alpha - 1, -alpha, ply + 1, false, stop);
                // Re-search
                if (score > alpha && lmrDepth < depth - 1) {
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1, true, stop);
                }
            }

            if (isRoot) {
                long moveNodes = this.nodes - nodesBefore;
                RootMove rm = rootMoves.get(rootMoves.indexOf(mv));
                rm.nodes += moveNodes;
                rm.score = score;
                if (frames[1].len > 0) {
                    rm.pv.clear();
                    rm.pv.add(mv);
                    for(int k=0; k < frames[1].len; k++) {
                        rm.pv.add(frames[1].pv[k]);
                    }
                } else {
                    rm.pv = List.of(mv);
                }
            }

            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;

            if (score > bestScore) {
                bestScore = score;
                localBestMove = mv;

                if (score > alpha) {
                    alpha = score;
                    if (isPvNode && !isRoot) {
                        frames[ply].set(frames[ply + 1].pv, frames[ply + 1].len, mv);
                    }
                    if (score >= beta) {
                        break; // Beta cutoff
                    }
                }
            }
            if (isRoot) {
                rootMoves.sort(Comparator.comparingInt(m -> -m.score));
            }
        }

        if (isRoot) return bestScore;

        int flag = (bestScore >= beta) ? FLAG_LOWER
                : (bestScore > alphaOrig) ? FLAG_EXACT
                : FLAG_UPPER;
        te.store(key, flag, depth, localBestMove, bestScore, SCORE_NONE, isPvNode, ply, tt.getCurrentAge());

        return bestScore;
    }

    private int quiescence(long[] bb, int alpha, int beta, int ply, AtomicBoolean stop) {
        if (ply >= MAX_PLY) return eval.evaluate(bb);

        nodes++;
        if ((nodes & 2047) == 0 && timeUp(stop, ply)) return 0;

        final long key = pf.zobrist(bb);
        TranspositionTable.Entry te = tt.probe(key);
        if (tt.wasHit(te, key)) {
            int eScore = te.getScore(ply);
            int eFlag = te.getBound();
            if (eFlag == FLAG_EXACT ||
                    (eFlag == FLAG_LOWER && eScore >= beta) ||
                    (eFlag == FLAG_UPPER && eScore <= alpha)) {
                return eScore;
            }
        }

        int standPat = eval.evaluate(bb);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        int[] list = moves[ply];
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        int nMoves = inCheck
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

        if (!inCheck && nMoves == 0) { // Stalemate
            return SCORE_STALEMATE;
        } else if (inCheck && nMoves == 0) { // Checkmate
            return -(SCORE_MATE_IN_MAX_PLY - ply);
        }

        return bestScore;
    }

    private boolean timeUp(AtomicBoolean stop, int ply) {
        if (stop.get()) return true;
        // In Lazy SMP, only the main thread is responsible for time checks
        // based on the optimum/maximum time derived from the initial 'go' command.
        if (isMainThread) {
            long elapsed = System.currentTimeMillis() - searchStartMs;
            if (elapsed >= maximumMs) {
                stop.set(true);
                return true;
            }
        }
        return false;
    }

    @Override
    public void terminate() {
    }

    @Override
    public void join() throws InterruptedException {
    }
}