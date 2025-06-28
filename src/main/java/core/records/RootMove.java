// C:\dev\Helios\src\main\java\core\records\RootMove.java
package core.records;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static core.constants.CoreConstants.SCORE_INF;

public class RootMove implements Comparable<RootMove> {
    public final int move;
    public int score;
    public int previousScore;
    public int averageScore;
    public int depth;
    public List<Integer> pv;

    public RootMove(int move) {
        this.move = move;
        this.score = -SCORE_INF;
        this.previousScore = -SCORE_INF;
        this.averageScore = -SCORE_INF;
        this.depth = 0;
        this.pv = new ArrayList<>(Collections.singletonList(move));
    }

    @Override
    public int compareTo(RootMove other) {
        if (this.score != other.score) {
            return Integer.compare(other.score, this.score);
        }
        return Integer.compare(other.previousScore, this.previousScore);
    }

    @Override
    public String toString() {
        return "RootMove{" + "move=" + move + ", score=" + score + '}';
    }
}