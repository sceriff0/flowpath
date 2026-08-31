package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What must happen when a gate's <em>axis</em> is changed in the editor — its channel,
 * or the compartment and statistic it is read in — asked of all three builders, through
 * the real controls.
 *
 * <p>{@code GateEditorSignalTest} already drives the compartment and statistic
 * selectors. The channel pickers were the uncovered half: one axis for
 * {@code buildThresholdEditor}, two for {@code buildQuadrantEditor}, two for
 * {@code build2DEditor}, each hand-wired with its own handler body. Every one of those
 * handlers has to do the same three things, and the ways they differed are the ways
 * they broke:
 * <ol>
 *   <li>point the axis at the new channel — and only that axis;</li>
 *   <li>re-pin the axis' compartment and statistic to something the <em>new</em>
 *       channel is actually quantified with, so the axis resolves to a column that is
 *       in the file rather than to one inherited from the channel it replaced;</li>
 *   <li>move the branch labels the user has not claimed onto the new channel, and
 *       leave the ones they have.</li>
 * </ol>
 *
 * <p>The pins below are stated as data — the resolved {@link MeasuredColumn} the engine
 * would gate on, read back through {@link GateAxis} — rather than as a settings triple,
 * because "resolves to a key that is not in the file" reads as a perfectly ordinary
 * (Nuclear, Median) selection right up until every cell reads NaN.
 */
class GateEditorAxisChangeTest {

    private static final int N = 12;

