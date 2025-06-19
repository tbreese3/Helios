package core.impl;

import core.contracts.*;
import core.records.SearchInfo;
import core.records.SearchResult;
import core.records.SearchSpec;
import core.records.TimeAllocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static core.constants.CoreConstants.*;
import static core.contracts.PositionFactory.*;
import static core.contracts.TranspositionTable.FLAG_EXACT;
import static core.contracts.TranspositionTable.FLAG_LOWER;
import static core.contracts.TranspositionTable.FLAG_UPPER;

public class SearchWorkerImpl implements SearchWorker, Runnable {

    private static class SearchFrame {
        final int[] pv = new int[MAX_PLY];
        int pvLength = 0;
        int staticEval = SCORE_NONE;

        void updatePv(int move, SearchFrame child) {
            pv[0] = move;
            if (child.pvLength > 0) {
                System.arraycopy(child.pv, 0, pv, 1, child.pvLength);
            }
            pvLength = child.pvLength + 1;
        }

        void clear() {
            pvLength = 0;
            staticEval = SCORE_NONE;
        }
    }

    private static class RootMove {
        final int move;
        int score;
        long nodes;
        final List<Integer> pv = new ArrayList<>();
        RootMove(int move) {
            this.move = move;
            this.score = -SCORE_MATE;
        }

        void setPvFrom(SearchFrame ss, int bestMove) {
            pv.clear();
            pv.add(bestMove);
            for (int i = 0; i < ss.pvLength; i++) {
                pv.add(ss.pv[i]);
            }
        }
    }

    private final boolean isMainThread;
    private final WorkerPool pool;
    private final AtomicBoolean terminateFlag = new AtomicBoolean(false);
    private final Thread thread;

    private long[] rootBoard;
    private SearchSpec searchSpec;
    private PositionFactory positionFactory;
    private MoveGenerator moveGenerator;
    private Evaluator evaluator;
    private TranspositionTable transpositionTable;
    private TimeManager timeManager;
    private InfoHandler infoHandler;
    private SearchResult finalResult;

    private long nodesSearched;
    private long lastReportedNodes;
    private long stopTime;
    private final SearchFrame[] searchStack = new SearchFrame[MAX_PLY + 1];
    private final int[][] moveStack = new int[MAX_PLY + 1][256];
    private final long[] keyHistory = new long[MAX_PLY + 256];
    private int historyIndex = 0;

    public SearchWorkerImpl(boolean isMainThread, WorkerPool pool) {
        this.isMainThread = isMainThread;
        this.pool = pool;
        this.thread = new Thread(this, "SearchWorker-" + (isMainThread ? "Main" : "Helper"));
        for (int i = 0; i < searchStack.length; i++) {
            searchStack[i] = new SearchFrame();
        }
        thread.start();
    }

    @Override
    public void run() {
        while (!terminateFlag.get()) {
            try {
                pool.workerReady();
                if (terminateFlag.get()) break;
                search();
            } catch (InterruptedException e) {
                terminate();
                Thread.currentThread().interrupt();
            } finally {
                pool.workerFinished();
            }
        }
    }

    private void search() {
        this.nodesSearched = 0;
        this.lastReportedNodes = 0;
        this.historyIndex = 0;
        if (searchSpec.history() != null) {
            for (Long key : searchSpec.history()) {
                if (historyIndex < keyHistory.length) {
                    keyHistory[historyIndex++] = key;
                }
            }
        }

        if (isMainThread) {
            iterativeDeepeningLoop();
        } else {
            int searchDepth = (searchSpec.depth() > 0) ? searchSpec.depth() : MAX_PLY;
            alphaBeta(rootBoard, searchDepth, -SCORE_MATE, SCORE_MATE, 0, true);
            pool.addNodes(this.nodesSearched - this.lastReportedNodes);
        }
    }

