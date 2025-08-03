package core.records;

import core.impl.NNUEImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the state for an incremental NNUE evaluation.
 * This includes the hidden layer accumulators and the list of active features.
 */
public class NNUEState {
    // Hidden layer accumulators for both White's and Black's perspectives.
    public final short[] whiteAcc;
    public final short[] blackAcc;

    // List of feature indices that are currently active for the position.
    // Stored to make updates easier during make/undo move.
    public final List<Integer> activeWhiteFeatures;
    public final List<Integer> activeBlackFeatures;

    public NNUEState() {
        this.whiteAcc = new short[NNUEImpl.HL_SIZE];
        this.blackAcc = new short[NNUEImpl.HL_SIZE];
        this.activeWhiteFeatures = new ArrayList<>(32);
        this.activeBlackFeatures = new ArrayList<>(32);
    }
}