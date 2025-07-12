package core.contracts;

public interface MoveGenerator {
  int generateCaptures(long[] packedPosition, int[] mv, int n);

  int generateQuiets(long[] packedPosition, int[] mv, int n);

  int generateEvasions(long[] packedPosition, int[] mv, int n);

  boolean kingAttacked(long[] bb, boolean moverWasWhite);

  boolean castleLegal(long[] packedPosition, int from, int to);

  boolean isAttacked(long[] bb, boolean byWhite, int sq);
}
