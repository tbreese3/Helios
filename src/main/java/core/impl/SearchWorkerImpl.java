// File: core/impl/SearchWorkerImpl.java
package core.impl;

import core.constants.CoreConstants;
import core.contracts.*;
import core.records.NNUEState;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static core.constants.CoreConstants.*;
import static core.contracts.PositionFactory.*;

public final class SearchWorkerImpl implements Runnable, SearchWorker {

    private final WorkerPoolImpl pool;
    final boolean isMainThread;

    // ── Threading ─────────────────────────────────────────────
    private final Lock lock = new ReentrantLock();
    private final Condition startCv = lock.newCondition();
    private final Condition doneCv  = lock.newCondition();
    private volatile boolean searching = false;
    private volatile boolean quit = false;

    // ── Per-search state ──────────────────────────────────────
    private long[] rootBoard;
    private SearchSpec spec;
    private PositionFactory pf;
    private MoveGenerator mg;
    private TimeManager tm;
    private InfoHandler ih;
    private TranspositionTable tt;

    private MoveOrderer orderer;

    // NNUE
    private final NNUEState nnueState = new NNUEState();
    private final NNUE nnue = new NNUEImpl();

    // Accounting
    private long nodes;
    private int bestMove, ponderMove;
    private int lastScore;
    private boolean mateScore;
    private long elapsedMs;
    private int completedDepth;
    private final List<Integer> pv = new ArrayList<>();
    private List<Long> gameHistory;
    private final long[] searchPathHistory = new long[MAX_PLY + 2];

    // Instability/time mgmt
    private final List<Integer> depthScores = new ArrayList<>();
    private int lastBestMove, stability;

    // Heuristics
    private final int[][] killers = new int[MAX_PLY + 2][2];
    private final int[][] history = new int[64][64];       // from->to quiet history

    // Scratch
    private final SearchFrame[] frames = new SearchFrame[MAX_PLY + 2];
    private final int[][] moves = new int[MAX_PLY + 2][256];
    private static final int[][] LMR_TABLE = new int[MAX_PLY][MAX_PLY];

    static {
        // LMR table (quiet only usage)
        for (int d = 1; d < MAX_PLY; d++) {
            for (int m = 1; m < MAX_PLY; m++) {
                double r = 0.78 + Math.log(d) * Math.log(m) / 2.47;
                LMR_TABLE[d][m] = Math.max(0, (int)Math.round(r));
            }
        }
    }

    private static final class SearchFrame {
        final int[] pv = new int[MAX_PLY];
        int len;
        void set(int[] childPv, int childLen, int move) {
            pv[0] = move;
            if (childLen > 0) System.arraycopy(childPv, 0, pv, 1, childLen);
            len = childLen + 1;
        }
    }

    public SearchWorkerImpl(boolean isMainThread, WorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        for (int i = 0; i < frames.length; i++) frames[i] = new SearchFrame();
    }

    // ── Runnable ──────────────────────────────────────────────
    @Override
    public void run() {
        while (true) {
            lock.lock();
            try {
                searching = false;
                doneCv.signal();
                while (!searching && !quit) {
                    try { startCv.await(); } catch (InterruptedException ignored) {}
                }
                if (quit) return;
            } finally {
                lock.unlock();
            }

            if (isMainThread) {
                tt.incrementAge();
                pool.startHelpers();
                doSearch();
                pool.waitForHelpersFinished();
                pool.finalizeSearch(getSearchResult());
            } else {
                doSearch();
            }
        }
    }

    // ── Entry points ──────────────────────────────────────────
    @Override
    public void prepareForSearch(long[] root, SearchSpec s, PositionFactory p, MoveGenerator m,
                                 TranspositionTable t, TimeManager timeMgr) {
        this.rootBoard = root.clone();
        this.spec = s;
        this.pf = p;
        this.mg = m;
        this.tt = t;
        this.tm = timeMgr;
        this.gameHistory = s.history();
    }

    public void startWorkerSearch() {
        lock.lock();
        try {
            searching = true;
            startCv.signal();
        } finally {
            lock.unlock();
        }
    }

