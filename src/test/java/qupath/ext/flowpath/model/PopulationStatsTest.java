package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PopulationStatsTest {

    /** CD45 root over 10 cells (values 1..10) split at 5.5, CD8 child under the positives. */
    private static GateTree twoLevelTree() {
        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        GateNode child = new GateNode("CD8", 8.5);
        child.setStatistic(Statistic.MEAN);
        child.setThresholdIsZScore(false);
        root.getBranches().get(0).getChildren().add(child);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);
        return tree;
    }

    private static BranchTally tally(GateTree tree, int[] regionOf, int regionCount) {
        CellIndex index = Cells.columns(List.of("CD45", "CD8"), new double[][] {
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
        }).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        return GatingEngine.assignAll(tree, index, stats, null, regionOf, regionCount).getTally();
    }

    @Test
    void wholeSlideRowsCarryBothDenominators() {
        GateTree tree = twoLevelTree();
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, null, 0), List.of(), null, null);

        List<PopulationStats.Row> rows = s.rows(PopulationStats.Scope.WHOLE_SLIDE);
        PopulationStats.Row pos = rows.stream()
                .filter(r -> r.branchName().equals("CD45+")).findFirst().orElseThrow();

        assertEquals(5, pos.count(), "values 6..10 are above 5.5");
        assertEquals(50.0, pos.percentOfTotal(), 1e-9);
        assertEquals(50.0, pos.percentOfParent(), 1e-9, "a root branch's parent is everything");
        assertEquals(5, pos.cleanCount(), "no outliers and no unmeasured cells here");
    }

    /**
     * The number that matters for a gating figure. A child's percentOfParent is against the
     * branch it hangs from, not the slide: reporting "20% CD8+" when 2 of 5 CD45+ cells are
     * CD8+ is the classic way a gating table misleads.
     */
    @Test
    void childPercentagesAreAgainstTheirOwnParentBranch() {
        GateTree tree = twoLevelTree();
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, null, 0), List.of(), null, null);

        PopulationStats.Row cd8 = s.rows(PopulationStats.Scope.WHOLE_SLIDE).stream()
                .filter(r -> r.path().equals("CD45+/CD8+")).findFirst().orElseThrow();

        assertEquals(2, cd8.count(), "of the CD45+ cells (6..10), 9 and 10 are above 8.5");
        assertEquals(5, cd8.parentCount(), "its parent is the 5 CD45+ cells");
        assertEquals(40.0, cd8.percentOfParent(), 1e-9, "2 of 5");
        assertEquals(20.0, cd8.percentOfTotal(), 1e-9, "2 of 10");
        assertEquals(1, cd8.depth());
    }

    /** Spec 3: three scopes that nest, each emitted with its own rows. */
    @Test
    void allThreeScopesAreEmitted() {
        GateTree tree = twoLevelTree();
        int[] regionOf = {0, 0, 0, 0, -1, 1, 1, 1, 1, -1};
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, regionOf, 2), List.of("Core", "Margin"), null, null);

        assertFalse(s.rows(PopulationStats.Scope.WHOLE_SLIDE).isEmpty());
        assertFalse(s.rows(PopulationStats.Scope.ANNOTATION_ALL).isEmpty());
        assertFalse(s.rows(PopulationStats.Scope.ANNOTATION_K).isEmpty());

        List<String> regions = s.rows(PopulationStats.Scope.ANNOTATION_K).stream()
                .map(PopulationStats.Row::regionName).distinct().toList();
        assertEquals(List.of("Core", "Margin"), regions, "one set of rows per region");
    }

    /** The scopes nest: annotation_all counts the cells in some region, no more. */
    @Test
    void annotationAllIsTheUnionOfTheRegions() {
        GateTree tree = twoLevelTree();
        int[] regionOf = {0, 0, 0, 0, -1, 1, 1, 1, 1, -1};
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, regionOf, 2), List.of("Core", "Margin"), null, null);

        int allNeg = rowFor(s, PopulationStats.Scope.ANNOTATION_ALL, null, "CD45-").count();
        int coreNeg = rowFor(s, PopulationStats.Scope.ANNOTATION_K, "Core", "CD45-").count();
        int marginNeg = rowFor(s, PopulationStats.Scope.ANNOTATION_K, "Margin", "CD45-").count();

        assertEquals(coreNeg + marginNeg, allNeg, "the union is the sum of the regions");
        assertTrue(allNeg <= rowFor(s, PopulationStats.Scope.WHOLE_SLIDE, null, "CD45-").count(),
                "annotation_all is a subset of whole_slide");
    }

    /** Spec 4: any branch may be the denominator. */
    @Test
    void anyBranchCanBeTheDenominator() {
        GateTree tree = twoLevelTree();
        Branch cd45pos = tree.getRoots().get(0).getBranches().get(0);
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, null, 0), List.of(), null, cd45pos);

        PopulationStats.Row cd8 = s.rows(PopulationStats.Scope.WHOLE_SLIDE).stream()
                .filter(r -> r.path().equals("CD45+/CD8+")).findFirst().orElseThrow();

        assertEquals(5, cd8.denominatorCount(), "the chosen denominator is the CD45+ branch");
        assertEquals(40.0, cd8.percentOfDenominator(), 1e-9, "2 of the 5 CD45+ cells");
    }

    /** With no denominator chosen, percentOfDenominator is NaN rather than a silent zero. */
    @Test
    void noDenominatorChosenLeavesThatColumnNaN() {
        GateTree tree = twoLevelTree();
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, null, 0), List.of(), null, null);

        assertTrue(Double.isNaN(s.rows(PopulationStats.Scope.WHOLE_SLIDE).get(0)
                .percentOfDenominator()), "not chosen is not the same as zero");
    }

    /** A chosen denominator that legitimately holds zero cells reports a real zero, not NaN. */
    @Test
    void aChosenButEmptyDenominatorReportsZeroNotNaN() {
        GateTree tree = twoLevelTree();
        Branch empty = tree.getRoots().get(0).getBranches().get(0)
                .getChildren().get(0).getBranches().get(1);   // CD8-
        PopulationStats s = PopulationStats.of(
                tree, new BranchTally(0), List.of(), null, empty);
        assertEquals(0.0, s.rows().get(0).percentOfDenominator(), 1e-9,
                "chosen-and-empty is a real answer; not-chosen is NaN");
    }

    /** Spec 6: density from the region's real area. */
    @Test
    void densityUsesTheRegionArea() {
        GateTree tree = twoLevelTree();
        int[] regionOf = {0, 0, 0, 0, 0, 1, 1, 1, 1, 1};
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, regionOf, 2), List.of("Core", "Margin"),
                new double[] {2.0, 5.0}, null);

        PopulationStats.Row coreNeg = rowFor(s, PopulationStats.Scope.ANNOTATION_K, "Core", "CD45-");
        assertEquals(2.0, coreNeg.areaMm2(), 1e-9);
        assertEquals(5 / 2.0, coreNeg.densityPerMm2(), 1e-9, "5 cells over 2 mm2");
    }

    /** A report must never carry NaN from a division; an empty parent yields zero. */
    @Test
    void emptyDenominatorsYieldZeroNotNaN() {
        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        PopulationStats s = PopulationStats.of(tree, new BranchTally(0), List.of(), null, null);
        for (PopulationStats.Row row : s.rows()) {
            assertFalse(Double.isNaN(row.percentOfParent()), row.path());
            assertFalse(Double.isNaN(row.percentOfTotal()), row.path());
            assertEquals(0.0, row.percentOfParent(), 1e-9);
        }
    }

    /** A disabled gate classifies nothing, so it contributes no rows. */
    @Test
    void disabledGatesContributeNoRows() {
        GateTree tree = twoLevelTree();
        tree.getRoots().get(0).setEnabled(false);
        PopulationStats s = PopulationStats.of(
                tree, new BranchTally(0), List.of(), null, null);
        assertTrue(s.rows().isEmpty(),
                "a disabled gate is a hard stop for its subtree, matching GatingEngine.walkNode");
    }

    @Test
    void rowsAreInDepthFirstTreeOrder() {
        GateTree tree = twoLevelTree();
        List<String> paths = PopulationStats.of(tree, tally(tree, null, 0), List.of(), null, null)
                .rows(PopulationStats.Scope.WHOLE_SLIDE).stream()
                .map(PopulationStats.Row::path).toList();
        assertEquals(List.of("CD45+", "CD45+/CD8+", "CD45+/CD8-", "CD45-"), paths,
                "a reader follows the table down the tree, so the order is the tree's");
    }

    private static PopulationStats.Row rowFor(PopulationStats s, PopulationStats.Scope scope,
                                              String region, String branch) {
        return s.rows(scope).stream()
                .filter(r -> java.util.Objects.equals(r.regionName(), region))
                .filter(r -> r.branchName().equals(branch))
                .findFirst().orElseThrow();
    }
}
