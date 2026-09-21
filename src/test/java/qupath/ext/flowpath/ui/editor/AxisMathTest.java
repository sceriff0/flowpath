package qupath.ext.flowpath.ui.editor;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The editors' axis arithmetic, without a toolkit. {@code quadrantSliderSpan},
 * {@code percentileOf} and {@code parseThreshold} keep their older pins
 * ({@code QuadrantSliderSpanTest}, {@code PercentileOfTest}, {@code GateEditorSignalTest});
 * this class pins the rest.
 */
class AxisMathTest {

    private static final int N = 101;

    /** CD3 = 0..100 whole-cell mean; its nuclear median = 10x that; CD4 constant. */
    private static CellIndex index() {
        return Cells.of(N)
                .marker("CD3", i -> i)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, i -> 10.0 * i)
                .marker("CD4", i -> 7.0)
                .area(50.0)
                .build();
    }

    private static MeasuredColumn column(CellIndex index, String channel, Compartment c, Statistic s) {
        return index.column(channel, c, s, MarkerStats.compute(index, Cells.allTrue(N)));
    }

    // ---- clipSpan ---------------------------------------------------------------------

    @Test
    void clipSpanIsTheColumnsClipPercentiles() {
        CellIndex index = index();
        MeasuredColumn col = column(index, "CD3", Compartment.WHOLE_CELL, Statistic.MEAN);
        assertArrayEquals(new double[]{col.percentile(1), col.percentile(99)},
                AxisMath.clipSpan(col, 1, 99), 1e-12);
    }

    @Test
    void clipSpanIsNullWhenThereIsNoUsableWindow() {
        CellIndex index = index();
        assertNull(AxisMath.clipSpan(null, 1, 99), "no column");
        assertNull(AxisMath.clipSpan(column(index, "CD4", Compartment.WHOLE_CELL, Statistic.MEAN), 1, 99),
                "a constant column has no width");
    }

    // ---- thresholdWindow ------------------------------------------------------------------

    @Test
    void thresholdWindowIsTheGlobalClipRangeWhenItIsUsable() {
        assertArrayEquals(new double[]{2, 8},
                AxisMath.thresholdWindow(2, 8, new double[]{-100, 100}), 1e-12,
                "the displayed values do not move a usable global window");
    }

    @Test
    void thresholdWindowFallsBackToTheDisplayedExtremes() {
        assertArrayEquals(new double[]{3, 9},
                AxisMath.thresholdWindow(Double.NaN, 5, new double[]{9, 3, 4}), 1e-12);
        assertArrayEquals(new double[]{3, 9},
                AxisMath.thresholdWindow(5, 5, new double[]{9, 3, 4}), 1e-12, "a zero-width window");
    }

    @Test
    void thresholdWindowWidensAConstantDisplayAndDefaultsWhenEmpty() {
        assertArrayEquals(new double[]{4, 5},
                AxisMath.thresholdWindow(Double.NaN, Double.NaN, new double[]{4, 4}), 1e-12);
        assertArrayEquals(new double[]{0, 1},
                AxisMath.thresholdWindow(Double.NaN, Double.NaN, new double[0]), 1e-12);
    }

    // ---- measuredValues -------------------------------------------------------------------

    @Test
    void measuredValuesDropsMaskedAndUnmeasuredCells() {
        double[] all = {1, Double.NaN, 3, 4, 5};
        boolean[] roi = {true, true, true, false, true};
        boolean[] ancestor = {true, true, false, true, true};
        assertArrayEquals(new double[]{1, 5}, AxisMath.measuredValues(all, roi, ancestor), 0);
        assertArrayEquals(new double[]{1, 3, 4, 5}, AxisMath.measuredValues(all, null, null), 0);
    }

    @Test
    void measuredValuesReturnsTheColumnItselfWhenNothingIsDropped() {
        double[] all = {1, 2, 3};
        assertSame(all, AxisMath.measuredValues(all, null, null),
                "the plot hot path must not copy an unfiltered column");
    }

    // ---- pairedMaskedValues ---------------------------------------------------------------

    @Test
    void pairedMaskedValuesFiltersBothAxesInLockstep() {
        double[] allX = {1, 2, 3, 4, 5};
        double[] allY = {10, 20, 30, 40, 50};
        boolean[] roi = {true, true, true, false, true};
        boolean[] ancestor = {true, true, false, true, true};
        double[][] filtered = AxisMath.pairedMaskedValues(allX, allY, roi, ancestor);
        assertArrayEquals(new double[]{1, 2, 5}, filtered[0], 0);
        assertArrayEquals(new double[]{10, 20, 50}, filtered[1], 0);
    }

    @Test
    void pairedMaskedValuesDoesNotDropNaNUnlikeMeasuredValues() {
        double[] allX = {1, Double.NaN, 3};
        double[] allY = {10, 20, Double.NaN};
        double[][] filtered = AxisMath.pairedMaskedValues(allX, allY, null, null);
        assertArrayEquals(allX, filtered[0], 0, "NaN kept: the two axes must stay in lockstep");
        assertArrayEquals(allY, filtered[1], 0);
    }

    @Test
    void pairedMaskedValuesReturnsTheColumnsThemselvesWhenBothMasksAreNull() {
        double[] allX = {1, 2, 3};
        double[] allY = {4, 5, 6};
        double[][] filtered = AxisMath.pairedMaskedValues(allX, allY, null, null);
        assertSame(allX, filtered[0], "the scatter hot path must not copy an unfiltered column");
        assertSame(allY, filtered[1]);
    }

    // ---- remapRawThreshold ----------------------------------------------------------------

    @Test
    void aThresholdKeepsItsPercentileAcrossColumns() {
        CellIndex index = index();
        MeasuredColumn mean = column(index, "CD3", Compartment.WHOLE_CELL, Statistic.MEAN);
        MeasuredColumn nuclear = column(index, "CD3", Compartment.NUCLEAR, Statistic.MEDIAN);
        double mapped = AxisMath.remapRawThreshold(mean, nuclear, 30);
        assertEquals(nuclear.percentile(mean.percentileRankOf(30)), mapped, 1e-9);
        assertEquals(300, mapped, 1.0, "30 of 0..100 is 300 of 0..1000");
    }

    @Test
    void aThresholdIsLeftAloneWhenItCannotOrNeedNotMove() {
        CellIndex index = index();
        MeasuredColumn mean = column(index, "CD3", Compartment.WHOLE_CELL, Statistic.MEAN);
        assertEquals(30, AxisMath.remapRawThreshold(null, mean, 30), 0);
        assertEquals(30, AxisMath.remapRawThreshold(mean, null, 30), 0);
        assertEquals(30, AxisMath.remapRawThreshold(mean,
                column(index, "CD3", Compartment.WHOLE_CELL, Statistic.MEAN), 30), 0, "same column");
    }
}
