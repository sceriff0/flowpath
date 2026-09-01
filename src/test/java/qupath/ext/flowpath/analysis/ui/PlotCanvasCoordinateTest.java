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
}
