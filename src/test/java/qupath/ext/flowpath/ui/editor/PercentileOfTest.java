package qupath.ext.flowpath.ui.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PercentileOfTest {

    @Test
    void medianOfOddArray() {
        double[] v = {1, 2, 3, 4, 5};
        assertEquals(3.0, AxisMath.percentileOf(v, 50.0), 1e-9);
    }

    @Test
    void minAndMaxPercentiles() {
        double[] v = {10, 20, 30, 40};
        assertEquals(10.0, AxisMath.percentileOf(v, 0.0), 1e-9);
        assertEquals(40.0, AxisMath.percentileOf(v, 100.0), 1e-9);
    }

    @Test
    void ignoresNaNs() {
        double[] v = {Double.NaN, 1, 2, 3, Double.NaN};
        assertEquals(2.0, AxisMath.percentileOf(v, 50.0), 1e-9);
    }

    @Test
    void emptyOrAllNaNReturnsNaN() {
        assertTrue(Double.isNaN(AxisMath.percentileOf(new double[0], 50.0)));
        assertTrue(Double.isNaN(AxisMath.percentileOf(new double[]{Double.NaN}, 50.0)));
    }

    @Test
    void interpolatesBetweenElements() {
        // rank = 0.5*(2-1) = 0.5 -> halfway between 0 and 10
        assertEquals(5.0, AxisMath.percentileOf(new double[]{0, 10}, 50.0), 1e-9);
        // rank = 0.25*(5-1) = 1.0 -> exactly element index 1
        assertEquals(20.0, AxisMath.percentileOf(new double[]{10, 20, 30, 40, 50}, 25.0), 1e-9);
        // rank = 0.1*(5-1) = 0.4 -> 10 + 0.4*(20-10) = 14
        assertEquals(14.0, AxisMath.percentileOf(new double[]{10, 20, 30, 40, 50}, 10.0), 1e-9);
    }
}
