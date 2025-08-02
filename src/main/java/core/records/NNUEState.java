package core.records;

import core.contracts.NNUE;

import java.util.ArrayList;
import java.util.List;

public class NNUEState {
    public short[] whiteAcc; // No longer final
    public short[] blackAcc; // No longer final
    public final List<Integer> activeWhiteFeatures;
    public final List<Integer> activeBlackFeatures;

    public NNUEState() {
        this.whiteAcc = new short[NNUE.HL_SIZE];
        this.blackAcc = new short[NNUE.HL_SIZE];
        this.activeWhiteFeatures = new ArrayList<>(32);
        this.activeBlackFeatures = new ArrayList<>(32);
    }
}