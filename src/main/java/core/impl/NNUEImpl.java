package core.impl;

import core.contracts.NNUE;
import core.contracts.PositionFactory;
import core.records.NNUEState;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;
import main.Main;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static core.contracts.PositionFactory.*;
import static core.contracts.PositionFactory.BK;
import static core.contracts.PositionFactory.BN;
import static core.contracts.PositionFactory.BR;
import static core.contracts.PositionFactory.WK;
import static core.contracts.PositionFactory.WN;
import static core.contracts.PositionFactory.WR;

public final class NNUEImpl implements NNUE {
    private final static int[] screluPreCalc = new int[Short.MAX_VALUE - Short.MIN_VALUE + 1];


    // --- Network Architecture Constants ---
    public static final int INPUT_SIZE = 768;
    public static final int HL_SIZE = 1536;
    private static final int QA = 255;
    private static final int QB = 64;
    private static final int QAB = QA * QB;
    private static final int FV_SCALE = 400;

    // --- Network Parameters (Weights and Biases) ---
    private static short[][] L1_WEIGHTS; // [INPUT_SIZE][HL_SIZE]
    private static short[] L1_BIASES;    // [HL_SIZE]
    private static short[][] L2_WEIGHTS; // [2][HL_SIZE]
    private static short[] L2_BIASES;    // [1]

    private static final VectorSpecies<Short> S = ShortVector.SPECIES_PREFERRED;
    private static final int UB = S.loopBound(HL_SIZE); // largest multiple ≤ HL_SIZE

    private static boolean isLoaded = false;

    // ===== Parallel evaluator wiring =====
    private static final int SMIN_OFF = -(int) Short.MIN_VALUE; // 32768

    // widen L2 weights once (optional but recommended)
    private static int[] L2W_STM_I, L2W_OPP_I;

    // thread count—2 is usually best for HL=1536; tweak if you like
    private static final int EVAL_THREADS = 2;

    // shared state for workers (published before kicking them)
    private static volatile short[] PV_a1, PV_a2;
    private static volatile int[]   PV_w1, PV_w2;
    private static volatile int     PV_len;

    // per-thread partials (avoid false sharing by padding)
    private static final long[] PARTIAL = new long[EVAL_THREADS * 8]; // stride 8 longs/thread

    // sequencing + completion
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger REM = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final Thread[] WORKERS = new Thread[Math.max(0, EVAL_THREADS - 1)];

    static {
        for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
            screluPreCalc[i - (int) Short.MIN_VALUE] = screlu((short) (i));
        }

