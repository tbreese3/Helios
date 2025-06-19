package core.impl;

import core.contracts.Search;
import core.contracts.TranspositionTable;
import core.contracts.UciOptions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Implements the UciOptions contract to manage engine settings.
 */
public class UciOptionsImpl implements UciOptions {

    private final Search search;
    private final TranspositionTable transpositionTable;

    private record UciOption(String type, String defaultValue, String min, String max, Consumer<String> onSet) {
        void print(String name) {
            System.out.print("option name " + name + " type " + type);
            if (defaultValue != null) System.out.print(" default " + defaultValue);
            if (min != null) System.out.print(" min " + min);
            if (max != null) System.out.print(" max " + max);
            System.out.println();
        }
    }

    private final Map<String, UciOption> options = new LinkedHashMap<>();

    public UciOptionsImpl(Search search, TranspositionTable transpositionTable) {
        this.search = search;
        this.transpositionTable = transpositionTable;
        initializeOptions();
    }

    @Override
    public TranspositionTable getTranspositionTable() {
        return this.transpositionTable;
    }

    private void initializeOptions() {
        options.put("Hash", new UciOption("spin", "64", "1", "1024",
                value -> this.transpositionTable.resize(Integer.parseInt(value))));
        options.put("Threads", new UciOption("spin", "1", "1", "128",
                value -> this.search.setThreads(Integer.parseInt(value))));
        options.put("Clear Hash", new UciOption("button", null, null, null,
                value -> this.transpositionTable.clear()));
    }

    @Override
    public void setOption(String line) {
        try {
            String[] parts = line.split(" value ");
            String namePart = parts[0].replace("setoption name ", "").trim();
            String valuePart = parts.length > 1 ? parts[1].trim() : "";

            UciOption option = options.get(namePart);
            if (option != null) {
                if (option.onSet != null) {
                    option.onSet.accept(valuePart);
                }
            } else {
                System.out.println("info string Unknown option: " + namePart);
            }
        } catch (Exception e) {
            System.out.println("info string Error setting option: " + line);
        }
    }

    @Override
    public void printOptions() {
        for (Map.Entry<String, UciOption> entry : options.entrySet()) {
            entry.getValue().print(entry.getKey());
        }
    }
}