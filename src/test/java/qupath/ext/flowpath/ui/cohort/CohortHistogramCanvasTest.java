package qupath.ext.flowpath.ui.cohort;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The canvas's x ↔ log-value mapping: pure arithmetic, no toolkit. */
class CohortHistogramCanvasTest {

    @Test
    void logAtInvertsXOf() {
        double min = 1.25, max = 9.75, w = 412;
        for (double u : new double[]{min, 2.0, 4.321, 7.5, max}) {
            assertEquals(u, CohortHistogramCanvas.logAt(CohortHistogramCanvas.xOf(u, w, min, max), w, min, max), 1e-9);
        }
    }

    @Test
    void theEndsMapToTheGridEnds() {
        double min = -0.5, max = 6.0, w = 300;
        assertEquals(min, CohortHistogramCanvas.logAt(0, w, min, max), 1e-12);
        assertEquals(max, CohortHistogramCanvas.logAt(w, w, min, max), 1e-12);
        assertEquals(0, CohortHistogramCanvas.xOf(min, w, min, max), 1e-12);
        assertEquals(w, CohortHistogramCanvas.xOf(max, w, min, max), 1e-12);
    }

    @Test
    void aDegenerateGridOrWidthAnswersTheGridMinimum() {
        assertEquals(3.0, CohortHistogramCanvas.logAt(50, 100, 3.0, 3.0));
        assertEquals(3.0, CohortHistogramCanvas.logAt(50, 0, 3.0, 4.0));
        assertEquals(0, CohortHistogramCanvas.xOf(3.0, 100, 3.0, 3.0));
    }
}
