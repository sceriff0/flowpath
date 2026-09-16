package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The quadrant sliders travel over the window the scatter plot shows, not the raw data
 * extremes.
 * <p>
 * They used to span every outlier, so on a skewed marker (values from 10 to 8000, most cells
 * below 300) the whole visible population sat in the first few pixels of a slider and a
 * single pixel of drag crossed it.
 */
class QuadrantSliderSpanTest {

    @Test
    void travelIsTheVisibleWindow() {
        assertArrayEquals(new double[]{-1.2, 2.8},
                GateEditorPane.quadrantSliderSpan(new double[]{-1.2, 2.8}, 0.5), 1e-12);
    }

    @Test
    void aThresholdOutsideTheWindowWidensItRatherThanPinningTheThumb() {
        assertArrayEquals(new double[]{-1.2, 6.0},
                GateEditorPane.quadrantSliderSpan(new double[]{-1.2, 2.8}, 6.0), 1e-12);
        assertArrayEquals(new double[]{-4.0, 2.8},
                GateEditorPane.quadrantSliderSpan(new double[]{-1.2, 2.8}, -4.0), 1e-12);
    }

    @Test
    void noWindowFallsBackToTheFixedDefaultWindow() {
        assertArrayEquals(new double[]{-5, 5}, GateEditorPane.quadrantSliderSpan(null, 0.0), 1e-12);
        assertArrayEquals(new double[]{-5, 5},
                GateEditorPane.quadrantSliderSpan(null, Double.NaN), 1e-12);
    }
}
