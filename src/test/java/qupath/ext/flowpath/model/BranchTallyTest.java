package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchTallyTest {

    /** 10 cells, CD45 = 1..10, threshold 5.5 -> 5 positive, 5 negative. */
    private static CellIndex population() {
        return Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
    }

    private static GateTree tree() {
        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);
        return tree;
    }

    @Test
    void tallyTotalsMatchTheBranchCounts() {
        CellIndex index = population();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        GateTree tree = tree();

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats, null, null, 0);
        BranchTally tally = result.getTally();
        Branch pos = tree.getRoots().get(0).getBranches().get(0);
        Branch neg = tree.getRoots().get(0).getBranches().get(1);

        assertEquals(5, tally.total(pos), "the tally agrees with Branch.getCount()");
        assertEquals(5, tally.total(neg));
        assertEquals(pos.getCount(), tally.total(pos));
        assertEquals(10, tally.cellsTotal());
    }

    /**
     * Cells 0-4 in region 0, cells 5-9 in region 1. CD45 splits at 5.5, so region 0 holds
     * five negatives and region 1 five positives -- a split no whole-slide number shows.
     */
    @Test
    void countsAreBrokenDownByRegion() {
        CellIndex index = population();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        GateTree tree = tree();

        int[] regionOf = {0, 0, 0, 0, 0, 1, 1, 1, 1, 1};
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats, null, regionOf, 2);
        BranchTally tally = result.getTally();
        Branch pos = tree.getRoots().get(0).getBranches().get(0);
        Branch neg = tree.getRoots().get(0).getBranches().get(1);

        assertEquals(2, tally.regionCount());
        assertEquals(0, tally.inRegion(pos, 0), "region 0 is entirely CD45-negative");
        assertEquals(5, tally.inRegion(neg, 0));
        assertEquals(5, tally.inRegion(pos, 1), "region 1 is entirely CD45-positive");
        assertEquals(0, tally.inRegion(neg, 1));

        assertEquals(5, tally.cellsInRegion(0));
        assertEquals(5, tally.cellsInRegion(1));
    }

    /** A cell in no region contributes to the totals but to no region's counts. */
    @Test
    void cellsInNoRegionCountTowardsTheSlideOnly() {
        CellIndex index = population();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        GateTree tree = tree();

        int[] regionOf = {0, 0, -1, -1, -1, -1, -1, -1, 1, 1};
        BranchTally tally = GatingEngine
                .assignAll(tree, index, stats, null, regionOf, 2).getTally();

        assertEquals(10, tally.cellsTotal(), "every cell counts on the slide");
        assertEquals(2, tally.cellsInRegion(0));
        assertEquals(2, tally.cellsInRegion(1));
    }

    /**
     * Spec 4: raw and clean side by side. The clean count drops cells the gate could not
     * judge or clipped, so the difference between the two IS the data-quality cost -- which
     * is why it must be visible rather than a choice buried in the exporter.
     */
    @Test
    void cleanCountsExcludeUnmeasuredAndClippedCells() {
        // Cell 2 has no CD45 measurement at all.
        CellIndex index = Cells.of(5)
                .marker("CD45", i -> i == 2 ? Double.NaN : (i + 1) * 10.0)
                .area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(5));

        GateNode root = new GateNode("CD45", 25.0);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        BranchTally tally = GatingEngine
                .assignAll(tree, index, stats, null, null, 0).getTally();

        assertEquals(5, tally.cellsTotal(), "every indexed cell");
        assertEquals(4, tally.cellsClean(), "the unmeasured cell is not clean");
    }

    @Test
    void anUnusedBranchTalliesZeroRatherThanThrowing() {
        CellIndex index = population();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        GateTree tree = tree();
        BranchTally tally = GatingEngine
                .assignAll(tree, index, stats, null, null, 0).getTally();

        assertEquals(0, tally.total(new Branch("never walked", 0)),
                "a branch the walk never reached reports zero, not an exception");
    }
}
