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
     * <p>
     * <b>{@code assertNotEquals(plain, clipped)} alone proves too little.</b> Lowering the axis
     * ceiling from 10 to 5 moves every gridline and every bar's own top, so that one assertion
     * passes even with {@code PlotCanvas#drawClipMarker} deleted from every canvas — it pins
     * "the axis changed", not "the clipped bar was marked", which is the user's own requirement
     * this feature exists for. {@link #theClippedBarCarriesTheAxisBreakGlyph} below asserts the
     * glyph itself; kept here anyway as a coarse smoke check that is cheap to keep passing.
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
     * The assertion {@link #aClippedPlotMarksTheBarItClipped} cannot make: that the specific
     * glyph {@code drawClipMarker} draws — a {@code theme.background()}-filled 4px band
     * overpainted with a {@code theme.axis()}-coloured zig-zag — is actually present in the
     * document, not merely that something moved.
     * <p>
     * <b>Falsified by hand, per this branch's own convention for a geometry claim</b> (see
     * {@code RotatedLabelRenderTest}'s class javadoc): with the {@code drawClipMarker} call in
     * {@code CompositionCanvas.draw} commented out, this test fails —
     * {@code hasClipBand} is false, because the bar is still drawn to the axis top by {@code
     * AxisScale#toFraction}'s own clamping (a value at or above {@code max} always maps to
     * fraction 1) but nothing overpaints it. Restoring the call makes it pass again. Both runs
     * are recorded in {@code task-6-report.md}'s fix-round section rather than only asserted
     * here, because a falsification that isn't written down is one a future edit can silently
     * stop being true of.
     * <p>
     * The signatures checked are unique to {@link PlotCanvas#drawClipMarker} in this document:
     * no other {@code <rect>} this canvas draws is exactly 4px tall (the background fill spans
     * the whole canvas, each bar's own height is data-derived and never a fixed 4, and this
     * canvas draws no legend), and no other element strokes a {@code <line>} at all — gridlines
     * are {@code <line>}s too, but in {@code theme.gridline()}, never {@code theme.axis()},
     * which only ever strokes the rectangular plot frame elsewhere.
     */
    @Test
    void theClippedBarCarriesTheAxisBreakGlyph() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setTheme(PlotTheme.DARK);
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        canvas.setScaleOptions(ScaleOptions.LINEAR.withClip(true).withPercentile(50));
        String svg = canvas.toSvg();

        String backgroundHex = hex(PlotTheme.DARK.background());
        String axisHex = hex(PlotTheme.DARK.axis());

        boolean hasClipBand = svg.lines().anyMatch(line ->
                line.contains("<rect") && line.contains("height=\"4\"")
                        && line.contains("fill=\"" + backgroundHex + "\""));
        assertTrue(hasClipBand,
                "the clipped bar's own top must be overpainted with a " + backgroundHex
                        + " band 4px tall:\n" + svg);

        boolean hasZigZag = svg.lines().anyMatch(line ->
                line.contains("<line") && line.contains("stroke=\"" + axisHex + "\""));
        assertTrue(hasZigZag,
                "the break band must be crossed by an axis-coloured zig-zag:\n" + svg);
    }

    /**
     * The other direction of the same claim: clipping <em>requested</em> but nothing actually
     * clipped — the tied-leaves case from {@link #aClippedPlotMarksTheBarItClipped}'s own
     * javadoc — must draw no glyph at all. {@code isClipped} is the one gate every canvas calls
     * before {@code drawClipMarker}; a marker drawn here would be exactly the false claim
     * {@code AxisScale#anyClipped}'s own javadoc calls out: "a break-marker drawn anyway would
     * be a plot telling the user about a clip that did not happen."
     */
    @Test
    void clippingEnabledButNothingClippedDrawsNoGlyph() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setTheme(PlotTheme.DARK);
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());
        canvas.setScaleOptions(ScaleOptions.LINEAR.withClip(true).withPercentile(50));
        assertFalse(canvas.anyClippedProperty().get(),
                "the tied 10/10 pair has no percentile candidate below its own maximum");

        String svg = canvas.toSvg();
        String backgroundHex = hex(PlotTheme.DARK.background());
        boolean hasClipBand = svg.lines().anyMatch(line ->
                line.contains("<rect") && line.contains("height=\"4\"")
                        && line.contains("fill=\"" + backgroundHex + "\""));
        assertFalse(hasClipBand,
                "clipping is on but nothing was clipped, so no marker may be drawn:\n" + svg);
    }

    private static String hex(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
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