    public void waitWorkerFinished() {
        lock.lock();
        try {
            while (searching) {
                try { doneCv.await(); } catch (InterruptedException ignored) {}
            }
        } finally { lock.unlock(); }
    }

    @Override public void setInfoHandler(InfoHandler handler) { this.ih = handler; }

    @Override
    public SearchResult getSearchResult() {
        return new SearchResult(bestMove, ponderMove, pv, lastScore, mateScore, completedDepth, nodes, elapsedMs);
    }

    @Override public long getNodes() { return nodes; }

    @Override
    public void terminate() {
        lock.lock();
        try {
            quit = true;
            searching = true;
            startCv.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override public void join() throws InterruptedException { /* handled by pool */ }

    // ── Main search driver ────────────────────────────────────
    private void doSearch() {
        // reset counters/heuristics
        nodes = 0; bestMove = ponderMove = 0; lastScore = 0; mateScore = false;
        elapsedMs = 0; completedDepth = 0; pv.clear();
        lastBestMove = 0; stability = 0; depthScores.clear();
        for (int[] k : killers) Arrays.fill(k, 0);
        for (int[] row : history) Arrays.fill(row, 0);

        // NNUE & orderer
        nnue.refreshAccumulator(nnueState, rootBoard);
        orderer = new MoveOrdererImpl(history);

        long startMs = pool.getSearchStartTime();
        final int maxDepth = spec.depth() > 0 ? spec.depth() : CoreConstants.MAX_PLY;

        // Aspiration baseline
        boolean havePrev = false;
        int prevScore = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (pool.isStopped()) break;

            // Ethereal-style aspiration:
            int delta = 10;
            int localDepth = depth;
            int alpha = -SCORE_INF, beta = SCORE_INF;
            if (havePrev && depth >= 4) {
                alpha = Math.max(-SCORE_INF, prevScore - delta);
                beta  = Math.min( SCORE_INF, prevScore + delta);
            }

            int score;
            while (true) {
                score = pvs(rootBoard, localDepth, alpha, beta, 0);

                // in-window → done
                if (score > alpha && score < beta) break;

                // fail-low: move window down, reset localDepth to full
                if (score <= alpha) {
                    beta  = (alpha + beta) / 2;
                    alpha = Math.max(-SCORE_INF, alpha - delta);
                    localDepth = depth; // Ethereal resets to full on low
                } else {
                    // fail-high: grow window upward; Ethereal may reduce depth by 1 unless near mate
                    beta = Math.min(SCORE_INF, beta + delta);
                    if (Math.abs(score) <= SCORE_MATE / 2) localDepth = Math.max(1, localDepth - 1);
                }
                delta = delta + delta / 2; // expand window ~1.5x
            }

            havePrev = true;
            prevScore = score;

            lastScore = score;
            mateScore = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY;
            completedDepth = depth;

            if (frames[0].len > 0) {
                pv.clear();
                for (int i = 0; i < frames[0].len; i++) pv.add(frames[0].pv[i]);
                bestMove = pv.get(0);
                ponderMove = pv.size() > 1 ? pv.get(1) : 0;

                if (bestMove == lastBestMove) stability++; else stability = 0;
                lastBestMove = bestMove;
            }
            depthScores.add(lastScore);

            elapsedMs = System.currentTimeMillis() - startMs;
            if (isMainThread && ih != null) {
                long total = pool.totalNodes();
                long nps = elapsedMs > 0 ? (total * 1000) / elapsedMs : 0;
                ih.onInfo(new SearchInfo(depth, completedDepth, 1, score, mateScore,
                        total, nps, elapsedMs, pv, tt.hashfull(), 0));
            }

            if (isMainThread) {
                if (mateScore || softTimeUp(startMs, pool.getSoftMs())) pool.stopSearch();
            }
        }
    }

    private boolean softTimeUp(long startMs, long softLimit) {
        if (softLimit >= Long.MAX_VALUE / 2) return false;
        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed >= pool.getMaximumMs()) return true;
        if (completedDepth < CoreConstants.TM_HEURISTICS_MIN_DEPTH) return elapsed >= softLimit;

        double instab = 0.0;
        if (!pv.isEmpty() && bestMove != lastBestMove) instab += CoreConstants.TM_INSTABILITY_PV_CHANGE_BONUS;
        if (depthScores.size() >= 2) {
            int prev = depthScores.get(depthScores.size() - 2);
            instab += Math.abs(lastScore - prev) * CoreConstants.TM_INSTABILITY_SCORE_WEIGHT;
        }
        double factor = Math.min(1.0 + instab, CoreConstants.TM_MAX_EXTENSION_FACTOR);
        long extended = (long)(softLimit * factor);
        return elapsed >= extended;
    }