    private void iterativeDeepeningLoop() {
        boolean isWhite = PositionFactory.whiteToMove(rootBoard[META]);
        TimeAllocation allocation = timeManager.calculate(searchSpec, isWhite);
        long startTime = System.currentTimeMillis();
        this.stopTime = startTime + allocation.maximum();

        boolean inCheck = moveGenerator.kingAttacked(rootBoard, isWhite);
        RootMove[] rootMoves = getLegalRootMoves(rootBoard);

        if (rootMoves.length == 0) {
            this.finalResult = new SearchResult(0, 0, Collections.emptyList(),
                    inCheck ? -SCORE_MATE : 0,
                    inCheck, 0, 0, System.currentTimeMillis() - startTime);
            pool.getStopFlag().set(true);
            return;
        }

        int maxDepth = (searchSpec.depth() > 0) ? searchSpec.depth() : MAX_PLY;
        for (int currentDepth = 1; currentDepth <= maxDepth; currentDepth++) {
            searchRoot(rootMoves, currentDepth, -SCORE_MATE, SCORE_MATE);

            if (pool.getStopFlag().get()) break;

            sortRootMoves(rootMoves);
            long elapsedTime = System.currentTimeMillis() - startTime;
            if (infoHandler != null) {
                RootMove topMove = rootMoves[0];
                long totalNodes = pool.getTotalNodes() + (nodesSearched - lastReportedNodes);
                long nps = (elapsedTime > 0) ? (totalNodes * 1000 / elapsedTime) : 0;
                int hashfull = transpositionTable != null ? transpositionTable.hashfull() : -1;
                boolean isMate = Math.abs(topMove.score) > SCORE_MATE_IN_MAX_PLY;
                SearchInfo info = new SearchInfo(currentDepth, 0, 1, topMove.score, isMate,
                        totalNodes, nps, elapsedTime, topMove.pv, hashfull, 0);
                infoHandler.onInfo(info);
            }

            if (!searchSpec.infinite() && searchSpec.moveTimeMs() == 0 && elapsedTime > allocation.optimal()) {
                break;
            }
        }

        pool.getStopFlag().set(true);
        RootMove bestRootMove = rootMoves[0];
        boolean mateFound = Math.abs(bestRootMove.score) > SCORE_MATE_IN_MAX_PLY;
        int ponderMove = bestRootMove.pv.size() > 1 ? bestRootMove.pv.get(1) : 0;
        pool.addNodes(this.nodesSearched - this.lastReportedNodes);
        long totalTime = System.currentTimeMillis() - startTime;
        this.finalResult = new SearchResult(bestRootMove.move, ponderMove, bestRootMove.pv,
                bestRootMove.score, mateFound, maxDepth, pool.getTotalNodes(), totalTime);
    }

    private void sortRootMoves(RootMove[] moves) {
        Arrays.sort(moves, (a, b) -> Integer.compare(b.score, a.score));
    }

    private void searchRoot(RootMove[] rootMoves, int depth, int alpha, int beta) {
        SearchFrame rootStack = searchStack[0];
        rootStack.clear();

        for (int i = 0; i < rootMoves.length; i++) {
            RootMove rootMove = rootMoves[i];
            positionFactory.makeMoveInPlace(rootBoard, rootMove.move, moveGenerator);
            keyHistory[historyIndex++] = positionFactory.zobrist(rootBoard);

            int score;
            if (i == 0) {
                score = -alphaBeta(rootBoard, depth - 1, -beta, -alpha, 1, true);
            } else {
                score = -alphaBeta(rootBoard, depth - 1, -alpha - 1, -alpha, 1, false);
                if (score > alpha && score < beta) {
                    score = -alphaBeta(rootBoard, depth - 1, -beta, -alpha, 1, true);
                }
            }

            historyIndex--;
            positionFactory.undoMoveInPlace(rootBoard);

            if (pool.getStopFlag().get()) return;

            rootMove.score = score;
            if (score > alpha) {
                alpha = score;
                rootMove.setPvFrom(searchStack[1], rootMove.move);
            }
        }
    }


