// C:\dev\Helios\src\main\java\core\impl\TimeManagerFactoryImpl.java
package core.impl;

import core.contracts.TimeManager;
import core.contracts.TimeManagerFactory;
import core.contracts.UciOptions;
import core.records.SearchSpec;

public class TimeManagerFactoryImpl implements TimeManagerFactory {
    private final UciOptions options;

    public TimeManagerFactoryImpl(UciOptions options) {
        this.options = options;
    }

    @Override
    public TimeManager create(SearchSpec spec, boolean isWhiteToMove) {
        return new TimeManagerImpl(options, spec, isWhiteToMove);
    }
}