    /**
     * A default (non-expanded) MIRAGE export with a legacy channel in it: CD3 and CD4
     * carry per-compartment {@code Median} keys, CD8 only the bare column. Retargeting an
     * axis from CD3 to CD8 therefore <em>must</em> change the signal — CD8 has no nuclear
     * median — which is what makes the re-pin observable.
     */
    private static Cells cells() {
        return Cells.of(N)
                .mirageMedianMarker("CD3", i -> 10.0 + i)
                .mirageMedianMarker("CD4", i -> 20.0 + i)
                // CD3's nuclear median, overridden to be deliberately NOT a scalar
                // multiple of the bare column. Cells.mirageMedianMarker builds the
                // compartments as exact multiples (0.9x / 1.5x / 0.5x), and z-scoring is
                // scale-invariant: on proportional columns, a plot reading the wrong one
                // is numerically identical to one reading the right one, so the z-score
                // path — which is the default, and the one users actually see — cannot be
                // tested at all. A quadratic ramp breaks the proportionality.
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, i -> 100.0 + i * i)
                .marker("CD8", i -> 100.0 + i)
                .area(100.0);
    }

    private record Fixture(GateEditorPane pane, CellIndex index, MarkerStats stats) {}

    private static Fixture editorFor(GateNode gate) {
        CellIndex index = cells().build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(index.size()));
        CompartmentCapability capability =
                CompartmentCapability.scan(Arrays.asList(index.getObjects()), 100);
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3", "CD4", "CD8"));
            pane.setCompartmentCapability(capability);
            pane.setCellIndex(index);
            pane.setMarkerStats(stats);
            pane.setGateNode(gate);
        });
        return new Fixture(pane, index, stats);
    }

    /** Let any {@code Platform.runLater} rebuild queued by the editor run. */
    private static void flushFx() {
        FxTestSupport.onFxRun(() -> { });
        FxTestSupport.onFxRun(() -> { });
    }

    private static <T extends Node> void collect(Parent root, Class<T> type, Predicate<T> keep, List<T> out) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) {
                T t = type.cast(child);
                if (keep.test(t)) out.add(t);
            }
            if (child instanceof Parent p) collect(p, type, keep, out);
        }
    }

    /** The {@code nth} channel picker in layout order — X first, then Y. */
    @SuppressWarnings("unchecked")
    private static ComboBox<String> channelCombo(GateEditorPane pane, int nth) {
        List<ComboBox> combos = new ArrayList<>();
        collect(pane, ComboBox.class,
                c -> !c.getItems().isEmpty() && c.getItems().get(0) instanceof String, combos);
        assertTrue(combos.size() > nth, "expected a channel picker at index " + nth
                + ", found " + combos.size());
        return (ComboBox<String>) combos.get(nth);
    }

    /** Pick a channel the way a user would, firing the combo's action handler. */
    private static void selectChannel(ComboBox<String> combo, String channel) {
        FxTestSupport.onFxRun(() -> {
            combo.setValue(channel);
            combo.fireEvent(new javafx.event.ActionEvent());
        });
        flushFx();
    }

    /** Select a value the way a user would, firing the combo's action handler. */
    private static <T> void select(ComboBox<T> combo, T value) {
        FxTestSupport.onFxRun(() -> {
            combo.setValue(value);
            combo.fireEvent(new javafx.event.ActionEvent());
        });
        flushFx();
    }

    private static ScatterPlotCanvas scatterIn(GateEditorPane pane) {
        List<ScatterPlotCanvas> found = new ArrayList<>();
        collect(pane, ScatterPlotCanvas.class, c -> true, found);
        assertFalse(found.isEmpty(), "the editor must have laid out a scatter plot");
        return found.get(0);
    }

    /**
     * The scatter's effective Y axis bounds, {@code [min, max]}, read back through its
     * public coordinate mapping.
     * <p>
     * The plot's padding is private, so rather than hardcode it the geometry is measured
     * off a second canvas of the same size whose range we set ourselves: at a range of
     * exactly 0..1, {@code dataYToScreenY} reports which pixel rows are the bottom and the
     * top of the plot area. Feeding those two rows back through the real scatter's
     * {@code screenYToDataY} yields its own bounds.
     */
    private static double[] yAxisBounds(ScatterPlotCanvas scatter) {
        return FxTestSupport.onFx(() -> {
            scatter.resize(400, 300);
            ScatterPlotCanvas ruler = new ScatterPlotCanvas();
            ruler.resize(400, 300);
            ruler.setAxisRange(0.0, 1.0, 0.0, 1.0);
            double bottomPx = ruler.dataYToScreenY(0.0);
            double topPx = ruler.dataYToScreenY(1.0);
            return new double[]{scatter.screenYToDataY(bottomPx), scatter.screenYToDataY(topPx)};
        });
    }

    /** The clip-percentile bounds of {@code column}, z-scored, as applyClipAxisRange builds them. */
    /**
     * The clip percentiles of a column, as measured.
     * <p>
     * These used to be z-scored, because the editor rendered gates in a standardised space
     * FlowPath derived. It no longer offers one -- a gate compares against columns that
     * exist in the export -- so the axes are in the column's own units, and so are these.
     * The invariant is unchanged and still has teeth: the fixture's nuclear and whole-cell
     * columns differ by construction, so an axis built from the wrong one still fails.
     */
    private static double[] clipBounds(MeasuredColumn column, GateNode gate) {
        return new double[]{
                column.percentile(gate.getClipPercentileLow()),
                column.percentile(gate.getClipPercentileHigh())};
    }

    private static List<String> branchNames(GateNode gate) {
        return gate.getBranches().stream().map(Branch::getName).toList();
    }

    /**
     * The guarantee: this axis resolves to {@code expectedKey}, and every cell reads a
     * real number off it. A signal inherited from the previous channel resolves to a key
     * the export does not contain, and {@code CellIndex} answers that with NaN for every
     * cell — an empty plot, and a gate that sweeps the whole population into one branch.
     */
    private static void assertAxisReadsRealData(Fixture f, GateNode gate, int slot, String expectedKey) {
        MeasuredColumn column = GateAxis.of(gate, slot).columnIn(f.index(), f.stats());
        assertNotNull(column, "axis " + slot + " must resolve to a column");
        assertEquals(expectedKey, column.key(),
                "axis " + slot + " must be read in a compartment/statistic the new channel has");
        for (double v : column.values()) {
            assertFalse(Double.isNaN(v), "every cell must read a real value on axis " + slot);
        }
        assertTrue(column.hasSpread(), "a column of NaN or one repeated value has no spread");
    }

    // ---- one axis: buildThresholdEditor --------------------------------------

    @Test
    void thresholdChannelChangeRepinsTheAxisToTheNewChannelsColumn() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.setCompartment(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD8", gate.getChannel());
        assertAxisReadsRealData(f, gate, 0, "CD8");
    }

    @Test
    void thresholdChannelChangeMovesTheDefaultBranchLabels() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        Fixture f = editorFor(gate);
        assertEquals(List.of("CD3+", "CD3-"), branchNames(gate));

        selectChannel(channelCombo(f.pane(), 0), "CD4");

        assertEquals(List.of("CD4+", "CD4-"), branchNames(gate));
    }

    @Test
    void thresholdChannelChangeLeavesARenamedBranchAlone() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.getBranches().get(0).setName("T cells");
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 0), "CD4");

        assertEquals("T cells", branchNames(gate).get(0),
                "a label the user typed is their name for the population, not a caption");
        assertEquals("CD4-", branchNames(gate).get(1));
    }

    // ---- two axes: buildQuadrantEditor ---------------------------------------

    @Test
    void quadrantXChannelChangeRepinsOnlyTheXAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");
        assertAxisReadsRealData(f, gate, 1, "CD4: Nucleus: Median");

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD8", gate.getChannelX());
        assertEquals("CD4", gate.getChannelY(), "the Y axis was not asked about");
        assertAxisReadsRealData(f, gate, 0, "CD8");
        assertAxisReadsRealData(f, gate, 1, "CD4: Nucleus: Median");
    }

    @Test
    void quadrantYChannelChangeRepinsOnlyTheYAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 1), "CD8");

        assertEquals("CD3", gate.getChannelX(), "the X axis was not asked about");
        assertEquals("CD8", gate.getChannelY());
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");
        assertAxisReadsRealData(f, gate, 1, "CD8");
    }

    @Test
    void quadrantChannelChangeMovesTheDefaultBranchLabelsOfThatAxisOnly() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        Fixture f = editorFor(gate);
        assertEquals(List.of("CD3+/CD4+", "CD3-/CD4+", "CD3+/CD4-", "CD3-/CD4-"), branchNames(gate));

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals(List.of("CD8+/CD4+", "CD8-/CD4+", "CD8+/CD4-", "CD8-/CD4-"), branchNames(gate));
    }

    @Test
    void quadrantChannelChangeLeavesARenamedBranchAlone() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.getBranches().get(0).setName("double positive");
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("double positive", branchNames(gate).get(0));
        assertEquals("CD8-/CD4+", branchNames(gate).get(1));
    }

    // ---- two axes: build2DEditor ---------------------------------------------

    @Test
    void regionXChannelChangeRepinsOnlyTheXAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");
        assertAxisReadsRealData(f, gate, 1, "CD4: Nucleus: Median");

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD8", gate.getChannelX());
        assertEquals("CD4", gate.getChannelY(), "the Y axis was not asked about");
        assertAxisReadsRealData(f, gate, 0, "CD8");
        assertAxisReadsRealData(f, gate, 1, "CD4: Nucleus: Median");
    }

    @Test
    void regionYChannelChangeRepinsOnlyTheYAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 1), "CD8");

        assertEquals("CD3", gate.getChannelX(), "the X axis was not asked about");
        assertEquals("CD8", gate.getChannelY());
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");
        assertAxisReadsRealData(f, gate, 1, "CD8");
    }

    /**
     * A channel that offers no compartment choice must stop offering one.
     * <p>
     * {@code GateAxis.retarget} re-pins the model, but the <em>selectors</em> are built
     * from the channel's capability, so they are only correct after the editor is rebuilt.
     * CD8 is a bare legacy column here: after the X axis moves onto it, the two-selector
     * layout has to become a one-selector layout, or the pane offers a nuclear median for
     * a channel that has none.
     */
    @Test
    void aChannelWithNoCompartmentsStopsOfferingASignalSelector() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        Fixture f = editorFor(gate);
        assertEquals(2, compartmentCombos(f.pane()).size(), "CD3 and CD4 are both quantified per compartment");

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals(1, compartmentCombos(f.pane()).size(),
                "the X axis now reads a bare legacy column, which offers no compartment");
    }

    /**
     * The points that get plotted must come off each axis' <em>own</em> resolved column.
     * <p>
     * Asked of the quadrant threshold sliders, whose range is built from exactly that
     * data. Deliberately in raw mode: a z-scored axis is scale-invariant, so a plot
     * reading the whole-cell mean where the gate is set to the nuclear median would look
     * identical — the fixture's compartment columns are proportional to each other, and an
     * assertion that cannot tell them apart is not an assertion. In raw units the nuclear
     * median is 1.5x the bare column and the range moves with it.
     *
     * <p>This is the bug of commit {@code 6b66868}, which had to be fixed in four places
     * because the read was written out four times.
     */
    @Test
    void theSliderRangeIsBuiltFromTheColumnTheAxisActuallyReads() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.setThresholdIsZScore(false);
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);

        double[] nuclearX = GateAxis.of(gate, 0).columnIn(f.index(), f.stats()).values();
        double expectedMin = Arrays.stream(nuclearX).min().orElseThrow();
        double expectedMax = Arrays.stream(nuclearX).max().orElseThrow();
        assertNotEquals(expectedMin, Arrays.stream(bareValues(f, "CD3")).min().orElseThrow(),
                "the fixture must make the two candidate columns distinguishable");

        List<Slider> sliders = new ArrayList<>();
        collect(f.pane(), Slider.class, sl -> true, sliders);
        assertTrue(sliders.size() >= 2, "a quadrant gate lays out a threshold slider per axis");

        assertEquals(expectedMin, sliders.get(0).getMin(), 1e-9,
                "the X slider spans the nuclear median column the X axis is set to");
        assertEquals(expectedMax, sliders.get(0).getMax(), 1e-9);
    }

    // The z-scored twin of the pin above is gone with the mode it tested. FlowPath no
    // longer derives a z-score of its own, so a gate reads its column as measured and the
    // raw pin covers the only path there is. Should a pre-standardised column ever be
    // gated on, it is a different column with its own range and the same pin still holds.

    // ---- the scatter's axes anchor per axis (commit d9c1de9) -----------------

    /**
     * Each scatter axis is anchored on the clip percentiles of <em>its own</em> resolved
     * column. Anchoring both on the X column is commit {@code d9c1de9}'s bug: the Y axis
     * then spans a range the Y data does not occupy, and every point is drawn at the wrong
     * height — over an overlay that is still in the right place.
     * <p>
     * The two axes are given different compartments here precisely so that reading the
     * wrong one is visible.
     */
    @Test
    void eachScatterAxisIsAnchoredOnItsOwnColumn() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.WHOLE_CELL);
        Fixture f = editorFor(gate);

        double[] expected = clipBounds(GateAxis.of(gate, 1).columnIn(f.index(), f.stats()), gate);
        double[] fromX = clipBounds(GateAxis.of(gate, 0).columnIn(f.index(), f.stats()), gate);
        assertNotEquals(fromX[0], expected[0], 1e-9,
                "the fixture must make the X and Y columns anchor differently");

        double[] bounds = yAxisBounds(scatterIn(f.pane()));

        assertEquals(expected[0], bounds[0], 1e-6,
                "the Y axis spans the Y column's clip percentiles, not the X column's");
        assertEquals(expected[1], bounds[1], 1e-6);
    }

    // ---- a signal change on a 2D gate rebuilds the editor (commit 99b6e6d) --

    /**
     * Changing the compartment on a 2D gate has to rebuild the editor, because a quadrant
     * builds its threshold sliders from the column's own data range. Commit {@code
     * 99b6e6d} fixed the 1D half of this by refreshing in place; the 2D half is a rebuild,
     * and without it the sliders keep spanning the column the gate no longer reads.
     */
    @Test
    void aCompartmentChangeOnATwoDimensionalGateRerangesItsSliders() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        Fixture f = editorFor(gate);
        assertEquals(Compartment.WHOLE_CELL, gate.getCompartmentX(), "a new gate opens whole-cell");

        select(compartmentCombos(f.pane()).get(0), Compartment.NUCLEAR);

        assertEquals(Compartment.NUCLEAR, gate.getCompartmentX());
        double[] nuclear = GateAxis.of(gate, 0).columnIn(f.index(), f.stats()).values();
        List<Slider> sliders = new ArrayList<>();
        collect(f.pane(), Slider.class, sl -> true, sliders);
        assertEquals(Arrays.stream(nuclear).min().orElseThrow(), sliders.get(0).getMin(), 1e-9,
                "the sliders must be rebuilt against the newly selected column");
        assertEquals(Arrays.stream(nuclear).max().orElseThrow(), sliders.get(0).getMax(), 1e-9);
    }

    // ---- a picker from a superseded build owns nothing -----------------------

    /**
     * A gate-type conversion queues its rebuild rather than running it, so for one pulse
     * the old pickers are still there, still holding a reference to the gate they were
     * built for. Firing one must not write to a gate the editor has moved off.
     */
    @Test
    void aPickerFromASupersededBuildDoesNotWriteToItsOldGate() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate shown = new QuadrantGate("CD3", "CD4");
        Fixture f = editorFor(shown);
        ComboBox<String> stale = channelCombo(f.pane(), 0);

        RectangleGate replacement = new RectangleGate("CD4", "CD8", -1, 1, -1, 1);
        FxTestSupport.onFxRun(() -> f.pane().setGateNode(replacement));
        flushFx();

        selectChannel(stale, "CD8");

        assertEquals("CD3", shown.getChannelX(),
                "the editor has moved on; this picker no longer speaks for that gate");
        assertEquals("CD4", replacement.getChannelX(), "and it must not write to the new one either");
    }

    // ---- an axis can be repointed while the other one is blank ---------------

    /**
     * The region editor's old handler read both combos and returned unless both held a
     * value, so a half-configured gate — one axis set, the other still blank — could not
     * be finished. Each axis is now its own decision.
     */
    @Test
    void anAxisCanBeRepointedWhileTheOtherIsStillUnset() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate();
        gate.setChannelX("CD3");
        gate.setCompartmentX(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);
        assertNull(gate.getChannelY(), "the Y axis is deliberately still blank");

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD8", gate.getChannelX());
        assertAxisReadsRealData(f, gate, 0, "CD8");
    }

    private static double[] bareValues(Fixture f, String channel) {
        return f.index().getResolvedColumn(channel, Compartment.WHOLE_CELL, Statistic.MEAN);
    }

    private static List<ComboBox> compartmentCombos(GateEditorPane pane) {
        List<ComboBox> combos = new ArrayList<>();
        collect(pane, ComboBox.class,
                c -> !c.getItems().isEmpty() && c.getItems().get(0) instanceof Compartment, combos);
        return combos;
    }

    /**
     * The region editor never renamed its branches at all, so a gate drawn on CD3/CD4 and
     * then repointed kept a label naming a channel it no longer read. The rename is the
     * same decision for all three gate types, so it is now made in the same place.
     */
    @Test
    void regionChannelChangeMovesTheDefaultBranchLabels() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);
        Fixture f = editorFor(gate);
        assertEquals(List.of("CD3/CD4 (in)", "CD3/CD4 (out)"), branchNames(gate));

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals(List.of("CD8/CD4 (in)", "CD8/CD4 (out)"), branchNames(gate),
                "a region gate's labels name the plane it is drawn on, so they follow it");
    }

    /**
     * The quadrant editor rewrote its labels by substring: {@code name.replace("CD3+",
     * "CD8+")}. That is not a test of whether the label is still the default — it is a
     * blind edit of whatever the user had typed, and it mangled any name that happened to
     * contain the old channel.
     */
    @Test
    void quadrantChannelChangeDoesNotRewriteInsideAUserTypedLabel() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.getBranches().get(0).setName("CD3+ blasts");
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD3+ blasts", branchNames(gate).get(0),
                "the user named this population; it is not a caption to be find-and-replaced");
    }

    /**
     * A statistic the new channel does not carry must not survive the change. CD3 is
     * quantified with {@code Median} only here, so an axis switched onto it from a
     * whole-cell {@code Mean} legacy channel has to land on {@code "CD3: Cell: Median"}.
     */
    @Test
    void regionChannelChangeOntoACompartmentChannelPicksAStatisticItCarries() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD8", "CD4", -1, 1, -1, 1);
        Fixture f = editorFor(gate);
        assertEquals(Statistic.MEAN, gate.getStatisticX(), "CD8 is a bare legacy column");

        selectChannel(channelCombo(f.pane(), 0), "CD3");

        assertEquals("CD3", gate.getChannelX());
        assertAxisReadsRealData(f, gate, 0, "CD3: Cell: Median");
    }
}
