package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntToDoubleFunction;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import qupath.ext.flowpath.model.MeasuredColumn;

/**
 * Regression coverage for the "signal selection" half of the gate editor: the
 * compartment (Nuclear / Cytoplasmic / Whole-cell) and statistic (Mean / Median /
 * Sum) pickers, and the Raw &harr; Z-score mode toggle.
 *
 * <p>The invariant under test is that the editor and {@code GatingEngine} agree on
 * the <em>resolved</em> measurement key. The engine z-scores and percentile-clips
 * against {@code CellIndex.resolvedKey(channel, compartment, statistic)} (e.g.
 * {@code "CD3: Nucleus: Median"}); the editor must display and transform against
 * exactly that key, not the bare channel name. When they disagree the histogram
 * axis, the threshold line and the actual classification all diverge.
 *
 * <p>Drives the real {@link GateEditorPane} through its public scene graph, so no
 * test-only accessors are needed. Skips when there is no display.
 */
class GateEditorSignalTest {

    // ---- fixture ------------------------------------------------------------

    private static final int N = 21;

    /**
     * {@code N} cells carrying bare + per-compartment CD3/CD8 keys on deliberately
     * different scales, so a compartment or statistic switch must visibly move the
     * axis: whole-cell mean spans 0..200, nuclear mean 1000..3000, medians half of
     * their mean, sums 100x.
     */
    private static CellIndex index() {
        IntToDoubleFunction cell = i -> i * 10.0;             // 0 .. 200
        IntToDoubleFunction nuc = i -> 1000.0 + i * 100.0;    // 1000 .. 3000
        return Cells.of(N)
                .marker("CD3", cell)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, cell)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN, i -> cell.applyAsDouble(i) / 2.0)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.SUM, i -> cell.applyAsDouble(i) * 100.0)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEAN, nuc)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, i -> nuc.applyAsDouble(i) / 2.0)
                .marker("CD3", Compartment.NUCLEAR, Statistic.SUM, i -> nuc.applyAsDouble(i) * 100.0)
                .marker("CD8", cell)
                .marker("CD8", Compartment.WHOLE_CELL, Statistic.MEAN, cell)
                .marker("CD8", Compartment.NUCLEAR, Statistic.MEAN, nuc)
                .area(100.0)
                .build();
    }

    private record Fixture(GateEditorPane pane, CellIndex index, MarkerStats stats) {}

    private static Fixture editorFor(GateNode gate) {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, Cells.allTrue(idx.size()));
        CompartmentCapability cap = CompartmentCapability.scan(Arrays.asList(idx.getObjects()), 100);
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3", "CD8"));
            pane.setCompartmentCapability(cap);
            pane.setCellIndex(idx);
            pane.setMarkerStats(stats);
            pane.setGateNode(gate);
        });
        return new Fixture(pane, idx, stats);
    }

    /** Let any {@code Platform.runLater} rebuild queued by the editor run. */
    private static void flushFx() {
        FxTestSupport.onFxRun(() -> { });
        FxTestSupport.onFxRun(() -> { });
    }

    // ---- scene-graph lookup (no production test seams) -----------------------

    private static <T extends Node> List<T> findAll(Parent root, Class<T> type, Predicate<T> keep) {
        List<T> out = new ArrayList<>();
        collect(root, type, keep, out);
        return out;
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

    private static <T extends Node> T findOne(Parent root, Class<T> type, Predicate<T> keep) {
        List<T> all = findAll(root, type, keep);
        assertFalse(all.isEmpty(), "no matching " + type.getSimpleName() + " in the editor");
        return all.get(0);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Compartment> compartmentCombo(GateEditorPane pane, int nth) {
        List<ComboBox> combos = findAll(pane, ComboBox.class,
                c -> !c.getItems().isEmpty() && c.getItems().get(0) instanceof Compartment);
        assertTrue(combos.size() > nth, "expected a compartment selector at index " + nth);
        return (ComboBox<Compartment>) combos.get(nth);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Statistic> statisticCombo(GateEditorPane pane, int nth) {
        List<ComboBox> combos = findAll(pane, ComboBox.class,
                c -> !c.getItems().isEmpty() && c.getItems().get(0) instanceof Statistic);
        assertTrue(combos.size() > nth, "expected a statistic selector at index " + nth);
        return (ComboBox<Statistic>) combos.get(nth);
    }

    private static Slider thresholdSlider(GateEditorPane pane) {
        return findOne(pane, Slider.class, s -> true);
    }

    private static TextField thresholdField(GateEditorPane pane) {
        return findOne(pane, TextField.class, f -> f.getText().matches("-?\\d+\\.\\d{4}"));
    }

    /**
     * The Raw / Z-score mode button, matched on the leading word.
     * <p>
     * Not an exact-text match: the z-score button's label carries a qualifier naming who
     * computed the number ("Z-score (computed)"), because MIRAGE now emits standardised
     * statistics of its own. What these tests mean by {@code modeButton(pane, "Z-score")}
     * is the mode, not the wording, and "Raw" / "Z-score" still pick out one button each.
     */
    private static RadioButton modeButton(GateEditorPane pane, String text) {
        return findOne(pane, RadioButton.class,
                b -> b.getText() != null && b.getText().startsWith(text));
    }

    /**
     * The button for <em>FlowPath's own</em> standardisation, specifically.
     * <p>
     * {@code modeButton(pane, "Z-score")} is no longer precise enough: since the selector
     * became a projection of what the export carries, a file holding a {@code " Z"} column
     * also offers "Z-score (MIRAGE)", and a prefix match picks whichever comes first. These
     * tests are about the value FlowPath derives, so they must say so — the ambiguity is
     * the point of the feature, not an accident of naming.
     */
    private static RadioButton computedModeButton(GateEditorPane pane) {
        return findOne(pane, RadioButton.class,
                b -> b.getText() != null && b.getText().contains("computed here"));
    }

    /** The button for a statistic MIRAGE already standardised, when the file carries one. */
    private static RadioButton mirageModeButton(GateEditorPane pane) {
        return findOne(pane, RadioButton.class,
                b -> b.getText() != null && b.getText().contains("(MIRAGE)"));
    }

    /** Select a value on a ComboBox the way a user would, firing its action handler. */
    private static <T> void select(ComboBox<T> combo, T value) {
        FxTestSupport.onFxRun(() -> {
            combo.setValue(value);
            combo.fireEvent(new javafx.event.ActionEvent());
        });
        flushFx();
    }

    // ---- the histogram / scatter follow the selected compartment -------------

    @Test
    void histogramAxisFollowsSelectedCompartment() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        Fixture f = editorFor(gate);

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);

        assertEquals(Compartment.NUCLEAR, gate.getCompartment());
        // Engine key for this selection; the editor must use the same one. The gate keeps
        // its default statistic (Median) across the compartment switch, so on this expanded
        // fixture the axis must resolve to "CD3: Nucleus: Median", not the bare mean.
        String key = f.index().resolvedKey("CD3", Compartment.NUCLEAR, gate.getStatistic());
        assertTrue(f.stats().hasColumn(key),
                "editor must register the resolved column with MarkerStats before displaying it");

        // Read through the column handle, which is the only public route to a column's
        // statistics; the hasColumn assertion above still proves the *editor* registered
        // it, because that ran before this line resolves anything.
        MeasuredColumn col = f.index().column("CD3", Compartment.NUCLEAR, gate.getStatistic(), f.stats());
        double expLo = col.percentile(gate.getClipPercentileLow());
        double expHi = col.percentile(gate.getClipPercentileHigh());

        Slider slider = thresholdSlider(f.pane());
        assertEquals(expLo, slider.getMin(), 1e-6,
                "the axis must be built from the nuclear column, not the whole-cell column");
        assertEquals(expHi, slider.getMax(), 1e-6);
    }

    @Test
    void histogramAxisFollowsSelectedStatistic() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        Fixture f = editorFor(gate);

        select(statisticCombo(f.pane(), 0), Statistic.SUM);

        assertEquals(Statistic.SUM, gate.getStatistic());
        MeasuredColumn col = f.index().column("CD3", Compartment.WHOLE_CELL, Statistic.SUM, f.stats());
        double expLo = col.percentile(1.0);
        double expHi = col.percentile(99.0);

        Slider slider = thresholdSlider(f.pane());
        assertEquals(expLo, slider.getMin(), 1e-6, "axis must follow the Sum column");
        assertEquals(expHi, slider.getMax(), 1e-6);
    }

    /**
     * A freshly created gate defaults to Median. On expanded Mirage data (which always
     * carries {@code "<marker>: Cell: Median"}) the editor must keep the model on Median
     * and resolve the axis to that structured column, and the statistic selector must
     * expose all three expanded statistics (Mean / Median / Sum) for CD3.
     */
    @Test
    void freshDefaultGateResolvesToCellMedianOnExpandedData() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        assertEquals(Statistic.MEDIAN, gate.getStatistic(), "new gates default to Median");

        Fixture f = editorFor(gate);

        // Median is available here, so the editor leaves the model on Median.
        assertEquals(Compartment.WHOLE_CELL, gate.getCompartment());
        assertEquals(Statistic.MEDIAN, gate.getStatistic());
        String key = f.index().resolvedKey("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);
        assertEquals("CD3: Cell: Median", key, "whole-cell median resolves to the structured key");
        assertTrue(f.stats().hasColumn(key),
                "editor must register the Cell Median column for a default gate on expanded data");

        // The statistic selector exposes the full expanded set and defaults to Median.
        ComboBox<Statistic> stat = statisticCombo(f.pane(), 0);
        assertTrue(stat.getItems().containsAll(List.of(Statistic.MEAN, Statistic.MEDIAN, Statistic.SUM)),
                "expanded data exposes Mean / Median / Sum, was: " + stat.getItems());
        assertEquals(Statistic.MEDIAN, stat.getValue(), "selector defaults to Median");
    }

    // ---- Raw <-> Z-score: adaptive, then freely adjustable ------------------


    @Test
    void aColumnChangeRemapsTheThresholdAgainstTheNewColumnsOwnStatistics() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        Fixture f = editorFor(gate);

        // Sit the threshold at a known percentile of the bare whole-cell column.
        double before = f.index()
                .column("CD3", Compartment.WHOLE_CELL, gate.getStatistic(), f.stats())
                .percentile(60.0);
        FxTestSupport.onFxRun(() -> gate.setThreshold(before));

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);
        select(statisticCombo(f.pane(), 0), Statistic.MEDIAN);

        // A bare number does not carry across columns -- a nuclear median is nothing like a
        // whole-cell mean -- so the gate must land on the same *percentile* of the column it
        // now reads, computed from that column's own statistics.
        MeasuredColumn to = f.index().column("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, f.stats());
        assertEquals(to.percentile(60.0), gate.getThreshold(), 1e-6,
                "the re-map must use the nuclear-median stats, not the bare CD3 stats");
        assertNotEquals(before, gate.getThreshold(), 1e-6,
                "the fixture must make the two columns distinguishable");
    }


    // ---- 2D region gates expose per-axis signal selectors -------------------

    @Test
    void regionGateEditorOffersACompartmentSelectorPerAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate rg = new RectangleGate("CD3", "CD8", 0, 100, 0, 100);
        rg.setThresholdIsZScore(false);
        Fixture f = editorFor(rg);

        // One per axis, in X-then-Y order.
        assertEquals(2, findAll(f.pane(), ComboBox.class,
                c -> !c.getItems().isEmpty() && c.getItems().get(0) instanceof Compartment).size(),
                "a 2D region gate needs its own compartment selector on each axis");

        select(compartmentCombo(f.pane(), 1), Compartment.NUCLEAR);

        assertEquals(Compartment.WHOLE_CELL, rg.getCompartmentX(), "X axis must be untouched");
        assertEquals(Compartment.NUCLEAR, rg.getCompartmentY());
        // Engine and editor must agree on how the axes resolve.
        assertEquals(List.of(Compartment.WHOLE_CELL, Compartment.NUCLEAR), rg.getCompartments());
        assertEquals(Compartment.NUCLEAR, rg.compartmentAt(1));
    }

    @Test
    void rawModeRegionShapeIsRemappedOnCompartmentChange() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate rg = new RectangleGate("CD3", "CD8", 0, 0, 0, 0);
        rg.setThresholdIsZScore(false);
        rg.setStatisticX(Statistic.MEAN);             // percentiles below are bare-column values
        rg.setStatisticY(Statistic.MEAN);
        Fixture f = editorFor(rg);
        MeasuredColumn bare = f.index().column("CD3", null, null, f.stats());
        double loX = bare.percentile(20.0);
        double hiX = bare.percentile(80.0);
        FxTestSupport.onFxRun(() -> { rg.setMinX(loX); rg.setMaxX(hiX); rg.setMinY(0); rg.setMaxY(200); });

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);

        MeasuredColumn nuclear = f.index().column("CD3", Compartment.NUCLEAR, Statistic.MEAN, f.stats());
        assertEquals(nuclear.percentile(20.0), rg.getMinX(), 1e-6,
                "a drawn region must follow its axis to the new column");
        assertEquals(nuclear.percentile(80.0), rg.getMaxX(), 1e-6);
    }

    @Test
    void clearedRegionShapeIsNotResurrectedByACompartmentChange() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate rg = new RectangleGate("CD3", "CD8", 0, 0, 0, 0);
        rg.setThresholdIsZScore(false);
        Fixture f = editorFor(rg);

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);

        assertEquals(0.0, rg.getMinX(), 1e-9, "a degenerate placeholder must stay degenerate");
        assertEquals(0.0, rg.getMaxX(), 1e-9);
    }

    // ---- the threshold box round-trips on a comma-decimal locale ------------

    /**
     * The threshold field is written with {@code String.format} and read back with
     * {@link Double#parseDouble}, which only accepts {@code '.'}. Under the JVM
     * default locale on a comma-decimal machine (e.g. {@code en_IT}) the field
     * rendered {@code "0,3303"} and then refused to parse it, so typing a threshold
     * silently did nothing.
     */
    @Test
    void thresholdFieldRoundTripsRegardlessOfDefaultLocale() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ITALY);
            GateNode gate = new GateNode("CD3");
            gate.setThresholdIsZScore(false);
            gate.setThreshold(0.3303);
            Fixture f = editorFor(gate);

            String shown = thresholdField(f.pane()).getText();
            assertEquals(0.3303, GateEditorPane.parseThreshold(shown), 1e-9,
                    "what the field renders must be what the field can parse back");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void thresholdFieldAcceptsCommaTypedByUser() {
        // Pure parsing, no toolkit interaction beyond class load.
        assertEquals(1.5, GateEditorPane.parseThreshold("1,5"), 1e-9);
        assertEquals(1.5, GateEditorPane.parseThreshold(" 1.5 "), 1e-9);
        assertEquals(-2.25, GateEditorPane.parseThreshold("-2,25"), 1e-9);
        assertThrows(NumberFormatException.class, () -> GateEditorPane.parseThreshold("abc"));
    }

    // ---- compartment change keeps the gate meaningful in raw mode -----------

    @Test
    void rawModeCompartmentChangeRemapsThresholdByPercentile() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.setThresholdIsZScore(false);
        gate.setStatistic(Statistic.MEAN);            // raw threshold is a bare-column value
        Fixture f = editorFor(gate);
        double raw = f.index().column("CD3", null, null, f.stats()).percentile(70.0);
        FxTestSupport.onFxRun(() -> gate.setThreshold(raw));

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);

        MeasuredColumn nuclear = f.index().column("CD3", Compartment.NUCLEAR, Statistic.MEAN, f.stats());
        assertEquals(nuclear.percentile(70.0), gate.getThreshold(), 1e-6,
                "a raw threshold must be remapped to the same percentile of the new column");
        Slider slider = thresholdSlider(f.pane());
        assertTrue(slider.getValue() >= slider.getMin() && slider.getValue() <= slider.getMax(),
                "remapped threshold must sit inside the new axis");
    }

    // ---- the computed z-score toggle ----------------------------------------

    /**
     * An index whose CD3 carries a plain Median, a MIRAGE-standardised {@code Median Z},
     * and a constant channel, so the three reasons the toggle behaves differently are all
     * reachable from one fixture.
     */
    private static Fixture editorForZScore(GateNode gate) {
        IntToDoubleFunction cell = i -> i * 10.0;
        CellIndex idx = Cells.of(N)
                .marker("CD3", cell)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN, cell)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.of("Median Z"), i -> i - 10.0)
                .marker("FLAT", i -> 7.0)
                .marker("FLAT", Compartment.WHOLE_CELL, Statistic.MEDIAN, i -> 7.0)
                .area(100.0)
                .build();
        MarkerStats stats = MarkerStats.compute(idx, Cells.allTrue(idx.size()));
        CompartmentCapability cap = CompartmentCapability.scan(Arrays.asList(idx.getObjects()), 100);
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3", "FLAT"));
            pane.setCompartmentCapability(cap);
            pane.setCellIndex(idx);
            pane.setMarkerStats(stats);
            pane.setGateNode(gate);
        });
        return new Fixture(pane, idx, stats);
    }




    /**
     * <b>The point of the redesign.</b> Choosing MIRAGE's own z-score must move the gate to
     * MIRAGE's <em>column</em> and leave FlowPath's standardisation off — the two are
     * different numbers, and the old two-way radio could not express the first at all.
     * Reaching that column meant using the Statistic dropdown instead, a control that did
     * not know a mode had been chosen and silently disabled this one.
     */
    @Test
    void choosingMiragesOwnZScoreSwitchesColumnRatherThanStandardisingHere() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = new GateNode("CD3");
        gate.setStatistic(Statistic.MEDIAN);
        Fixture f = editorForZScore(gate);
        flushFx();

        RadioButton mirage = FxTestSupport.onFx(() -> mirageModeButton(f.pane()));
        assertEquals("Z-score (MIRAGE)", mirage.getText(),
                "the label must name who computed the number");
        assertFalse(mirage.isDisable(), "the file carries CD3: Cell: Median Z");

        FxTestSupport.onFxRun(() -> mirage.setSelected(true));
        flushFx();

        assertEquals(Statistic.of("Median Z"), gate.getStatistic(),
                "the gate must now read MIRAGE's own standardised column");
        assertFalse(gate.isThresholdIsZScore(),
                "and FlowPath must not standardise an already-standardised column");
    }

    // ---- the Values row is what the file offers, and nothing else ----------------

    /**
     * <b>No standardised column in the file, no Values row.</b> A default MIRAGE export
     * carries compartments but nothing pre-standardised, so a gate has exactly one way to
     * be read. A single-button radio group would pose a question with one answer; the row
     * is hidden instead.
     */
    @Test
    void anExportWithNoStandardisedColumnShowsNoValuesRow() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = new GateNode("CD3");
        gate.setStatistic(Statistic.MEDIAN);
        Fixture f = editorFor(gate);
        flushFx();

        List<RadioButton> buttons = new ArrayList<>();
        FxTestSupport.onFxRun(() -> collect(f.pane(), RadioButton.class,
                b -> b.getText() != null && (b.getText().startsWith("Raw")
                        || b.getText().contains("Z-score")), buttons));
        assertTrue(buttons.isEmpty(),
                "nothing to choose between, so no buttons: " + buttons.stream()
                        .map(RadioButton::getText).toList());
        assertFalse(gate.isThresholdIsZScore(),
                "and the gate reads its column as measured");
    }

    /** FlowPath's own z-score is not offered anywhere, on any fixture. */
    @Test
    void theComputedZScoreIsNotOfferedAtAll() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = new GateNode("CD3");
        gate.setStatistic(Statistic.MEDIAN);
        Fixture f = editorForZScore(gate);   // this fixture *does* carry CD3: Cell: Median Z
        flushFx();

        List<RadioButton> buttons = new ArrayList<>();
        FxTestSupport.onFxRun(() -> collect(f.pane(), RadioButton.class, b -> true, buttons));
        assertTrue(buttons.stream().noneMatch(
                        b -> b.getText() != null && b.getText().contains("computed")),
                "no mode may name a number FlowPath derived: " + buttons.stream()
                        .map(RadioButton::getText).toList());
    }

    /**
     * <b>Migrating a gate saved under the retired mode.</b> Its threshold is in standard
     * deviations. Clearing the flag without converting would leave that number — often
     * around 1 — compared against a column whose values run to hundreds, so every cell
     * reads negative: no error, and a gate tree that still looks right. The threshold must
     * come back to the column's own units, landing on the same cells it did before.
     */
    @Test
    void aGateSavedInTheRetiredZScoreModeIsConvertedNotJustCleared() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = new GateNode("CD3");
        gate.setStatistic(Statistic.MEDIAN);
        gate.setThresholdIsZScore(true);

        // Build the index first so we can express the saved threshold as a real z-score.
        Fixture probe = editorForZScore(new GateNode("CD3"));
        MeasuredColumn col = probe.index()
                .column("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN, probe.stats());
        double rawAtP60 = col.percentile(60.0);
        double savedZ = col.toZScore(rawAtP60);
        gate.setThreshold(savedZ);

        Fixture f = editorForZScore(gate);
        flushFx();

        assertFalse(gate.isThresholdIsZScore(), "the retired flag must be cleared");
        assertEquals(rawAtP60, gate.getThreshold(), 1e-6,
                "and the threshold converted back to the column's own units, so the gate "
                        + "keeps the cells it had");
        assertNotEquals(savedZ, gate.getThreshold(), 1e-6,
                "the fixture must make the two spaces distinguishable");
    }

}
