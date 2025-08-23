// File: core/constants/TuningParams.java
package core.constants;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class TuningParams {

    /* ========== PASTE YOUR TUNED VALUES HERE ========== */
    // Format: one "Name, value" per line. Comments/blank lines are ignored.
    public static final String PASTED_TUNING = """
LmrBase, 73
LmrDiv, 258
PvsQuietSeeMargin, -66
PvsCapSeeMargin, -100
ProbcutBetaMargin, 172
RfpMaxDepth, 8
RfpDepthMul, 73
RfpMin, 21
NmpBase, 3
NmpDepthDiv, 3
NmpEvalDiv, 150
NmpEvalDivMin, 4
NmpA, 23
NmpB, 204
LmpBase, 3
HistPrDepthMul, 7873
EarlyLmrHistoryDiv, 3707
LmrQuietHistoryDiv, 9802
LmrComplexityDiv, 117
QsSeeMargin, -29
AspWindowStartDepth, 4
AspWindowStartDelta, 13
""";
    /* =================================================== */

    // ---- Defaults (will be overridden by PASTED_TUNING on construction) ----
    private int lmrBase = 75;     // [10..200]
    private int lmrDiv  = 250;    // [50..800]

    private int pvsQuietSeeMargin = -70;  // [-300..50]
    private int pvsCapSeeMargin   = -96;  // [-300..50]

    private int probcutBetaMargin = 175;  // [50..350]

    private int rfpMaxDepth = 8;   // [1..12]
    private int rfpDepthMul = 75;  // [10..200]
    private int rfpMin      = 22;  // [0..80]

    private int nmpBase       = 3;    // [0..6]
    private int nmpDepthDiv   = 3;    // [1..21]
    private int nmpEvalDiv    = 147;  // [50..300]
    private int nmpEvalDivMin = 4;    // [1..12]
    private int nmpA          = 22;   // [0..60]
    private int nmpB          = 208;  // [0..400]

    private int lmpBase         = 3;      // [0..12]
    private int histPrDepthMul  = -7471;  // [-20000..20000]

    private int earlyLmrHistoryDiv = 3516; // [1000..20000]
    private int lmrQuietHistoryDiv = 9621; // [2000..20000]
    // You said you’re not using this one; it’s harmless to keep.
    private int lmrCapHistoryDiv   = 5693; // [2000..20000]
    private int lmrComplexityDiv   = 120;  // [40..300]

    private int qsSeeMargin = -32; // [-200..50]

    private int aspWindowStartDepth = 4;  // [1..40]
    private int aspWindowStartDelta = 15; // [2..200]

    // === Auto-apply pasted tuning on construction ===
    public TuningParams() {
        if (PASTED_TUNING != null && !PASTED_TUNING.isBlank()) {
            applyFromBlob(PASTED_TUNING);
        }
    }

    // === Parser: applies "Name, value" lines using setters (with clamping) ===
    public void applyFromBlob(String blob) {
        Map<String, Consumer<Integer>> set = new HashMap<>();
        set.put("LmrBase", this::setLmrBase);
        set.put("LmrDiv", this::setLmrDiv);
        set.put("PvsQuietSeeMargin", this::setPvsQuietSeeMargin);
        set.put("PvsCapSeeMargin", this::setPvsCapSeeMargin);
        set.put("ProbcutBetaMargin", this::setProbcutBetaMargin);
        set.put("RfpMaxDepth", this::setRfpMaxDepth);
        set.put("RfpDepthMul", this::setRfpDepthMul);
        set.put("RfpMin", this::setRfpMin);
        set.put("NmpBase", this::setNmpBase);
        set.put("NmpDepthDiv", this::setNmpDepthDiv);
        set.put("NmpEvalDiv", this::setNmpEvalDiv);
        set.put("NmpEvalDivMin", this::setNmpEvalDivMin);
        set.put("NmpA", this::setNmpA);
        set.put("NmpB", this::setNmpB);
        set.put("LmpBase", this::setLmpBase);
        set.put("HistPrDepthMul", this::setHistPrDepthMul);
        set.put("EarlyLmrHistoryDiv", this::setEarlyLmrHistoryDiv);
        set.put("LmrQuietHistoryDiv", this::setLmrQuietHistoryDiv);
        set.put("LmrComplexityDiv", this::setLmrComplexityDiv);
        set.put("QsSeeMargin", this::setQsSeeMargin);
        set.put("AspWindowStartDepth", this::setAspWindowStartDepth);
        set.put("AspWindowStartDelta", this::setAspWindowStartDelta);
        // (We intentionally do not wire LmrCapHistoryDiv)

        // min/max for clamping (same as your UCI ranges)
        Map<String, int[]> bounds = new HashMap<>();
        bounds.put("LmrBase", new int[]{10,200});
        bounds.put("LmrDiv", new int[]{50,800});
        bounds.put("PvsQuietSeeMargin", new int[]{-300,50});
        bounds.put("PvsCapSeeMargin", new int[]{-300,50});
        bounds.put("ProbcutBetaMargin", new int[]{50,350});
        bounds.put("RfpMaxDepth", new int[]{1,12});
        bounds.put("RfpDepthMul", new int[]{10,200});
        bounds.put("RfpMin", new int[]{0,80});
        bounds.put("NmpBase", new int[]{0,6});
        bounds.put("NmpDepthDiv", new int[]{1,21});
        bounds.put("NmpEvalDiv", new int[]{50,300});
        bounds.put("NmpEvalDivMin", new int[]{1,12});
        bounds.put("NmpA", new int[]{0,60});
        bounds.put("NmpB", new int[]{0,400});
        bounds.put("LmpBase", new int[]{0,12});
        bounds.put("HistPrDepthMul", new int[]{-20000,20000});
        bounds.put("EarlyLmrHistoryDiv", new int[]{1000,20000});
        bounds.put("LmrQuietHistoryDiv", new int[]{2000,20000});
        bounds.put("LmrComplexityDiv", new int[]{40,300});
        bounds.put("QsSeeMargin", new int[]{-200,50});
        bounds.put("AspWindowStartDepth", new int[]{1,40});
        bounds.put("AspWindowStartDelta", new int[]{2,200});

        for (String raw : blob.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            String[] parts = line.split(",");
            if (parts.length < 2) continue;
            String name = parts[0].trim();
            String vStr = parts[1].trim();
            Consumer<Integer> setter = set.get(name);
            if (setter == null) continue; // unknown/unused key → skip

            try {
                int v = (int)Math.round(Double.parseDouble(vStr)); // tolerate “12.0”
                int[] b = bounds.get(name);
                if (b != null) v = Math.max(b[0], Math.min(b[1], v));
                setter.accept(v);
            } catch (NumberFormatException ignored) { /* skip bad line */ }
        }
    }

    /* ======= Getters / Setters ======= */
    public int getLmrBase() { return lmrBase; }
    public void setLmrBase(int v) { lmrBase = v; onChange.accept(this); }

    public int getLmrDiv() { return lmrDiv; }
    public void setLmrDiv(int v) { lmrDiv = v; onChange.accept(this); }

    public int getPvsQuietSeeMargin() { return pvsQuietSeeMargin; }
    public void setPvsQuietSeeMargin(int v) { pvsQuietSeeMargin = v; }

    public int getPvsCapSeeMargin() { return pvsCapSeeMargin; }
    public void setPvsCapSeeMargin(int v) { pvsCapSeeMargin = v; }

    public int getProbcutBetaMargin() { return probcutBetaMargin; }
    public void setProbcutBetaMargin(int v) { probcutBetaMargin = v; }

    public int getRfpMaxDepth() { return rfpMaxDepth; }
    public void setRfpMaxDepth(int v) { rfpMaxDepth = v; }

    public int getRfpDepthMul() { return rfpDepthMul; }
    public void setRfpDepthMul(int v) { rfpDepthMul = v; }

    public int getRfpMin() { return rfpMin; }
    public void setRfpMin(int v) { rfpMin = v; }

    public int getNmpBase() { return nmpBase; }
    public void setNmpBase(int v) { nmpBase = v; }

    public int getNmpDepthDiv() { return nmpDepthDiv; }
    public void setNmpDepthDiv(int v) { nmpDepthDiv = Math.max(1, v); }

    public int getNmpEvalDiv() { return nmpEvalDiv; }
    public void setNmpEvalDiv(int v) { nmpEvalDiv = v; }

    public int getNmpEvalDivMin() { return nmpEvalDivMin; }
    public void setNmpEvalDivMin(int v) { nmpEvalDivMin = v; }

    public int getNmpA() { return nmpA; }
    public void setNmpA(int v) { nmpA = v; }

    public int getNmpB() { return nmpB; }
    public void setNmpB(int v) { nmpB = v; }

    public int getLmpBase() { return lmpBase; }
    public void setLmpBase(int v) { lmpBase = v; }

    public int getHistPrDepthMul() { return histPrDepthMul; }
    public void setHistPrDepthMul(int v) { histPrDepthMul = v; }

    public int getEarlyLmrHistoryDiv() { return earlyLmrHistoryDiv; }
    public void setEarlyLmrHistoryDiv(int v) { earlyLmrHistoryDiv = v; }

    public int getLmrQuietHistoryDiv() { return lmrQuietHistoryDiv; }
    public void setLmrQuietHistoryDiv(int v) { lmrQuietHistoryDiv = v; }

    public int getLmrComplexityDiv() { return lmrComplexityDiv; }
    public void setLmrComplexityDiv(int v) { lmrComplexityDiv = v; }

    public int getQsSeeMargin() { return qsSeeMargin; }
    public void setQsSeeMargin(int v) { qsSeeMargin = v; }

    public int getAspWindowStartDepth() { return aspWindowStartDepth; }
    public void setAspWindowStartDepth(int v) { aspWindowStartDepth = v; }

    public int getAspWindowStartDelta() { return aspWindowStartDelta; }
    public void setAspWindowStartDelta(int v) { aspWindowStartDelta = v; }

    /* ---- LMR table recomputation hook ---- */
    private Consumer<TuningParams> onChange = p -> {};
    public void setOnChange(Consumer<TuningParams> listener) {
        this.onChange = (listener != null) ? listener : (x -> {});
    }
}
