package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.testing.FxTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end regression coverage for the "total intensity QC max doesn't start at
 * the top" bug, driving the real {@link QualityFilterPane} on the JavaFX thread.
 * The pure decision logic is covered toolkit-free in {@code SliderUtilsTest}; these
 * tests verify the full {@code updateRanges} wiring and skip when no display exists.
 */
class QualityFilterPaneTest {

    // ---- real pane integration (skips when no display) ----

    @Test
    void freshFilterStaysOffAfterLargeTotalIntensityRange() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QualityFilter filter = new QualityFilter();
        QualityFilterPane pane = FxTestSupport.onFx(() -> new QualityFilterPane(filter));

        // Data max total intensity (80000) far exceeds the initial 50000 ceiling.
        FxTestSupport.onFxRun(() -> pane.updateRanges(3000, 80000, 500));

        assertEquals(Double.MAX_VALUE, pane.getFilter().getMaxTotalIntensity(),
                "total-intensity max must remain 'off' after the range grows past 50000");
        assertEquals(0.0, pane.getFilter().getMinTotalIntensity(), 1e-9);
        // Area data (3000) is below the ceiling — that one was never broken; confirm it too.
        assertEquals(Double.MAX_VALUE, pane.getFilter().getMaxArea());
    }

    @Test
    void userSetTotalIntensityBoundSurvivesImageLoad() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QualityFilter filter = new QualityFilter();
        filter.setMaxTotalIntensity(20000);
        QualityFilterPane pane = FxTestSupport.onFx(() -> new QualityFilterPane(filter));
        FxTestSupport.onFxRun(() -> pane.setFilter(filter));      // sync slider to the bound

        FxTestSupport.onFxRun(() -> pane.updateRanges(3000, 80000, 500));

        assertEquals(20000.0, pane.getFilter().getMaxTotalIntensity(), 1e-6,
                "an explicit upper bound must not be reset to 'off' by a range change");
    }
}
