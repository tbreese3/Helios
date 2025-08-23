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

/**
 * Search worker implementing ID+Aspiration PVS with PV tables and a per-ply stack,
 * while keeping the pruning techniques from the previous implementation.
 */
public final class SearchWorkerImpl implements Runnable, SearchWorker {

    private final WorkerPoolImpl pool;
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
    private TimeManager tm;
    private InfoHandler ih;
    private TranspositionTable tt;
    private MoveOrderer moveOrderer;

    /* ── NNUE ────────── */
    private final NNUEState nnueState = new NNUEState();
    private final NNUE nnue = new NNUEImpl();

    /* ── search report fields ───── */
    private int lastScore;
    private boolean mateScore;
    private long elapsedMs;
    private int completedDepth;
    private long nodes;
    private int bestMove;
    private int ponderMove;
    private List<Integer> pv = new ArrayList<>();
    private List<Long> gameHistory;
    private final long[] searchPathHistory = new long[MAX_PLY + 2];

    /* ── Heuristics for Time Management ── */
    private int stability;
    private int lastBestMove;
    private final List<Integer> searchScores = new ArrayList<>();
    private final long[][] nodeTable = new long[64][64];
    private final int[][] killers = new int[MAX_PLY + 2][2];

    /* ── History Heuristic (quiet moves) ─ */
    private final int[][] history = new int[64][64];  // from-to scores for quiet moves

    /* ── scratch buffers ─────────────── */
    private static final int LIST_CAP = 256;
    private final int[][] moves = new int[MAX_PLY + 2][LIST_CAP];

    /* ── PV table like the reference ─── */
    private final int[][] pvTable = new int[MAX_PLY][MAX_PLY]; // [ply][i]
    private void resetPv() {
        for (int i = 0; i < MAX_PLY; i++) Arrays.fill(pvTable[i], 0);
    }
    private void updatePV(int move, int ply) {
        pvTable[ply][0] = move;
        if (ply + 1 < MAX_PLY) {
            System.arraycopy(pvTable[ply + 1], 0, pvTable[ply], 1, MAX_PLY - 1);
        }
    }

    /* ── Per-ply scratch stack (subset of reference SearchStack) ─ */
    private static final class PlyState {
        boolean inCheck;
        boolean ttHit;
        int moveCount;
        int staticEval = SCORE_NONE;
        int killer;    // local killer (in addition to killers[ply][2])
        int move;      // last tried move at this ply
        int excludedMove; // for singular extensions (unused here, keep for parity)
    }
    private final PlyState[] ss = new PlyState[MAX_PLY + 5];
    private PlyState S(int idx) { return ss[idx + 5]; }

    /* ── LMR precomputed table ───────── */
    private static final int[][] LMR_TABLE = new int[MAX_PLY][MAX_PLY];
    static {
        for (int d = 1; d < MAX_PLY; d++) {
            for (int m = 1; m < MAX_PLY; m++) {
                double reduction = (75 / 100.0 + Math.log(d) * Math.log(m) / (250 / 100.0));
                LMR_TABLE[d][m] = Math.max(0, (int)Math.round(reduction));
            }
        }
    }

    /* ── selective depth (for info) ──── */
    private int selDepth;

