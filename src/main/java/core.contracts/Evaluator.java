package core.contracts;

public interface Evaluator {
    int evaluate(long[] bb);  // centipawns from side to move
    default void reset() {}   // clear caches between games
}