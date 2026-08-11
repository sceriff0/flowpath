package qupath.ext.flowpath.umap;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the gating-to-UMAP handoff.
 * <p>
 * These assertions are the contract the two halves of the fused extension meet on. The
 * per-cell arrays are positional against the shared {@link CellIndex}, so a silent
 * misalignment here would not crash — it would draw the wrong colours on the right
 * points, which is far worse.
 */
class PhenotypeSnapshotTest {

    private static CellIndex indexOf(int n) {
        return Cells.of(n).at(i -> i, i -> i).marker("CD3", i -> i).build();
    }

    private static PhenotypeSnapshot snapshot(CellIndex index, String[] labels,
                                              int[] colors, boolean[] excluded) {
        return new PhenotypeSnapshot(index, MarkerStats.compute(index), List.of("CD3"),
                CompartmentCapability.empty(), labels, colors, excluded,
                List.of(), new qupath.ext.flowpath.model.MarkerSelection(), 0, "img");
    }

    // --- Construction guards ---

    @Test
    void rejectsPerCellArraysThatDoNotMatchTheIndex() {
        var index = indexOf(3);
        var ex = assertThrows(IllegalArgumentException.class, () ->
                snapshot(index, new String[]{"A", "B"}, new int[3], new boolean[3]));
        assertTrue(ex.getMessage().contains("3"),
                "The message should name the size the arrays had to match");
    }

    @Test
    void acceptsMatchingArrays() {
        var index = indexOf(2);
        var s = snapshot(index, new String[]{"A", "B"}, new int[]{1, 2}, new boolean[2]);
        assertEquals(2, s.cellCount());
        assertSame(index, s.index(), "The index must be shared, not copied");
    }

    // --- Population summary ---

    @Test
    void populationsAreOrderedLargestFirstWithUnclassifiedLast() {
        var index = indexOf(6);
        // "Rare" and Unclassified both have one cell. Ties break alphabetically, which
        // would put Unclassified first — the sort must override that, because a residual
        // bucket is not a finding and never belongs above one.
        String[] labels = {"Rare", "Big", "Big", "Big", PhenotypeSnapshot.UNCLASSIFIED, "Mid"};
        var s = snapshot(index, labels, new int[6], new boolean[6]);

        var pops = s.populations();
        assertEquals(List.of("Big", "Mid", "Rare", PhenotypeSnapshot.UNCLASSIFIED),
                pops.stream().map(PhenotypeSnapshot.Population::name).toList());
        assertEquals(3, pops.get(0).count());
    }

    @Test
    void excludedCellsAreLeftOutOfPopulationsAndCounts() {
        var index = indexOf(4);
        String[] labels = {"A", "A", "B", "B"};
        boolean[] excluded = {false, true, true, true};
        var s = snapshot(index, labels, new int[4], excluded);

        assertEquals(1, s.includedCount());
        var pops = s.populations();
        assertEquals(1, pops.size(), "Only the surviving cell's phenotype should appear");
        assertEquals("A", pops.get(0).name());
        assertEquals(1, pops.get(0).count());
    }

    @Test
    void populationTakesItsColourFromTheGateTree() {
        var index = indexOf(2);
        var s = snapshot(index, new String[]{"T cell", "T cell"},
                new int[]{0x2563EB, 0x2563EB}, new boolean[2]);
        assertEquals(0x2563EB, s.populations().get(0).color());
    }

    @Test
    void hasPhenotypesIsFalseWhenEverythingIsUnclassified() {
        var index = indexOf(3);
        String[] all = {PhenotypeSnapshot.UNCLASSIFIED, PhenotypeSnapshot.UNCLASSIFIED,
                PhenotypeSnapshot.UNCLASSIFIED};
        assertFalse(snapshot(index, all, new int[3], new boolean[3]).hasPhenotypes());
    }

    @Test
    void hasPhenotypesIgnoresLabelsOnExcludedCells() {
        var index = indexOf(2);
        // The excluded cell carries the label it would have had; that must not count as
        // "the user has phenotyped something".
        var s = snapshot(index, new String[]{"T cell", PhenotypeSnapshot.UNCLASSIFIED},
                new int[2], new boolean[]{true, false});
        assertFalse(s.hasPhenotypes());
    }

    // --- Gated panel extraction ---

    @Test
    void collectsGatedMarkersInTreeOrder() {
        var tree = new GateTree();
        var root = new GateNode("CD45", 1.0);
        var child = new GateNode("CD3", 0.5);
        root.getPositiveChildren().add(child);
        child.getPositiveChildren().add(new GateNode("CD4", 0.2));
        tree.addRoot(root);

        var panel = PhenotypeSnapshot.collectGatedPanel(tree);
        assertEquals(List.of("CD45", "CD3", "CD4"), panel.markers());
    }

    @Test
    void collectsBothAxesOfATwoMarkerGate() {
        var tree = new GateTree();
        tree.addRoot(new QuadrantGate("CD4", "CD8", 0.5, 0.5));

        var panel = PhenotypeSnapshot.collectGatedPanel(tree);
        assertEquals(List.of("CD4", "CD8"), panel.markers());
    }

    @Test
    void skipsDisabledGates() {
        var tree = new GateTree();
        var enabled = new GateNode("CD45", 1.0);
        var disabled = new GateNode("CD19", 1.0);
        disabled.setEnabled(false);
        tree.addRoot(enabled);
        tree.addRoot(disabled);

        // A gate the user switched off is not part of the phenotyping, so it must not
        // pull its marker into the UMAP's pre-selected feature set.
        assertEquals(List.of("CD45"), PhenotypeSnapshot.collectGatedPanel(tree).markers());
    }

    @Test
    void carriesEachMarkersCompartmentAndStatistic() {
        var tree = new GateTree();
        var node = new GateNode("CD3", 0.5);
        node.setCompartment(Compartment.NUCLEAR);
        node.setStatistic(Statistic.MEDIAN);
        tree.addRoot(node);

        var panel = PhenotypeSnapshot.collectGatedPanel(tree);
        var entry = panel.selection().entryFor("CD3");
        assertEquals(Compartment.NUCLEAR, entry.compartment());
        assertEquals(Statistic.MEDIAN, entry.statistic());
        assertTrue(entry.included(), "A gated marker must start ticked in the feature picker");
    }

    @Test
    void firstGateToUseAMarkerDefinesItsCompartment() {
        var tree = new GateTree();
        var root = new GateNode("CD3", 0.5);
        root.setCompartment(Compartment.NUCLEAR);
        var child = new GateNode("CD3", 0.9);
        child.setCompartment(Compartment.CYTOPLASMIC);
        root.getPositiveChildren().add(child);
        tree.addRoot(root);

        var panel = PhenotypeSnapshot.collectGatedPanel(tree);
        assertEquals(List.of("CD3"), panel.markers(), "The marker should appear once");
        assertEquals(Compartment.NUCLEAR, panel.selection().entryFor("CD3").compartment(),
                "The shallower gate defines the population structure, so it wins");
    }

    @Test
    void emptyTreeYieldsAnEmptyPanel() {
        var panel = PhenotypeSnapshot.collectGatedPanel(new GateTree());
        assertTrue(panel.markers().isEmpty());
        assertTrue(panel.selection().isEmpty());
    }

    @Test
    void nullTreeIsTolerated() {
        assertTrue(PhenotypeSnapshot.collectGatedPanel(null).markers().isEmpty());
    }
}
