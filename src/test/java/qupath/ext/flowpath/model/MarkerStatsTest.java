package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkerStatsTest {

    private static CellIndex buildIndex(List<String> markers, double[][] values) {
        int n = values[0].length;
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ROI roi = ROIs.createPointsROI(i, i, ImagePlane.getDefaultPlane());
            PathObject obj = PathObjects.createDetectionObject(roi);
            for (int m = 0; m < markers.size(); m++) {
                obj.getMeasurements().put(markers.get(m), values[m][i]);
            }
            obj.getMeasurements().put("area", 100.0);
            cells.add(obj);
        }
        return CellIndex.build(cells, markers);
    }

    private static boolean[] allTrue(int n) {
        boolean[] mask = new boolean[n];
        Arrays.fill(mask, true);
        return mask;
    }

    @Test
    void computeWithKnownDistribution() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{1, 2, 3, 4, 5}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(5));

        assertEquals(3.0, stats.getMean("CD45"), 0.001);
        assertEquals(Math.sqrt(2.0), stats.getStd("CD45"), 0.001);
        assertEquals(1.0, stats.getMin("CD45"), 0.001);
        assertEquals(5.0, stats.getMax("CD45"), 0.001);
    }

    @Test
    void zScoreConversion() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{1, 2, 3, 4, 5}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(5));

        assertEquals(0.0, stats.toZScore("CD45", 3.0), 0.001);
        assertEquals(Math.sqrt(2.0), stats.toZScore("CD45", 5.0), 0.001);
    }

    @Test
    void fromZScoreRoundTrips() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{1, 2, 3, 4, 5}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(5));

        double rawValue = 4.2;
        double z = stats.toZScore("CD45", rawValue);
        double recovered = stats.fromZScore("CD45", z);
        assertEquals(rawValue, recovered, 0.001);
    }

    @Test
    void zeroPassingCellsReturnsZeroStats() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{1, 2, 3}});
        boolean[] mask = new boolean[3]; // all false

        MarkerStats stats = MarkerStats.compute(index, mask);

        assertEquals(0.0, stats.getMean("CD45"), 0.001);
        assertEquals(0.0, stats.getStd("CD45"), 0.001);
        assertEquals(0.0, stats.getMin("CD45"), 0.001);
        assertEquals(0.0, stats.getMax("CD45"), 0.001);
    }

    @Test
    void singlePassingCell() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{7.0}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(1));

        assertEquals(7.0, stats.getMean("CD45"), 0.001);
        assertEquals(0.0, stats.getStd("CD45"), 0.001);
    }

    @Test
    void constantValuesGiveZeroStd() {
        double[] vals = new double[10];
        Arrays.fill(vals, 5.0);
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{vals});
        MarkerStats stats = MarkerStats.compute(index, allTrue(10));

        assertEquals(0.0, stats.getStd("CD45"), 0.001);
        assertEquals(0.0, stats.toZScore("CD45", 5.0), 0.001);
    }

    @Test
    void percentileInterpolation() {
        double[] vals = new double[100];
        for (int i = 0; i < 100; i++) vals[i] = i + 1;
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{vals});
        MarkerStats stats = MarkerStats.compute(index, allTrue(100));

        assertEquals(1.0, stats.getPercentileValue("CD45", 0), 0.001);
        assertEquals(50.5, stats.getPercentileValue("CD45", 50), 0.001);
        assertEquals(100.0, stats.getPercentileValue("CD45", 100), 0.001);
    }

    @Test
    void percentileWithFewCells() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{10, 20}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(2));

        assertEquals(10.0, stats.getPercentileValue("CD45", 0), 0.001);
        assertEquals(15.0, stats.getPercentileValue("CD45", 50), 0.001);
        assertEquals(20.0, stats.getPercentileValue("CD45", 100), 0.001);
    }

    @Test
    void partialMaskExcludesCells() {
        double[] vals = new double[10];
        for (int i = 0; i < 10; i++) vals[i] = i + 1;
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{vals});

        // Only odd-indexed cells pass (indices 1,3,5,7,9 -> values 2,4,6,8,10)
        boolean[] mask = new boolean[10];
        for (int i = 0; i < 10; i++) mask[i] = (i % 2 == 1);

        MarkerStats stats = MarkerStats.compute(index, mask);

        // Passing values: 2, 4, 6, 8, 10
        double expectedMean = (2 + 4 + 6 + 8 + 10) / 5.0; // 6.0
        assertEquals(expectedMean, stats.getMean("CD45"), 0.001);
        assertEquals(2.0, stats.getMin("CD45"), 0.001);
        assertEquals(10.0, stats.getMax("CD45"), 0.001);

        // Population std of {2,4,6,8,10}: sqrt(((2-6)^2+(4-6)^2+(6-6)^2+(8-6)^2+(10-6)^2)/5)
        // = sqrt((16+4+0+4+16)/5) = sqrt(8) = 2.8284
        assertEquals(Math.sqrt(8.0), stats.getStd("CD45"), 0.001);
    }

    @Test
    void histogramHasCorrectBinCount() {
        double[] vals = new double[50];
        for (int i = 0; i < 50; i++) vals[i] = i;
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{vals});
        MarkerStats stats = MarkerStats.compute(index, allTrue(50));

        double[] bins = stats.getHistogramBins("CD45");
        double[] counts = stats.getHistogramCounts("CD45");

        assertNotNull(bins);
        assertNotNull(counts);
        assertEquals(201, bins.length);
        assertEquals(200, counts.length);
    }

    @Test
    void multipleMarkersComputedIndependently() {
        double[] cd45Vals = {1, 2, 3, 4, 5};
        double[] cd3Vals = {10, 20, 30, 40, 50};
        CellIndex index = buildIndex(List.of("CD45", "CD3"), new double[][]{cd45Vals, cd3Vals});
        MarkerStats stats = MarkerStats.compute(index, allTrue(5));

        assertEquals(3.0, stats.getMean("CD45"), 0.001);
        assertEquals(30.0, stats.getMean("CD3"), 0.001);
        assertNotEquals(stats.getMean("CD45"), stats.getMean("CD3"));
        assertNotEquals(stats.getStd("CD45"), stats.getStd("CD3"));
    }

    // ---- percentileRankOf: inverse of getPercentileValue --------------------

    @Test
    void percentileRankIsInverseOfPercentileValue() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{10, 20, 30, 40, 50}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(5));

        for (double pct : new double[]{0, 12.5, 25, 50, 70, 99, 100}) {
            double value = stats.getPercentileValue("CD45", pct);
            assertEquals(pct, stats.percentileRankOf("CD45", value), 1e-9,
                    "round-trip must hold at percentile " + pct);
        }
    }

    @Test
    void percentileRankInterpolatesAndClampsToTheEnds() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{10, 20, 30, 40, 50}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(5));

        assertEquals(12.5, stats.percentileRankOf("CD45", 15.0), 1e-9, "halfway between ranks 0 and 25");
        assertEquals(0.0, stats.percentileRankOf("CD45", -100.0), 1e-9, "below the minimum clamps to 0");
        assertEquals(100.0, stats.percentileRankOf("CD45", 999.0), 1e-9, "above the maximum clamps to 100");
    }

    @Test
    void percentileRankReturnsNaNWhenTheColumnIsUnusable() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{10, 20, 30}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(3));

        assertTrue(Double.isNaN(stats.percentileRankOf("NoSuchMarker", 5.0)));
        assertTrue(Double.isNaN(stats.percentileRankOf("CD45", Double.NaN)));
    }

    @Test
    void percentileRankHandlesAConstantColumn() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{7, 7, 7, 7}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(4));

        // Every value equals the min, so the rank pins to 0 rather than dividing by zero.
        assertEquals(0.0, stats.percentileRankOf("CD45", 7.0), 1e-9);
        assertFalse(Double.isNaN(stats.percentileRankOf("CD45", 7.0)));
    }

    @Test
    void percentileRankTracksLazilyRegisteredCompartmentColumns() {
        CellIndex index = buildIndex(List.of("CD45"), new double[][]{{10, 20, 30, 40, 50}});
        MarkerStats stats = MarkerStats.compute(index, allTrue(5));

        // The editor and GatingEngine both register resolved columns this way.
        stats.ensureColumn("CD45: Nucleus: Sum", new double[]{1000, 2000, 3000, 4000, 5000});

        assertTrue(stats.hasColumn("CD45: Nucleus: Sum"));
        assertEquals(70.0, stats.percentileRankOf("CD45: Nucleus: Sum", 3800.0), 1e-9);
        // Same percentile, different column: the basis of the raw-threshold remap.
        assertEquals(38.0, stats.getPercentileValue("CD45",
                stats.percentileRankOf("CD45: Nucleus: Sum", 3800.0)), 1e-9);
    }
}
