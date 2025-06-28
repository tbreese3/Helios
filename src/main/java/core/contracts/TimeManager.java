// C:\dev\Helios\src\main\java\core\contracts\TimeManager.java
package core.contracts;

import core.records.RootMove;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public interface TimeManager {
    boolean isTimeUp(List<RootMove> rootMoves, int rootDepth, int stability, List<Integer> scoreHistory, AtomicLong totalNodes, long[][] nodeTable);
}