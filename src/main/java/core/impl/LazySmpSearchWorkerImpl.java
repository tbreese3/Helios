// C:\dev\Helios\src\main\java\core\impl\LazySmpSearchWorkerImpl.java
package core.impl;

import core.contracts.*;
import core.records.RootMove;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static core.constants.CoreConstants.*;
import static core.contracts.PositionFactory.META;
import static core.contracts.TranspositionTable.*;

public final class LazySmpSearchWorkerImpl implements Runnable, SearchWorker {
    private final LazySmpWorkerPoolImpl pool;
    private final boolean isMainThread;
    private long[] rootBoard;
    private SearchSpec spec;
    private PositionFactory pf;
    private MoveGenerator mg;
    private Evaluator eval;
    private TimeManager timeManager;
    private InfoHandler ih;
    private TranspositionTable tt;
    private List<RootMove> rootMoves;
    private int completedDepth;
    private int selDepth;
    private long elapsedMs;
    private long nodesThisIteration;
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private static final int LIST_CAP = 256;
    private final int[][] moves = new int[MAX_PLY + 2][LIST_CAP];
    private static final int LMR_MIN_DEPTH = 3;
    private static final int LMR_MIN_MOVE_COUNT = 2;

    public LazySmpSearchWorkerImpl(boolean isMainThread, LazySmpWorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        for (int i = 0; i < frames.length; ++i) frames[i] = new SearchFrame();
    }

    @Override
    public void prepareForSearch(long[] root, SearchSpec spec, PositionFactory pf, MoveGenerator mg, Evaluator ev, TranspositionTable tt, TimeManager tm) {
        this.rootBoard = root.clone();
        this.spec = spec;
        this.pf = pf;
        this.mg = mg;
        this.eval = ev;
        this.timeManager = tm;
        this.tt = tt;
        this.rootMoves = new ArrayList<>();
        int[] legalMoves = new int[LIST_CAP];
        int numLegal = mg.kingAttacked(root, PositionFactory.whiteToMove(root[META]))
                ? mg.generateEvasions(root, legalMoves, 0)
                : mg.generateQuiets(root, legalMoves, mg.generateCaptures(root, legalMoves, 0));
        for (int i = 0; i < numLegal; i++) rootMoves.add(new RootMove(legalMoves[i]));
        this.completedDepth = 0;
    }

    @Override
    public SearchResult getSearchResult() {
        if (rootMoves == null || rootMoves.isEmpty()) return new SearchResult(0, 0, List.of(), 0, false, 0, pool.totalNodes(), elapsedMs);
        Collections.sort(rootMoves);
        RootMove best = rootMoves.get(0);
        int ponderMove = best.pv.size() > 1 ? best.pv.get(1) : 0;
        boolean mateFound = Math.abs(best.score) > SCORE_MATE_IN_MAX_PLY;
        return new SearchResult(best.move, ponderMove, best.pv, best.score, mateFound, completedDepth, pool.totalNodes(), elapsedMs);
    }

    @Override
    public void run() {
        if (isMainThread) mainThreadSearch();
        else helperThreadSearch();
    }

    private void mainThreadSearch() {
        AtomicBoolean stopFlag = pool.getStopFlag();
        long searchStartMs = System.currentTimeMillis();
        int stability = 0;
        int lastBestMove = 0;
        List<Integer> scoreHistory = new ArrayList<>();
        long[][] nodeTable = new long[64][64];
        int maxDepth = spec.depth() > 0 ? spec.depth() : MAX_PLY;

        for (int depth = 1; depth <= maxDepth; ++depth) {
            if (stopFlag.get()) break;
            selDepth = 0;
            searchRoot(depth, nodeTable);
            if (stopFlag.get()) break;

            Collections.sort(rootMoves);

            if (!rootMoves.isEmpty()) {
                RootMove best = rootMoves.get(0);
                if (best.move == lastBestMove) stability++;
                else stability = 0;
                lastBestMove = best.move;
                scoreHistory.add(best.score);
            }
            completedDepth = depth;
            elapsedMs = System.currentTimeMillis() - searchStartMs;
            if (ih != null) publishInfo(depth, elapsedMs);
            if (timeManager.isTimeUp(rootMoves, depth, stability, scoreHistory, pool.getNodesCounter(), nodeTable)) stopFlag.set(true);
            if (isMateFound()) break;
        }
        stopFlag.set(true);
    }

