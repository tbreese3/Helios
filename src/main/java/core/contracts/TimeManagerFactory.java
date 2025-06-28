// C:\dev\Helios\src\main\java\core\contracts\TimeManagerFactory.java
package core.contracts;

import core.impl.TimeManagerImpl;
import core.records.SearchSpec;

@FunctionalInterface
public interface TimeManagerFactory {
    TimeManager create(SearchSpec spec, boolean isWhiteToMove);
}