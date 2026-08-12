package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One placement rule, two callers. These pin the behaviour both of them rely on — most
 * of all that a coincident neighbour produces a position rather than a NaN, which is the
 * shape of failure that would reach the canvas as an invisible cell.
 */
class InverseDistanceBlendTest {

    private static final double[][] POSITIONS = { {0, 0}, {10, 0}, {0, 10}, {10, 10} };

    @Test
    void nearerNeighboursPullHarder() {
        double[] out = new double[2];
        assertTrue(InverseDistanceBlend.place(new int[] {0, 1}, new double[] {1, 3},
                POSITIONS, -1, out));
        // weights 1/1 and 1/3 -> x = (1*0 + (1/3)*10) / (4/3) = 2.5
        assertEquals(2.5, out[0], 1e-9);
        assertEquals(0.0, out[1], 1e-9);
    }

    @Test
    void equalDistancesGiveThePlainMean() {
        double[] out = new double[2];
        assertTrue(InverseDistanceBlend.place(new int[] {0, 3}, new double[] {2, 2},
                POSITIONS, -1, out));
        assertArrayEquals(new double[] {5, 5}, out, 1e-9);
    }

    @Test
    void aCoincidentNeighbourDominatesInsteadOfPoisoningTheResult() {
        // Duplicate cells are real in multiplexed imaging, and a zero distance is a
        // division by zero away from an NaN coordinate — a cell that simply does not
        // draw, with nothing in the log to say why.
        double[] out = new double[2];
        assertTrue(InverseDistanceBlend.place(new int[] {1, 2}, new double[] {0, 5},
                POSITIONS, -1, out));
        assertTrue(Double.isFinite(out[0]) && Double.isFinite(out[1]),
                "a zero distance must not produce NaN: " + out[0] + "," + out[1]);
        assertEquals(10.0, out[0], 1e-6);
        assertEquals(0.0, out[1], 1e-6);
    }

    @Test
    void unfilledSlotsAndTheExcludedIndexAreSkipped() {
        double[] out = new double[2];
        assertTrue(InverseDistanceBlend.place(new int[] {-1, 2, 3}, new double[] {1, 1, 1},
                POSITIONS, 3, out));
        assertArrayEquals(new double[] {0, 10}, out, 1e-9,
                "only neighbour 2 should have counted");
    }

    @Test
    void nothingIsWrittenWhenNoNeighbourCarriesWeight() {
        double[] out = { -7, -7 };
        assertFalse(InverseDistanceBlend.place(new int[] {-1, -1}, new double[] {1, 1},
                POSITIONS, -1, out));
        assertArrayEquals(new double[] {-7, -7}, out,
                "the caller's fallback must survive intact");
    }

    @Test
    void theTargetRowMayAliasTheOutput() {
        // EmbeddingInitialisation repairs a node in place, so `out` IS a row of
        // `positions`. Accumulating straight into it would blend the artefact position
        // being replaced back into its own replacement.
        double[][] positions = { {0, 0}, {10, 0}, {-1000, -1000} };
        assertTrue(InverseDistanceBlend.place(new int[] {0, 1}, new double[] {1, 1},
                positions, 2, positions[2]));
        assertArrayEquals(new double[] {5, 0}, positions[2], 1e-9);
    }
}
