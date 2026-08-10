package qupath.ext.flowpath.umap.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MarkerOverlayCanvas}.
 * <p>
 * Focused on pure helper logic; does not exercise the JavaFX scene graph.
 */
class MarkerOverlayCanvasTest {

    // --- sortedFiniteSample: the percentile input, sampled instead of fully sorted ---

    @Test
    void sortedFiniteSample_smallInput_returnsEveryFiniteValueSorted() {
        double[] values = {3.0, 1.0, 2.0};

        double[] out = MarkerOverlayCanvas.sortedFiniteSample(values, 1000);

        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, out);
    }

    @Test
    void sortedFiniteSample_dropsNonFiniteValues() {
        double[] values = {5.0, Double.NaN, 1.0, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 3.0};

        double[] out = MarkerOverlayCanvas.sortedFiniteSample(values, 1000);

        assertArrayEquals(new double[]{1.0, 3.0, 5.0}, out);
    }

    @Test
    void sortedFiniteSample_allNonFinite_returnsEmpty() {
        double[] values = {Double.NaN, Double.POSITIVE_INFINITY};

        assertEquals(0, MarkerOverlayCanvas.sortedFiniteSample(values, 1000).length);
    }

    @Test
    void sortedFiniteSample_largeInput_isCappedAndSorted() {
        int n = 500_000;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = n - i; // descending

        double[] out = MarkerOverlayCanvas.sortedFiniteSample(values, 50_000);

        assertTrue(out.length <= 50_001,
                "sample must respect the cap, got " + out.length);
        assertTrue(out.length > 1000, "sample must not collapse to nothing");
        for (int i = 1; i < out.length; i++) {
            assertTrue(out[i] >= out[i - 1], "sample must be sorted");
        }
    }

    @Test
    void sortedFiniteSample_isDeterministic() {
        // The legend must not shift between two renders of identical data, so the
        // stride has to be fixed rather than randomised.
        int n = 200_000;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = (i * 7919) % 1000;

        assertArrayEquals(
                MarkerOverlayCanvas.sortedFiniteSample(values, 10_000),
                MarkerOverlayCanvas.sortedFiniteSample(values, 10_000));
    }

    @Test
    void sortedFiniteSample_percentilesTrackTheFullSort() {
        // The whole point of sampling is that the 1st/99th percentile barely moves.
        int n = 400_000;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = i; // uniform 0..n-1

        double[] full = MarkerOverlayCanvas.sortedFiniteSample(values, n);
        double[] sampled = MarkerOverlayCanvas.sortedFiniteSample(values, 50_000);

        double[] fullRange = MarkerOverlayCanvas.computePercentileRange(full);
        double[] sampledRange = MarkerOverlayCanvas.computePercentileRange(sampled);

        // Within 0.5% of the value range — far below one display step.
        double tolerance = n * 0.005;
        assertEquals(fullRange[0], sampledRange[0], tolerance);
        assertEquals(fullRange[1], sampledRange[1], tolerance);
    }

    @Test
    void sortedFiniteSample_emptyInput() {
        assertEquals(0, MarkerOverlayCanvas.sortedFiniteSample(new double[0], 100).length);
    }

    @Test
    void percentileRange_hundredValues_picksJustInsideExtremes() {
        // 100 sorted values: 1, 2, ..., 100
        double[] finite = new double[100];
        for (int i = 0; i < 100; i++) finite[i] = i + 1;

        double[] range = MarkerOverlayCanvas.computePercentileRange(finite);

        // 1st percentile: round(100 * 0.01) = 1, clamped to 99 → finite[1] = 2.0
        // 99th percentile: round(100 * 0.99) - 1 = 98 → finite[98] = 99.0
        assertEquals(2.0, range[0], 1e-9, "1st percentile should be just above the min");
        assertEquals(99.0, range[1], 1e-9, "99th percentile should be just below the max");
    }

    @Test
    void percentileRange_tinyArray_doesNotCrashAndProducesValidBounds() {
        // 3 values: low and high collapse to first/last but the helper must not crash
        double[] finite = {1.0, 2.0, 3.0};
        double[] range = MarkerOverlayCanvas.computePercentileRange(finite);

        // loIdx = min(2, round(0.03)) = 0 → finite[0] = 1.0
        // hiIdx = max(0, round(2.97) - 1) = max(0, 2) = 2 → finite[2] = 3.0
        assertEquals(1.0, range[0], 1e-9);
        assertEquals(3.0, range[1], 1e-9);
        assertTrue(range[1] >= range[0], "high must be >= low");
    }

    @Test
    void percentileRange_singleValue_lowEqualsHigh() {
        double[] finite = {5.0};
        double[] range = MarkerOverlayCanvas.computePercentileRange(finite);

        // loIdx = min(0, round(0.01)) = 0 → finite[0] = 5.0
        // hiIdx = max(0, round(0.99) - 1) = max(0, 0) = 0 → finite[0] = 5.0
        // Helper returns faithful {v, v} pair; caller is responsible for
        // applying any divide-by-zero guard.
        assertEquals(5.0, range[0], 1e-9);
        assertEquals(5.0, range[1], 1e-9);
    }

    @Test
    void percentileRange_emptyArray_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MarkerOverlayCanvas.computePercentileRange(new double[0]));
    }

    @Test
    void percentileRange_nullArray_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MarkerOverlayCanvas.computePercentileRange(null));
    }

    @Test
    void percentileRange_twoValues_loAtMin_hiAtMax() {
        double[] finite = {10.0, 20.0};
        double[] range = MarkerOverlayCanvas.computePercentileRange(finite);

        // loIdx = min(1, round(0.02)) = 0 → 10.0
        // hiIdx = max(0, round(1.98) - 1) = max(0, 1) = 1 → 20.0
        assertEquals(10.0, range[0], 1e-9);
        assertEquals(20.0, range[1], 1e-9);
    }
}
