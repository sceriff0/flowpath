package qupath.ext.flowpath.ui.widgets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The canvases take the All slides view as plain arrays; one gate is shown, so one gate's worth (C5 exemption). */
class CohortCanvasTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void curvesAndPooledPointsAreHeldAndCleared() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        FxTestSupport.onFxRun(() -> {
            HistogramCanvas h = new HistogramCanvas();
            h.setData(new double[]{1, 2, 3}, 0, 4);
            h.setCohortCurves(List.of(new double[]{1, 2}, new double[]{2, 3}, new double[]{3}), 1);
            assertEquals(3, h.cohortCurveCount());
            h.clearCohortCurves();
            assertEquals(0, h.cohortCurveCount());

            ScatterPlotCanvas s = new ScatterPlotCanvas();
            s.setData(new double[]{1}, new double[]{1}, "X", "Y");
            s.setCohortPoints(new double[]{1, 2, 3}, new double[]{3, 2, 1});
            assertEquals(3, s.cohortPointCount());
            s.clearCohortPoints();
            assertEquals(0, s.cohortPointCount());
        });
    }

    @Test
    void theRidgesAreBinnedWhenSetOrWhenTheWindowMovesNeverOnRepaint() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        FxTestSupport.onFxRun(() -> {
            HistogramCanvas h = new HistogramCanvas();
            h.setData(new double[]{1, 2, 3}, 0, 4);
            double[] a = {1, 2};
            double[] b = {2, 3};
            h.setCohortCurves(List.of(a, b), 1);
            assertEquals(1, h.cohortBinPasses());
            for (int i = 0; i < 5; i++) h.setThreshold(i * 0.5); // a drag: repaints only
            h.setCohortCurves(List.of(a, b), 0);                 // the same arrays again
            h.setData(new double[]{1, 2, 3, 3}, 0, 4);             // new bars, same window
            assertEquals(1, h.cohortBinPasses());
            h.setData(new double[]{1, 2, 3}, 0, 5);                // the window moved
            assertEquals(2, h.cohortBinPasses());
            h.setCohortCurves(List.of(a, new double[]{2, 3}), 0); // a new slide array
            assertEquals(3, h.cohortBinPasses());
        });
    }
}
