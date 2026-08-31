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

    /**
     * {@code clean(branch)} is the field that mirrors {@code Branch.getCount()} -- not
     * {@code total(branch)}, which counts every cell that landed in the branch including
     * ones excluded there. A gate whose own percentile clipping excludes nothing (as the
     * un-clipped {@link #tree()} fixture does) makes {@code total() == clean()} trivially,
     * so this test turns on clipping to give the two fields room to actually differ.
     */
    @Test
    void cleanTotalsMatchTheBranchCountsAndTotalCountsMore() {
        CellIndex index = population();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        // Clip bounds land at [2.8, 8.2] over values 1..10: cells 1,2,9,10 are outlier-
        // clipped (excluded, but still land in a real branch), cells 3-8 are clean.
        root.setExcludeOutliers(true);
        root.setClipPercentileLow(20.0);
        root.setClipPercentileHigh(80.0);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats, null, null, 0);
        BranchTally tally = result.getTally();
        Branch pos = tree.getRoots().get(0).getBranches().get(0);
        Branch neg = tree.getRoots().get(0).getBranches().get(1);

        assertEquals(pos.getCount(), tally.clean(pos),
                "clean(branch) agrees with Branch.getCount(), not total(branch)");
        assertEquals(neg.getCount(), tally.clean(neg));
        assertTrue(tally.total(pos) > tally.clean(pos),
                "clipped-but-real-branch cells inflate total() without inflating clean()");
        assertTrue(tally.total(neg) > tally.clean(neg));
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
     * Ruling: {@code cellsClean()} is judged by the quality-filter/ROI exclusion
     * ({@code baseExcluded}) only -- not by whether a gate could measure the cell. An
     * unmeasured cell never reaches {@code assignBranch}, so it is already absent from
     * every branch's {@code total()}/{@code clean()} without {@code cellsClean()} needing
     * to subtract it a second time; with no quality filter or ROI mask active here, it
     * still counts toward {@code cellsClean()}.
     */
    @Test
    void unmeasuredCellIsAbsentFromBranchCountsButStillCountsTowardCellsClean() {
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

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats, null, null, 0);
        BranchTally tally = result.getTally();
        Branch pos = tree.getRoots().get(0).getBranches().get(0);
        Branch neg = tree.getRoots().get(0).getBranches().get(1);

        assertEquals(5, tally.cellsTotal(), "every indexed cell");
        assertEquals(5, tally.cellsClean(),
                "no quality filter or ROI mask is active, so nothing is base-excluded");
        // The unmeasured cell landed in neither branch: only the other 4 cells are split.
        assertEquals(4, tally.total(pos) + tally.total(neg));
        assertEquals(pos.getCount() + neg.getCount(), tally.total(pos) + tally.total(neg));
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

    /**
     * The bound Task 2's percentages depend on: a cell can only land in a branch if it was
     * not base-excluded (quality filter / ROI) in the first place, so {@code clean(branch)}
     * can never exceed {@code cellsClean()} for any branch -- no percentage computed from
     * the two can exceed 100%.
     */
    @Test
    void cleanBranchCountNeverExceedsCellsCleanUnderAnActiveQualityFilter() {
        // Cells 0 and 1 are undersized and will be rejected by the area filter below.
        CellIndex index = Cells.of(10)
                .marker("CD45", i -> i + 1.0)
                .area(i -> i < 2 ? 10.0 : 100.0)
                .build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));

        QualityFilter qf = new QualityFilter();
        qf.setRange(QualityFilter.AREA, new QualityFilter.Range(50.0, Double.POSITIVE_INFINITY));

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        GateTree tree = new GateTree();
        tree.setQualityFilter(qf);
        tree.addRoot(root);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats, null, null, 0);
        BranchTally tally = result.getTally();

        assertTrue(tally.cellsClean() < tally.cellsTotal(),
                "the quality filter must actually have excluded something for this bound to be interesting");
        for (Branch b : tree.getRoots().get(0).getBranches()) {
            assertTrue(tally.clean(b) <= tally.cellsClean(),
                    "a branch's clean count can never exceed the slide-wide clean denominator");
        }
    }

    /**
     * Multi-root is first-class in this codebase (see
     * {@code GatingEngineTest#multiRootCountsDoNotDependOnRootOrder}): each root is walked
     * from a clean starting exclusion and its own outlier clipping is discarded before the
     * next root runs, so the tally must agree regardless of which root was added first --
     * including when one root cannot measure some cells at all.
     */
    @Test
    void multiRootTallyCountsAreOrderIndependentAndCleanTracksGetCount() {
        int[][] cleanByOrder = new int[2][4];
        int[][] totalByOrder = new int[2][4];

        for (int order = 0; order < 2; order++) {
            CellIndex index = Cells.of(10)
                    .marker("A", i -> i + 1.0)
                    // Cell 2 has no B measurement -- the B root cannot judge it.
                    .marker("B", i -> i == 2 ? Double.NaN : i + 1.0)
                    .area(100.0)
                    .build();
            MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));

            GateNode clipper = new GateNode("A", 5.5);
            clipper.setStatistic(Statistic.MEAN);
            clipper.setThresholdIsZScore(false);
            clipper.setExcludeOutliers(true);
            clipper.setClipPercentileLow(20.0);
            clipper.setClipPercentileHigh(80.0);

            GateNode plain = new GateNode("B", 5.5);
            plain.setStatistic(Statistic.MEAN);
            plain.setThresholdIsZScore(false);

            GateTree tree = new GateTree();
            tree.setQualityFilter(null);
            if (order == 0) {
                tree.addRoot(clipper);
                tree.addRoot(plain);
            } else {
                tree.addRoot(plain);
                tree.addRoot(clipper);
            }

            AssignmentResult result = GatingEngine.assignAll(tree, index, stats, null, null, 0);
            BranchTally tally = result.getTally();

            Branch clipPos = clipper.getBranches().get(0);
            Branch clipNeg = clipper.getBranches().get(1);
            Branch plainPos = plain.getBranches().get(0);
            Branch plainNeg = plain.getBranches().get(1);

            cleanByOrder[order] = new int[] {
                    tally.clean(clipPos), tally.clean(clipNeg),
                    tally.clean(plainPos), tally.clean(plainNeg)
            };
            totalByOrder[order] = new int[] {
                    tally.total(clipPos), tally.total(clipNeg),
                    tally.total(plainPos), tally.total(plainNeg)
            };

            assertEquals(clipPos.getCount(), tally.clean(clipPos));
            assertEquals(clipNeg.getCount(), tally.clean(clipNeg));
            assertEquals(plainPos.getCount(), tally.clean(plainPos));
            assertEquals(plainNeg.getCount(), tally.clean(plainNeg));

            // The B root could not judge cell 2, so its two branches account for only 9 cells.
            assertEquals(9, tally.total(plainPos) + tally.total(plainNeg));
        }

        assertArrayEquals(cleanByOrder[0], cleanByOrder[1],
                "clean() must not depend on which root ran first");
        assertArrayEquals(totalByOrder[0], totalByOrder[1],
                "total() must not depend on which root ran first");
    }

    /**
     * {@code regionOf} is positional against {@code CellIndex.getObjects()}, so a length
     * that does not match the index describes a different population -- see
     * {@code GatingEngine.combineMasks} for the same rule applied to masks.
     */
    @Test
    void regionOfLengthMismatchThrows() {
        CellIndex index = population();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        GateTree tree = tree();
        int[] regionOf = {0, 0, 0};

        assertThrows(IllegalArgumentException.class, () ->
                GatingEngine.assignAll(tree, index, stats, null, regionOf, 1));
    }
}
