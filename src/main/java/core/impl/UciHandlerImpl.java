package core.impl;

import core.contracts.*;
import core.impl.MoveGeneratorImpl;
import core.records.SearchInfo;
import core.records.SearchSpec;
import core.records.SearchResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Implements the UciHandler contract to manage UCI protocol communication.
 */
public class UciHandlerImpl implements UciHandler {

    private final Search search;
    private final PositionFactory positionFactory;
    private final UciOptions options;

    private long[] currentPosition;
    private final List<Long> history = new ArrayList<>();

    private CompletableFuture<SearchResult> searchFuture;

    public UciHandlerImpl(Search search, PositionFactory positionFactory, UciOptions options) {
        this.search = search;
        this.positionFactory = positionFactory;
        this.options = options;
        this.currentPosition = positionFactory.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    @Override
    public void runLoop() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                if (!scanner.hasNextLine()) break;

                String line = scanner.nextLine();
                if (line == null) continue;

                String[] tokens = line.trim().split("\\s+");
                if (tokens.length == 0 || tokens[0].isEmpty()) continue;

                if (processCommand(line, tokens)) {
                    break;
                }
            }
        }
    }

    private boolean processCommand(String line, String[] tokens) {
        switch (tokens[0].toLowerCase()) {
            case "uci": handleUci(); break;
            case "isready": handleIsReady(); break;
            case "ucinewgame": handleUciNewGame(); break;
            case "position": handlePosition(tokens); break;
            case "go": handleGo(tokens); break;
            case "stop": handleStop(); break;
            case "ponderhit": break;
            case "setoption": handleSetOption(line); break;
            case "quit": return true;
            default: System.out.println("info string Unknown command: " + line); break;
        }
        return false;
    }

    private void handleUci() {
        System.out.println("id name Helios");
        System.out.println("id author Your Name");
        options.printOptions();
        System.out.println("uciok");
    }

    private void handleIsReady() {
        System.out.println("readyok");
    }

    private void handleUciNewGame() {
        options.getTranspositionTable().clear();
    }

    private void handlePosition(String[] tokens) {
        int movesTokenIndex = -1;
        for (int i = 0; i < tokens.length; i++) {
            if ("moves".equals(tokens[i])) {
                movesTokenIndex = i;
                break;
            }
        }

        if ("startpos".equals(tokens[1])) {
            currentPosition = positionFactory.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        } else if ("fen".equals(tokens[1])) {
            int fenEnd = (movesTokenIndex != -1) ? movesTokenIndex : tokens.length;
            StringBuilder fenBuilder = new StringBuilder();
            for (int i = 2; i < fenEnd; i++) {
                fenBuilder.append(tokens[i]).append(" ");
            }
            currentPosition = positionFactory.fromFen(fenBuilder.toString().trim());
        }

        history.clear();
        history.add(currentPosition[PositionFactory.HASH]);

        if (movesTokenIndex != -1) {
            MoveGenerator moveGenerator = new MoveGeneratorImpl();
            for (int i = movesTokenIndex + 1; i < tokens.length; i++) {
                int move = UciMoveConverter.uciToMove(currentPosition, tokens[i], moveGenerator);
                if (move != 0) {
                    if (positionFactory.makeMoveInPlace(currentPosition, move, moveGenerator)) {
                        history.add(currentPosition[PositionFactory.HASH]);
                    }
                }
            }
        }
    }

    private void handleGo(String[] tokens) {
        SearchSpec.Builder specBuilder = new SearchSpec.Builder();
        for (int i = 1; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "wtime": if (i + 1 < tokens.length) specBuilder.wTimeMs(Long.parseLong(tokens[++i])); break;
                case "btime": if (i + 1 < tokens.length) specBuilder.bTimeMs(Long.parseLong(tokens[++i])); break;
                case "winc": if (i + 1 < tokens.length) specBuilder.wIncMs(Long.parseLong(tokens[++i])); break;
                case "binc": if (i + 1 < tokens.length) specBuilder.bIncMs(Long.parseLong(tokens[++i])); break;
                case "movestogo": if (i + 1 < tokens.length) specBuilder.movesToGo(Integer.parseInt(tokens[++i])); break;
                case "depth": if (i + 1 < tokens.length) specBuilder.depth(Integer.parseInt(tokens[++i])); break;
                case "nodes": if (i + 1 < tokens.length) specBuilder.nodes(Long.parseLong(tokens[++i])); break;
                case "movetime": if (i + 1 < tokens.length) specBuilder.moveTimeMs(Long.parseLong(tokens[++i])); break;
                case "infinite": specBuilder.infinite(true); break;
                case "ponder": specBuilder.ponder(true); break;
            }
        }
        specBuilder.history(new ArrayList<>(history));

        if (searchFuture != null && !searchFuture.isDone()) search.stop();
        options.getTranspositionTable().incrementAge();
        searchFuture = search.searchAsync(currentPosition.clone(), specBuilder.build(), this::handleSearchInfo);
        searchFuture.thenAccept(this::handleSearchResult).exceptionally(ex -> {
            System.out.println("info string Search failed: " + ex.getMessage());
            ex.printStackTrace();
            searchFuture = null; // Also reset on exception
            return null;
        }).thenRun(() -> {
            searchFuture = null; // Reset after the search completes, either successfully or exceptionally
        });
    }

    private void handleStop() {
        search.stop();
        if (searchFuture != null) {
            searchFuture.join();
        }
    }

    private void handleSetOption(String line) {
        options.setOption(line);
    }

    private void handleSearchInfo(SearchInfo info) {
        StringBuilder sb = new StringBuilder("info");
        sb.append(" depth ").append(info.depth());
        if (info.selDepth() > 0) sb.append(" seldepth ").append(info.selDepth());
        sb.append(" score ").append(scoreToUci(info.scoreCp(), info.isMate()));
        sb.append(" nodes ").append(info.nodes());
        sb.append(" nps ").append(info.nps());
        sb.append(" time ").append(info.timeMs());
        if (info.hashFullPermil() >= 0) sb.append(" hashfull ").append(info.hashFullPermil());
        if (info.tbHits() > 0) sb.append(" tbhits ").append(info.tbHits());

        if (info.pv() != null && !info.pv().isEmpty()) {
            sb.append(" pv");
            for (Integer move : info.pv()) {
                sb.append(" ").append(UciMoveConverter.moveToUci(move));
            }
        }
        System.out.println(sb.toString());
    }

    private void handleSearchResult(SearchResult result) {
        String bestMoveUci = UciMoveConverter.moveToUci(result.bestMove());
        String ponderMoveUci = (result.ponderMove() != 0) ? " ponder " + UciMoveConverter.moveToUci(result.ponderMove()) : "";
        System.out.println("bestmove " + bestMoveUci + ponderMoveUci);
    }

    private String scoreToUci(int score, boolean isMate) {
        if (isMate) {
            int mateIn = (core.constants.CoreConstants.SCORE_MATE - Math.abs(score) + 1) / 2;
            if (score < 0) mateIn = -mateIn;
            return "mate " + mateIn;
        }
        return "cp " + score;
    }

    private static class UciMoveConverter {
        static String moveToUci(int move) {
            if (move == 0) return "0000";
            int from = (move >>> 6) & 0x3F;
            int to = move & 0x3F;
            int promoType = (move >>> 12) & 0x3;
            int moveType = (move >>> 14) & 0x3;
            String uci = squareToString(from) + squareToString(to);
            if (moveType == 1) { // Promotion
                uci += switch (promoType) {
                    case 3 -> "q"; case 2 -> "r"; case 1 -> "b";
                    default -> "n";
                };
            }
            return uci;
        }

        static int uciToMove(long[] position, String uci, MoveGenerator moveGenerator) {
            int[] moves = new int[256];
            boolean isWhite = PositionFactory.whiteToMove(position[PositionFactory.META]);
            int numMoves = moveGenerator.kingAttacked(position, isWhite)
                    ? moveGenerator.generateEvasions(position, moves, 0)
                    : moveGenerator.generateQuiets(position, moves, moveGenerator.generateCaptures(position, moves, 0));

            for (int i = 0; i < numMoves; i++) {
                if (moveToUci(moves[i]).equals(uci)) {
                    return moves[i];
                }
            }
            return 0;
        }

        static String squareToString(int sq) {
            if (sq < 0 || sq > 63) return "";
            char file = (char) ('a' + (sq % 8));
            char rank = (char) ('1' + (sq / 8));
            return "" + file + rank;
        }
    }
}