        // spawn workers once
        for (int t = 1; t < EVAL_THREADS; t++) {
            final int tid = t;
            Thread th = new Thread(() -> {
                int seen = 0;
                for (; ; ) {
                    // wait for new work
                    int s;
                    while ((s = SEQ.get()) == seen) {
                        Thread.onSpinWait();
                    }
                    seen = s;

                    // snapshot shared state
                    short[] a1 = PV_a1, a2 = PV_a2;
                    int[] w1 = PV_w1, w2 = PV_w2;
                    int n = PV_len;

                    // compute my chunk
                    final int lo = (n * tid) / EVAL_THREADS;
                    final int hi = (n * (tid + 1)) / EVAL_THREADS;
                    long sum = 0L;
                    int off = SMIN_OFF;
                    int[] tbl = screluPreCalc;
                    for (int i = lo; i < hi; i++) {
                        sum += (long) tbl[a1[i] + off] * (long) w1[i];
                        sum += (long) tbl[a2[i] + off] * (long) w2[i];
                    }
                    PARTIAL[tid * 8] = sum;

                    // signal done
                    if (REM.decrementAndGet() == 0) {
                        // last worker finished; nothing else to do—main thread is spinning
                    }
                }
            }, "nnue-eval-" + t);
            th.setDaemon(true);
            th.setPriority(Thread.NORM_PRIORITY);
            th.start();
            WORKERS[t - 1] = th;
        }
    }

    static {
        String resourcePath = "/core/nnue/network.bin";
        try (InputStream nnueStream = Main.class.getResourceAsStream(resourcePath)) {
            NNUEImpl.loadNetwork(nnueStream, "embedded resource");
        } catch (Exception e) {
            System.out.println("info string Error loading embedded NNUE file: " + e.getMessage());
        }
    }

    /**
     * Loads the quantized network weights from an InputStream.
     */
    private static synchronized void loadNetwork(InputStream is, String sourceName) {
        if (isLoaded) return;
        if (is == null) {
            System.err.println("Failed to load NNUE network: InputStream is null. Source: " + sourceName);
            isLoaded = false;
            return;
        }

        try (DataInputStream dis = new DataInputStream(is)) {
            L1_WEIGHTS = new short[INPUT_SIZE][HL_SIZE];
            L1_BIASES = new short[HL_SIZE];
            L2_WEIGHTS = new short[2][HL_SIZE];
            L2_BIASES = new short[1];

            byte[] buffer = new byte[2];

            for (int i = 0; i < INPUT_SIZE; i++) {
                for (int j = 0; j < HL_SIZE; j++) {
                    dis.readFully(buffer);
                    L1_WEIGHTS[i][j] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
                }
            }
            for (int i = 0; i < HL_SIZE; i++) {
                dis.readFully(buffer);
                L1_BIASES[i] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
            }
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < HL_SIZE; j++) {
                    dis.readFully(buffer);
                    L2_WEIGHTS[i][j] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();
                }
            }
            dis.readFully(buffer);
            L2_BIASES[0] = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getShort();

            isLoaded = true;
        } catch (IOException e) {
            System.err.println("Failed to read NNUE stream from " + sourceName + ": " + e.getMessage());
            isLoaded = false;
        }

        L2W_STM_I = new int[HL_SIZE];
        L2W_OPP_I = new int[HL_SIZE];
        for (int i = 0; i < HL_SIZE; i++) {
            L2W_STM_I[i] = L2_WEIGHTS[0][i];
            L2W_OPP_I[i] = L2_WEIGHTS[1][i];
        }
    }

    public static boolean isLoaded() {
        return isLoaded;
    }

    public void updateNnueAccumulator(NNUEState nnueState, int moverPiece, int capturedPiece, int move) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indicies = getFeatureIndices(capturedPiece, capturedSquare);
            subtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicies[0]]);
            subtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicies[1]]);
        }

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] subPawnFrom = getFeatureIndices(moverPiece, from);
            int[] addPromoTo  = getFeatureIndices(promotedToPiece, to);

            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[addPromoTo[0]], L1_WEIGHTS[subPawnFrom[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[addPromoTo[1]], L1_WEIGHTS[subPawnFrom[1]]);
        } else if (moveType == 3) { // Castle
            int rook = moverPiece < 6 ? WR : BR;
            switch (to) {
                case 6:
                    NNUEImpl.updateCastle(nnueState, WK, rook, 4, 6, 7, 5);
                    break; // White O-O
                case 2:
                    NNUEImpl.updateCastle(nnueState, WK, rook, 4, 2, 0, 3);
                    break; // White O-O-O
                case 62:
                    NNUEImpl.updateCastle(nnueState, BK, rook, 60, 62, 63, 61);
                    break; // Black O-O
                case 58:
                    NNUEImpl.updateCastle(nnueState, BK, rook, 60, 58, 56, 59);
                    break; // Black O-O-O
            }
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from);
            int[] indicesTo = getFeatureIndices(moverPiece, to);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicesTo[0]], L1_WEIGHTS[indicesFrom[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicesTo[1]], L1_WEIGHTS[indicesFrom[1]]);
        }
    }

    /**
     * Undoes a move's effect on the NNUE accumulator, now correctly handling castling.
     */
    public void undoNnueAccumulatorUpdate(NNUEState nnueState, int moverPiece, int capturedPiece, int move) {
        int from = (move >>> 6) & 0x3F;
        int to = move & 0x3F;
        int moveType = (move >>> 14) & 0x3;

        if (moveType == 1) { // Promotion
            int promotedToPiece = (moverPiece < 6 ? WN : BN) + ((move >>> 12) & 0x3);
            int[] addPawnFrom = getFeatureIndices(moverPiece, from);
            int[] subPromoTo  = getFeatureIndices(promotedToPiece, to);

            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[addPawnFrom[0]], L1_WEIGHTS[subPromoTo[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[addPawnFrom[1]], L1_WEIGHTS[subPromoTo[1]]);
        } else if (moveType == 3) { // Castle
            int rook = moverPiece < 6 ? WR : BR;
            switch (to) {
                case 6:
                    NNUEImpl.updateCastle(nnueState, WK, rook, 6, 4, 5, 7);
                    break; // Undo White O-O
                case 2:
                    NNUEImpl.updateCastle(nnueState, WK, rook, 2, 4, 3, 0);
                    break; // Undo White O-O-O
                case 62:
                    NNUEImpl.updateCastle(nnueState, BK, rook, 62, 60, 61, 63);
                    break; // Undo Black O-O
                case 58:
                    NNUEImpl.updateCastle(nnueState, BK, rook, 58, 60, 59, 56);
                    break; // Undo Black O-O-O
            }
        } else { // Normal move
            int[] indicesFrom = getFeatureIndices(moverPiece, from);
            int[] indicesTo = getFeatureIndices(moverPiece, to);
            addSubtractWeights(nnueState.whiteAcc, L1_WEIGHTS[indicesFrom[0]], L1_WEIGHTS[indicesTo[0]]);
            addSubtractWeights(nnueState.blackAcc, L1_WEIGHTS[indicesFrom[1]], L1_WEIGHTS[indicesTo[1]]);
        }

        if (capturedPiece != -1) {
            int capturedSquare = (moveType == 2) ? (to + (moverPiece < 6 ? -8 : 8)) : to;
            int[] indicies = getFeatureIndices(capturedPiece, capturedSquare);
            addWeights(nnueState.whiteAcc, L1_WEIGHTS[indicies[0]]);
            addWeights(nnueState.blackAcc, L1_WEIGHTS[indicies[1]]);
        }
    }

    public void refreshAccumulator(NNUEState state, long[] bb) {
        System.arraycopy(L1_BIASES, 0, state.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, state.blackAcc, 0, HL_SIZE);

        for (int p = PositionFactory.WP; p <= PositionFactory.BK; p++) {
            long board = bb[p];
            while (board != 0) {
                int sq = Long.numberOfTrailingZeros(board);
                int[] indicies = getFeatureIndices(p, sq);
                addWeights(state.whiteAcc, L1_WEIGHTS[indicies[0]]);
                addWeights(state.blackAcc, L1_WEIGHTS[indicies[1]]);
                board &= board - 1;
            }
        }
    }

    public int evaluateFromAccumulator(NNUEState state, boolean whiteToMove) {
        final short[] a1 = whiteToMove ? state.whiteAcc : state.blackAcc;
        final short[] a2 = whiteToMove ? state.blackAcc : state.whiteAcc;
        final int[]   w1 = L2W_STM_I;
        final int[]   w2 = L2W_OPP_I;

        long sum;
        if (EVAL_THREADS <= 1) {
            sum = evalSequential(a1, a2, w1, w2, 0, HL_SIZE);
        } else {
            // publish work
            PV_a1 = a1; PV_a2 = a2; PV_w1 = w1; PV_w2 = w2; PV_len = HL_SIZE;
            // reset partials
            for (int t = 0; t < EVAL_THREADS; t++) PARTIAL[t * 8] = 0L;
            // number of worker threads to wait on (others)
            REM.set(EVAL_THREADS - 1);
            // bump sequence to release workers
            SEQ.incrementAndGet();

            // main thread computes chunk 0 while others run
            long local = evalRange(a1, a2, w1, w2, 0, (HL_SIZE * 1) / EVAL_THREADS);

            // wait for workers (spin; no parking syscalls)
            while (REM.get() != 0) { Thread.onSpinWait(); }

            sum = local;
            for (int t = 1; t < EVAL_THREADS; t++) sum += PARTIAL[t * 8];
        }

        // finish as before
        sum /= QA;
        sum += L2_BIASES[0];
        sum *= FV_SCALE;
        sum /= QAB;
        return (int) sum;
    }

    private static long evalSequential(short[] a1, short[] a2, int[] w1, int[] w2, int lo, int hi) {
        long s = 0L; int off = SMIN_OFF; int[] tbl = screluPreCalc;
        for (int i = lo; i < hi; i++) {
            s += (long) tbl[a1[i] + off] * (long) w1[i];
            s += (long) tbl[a2[i] + off] * (long) w2[i];
        }
        return s;
    }
    private static long evalRange(short[] a1, short[] a2, int[] w1, int[] w2, int lo, int hi) {
        return evalSequential(a1, a2, w1, w2, lo, hi); // kept separate for clarity
    }

    private static int[] getFeatureIndices(int piece, int square) {
        int color = piece / 6;
        int pieceType = piece % 6;
        int whiteFeature = (color * 384) + (pieceType * 64) + square;
        int blackFeature = ((1 - color) * 384) + (pieceType * 64) + (square ^ 56);
        return new int[]{whiteFeature, blackFeature};
    }

    private static void updateCastle(NNUEState state, int king, int rook, int k_from, int k_to, int r_from, int r_to) {
        int[] kFrom = getFeatureIndices(king, k_from);
        int[] kTo   = getFeatureIndices(king, k_to);
        int[] rFrom = getFeatureIndices(rook, r_from);
        int[] rTo   = getFeatureIndices(rook, r_to);

        addAddSubSubWeights(state.whiteAcc, L1_WEIGHTS[kTo[0]], L1_WEIGHTS[rTo[0]], L1_WEIGHTS[kFrom[0]], L1_WEIGHTS[rFrom[0]]);
        addAddSubSubWeights(state.blackAcc, L1_WEIGHTS[kTo[1]], L1_WEIGHTS[rTo[1]], L1_WEIGHTS[kFrom[1]], L1_WEIGHTS[rFrom[1]]);
    }

    private static int screlu(short v) {
        int vCalc = Math.max(0, Math.min(v, QA));
        return vCalc * vCalc;
    }

    private static void addSubtractWeights(short[] acc, short[] addW, short[] subW) {
        for (int i = 0; i < UB; i += S.length()) {
            var a = ShortVector.fromArray(S, acc, i);
            var ad = ShortVector.fromArray(S, addW, i);
            var sb = ShortVector.fromArray(S, subW, i);
            a.add(ad).sub(sb).intoArray(acc, i);
        }
        for (int i = UB; i < HL_SIZE; i++) acc[i] = (short) (acc[i] + addW[i] - subW[i]);
    }

    private static void subtractWeights(short[] acc, short[] subW)
    {
        for (int i = 0; i < UB; i += S.length()) {
            var a = ShortVector.fromArray(S, acc, i);
            var b = ShortVector.fromArray(S, subW, i);
            a.sub(b).intoArray(acc, i);
        }
        for (int i = UB; i < HL_SIZE; i++) acc[i] -= subW[i];
    }

    private static void addWeights(short[] acc, short[] addW) {
        for (int i = 0; i < UB; i += S.length()) {
            var a = ShortVector.fromArray(S, acc, i);
            var b = ShortVector.fromArray(S, addW,   i);
            a.add(b).intoArray(acc, i);
        }
        // tail (if ever needed)
        for (int i = UB; i < HL_SIZE; i++) acc[i] += addW[i];
    }

    private static void addAddSubSubWeights(short[] acc, short[] addW1, short[] addW2, short[] subW1, short[] subW2) {
        for (int i = 0; i < UB; i += S.length()) {
            var a   = ShortVector.fromArray(S, acc,   i);
            var a1  = ShortVector.fromArray(S, addW1, i);
            var a2  = ShortVector.fromArray(S, addW2, i);
            var s1  = ShortVector.fromArray(S, subW1, i);
            var s2  = ShortVector.fromArray(S, subW2, i);
            a.add(a1).add(a2).sub(s1).sub(s2).intoArray(acc, i);
        }
        for (int i = UB; i < HL_SIZE; i++) acc[i] = (short)(acc[i] + addW1[i] + addW2[i] - subW1[i] - subW2[i]);
    }
}