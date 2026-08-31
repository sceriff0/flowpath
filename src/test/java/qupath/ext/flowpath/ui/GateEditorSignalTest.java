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
    void zScoreHistogramAxisFollowsSelectedCompartment() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");          // z-score mode by default
        Fixture f = editorFor(gate);

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);

        assertEquals(Compartment.NUCLEAR, gate.getCompartment());
        // Engine key for this selection; the editor must use the same one. The gate keeps
        // its default statistic (Median) across the compartment switch, so on this expanded
        // fixture the axis must resolve to "CD3: Nucleus: Median", not the bare mean.
        String key = f.index().resolvedKey("CD3", Compartment.NUCLEAR, gate.getStatistic());
        assertTrue(f.stats().hasColumn(key),
                "editor must register the resolved column with MarkerStats before displaying it");

        double expLo = f.stats().toZScore(key, f.stats().getPercentileValue(key, gate.getClipPercentileLow()));
        double expHi = f.stats().toZScore(key, f.stats().getPercentileValue(key, gate.getClipPercentileHigh()));

        Slider slider = thresholdSlider(f.pane());
        assertEquals(expLo, slider.getMin(), 1e-6,
                "z-score axis must be built from the nuclear column, not the whole-cell column");
        assertEquals(expHi, slider.getMax(), 1e-6);
    }

    @Test
    void zScoreHistogramAxisFollowsSelectedStatistic() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        Fixture f = editorFor(gate);

        select(statisticCombo(f.pane(), 0), Statistic.SUM);

        assertEquals(Statistic.SUM, gate.getStatistic());
        String key = f.index().resolvedKey("CD3", Compartment.WHOLE_CELL, Statistic.SUM);
        double expLo = f.stats().toZScore(key, f.stats().getPercentileValue(key, 1.0));
        double expHi = f.stats().toZScore(key, f.stats().getPercentileValue(key, 99.0));

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
    void modeToggleConvertsThresholdAndResyncsSliderAndField() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.setThresholdIsZScore(false);
        gate.setThreshold(120.0);                     // raw whole-cell mean
        gate.setStatistic(Statistic.MEAN);            // 120 is a bare whole-cell-mean value
        Fixture f = editorFor(gate);

        FxTestSupport.onFxRun(() -> computedModeButton(f.pane()).setSelected(true));
        flushFx();

        double expected = f.stats().toZScore("CD3", 120.0);
        assertEquals(expected, gate.getThreshold(), 1e-9, "threshold must convert into z-score space");

        Slider slider = thresholdSlider(f.pane());
        assertEquals(gate.getThreshold(), slider.getValue(), 1e-6,
                "slider thumb must track the converted threshold, not clamp to the new range");
        assertEquals(gate.getThreshold(), Double.parseDouble(thresholdField(f.pane()).getText()), 1e-3,
                "threshold text field must show the converted value");
        assertTrue(slider.getValue() >= slider.getMin() && slider.getValue() <= slider.getMax(),
                "converted threshold must land inside the new axis so it stays draggable");
    }

    @Test
    void modeToggleUsesResolvedKeyForNonDefaultCompartment() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.setThresholdIsZScore(false);
        Fixture f = editorFor(gate);

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);
        select(statisticCombo(f.pane(), 0), Statistic.MEDIAN);

        String key = f.index().resolvedKey("CD3", Compartment.NUCLEAR, Statistic.MEDIAN);
        double raw = f.stats().getPercentileValue(key, 60.0);
        FxTestSupport.onFxRun(() -> gate.setThreshold(raw));

        FxTestSupport.onFxRun(() -> computedModeButton(f.pane()).setSelected(true));
        flushFx();

        assertEquals(f.stats().toZScore(key, raw), gate.getThreshold(), 1e-9,
                "conversion must use the nuclear-median stats, not the bare CD3 stats");
        // Bare-channel conversion would give a wildly different number; make sure we didn't get it.
        assertNotEquals(f.stats().toZScore("CD3", raw), gate.getThreshold(), 1e-6);
    }

    @Test
    void modeToggleRoundTripIsStable() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.setThresholdIsZScore(false);
        gate.setThreshold(120.0);
        gate.setStatistic(Statistic.MEAN);            // 120 is a bare whole-cell-mean value
        Fixture f = editorFor(gate);

        FxTestSupport.onFxRun(() -> computedModeButton(f.pane()).setSelected(true));
        flushFx();
        FxTestSupport.onFxRun(() -> modeButton(f.pane(), "Raw").setSelected(true));
        flushFx();

        assertEquals(120.0, gate.getThreshold(), 1e-6, "raw -> z -> raw must return the original threshold");
        assertEquals(120.0, thresholdSlider(f.pane()).getValue(), 1e-6);
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
        double loX = f.stats().getPercentileValue("CD3", 20.0);
        double hiX = f.stats().getPercentileValue("CD3", 80.0);
        FxTestSupport.onFxRun(() -> { rg.setMinX(loX); rg.setMaxX(hiX); rg.setMinY(0); rg.setMaxY(200); });

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);

        String key = f.index().resolvedKey("CD3", Compartment.NUCLEAR, Statistic.MEAN);
        assertEquals(f.stats().getPercentileValue(key, 20.0), rg.getMinX(), 1e-6,
                "a drawn region must follow its axis to the new column");
        assertEquals(f.stats().getPercentileValue(key, 80.0), rg.getMaxX(), 1e-6);
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
        double raw = f.stats().getPercentileValue("CD3", 70.0);
        FxTestSupport.onFxRun(() -> gate.setThreshold(raw));

        select(compartmentCombo(f.pane(), 0), Compartment.NUCLEAR);

        String key = f.index().resolvedKey("CD3", Compartment.NUCLEAR, Statistic.MEAN);
        assertEquals(f.stats().getPercentileValue(key, 70.0), gate.getThreshold(), 1e-6,
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

    /** The ordinary case: a column with spread offers the computed z-score. */
    @Test
    void aChannelWithSpreadOffersTheComputedZScore() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = new GateNode("CD3");
        gate.setStatistic(Statistic.MEDIAN);
        Fixture f = editorForZScore(gate);
        flushFx();

        RadioButton z = FxTestSupport.onFx(() -> computedModeButton(f.pane()));
        assertFalse(z.isDisable());
    }

    /**
     * The label and colour say who computed the number. MIRAGE now emits standardised
     * statistics of its own, so an unqualified "Z-score" no longer identifies whose.
     */
    @Test
    void theToggleSaysTheNumberIsComputedHere() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = new GateNode("CD3");
        gate.setStatistic(Statistic.MEDIAN);
        Fixture f = editorForZScore(gate);
        flushFx();

        RadioButton z = FxTestSupport.onFx(() -> computedModeButton(f.pane()));
        assertTrue(z.getText().toLowerCase(Locale.ROOT).contains("computed"),
                "the label must say the value is derived, not read: " + z.getText());
        assertTrue(z.getStyle().contains("#7fc4a8"),
                "and carry the computed-here accent: " + z.getStyle());
        assertNotNull(z.getTooltip());
        assertTrue(z.getTooltip().getText().contains("FlowPath"),
                "the tooltip must name who computes it: " + z.getTooltip().getText());
    }

    /**
     * <b>The display/model agreement this exists for.</b> A constant column cannot be
     * standardised — {@code updateHistogram} already declined to, silently. The button must
     * decline visibly, and the gate's own flag must come with it, or the engine keeps
     * z-scoring a column the editor is drawing raw.
     */
    @Test
    void aConstantChannelDisablesTheToggleAndClearsTheFlag() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = new GateNode("FLAT");
        gate.setStatistic(Statistic.MEDIAN);
        gate.setThresholdIsZScore(true);
        Fixture f = editorForZScore(gate);
        flushFx();

        RadioButton z = FxTestSupport.onFx(() -> computedModeButton(f.pane()));
        assertTrue(z.isDisable(), "a flat column has no spread to standardise against");
        assertFalse(gate.isThresholdIsZScore(),
                "the flag the engine reads must follow the button, not outlive it");
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

    /**
     * Switching the statistic to one MIRAGE already standardised withdraws the offer —
     * the case that made "update on compartment/statistic change" necessary rather than
     * merely tidy. Driven through the real combo box, as a user would.
     */
    @Test
    void choosingAStandardisedStatisticWithdrawsTheComputedZScore() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = new GateNode("CD3");
        gate.setStatistic(Statistic.MEDIAN);
        gate.setThresholdIsZScore(true);
        Fixture f = editorForZScore(gate);
        flushFx();

        assertFalse(FxTestSupport.onFx(() -> computedModeButton(f.pane())).isDisable(),
                "Median is not standardised, so the toggle starts available");

        select(statisticCombo(f.pane(), 0), Statistic.of("Median Z"));
        flushFx();

        RadioButton z = FxTestSupport.onFx(() -> computedModeButton(f.pane()));
        assertTrue(z.isDisable(), "z-scoring a z-score rescales the axis by the current filter");
        assertFalse(gate.isThresholdIsZScore());
        assertTrue(z.getTooltip().getText().contains("already standardised"),
                "and the tooltip must say why: " + z.getTooltip().getText());
    }
}