    private void helperThreadSearch() {
        AtomicBoolean stopFlag = pool.getStopFlag();
        for (int depth = 1; depth <= MAX_PLY; ++depth) {
            if (stopFlag.get()) break;
            searchRoot(depth, null);
        }
    }

    private void searchRoot(int depth, long[][] nodeTable) {
        for (int i = 0; i < rootMoves.size(); i++) {
            if (pool.getStopFlag().get()) return;
            RootMove rm = rootMoves.get(i);
            long nodesBefore = nodesThisIteration;
            int alpha = -SCORE_INF, beta = SCORE_INF, score;
            if (depth >= 5 && rm.score != -SCORE_INF) {
                alpha = rm.score - 15;
                beta = rm.score + 15;
            }
            while (true) {
                score = pvs(rootBoard, depth, alpha, beta, 0, true, pool.getStopFlag(), rm.move);
                if (pool.getStopFlag().get()) return;
                if (score <= alpha) { alpha = -SCORE_INF; continue; }
                if (score >= beta) { beta = SCORE_INF; continue; }
                break;
            }
            rm.score = score;
            rm.previousScore = score;
            rm.depth = selDepth;
            frames[0].set(frames[1].pv, frames[1].len, rm.move);
            rm.pv = new ArrayList<>();
            for (int k = 0; k < frames[0].len; k++) rm.pv.add(frames[0].pv[k]);
            if (nodeTable != null) nodeTable[rm.move >>> 6][rm.move & 0x3F] += (nodesThisIteration - nodesBefore);
        }
    }

    private int pvs(long[] bb, int depth, int alpha, int beta, int ply, boolean isPvNode, AtomicBoolean stop, int rootMove) {
        long[] board = bb.clone();
        pf.makeMoveInPlace(board, rootMove, mg);
        return -pvsSearch(board, depth - 1, -beta, -alpha, ply + 1, isPvNode, stop);
    }

