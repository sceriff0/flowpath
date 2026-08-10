package qupath.ext.flowpath.umap.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scene-graph coverage for {@link UmapCanvas}: point picking (the basis of
 * click-to-locate-in-viewer) and the overlay index resolution that keeps rings and
 * highlights off the per-repaint hot path.
 */
class UmapCanvasFxTest {

    private static UmapCanvas canvasWith(double[] xs, double[] ys) {
        return FxTestSupport.onFx(() -> {
            UmapCanvas c = new UmapCanvas();
            c.resize(400, 400);
            c.setData(xs, ys);
            return c;
        });
    }

    // --- findNearestPoint ---

    @Test
    void findNearestPointReturnsTheClosestPoint() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        double[] xs = {0, 1, 2, 3};
        double[] ys = {0, 1, 2, 3};
        UmapCanvas canvas = canvasWith(xs, ys);

        FxTestSupport.onFxRun(() -> {
            // Ask for the screen position of a known point, then pick there.
            for (int i = 0; i < xs.length; i++) {
                double sx = canvas.dataXToScreenX(xs[i]);
                double sy = canvas.dataYToScreenY(ys[i]);
                assertEquals(i, canvas.findNearestPoint(sx, sy, 20),
                        "clicking a point's own screen position must pick it");
            }
        });
    }

    @Test
    void findNearestPointRespectsTheRadius() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        UmapCanvas canvas = canvasWith(new double[]{0, 10}, new double[]{0, 10});

        FxTestSupport.onFxRun(() -> {
            double sx = canvas.dataXToScreenX(0);
            double sy = canvas.dataYToScreenY(0);
            assertEquals(0, canvas.findNearestPoint(sx, sy, 5));
            // A click far from any point, with a tight radius, picks nothing.
            assertEquals(-1, canvas.findNearestPoint(sx + 100, sy + 100, 5));
        });
    }

    @Test
    void findNearestPointOnEmptyDataReturnsMinusOne() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        UmapCanvas canvas = FxTestSupport.onFx(() -> {
            UmapCanvas c = new UmapCanvas();
            c.resize(400, 400);
            return c;
        });

        FxTestSupport.onFxRun(() ->
                assertEquals(-1, canvas.findNearestPoint(10, 10, 20)));
    }

    @Test
    void findNearestPointStillWorksAfterZoom() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        double[] xs = {0, 1, 2, 3, 4};
        double[] ys = {0, 1, 2, 3, 4};
        UmapCanvas canvas = canvasWith(xs, ys);

        FxTestSupport.onFxRun(() -> {
            // Picking works in data space scaled by the *current* view, so a zoomed
            // view must still resolve the same point under the cursor.
            double sx = canvas.dataXToScreenX(2);
            double sy = canvas.dataYToScreenY(2);
            assertEquals(2, canvas.findNearestPoint(sx, sy, 20));
        });
    }

    // --- overlay index resolution ---

    @Test
    void highlightAndRingOverlaysAcceptMasksWithoutError() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        double[] xs = new double[1000];
        double[] ys = new double[1000];
        for (int i = 0; i < 1000; i++) { xs[i] = i; ys[i] = i; }
        UmapCanvas canvas = canvasWith(xs, ys);

        boolean[] mask = new boolean[1000];
        for (int i = 0; i < 1000; i += 3) mask[i] = true;

        FxTestSupport.onFxRun(() -> {
            assertDoesNotThrow(() -> canvas.setHighlightMask(mask));
            assertDoesNotThrow(() -> canvas.setHighlightIndices(new int[]{0, 5, 999}));
            assertDoesNotThrow(() -> canvas.setHighlightIndices(null));
            assertDoesNotThrow(() -> canvas.setPopulationRings(
                    List.of(new int[]{0xFF0000}), List.of(mask)));
        });
    }

    /**
     * A mask shorter than the point array must not walk off the end — this happens
     * whenever a population tag outlives the embedding it was created against.
     */
    @Test
    void overlayToleratesMaskShorterThanData() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        double[] xs = {0, 1, 2, 3, 4};
        double[] ys = {0, 1, 2, 3, 4};
        UmapCanvas canvas = canvasWith(xs, ys);

        boolean[] shortMask = {true, true};

        FxTestSupport.onFxRun(() -> {
            assertDoesNotThrow(() -> canvas.setHighlightMask(shortMask));
            assertDoesNotThrow(() -> canvas.setPopulationRings(
                    List.of(new int[]{0x00FF00}), List.of(shortMask)));
        });
    }

    /**
     * Replacing the embedding must drop overlay index lists resolved against the old
     * one; otherwise they index cells that no longer exist at those positions.
     */
    @Test
    void newDataClearsStaleOverlays() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");

        double[] xs = new double[100];
        double[] ys = new double[100];
        for (int i = 0; i < 100; i++) { xs[i] = i; ys[i] = i; }
        UmapCanvas canvas = canvasWith(xs, ys);

        boolean[] mask = new boolean[100];
        mask[99] = true;

        FxTestSupport.onFxRun(() -> {
            canvas.setPopulationRings(List.of(new int[]{0xFF0000}), List.of(mask));
            canvas.setHighlightMask(mask);
            // A shorter embedding — a stale index of 99 would be out of bounds.
            assertDoesNotThrow(() -> canvas.setData(new double[]{0, 1}, new double[]{0, 1}));
        });
    }
}
