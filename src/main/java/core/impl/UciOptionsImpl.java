package core.impl;

import core.constants.TuningParams;
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

    private Search search;
    private final TranspositionTable transpositionTable;
    private final TuningParams tp;

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

    public UciOptionsImpl(Search search, TranspositionTable transpositionTable, TuningParams tp)
    {
        this.tp = tp;
        this.search = search;
        this.transpositionTable = transpositionTable;
        initializeOptions();
        registerTuningOptions();
    }

    private void registerTuningOptions() {
        // ---- LMR ----
        options.put("LmrBase", new UciOption(
                "spin", Integer.toString(tp.getLmrBase()), "10", "200",
                v -> tp.setLmrBase(Integer.parseInt(v))));
        options.put("LmrDiv", new UciOption(
                "spin", Integer.toString(tp.getLmrDiv()), "50", "800",
                v -> tp.setLmrDiv(Integer.parseInt(v))));

        // ---- SEE margins (PVS) ----
        options.put("PvsQuietSeeMargin", new UciOption(
                "spin", Integer.toString(tp.getPvsQuietSeeMargin()), "-300", "50",
                v -> tp.setPvsQuietSeeMargin(Integer.parseInt(v))));
        options.put("PvsCapSeeMargin", new UciOption(
                "spin", Integer.toString(tp.getPvsCapSeeMargin()), "-300", "50",
                v -> tp.setPvsCapSeeMargin(Integer.parseInt(v))));

        // ---- ProbCut ----
        options.put("ProbcutBetaMargin", new UciOption(
                "spin", Integer.toString(tp.getProbcutBetaMargin()), "50", "350",
                v -> tp.setProbcutBetaMargin(Integer.parseInt(v))));

        // ---- Reverse Futility Pruning (RFP) ----
        options.put("RfpMaxDepth", new UciOption(
                "spin", Integer.toString(tp.getRfpMaxDepth()), "1", "12",
                v -> tp.setRfpMaxDepth(Integer.parseInt(v))));
        options.put("RfpDepthMul", new UciOption(
                "spin", Integer.toString(tp.getRfpDepthMul()), "10", "200",
                v -> tp.setRfpDepthMul(Integer.parseInt(v))));
        options.put("RfpMin", new UciOption(
                "spin", Integer.toString(tp.getRfpMin()), "0", "80",
                v -> tp.setRfpMin(Integer.parseInt(v))));

        // ---- Null Move Pruning (NMP) ----
        options.put("NmpBase", new UciOption(
                "spin", Integer.toString(tp.getNmpBase()), "0", "6",
                v -> tp.setNmpBase(Integer.parseInt(v))));
        options.put("NmpDepthDiv", new UciOption(
                "spin", Integer.toString(tp.getNmpDepthDiv()), "1", "21",
                v -> tp.setNmpDepthDiv(Integer.parseInt(v))));
        options.put("NmpEvalDiv", new UciOption(
                "spin", Integer.toString(tp.getNmpEvalDiv()), "50", "300",
                v -> tp.setNmpEvalDiv(Integer.parseInt(v))));
        options.put("NmpEvalDivMin", new UciOption(
                "spin", Integer.toString(tp.getNmpEvalDivMin()), "1", "12",
                v -> tp.setNmpEvalDivMin(Integer.parseInt(v))));
        options.put("NmpA", new UciOption(
                "spin", Integer.toString(tp.getNmpA()), "0", "60",
                v -> tp.setNmpA(Integer.parseInt(v))));
        options.put("NmpB", new UciOption(
                "spin", Integer.toString(tp.getNmpB()), "0", "400",
                v -> tp.setNmpB(Integer.parseInt(v))));

        // ---- LMP & history pruning ----
        options.put("LmpBase", new UciOption(
                "spin", Integer.toString(tp.getLmpBase()), "0", "12",
                v -> tp.setLmpBase(Integer.parseInt(v))));
        options.put("HistPrDepthMul", new UciOption(
                "spin", Integer.toString(tp.getHistPrDepthMul()), "-20000", "20000",
                v -> tp.setHistPrDepthMul(Integer.parseInt(v))));

        // ---- LMR history/complexity scaling ----
        options.put("EarlyLmrHistoryDiv", new UciOption(
                "spin", Integer.toString(tp.getEarlyLmrHistoryDiv()), "1000", "20000",
                v -> tp.setEarlyLmrHistoryDiv(Integer.parseInt(v))));
        options.put("LmrQuietHistoryDiv", new UciOption(
                "spin", Integer.toString(tp.getLmrQuietHistoryDiv()), "2000", "20000",
                v -> tp.setLmrQuietHistoryDiv(Integer.parseInt(v))));
        options.put("LmrComplexityDiv", new UciOption(
                "spin", Integer.toString(tp.getLmrComplexityDiv()), "40", "300",
                v -> tp.setLmrComplexityDiv(Integer.parseInt(v))));

        // ---- QSearch SEE ----
        options.put("QsSeeMargin", new UciOption(
                "spin", Integer.toString(tp.getQsSeeMargin()), "-200", "50",
                v -> tp.setQsSeeMargin(Integer.parseInt(v))));

        // ---- Aspiration window ----
        options.put("AspWindowStartDepth", new UciOption(
                "spin", Integer.toString(tp.getAspWindowStartDepth()), "1", "40",
                v -> tp.setAspWindowStartDepth(Integer.parseInt(v))));
        options.put("AspWindowStartDelta", new UciOption(
                "spin", Integer.toString(tp.getAspWindowStartDelta()), "2", "200",
                v -> tp.setAspWindowStartDelta(Integer.parseInt(v))));
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
        options.put("MultiPV",
                new UciOption("spin", "1",       // default 1 line
                        "1", "8",
                        v -> {}));
        options.put("Minimal",
                new UciOption("check", "false", null, null,
                        v -> {}));
    }

    public String getOptionValue(String name) {
        UciOption o = options.get(name);
        return o != null ? o.defaultValue /* or cached current value */ : null;
    }

    public void attachSearch(Search s) { this.search = s; }

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