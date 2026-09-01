package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layout arithmetic only — no toolkit. Every method under test is reachable through an
 * SvgSurface plus an ApproxTextMeasurer, which is the point of Task 2's abstraction.
 */
class PlotCanvasLayoutTest {

    /** A minimal concrete canvas; draw() is never called by these tests. */
    private static final class Probe extends PlotCanvas {
        Probe() { super(400, 200); }
        @Override protected void draw(PlotSurface s, PlotTheme theme) { }
        @Override public List<PlotDatum> plotData() { return List.of(); }
        LabelLayout layout(PlotSurface s, List<String> labels) { return layoutLabels(s, labels); }
        double top(int rows) { return plotTop(rows); }
        double height(LabelLayout l, int rows) { return plotHeight(l, rows); }
        void legend(PlotSurface s, int rows, List<String> labels) {
            drawLegend(s, PlotTheme.LIGHT, rows, labels, List.of());
        }
    }

    @Test
    void niceTicksStepInOnesTwosOrFives() {
        assertArrayEquals(new double[] { 0, 20, 40, 60, 80, 100 },
                PlotCanvas.niceTicks(0, 100, 5), 1e-9);
        double[] ticks = PlotCanvas.niceTicks(0, 41733, 4);
        for (double t : ticks) {
            assertEquals(t, Math.rint(t), 1e-9, "a count tick is never fractional: " + t);
        }
        assertTrue(ticks[ticks.length - 1] <= 41733, "ticks stay inside the range");
    }

    @Test
    void niceTicksSurvivesADegenerateRange() {
        assertArrayEquals(new double[] { 5 }, PlotCanvas.niceTicks(5, 5, 4), 1e-9);
        assertArrayEquals(new double[] { 0 }, PlotCanvas.niceTicks(0, Double.NaN, 4), 1e-9);
    }

    @Test
    void shortLabelsStayHorizontal() {
        PlotSurface s = new SvgSurface(400, 200, new ApproxTextMeasurer());
        s.setFont(8, false);
        PlotCanvas.LabelLayout layout = new Probe().layout(s, List.of("CD4", "CD8", "B", "NK"));
        assertFalse(layout.rotated(), "four short labels fit four wide slots");
        assertEquals(30, layout.bottomPadding(), 1e-9);
        assertEquals(List.of("CD4", "CD8", "B", "NK"), layout.text());
    }

    @Test
    void longLabelsRotateAndTruncateRatherThanOverlap() {
        PlotSurface s = new SvgSurface(400, 200, new ApproxTextMeasurer());
        s.setFont(8, false);
        List<String> paths = List.of(
                "CD45+/CD3+/CD8+/PD1+", "CD45+/CD3+/CD8+/PD1-", "CD45+/CD3+/CD4+/FOXP3+",
                "CD45+/CD3+/CD4+/FOXP3-", "CD45+/CD3-/CD19+", "CD45+/CD3-/CD19-",
                "CD45-/PanCK+", "CD45-/PanCK-");
        PlotCanvas.LabelLayout layout = new Probe().layout(s, paths);
        assertTrue(layout.rotated(), "eight long paths cannot sit side by side");
        assertEquals(64, layout.bottomPadding(), 1e-9);
        assertEquals(paths.size(), layout.text().size(), "one label per slot, still");
        assertTrue(layout.text().stream().anyMatch(t -> t.endsWith("…")),
                "over-long labels are elided rather than drawn past their slot");
        for (String t : layout.text()) {
            assertTrue(s.textWidth(t) <= 84.001, "no label exceeds the 84px cap: " + t);
        }
    }