    private int alphaBeta(long[] bb, int depth, int alpha, int beta, int ply, boolean isPvNode) {
        if ((nodesSearched & 4095) == 0) {
            if (pool.getStopFlag().get() || (isMainThread && System.currentTimeMillis() >= stopTime)) {
                pool.getStopFlag().set(true);
            }
            pool.addNodes(nodesSearched - this.lastReportedNodes);
            this.lastReportedNodes = nodesSearched;
        }

        if (pool.getStopFlag().get()) return 0;
        if (ply > 0 && (isRepetition(bb) || is50MoveDraw(bb))) return 0;

        if (ply >= MAX_PLY) return evaluator.evaluate(bb);
        boolean inCheck = moveGenerator.kingAttacked(bb, PositionFactory.whiteToMove(bb[META]));
        if (depth <= 0) {
            return qSearch(bb, alpha, beta, ply);
        }

        alpha = Math.max(alpha, -SCORE_MATE + ply);
        beta = Math.min(beta, SCORE_MATE - ply - 1);
        if (alpha >= beta) return alpha;

        SearchFrame ss = searchStack[ply];
        ss.clear();
        long hash = bb[HASH];
        int ttSlot = transpositionTable.indexFor(hash);
        boolean ttHit = transpositionTable.wasHit(ttSlot);
        long ttData = ttHit ? transpositionTable.dataAt(ttSlot) : 0L;
        if (ttHit) {
            if (!isPvNode && transpositionTable.unpackDepth(ttData) >= depth) {
                int ttScore = transpositionTable.unpackScore(ttData, ply);
                int ttFlag = transpositionTable.unpackFlag(ttData);
                if ((ttFlag == FLAG_EXACT) ||
                        (ttFlag == FLAG_LOWER && ttScore >= beta) ||
                        (ttFlag == FLAG_UPPER && ttScore <= alpha)) {
                    return ttScore;
                }
            }
        }

        nodesSearched++;
        ss.staticEval = evaluator.evaluate(bb);

        int[] moves = moveStack[ply];
        int numMoves = inCheck
                ? moveGenerator.generateEvasions(bb, moves, 0)
                : moveGenerator.generateQuiets(bb, moves, moveGenerator.generateCaptures(bb, moves, 0));

        int movesMade = 0;
        int bestMove = 0;
        int originalAlpha = alpha;

        for (int i = 0; i < numMoves; i++) {
            if (positionFactory.makeMoveInPlace(bb, moves[i], moveGenerator)) {
                movesMade++;
                keyHistory[historyIndex++] = bb[HASH];

                int score;
                if (isPvNode && movesMade == 1) {
                    score = -alphaBeta(bb, depth - 1, -beta, -alpha, ply + 1, true);
                } else {
                    score = -alphaBeta(bb, depth - 1, -alpha - 1, -alpha, ply + 1, false);
                    if (score > alpha && score < beta && isPvNode) {
                        score = -alphaBeta(bb, depth - 1, -beta, -alpha, ply + 1, true);
                    }
                }

                historyIndex--;
                positionFactory.undoMoveInPlace(bb);

                if (pool.getStopFlag().get()) return 0;

                if (score >= beta) {
                    transpositionTable.store(ttSlot, hash, depth, beta, FLAG_LOWER, moves[i], ss.staticEval, false, ply);
                    return beta;
                }
                if (score > alpha) {
                    alpha = score;
                    bestMove = moves[i];
                    ss.updatePv(bestMove, searchStack[ply + 1]);
                }
            }
        }

        if (movesMade == 0) {
            return inCheck ? -(SCORE_MATE - ply) : 0;
        }

        int ttFlag = (alpha > originalAlpha) ? FLAG_EXACT : FLAG_UPPER;
        transpositionTable.store(ttSlot, hash, depth, alpha, ttFlag, bestMove, ss.staticEval, isPvNode && (ttFlag == FLAG_EXACT), ply);

        return alpha;
    }

