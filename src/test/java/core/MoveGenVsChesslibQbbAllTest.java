package core;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import core.contracts.MoveGenerator;
import core.contracts.PositionFactory;
import core.impl.MoveGeneratorImpl;
import core.impl.PositionFactoryImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static core.contracts.PositionFactory.*;

/**
 * Cross-check your MoveGeneratorImpl/PositionFactoryImpl against chesslib, using qbbAll.txt like the reference test.
 *
 * For each vector (FEN, depth), at every visited node we assert:
 *  - 12 per-piece bitboards equal (and thus occupancy),
 *  - in-check parity,
 *  - identical legal-move set (UCI),
 *  - and for EVERY legal move we validate the packed fields (from,to,capIdx,mover,type,promo).
 *
 * Depth can be capped with -Dqbb.maxDepth=<n> (defaults to Integer.MAX_VALUE).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGenVsChesslibQbbAllTest {

    /* wiring identical to reference tests */
    private static final PositionFactory POS = new PositionFactoryImpl();
    private static final MoveGenerator   GEN = new MoveGeneratorImpl();

    /* qbbAll.txt has 7 columns; we only need fen+depth but we parse all to mirror the ref loader */
    private record Vec(String fen, int depth, long expNodes, long expPseudo,
                       long expCaps, long expQuiets, long expEvas) {}
    private List<Vec> vecs;

    @BeforeAll
    void load() throws Exception {
        vecs = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/perft/qbbAll.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(
                     Objects.requireNonNull(in, "qbbAll.txt not on classpath")))) {
            br.lines().map(String::trim)
                    .filter(l -> !(l.isEmpty() || l.startsWith("#")))
                    .forEach(l -> {
                        String[] p = l.split(";");
                        if (p.length < 7) return;
                        vecs.add(new Vec(
                                p[0].trim(),
                                Integer.parseInt(p[1].replaceAll("\\D","")),
                                Long.parseLong(p[2].replaceAll("\\D","")),
                                Long.parseLong(p[3].replaceAll("\\D","")),
                                Long.parseLong(p[4].replaceAll("\\D","")),
                                Long.parseLong(p[5].replaceAll("\\D","")),
                                Long.parseLong(p[6].replaceAll("\\D",""))));
                    });
        }
        Assertions.assertFalse(vecs.isEmpty(), "qbbAll.txt missing / empty");
    }

    Stream<Vec> vecStream(){ return vecs.stream(); }

    /* move list scratch (per ply) like your perft tests */
    private static final int MAX_PLY  = 64;
    private static final int LIST_CAP = 256;
    private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

    /* ------- main parameterized test --------------------------------------- */
    @ParameterizedTest(name="qbbAll parity {index}")
    @MethodSource("vecStream")
    void parityAgainstChesslib(Vec v) {
        // allow capping via system property if desired
        int maxDepth = Integer.getInteger("qbb.maxDepth", Integer.MAX_VALUE);
        int depth = Math.min(v.depth(), maxDepth);

        // set up both boards
        String fen = v.fen();
        if ("startpos".equalsIgnoreCase(fen)) {
            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        }
        long[] bb = POS.fromFen(fen);
        Board board = new Board();
        board.setEnableEvents(false);
        board.loadFromFen(fen);

        // initial checks
        assertBitboardsEqual(bb, board, fen);
        assertInCheckAgree(bb, board, fen);

        // recurse and fully check parity (moves + DIFF) at every node
        dfs(bb, board, depth, 0);
    }

    /* ------- depth-first cross-check -------------------------------------- */
    private void dfs(long[] bb, Board board, int depth, int ply) {
        // 1) Generate your move list exactly like xPerft: evasions in check else caps+quiets
        boolean stmWhite = (bb[META] & 1L) == 0;
        boolean inCheck  = GEN.kingAttacked(bb, stmWhite);

        int[] moves = MOVES[ply];
        int total;
        if (inCheck) {
            total = GEN.generateEvasions(bb, moves, 0);
        } else {
            int caps = GEN.generateCaptures(bb, moves, 0);
            total = GEN.generateQuiets(bb, moves, caps);
        }

        // 2) Filter to LEGAL (by making/undoing), collect UCI and validate DIFF for every move
        Map<String, Integer> ourLegal = new LinkedHashMap<>();
        List<Move> chesslibLegal = board.legalMoves();
        Set<String> theirUci = chesslibLegal.stream().map(this::uciOf).collect(Collectors.toCollection(LinkedHashSet::new));

        for (int i = 0; i < total; i++) {
            int mv = moves[i];
            String u = uciOf(mv);

            // Build chesslib Move for detailed validation (promotion handled in uciOf/parse)
            Move cm = fromUciOnBoard(board, u);

            // Before making the move, validate packed fields (mover, type, promo, capIdx) against current state
            assertMoveFieldsSemantics(bb, board, mv, cm, u);

            // Try to make legal — use your legality filter (false if illegal or leaves own king in check)
            if (!POS.makeMoveInPlace(bb, mv, GEN)) {
                // illegal — must NOT appear in chesslib legal set
                Assertions.assertFalse(theirUci.contains(u),
                        "Your generator produced illegal move present in chesslib: " + u + "\nFEN: " + POS.toFen(bb));
                continue;
            }

            // Legal — must be in chesslib set
            Assertions.assertTrue(theirUci.remove(u),
                    "Missing move in chesslib legal set: " + u + "\nFEN(before): " + board.getFen());

            ourLegal.put(u, mv);

            // After both sides play the SAME move, positions must match; recurse if depth>1
            boolean ok = board.doMove(cm);
            Assertions.assertTrue(ok, "Chesslib failed to make move " + cm + " from " + board.getFen());

            // Positions must match (piece bitboards + STM)
            assertBitboardsEqual(bb, board, "after move " + u);
            assertInCheckAgree(bb, board, "after move " + u);

            if (depth > 1) {
                dfs(bb, board, depth - 1, ply + 1);
            }

            // Undo both
            POS.undoMoveInPlace(bb);
            Move undone = board.undoMove();
            Assertions.assertNotNull(undone, "Chesslib failed to undo");
        }

        // 3) Any leftover chesslib moves are moves you missed
        if (!theirUci.isEmpty()) {
            Assertions.fail("Extra moves in chesslib (missing from yours): " + theirUci + "\nFEN: " + board.getFen());
        }
    }

    /* ------- DIFF / packed-fields validation per move ---------------------- */
    private void assertMoveFieldsSemantics(long[] bb, Board board, int mv, Move cm, String uci) {
        int from  = (mv >>>  6) & 0x3F;
        int to    =  mv         & 0x3F;
        int type  = (mv >>> 14) & 0x3;
        int promo = (mv >>> 12) & 0x3;
        int mover = (mv >>> 16) & 0xF;

        // from/to squares match UCI/chesslib
        Assertions.assertEquals(from, cm.getFrom().ordinal(), "from mismatch for " + uci);
        Assertions.assertEquals(to,   cm.getTo().ordinal(),   "to mismatch for " + uci);

        // mover must equal actual piece occupying 'from' in your bitboards AND match chesslib's piece kind/side
        int ourPieceIdx = pieceAt(bb, from);
        Assertions.assertEquals(mover, ourPieceIdx, "mover index mismatch for " + uci);

        Piece chessPiece = board.getPiece(Square.squareAt(from));
        Assertions.assertNotEquals(Piece.NONE, chessPiece, "no chesslib piece at from for " + uci);
        Assertions.assertEquals(chessPieceIndex(chessPiece), mover, "mover vs chesslib piece mismatch for " + uci);

        // type/promo:
        boolean isCastle = (from == 4 && (to == 6 || to == 2)) || (from == 60 && (to == 62 || to == 58));
        boolean isPromo = cm.getPromotion() != Piece.NONE;
        boolean isEp = isEnPassantPreMove(board, cm);

        int expType = isCastle ? 3 : (isEp ? 2 : (isPromo ? 1 : 0));
        Assertions.assertEquals(expType, type, "type mismatch for " + uci);

        if (isPromo) {
            int expPromo = promoIndex(cm.getPromotion().getPieceType());
            Assertions.assertEquals(expPromo, promo, "promo kind mismatch for " + uci);
        } else {
            Assertions.assertEquals(0, promo, "promo bits must be zero when not a promotion: " + uci);
        }

        // capIdx must reflect EP pawn or destination occupant (else 15)
        int expCapIdx = 15;
        if (isEp) {
            expCapIdx = (mover < 6) ? BP : WP;
        } else {
            Piece dst = board.getPiece(cm.getTo());
            if (dst != Piece.NONE && dst.getPieceSide() != chessPiece.getPieceSide()) {
                expCapIdx = chessPieceIndex(dst);
            }
        }
        // Make the move to read DIFF and verify stored capIdx too
        long beforeHash = bb[HASH];
        boolean made = POS.makeMoveInPlace(bb, mv, GEN);
        if (made) {
            int dfCap = dfCap(bb[DIFF_INFO]);
            Assertions.assertEquals(expCapIdx, dfCap, "capIdx mismatch for " + uci);

            // Also re-check DIFF basic fields match the move we just made
            Assertions.assertEquals(from, dfFrom(bb[DIFF_INFO]), "DIFF.from mismatch " + uci);
            Assertions.assertEquals(to,   dfTo  (bb[DIFF_INFO]), "DIFF.to mismatch "   + uci);
            Assertions.assertEquals(mover,dfMover(bb[DIFF_INFO]),"DIFF.mover mismatch "+ uci);
            Assertions.assertEquals(type, dfType (bb[DIFF_INFO]),"DIFF.type mismatch " + uci);
            Assertions.assertEquals(promo,dfPromo(bb[DIFF_INFO]),"DIFF.promo mismatch "+ uci);

            // undo immediately to keep caller's flow clean
            POS.undoMoveInPlace(bb);
        } else {
            // illegal – caller will assert set parity; still check expected cap for debug context
            Assertions.assertFalse(board.legalMoves().contains(cm),
                    "Illegal on your side but legal on chesslib: " + uci);
        }
        // hash untouched after our test make/undo
        Assertions.assertEquals(beforeHash, bb[HASH], "HASH changed after test make/undo for " + uci);
    }

    /* ------- helpers ------------------------------------------------------- */

    private static int pieceAt(long[] bb, int sq) {
        long bit = 1L << sq;
        for (int i = WP; i <= BK; i++) if ((bb[i] & bit) != 0L) return i;
        return -1;
    }

    private static boolean isEnPassantPreMove(Board b, Move m) {
        // EP if moving pawn, diagonal move to the EP destination square
        Piece p = b.getPiece(m.getFrom());
        if (p.getPieceType() != PieceType.PAWN) return false;
        if (b.getEnPassant() == Square.NONE) return false;
        if (m.getTo() != b.getEnPassant()) return false;
        // destination is empty pre-move (normal captures would have a piece)
        return b.getPiece(m.getTo()) == Piece.NONE;
    }

    private static int chessPieceIndex(Piece p) {
        return switch (p) {
            case WHITE_PAWN   -> WP;
            case WHITE_KNIGHT -> WN;
            case WHITE_BISHOP -> WB;
            case WHITE_ROOK   -> WR;
            case WHITE_QUEEN  -> WQ;
            case WHITE_KING   -> WK;
            case BLACK_PAWN   -> BP;
            case BLACK_KNIGHT -> BN;
            case BLACK_BISHOP -> BB;
            case BLACK_ROOK   -> BR;
            case BLACK_QUEEN  -> BQ;
            case BLACK_KING   -> BK;
            default -> -1;
        };
    }

    private static int promoIndex(PieceType t) {
        // your encoding: promo bits 0..3 map to N,B,R,Q via "nbrq".charAt(3-p) in moveToUci
        return switch (t) {
            case KNIGHT -> 0;
            case BISHOP -> 1;
            case ROOK   -> 2;
            case QUEEN  -> 3;
            default     -> 0; // should not happen for promotions
        };
    }

    private Move fromUciOnBoard(Board board, String uci) {
        String s = uci.trim().toLowerCase();
        Square from = Square.valueOf(s.substring(0,2).toUpperCase());
        Square to   = Square.valueOf(s.substring(2,4).toUpperCase());
        Piece promo = Piece.NONE;
        if (s.length() == 5) {
            char c = s.charAt(4);
            Side side = board.getSideToMove();
            promo = switch (c) {
                case 'q' -> Piece.make(side, PieceType.QUEEN);
                case 'r' -> Piece.make(side, PieceType.ROOK);
                case 'b' -> Piece.make(side, PieceType.BISHOP);
                case 'n' -> Piece.make(side, PieceType.KNIGHT);
                default -> Piece.NONE;
            };
        }
        return new Move(from, to, promo);
    }

    private String uciOf(Move m) {
        String u = sq(m.getFrom().ordinal()) + sq(m.getTo().ordinal());
        if (m.getPromotion() != Piece.NONE) {
            char c = switch (m.getPromotion().getPieceType()) {
                case QUEEN -> 'q';
                case ROOK  -> 'r';
                case BISHOP-> 'b';
                case KNIGHT-> 'n';
                default    -> 0;
            };
            if (c != 0) u += c;
        }
        return u;
    }

    private String uciOf(int mv) {
        int from  = (mv>>>6) & 63;
        int to    =  mv      & 63;
        int type  = (mv>>>14)&3;
        int promo = (mv>>>12)&3;
        String s = sq(from) + sq(to);
        if (type == 1) s += "nbrq".charAt(3 - promo);
        return s;
    }

    private static String sq(int sq){
        return "" + (char)('a'+(sq&7)) + (char)('1'+(sq>>>3));
    }

    private void assertBitboardsEqual(long[] bb, Board board, String where) {
        Assertions.assertEquals(board.getBitboard(Piece.WHITE_PAWN),   bb[WP], "WP mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.WHITE_KNIGHT), bb[WN], "WN mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.WHITE_BISHOP), bb[WB], "WB mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.WHITE_ROOK),   bb[WR], "WR mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.WHITE_QUEEN),  bb[WQ], "WQ mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.WHITE_KING),   bb[WK], "WK mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.BLACK_PAWN),   bb[BP], "BP mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.BLACK_KNIGHT), bb[BN], "BN mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.BLACK_BISHOP), bb[BB], "BB mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.BLACK_ROOK),   bb[BR], "BR mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.BLACK_QUEEN),  bb[BQ], "BQ mismatch @ " + where);
        Assertions.assertEquals(board.getBitboard(Piece.BLACK_KING),   bb[BK], "BK mismatch @ " + where);

        long ourOcc   = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]|bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
        long theirOcc = board.getBitboard();
        Assertions.assertEquals(theirOcc, ourOcc, "OCC mismatch @ " + where);

        boolean stmWhite = (bb[META] & 1L) == 0;
        Assertions.assertEquals(stmWhite ? Side.WHITE : Side.BLACK, board.getSideToMove(), "STM mismatch @ " + where);
    }

    private void assertInCheckAgree(long[] bb, Board board, String where) {
        boolean stmWhite = (bb[META] & 1L) == 0;
        boolean ours = GEN.kingAttacked(bb, stmWhite);
        boolean theirs = board.isKingAttacked();
        Assertions.assertEquals(theirs, ours, "in-check disagreement @ " + where);
    }

    /* --- DIFF field readers (mirror PositionFactoryImpl.packDiff layout) --- */
    private static int dfFrom (long d){ return (int)(d & 0x3F); }
    private static int dfTo   (long d){ return (int)((d >>> 6) & 0x3F); }
    private static int dfCap  (long d){ return (int)((d >>> 12) & 0x0F); }
    private static int dfMover(long d){ return (int)((d >>> 16) & 0x0F); }
    private static int dfType (long d){ return (int)((d >>> 20) & 0x03); }
    private static int dfPromo(long d){ return (int)((d >>> 22) & 0x03); }
}
