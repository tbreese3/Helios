// C:/dev/Helios/src/main/java/core/nnue/NnueState.java
package core.nnue;

public class NnueState {
    // Hidden layer accumulators for both White's and Black's perspectives.
    public final short[] whiteAcc;
    public final short[] blackAcc;

    public NnueState() {
        this.whiteAcc = new short[NnueManager.HL_SIZE];
        this.blackAcc = new short[NnueManager.HL_SIZE];
    }
}