    private int qSearch(long[] bb, int alpha, int beta, int ply) {
        if ((nodesSearched & 4095) == 0) {
            if (pool.getStopFlag().get() || (isMainThread && System.currentTimeMillis() >= stopTime)) {
                pool.getStopFlag().set(true);
            }
            pool.addNodes(nodesSearched - this.lastReportedNodes);
            this.lastReportedNodes = nodesSearched;
        }

        if (pool.getStopFlag().get()) return 0;
        if (ply >= MAX_PLY) return evaluator.evaluate(bb);
        if (isRepetition(bb) || is50MoveDraw(bb)) return 0;

        nodesSearched++;
        int standPat = evaluator.evaluate(bb);
        if (standPat >= beta) {
            return beta;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        int[] moves = moveStack[ply];
        int numMoves = moveGenerator.generateCaptures(bb, moves, 0);
        for (int i = 0; i < numMoves; i++) {
            if (positionFactory.makeMoveInPlace(bb, moves[i], moveGenerator)) {
                keyHistory[historyIndex++] = bb[HASH];
                int score = -qSearch(bb, -beta, -alpha, ply + 1);
                historyIndex--;
                positionFactory.undoMoveInPlace(bb);

                if (pool.getStopFlag().get()) return 0;
                if (score >= beta) return beta;
                if (score > alpha) alpha = score;
            }
        }

        return alpha;
    }

    private boolean isRepetition(long[] bb) {
        long currentKey = bb[HASH];
        int halfMoveClock = (int) PositionFactory.halfClock(bb[META]);
        int searchLimit = Math.min(historyIndex - 1, halfMoveClock);
        for (int i = 4; i <= searchLimit; i += 2) {
            if (keyHistory[historyIndex - 1 - i] == currentKey) {
                return true;
            }
        }
        return false;
    }

    private boolean is50MoveDraw(long[] bb) {
        return PositionFactory.halfClock(bb[META]) >= 100;
    }

    private RootMove[] getLegalRootMoves(long[] board) {
        List<Integer> legalMoveList = new ArrayList<>();
        int[] pseudoLegalMoves = moveStack[0];
        boolean isWhite = PositionFactory.whiteToMove(board[META]);

        int numPseudoLegal = moveGenerator.kingAttacked(board, isWhite)
                ? moveGenerator.generateEvasions(board, pseudoLegalMoves, 0)
                : moveGenerator.generateQuiets(board, pseudoLegalMoves, moveGenerator.generateCaptures(board, pseudoLegalMoves, 0));

        long[] boardCloneForCheck = board.clone();
        for (int i = 0; i < numPseudoLegal; i++) {
            if (positionFactory.makeMoveInPlace(boardCloneForCheck, pseudoLegalMoves[i], moveGenerator)) {
                legalMoveList.add(pseudoLegalMoves[i]);
                positionFactory.undoMoveInPlace(boardCloneForCheck);
            }
        }

        RootMove[] rootMoves = new RootMove[legalMoveList.size()];
        for(int i = 0; i < legalMoveList.size(); i++) {
            rootMoves[i] = new RootMove(legalMoveList.get(i));
        }
        return rootMoves;
    }

    @Override
    public void prepareForSearch(long[] bb, SearchSpec spec, PositionFactory pf, MoveGenerator mg, Evaluator eval, TranspositionTable tt, TimeManager tm) {
        this.rootBoard = bb.clone();
        this.searchSpec = spec;
        this.positionFactory = pf;
        this.moveGenerator = mg;
        this.evaluator = eval;
        this.transpositionTable = tt;
        this.timeManager = tm;
        this.finalResult = new SearchResult(0, 0, Collections.emptyList(), 0, false, 0, 0, 0);
    }

    @Override
    public void setInfoHandler(InfoHandler handler) {
        if (isMainThread) {
            this.infoHandler = handler;
        }
    }

    @Override
    public void terminate() {
        terminateFlag.set(true);
        thread.interrupt();
    }

    @Override
    public void join() throws InterruptedException {
        thread.join();
    }

    @Override
    public SearchResult getSearchResult() {
        return finalResult;
    }
}