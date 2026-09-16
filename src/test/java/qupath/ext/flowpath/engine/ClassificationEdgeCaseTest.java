package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.LegacyZScoreMigration;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.AnalysisFixtures;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Classification edge cases the rest of the engine suite does not pin: exact boundaries
 * reached through the engine (not only through the pure geometry), degenerate and
 * self-intersecting shapes, one-axis NaN on region gates, non-finite values, clip bounds hit
 * exactly, stale counts on disabled gates, deep nesting, two same-channel roots, and the
 * quality-filter + ROI + clipping + unmeasured count bookkeeping all at once.
 * <p>
 * Values are chosen to be exactly representable as {@code float}, because QuPath stores
 * measurements as floats and a boundary probe at {@code 0.3} would silently test
 * {@code 0.30000001} instead.
 */
class ClassificationEdgeCaseTest {

    // ---- helpers ----

    private static GateNode threshold(String channel, double t) {
        GateNode g = new GateNode(channel, t);
        g.setStatistic(Statistic.MEAN);
        return g;
    }

    private static <G extends Region2DGate> G raw2D(G g) {
        g.setStatisticX(Statistic.MEAN);
        g.setStatisticY(Statistic.MEAN);
        return g;
    }

    private static GateTree treeOf(GateNode... roots) {
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        for (GateNode r : roots) tree.addRoot(r);
        return tree;
    }

    private static int countOf(String[] phenotypes, String name) {
        int c = 0;
        for (String p : phenotypes) if (name.equals(p)) c++;
        return c;
    }

    // ---- exact thresholds, through the engine ----

    // Pins GateNode.isAtOrAbove (>=, not >) end to end: a cell whose stored value equals the
    // threshold lands positive and is counted there, in raw space.
    @Test
    void aCellExactlyOnARawThresholdIsPositiveAndCountedThere() {
        CellIndex index = Cells.of(5).marker("A", 1.0, 2.0, 3.0, 4.0, 5.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(5));
        GateNode gate = threshold("A", 3.0);

        AssignmentResult result = GatingEngine.assignAll(treeOf(gate), index, stats);

        assertEquals("A+", result.getPhenotypes()[2], "3.0 >= 3.0 is at-or-above");
        assertEquals(3, gate.getBranches().get(0).getCount(), "3, 4, 5");
        assertEquals(2, gate.getBranches().get(1).getCount(), "1, 2");
    }

    // The same >= rule for a legacy z-threshold of 0 once migrated: it converts to exactly
    // the column mean, and the cell sitting on the mean is still positive.
    @Test
    void aCellExactlyAtTheMeanIsPositiveAtAMigratedZeroZThreshold() {
        CellIndex index = Cells.of(3).marker("A", 1.0, 2.0, 3.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(3));
        GateNode gate = threshold("A", 0.0);
        gate.setThresholdIsZScore(true);
        GateTree tree = treeOf(gate);

        LegacyZScoreMigration.migrate(tree, index, stats);
        assertEquals(2.0, gate.getThreshold(), 0.0, "z = 0 is exactly the mean");
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        assertEquals("A-", result.getPhenotypes()[0]);
        assertEquals("A+", result.getPhenotypes()[1], "2.0 on the migrated cut is at-or-above");
        assertEquals("A+", result.getPhenotypes()[2]);
    }

    // Both quadrant axes use >= independently: cells on the X line, the Y line and the
    // crosshair itself each land on the positive side of whichever cut they sit on.
    @Test
    void quadrantCellsOnEitherCutOrTheCrosshairLandOnThePositiveSide() {
        double[] xs = {2.0, 2.0, 1.0, 2.0, 1.0};
        double[] ys = {2.0, 1.0, 2.0, 3.0, 1.0};
        CellIndex index = Cells.of(5).marker("X", xs).marker("Y", ys).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(5));
        QuadrantGate gate = new QuadrantGate("X", "Y", 2.0, 2.0);
        gate.setStatisticX(Statistic.MEAN);
        gate.setStatisticY(Statistic.MEAN);

        AssignmentResult result = GatingEngine.assignAll(treeOf(gate), index, stats);
        String[] p = result.getPhenotypes();

