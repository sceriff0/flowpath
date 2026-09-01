package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.AnalysisFixtures;
import qupath.ext.flowpath.testing.FxTestSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scale controls, wired end to end: a toggle on {@link PlotControls} reaches its own
 * canvas's {@link ScaleOptions} and no other canvas's, and a percentile clip that actually
 * changes the axis is visible both in what is drawn and in the "clipped" label.
 * <p>
 * {@code @BeforeAll startToolkit()} rather than {@code assumeToolkit()} — there is no such
 * method on {@link FxTestSupport}, per this task's own constraints; see that class for what it
 * does expose.
 */
class PlotScaleIntegrationTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void theTogglesAreIndependentPerPlot() {
        CompositionCanvas a = new CompositionCanvas();
        MarkerPositivityCanvas b = new MarkerPositivityCanvas();
        a.setScaleOptions(ScaleOptions.LINEAR.withLog(true));
        assertTrue(a.scaleOptions().log());
        assertFalse(b.scaleOptions().log(), "one plot's scale is not every plot's scale");
    }

    /**
     * {@code twoRootsSameChannelRows()} does not exercise this: {@code CompositionCanvas}
     * defaults to root 0, whose two leaves ({@code CD45+}, {@code CD45-}) both count 10 —
     * literally tied. {@link AxisScale#of} treats a percentile candidate that lands on the
     * data's own maximum as "no clip happened" (see its own javadoc), and with two equal bars
     * every candidate a nearest-rank percentile can pick from a two-element array *is* that
     * maximum, so no percentile at all can clip this particular row set. This is the same class
     * of brief defect flagged before starting ("a percentile edge case"): {@link
     * AnalysisFixtures#twoLevelRows()} is used here instead, whose one root has three leaves —
     * {@code CD45-} (10), {@code CD45+/CD3+} (5), {@code CD45+/CD3-} (5) — so a 50th-percentile
     * clip (nearest-rank index 1 of the sorted values {@code [5, 5, 10]}) lands on 5, strictly
     * below the true maximum of 10, and the {@code CD45-} bar is genuinely clipped.
     */
    @Test
    void aClippedPlotMarksTheBarItClipped() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setTheme(PlotTheme.DARK);
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        String plain = canvas.toSvg();
        canvas.setScaleOptions(ScaleOptions.LINEAR.withClip(true).withPercentile(50));
        String clipped = canvas.toSvg();
        assertNotEquals(plain, clipped, "clipping changes what is drawn");
    }

    /**
     * The same clip as above, but read through {@link PlotCanvas#anyClippedProperty()} rather
     * than by diffing SVG text — the fact {@link PlotControls}' "— top values clipped" label
     * binds to. Pinned separately from the SVG diff above because a document can differ for
     * reasons that have nothing to do with clipping (a moved gridline, a reformatted tick), so
     * neither assertion can stand in for the other.
     */
    @Test
    void anyClippedTracksWhatTheLastPaintActuallyClipped() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        assertFalse(canvas.anyClippedProperty().get(), "nothing is clipped before clipping is requested");

        canvas.setScaleOptions(ScaleOptions.LINEAR.withClip(true).withPercentile(50));
        assertTrue(canvas.anyClippedProperty().get(), "the 50th percentile clips the CD45- bar");

        // The tied-values case from aClippedPlotMarksTheBarItClipped's own javadoc: clipping
        // requested, nothing to clip, so the property must go back to false rather than latch.
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());
        assertFalse(canvas.anyClippedProperty().get(),
                "two equal bars have no percentile candidate below their shared maximum");
    }

    @Test
    void controlsDriveTheCanvasAndNothingElse() {
        CompositionCanvas canvas = new CompositionCanvas();
        PlotControls controls = FxTestSupport.onFx(() -> new PlotControls(canvas));

        FxTestSupport.onFxRun(() -> controls.logToggle().setSelected(true));
        assertTrue(canvas.scaleOptions().log(), "the toggle reaches the canvas");

        assertTrue(FxTestSupport.onFx(() -> controls.percentileSpinner().isDisabled()),
                "the percentile is meaningless until clipping is on");
        FxTestSupport.onFxRun(() -> controls.clipToggle().setSelected(true));
        assertFalse(FxTestSupport.onFx(() -> controls.percentileSpinner().isDisabled()));

        FxTestSupport.onFxRun(() -> controls.percentileSpinner().getValueFactory().setValue(90.0));
        assertEquals(90.0, canvas.scaleOptions().percentile(), 1e-9);
        assertTrue(canvas.scaleOptions().log(), "setting one option preserves the other");
    }
}
