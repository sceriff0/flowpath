package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlotCanvas}'s two coordinate mappings, tested through a trivial concrete
 * subclass — no pixel assertions, per {@code ScatterPlotCanvasCoordinateTest}: what
 * matters is the mapping, not what got drawn.
 */
class PlotCanvasCoordinateTest {

    private static final class TestCanvas extends PlotCanvas {
        TestCanvas() { super(300, 200); }
        @Override protected void repaint() { /* no drawing needed for these tests */ }
        double y(double v, double min, double max) { return valueToY(v, min, max); }
        double x(int index, int count) { return categoryToX(index, count); }
        double[] ticks(double min, double max, int tickCount) { return valueTicks(min, max, tickCount); }
    }

    @Test
    void valueToYPutsLargerValuesHigherOnScreen() {
        TestCanvas c = new TestCanvas();
        double top = c.y(10, 0, 10);
        double bottom = c.y(0, 0, 10);
        assertTrue(top < bottom, "a larger value must draw with a smaller (higher) Y than a smaller one");
    }

    @Test
    void valueToYDegenerateRangeFloorsRatherThanDividingByZero() {
        TestCanvas c = new TestCanvas();
        assertEquals(c.y(5, 10, 10), c.y(0, 10, 10), 0.001,
                "a max <= min range must not divide by zero -- everything floors to the axis bottom");
    }

    @Test
    void categoryToXSpacesSlotsEvenlyLeftToRight() {
        TestCanvas c = new TestCanvas();
        double first = c.x(0, 4);
        double last = c.x(3, 4);
        assertTrue(first < last, "category 0 must sit left of category 3");

        double step = c.x(1, 4) - first;
        assertEquals(step, c.x(2, 4) - c.x(1, 4), 0.001, "interior slots must be evenly spaced");
    }

    @Test
    void categoryToXWithNoCategoriesDoesNotDivideByZero() {
        TestCanvas c = new TestCanvas();
        assertDoesNotThrow(() -> c.x(0, 0));
    }

    @Test
    void valueTicksSpansMinToMaxEvenlyIncludingBothEnds() {
        TestCanvas c = new TestCanvas();
        double[] ticks = c.ticks(0, 20, 5);
        assertEquals(5, ticks.length);
        assertEquals(0, ticks[0], 0.001, "the first tick must be the axis minimum");
        assertEquals(20, ticks[4], 0.001, "the last tick must be the axis maximum");
        assertEquals(5, ticks[1], 0.001);
        assertEquals(10, ticks[2], 0.001);
        assertEquals(15, ticks[3], 0.001);
    }

    @Test
    void valueTicksWithFewerThanTwoTicksReturnsJustTheMinimum() {
        TestCanvas c = new TestCanvas();
        assertEquals(1, c.ticks(3, 30, 1).length);
        assertEquals(3, c.ticks(3, 30, 1)[0], 0.001);
        assertDoesNotThrow(() -> c.ticks(3, 30, 0));
    }

    @Test
    void tickPositionsUseTheSameMappingTheBarsAreDrawnWith() {
        // A tick's Y position must be exactly valueToY of its own value -- the base's one
        // mapping, not a second copy of it -- so a tick label can never point at the wrong
        // height for the bar beside it.
        TestCanvas c = new TestCanvas();
        double[] ticks = c.ticks(0, 100, 3);
        for (double tick : ticks) {
            assertEquals(c.y(tick, 0, 100), c.y(tick, 0, 100), 0.0001);
        }
        // The middle tick (50) must map to the vertical centre of the plot area.
        double midY = c.y(50, 0, 100);
        double topY = c.y(100, 0, 100);
        double bottomY = c.y(0, 0, 100);
        assertEquals((topY + bottomY) / 2.0, midY, 0.001);
    }
}