        assertEquals(gate.getBranchPP().getName(), p[0], "(2,2) on the crosshair is ++");
        assertEquals(gate.getBranchPN().getName(), p[1], "(2,1) on the X cut is +-");
        assertEquals(gate.getBranchNP().getName(), p[2], "(1,2) on the Y cut is -+");
        assertEquals(gate.getBranchPP().getName(), p[3]);
        assertEquals(gate.getBranchNN().getName(), p[4]);
        assertEquals(2, gate.getBranchPP().getCount());
    }

    // ---- region boundaries and degenerate shapes ----

    // All four edges and all four vertices of an axis-aligned square are Inside -- the same
    // rule RectangleGate already applies to the same square (see Region2DGate's javadoc).
    // A point one representable double outside any edge is Outside.
    @Test
    void polygonBoundaryIncludesEveryEdgeAndVertex() {
        PolygonGate gate = new PolygonGate("X", "Y");
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{2, 0},
                new double[]{2, 2}, new double[]{0, 2}));

        assertTrue(gate.contains(0.0, 1.0), "left edge");
        assertTrue(gate.contains(1.0, 0.0), "bottom edge");
        assertTrue(gate.contains(2.0, 1.0), "right edge");
        assertTrue(gate.contains(1.0, 2.0), "top edge");
        assertTrue(gate.contains(0.0, 0.0), "bottom-left vertex");
        assertTrue(gate.contains(2.0, 2.0), "top-right vertex");
        assertTrue(gate.contains(2.0, 0.0), "bottom-right vertex");
        assertTrue(gate.contains(0.0, 2.0), "top-left vertex");

        double justOutside = Math.nextUp(2.0);
        assertFalse(gate.contains(justOutside, 1.0), "just past the right edge");
        assertFalse(gate.contains(1.0, justOutside), "just past the top edge");
        assertFalse(gate.contains(Math.nextDown(0.0), 1.0), "just past the left edge");
        assertFalse(gate.contains(1.0, Math.nextDown(0.0)), "just past the bottom edge");
    }

    // A self-intersecting (bowtie) polygon still puts every point on any of its edges
    // Inside, including the crossing segments and the point where they cross.
    @Test
    void selfIntersectingPolygonEdgesIncludingTheCrossingAreInside() {
        PolygonGate gate = new PolygonGate("X", "Y");
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{2, 2},
                new double[]{2, 0}, new double[]{0, 2}));

        assertTrue(gate.contains(0.5, 0.5), "on the (0,0)-(2,2) diagonal edge");
        assertTrue(gate.contains(1.0, 1.0), "the crossing point of the two diagonals");
        assertTrue(gate.contains(1.5, 0.5), "on the (2,0)-(0,2) diagonal edge");
    }

    // A self-intersecting (bowtie) polygon follows the even-odd rule: the two lobes are
    // inside, the two notches between them are outside — through the engine, not just contains().
    @Test
    void selfIntersectingPolygonClassifiesByEvenOddRule() {
        double[] xs = {0.25, 1.75, 1.0, 1.0};
        double[] ys = {0.875, 0.875, 0.25, 1.75};
        CellIndex index = Cells.of(4).marker("X", xs).marker("Y", ys).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));
        PolygonGate gate = raw2D(new PolygonGate("X", "Y"));
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{2, 2},
                new double[]{2, 0}, new double[]{0, 2}));

        AssignmentResult result = GatingEngine.assignAll(treeOf(gate), index, stats);
        String in = gate.getInsideBranch().getName();
        String out = gate.getOutsideBranch().getName();

        assertEquals(in, result.getPhenotypes()[0], "left lobe");
        assertEquals(in, result.getPhenotypes()[1], "right lobe");
        assertEquals(out, result.getPhenotypes()[2], "bottom notch");
        assertEquals(out, result.getPhenotypes()[3], "top notch");
    }

    // A polygon whose vertices are collinear encloses no area, so like a <3-vertex polygon
    // it must put every cell Outside, including cells lying on the line itself.
    @Test
    void collinearPolygonEnclosesNothing() {
        CellIndex index = Cells.of(3).marker("X", 0.0, 1.0, 2.0).marker("Y", 0.0, 1.0, 2.0)
                .area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(3));
        PolygonGate gate = raw2D(new PolygonGate("X", "Y"));
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{1, 1}, new double[]{2, 2}));

        GatingEngine.assignAll(treeOf(gate), index, stats);

        assertEquals(0, gate.getInsideBranch().getCount());
        assertEquals(3, gate.getOutsideBranch().getCount());
    }

    // "Clear Shape" sets a rectangle to (0,0,0,0). ScatterPlotCanvas.setGateOverlay's javadoc:
    // a rectangle with no extent "classifies every cell as outside". Cells exactly at 0/0
    // (raw background intensity) must therefore be Outside too, not Inside.
    @Test
    void aClearedRectangleClassifiesACellAtTheOriginAsOutside() {
        CellIndex index = Cells.of(3).marker("X", 0.0, 1.0, 0.0).marker("Y", 0.0, 1.0, 5.0)
                .area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(3));
        RectangleGate gate = raw2D(new RectangleGate("X", "Y", 0, 0, 0, 0));

        AssignmentResult result = GatingEngine.assignAll(treeOf(gate), index, stats);

        assertEquals(0, gate.getInsideBranch().getCount(),
                "a zero-extent (cleared) rectangle has no usable shape, so nothing is inside it");
        assertEquals(gate.getOutsideBranch().getName(), result.getPhenotypes()[0],
                "the cell at (0, 0) sits on the degenerate rectangle's only point");
    }

    // The whole-population arm of the same degenerate case: every cell reads exactly (0, 0)
    // -- a raw background channel -- so every cell sits on the cleared rectangle's only
    // point. (It used to be reached through a zero-spread column z-scoring every cell to 0;
    // a legacy cleared rectangle on such a column must also migrate still cleared.)
    @Test
    void aClearedRectangleDoesNotSelectAPopulationSittingAtTheOrigin() {
        CellIndex index = Cells.of(6).marker("X", 0.0).marker("Y", 0.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(6));
        RectangleGate gate = raw2D(new RectangleGate("X", "Y", 0, 0, 0, 0));
        gate.setThresholdIsZScore(true);
        GateTree tree = treeOf(gate);

        LegacyZScoreMigration.migrate(tree, index, stats);
        GatingEngine.assignAll(tree, index, stats);

        assertEquals(0.0, gate.getMaxX(), "a cleared rectangle migrates still cleared");
        assertEquals(0, gate.getInsideBranch().getCount(),
                "a cleared rectangle must not select the whole population");
        assertEquals(6, gate.getOutsideBranch().getCount());
    }

    // The ellipse counterpart of the cleared-shape case, through the engine rather than
    // contains(): zero radii at the origin put even an origin cell Outside, and a legacy flag
    // still on the gate changes nothing -- the engine has no second space to read it in.
    @Test
    void aClearedEllipseClassifiesEveryCellOutsideWithOrWithoutTheLegacyFlag() {
        for (boolean z : new boolean[]{false, true}) {
            CellIndex index = Cells.of(4).marker("X", 0.0).marker("Y", 0.0).area(100.0).build();
            MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));
            EllipseGate gate = raw2D(new EllipseGate("X", "Y", 0, 0, 0, 0));
            gate.setThresholdIsZScore(z);

            GatingEngine.assignAll(treeOf(gate), index, stats);

            assertEquals(0, gate.getInsideBranch().getCount(), "z=" + z);
            assertEquals(4, gate.getOutsideBranch().getCount(), "z=" + z);
        }
    }

    // ---- unmeasured on one axis of a region gate ----

    // Unmeasured is not negative, for region gates too: NaN on only X or only Y must stop the
    // cell (UNMEASURED), never fall through to contains(), where NaN comparisons read Outside.
    // Existing coverage only exercises a quadrant with clipping on; this is the no-clip path.
    @Test
    void nanOnOneAxisOfARegionGateIsUnmeasuredNotOutside() {
        List<Region2DGate> gates = List.of(
                new RectangleGate("X", "Y", -100, 100, -100, 100),
                new EllipseGate("X", "Y", 0, 0, 100, 100),
                polygonAround100());
        for (Region2DGate proto : gates) {
            CellIndex index = Cells.of(4)
                    .marker("X", i -> i == 1 ? Double.NaN : 1.0)
                    .marker("Y", i -> i == 2 ? Double.NaN : 1.0)
                    .area(100.0).build();
            MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));
            Region2DGate gate = raw2D(proto);

            AssignmentResult result = GatingEngine.assignAll(treeOf(gate), index, stats);
            String what = gate.getGateType();

            assertTrue(result.getUnmeasured()[1], what + ": NaN on X only");
            assertTrue(result.getUnmeasured()[2], what + ": NaN on Y only");
            assertEquals("Unclassified", result.getPhenotypes()[1], what);
            assertEquals("Unclassified", result.getPhenotypes()[2], what);
            assertEquals(2, gate.getInsideBranch().getCount(), what + ": cells 0 and 3");
            assertEquals(0, gate.getOutsideBranch().getCount(),
                    what + ": neither half-measured cell may be counted Outside");
        }
    }

    private static PolygonGate polygonAround100() {
        PolygonGate g = new PolygonGate("X", "Y");
        g.setVertices(List.of(new double[]{-100, -100}, new double[]{100, -100},
                new double[]{100, 100}, new double[]{-100, 100}));
        return g;
    }

    // ---- CLIPPED vs UNMEASURED at the predicate itself ----

    // The two sentinels are distinct values and branchOf returns each for its own cause;
    // branchIgnoringClip on a clipped cell yields a real branch index.
    @Test
    void branchOfDistinguishesClippedFromUnmeasured() {
        assertNotEquals(ResolvedGate.CLIPPED, ResolvedGate.UNMEASURED);
        CellIndex index = Cells.of(11)
                .marker("A", i -> i == 5 ? Double.NaN : i + 1.0)
                .area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(11));
        GateNode gate = threshold("A", 6.0);
        gate.setExcludeOutliers(true);
        gate.setClipPercentileLow(10.0);
        gate.setClipPercentileHigh(90.0);
        ResolvedGate rg = ResolvedGate.compile(List.of(gate), index, stats, null).get(0);

        assertEquals(ResolvedGate.UNMEASURED, rg.branchOf(5), "NaN cell");
        assertEquals(ResolvedGate.UNMEASURED, rg.branchIgnoringClip(5),
                "ignoring the clip does not invent a value");
        assertEquals(ResolvedGate.CLIPPED, rg.branchOf(0), "the lowest value is below p10");
        assertEquals(1, rg.branchIgnoringClip(0), "clipped cell 0 still has the negative branch");
        assertEquals(ResolvedGate.CLIPPED, rg.branchOf(10), "the highest value is above p90");
        assertEquals(0, rg.branchIgnoringClip(10));
    }

    // ---- clipping bounds and outlier toggling ----

    // Clip bounds are inclusive: raw < lo || raw > hi. With 11 values 1..11 and a 10/90 clip
    // the bounds interpolate to exactly 2.0 and 10.0; the cells ON the bounds are kept.
    @Test
    void cellsExactlyOnAClipPercentileBoundAreNotClipped() {
        CellIndex index = Cells.of(11).marker("A", i -> i + 1.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(11));
        GateNode gate = threshold("A", 6.0);
        gate.setExcludeOutliers(true);
        gate.setClipPercentileLow(10.0);
        gate.setClipPercentileHigh(90.0);

        ResolvedGate rg = ResolvedGate.compile(List.of(gate), index, stats, null).get(0);
        assertEquals(2.0, rg.clipLoX, 1e-12);
        assertEquals(10.0, rg.clipHiX, 1e-12);

        AssignmentResult result = GatingEngine.assignAll(treeOf(gate), index, stats);
        boolean[] outlier = result.getOutlier();
        assertTrue(outlier[0], "1.0 < 2.0");
        assertFalse(outlier[1], "2.0 is exactly the low bound");
        assertFalse(outlier[9], "10.0 is exactly the high bound");
        assertTrue(outlier[10], "11.0 > 10.0");
        assertEquals(5, gate.getBranches().get(0).getCount(), "6..10");
        assertEquals(4, gate.getBranches().get(1).getCount(), "2..5");
    }

    // Toggling excludeOutliers changes only counting and flags, never classification: the
    // phenotypes and raw tally totals are identical; clean counts drop by exactly the clipped cells.
    @Test
    void excludeOutliersChangesCountsButNotPhenotypesOrRawTotals() {
        CellIndex index = Cells.of(20).marker("A", i -> i + 1.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(20));

        GateNode off = threshold("A", 10.5);
        AssignmentResult rOff = GatingEngine.assignAll(treeOf(off), index, stats);

        GateNode on = threshold("A", 10.5);
        on.setExcludeOutliers(true);
        on.setClipPercentileLow(10.0);
        on.setClipPercentileHigh(90.0);
        AssignmentResult rOn = GatingEngine.assignAll(treeOf(on), index, stats);

        assertArrayEquals(rOff.getPhenotypes(), rOn.getPhenotypes());
        int clipped = 0;
        for (boolean o : rOn.getOutlier()) if (o) clipped++;
        assertTrue(clipped > 0);
        for (boolean o : rOff.getOutlier()) assertFalse(o);

        int sumOff = 0, sumOn = 0;
        for (int b = 0; b < 2; b++) {
            Branch bOff = off.getBranches().get(b), bOn = on.getBranches().get(b);
            assertEquals(rOff.getTally().total(bOff), rOn.getTally().total(bOn),
                    "raw totals include clipped cells either way");
            assertEquals(bOn.getCount(), rOn.getTally().clean(bOn));
            sumOff += bOff.getCount();
            sumOn += bOn.getCount();
        }
        assertEquals(20, sumOff);
        assertEquals(20 - clipped, sumOn);
        assertEquals(20, rOn.getTally().cellsClean(),
                "cellsClean ignores a gate's own clipping -- only QF + ROI make a cell unclean");
    }

    // A zero-spread column with clipping on: every percentile equals the constant, so no cell
    // is strictly outside the bounds; and every cell sits exactly on a cut at the constant, so
    // all land positive.
    @Test
    void aZeroSpreadColumnClipsNobodyAndACutAtTheConstantKeepsEveryone() {
        CellIndex index = Cells.of(8).marker("A", 5.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(8));
        GateNode gate = threshold("A", 5.0);
        gate.setExcludeOutliers(true);
        gate.setClipPercentileLow(10.0);
        gate.setClipPercentileHigh(90.0);

        AssignmentResult result = GatingEngine.assignAll(treeOf(gate), index, stats);

        for (int i = 0; i < 8; i++) {
            assertFalse(result.getOutlier()[i], "cell " + i);
            assertFalse(result.getUnmeasured()[i], "cell " + i);
            assertEquals("A+", result.getPhenotypes()[i], "cell " + i);
        }
        assertEquals(8, gate.getBranches().get(0).getCount());
    }

    // ---- non-finite values ----

    // +/-Infinity is a real (if extreme) value, not a missing one: in raw space it lands on the
    // side its sign says, is never UNMEASURED, and is Outside every finite region.
    @Test
    void infiniteValuesInRawSpaceAreClassifiedNotUnmeasured() {
        CellIndex index = Cells.of(4)
                .marker("X", Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0, 1.0)
                .marker("Y", 1.0, 1.0, Double.POSITIVE_INFINITY, 1.0)
                .area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));

        GateNode gate = threshold("X", 0.0);
        AssignmentResult r1 = GatingEngine.assignAll(treeOf(gate), index, stats);
        assertEquals("X+", r1.getPhenotypes()[0], "+Infinity >= 0");
        assertEquals("X-", r1.getPhenotypes()[1], "-Infinity < 0");
        assertFalse(r1.getUnmeasured()[0]);
        assertFalse(r1.getUnmeasured()[1]);

        RectangleGate rect = raw2D(new RectangleGate("X", "Y", -10, 10, -10, 10));
        AssignmentResult r2 = GatingEngine.assignAll(treeOf(rect), index, stats);
        for (int i = 0; i < 3; i++) {
            assertEquals(rect.getOutsideBranch().getName(), r2.getPhenotypes()[i], "cell " + i);
            assertFalse(r2.getUnmeasured()[i], "cell " + i);
        }
        assertEquals(rect.getInsideBranch().getName(), r2.getPhenotypes()[3]);
    }

    // One +Infinity in a column must not poison the statistics of every finite cell. MarkerStats
    // used to exclude only NaN from mean/std, so mean=Inf and std=NaN; toZScore's `std < 1e-10`
    // guard is false for NaN and every finite z became NaN. Gates no longer z-score, but the
    // UMAP still does, and a legacy z-threshold is migrated through the same mean and std --
    // an infinite mean would convert it to an infinite cut that no finite cell reaches.
    @Test
    void oneInfiniteValueDoesNotTurnEveryFiniteZScoreIntoNaN() {
        CellIndex index = Cells.of(11)
                .marker("A", i -> i == 10 ? Double.POSITIVE_INFINITY : i + 1.0)
                .area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(11));

        assertFalse(Double.isNaN(index.column("A", null, null, stats).toZScore(5.0)),
                "a finite value's z-score must be a number");

        GateNode gate = threshold("A", 0.0);
        gate.setThresholdIsZScore(true);
        GateTree tree = treeOf(gate);
        LegacyZScoreMigration.migrate(tree, index, stats);
        assertTrue(Double.isFinite(gate.getThreshold()), "migrated through finite statistics");
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        assertEquals("A+", result.getPhenotypes()[9],
                "10.0, the largest finite value, is above any mean that is not itself infinite");
    }

    // ---- disabled gates ----

    // resetCounts walks disabled gates too, so disabling a gate after a pass must zero its
    // counts and its child's -- otherwise the tree view shows the previous pass's numbers.
    @Test
    void disablingAGateClearsItsAndItsSubtreesStaleCounts() {
        CellIndex index = Cells.of(6).marker("A", i -> i + 1.0).marker("B", i -> i + 1.0)
                .area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(6));
        GateNode root = threshold("A", 3.5);
        GateNode child = threshold("B", 5.5);
        root.getBranches().get(0).getChildren().add(child);
        GateTree tree = treeOf(root);

        GatingEngine.assignAll(tree, index, stats);
        assertEquals(3, root.getBranches().get(0).getCount());
        assertEquals(1, child.getBranches().get(0).getCount());

        child.setEnabled(false);
        AssignmentResult r2 = GatingEngine.assignAll(tree, index, stats);
        assertEquals(0, child.getBranches().get(0).getCount(), "disabled child: stale count");
        assertEquals(0, child.getBranches().get(1).getCount());
        assertEquals(3, root.getBranches().get(0).getCount(), "the enabled parent still counts");
        assertEquals("A+", r2.getPhenotypes()[5], "a disabled child adds nothing to the label");
        assertFalse(r2.getUnmeasured()[5], "a disabled gate is not a gate that lacked data");
        assertEquals(0, r2.getTally().total(child.getBranches().get(0)));

        root.setEnabled(false);
        AssignmentResult r3 = GatingEngine.assignAll(tree, index, stats);
        for (Branch b : root.getBranches()) assertEquals(0, b.getCount(), "disabled root");
        assertEquals(6, countOf(r3.getPhenotypes(), "Unclassified"));
        assertEquals(6, r3.getTally().cellsTotal(), "cells are still seen with every root off");
        assertNull(r3.getPerRootColors());
    }

    // With three roots of which two are disabled the walk is single-root: no composite, no
    // per-root colours, and the disabled roots' branches count nothing.
    @Test
    void threeRootsWithTwoDisabledBehaveAsSingleRoot() {
        CellIndex index = Cells.of(4).marker("A", i -> i + 1.0).marker("B", i -> i + 1.0)
                .marker("C", i -> i + 1.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));
        GateNode a = threshold("A", 2.5);
        GateNode b = threshold("B", 2.5);
        GateNode c = threshold("C", 2.5);
        a.setEnabled(false);
        c.setEnabled(false);

        AssignmentResult result = GatingEngine.assignAll(treeOf(a, b, c), index, stats);

        assertNull(result.getPerRootColors());
        assertNull(result.getRootLabels());
        assertEquals("B-", result.getPhenotypes()[0]);
        assertEquals("B+", result.getPhenotypes()[3]);
        for (GateNode off : List.of(a, c)) {
            for (Branch br : off.getBranches()) assertEquals(0, br.getCount());
        }
    }

    // ---- deep nesting ----

    // Four levels: a cell unmeasured at level 2 keeps its level-1 label and never reaches levels
    // 3 or 4; a cell unmeasured at level 3 keeps level 2's label. Counts shrink accordingly.
    @Test
    void unmeasuredAtAMiddleLevelKeepsTheAncestorLabelAndStopsDescending() {
        // All cells are positive on every marker they have; cell 1 lacks B, cell 2 lacks C.
        CellIndex index = Cells.of(4)
                .marker("A", 10.0)
                .marker("B", i -> i == 1 ? Double.NaN : 10.0)
                .marker("C", i -> i == 2 ? Double.NaN : 10.0)
                .marker("D", 10.0)
                .area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));
        GateNode a = threshold("A", 5.0);
        GateNode b = threshold("B", 5.0);
        GateNode c = threshold("C", 5.0);
        GateNode d = threshold("D", 5.0);
        a.getBranches().get(0).getChildren().add(b);
        b.getBranches().get(0).getChildren().add(c);
        c.getBranches().get(0).getChildren().add(d);

        AssignmentResult result = GatingEngine.assignAll(treeOf(a), index, stats);
        String[] p = result.getPhenotypes();

        assertEquals("D+", p[0]);
        assertEquals("A+", p[1], "unmeasured at level 2 keeps level 1's label");
        assertEquals("B+", p[2], "unmeasured at level 3 keeps level 2's label");
        assertEquals("D+", p[3]);
        assertTrue(result.getUnmeasured()[1]);
        assertTrue(result.getUnmeasured()[2]);
        assertFalse(result.getUnmeasured()[0]);

        assertEquals(4, a.getBranches().get(0).getCount());
        assertEquals(3, b.getBranches().get(0).getCount(), "cell 1 stopped at B");
        assertEquals(2, c.getBranches().get(0).getCount(), "cell 2 stopped at C");
        assertEquals(2, d.getBranches().get(0).getCount(), "cell 1 never reached D despite having D");
        for (GateNode g : List.of(a, b, c, d)) {
            assertEquals(0, g.getBranches().get(1).getCount(),
                    g.getChannel() + "-: an unmeasured cell is never counted negative");
        }
    }

    // ---- two roots on the same channel ----

    // Two roots on one channel emit identical branch names; the identity-keyed tally keeps
    // them apart, and the per-root counts do not depend on the order the roots were added.
    @Test
    void twoRootsOnTheSameChannelCountSeparatelyAndOrderFreely() {
        AnalysisSession.AnalysisInput input = AnalysisFixtures.twoRootsSameChannelInput();
        GateNode rootA = input.tree().getRoots().get(0);   // CD45 >= 10.5
        GateNode rootB = input.tree().getRoots().get(1);   // CD45 >= 15.5
        BranchTally tally = input.tally();

        assertEquals(rootA.getBranches().get(0).getName(), rootB.getBranches().get(0).getName(),
                "the premise: both roots name their branches identically");
        assertEquals(10, tally.clean(rootA.getBranches().get(0)));
        assertEquals(10, tally.clean(rootA.getBranches().get(1)));
        assertEquals(5, tally.clean(rootB.getBranches().get(0)));
        assertEquals(15, tally.clean(rootB.getBranches().get(1)));
        assertEquals(20, input.tally().cellsTotal(), "cells are tallied once, not once per root");

        GateTree reversed = treeOf(rootB, rootA);
        AssignmentResult r = GatingEngine.assignAll(reversed, input.index(), input.stats());
        assertEquals(10, rootA.getBranches().get(0).getCount());
        assertEquals(10, rootA.getBranches().get(1).getCount());
        assertEquals(5, rootB.getBranches().get(0).getCount());
        assertEquals(15, rootB.getBranches().get(1).getCount());
        assertEquals(10, r.getTally().clean(rootA.getBranches().get(0)));
        assertEquals(5, r.getTally().clean(rootB.getBranches().get(0)));

        // Cell 11 (value 12): positive on A, negative on B -- composite in root order.
        assertEquals("CD45-: CD45+", r.getPhenotypes()[11]);
        assertEquals(2, r.getPerRootColors().size());
        assertEquals(rootB.getBranches().get(1).getColor(), r.getPerRootColors().get(0)[11]);
        assertEquals(rootA.getBranches().get(0).getColor(), r.getPerRootColors().get(1)[11]);
    }

    // ---- quality filter + ROI + clipping + unmeasured, all at once ----

    // Every exclusion source together, with two roots. Pins: cellsClean = n - |QF union ROI|
    // (an overlap counted once), clean(b) == getCount() and <= cellsClean for every branch,
    // and branch totals summing to n - unmeasured(at that root) and cleans to clean-and-unclipped.
    @Test
    void qualityFilterRoiClippingAndUnmeasuredBookkeepAcrossTwoRoots() {
        int n = 20;
        // Cells 0,1 fail QF (small area); cells 1,2 are outside the ROI (cell 1 is both).
        // Marker B is absent on cells 3 and 4. A is 1..20 so a 10/90 clip catches tails.
        CellIndex index = Cells.of(n)
                .marker("A", i -> i + 1.0)
                .marker("B", i -> (i + 1) * 2.0)
                .absentOn(i -> i == 3 || i == 4)
                .area(i -> i < 2 ? 10.0 : 100.0)
                .build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));
        boolean[] roi = Cells.allTrue(n);
        roi[1] = false;
        roi[2] = false;

        QualityFilter qf = new QualityFilter();
        qf.setRange(QualityFilter.AREA, new QualityFilter.Range(50.0, Double.POSITIVE_INFINITY));

        for (int order = 0; order < 2; order++) {
            GateNode rootA = threshold("A", 10.5);
            rootA.setExcludeOutliers(true);
            rootA.setClipPercentileLow(10.0);
            rootA.setClipPercentileHigh(90.0);
            GateNode rootB = threshold("B", 20.5);

            GateTree tree = new GateTree();
            tree.setQualityFilter(qf);
            if (order == 0) { tree.addRoot(rootA); tree.addRoot(rootB); }
            else { tree.addRoot(rootB); tree.addRoot(rootA); }

            ResolvedGate rgA = ResolvedGate.compile(List.of(rootA), index, stats, null).get(0);
            AssignmentResult result = GatingEngine.assignAll(tree, index, stats, roi, null, 0);
            BranchTally tally = result.getTally();
            String tag = "order " + order + ": ";

            assertEquals(n, tally.cellsTotal(), tag);
            assertEquals(n - 3, tally.cellsClean(), tag + "cells 0, 1, 2 -- cell 1 counted once");

            int baseExcluded = 0, clippedClean = 0;
            for (int i = 0; i < n; i++) {
                boolean base = i <= 2;
                if (base) baseExcluded++;
                boolean clipped = rgA.branchOf(i) == ResolvedGate.CLIPPED;
                if (clipped && !base) clippedClean++;
                assertEquals(base || clipped, result.getExcluded()[i], tag + "excluded union, cell " + i);
                assertEquals(i < 2 || clipped, result.getOutlier()[i], tag + "outlier, cell " + i);
                assertEquals(i == 1 || i == 2, result.getOutOfAnnotation()[i], tag + "ROI, cell " + i);
            }
            assertTrue(clippedClean > 0, tag + "the clip must catch some clean cell");

            // Root A: measures everyone.
            int totA = 0, cleanA = 0;
            for (Branch br : rootA.getBranches()) {
                assertEquals(br.getCount(), tally.clean(br), tag + "clean == getCount");
                assertTrue(tally.clean(br) <= tally.cellsClean(), tag);
                totA += tally.total(br);
                cleanA += tally.clean(br);
            }
            assertEquals(n, totA, tag + "A's raw totals cover every cell");
            assertEquals(n - baseExcluded - clippedClean, cleanA,
                    tag + "A's clean counts drop QF/ROI cells and its own clipped cells");

            // Root B: two unmeasured cells, both clean; B does not inherit A's clipping.
            int totB = 0, cleanB = 0;
            for (Branch br : rootB.getBranches()) {
                assertEquals(br.getCount(), tally.clean(br), tag + "clean == getCount");
                assertTrue(tally.clean(br) <= tally.cellsClean(), tag);
                totB += tally.total(br);
                cleanB += tally.clean(br);
            }
            assertEquals(n - 2, totB, tag + "B's totals omit its two unmeasured cells");
            assertEquals(n - baseExcluded - 2, cleanB,
                    tag + "B's clean counts omit QF/ROI and unmeasured cells, but not A's clipped ones");
        }
    }
}