    // ── Core PVS ──────────────────────────────────────────────
    private int pvs(long[] bb, int depth, int alpha, int beta, int ply) {
        frames[ply].len = 0;
        searchPathHistory[ply] = bb[HASH];

        // repetition & 50-move
        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return SCORE_DRAW;
        }

        // mate distance pruning
        if (ply > 0) {
            alpha = Math.max(alpha, ply - SCORE_MATE);
            beta  = Math.min(beta , SCORE_MATE - ply - 1);
            if (alpha >= beta) return alpha;
        }

        // leaf → QS
        if (depth <= 0) return quiescence(bb, alpha, beta, ply);

        // budget & horizon
        if (ply > 0) {
            nodes++;
            if ((nodes & 2047) == 0) {
                if (pool.isStopped() || (isMainThread && pool.shouldStop(pool.getSearchStartTime(), false))) {
                    pool.stopSearch();
                    return 0;
                }
            }
            if (ply >= MAX_PLY) return nnue.evaluateFromAccumulator(nnueState, bb);
        }

        final boolean pvNode = (beta - alpha) > 1;

        // TT lookup
        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        boolean ttHit = tt.wasHit(ttIndex, key);

        // TT cutoff (respect PV)
        if (!pvNode && ttHit && tt.getDepth(ttIndex) >= depth && ply > 0) {
            int score = tt.getScore(ttIndex, ply);
            int flag  = tt.getBound(ttIndex);
            if (flag == TranspositionTable.FLAG_EXACT ||
                    (flag == TranspositionTable.FLAG_LOWER && score >= beta) ||
                    (flag == TranspositionTable.FLAG_UPPER && score <= alpha)) {
                return score;
            }
        }

        int ttMove = ttHit ? tt.getMove(ttIndex) : 0;

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        if (inCheck) depth++; // in-check extension

        // Internal Iterative Reduction (when TT is unhelpful)
        if (depth >= 7 && (pvNode /*or cutnode-like*/) && (!ttHit || tt.getDepth(ttIndex) + 4 < depth)) {
            depth--;
        }

        // Static eval (TT cached or fresh)
        int staticEval = SCORE_NONE;
        if (ttHit) staticEval = tt.getStaticEval(ttIndex);
        if (staticEval == SCORE_NONE) staticEval = nnue.evaluateFromAccumulator(nnueState, bb);

        // Reverse-Futility / Beta-like static prune (>=10 Elo)
        if (!pvNode && !inCheck && depth <= 8 && Math.abs(beta) < SCORE_MATE_IN_MAX_PLY) {
            int margin = 75 * depth;
            if (staticEval - margin >= beta) return beta;
        }

        // Null Move Pruning (big Elo)
        if (!pvNode && !inCheck && depth >= 3 && pf.hasNonPawnMaterial(bb) && staticEval >= beta) {
            long oldMeta = bb[META];
            bb[META] ^= PositionFactory.STM_MASK;
            bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

            int R = 3 + depth / 5;
            int v = -pvs(bb, depth - 1 - R, -beta, -beta + 1, ply + 1);

            bb[META] = oldMeta;
            bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

            if (v >= beta) return beta;
        }

        // Generate moves
        int[] list = moves[ply];
        int capturesEnd, nMoves;
        if (inCheck) {
            nMoves = mg.generateEvasions(bb, list, 0);
            capturesEnd = nMoves;
        } else {
            capturesEnd = mg.generateCaptures(bb, list, 0);
            nMoves = mg.generateQuiets(bb, list, capturesEnd);
        }

