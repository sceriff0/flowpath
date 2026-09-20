package qupath.ext.flowpath.ui.widgets;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScatterPlotCanvasCoordinateTest {

    @Test
    void valueToPixelAndBack() {
        double min = 0, max = 10, size = 200;
        double pixel = ScatterPlotCanvas.valueToPixel(5.0, min, max, size);
        assertEquals(100.0, pixel, 0.01);
        double roundTrip = ScatterPlotCanvas.pixelToValue(pixel, min, max, size);
        assertEquals(5.0, roundTrip, 0.001);
    }

    @Test
    void valueToPixelEdgeCases() {
        double min = -5, max = 5, size = 100;
        assertEquals(0.0, ScatterPlotCanvas.valueToPixel(-5, min, max, size), 0.01);
        assertEquals(100.0, ScatterPlotCanvas.valueToPixel(5, min, max, size), 0.01);
        assertEquals(50.0, ScatterPlotCanvas.valueToPixel(0, min, max, size), 0.01);
    }

    @Test
    void pixelToValueEdgeCases() {
        double min = 0, max = 100, size = 200;
        assertEquals(0.0, ScatterPlotCanvas.pixelToValue(0, min, max, size), 0.01);
        assertEquals(100.0, ScatterPlotCanvas.pixelToValue(200, min, max, size), 0.01);
        assertEquals(50.0, ScatterPlotCanvas.pixelToValue(100, min, max, size), 0.01);
    }

    @Test
    void valueToPixelDegenerateRange() {
        assertEquals(0.0, ScatterPlotCanvas.valueToPixel(5.0, 10, 10, 100), 0.01);
        assertEquals(0.0, ScatterPlotCanvas.pixelToValue(50, 0, 100, 0), 0.01);
    }
}
