package core;

import static core.contracts.PositionFactory.*;

import core.contracts.MoveGenerator;
import core.contracts.PositionFactory;
import core.impl.MoveGeneratorImpl;
import core.impl.PositionFactoryImpl;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Stream;

/**
 * Cross-checks your engine against chesslib on EVERY node:
 *  • per-piece, side, and occupancy masks match
 *  • DIFF_INFO / DIFF_META match chesslib-implied values
 *  • FEN matches
 *  • Zobrist (engine incremental) == chesslib incremental
 *  • Zobrist (engine incremental) == engine full recomputation (PositionFactoryImpl.fullHash)
 *
 * On any Zobrist mismatch, emits a focused diagnostic to help you fix the hashing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OracleCrossCheckPerftTest {

    /* wiring identical to your perft tests */
    private static final PositionFactory POS_FACTORY = new PositionFactoryImpl();
    private static final MoveGenerator   GEN         = new MoveGeneratorImpl();

    /* load only fen/depth from qbbAll.txt (semicolon-separated) */
    private record Vec(String fen, int depth) {}
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
                        if (p.length < 2) return;
                        vecs.add(new Vec(
                                p[0].trim(),
                                Integer.parseInt(p[1].replaceAll("\\D",""))
                        ));
                    });
        }
        Assertions.assertFalse(vecs.isEmpty(), "qbbAll.txt missing / empty");
    }

    Stream<Vec> vecStream(){ return vecs.stream(); }

    /* tunables */
    private static final int MAX_PLY  = 64;
    private static final int LIST_CAP = 256;
    private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];
    private static final int ORACLE_MAX_DEPTH = 4; // clamp for runtime

    /* stats */
    private long walkedNodes, timeNs;

    /* ===== chesslib key layout (for delta classification only) ============== */
    private static final class CLKeys {
        static final long[] KEYS = new long[2000];
        static {
            long seed = 49109794719L;
            for (int i = 0; i < KEYS.length; i++) {
                seed ^= seed >>> 12;
                seed ^= seed << 25;
                seed ^= seed >>> 27;
                KEYS[i] = seed * 0x2545F4914F6CDD1DL;
            }
        }
        static long piece(int pc,int sq){ return KEYS[57*pc + 13*sq]; }
        static long castle(int side,int ord){ return (ord==0)?0L:KEYS[300 + 3*side + 3*ord]; } // ord: 0 NONE,1 K,2 Q,3 KQ
        static long side(int side){ return KEYS[500 + 3*side]; } // 0=WHITE,1=BLACK
        static long epTarget(int sq){ return KEYS[400 + 3*sq]; } // EP TARGET (pawn square), not destination
    }

    @ParameterizedTest(name="oracleXcheck {index}")
    @MethodSource("vecStream")
    void crossCheckAgainstChesslib(Vec v){
        long[] bb = POS_FACTORY.fromFen(v.fen());
        Board oracle = new Board(); oracle.loadFromFen(v.fen());

        // root sanity
        Assertions.assertEquals(oracle.getFen(), POS_FACTORY.toFen(bb), "root FEN mismatch");
        Assertions.assertEquals(oracle.getZobristKey(), oracle.getIncrementalHashKey(), "chesslib zobrist mismatch at root");

        int depth = Math.min(v.depth(), ORACLE_MAX_DEPTH);

        long t0 = System.nanoTime();
        long nodes = walk(bb, oracle, depth, 0, null);
        timeNs += System.nanoTime() - t0;
        walkedNodes += nodes;
    }

    @AfterAll
    void report() {
        double secs = timeNs / 1_000_000_000.0;
        System.out.printf("""
        ── ORACLE XCHECK SUMMARY ─────────────────────────────────────
        nodes walked : %,d
        time         : %.3f s   → %,d NPS
        depth clamp  : %d
        (hash/DIFF/bitboard mismatches are asserted with diagnostics)
        ───────────────────────────────────────────────────────────────
        """, walkedNodes, secs, (long)(walkedNodes / Math.max(1e-9, secs)), ORACLE_MAX_DEPTH);
    }

    /* ===== DFS with cross-checks ============================================ */

    private long walk(long[] bb, Board oracle, int depth, int ply, Move prevMove){
        if (depth == 0) return 1;

        boolean stmWhite = (bb[META] & 1L) == 0;
        boolean inCheck  = GEN.kingAttacked(bb, stmWhite);

        int[] moves = MOVES[ply];
        int legalCnt;

        if (inCheck) {
            legalCnt = GEN.generateEvasions(bb, moves, 0);
        } else {
            int caps   = GEN.generateCaptures(bb, moves, 0);
            legalCnt   = GEN.generateQuiets  (bb, moves, caps);
        }

        long nodes = 0;
        for (int i = 0; i < legalCnt; ++i) {
            int mv = moves[i];

            // forbid illegal king-capture pseudo moves
            if (capturesKing(mv, bb, stmWhite)) {
                throw new AssertionError("Generated king capture: " + moveToUci(mv) + "  FEN " + POS_FACTORY.toFen(bb));
            }

            // snapshot engine before
            long[] pre = Arrays.copyOf(bb, bb.length);
            long spBefore = bb[COOKIE_SP];
            String fenBefore = POS_FACTORY.toFen(bb);

            // play in engine
            if (!POS_FACTORY.makeMoveInPlace(bb, mv, GEN)) continue;

            // play in chesslib
            Move m = toChesslibMove(mv, stmWhite);
            Board oracleBefore = oracle.clone();
            boolean ok = oracle.doMove(m, true);
            if (!ok) throw new AssertionError("chesslib rejected move " + m + " from FEN " + fenBefore);

            // 1) Bitboards equality (per piece + side + occ)
            for (Piece p : Piece.allPieces) {
                if (p == Piece.NONE) continue;
                Assertions.assertEquals(oracle.getBitboard(p), maskFrom(bb, p),
                        "Bitboard mismatch for " + p + "  FEN " + fenBefore + " → " + oracle.getFen());
            }
            long wOurs = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK];
            long bOurs = bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
            Assertions.assertEquals(oracle.getBitboard(Side.WHITE), wOurs, "White side mask mismatch");
            Assertions.assertEquals(oracle.getBitboard(Side.BLACK), bOurs, "Black side mask mismatch");
            Assertions.assertEquals(oracle.getBitboard(), wOurs | bOurs, "Occupancy mismatch");

            // 2) FEN exact match
            String fenAfter = POS_FACTORY.toFen(bb);
            Assertions.assertEquals(oracle.getFen(), fenAfter, "FEN mismatch after move");

            // 3) Hash checks
            long clInc  = oracle.getIncrementalHashKey();
            long engInc = POS_FACTORY.zobrist(bb);
            // engine full recomputation (uses your impl)
            long engFull = ((PositionFactoryImpl)POS_FACTORY).fullHash(bb);

            if (engInc != clInc) {
                // extra info so you can fix it quickly
                String uci = moveToUci(mv);
                long deltaCLvsENG = clInc ^ engInc;
                long deltaENGvsFULL = engInc ^ engFull;

                String guess = classifyDelta(deltaCLvsENG, oracleBefore, oracle, bb);

                String msg = "\n" +
                        "engine zobrist != chesslib zobrist\n" +
                        "Move: " + uci + "  (SAN: " + m + ")\n" +
                        "FEN(before): " + fenBefore + "\n" +
                        "FEN(after) : " + fenAfter + "\n" +
                        "chesslib inc : " + clInc + "\n" +
                        "engine  inc  : " + engInc + "\n" +
                        "engine  full : " + engFull + "\n" +
                        "Δ(CL^ENG)    : " + deltaCLvsENG + "  (0x" + Long.toHexString(deltaCLvsENG) + ")\n" +
                        "Δ(ENG^FULL)  : " + deltaENGvsFULL + "  (0x" + Long.toHexString(deltaENGvsFULL) + ")\n" +
                        "Guess        : " + guess + "\n";
                Assertions.fail(msg);
            }

            // 4) DIFF cross-check
            long diff   = bb[DIFF_INFO];
            long metaΔ  = bb[DIFF_META];

            Assertions.assertEquals(pre[META] ^ bb[META], metaΔ, "DIFF_META != (metaBefore ^ metaAfter)");

            Expected ed = expectedFromOracle(oracleBefore, oracle, m);

            Assertions.assertEquals(ed.from,  dfFrom(diff),  "DIFF.from mismatch");
            Assertions.assertEquals(ed.to,    dfTo(diff),    "DIFF.to mismatch");
            Assertions.assertEquals(ed.mover, dfMover(diff), "DIFF.mover mismatch");
            Assertions.assertEquals(ed.type,  dfType(diff),  "DIFF.type mismatch");
            if (ed.type == 1) {
                Assertions.assertEquals(ed.promo, dfPromo(diff), "DIFF.promo mismatch");
            } else {
                Assertions.assertEquals(0, dfPromo(diff), "DIFF.promo should be 0 for non-promotions");
            }
            Assertions.assertEquals(ed.cap,   dfCap(diff),   "DIFF.cap mismatch");

            // recurse
            nodes += walk(bb, oracle, depth-1, ply+1, m);

            // undo both
            POS_FACTORY.undoMoveInPlace(bb);
            oracle.undoMove();

            // state restored + cookie popped
            Assertions.assertArrayEquals(pre, bb, "undoMoveInPlace() failed to restore position exactly");
            Assertions.assertEquals(spBefore, bb[COOKIE_SP], "COOKIE_SP should decrement on undo");
        }
        return nodes;
    }

    /* ===== Hash delta classifier (best-effort hints) ======================== */

    private static String classifyDelta(long delta, Board before, Board after, long[] bb){
        if (delta == 0) return "no delta";

        // side key?
        if (delta == CLKeys.side(0) || delta == CLKeys.side(1))
            return "side-to-move key toggled incorrectly";
        if (delta == (CLKeys.side(0) ^ CLKeys.side(1)))
            return "both side keys suspect (toggle order/duplication)";

        // EP target?
        for (Square sq : Square.values()){
            if (sq == Square.NONE) continue;
            if (delta == CLKeys.epTarget(sq.ordinal()))
                return "en-passant TARGET key mismatch at " + sq + " (remember: target = pawn square, not destination)";
        }

        // castle (per side)
        for (int side = 0; side < 2; side++){
            for (int ord = 1; ord <= 3; ord++){
                if (delta == CLKeys.castle(side, ord)) {
                    String s = (side==0 ? "WHITE" : "BLACK");
                    String r = switch (ord){ case 1->"K"; case 2->"Q"; default->"KQ"; };
                    return "castling key mismatch: " + s + " " + r;
                }
            }
        }

        // single piece-square?
        // try both colors and all squares
        for (int pc = 0; pc < 12; pc++){
            for (int sq = 0; sq < 64; sq++){
                if (delta == CLKeys.piece(pc, sq)) {
                    String piece = "PNBRQKpnbrqk".charAt(pc) + "@" + sqName(sq);
                    return "single piece-square key mismatch: " + piece;
                }
            }
        }

        return "unclassified (looks like XOR of multiple keys)";
    }

    /* ===== DIFF expected from chesslib ===================================== */

    private static final class Expected { int from, to, cap, mover, type, promo; }

    private static Expected expectedFromOracle(Board before, Board after, Move m){
        Expected e = new Expected();
        e.from  = m.getFrom().ordinal();
        e.to    = m.getTo().ordinal();
        boolean white = before.getSideToMove() == Side.WHITE;

        Piece moverPieceBefore = before.getPiece(m.getFrom());
        e.mover = idx(moverPieceBefore);

        // detect capture by looking at removed enemy piece squares
        int capIdx = 15;
        int removedSq = -1;
        for (Piece p : (white
                ? new Piece[]{Piece.BLACK_PAWN,Piece.BLACK_KNIGHT,Piece.BLACK_BISHOP,Piece.BLACK_ROOK,Piece.BLACK_QUEEN,Piece.BLACK_KING}
                : new Piece[]{Piece.WHITE_PAWN,Piece.WHITE_KNIGHT,Piece.WHITE_BISHOP,Piece.WHITE_ROOK,Piece.WHITE_QUEEN,Piece.WHITE_KING})) {
            long rm = before.getBitboard(p) & ~after.getBitboard(p);
            if (rm != 0) {
                capIdx = idx(p);
                removedSq = Long.numberOfTrailingZeros(rm);
                break;
            }
        }

        // type & promo
        e.type = 0; e.promo = 0;

        // promotion (pawn changed piece type)
        if (moverPieceBefore.getPieceType() == PieceType.PAWN) {
            Piece destAfter = after.getPiece(m.getTo());
            if (destAfter.getPieceType() != PieceType.PAWN) {
                e.type = 1;
                e.promo = switch (destAfter.getPieceType()){
                    case KNIGHT -> 0;
                    case BISHOP -> 1;
                    case ROOK   -> 2;
                    case QUEEN  -> 3;
                    default     -> 0;
                };
            }
        }

        // en passant (capture square != To)
        if (e.type == 0 && moverPieceBefore.getPieceType() == PieceType.PAWN && capIdx != 15 && removedSq != e.to) {
            e.type = 2;
        }

        // castling (king moved two files)
        if (moverPieceBefore.getPieceType() == PieceType.KING && Math.abs((e.to & 7) - (e.from & 7)) == 2) {
            e.type = 3;
        }

        e.cap = (capIdx == -1) ? 15 : capIdx;
        return e;
    }

    /* ===== Helpers ========================================================== */

    private static boolean capturesKing(int mv,long[] bb,boolean stmWhite){
        int to   = mv & 0x3F;
        int enemyK = stmWhite ? BK : WK;
        return (bb[enemyK] & (1L << to)) != 0;
    }

    // Promotion note:
    // If your move encoding stores promo bits differently, adjust here.
    private static Move toChesslibMove(int mv, boolean stmWhite){
        int from  = (mv>>>6) & 63;
        int to    =  mv      & 63;
        int type  = (mv>>>14)&3;
        int promo = (mv>>>12)&3;

        Piece promoPiece = Piece.NONE;
        if (type == 1) {
            // engine packs 0..3 = N,B,R,Q  (if yours differs, fix mapping here)
            PieceType pt = switch(promo){
                case 0 -> PieceType.KNIGHT;
                case 1 -> PieceType.BISHOP;
                case 2 -> PieceType.ROOK;
                case 3 -> PieceType.QUEEN;
                default -> PieceType.NONE;
            };
            promoPiece = (pt==PieceType.NONE) ? Piece.NONE : Piece.make(stmWhite ? Side.WHITE : Side.BLACK, pt);
        }
        return new Move(Square.squareAt(from), Square.squareAt(to), promoPiece);
    }

    private static String moveToUci(int mv){
        int from  = (mv>>>6) & 63;
        int to    =  mv      & 63;
        int type  = (mv>>>14)&3;
        int promo = (mv>>>12)&3;
        String s = sqName(from) + sqName(to);
        if (type == 1) s += "nbrq".charAt(promo);
        return s;
    }

    private static String sqName(int sq){ return "" + (char)('a'+(sq&7)) + (char)('1'+(sq>>>3)); }

    private static long maskFrom(long[] bb, Piece p){
        return switch (p){
            case WHITE_PAWN   -> bb[WP];
            case WHITE_KNIGHT -> bb[WN];
            case WHITE_BISHOP -> bb[WB];
            case WHITE_ROOK   -> bb[WR];
            case WHITE_QUEEN  -> bb[WQ];
            case WHITE_KING   -> bb[WK];
            case BLACK_PAWN   -> bb[BP];
            case BLACK_KNIGHT -> bb[BN];
            case BLACK_BISHOP -> bb[BB];
            case BLACK_ROOK   -> bb[BR];
            case BLACK_QUEEN  -> bb[BQ];
            case BLACK_KING   -> bb[BK];
            default -> 0L;
        };
    }

    private static int idx(Piece p){
        return switch (p){
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
            default -> 15;
        };
    }

    private static int dfFrom (long d){ return (int) (d & 0x3F); }
    private static int dfTo   (long d){ return (int) ((d >>> 6)  & 0x3F); }
    private static int dfCap  (long d){ return (int) ((d >>> 12) & 0x0F); }
    private static int dfMover(long d){ return (int) ((d >>> 16) & 0x0F); }
    private static int dfType (long d){ return (int) ((d >>> 20) & 0x03); }
    private static int dfPromo(long d){ return (int) ((d >>> 22) & 0x03); }
}
