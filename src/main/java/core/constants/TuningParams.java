// File: core/tuning/TuningParams.java
package core.constants;

import java.util.function.Consumer;

public final class TuningParams {

    /* ---- Reductions (LMR) ----
     * Java's LMR_TABLE uses (base + log(d)*log(m)/div).
     * Reference: LmrBase, LmrDiv
     * Defaults here keep your current behavior (≈ 75 base, 250 div).
     */
    private int lmrBase = 75;     // [10..200]    “LmrBase”
    private int lmrDiv  = 250;    // [50..800]    “LmrDiv”

    /* ---- SEE margins in PVS ----
     * We apply: quiet => margin * depth^2, capture => margin * depth
     * Your current single -70*depth becomes two knobs.
     */
    private int pvsQuietSeeMargin = -70;  // [ -300 .. 50 ]
    private int pvsCapSeeMargin   = -96;  // [ -300 .. 50 ]

    /* ---- ProbCut ----
     * beta' = beta + margin
     */
    private int probcutBetaMargin = 175;  // [50..350]

    /* ---- Reverse Futility Pruning (RFP) ----
     *   if (depth <= maxDepth && staticEval - max(depthMul*depth, min) >= beta) cutoff
     */
    private int rfpMaxDepth = 8;   // [1..12]
    private int rfpDepthMul = 75;  // [10..200]
    private int rfpMin      = 22;  // [0..80]

    /* ---- Null Move Pruning (NMP) ----
     *   r = base + depth / depthDiv + min( (eval-beta)/evalDiv, evalDivMin)
     * Gating: (staticEval + A*depth - B >= beta) like reference.
     */
    private int nmpBase       = 3;   // [0..6]
    private int nmpDepthDiv   = 3;   // [1..21] (bounded int in reference)
    private int nmpEvalDiv    = 147; // [50..300]
    private int nmpEvalDivMin = 4;   // [1..12]
    private int nmpA          = 22;  // [0..60]
    private int nmpB          = 208; // [0..400]

    /* ---- Late Move Pruning (LMP) & history pruning ----
     * We replace your 2 + 2*depth*depth & (hist < 50*depth) with knobs.
     * LMP limit used: i >= (depth*depth + LmpBase)/2
     */
    private int lmpBase         = 3;    // [0..12]
    private int histPrDepthMul  =  -7471; // same sign/range as reference (we use positive below)

    /* ---- LMR history/complexity scaling (we partially map) ----
     * We subtract history/div from the base reduction (quiet vs capture).
     */
    private int earlyLmrHistoryDiv = 3516; // [1000..20000]
    private int lmrQuietHistoryDiv = 9621; // [2000..20000]
    private int lmrCapHistoryDiv   = 5693; // [2000..20000]
    private int lmrComplexityDiv   = 120;  // [40..300]

    /* ---- Quiescence SEE keep threshold (and optional stand-pat futility) ----
     */
    private int qsSeeMargin = -32; // [ -200 .. 50 ]

    /* ---- Aspiration window ----
     * We enable aspiration only from a given depth; initial half-window = delta.
     */
    private int aspWindowStartDepth = 4;  // [1..40]
    private int aspWindowStartDelta = 15; // [2..200]

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
    public void setOnChange(Consumer<TuningParams> listener) { this.onChange = (listener != null) ? listener : (x -> {}); }
}
