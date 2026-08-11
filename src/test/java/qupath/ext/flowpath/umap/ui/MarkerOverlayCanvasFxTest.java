package qupath.ext.flowpath.umap.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import qupath.ext.flowpath.testing.FxTestSupport;

/**
 * Real scene-graph coverage for {@link MarkerOverlayCanvas}. Unlike the pure-logic
 * tests, these construct the actual JavaFX {@link javafx.scene.canvas.Canvas} on the
 * FX application thread, so they only run where a toolkit is available (skipped on a
 * headless machine without xvfb, failed loudly when {@code FLOWPATH_FX_REQUIRED=true}).
 */
class MarkerOverlayCanvasFxTest {

    private static final double DELTA = 1e-9;

    @Test
    void constructsWithExpectedDimensionsAndSizingHints() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        MarkerOverlayCanvas canvas = FxTestSupport.onFx(MarkerOverlayCanvas::new);

        assertEquals(400, canvas.getWidth(), DELTA);
        assertEquals(400, canvas.getHeight(), DELTA);
        assertTrue(canvas.isResizable());
        assertEquals(400, canvas.prefWidth(-1), DELTA);
        assertEquals(150, canvas.minWidth(-1), DELTA);
    }

    @Test
    void setDataWithFiniteValuesDoesNotThrow() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        FxTestSupport.onFxRun(() -> {
            MarkerOverlayCanvas canvas = new MarkerOverlayCanvas();
            canvas.setData(new double[]{1, 2, 3}, new double[]{1, 2, 3});
            assertEquals(400, canvas.getWidth(), DELTA);
            assertEquals(400, canvas.getHeight(), DELTA);
        });
    }
}
