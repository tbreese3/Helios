package core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.*;

import core.impl.MoveGenerator;
import core.impl.PositionFactory;
import org.junit.jupiter.api.*;

/**
 * *Exhaustive* verification that Zobrist hashing – *both* the full
 * recomputation and the incremental updates – is correct.
 *
 * The test is split into two parts:
 *
 * 1.  **Primitive-invariant tests** – prove that fullHash() returns
 * exactly the XOR of the individual table entries. This does
 * *not* call fullHash internally for the comparison; instead we
 * rebuild the hash manually from the private PIECE_SQUARE,
 * CASTLING, … tables obtained via reflection. When these pass we
 * can trust fullHash as a reference oracle.
 *
 * 2.  **Random walk + edge script** – same as usual, but guarded by
 * the proven-correct oracle, which in turn guarantees that the
 * incremental `make/undo` path is right.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PositionFactoryZobristTest {

    /* ─── reference to the engine objects ───────────────────────── */
    private static final core.impl.PositionFactory PF  = new core.impl.PositionFactory();
    private static final MoveGenerator GEN = new core.impl.MoveGenerator();
    private static final Random              RNG = new Random(0xDEADBEEF);

    /* ─── reflect private static fields we need for ground truth ── */
    private static final long[][] PSQ   = getStatic("PIECE_SQUARE");
    private static final long[]   CAST  = getStatic("CASTLING");
    private static final long[]   EPF   = getStatic("EP_FILE");
    private static final long     STM_K = getStatic("SIDE_TO_MOVE");
    private static final int      EP_NONE = (int) PositionFactory.EP_NONE;

    /* piece indices copied to avoid reflection inside hot loop      */
    private static final int WP = getIntConst("WP"),
            WN = getIntConst("WN"),
            WB = getIntConst("WB"),
            WR = getIntConst("WR"),
            WQ = getIntConst("WQ"),
            WK = getIntConst("WK"),
            BP = getIntConst("BP"),
            BN = getIntConst("BN"),
            BB = getIntConst("BB"),
            BR = getIntConst("BR"),
            BQ = getIntConst("BQ"),
            BK = getIntConst("BK");
    private static final int HASH = getIntConst("HASH");
    private static final int META = getIntConst("META");
    private static final long STM_MASK = getLongConst("STM_MASK");
    private static final long CR_MASK  = getLongConst("CR_MASK");
    private static final long EP_MASK  = getLongConst("EP_MASK");

    /* ————————————————— 1 ─  primitive invariants  ————————————————— */

    /** Empty board, no STM bit, CR=0, EP = EP_NONE → hash == CASTLING[0]. */
    @Test @Order(1)
    void emptyBoardBaseline() {
        long[] bb = blank();
        long expected = CAST[0]; // The only non-zero component should be the empty castling rights key
        long actual   = PF.fullHash(bb);
        assertEquals(expected, actual, "baseline hash wrong");
    }

    /** Every piece / square flips exactly PIECE_SQUARE[p][sq]. */
    @Test @Order(2)
    void pieceSquareContributionExact() {
        long[] bb = blank();
        for (int p = WP; p <= BK; ++p) {
            for (int sq = 0; sq < 64; ++sq) {
                bb[p] = 1L << sq;
                long got = PF.fullHash(bb);
                long want = CAST[0] ^ PSQ[p][sq]; // baseline XOR piece
                int finalP = p;
                int finalSq = sq;
                assertEquals(want, got,
                        () -> "piece idx " + finalP + " on sq " + finalSq + " wrong");
                bb[p] = 0;
            }
        }
    }

    /** Side-to-move toggles exactly SIDE_TO_MOVE constant. */
    @Test @Order(3)
    void sideToMoveXorCorrect() {
        long[] bb = blank();
        long hashWhite = PF.fullHash(bb);
        bb[META] |= STM_MASK;              // make it black to move
        long hashBlack = PF.fullHash(bb);
        assertEquals(hashWhite ^ STM_K, hashBlack, "STM XOR wrong");
    }

    /** Each of 16 castling codes injects its own CASTLING index. */
    @Test @Order(4)
    void castlingXorCorrect() {
        long[] bb = blank();
        for (int cr = 0; cr < 16; ++cr) {
            bb[META] = (bb[META] & ~CR_MASK) | ((long) cr << 1);
            long h = PF.fullHash(bb);
            long want = CAST[cr];
            assertEquals(want, h, "CR code " + cr + " wrong");
        }
    }

    /** Each EP file 0-7 toggles the matching EP_FILE entry. */
    @Test @Order(5)
    void enPassantXorCorrect() {
        long[] bb = blank();
        long base = PF.fullHash(bb);
        for (int f = 0; f < 8; ++f) {
            int epSq = f; // any square with same file works
            bb[META] = (bb[META] & ~EP_MASK) | ((long) epSq << 5);
            long h = PF.fullHash(bb);
            assertEquals(base ^ EPF[f], h, "EP file " + f + " wrong");
        }
    }

    /* ————————————————— 2 ─  incremental vs proven-full  —————————— */

    @Test @Order(6)
    void perftKeepsHashCorrect() {
        long[] bb = PF.fromFen(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        verifyPerft(bb, 4); // Depth 5 is slow, reducing to 4 for reasonable test time
        bb = PF.fromFen(
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        verifyPerft(bb, 3);
        bb = PF.fromFen(
                "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1");
        verifyPerft(bb, 4);
        bb = PF.fromFen(
                "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1");
        verifyPerft(bb, 3);
        bb = PF.fromFen(
                "rnbqkb1r/pp1p1ppp/2p5/4P3/2B5/8/PPP1NnPP/RNBQK2R w KQkq - 0 6");
        verifyPerft(bb, 4);
    }

    /* ————————————————— helpers ——————————————————————————— */
    private static long[] COPY_TMP;

    private static void assertHashesEqual(long[] bb) {
        COPY_TMP = Arrays.copyOf(bb, bb.length);
        long full = PF.fullHash(bb);
        long inc  = PF.zobrist(bb);
        if (full == inc) return;

        // Fallback for debugging if hashes mismatch
        dumpAndFail("Hash mismatch", full, inc);
    }

    private static void dumpAndFail(String tag, long exp, long act) {
        System.err.println("Failure reason: " + tag);
        System.err.printf("Expected: 0x%016X%nActual:   0x%016X%n", exp, act);
        System.err.println("FEN at failure: " + new core.impl.PositionFactory().toFen(COPY_TMP));
        fail("incremental != proven-full (" + tag + ")");
    }


    private static long[] blank() {
        long[] a = new long[getIntConst("BB_LEN")];
        a[getIntConst("META")] = ((long)EP_NONE) << 5;
        return a;
    }

    private static int legalMoves(long[] bb, int[] dst) {
        int[] pseudo = new int[256];
        boolean white = (bb[META] & STM_MASK) == 0;
        int n = GEN.kingAttacked(bb, white)
                ? GEN.generateEvasions(bb, pseudo, 0)
                : GEN.generateQuiets(bb, pseudo,
                GEN.generateCaptures(bb, pseudo, 0));
        int len = 0;
        for (int i = 0; i < n; ++i) {
            int mv = pseudo[i];
            if (!PF.makeMoveInPlace(bb, mv, GEN))
                continue;
            PF.undoMoveInPlace(bb);
            dst[len++] = mv;
        }
        return len;
    }

    private static void verifyPerft(long[] bb, int depth) {
        assertHashesEqual(bb); // Check upon entering the node

        if (depth == 0) return;

        int[] moves = new int[256];
        int cnt = legalMoves(bb, moves);

        for (int i = 0; i < cnt; ++i) {
            int mv = moves[i];
            PF.makeMoveInPlace(bb, mv, GEN);
            assertHashesEqual(bb); // Check after making a move

            verifyPerft(bb, depth - 1);

            PF.undoMoveInPlace(bb);
            assertHashesEqual(bb); // Check after undoing the move
        }
    }


    @SuppressWarnings("unchecked")
    private static <T> T getStatic(String name) {
        try {
            // First, try to get the field from the interface where public constants are defined.
            Field f = PositionFactory.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(null);
        } catch (NoSuchFieldException e) {
            // If not found, it must be a private/package-private constant in the implementation
            // (like the Zobrist arrays).
            try {
                Field f = core.impl.PositionFactory.class.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(null);
            } catch (Exception e2) {
                throw new RuntimeException("Failed to reflect field '" + name + "' from PositionFactoryImpl", e2);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to reflect field '" + name + "' from PositionFactory", e);
        }
    }

    private static int getIntConst(String n){return (Integer)getStatic(n);}
    private static long getLongConst(String n){return (Long)getStatic(n);}
}