    private int pvsSearch(long[] bb, int depth, int alpha, int beta, int ply, boolean isPvNode, AtomicBoolean stop) {
        if (depth <= 0) return quiescence(bb, alpha, beta, ply, stop);
        frames[ply].len = 0;
        if (ply > 0) {
            pool.getNodesCounter().getAndIncrement();
            nodesThisIteration++;
            if ((nodesThisIteration & 2047) == 0 && stop.get()) return 0;
            if (ply >= MAX_PLY) return eval.evaluate(bb);
        }
        final int alphaOrig = alpha;
        final long key = pf.zobrist(bb);
        TranspositionTable.Entry te = tt.probe(key);
        if (tt.wasHit(te, key) && te.getDepth() >= depth && ply > 0) {
            int eScore = te.getScore(ply);
            int eFlag = te.getBound();
            if (eFlag == FLAG_EXACT || (eFlag == FLAG_LOWER && eScore >= beta) || (eFlag == FLAG_UPPER && eScore <= alpha)) return eScore;
        }
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        if (inCheck) depth++;
        int[] list = moves[ply];
        int capturesEnd, nMoves;
        if (inCheck) nMoves = capturesEnd = mg.generateEvasions(bb, list, 0);
        else {
            capturesEnd = mg.generateCaptures(bb, list, 0);
            nMoves = mg.generateQuiets(bb, list, capturesEnd);
        }
        if (nMoves == 0) return inCheck ? -(SCORE_MATE_IN_MAX_PLY - ply) : SCORE_STALEMATE;
        if (tt.wasHit(te, key) && te.getMove() != 0) {
            int ttMove = te.getMove();
            for (int i = 0; i < nMoves; i++) if (list[i] == ttMove) { list[i] = list[0]; list[0] = ttMove; break; }
        }
        int bestScore = -SCORE_INF;
        int localBestMove = 0;
        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
            boolean isCapture = (i < capturesEnd);
            boolean isPromotion = ((mv >>> 14) & 0x3) == 1;
            boolean isTactical = isCapture || isPromotion;
            int score;
            if (i == 0) {
                score = -pvsSearch(bb, depth - 1, -beta, -alpha, ply + 1, isPvNode, stop);
            } else {
                int lmrDepth = depth - 1;
                if (depth >= LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !isTactical && !inCheck) {
                    int reduction = (int) (0.75 + Math.log(depth) * Math.log(i) / 2.0);
                    lmrDepth -= Math.max(0, reduction);
                }
                score = -pvsSearch(bb, lmrDepth, -alpha - 1, -alpha, ply + 1, false, stop);
                if (score > alpha && lmrDepth < depth - 1) score = -pvsSearch(bb, depth - 1, -alpha - 1, -alpha, ply + 1, false, stop);
                if (score > alpha && isPvNode) score = -pvsSearch(bb, depth - 1, -beta, -alpha, ply + 1, true, stop);
            }
            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;
            if (score > bestScore) {
                bestScore = score;
                localBestMove = mv;
                if (score > alpha) {
                    alpha = score;
                    frames[ply].set(frames[ply + 1].pv, frames[ply + 1].len, mv);
                    if (score >= beta) break;
                }
            }
        }
        int flag = (bestScore >= beta) ? FLAG_LOWER : (bestScore > alphaOrig) ? FLAG_EXACT : FLAG_UPPER;
        te.store(key, flag, depth, localBestMove, bestScore, SCORE_NONE, isPvNode, ply, currentAge());
        return bestScore;
    }

    private int quiescence(long[] bb, int alpha, int beta, int ply, AtomicBoolean stop) {
        if (ply >= MAX_PLY) return eval.evaluate(bb);
        nodesThisIteration++;
        if ((nodesThisIteration & 2047) == 0 && stop.get()) return 0;
        int standPat = eval.evaluate(bb);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;
        int[] list = moves[ply];
        int nMoves = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META])) ? mg.generateEvasions(bb, list, 0) : mg.generateCaptures(bb, list, 0);
        for (int i = 0; i < nMoves; i++) {
            if (!pf.makeMoveInPlace(bb, list[i], mg)) continue;
            int score = -quiescence(bb, -beta, -alpha, ply + 1, stop);
            pf.undoMoveInPlace(bb);
            if (stop.get()) return 0;
            if (score > standPat) {
                standPat = score;
                if (score >= beta) return beta;
                if (score > alpha) alpha = score;
            }
        }
        return standPat;
    }

    private void publishInfo(int depth, long elapsedMs) {
        if(rootMoves.isEmpty()) return;
        long totalNodes = pool.totalNodes();
        long nps = elapsedMs > 0 ? (totalNodes * 1000 / elapsedMs) : 0;
        RootMove best = rootMoves.get(0);
        boolean isMate = Math.abs(best.score) > SCORE_MATE_IN_MAX_PLY;
        ih.onInfo(new SearchInfo(depth, selDepth, 1, best.score, isMate, totalNodes, nps, elapsedMs, best.pv, tt.hashfull(), 0));
    }

    private boolean isMateFound() {
        return !rootMoves.isEmpty() && Math.abs(rootMoves.get(0).score) >= SCORE_MATE_IN_MAX_PLY - 100;
    }

    private byte currentAge() { return ((TranspositionTableImpl) tt).getCurrentAge(); }
    @Override public void setInfoHandler(InfoHandler ih) { this.ih = ih; }
    @Override public void terminate() {}
    @Override public void join() throws InterruptedException {}
    @Override public long getNodes() { return nodesThisIteration; }
    private static class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int len;
        void set(int[] childPv, int childLen, int move) {
            pv[0] = move;
            if (childLen > 0) System.arraycopy(childPv, 0, pv, 1, Math.min(childLen, pv.length - 1));
            len = Math.min(childLen + 1, MAX_PLY);
        }
    }
}