    @Test
    void theLegendReservesItsOwnStripSoItCannotOverlapTheData() {
        Probe probe = new Probe();
        double withoutLegend = probe.top(0);
        double withLegend = probe.top(3);
        assertTrue(withLegend > withoutLegend, "a legend pushes the plot down");
        assertEquals(3 * 14 + 6, withLegend - withoutLegend, 1e-9);

        PlotCanvas.LabelLayout flat = new PlotCanvas.LabelLayout(false, 30, List.of("a"));
        assertTrue(probe.height(flat, 3) < probe.height(flat, 0),
                "the strip comes out of the plot's own height, never off the bottom of the canvas");
    }

    @Test
    void rotatedLabelsTakeTheirExtraRoomFromThePlotNotFromTheCanvas() {
        Probe probe = new Probe();
        PlotCanvas.LabelLayout flat = new PlotCanvas.LabelLayout(false, 30, List.of("a"));
        PlotCanvas.LabelLayout tilted = new PlotCanvas.LabelLayout(true, 64, List.of("a"));
        assertEquals(34, probe.height(flat, 0) - probe.height(tilted, 0), 1e-9);
    }

    /**
     * Added after review: the tick target became a number a user can set (Task 6 exposes it),
     * and this is the end of its range that returns nothing at all. {@code niceTicks(3, 30, 1)}
     * asks for one tick across a span of 27, which rounds the step up to 50, whose first
     * multiple at or above 3 is 50 — past the range. Empty is the honest answer: there is no
     * round number inside {@code [3, 30]} on a step of 50. The superseded {@code valueTicks}
     * returned {@code {3}} here, a tick at a value the ladder never offers, which is the
     * behaviour change this pins rather than the coverage gap it was mistaken for.
     */
    @Test
    void aTickTargetTooLowToLandOnTheRangeYieldsNoTicksRatherThanAWrongOne() {
        assertArrayEquals(new double[0], PlotCanvas.niceTicks(3, 30, 1), 1e-9);
    }

    /**
     * The other end of the same user-settable input. {@code range / targetCount} divides by
     * zero at 0 and yields a negative step below it, so a non-positive target is guarded and
     * answers with the range's own minimum — the same answer a degenerate range gives, for the
     * same reason: there is no ladder to climb.
     */
    @Test
    void aNonPositiveTickTargetIsGuardedRatherThanDividedBy() {
        assertArrayEquals(new double[] { 3 }, PlotCanvas.niceTicks(3, 30, 0), 1e-9);
        assertArrayEquals(new double[] { 3 }, PlotCanvas.niceTicks(3, 30, -4), 1e-9);
    }

    /**
     * The legend strip is only real if the row count that bought it and the row count that
     * fills it are the same number. Drawing more rows than were reserved spills over the plot
     * frame — the very overlap the strip exists to prevent — so the mismatch is rejected rather
     * than drawn, and rejected in both directions, since a legend short of its strip leaves a
     * band of dead space above every chart.
     */
    @Test
    void aLegendCannotDrawMoreRowsThanTheStripItWasGiven() {
        PlotSurface s = new SvgSurface(400, 200, new ApproxTextMeasurer());
        Probe probe = new Probe();
        assertThrows(IllegalArgumentException.class,
                () -> probe.legend(s, 2, List.of("Positive", "Negative", "Ungated")));
        assertThrows(IllegalArgumentException.class,
                () -> probe.legend(s, 3, List.of("Positive", "Negative")));
        assertDoesNotThrow(() -> probe.legend(s, 3, List.of("Positive", "Negative", "Ungated")));
        assertDoesNotThrow(() -> probe.legend(s, 0, List.of()));
    }

    @Test
    void truncateToWidthLeavesShortTextAlone() {
        PlotSurface s = new SvgSurface(400, 200, new ApproxTextMeasurer());
        s.setFont(8, false);
        assertEquals("CD4", PlotCanvas.truncateToWidth(s, "CD4", 200));
        String cut = PlotCanvas.truncateToWidth(s, "CD45+/CD3+/CD8+/PD1+", 30);
        assertTrue(cut.endsWith("…"));
        assertTrue(s.textWidth(cut) <= 30.001);
    }
}
