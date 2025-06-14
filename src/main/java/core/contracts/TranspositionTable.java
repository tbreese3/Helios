package core.contracts;

/** Pluggable transposition table – trivial façade. */
public interface TranspositionTable extends AutoCloseable {
    Entry probe(long zobrist, int depth, int alpha, int beta);
    void  store(long zobrist, int depth, int value, int flag, int bestMove);
    @Override void close();
    record Entry(int value, int flag, int depth, int bestMove) {}
}
