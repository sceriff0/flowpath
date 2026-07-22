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
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Map<String, Double> m = new LinkedHashMap<>();
            double cell = i * 10.0;              // 0 .. 200
            double nuc = 1000.0 + i * 100.0;     // 1000 .. 3000
            m.put("CD3", cell);
            m.put("CD3: Cell: Mean", cell);
            m.put("CD3: Cell: Median", cell / 2.0);
            m.put("CD3: Cell: Sum", cell * 100.0);
            m.put("CD3: Nucleus: Mean", nuc);
            m.put("CD3: Nucleus: Median", nuc / 2.0);
            m.put("CD3: Nucleus: Sum", nuc * 100.0);
            m.put("CD8", cell);
            m.put("CD8: Cell: Mean", cell);
            m.put("CD8: Nucleus: Mean", nuc);
            cells.add(cell(m));
        }
        return CellIndex.build(cells, List.of("CD3", "CD8"));
    }

    private static PathObject cell(Map<String, Double> meas) {
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        meas.forEach((k, v) -> o.getMeasurements().put(k, v));
        o.getMeasurements().put("area", 100.0);
        return o;
    }

    private static boolean[] allTrue(int n) {
        boolean[] m = new boolean[n];
        Arrays.fill(m, true);
        return m;
    }

    private record Fixture(GateEditorPane pane, CellIndex index, MarkerStats stats) {}

    private static Fixture editorFor(GateNode gate) {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, allTrue(idx.size()));
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

    private static RadioButton modeButton(GateEditorPane pane, String text) {
        return findOne(pane, RadioButton.class, b -> text.equals(b.getText()));
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
        // Engine key for this selection; the editor must use the same one.
        String key = f.index().resolvedKey("CD3", Compartment.NUCLEAR, Statistic.MEAN);
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

    // ---- Raw <-> Z-score: adaptive, then freely adjustable ------------------

    @Test
    void modeToggleConvertsThresholdAndResyncsSliderAndField() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.setThresholdIsZScore(false);
        gate.setThreshold(120.0);                     // raw whole-cell mean
        Fixture f = editorFor(gate);

        FxTestSupport.onFxRun(() -> modeButton(f.pane(), "Z-score").setSelected(true));
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

        FxTestSupport.onFxRun(() -> modeButton(f.pane(), "Z-score").setSelected(true));
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
        Fixture f = editorFor(gate);

        FxTestSupport.onFxRun(() -> modeButton(f.pane(), "Z-score").setSelected(true));
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
}
