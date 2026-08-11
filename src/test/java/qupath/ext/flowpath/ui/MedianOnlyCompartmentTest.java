package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntToDoubleFunction;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression coverage for MIRAGE's <em>default</em> compartment quantification —
 * {@code quantify.py --compartment_quantification} <b>without</b>
 * {@code --expanded_quantification}.
 *
 * <p>That mode emits exactly one statistic per compartment: {@code Median}. {@code Mean}
 * and {@code Sum} are expanded-only. The existing {@link GateEditorSignalTest} fixture
 * carries all three statistics, so every compartment test to date has run against
 * expanded data and the Median-only shape was never exercised.
 *
 * <p>The invariant: a compartment the capability advertises must resolve to a column
 * that actually holds data. Pinning a gate to a statistic the export does not contain
 * yields an all-NaN column — an empty histogram and a gate that classifies nothing,
 * over measurements that are present in the file.
 */
class MedianOnlyCompartmentTest {

    private static final int N = 21;

    /**
     * {@code N} cells shaped exactly like a default-quantification MIRAGE export:
     * the bare whole-cell mean column plus {@code Median} for every compartment, and
     * no {@code Mean}/{@code Sum} compartment keys at all.
     */
    private static CellIndex index() {
        IntToDoubleFunction cell = i -> i * 10.0;             // 0 .. 200
        IntToDoubleFunction nuc = i -> 1000.0 + i * 100.0;    // 1000 .. 3000
        return Cells.of(N)
                .marker("CD3", cell)                          // bare = whole-cell mean
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN, i -> cell.applyAsDouble(i) / 2.0)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, i -> nuc.applyAsDouble(i) / 2.0)
                .marker("CD3", Compartment.CYTOPLASMIC, Statistic.MEDIAN, i -> nuc.applyAsDouble(i) / 4.0)
                .area(100.0)
                .build();
    }

    private record Fixture(GateEditorPane pane, CellIndex index) {}

    private static Fixture editorFor(GateNode gate) {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, Cells.allTrue(idx.size()));
        CompartmentCapability cap = CompartmentCapability.scan(Arrays.asList(idx.getObjects()), 100);
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3"));
            pane.setCompartmentCapability(cap);
            pane.setCellIndex(idx);
            pane.setMarkerStats(stats);
            pane.setGateNode(gate);
        });
        FxTestSupport.onFxRun(() -> { });
        return new Fixture(pane, idx);
    }

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

    @SuppressWarnings("unchecked")
    private static ComboBox<Compartment> compartmentCombo(GateEditorPane pane) {
        List<ComboBox> combos = findAll(pane, ComboBox.class,
                c -> !c.getItems().isEmpty() && c.getItems().get(0) instanceof Compartment);
        assertFalse(combos.isEmpty(), "expected a compartment selector");
        return (ComboBox<Compartment>) combos.get(0);
    }

    private static boolean allNaN(double[] col) {
        for (double v : col) {
            if (!Double.isNaN(v)) return false;
        }
        return true;
    }

    // ---- the input shape ----------------------------------------------------

    @Test
    void defaultQuantificationAdvertisesMedianOnly() {
        CellIndex idx = index();
        CompartmentCapability cap =
                CompartmentCapability.scan(Arrays.asList(idx.getObjects()), 100);

        assertTrue(cap.isRich(), "per-compartment keys are present, so this is rich data");
        assertEquals(
                java.util.Set.of(Compartment.NUCLEAR, Compartment.CYTOPLASMIC, Compartment.WHOLE_CELL),
                cap.compartmentsFor("CD3"));
        assertEquals(java.util.Set.of(Statistic.MEDIAN), cap.statisticsFor("CD3"),
                "default quantification emits Median only; Mean/Sum are expanded-only");
    }

    // ---- the defect ---------------------------------------------------------

    @Test
    void freshGateKeepsMedianOnMedianOnlyData() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3", 10.0);
        assertEquals(Statistic.MEDIAN, gate.getStatistic(), "gate model defaults to Median");

        editorFor(gate);

        assertEquals(Statistic.MEDIAN, gate.getStatistic(),
                "the editor must not pin the gate to a statistic the export lacks");
    }

    @Test
    void nuclearSelectionResolvesToAColumnWithData() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3", 10.0);
        Fixture f = editorFor(gate);

        ComboBox<Compartment> combo = compartmentCombo(f.pane());
        FxTestSupport.onFxRun(() -> {
            combo.setValue(Compartment.NUCLEAR);
            combo.fireEvent(new javafx.event.ActionEvent());
        });
        FxTestSupport.onFxRun(() -> { });
        FxTestSupport.onFxRun(() -> { });

        assertEquals(Compartment.NUCLEAR, gate.getCompartment());
        String key = f.index().resolvedKey("CD3", gate.getCompartment(), gate.getStatistic());
        double[] col = f.index()
                .getResolvedColumn("CD3", gate.getCompartment(), gate.getStatistic());

        assertFalse(allNaN(col),
                "nuclear selection resolved to \"" + key + "\", which holds no data");
        assertEquals("CD3: Nucleus: Median", key);
    }

    // ---- statistic choice is driven by the export, not by a constant --------

    @Test
    void resolveStatisticNeverReturnsOneTheExportLacks() {
        CellIndex idx = index();
        CompartmentCapability cap =
                CompartmentCapability.scan(Arrays.asList(idx.getObjects()), 100);

        // Asking for Mean on a Median-only export must not hand back Mean.
        assertEquals(Statistic.MEDIAN, cap.resolveStatistic("CD3", Statistic.MEAN));
        assertEquals(Statistic.MEDIAN, cap.resolveStatistic("CD3", Statistic.SUM));
        assertEquals(Statistic.MEDIAN, cap.resolveStatistic("CD3", null));
    }

    @Test
    void resolveStatisticKeepsAnAvailableSelection() {
        CompartmentCapability cap = CompartmentCapability.fromKeys(List.of(
                "CD3: Cell: Mean", "CD3: Cell: Median", "CD3: Cell: Sum"));
        assertEquals(Statistic.SUM, cap.resolveStatistic("CD3", Statistic.SUM));
        assertEquals(Statistic.MEAN, cap.resolveStatistic("CD3", Statistic.MEAN));
    }

    @Test
    void resolveFallsBackToWholeCellMeanForALegacyChannel() {
        CompartmentCapability cap = CompartmentCapability.empty();
        assertEquals(Statistic.MEAN, cap.resolveStatistic("CD3", Statistic.MEDIAN),
                "a legacy channel's only column is the bare whole-cell mean");
        assertEquals(Compartment.WHOLE_CELL, cap.resolveCompartment("CD3", Compartment.NUCLEAR));
    }

    @Test
    void resolveCompartmentFallsBackToCellWithoutANuclearMask() {
        // Compartment quantification with no nuclei mask: Cell only.
        CompartmentCapability cap = CompartmentCapability.fromKeys(List.of("CD3: Cell: Median"));
        assertEquals(Compartment.WHOLE_CELL, cap.resolveCompartment("CD3", Compartment.NUCLEAR));
        assertEquals(Statistic.MEDIAN, cap.resolveStatistic("CD3", Statistic.MEAN));
    }

    // ---- a new gate is pinned before it ever renders (the badge flicker) ----

    /** Cells with bare marker keys only — a pre-compartment export. */
    private static CellIndex legacyIndex() {
        return Cells.of(N).marker("CD3", i -> i * 10.0).area(100.0).build();
    }

    @Test
    void newGateOnLegacyDataIsPinnedToWholeCellMeanUpFront() {
        CellIndex idx = legacyIndex();
        CompartmentCapability cap =
                CompartmentCapability.scan(Arrays.asList(idx.getObjects()), 100);
        assertFalse(cap.isRich(), "bare-marker export carries no compartment keys");

        GateNode gate = new GateNode("CD3", 10.0);
        assertEquals(Statistic.MEDIAN, gate.getStatistic(), "model default before resolution");

        GateEditorPane.applyAvailableSignal(gate, cap);

        assertEquals(Compartment.WHOLE_CELL, gate.getCompartment());
        assertEquals(Statistic.MEAN, gate.getStatistic(),
                "legacy data must be resolved at creation, not corrected once the editor opens");
    }

    @Test
    void newGateOnMedianOnlyDataStaysOnMedian() {
        CellIndex idx = index();
        CompartmentCapability cap =
                CompartmentCapability.scan(Arrays.asList(idx.getObjects()), 100);

        GateNode gate = new GateNode("CD3", 10.0);
        GateEditorPane.applyAvailableSignal(gate, cap);

        assertEquals(Compartment.WHOLE_CELL, gate.getCompartment());
        assertEquals(Statistic.MEDIAN, gate.getStatistic());
        assertEquals("CD3: Cell: Median",
                idx.resolvedKey("CD3", gate.getCompartment(), gate.getStatistic()));
    }

    // ---- end to end: the gate actually splits the population ----------------

    @Test
    void nuclearGateOnMedianOnlyDataSplitsThePopulation() {
        CellIndex idx = index();
        CompartmentCapability cap =
                CompartmentCapability.scan(Arrays.asList(idx.getObjects()), 100);

        GateNode gate = new GateNode("CD3", 0.0);
        gate.setChannel("CD3");
        gate.setCompartment(Compartment.NUCLEAR);
        GateEditorPane.applyAvailableSignal(gate, cap);
        gate.setThresholdIsZScore(false);
        // Nuclear medians run 500..1500; split them down the middle.
        gate.setThreshold(1000.0);

        qupath.ext.flowpath.model.GateTree tree = new qupath.ext.flowpath.model.GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);
        String[] phenotypes = qupath.ext.flowpath.engine.GatingEngine
                .assignAll(tree, idx, MarkerStats.compute(idx, Cells.allTrue(idx.size())))
                .getPhenotypes();

        long positive = Arrays.stream(phenotypes).filter("CD3+"::equals).count();
        long negative = Arrays.stream(phenotypes).filter("CD3-"::equals).count();

        assertEquals(Compartment.NUCLEAR, gate.getCompartment());
        assertEquals(Statistic.MEDIAN, gate.getStatistic());
        assertEquals(N, positive + negative, "every cell must be classified");
        assertTrue(positive > 0 && negative > 0,
                "a nuclear gate on default-quantification data must split the population, "
                        + "not sweep every cell into one branch on an all-NaN column "
                        + "(got " + positive + " positive / " + negative + " negative)");
    }

    // ---- gate-type conversion must not fabricate an absent axis -------------

    @Test
    void thresholdToQuadrantLeavesTheNewAxisAtTheModelDefault() {
        GateNode threshold = new GateNode("CD3", 10.0);
        threshold.setCompartment(Compartment.NUCLEAR);
        threshold.setStatistic(Statistic.MEDIAN);

        qupath.ext.flowpath.model.QuadrantGate quad =
                new qupath.ext.flowpath.model.QuadrantGate("CD3", "CD8");
        GateEditorPane.copySharedSettings(threshold, quad);

        assertEquals(Compartment.NUCLEAR, quad.getCompartmentX());
        assertEquals(Statistic.MEDIAN, quad.getStatisticX());
        assertEquals(Statistic.MEDIAN, quad.getStatisticY(),
                "the Y axis had no source to copy from, so it must keep the model default "
                        + "rather than the out-of-range Mean fallback");
    }
}