    public SearchWorkerImpl(boolean isMainThread, WorkerPoolImpl pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        for (int i = 0; i < ss.length; ++i) ss[i] = new PlyState();
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
                finishedCondition.signal(); // tell pool we're idle/done

                while (!searching && !quit) {
                    try { startCondition.await(); } catch (InterruptedException ignored) {}
                }
                if (quit) return;

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
        // Reset counters and heuristics
        this.nodes = 0;
        this.completedDepth = 0;
        this.lastScore = 0;
        this.mateScore = false;
        this.elapsedMs = 0;
        this.pv.clear();
        this.stability = 0;
        this.lastBestMove = 0;
        this.searchScores.clear();
        this.bestMove = 0;
        this.selDepth = 0;

        for (long[] row : nodeTable) Arrays.fill(row, 0);
        for (int[] k : killers) Arrays.fill(k, 0);
        for (int[] row : history) Arrays.fill(row, 0);

        resetPv();

        nnue.refreshAccumulator(nnueState, rootBoard);
        this.moveOrderer = new MoveOrdererImpl(history);

        long searchStartMs = pool.getSearchStartTime();
        int maxDepth = spec.depth() > 0 ? spec.depth() : CoreConstants.MAX_PLY;

        // Reference-style aspiration window loop
        int currentScore = -SCORE_INF;
        int alpha = -SCORE_INF, beta = SCORE_INF;
        int delta = 25;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (pool.isStopped()) break;
            completedDepth = depth;
            selDepth = 0;

            if (depth > 3) {
                delta = 25;
                alpha = currentScore - delta;
                beta  = currentScore + delta;
            } else {
                alpha = -SCORE_INF;
                beta  =  SCORE_INF;
            }

            while (true) {
                int score = pvs(rootBoard, depth, alpha, beta, 0);
                if (pool.isStopped()) break;

                if (score > alpha && score < beta) {
                    // successful search inside window
                    currentScore = score;
                    lastScore = score;
                    mateScore = Math.abs(score) >= SCORE_MATE_IN_MAX_PLY;

                    // Build PV from pvTable[0]
                    pv.clear();
                    for (int i = 0; i < MAX_PLY && pvTable[0][i] != 0; i++) pv.add(pvTable[0][i]);
                    bestMove   = pv.isEmpty() ? 0 : pv.get(0);
                    ponderMove = pv.size() > 1 ? pv.get(1) : 0;

                    if (bestMove == lastBestMove) stability++; else stability = 0;
                    lastBestMove = bestMove;
                    searchScores.add(lastScore);

                    elapsedMs = System.currentTimeMillis() - searchStartMs;

                    if (isMainThread && ih != null) {
                        long totalNodes = pool.totalNodes();
                        long nps = elapsedMs > 0 ? (totalNodes * 1000) / elapsedMs : 0;
                        ih.onInfo(new SearchInfo(
                                depth, completedDepth, 1, score, mateScore,
                                totalNodes, nps, elapsedMs, pv, tt.hashfull(), selDepth));
                    }

                    if (isMainThread) {
                        if (mateScore || softTimeUp(searchStartMs, pool.getSoftMs())) {
                            pool.stopSearch();
                        }
                    }
                    break; // depth done
                } else if (score <= alpha) {
                    // fail-low → shift window downward
                    beta  = (alpha + beta) / 2;
                    alpha = Math.max(alpha - delta, -SCORE_INF);
                } else {
                    // fail-high → shift window upward
                    beta  = Math.min(beta + delta, SCORE_INF);
                }
                delta += (delta * 3);
            }
        }
    }

    private boolean softTimeUp(long searchStartMs, long softTimeLimit) {
        if (softTimeLimit >= Long.MAX_VALUE / 2) return false; // infinite time

        long currentElapsed = System.currentTimeMillis() - searchStartMs;

        // Obey hard time
        if (currentElapsed >= pool.getMaximumMs()) return true;

        // Respect soft limit until heuristics available
        if (completedDepth < CoreConstants.TM_HEURISTICS_MIN_DEPTH) {
            return currentElapsed >= softTimeLimit;
        }

        // Instability-based extension (your existing logic)
        double instability = 0.0;

        if (bestMove != lastBestMove) {
            instability += CoreConstants.TM_INSTABILITY_PV_CHANGE_BONUS;
        }

        if (searchScores.size() >= 2) {
            int prevScore = searchScores.get(searchScores.size() - 2);
            int scoreDiff = Math.abs(lastScore - prevScore);
            instability += scoreDiff * CoreConstants.TM_INSTABILITY_SCORE_WEIGHT;
        }

        double extensionFactor = 1.0 + instability;
        extensionFactor = Math.min(extensionFactor, CoreConstants.TM_MAX_EXTENSION_FACTOR);

        long extendedSoftTime = (long)(softTimeLimit * extensionFactor);
        return currentElapsed >= extendedSoftTime;
    }

    private int pvs(long[] bb, int depth, int alpha, int beta, int ply) {
        selDepth = Math.max(selDepth, ply);
        pvTable[ply][0] = 0; // clear PV head at this ply
        searchPathHistory[ply] = bb[HASH];

        // draw by repetition or 50-move rule
        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return SCORE_DRAW;
        }

        // mate distance pruning like original
        if (ply > 0) {
            alpha = Math.max(alpha, ply - SCORE_MATE);
            beta  = Math.min(beta, SCORE_MATE - ply - 1);
            if (alpha >= beta) return alpha;
        }

        if (depth <= 0) return quiescence(bb, alpha, beta, ply);

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

        boolean isPvNode = (beta - alpha) > 1;
        PlyState s = S(ply);
        s.moveCount = 0;
        s.inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        S(ply + 2).killer = 0;

        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        boolean ttHit = tt.wasHit(ttIndex, key);
        s.ttHit = ttHit;

        // TT cutoff for non-PV nodes (like reference)
        if (ttHit && tt.getDepth(ttIndex) >= depth && ply > 0 && !isPvNode) {
            int score = tt.getScore(ttIndex, ply);
            int flag = tt.getBound(ttIndex);
            if (flag == TranspositionTable.FLAG_EXACT
                    || (flag == TranspositionTable.FLAG_LOWER && score >= beta)
                    || (flag == TranspositionTable.FLAG_UPPER && score <= alpha)) {
                return score;
            }
        }

        boolean inCheck = s.inCheck;
        if (inCheck) depth++;

        final int IIR_MIN_DEPTH = 4;
        if (depth >= IIR_MIN_DEPTH && isPvNode && (!ttHit || tt.getMove(ttIndex) == 0)) {
            depth--;
        }

        // --- static eval (TT or fresh) ---
        int staticEval = Integer.MIN_VALUE;
        if (ttHit) {
            int ttEval = tt.getStaticEval(ttIndex);
            if (ttEval != SCORE_NONE) staticEval = ttEval;
        }
        if (staticEval == Integer.MIN_VALUE) {
            staticEval = nnue.evaluateFromAccumulator(nnueState, bb);
        }
        s.staticEval = staticEval;

        // Reduced Futility Pruning (yours)
        final int RFP_MAX_DEPTH = 8;
        final int RFP_MARGIN = 75;
        if (!isPvNode && !inCheck && depth <= RFP_MAX_DEPTH && Math.abs(beta) < SCORE_MATE_IN_MAX_PLY) {
            if (staticEval - RFP_MARGIN * depth >= beta) return beta;
        }

        // Razoring (yours)
        if (depth <= 4 && staticEval + 150 * depth <= alpha) {
            int score = quiescence(bb, alpha, beta, ply);
            if (score <= alpha) return score;
        }

        // ProbCut (yours)
        if (!isPvNode && !inCheck && depth >= CoreConstants.PROBCUT_MIN_DEPTH && Math.abs(beta) < SCORE_MATE_IN_MAX_PLY) {
            final int rBeta = Math.min(beta + CoreConstants.PROBCUT_MARGIN_CP, SCORE_MATE_IN_MAX_PLY - 1);

            int[] clist = moves[ply];
            int ccount = mg.generateCaptures(bb, clist, 0);
            moveOrderer.orderMoves(bb, clist, ccount, 0, killers[ply]);

            for (int i = 0; i < ccount; i++) {
                int mv = clist[i];
                int seeGain = moveOrderer.see(bb, mv);
                if (staticEval + seeGain < rBeta) continue;

                int capturedPiece = getCapturedPieceType(bb, mv);
                int moverPiece    = (mv >>> 16) & 0xF;

                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

                int value;
                if (depth >= 2 * CoreConstants.PROBCUT_MIN_DEPTH) {
                    value = -quiescence(bb, -rBeta, -rBeta + 1, ply + 1);
                    if (value < rBeta) {
                        pf.undoMoveInPlace(bb);
                        nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);
                        if (pool.isStopped()) return 0;
                        continue;
                    }
                }

                value = -pvs(bb, depth - CoreConstants.PROBCUT_REDUCTION, -rBeta, -rBeta + 1, ply + 1);

                pf.undoMoveInPlace(bb);
                nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);
                if (pool.isStopped()) return 0;

                if (value >= rBeta) {
                    int storeDepth = Math.max(0, depth - Math.max(1, CoreConstants.PROBCUT_REDUCTION - 1));
                    int oldDepth = tt.getDepth(ttIndex);
                    if (!ttHit || oldDepth < storeDepth) {
                        tt.store(ttIndex, key, TranspositionTable.FLAG_LOWER, storeDepth, mv, value, staticEval, false, ply);
                    }
                    return value;
                }
            }
        }

        // Null-move pruning (yours)
        if (!inCheck && !isPvNode && depth >= 3 && ply > 0 && pf.hasNonPawnMaterial(bb)) {
            if (staticEval >= beta) {
                int r = 3 + depth / 4;
                int nmpDepth = depth - 1 - r;

                long oldMeta = bb[META];
                bb[META] ^= PositionFactory.STM_MASK;
                bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

                int nullScore = -pvs(bb, nmpDepth, -beta, -beta + 1, ply + 1);

                bb[META] = oldMeta;
                bb[HASH] ^= PositionFactoryImpl.SIDE_TO_MOVE;

                if (nullScore >= beta) return beta;
            }
        }

        // Move generation
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

        // TT move to front
        int ttMove = 0;
        if (ttHit) {
            ttMove = tt.getMove(ttIndex);
            if (ttMove != 0) {
                for (int i = 0; i < nMoves; i++) {
                    if (list[i] == ttMove) {
                        list[i] = list[0];
                        list[0] = ttMove;
                        break;
                    }
                }
            }
        }

        moveOrderer.orderMoves(bb, list, nMoves, ttMove, killers[ply]);

        int bestScore = -SCORE_INF;
        int localBestMove = 0;
        int originalAlpha = alpha;
        int legalMovesFound = 0;

        for (int i = 0; i < nMoves; i++) {
            int mv = list[i];
            s.moveCount++;
            s.move = mv;

            long nodesBeforeMove = this.nodes;
            int capturedPiece = getCapturedPieceType(bb, mv);
            int moverPiece = ((mv >>> 16) & 0xF);
            int from = (mv >>> 6) & 0x3F;
            int to = mv & 0x3F;

            // SEE pruning for bad tactical moves (yours)
            final int SEE_MARGIN_PER_DEPTH = -70;
            if (!isPvNode && !inCheck && depth <= 8 && moveOrderer.see(bb, mv) < SEE_MARGIN_PER_DEPTH * depth) {
                continue;
            }

            boolean isCapture = (i < capturesEnd);
            boolean isPromotion = ((mv >>> 14) & 0x3) == 1;
            boolean isTactical = isCapture || isPromotion;

            // Late Move Pruning (LMP) for quiets (yours)
            if (!isPvNode && !inCheck && depth <= CoreConstants.LMP_MAX_DEPTH && !isTactical && bestScore > -SCORE_MATE_IN_MAX_PLY) {
                int lmpLimit = CoreConstants.LMP_BASE_MOVES + CoreConstants.LMP_DEPTH_SCALE * depth * depth;
                if (i >= lmpLimit) {
                    int hist = history[from][to];
                    if (hist < CoreConstants.LMP_HIST_MIN * depth) continue;
                }
            }

            // Futility pruning for quiets (yours enhanced)
            if (!isPvNode && !inCheck && bestScore > -SCORE_MATE_IN_MAX_PLY && !isTactical) {
                if (depth <= FP_MAX_DEPTH) {
                    int margin = (depth * FP_MARGIN_PER_PLY) + (depth * depth * FP_MARGIN_QUADRATIC);
                    if (s.staticEval + margin < alpha) {
                        continue;
                    }
                }
            }

            if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
            legalMovesFound++;
            nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

            int score;
            if (i == 0) {
                // first move full window
                score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
            } else {
                // LMR (yours via table)
                int reduction = 0;
                if (depth >= LMR_MIN_DEPTH && i >= LMR_MIN_MOVE_COUNT && !isTactical && !inCheck) {
                    reduction = calculateReduction(depth, i);
                }
                int reducedDepth = Math.max(0, depth - 1 - reduction);

                // zero-window trial
                score = -pvs(bb, reducedDepth, -alpha - 1, -alpha, ply + 1);

                // if promising, re-search at full depth & window
                if (score > alpha) {
                    score = -pvs(bb, depth - 1, -beta, -alpha, ply + 1);
                }
            }

            pf.undoMoveInPlace(bb);
            nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);
            if (pool.isStopped()) return 0;

            // per-move node accounting at root (yours)
            if (ply == 0) {
                long nodesAfterMove = this.nodes;
                long nodesForThisMove = nodesAfterMove - nodesBeforeMove;
                nodeTable[from][to] += nodesForThisMove;
            }

            if (score > bestScore) {
                bestScore = score;
                localBestMove = mv;
                if (score > alpha) {
                    alpha = score;
                    if (isPvNode) updatePV(mv, ply);   // PV discipline like reference
                    if (score >= beta) {
                        // quiet fail-high updates
                        if (!isTactical) {
                            history[from][to] += depth * depth;
                            if (killers[ply][0] != mv) {
                                killers[ply][1] = killers[ply][0];
                                killers[ply][0] = mv;
                            }
                            s.killer = mv;
                        }
                        break;
                    }
                }
            }
        }

        if (legalMovesFound == 0) {
            return inCheck ? -(SCORE_MATE - ply) : SCORE_STALEMATE;
        }

        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : (bestScore > originalAlpha) ? TranspositionTable.FLAG_EXACT
                : TranspositionTable.FLAG_UPPER;

        tt.store(ttIndex, key, flag, depth, localBestMove, bestScore, s.staticEval, isPvNode, ply);
        return bestScore;
    }

    private int quiescence(long[] bb, int alpha, int beta, int ply) {
        selDepth = Math.max(selDepth, ply);
        searchPathHistory[ply] = bb[HASH];

        if (ply > 0 && (isRepetitionDraw(bb, ply) || PositionFactory.halfClock(bb[META]) >= 100)) {
            return SCORE_DRAW;
        }

        if ((nodes & 2047) == 0 && pool.isStopped()) return 0;
        if (ply >= MAX_PLY) return nnue.evaluateFromAccumulator(nnueState, bb);

        nodes++;

        long key = pf.zobrist(bb);
        int ttIndex = tt.probe(key);
        int staticEval = SCORE_NONE;
        int localBestMove = 0;

        if (tt.wasHit(ttIndex, key)) {
            if (tt.getDepth(ttIndex) >= 0) {
                int score = tt.getScore(ttIndex, ply);
                int flag = tt.getBound(ttIndex);
                if ((flag == TranspositionTable.FLAG_LOWER && score >= beta)
                        || (flag == TranspositionTable.FLAG_UPPER && score <= alpha)) {
                    return score;
                }
            }
            staticEval = tt.getStaticEval(ttIndex);
        }

        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        int bestScore;

        if (inCheck) {
            int[] list = moves[ply];
            int nMoves = mg.generateEvasions(bb, list, 0);
            int legalMovesFound = 0;
            bestScore = -SCORE_INF;
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                int capturedPiece = getCapturedPieceType(bb, mv);
                int moverPiece = ((mv >>> 16) & 0xF);

                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                legalMovesFound++;
                nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

                int score = -quiescence(bb, -beta, -alpha, ply + 1);

                pf.undoMoveInPlace(bb);
                nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);
                if (pool.isStopped()) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    localBestMove = mv;
                    if (score >= beta) break;
                    if (score > alpha) alpha = score;
                }
            }

            if (legalMovesFound == 0) bestScore = -(SCORE_MATE - ply);

        } else {
            if (staticEval == SCORE_NONE) staticEval = nnue.evaluateFromAccumulator(nnueState, bb);

            bestScore = staticEval; // stand-pat

            if (bestScore >= beta) {
                tt.store(ttIndex, key, TranspositionTable.FLAG_LOWER, 0, 0, bestScore, staticEval, false, ply);
                return beta;
            }
            if (bestScore > alpha) alpha = bestScore;

            int[] list = moves[ply];
            int nMoves = mg.generateCaptures(bb, list, 0);
            nMoves = moveOrderer.seePrune(bb, list, nMoves);
            moveOrderer.orderMoves(bb, list, nMoves, 0, killers[ply]);

            for (int i = 0; i < nMoves; i++) {
                int mv = list[i];
                int capturedPiece = getCapturedPieceType(bb, mv);
                int moverPiece = ((mv >>> 16) & 0xF);

                if (!pf.makeMoveInPlace(bb, mv, mg)) continue;
                nnue.updateNnueAccumulator(nnueState, bb, moverPiece, capturedPiece, mv);

                int score = -quiescence(bb, -beta, -alpha, ply + 1);

                pf.undoMoveInPlace(bb);
                nnue.undoNnueAccumulatorUpdate(nnueState, bb, moverPiece, capturedPiece, mv);
                if (pool.isStopped()) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    localBestMove = mv;
                    if (score >= beta) break;
                    if (score > alpha) alpha = score;
                }
            }
        }

        int flag = (bestScore >= beta) ? TranspositionTable.FLAG_LOWER
                : TranspositionTable.FLAG_UPPER;

        tt.store(ttIndex, key, flag, 0, localBestMove, bestScore, staticEval, false, ply);
        return bestScore;
    }

    private int calculateReduction(int depth, int moveNumber) {
        int d = Math.min(depth, CoreConstants.MAX_PLY - 1);
        int m = Math.min(moveNumber, CoreConstants.MAX_PLY - 1);
        return LMR_TABLE[d][m];
    }

    /**
     * Checks if the current position is a draw by repetition.
     * Uses current search path plus game history within 50-move window.
     */
    private boolean isRepetitionDraw(long[] bb, int ply) {
        final long currentHash = bb[HASH];
        final int halfmoveClock = (int) PositionFactory.halfClock(bb[META]);

        for (int i = 2; i <= halfmoveClock; i += 2) {
            int prevPly = ply - i;
            long previousHash;
            if (prevPly < 0) {
                int gameHistoryIdx = gameHistory.size() + prevPly;
                if (gameHistoryIdx >= 0) {
                    previousHash = gameHistory.get(gameHistoryIdx);
                } else {
                    break;
                }
            } else {
                previousHash = searchPathHistory[prevPly];
            }

            if (previousHash == currentHash) return true;
        }
        return false;
    }

    private int getCapturedPieceType(long[] bb, int move) {
        int to = move & 0x3F;
        long toBit = 1L << to;
        int moveType = (move >>> 14) & 0x3;
        boolean isWhiteMover = (((move >>> 16) & 0xF) < 6);

        if (moveType == 2) { // En-passant
            return isWhiteMover ? PositionFactory.BP : PositionFactory.WP;
        }

        for (int p = isWhiteMover ? PositionFactory.BP : PositionFactory.WP;
             p <= (isWhiteMover ? PositionFactory.BK : PositionFactory.WK); p++) {
            if ((bb[p] & toBit) != 0) return p;
        }
        return -1; // No capture
    }

    @Override
    public void prepareForSearch(long[] root, SearchSpec s, PositionFactory p, MoveGenerator m, TranspositionTable t, TimeManager timeMgr) {
        this.rootBoard = root.clone();
        this.spec = s;
        this.pf = p;
        this.mg = m;
        this.tt = t;
        this.tm = timeMgr;
        this.gameHistory = s.history();
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
                try { finishedCondition.await(); } catch (InterruptedException ignored) {}
            }
        } finally {
            mutex.unlock();
        }
    }

    @Override public void setInfoHandler(InfoHandler handler) { this.ih = handler; }

    @Override
    public SearchResult getSearchResult() {
        return new SearchResult(bestMove, ponderMove, pv, lastScore, mateScore, completedDepth, nodes, elapsedMs);
    }

    @Override public long getNodes() { return nodes; }

    @Override
    public void terminate() {
        mutex.lock();
        try {
            quit = true;
            searching = true;
            startCondition.signal();
        } finally {
            mutex.unlock();
        }
    }

    @Override public void join() throws InterruptedException { /* handled by pool */ }
}
