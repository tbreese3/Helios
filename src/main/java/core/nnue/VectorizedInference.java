// C:/dev/Helios/src/main/java/core/nnue/VectorizedInference.java
package core.nnue;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * High-performance SIMD implementation of NNUE operations using the Java Vector API.
 * This version is compatible with older JDK Vector API versions.
 */
public class VectorizedInference {

    private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
    private static final int LOOP_BOUND = SPECIES.loopBound(NnueManager.HL_SIZE);

    public void applyChanges(short[] childAcc, short[] parentAcc, short[][] weights, int[] addedIndices, int[] removedIndices) {
        System.arraycopy(parentAcc, 0, childAcc, 0, NnueManager.HL_SIZE);

        for (int featureIndex : addedIndices) {
            if (featureIndex < 0) continue; // Safety check
            short[] w = weights[featureIndex];
            for (int i = 0; i < LOOP_BOUND; i += SPECIES.length()) {
                ShortVector accVec = ShortVector.fromArray(SPECIES, childAcc, i);
                ShortVector wVec = ShortVector.fromArray(SPECIES, w, i);
                accVec.add(wVec).intoArray(childAcc, i);
            }
        }
        for (int featureIndex : removedIndices) {
            if (featureIndex < 0) continue; // Safety check
            short[] w = weights[featureIndex];
            for (int i = 0; i < LOOP_BOUND; i += SPECIES.length()) {
                ShortVector accVec = ShortVector.fromArray(SPECIES, childAcc, i);
                ShortVector wVec = ShortVector.fromArray(SPECIES, w, i);
                accVec.sub(wVec).intoArray(childAcc, i);
            }
        }
    }

    public int evaluate(short[] stmAcc, short[] oppAcc, short[] stmWeights, short[] oppWeights, short bias) {
        IntVector sumLo = null;
        IntVector sumHi = null;

        final ShortVector zero = ShortVector.zero(SPECIES);
        final ShortVector qa_vec = ShortVector.broadcast(SPECIES, (short) NnueManager.QA);

        for (int i = 0; i < LOOP_BOUND; i += SPECIES.length()) {
            // Load accumulator values
            ShortVector stmValVec = ShortVector.fromArray(SPECIES, stmAcc, i);
            ShortVector oppValVec = ShortVector.fromArray(SPECIES, oppAcc, i);

            // Apply Clipped ReLU: clamp(v, 0, QA)
            stmValVec = stmValVec.max(zero).min(qa_vec);
            oppValVec = oppValVec.max(zero).min(qa_vec);

            // Widen short vectors to integer vectors by casting the conversion result.
            // This splits each ShortVector into two IntVectors (a lower and upper half).
            IntVector stmValLo = (IntVector) stmValVec.convert(VectorOperators.S2I, 0);
            IntVector stmValHi = (IntVector) stmValVec.convert(VectorOperators.S2I, 1);
            IntVector oppValLo = (IntVector) oppValVec.convert(VectorOperators.S2I, 0);
            IntVector oppValHi = (IntVector) oppValVec.convert(VectorOperators.S2I, 1);

            // On the first iteration, initialize the sum vectors.
            if (sumLo == null) {
                sumLo = IntVector.zero(stmValLo.species());
                sumHi = IntVector.zero(stmValHi.species());
            }

            // Square the integer values to complete the SCReLU activation
            IntVector stmSqLo = stmValLo.mul(stmValLo);
            IntVector stmSqHi = stmValHi.mul(stmValHi);
            IntVector oppSqLo = oppValLo.mul(oppValLo);
            IntVector oppSqHi = oppValHi.mul(oppValHi);

            // Load L2 weights and widen them similarly
            ShortVector stmWeightsVec = ShortVector.fromArray(SPECIES, stmWeights, i);
            ShortVector oppWeightsVec = ShortVector.fromArray(SPECIES, oppWeights, i);
            IntVector stmWLo = (IntVector) stmWeightsVec.convert(VectorOperators.S2I, 0);
            IntVector stmWHi = (IntVector) stmWeightsVec.convert(VectorOperators.S2I, 1);
            IntVector oppWLo = (IntVector) oppWeightsVec.convert(VectorOperators.S2I, 0);
            IntVector oppWHi = (IntVector) oppWeightsVec.convert(VectorOperators.S2I, 1);

            // Multiply activated values by weights and accumulate
            sumLo = sumLo.add(stmSqLo.mul(stmWLo));
            sumHi = sumHi.add(stmSqHi.mul(stmWHi));
            sumLo = sumLo.add(oppSqLo.mul(oppWLo));
            sumHi = sumHi.add(oppSqHi.mul(oppWHi));
        }

        // If the loop did not run, return a value based only on the bias.
        if (sumLo == null) {
            return (int) ((long)bias * NnueManager.FV_SCALE / NnueManager.QAB);
        }

        // Reduce the vector sums to a single long
        long output = sumLo.reduceLanes(VectorOperators.ADD) + sumHi.reduceLanes(VectorOperators.ADD);

        // Dequantize
        output /= NnueManager.QA;
        output += bias;
        return (int) (output * NnueManager.FV_SCALE / NnueManager.QAB);
    }
}