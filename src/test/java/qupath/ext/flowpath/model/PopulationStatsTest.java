package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.testing.AnalysisFixtures;
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

    /**
     * A chosen denominator that holds zero cells gives a percentage with no defined value.
     * <p>
     * Zero is right for percent-of-parent and percent-of-total — an empty parent implies an
     * empty part — but not here: the denominator branch is unrelated to the branch being
     * reported, so "10 cells, denominator 0" rendered as {@code 0.0%} is a plausible false
     * statement. {@code NaN} is already the value the field carries for "no denominator
     * chosen" and already renders blank in {@code AnalysisPane}; the two remain
     * distinguishable through {@link PopulationStats.Row#denominatorCount()}.
     */
    @Test
    void aChosenButEmptyDenominatorReportsNaNNotAFalseZero() {
        GateTree tree = twoLevelTree();
        // Push the CD8 gate above every value, so its positive branch really is empty
        // rather than merely un-tallied.
        GateNode cd8 = tree.getRoots().get(0).getBranches().get(0).getChildren().get(0);
        cd8.setThreshold(100.0);
        Branch empty = cd8.getBranches().get(0);   // CD8+

        PopulationStats s = PopulationStats.of(
                tree, tally(tree, null, 0), List.of(), null, empty);
        PopulationStats.Row cd45pos = s.rows(PopulationStats.Scope.WHOLE_SLIDE).get(0);
        assertEquals(5, cd45pos.count(), "a non-empty population...");
        assertEquals(0, cd45pos.denominatorCount(), "...against an empty denominator");
        assertTrue(Double.isNaN(cd45pos.percentOfDenominator()),
                "no defined percentage, so not a zero that reads as an answer");
        assertEquals(50.0, cd45pos.percentOfParent(), 1e-9,
                "percent-of-parent still answers normally -- only the denominator column is NaN");
    }

    /**
     * {@code regionNames} and the tally's region indices are two views of one region set.
     * {@code AnalysisSession.AnalysisInput} rejects a mismatch, but this method is public and
     * the batch/cohort callers reach it directly, so the guard must live here too — it
     * replaced a {@code "Region N"} fallback that quietly invented names for regions the
     * caller never described.
     */
    @Test
    void regionNamesThatDoNotDescribeTheTallyThrow() {
        GateTree tree = twoLevelTree();
        int[] regionOf = {0, 0, 0, 0, 0, 1, 1, 1, 1, 1};
        BranchTally tally = tally(tree, regionOf, 2);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PopulationStats.of(tree, tally, List.of("Core"), null, null));
        assertTrue(ex.getMessage().contains("1") && ex.getMessage().contains("2"),
                "the message must name both counts: " + ex.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> PopulationStats.of(tree, tally, List.of(), null, null),
                "no names at all for a two-region tally is the same mismatch");
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

    /** CD45 x CD8 quadrant gate over the same 10 cells, split at 5.5 on both axes. */
    private static GateTree quadrantTree() {
        QuadrantGate root = new QuadrantGate("CD45", "CD8", 5.5, 5.5);
        root.setThresholdIsZScore(false);
        root.setCompartmentX(Compartment.WHOLE_CELL);
        root.setStatisticX(Statistic.MEAN);
        root.setCompartmentY(Compartment.WHOLE_CELL);
        root.setStatisticY(Statistic.MEAN);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);
        return tree;
    }

    /**
     * Regression: {@code getChannels()} on a quadrant/region gate returns TWO channels, and
     * {@code gateChannel} must carry both — reporting only the X axis would mislabel every
     * quadrant row as if it were a single-marker gate.
     */
    @Test
    void quadrantGateRowsCarryBothChannelsJoined() {
        GateTree tree = quadrantTree();
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, null, 0), List.of(), null, null);

        List<PopulationStats.Row> rows = s.rows(PopulationStats.Scope.WHOLE_SLIDE);
        assertEquals(4, rows.size(), "a quadrant gate has four branches");
        for (PopulationStats.Row row : rows) {
            assertEquals("CD45 / CD8", row.gateChannel(), row.path());
        }
    }

    /** A 1D threshold gate's rows are unaffected by the multi-channel join. */
    @Test
    void thresholdGateRowsReportASingleChannel() {
        GateTree tree = twoLevelTree();
        PopulationStats s = PopulationStats.of(
                tree, tally(tree, null, 0), List.of(), null, null);

        List<PopulationStats.Row> rows = s.rows(PopulationStats.Scope.WHOLE_SLIDE);
        PopulationStats.Row cd45pos = rows.stream()
                .filter(r -> r.path().equals("CD45+")).findFirst().orElseThrow();
        assertEquals("CD45", cd45pos.gateChannel());

        PopulationStats.Row cd8pos = rows.stream()
                .filter(r -> r.path().equals("CD45+/CD8+")).findFirst().orElseThrow();
        assertEquals("CD8", cd8pos.gateChannel());
    }

    /**
     * Two independent root gates on the identical channel (neither renamed) emit
     * byte-identical {@code path} values -- {@code GateNode}'s default branch names are a
     * pure function of the channel. {@code rootIndex} is the one field that tells them
     * apart; a consumer that partitions on {@code path} or {@code gateChannel} instead
     * would merge the two roots into one.
     */
    @Test
    void twoRootsOnTheSameChannelAreDistinguishedByRootIndexNotPath() {
        GateNode rootA = new GateNode("CD45", 5.5);
        rootA.setStatistic(Statistic.MEAN);
        rootA.setThresholdIsZScore(false);

        GateNode rootB = new GateNode("CD45", 5.5);
        rootB.setStatistic(Statistic.MEAN);
        rootB.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(rootA);
        tree.addRoot(rootB);

        PopulationStats s = PopulationStats.of(tree, tally(tree, null, 0), List.of(), null, null);
        List<PopulationStats.Row> rows = s.rows(PopulationStats.Scope.WHOLE_SLIDE);

        assertEquals(4, rows.size(), "two threshold gates, two branches each");

        List<PopulationStats.Row> cd45PosRows = rows.stream()
                .filter(r -> r.path().equals("CD45+")).toList();
        assertEquals(2, cd45PosRows.size(),
                "both roots' positive branch share the identical, un-renamed path");
        assertEquals(cd45PosRows.get(0).path(), cd45PosRows.get(1).path(),
                "path cannot distinguish the two roots");

        List<Integer> rootIndexes = cd45PosRows.stream()
                .map(PopulationStats.Row::rootIndex).sorted().toList();
        assertEquals(List.of(0, 1), rootIndexes,
                "rootIndex, in tree order, is what actually distinguishes them");
    }

    /**
     * {@code collectFromRoots} skips a disabled root <b>before</b> handing out a
     * {@code rootIndex}, so the enabled roots are numbered {@code 0,1,...} contiguously
     * however many disabled roots sit among them. That is documented in the method and was
     * never tested, while three separate consumers key on the number being exactly this:
     * {@code PopulationRef}, {@code CompositionCanvas.availableRoots()} and
     * {@code AssignmentResult.getPerRootColors()} — the last of which is itself built over
     * the enabled roots only. Numbering by position in {@code getRoots()} instead would
     * leave every one of them pointing at a root the row does not belong to: a silent
     * misalignment, not a failure.
     */
    @Test
    void rootIndexStaysContiguousWhenADisabledRootSitsBetweenTwoEnabledOnes() {
        GateNode first = threshold("CD45", 5.5);
        GateNode middle = threshold("CD3", 5.5);
        GateNode last = threshold("CD19", 5.5);
        middle.setEnabled(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(first);
        tree.addRoot(middle);
        tree.addRoot(last);

        CellIndex index = Cells.columns(List.of("CD45", "CD3", "CD19"), new double[][] {
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
        }).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, null, 0).getTally();

        List<PopulationStats.Row> rows =
                PopulationStats.of(tree, tally, List.of(), null, null).rows();

        List<Integer> indexes = rows.stream()
                .map(PopulationStats.Row::rootIndex).distinct().sorted().toList();
        assertEquals(List.of(0, 1), indexes,
                "two enabled roots are numbered 0 and 1, not 0 and 2");

        List<String> channelsAt0 = rows.stream().filter(r -> r.rootIndex() == 0)
                .map(PopulationStats.Row::gateChannel).distinct().toList();
        assertEquals(List.of("CD45"), channelsAt0);

        List<String> channelsAt1 = rows.stream().filter(r -> r.rootIndex() == 1)
                .map(PopulationStats.Row::gateChannel).distinct().toList();
        assertEquals(List.of("CD19"), channelsAt1,
                "rootIndex 1 is the THIRD root -- the disabled one never took a number");

        assertTrue(rows.stream().noneMatch(r -> r.gateChannel().equals("CD3")),
                "the disabled root contributes no rows at all");
        assertTrue(rows.stream().noneMatch(r -> r.path().startsWith("CD3")),
                "and none of its paths either");
    }

    /**
     * {@code parentCount}/{@code cleanParentCount} chaining and {@code percentOfParent} at
     * depth &gt;= 2 carry every displayed percentage below the first child, and nothing else
     * in the suite reaches depth 2 at all: the recursion could pass a grandparent's count,
     * or transpose the raw and clean pair, and every existing test would stay green.
     * <p>
     * The middle gate clips outliers so that its {@code count} and {@code cleanCount}
     * genuinely differ — with the two equal, a transposition has no visible effect and the
     * assertions below pin nothing. {@code percentOfParent} is against the <b>raw</b> parent
     * count ({@code PopulationStats.percent(count, parentCount)}); against the clean one the
     * last assertion would read 100%, not 60%.
     */
    @Test
    void aThreeLevelTreeChainsParentCountsThroughEveryDepth() {
        GateNode root = threshold("CD45", 10.5);
        GateNode middle = threshold("CD3", 15.5);
        GateNode grandchild = threshold("CD8", 17.5);

        // The top decile of CD3 (values 1..20, so the 90th percentile is 18.1) is clipped:
        // cells 18 and 19 land in the middle gate's branch and are counted there, but are
        // not clean there.
        middle.setExcludeOutliers(true);
        middle.setClipPercentileLow(0.0);
        middle.setClipPercentileHigh(90.0);

        root.getBranches().get(0).getChildren().add(middle);
        middle.getBranches().get(0).getChildren().add(grandchild);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        int n = 20;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = i + 1;
        CellIndex index = Cells.columns(List.of("CD45", "CD3", "CD8"),
                new double[][] {values, values, values}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));
        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, null, 0).getTally();

        PopulationStats s = PopulationStats.of(tree, tally, List.of(), null, null);
        PopulationStats.Row depth1 = pathRow(s, "CD45+/CD3+");
        PopulationStats.Row depth2 = pathRow(s, "CD45+/CD3+/CD8+");

        assertEquals(1, depth1.depth());
        assertEquals(2, depth2.depth());

        assertEquals(5, depth1.count(), "CD45+ is cells 10-19; of those, 15-19 are CD3+");
        assertEquals(3, depth1.cleanCount(), "cells 18 and 19 were clipped as CD3 outliers");
        assertNotEquals(depth1.count(), depth1.cleanCount(),
                "the raw/clean pair must really differ, or transposing them below is invisible");

        assertEquals(5, depth2.parentCount(),
                "the grandchild's parent is the CD3+ branch, raw");
        assertEquals(depth1.count(), depth2.parentCount());
        assertEquals(3, depth2.cleanParentCount(),
                "and its clean parent is that same branch, clean");
        assertEquals(depth1.cleanCount(), depth2.cleanParentCount());

        assertEquals(3, depth2.count(), "of the CD3+ cells 15-19, cells 17-19 clear 17.5");
        assertEquals(1, depth2.cleanCount(), "cells 18 and 19 are still clipped here");
        assertEquals(60.0, depth2.percentOfParent(), 1e-9,
                "3 of the 5 RAW parent cells -- against the 3 clean ones this would be 100%");
        assertEquals(15.0, depth2.percentOfTotal(), 1e-9, "3 of 20");
    }

    /**
     * {@code RegionMask.nameOf} falls back to an annotation's classification when it has no
     * name of its own, so two annotations both classified {@code Tumor} are two distinct
     * regions sharing one name — the ordinary way a slide gets annotated, not a
     * pathological input. {@code regionIndex} is the field that tells them apart; keying a
     * per-region reduction on {@code regionName} instead collapses them, which is exactly
     * the defect {@code RegionComparisonCanvas} shipped (a bar per name, resolved with
     * {@code findFirst()}, so both bars showed the first region's count).
     */
    @Test
    void regionIndexDistinguishesTwoRegionsThatShareAName() {
        List<PopulationStats.Row> perRegion = AnalysisFixtures.twoRegionsSharingOneNameRows().stream()
                .filter(r -> r.scope() == PopulationStats.Scope.ANNOTATION_K)
                .filter(r -> r.path().equals("CD45-"))
                .toList();

        assertEquals(2, perRegion.size(), "two regions, so two rows for the one population");
        assertEquals(List.of("Tumor", "Tumor"),
                perRegion.stream().map(PopulationStats.Row::regionName).toList(),
                "the name cannot distinguish them -- both annotations are classified Tumor");

        assertEquals(List.of(0, 1),
                perRegion.stream().map(PopulationStats.Row::regionIndex).sorted().toList(),
                "regionIndex can");

        PopulationStats.Row region0 = perRegion.stream()
                .filter(r -> r.regionIndex() == 0).findFirst().orElseThrow();
        PopulationStats.Row region1 = perRegion.stream()
                .filter(r -> r.regionIndex() == 1).findFirst().orElseThrow();
        assertEquals(10, region0.count(), "region 0 holds cells 0-14, of which 0-9 are CD45-");
        assertEquals(0, region1.count(), "region 1 holds cells 15-19, every one of them CD45+");
        assertNotEquals(region0.count(), region1.count(),
                "distinct counts, so a name-keyed reduction reports region 0's number twice "
                        + "rather than coincidentally agreeing with region 1's");

        // What a name-keyed reduction actually does, spelled out: it can only ever see one
        // of the two rows, and which one is an accident of stream order.
        PopulationStats.Row byNameOnly = perRegion.stream()
                .filter(r -> "Tumor".equals(r.regionName())).findFirst().orElseThrow();
        assertEquals(region0.count(), byNameOnly.count(),
                "region 1's cells are unreachable through the name alone");
    }

    /** A threshold gate on {@code channel}, cut at a raw (non-z-scored) mean. */
    private static GateNode threshold(String channel, double at) {
        GateNode node = new GateNode(channel, at);
        node.setStatistic(Statistic.MEAN);
        node.setThresholdIsZScore(false);
        return node;
    }

    private static PopulationStats.Row pathRow(PopulationStats s, String path) {
        return s.rows(PopulationStats.Scope.WHOLE_SLIDE).stream()
                .filter(r -> r.path().equals(path)).findFirst().orElseThrow();
    }

    private static PopulationStats.Row rowFor(PopulationStats s, PopulationStats.Scope scope,
                                              String region, String branch) {
        return s.rows(scope).stream()
                .filter(r -> java.util.Objects.equals(r.regionName(), region))
                .filter(r -> r.branchName().equals(branch))
                .findFirst().orElseThrow();
    }
}
