package core.impl;

import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static core.constants.CoreConstants.*;
import static core.contracts.PositionFactory.*; // <-- BUG FIX: Added missing import for META, HC_MASK, etc.

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
    private TranspositionTable   tt;
    private TimeManager          tm; // Field for TimeManager
    private InfoHandler          ih; // Field for InfoHandler

    private int     lastScore;
    private boolean mateScore;
    private long    elapsedMs;
    private int     completedDepth;

    /* scratch */
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private final int[][]       moves  = new int[MAX_PLY + 2][LIST_CAP];
    private long                nodes;

    private int           bestMove;
    private int           ponderMove;
    private List<Integer> pv = new ArrayList<>();

    private long searchStartMs;
    private long optimumMs;
    private long maximumMs;

    private final long[] keyStack = new long[100 + MAX_PLY];
    private int keyStackHead;

    private static final int LIST_CAP = 256;

    private static final class SearchFrame {
        int[] pv = new int[MAX_PLY];
        int   len;

        void set(int[] childPv, int childLen, int move) {
            pv[0] = move;
            System.arraycopy(childPv, 0, pv, 1, childLen);
            len = childLen + 1;
        }
    }

    public LazySmpSearchWorkerImpl(boolean isMainThread, LazySmpWorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool         = pool;
        for (int i = 0; i < frames.length; ++i)
            frames[i] = new SearchFrame();
    }

    @Override
    public void prepareForSearch(long[] root, SearchSpec spec, PositionFactory pf, MoveGenerator mg, Evaluator ev, TranspositionTable tt, TimeManager tm) {
        this.rootBoard = root.clone();
        this.spec      = spec;
        this.pf        = pf;
        this.mg        = mg;
        this.eval      = ev;
        this.tt        = tt;
        this.tm        = tm; // Assignment is correct

        this.optimumMs = pool.getOptimumMs();
        this.maximumMs = pool.getMaximumMs();

        bestMove = ponderMove = 0;
        lastScore = 0;
        mateScore = false;
        completedDepth = 0;
        nodes = 0;
        pv.clear();

        keyStackHead = 0;
        for (long key : spec.history()) {
            keyStack[keyStackHead++] = key;
        }
    }

    @Override public void setInfoHandler(InfoHandler ih) { this.ih = ih; }

    @Override
    public SearchResult getSearchResult() {
        return new SearchResult(bestMove, ponderMove, pv, lastScore, mateScore, completedDepth, nodes, elapsedMs);
    }

    @Override
    public void run() {
        searchStartMs = System.currentTimeMillis();
        AtomicBoolean stopFlag = pool.getStopFlag();

        final long[] board = rootBoard;
        final int maxDepth = spec.depth() > 0 ? spec.depth() : MAX_PLY - 4;

        int failHighCount = 0;
        int window = ASP_WINDOW_INITIAL_DELTA;

        for (int depth = 1; depth <= maxDepth; ++depth) {
            if (stopFlag.get()) break;

            int alpha = -SCORE_INF;
            int beta = SCORE_INF;

            if (depth >= ASP_WINDOW_START_DEPTH) {
                alpha = lastScore - window;
                beta  = lastScore + window;
            }

            int score;
            while (true) {
                int adjustedDepth = Math.max(1, depth - failHighCount);
                score = alphaBeta(board, adjustedDepth, alpha, beta, 0, stopFlag);

                if (stopFlag.get()) break;

                if (score <= alpha) {
                    beta = (alpha + beta) / 2;
                    alpha = Math.max(-SCORE_INF, score - window);
                    failHighCount = 0;
                } else if (score >= beta) {
                    beta = Math.min(SCORE_INF, score + window);
                    if (score < 2000) failHighCount++;
                } else {
                    break;
                }
                window += window / 3;
            }

            if (stopFlag.get() || frames[0].len == 0) break;

            lastScore      = score;
            mateScore      = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY;
            elapsedMs      = System.currentTimeMillis() - searchStartMs;
            completedDepth = depth;

            pv.clear();
            for (int i = 0; i < frames[0].len; ++i) {
                pv.add(frames[0].pv[i]);
            }

            bestMove   = pv.isEmpty() ? 0 : pv.get(0);
            ponderMove = pv.size() > 1 ? pv.get(1) : 0;

            if (isMainThread && ih != null) { // This check is correct
                long totNodes = pool.totalNodes();
                long ms       = Math.max(1, elapsedMs);
                long nps      = totNodes * 1000 / ms;
                ih.onInfo(new SearchInfo(depth, depth, 1, score, mateScore, totNodes, nps, ms, pv, tt.hashfull(), 0));
            }

            if (mateScore) break;
            if (isMainThread) {
                long now = System.currentTimeMillis();
                if (now - searchStartMs >= maximumMs)   stopFlag.set(true);
                else if (now - searchStartMs >= optimumMs) stopFlag.set(true);
            }
        }
    }

    private boolean checkTime(AtomicBoolean stop) {
        if ((nodes & 2047) == 0) {
            if (isMainThread) {
                if (System.currentTimeMillis() - searchStartMs >= maximumMs) {
                    stop.set(true);
                }
            }
            return stop.get();
        }
        return false;
    }

    private boolean isRepetition(long baseKey, int ply) {
        if (ply == 0) return false;
        int maxDist = Math.min((int)PositionFactory.halfClock(rootBoard[META]), keyStackHead);
        for (int i = 4; i <= maxDist; i += 2) {
            if (keyStack[keyStackHead - i] == baseKey) {
                return true;
            }
        }
        return false;
    }

    private int alphaBeta(long[] bb, int depth, int alpha, int beta, int ply, AtomicBoolean stop) {
        if (checkTime(stop)) return 0;

        final long baseKey = pf.zobrist(bb);

        if (ply > 0) {
            if (isRepetition(baseKey, ply) || PositionFactory.halfClock(bb[META]) >= 100) {
                return SCORE_DRAW;
            }
        }

        if (ply >= MAX_PLY - 4) {
            return eval.evaluate(bb);
        }

        frames[ply].len = 0;

        alpha = Math.max(alpha, -(SCORE_MATE - ply));
        beta = Math.min(beta, SCORE_MATE - (ply + 1));
        if (alpha >= beta) return alpha;

        final int alphaOrig = alpha;

        final long ttKey = pf.zobrist50(bb);
        TranspositionTable.Entry te = tt.probe(ttKey);
        boolean hit = tt.wasHit(te, ttKey);
        int ttMove  = 0;

        if (hit) {
            int eDepth = te.getDepth();
            if (ply > 0 && eDepth >= depth) {
                int eFlag  = te.getBound();
                int eScore = te.getScore(ply);
                switch (eFlag) {
                    case TranspositionTable.FLAG_EXACT: return eScore;
                    case TranspositionTable.FLAG_LOWER: alpha = Math.max(alpha, eScore); break;
                    case TranspositionTable.FLAG_UPPER: beta  = Math.min(beta,  eScore); break;
                }
                if (alpha >= beta) return eScore;
            }
            ttMove = te.getMove();
        }

        if (depth <= 0) {
            return quiescence(bb, alpha, beta, ply, stop);
        }

        nodes++;
        keyStack[keyStackHead++] = baseKey;

        int[] list = moves[ply];
        boolean inCheck = PositionFactory.whiteToMove(bb[META]) ? mg.kingAttacked(bb, true) : mg.kingAttacked(bb, false);
        int nMoves = inCheck ? mg.generateEvasions(bb, list, 0) : mg.generateQuiets(bb, list, mg.generateCaptures(bb, list, 0));

        if (ttMove != 0) {
            for (int i = 0; i < nMoves; i++)
                if (list[i] == ttMove) { list[i] = list[0]; list[0] = ttMove; break; }
        }

        int bestScore = -SCORE_INF;
        int bestMove  = 0;
        int movesMade = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
            movesMade++;

            int score = -alphaBeta(bb, depth - 1, -beta, -alpha, ply + 1, stop);
            pf.undoMoveInPlace(bb);
            if (stop.get()) { keyStackHead--; return 0; }

            if (score > bestScore) {
                bestScore = score;
                bestMove  = mv;
                frames[ply].set(frames[ply + 1].pv, frames[ply + 1].len, mv);
            }

            if (score > alpha) {
                alpha = score;
                if (alpha >= beta) break;
            }
        }

        keyStackHead--;

        if (movesMade == 0) {
            return inCheck ? -(SCORE_MATE_IN_MAX_PLY - ply) : SCORE_STALEMATE;
        }

        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER : (bestScore > alphaOrig) ? TranspositionTable.FLAG_EXACT : TranspositionTable.FLAG_UPPER;
        boolean isPV = flag == TranspositionTable.FLAG_EXACT;
        te.store(ttKey, flag, depth, bestMove, bestScore, SCORE_NONE, isPV, ply, tt.getCurrentAge());

        return bestScore;
    }

    private int quiescence(long[] bb, int alpha, int beta, int ply, AtomicBoolean stop) {
        if (checkTime(stop)) return 0;

        final long baseKey = pf.zobrist(bb);
        if (isRepetition(baseKey, ply) || PositionFactory.halfClock(bb[META]) >= 100) {
            return SCORE_DRAW;
        }

        if (ply >= QSEARCH_MAX_PLY) return eval.evaluate(bb);

        nodes++;

        final long ttKey = pf.zobrist50(bb);
        TranspositionTable.Entry te = tt.probe(ttKey);
        if (tt.wasHit(te, ttKey)) {
            if (te.getDepth() >= 0) {
                int s = te.getScore(ply);
                switch (te.getBound()) {
                    case TranspositionTable.FLAG_EXACT: return s;
                    case TranspositionTable.FLAG_LOWER: alpha = Math.max(alpha, s); break;
                    case TranspositionTable.FLAG_UPPER: beta  = Math.min(beta,  s); break;
                }
                if (alpha >= beta) return s;
            }
        }

        int standPat = eval.evaluate(bb);
        if (standPat >= beta) {
            te.store(ttKey, TranspositionTable.FLAG_LOWER, 0, 0, standPat, standPat, false, ply, tt.getCurrentAge());
            return standPat;
        }
        if (standPat > alpha) alpha = standPat;

        int[] list = moves[ply];
        boolean inCheck = PositionFactory.whiteToMove(bb[META]) ? mg.kingAttacked(bb, true) : mg.kingAttacked(bb, false);
        int nMoves = inCheck ? mg.generateEvasions(bb, list, 0) : mg.generateCaptures(bb, list, 0);

        int bestScore = standPat;
        int bestMove  = 0;
        int alphaOrig = alpha;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            keyStack[keyStackHead++] = baseKey;
            if (!pf.makeMoveInPlace(bb, mv, mg)) {keyStackHead--; continue;}

            int score = -quiescence(bb, -beta, -alpha, ply + 1, stop);
            pf.undoMoveInPlace(bb);
            keyStackHead--;
            if (stop.get()) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove  = mv;
            }
            if (score >= beta) {
                te.store(ttKey, TranspositionTable.FLAG_LOWER, 0, bestMove, score, standPat, false, ply, tt.getCurrentAge());
                return score;
            }
            if (score > alpha) alpha = score;
        }

        int flag = (bestScore > alphaOrig) ? TranspositionTable.FLAG_EXACT : TranspositionTable.FLAG_UPPER;
        boolean isPV = flag == TranspositionTable.FLAG_EXACT;
        te.store(ttKey, flag, 0, bestMove, bestScore, standPat, isPV, ply, tt.getCurrentAge());
        return bestScore;
    }

    @Override public long getNodes() { return nodes; }
    @Override public void terminate() {}
    @Override public void join() throws InterruptedException {}
}