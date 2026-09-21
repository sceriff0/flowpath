package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.LegacyZScoreMigration;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2: compartment/statistic selection drives gating, with percentile stats (and the
 * legacy z-score migration) computed on the resolved column.
 */
class CompartmentGatingTest {

    /** Two cells carrying bare + per-compartment CD3 keys. Cell A is nuclear-high,
     *  cell B is cytoplasm-high; whole-cell mean is identical (50) for both. */
    private static CellIndex twoCellIndex() {
        return Cells.of(2)
                .marker("CD3", 50.0)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, 50.0)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEAN, 100.0, 1.0)
                .marker("CD3", Compartment.CYTOPLASMIC, Statistic.MEAN, 1.0, 100.0)
                .area(100.0)
                .build();
    }

    private static AssignmentResult run(CellIndex index, GateNode gate) {
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(index.size()));
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);
        return GatingEngine.assignAll(tree, index, stats);
    }

    @Test
    void wholeCellDefaultUnchanged() {
        // Default whole-cell mean (== bare "CD3" = 50 for both) -> both positive at raw t=10.
        GateNode gate = new GateNode("CD3", 10.0);
        gate.setStatistic(Statistic.MEAN);   // whole-cell mean resolves to bare "CD3"
        String[] ph = run(twoCellIndex(), gate).getPhenotypes();
        assertEquals("CD3+", ph[0]);
        assertEquals("CD3+", ph[1]);
    }

    @Test
    void nuclearCompartmentChangesAssignment() {
        // Nuclear: A=100 (>=10) positive, B=1 (<10) negative — differs from whole-cell.
        GateNode gate = new GateNode("CD3", 10.0);
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.MEAN);   // data carries Nucleus Mean, not Median
        String[] ph = run(twoCellIndex(), gate).getPhenotypes();
        assertEquals("CD3+", ph[0]);
        assertEquals("CD3-", ph[1]);
    }

    @Test
    void cytoplasmicCompartmentIsInverse() {
        GateNode gate = new GateNode("CD3", 10.0);
        gate.setCompartment(Compartment.CYTOPLASMIC);
        gate.setStatistic(Statistic.MEAN);   // data carries Cytoplasm Mean, not Median
        String[] ph = run(twoCellIndex(), gate).getPhenotypes();
        assertEquals("CD3-", ph[0]);
        assertEquals("CD3+", ph[1]);
    }

    @Test
    void legacyZScoreMigrationUsesResolvedColumnStatsNotBare() {
        // A legacy gate saved at z = 0 on the NUCLEAR column [100, 1] (mean 50.5) must
        // convert through that column's statistics: A (100) positive, B (1) negative.
        // Converting through the bare whole-cell column [50, 50] would have no spread to
        // convert with at all.
        CellIndex index = twoCellIndex();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(index.size()));
        GateNode gate = new GateNode("CD3", 0.0);
        gate.setThresholdIsZScore(true);
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.MEAN);   // data carries Nucleus Mean, not Median
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertEquals(1, result.converted());
        assertEquals(50.5, gate.getThreshold(), 1e-9, "z = 0 is the nuclear column's mean");
        String[] ph = GatingEngine.assignAll(tree, index, stats).getPhenotypes();
        assertEquals("CD3+", ph[0]);
        assertEquals("CD3-", ph[1]);

        // Sanity: the whole-cell column has no spread, so a whole-cell legacy gate cannot be
        // converted, and is reported rather than silently moved.
        GateNode wc = new GateNode("CD3", 0.0);
        wc.setThresholdIsZScore(true);
        wc.setStatistic(Statistic.MEAN);   // whole-cell mean resolves to bare "CD3"
        GateTree wcTree = new GateTree();
        wcTree.addRoot(wc);
        assertEquals(List.of(wc), LegacyZScoreMigration.migrate(wcTree, index, stats).unconvertible());
    }

    @Test
    void quadrantPerAxisCompartments() {
        // X = CD3 nuclear, Y = CD3 cytoplasmic, raw threshold 10.
        // A: nuc=100 (X+), cyto=1 (Y-)  -> "CD3+/CD3-"
        // B: nuc=1   (X-), cyto=100 (Y+) -> "CD3-/CD3+"
        QuadrantGate q = new QuadrantGate("CD3", "CD3", 10.0, 10.0);
        q.setCompartmentX(Compartment.NUCLEAR);
        q.setStatisticX(Statistic.MEAN);
        q.setCompartmentY(Compartment.CYTOPLASMIC);
        q.setStatisticY(Statistic.MEAN);

        String[] ph = run(twoCellIndex(), q).getPhenotypes();
        assertEquals("CD3+/CD3-", ph[0]);
        assertEquals("CD3-/CD3+", ph[1]);
    }
}