        // Put TT move first then full ordering
        if (ttMove != 0) {
            for (int i = 0; i < nMoves; i++) {
                if (list[i] == ttMove) { list[i] = list[0]; list[0] = ttMove; break; }
            }
        }
        orderer.orderMoves(bb, list, nMoves, ttMove, killers[ply]);

        int bestScore = -SCORE_INF;
        int bestLocal = 0;
        int originalAlpha = alpha;
        int legal = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];

            // SEE pruning for captures (>=10 Elo)
            boolean isCapture = (i < capturesEnd);
            boolean isPromotion = (((mv >>> 14) & 0x3) == 1);
            boolean tactical = isCapture || isPromotion;

            if (isCapture && !pvNode && !inCheck && depth <= 10) {
                if (orderer.see(bb, mv) < -64 * depth) continue;
            }

            // Late Move Pruning for quiets (>=10 Elo)
            if (!pvNode && !inCheck && depth <= CoreConstants.LMP_MAX_DEPTH && !tactical
                    && bestScore > -SCORE_MATE_IN_MAX_PLY) {
                int lmpLimit = CoreConstants.LMP_BASE_MOVES + CoreConstants.LMP_DEPTH_SCALE * depth * depth;
                if (i >= lmpLimit) {
                    int from = (mv >>> 6) & 0x3F, to = mv & 0x3F;
                    if (history[from][to] < CoreConstants.LMP_HIST_MIN * depth) continue;
                }
            }

            int captured = getCapturedPieceType(bb, mv);
            int mover    = ((mv >>> 16) & 0xF);

            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
            legal++;
            nnue.updateNnueAccumulator(nnueState, bb, mover, captured, mv);

            int score;
            if (i == 0) {
                score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
            } else {
                // Quiet LMR only (drop noisy-LMR)
                int reduction = 0;
                if (depth >= 3 && !tactical && !inCheck) {
                    int d = Math.min(depth, MAX_PLY - 1);
                    int m = Math.min(i,     MAX_PLY - 1);
                    reduction = LMR_TABLE[d][m];
                    if (!pvNode) reduction += 1;
                }
                int reducedDepth = Math.max(0, depth - 1 - reduction);

                // first, zero-window at reduced depth
                score = -pvs(bb, reducedDepth, -alpha - 1, -alpha, ply + 1);

                // if promising, full-depth re-search
                if (score > alpha) {
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
                }
            }

            pf.undoMoveInPlace(bb);
            nnue.undoNnueAccumulatorUpdate(nnueState, bb, mover, captured, mv);
            if (pool.isStopped()) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestLocal = mv;
                if (score > alpha) {
                    alpha = score;
                    if (pvNode) frames[ply].set(frames[ply + 1].pv, frames[ply + 1].len, mv);
                    if (score >= beta) {
                        // quiet fail-high → history/killers
                        if (!tactical) {
                            int from = (mv >>> 6) & 0x3F, to = mv & 0x3F;
                            history[from][to] += depth * depth;
                            if (killers[ply][0] != mv) { killers[ply][1] = killers[ply][0]; killers[ply][0] = mv; }
                        }
                        break;
                    }
                }
            }
        }

        if (legal == 0) return inCheck ? -(SCORE_MATE - ply) : SCORE_STALEMATE;

        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestScore > originalAlpha) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        tt.store(ttIndex, key, flag, depth, bestLocal, bestScore, staticEval, pvNode, ply);
        return bestScore;
    }

    // ── Quiescence ────────────────────────────────────────────
    private int quiescence(long[] bb, int alpha, int beta, int ply) {
        searchPathHistory[ply] = bb[HASH];
        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) return SCORE_DRAW;
        if ((nodes & 2047) == 0 && pool.isStopped()) return 0;
        if (ply >= MAX_PLY) return nnue.evaluateFromAccumulator(nnueState, bb);
        nodes++;

        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        boolean hit = tt.wasHit(ttIndex, key);
        int staticEval = SCORE_NONE;
        int best = -SCORE_INF;
        int bestMv = 0;
        int oldAlpha = alpha;

        if (hit) {
            int score = tt.getScore(ttIndex, ply);
            int bnd = tt.getBound(ttIndex);
            if ((bnd == TranspositionTable.FLAG_LOWER && score >= beta) ||
                    (bnd == TranspositionTable.FLAG_UPPER && score <= alpha)) {
                return score;
            }
            staticEval = tt.getStaticEval(ttIndex);
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        if (inCheck) {
            int[] list = moves[ply];
            int n = mg.generateEvasions(bb, list, 0);
            orderer.orderMoves(bb, list, n, 0, killers[ply]);

            for (int i = 0; i < n; i++) {
                int mv = list[i];
                int cap = getCapturedPieceType(bb, mv);
                int mover = ((mv >>> 16) & 0xF);
                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                nnue.updateNnueAccumulator(nnueState, bb, mover, cap, mv);

                int v = -quiescence(bb, -beta, -alpha, ply + 1);

                pf.undoMoveInPlace(bb);
                nnue.undoNnueAccumulatorUpdate(nnueState, bb, mover, cap, mv);
                if (pool.isStopped()) return 0;

                if (v > best) {
                    best = v; bestMv = mv;
                    if (v >= beta) break;
                    if (v > alpha) alpha = v;
                }
            }
        } else {
            if (staticEval == SCORE_NONE) staticEval = nnue.evaluateFromAccumulator(nnueState, bb);
            best = staticEval;
            if (best >= beta) {
                tt.store(ttIndex, key, TranspositionTable.FLAG_LOWER, 0, 0, best, staticEval, false, ply);
                return beta;
            }
            if (best > alpha) alpha = best;

            int[] list = moves[ply];
            int n = mg.generateCaptures(bb, list, 0);
            n = orderer.seePrune(bb, list, n);               // prune losing captures
            orderer.orderMoves(bb, list, n, 0, killers[ply]);

            for (int i = 0; i < n; i++) {
                int mv = list[i];
                int cap = getCapturedPieceType(bb, mv);
                int mover = ((mv >>> 16) & 0xF);
                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                nnue.updateNnueAccumulator(nnueState, bb, mover, cap, mv);

                int v = -quiescence(bb, -beta, -alpha, ply + 1);

                pf.undoMoveInPlace(bb);
                nnue.undoNnueAccumulatorUpdate(nnueState, bb, mover, cap, mv);
                if (pool.isStopped()) return 0;

                if (v > best) {
                    best = v; bestMv = mv;
                    if (v >= beta) break;
                    if (v > alpha) alpha = v;
                }
            }
        }

        int flag = (best >= beta) ? TranspositionTable.FLAG_LOWER : TranspositionTable.FLAG_UPPER;
        tt.store(ttIndex, key, flag, 0, bestMv, best, staticEval, false, ply);
        return best;
    }

    // ── Helpers ───────────────────────────────────────────────
    private int calculateReduction(int depth, int moveNumber) {
        int d = Math.min(depth, MAX_PLY - 1);
        int m = Math.min(moveNumber, MAX_PLY - 1);
        return LMR_TABLE[d][m];
    }

    private boolean isRepetitionDraw(long[] bb, int ply) {
        final long currentHash = bb[HASH];
        final int halfmoveClock = (int) PositionFactory.halfClock(bb[META]);

        for (int i = 2; i <= halfmoveClock; i += 2) {
            int prevPly = ply - i;
            long prevHash;
            if (prevPly < 0) {
                int idx = gameHistory.size() + prevPly;
                if (idx >= 0) prevHash = gameHistory.get(idx);
                else break;
            } else {
                prevHash = searchPathHistory[prevPly];
            }
            if (prevHash == currentHash) return true;
        }
        return false;
    }

    private int getCapturedPieceType(long[] bb, int move) {
        int to = move & 0x3F;
        long toBit = 1L << to;
        int mtype = (move >>> 14) & 0x3;
        boolean whiteMover = (((move >>> 16) & 0xF) < 6);

        if (mtype == 2) return whiteMover ? PositionFactory.BP : PositionFactory.WP; // en-passant

        for (int p = whiteMover ? PositionFactory.BP : PositionFactory.WP;
             p <= (whiteMover ? PositionFactory.BK : PositionFactory.WK); p++) {
            if ((bb[p] & toBit) != 0) return p;
        }
        return -1;
    }
}
