package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one resync path: what {@link GatingSession#resync} recomputes, in which order and from
 * which tree, whenever the tree, the filters or the image change — undo included.
 * <p>
 * {@code FlowPathPane} cannot be built in the suite (it needs a live {@code QuPathGUI}), so
 * the decision lives here, without a toolkit, and the pane only renders it. Every scenario
 * gates two enabled roots on the <em>same</em> channel and asserts per-branch counts from the
 * pass the resync itself requested, across at least two passes: a single pass cannot tell a
 * resync that recomputed from one that happened to start in the right state.
 */
class GatingSessionResyncTest {

    private static final int N = 10;
    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    /** CD3 = 1..10, area = 10..100, cell i at x = 10*i. */
    private static CellIndex slideA() {
        return Cells.of(N)
                .marker("CD3", i -> i + 1.0)
                .area(i -> 10.0 * (i + 1))
                .at(i -> i * 10.0, i -> 0.0)
                .build();
    }

    /** A different slide: six cells, CD3 = 1 on the first two and 100 on the other four. */
    private static CellIndex slideB() {
        return Cells.of(6)
                .marker("CD3", i -> i < 2 ? 1.0 : 100.0)
                .area(i -> 50.0)
                .at(i -> i * 10.0, i -> 0.0)
                .build();
    }

    /**
     * Root 0: CD3 at 5.5, no clipping — its counts depend only on the quality filter.
     * Root 1: CD3 at 3.5 with outliers excluded at the 20th/80th percentile — its counts
     * depend on the <em>statistics</em>, so stale statistics show up as wrong counts.
     */
    private static GateTree twoRootsOnCd3() {
        GateTree tree = new GateTree();
        GateNode plain = new GateNode("CD3", 5.5);
        plain.setStatistic(Statistic.MEAN);
        GateNode clipped = new GateNode("CD3", 3.5);
        clipped.setStatistic(Statistic.MEAN);
        clipped.setExcludeOutliers(true);
        clipped.setClipPercentileLow(20);
        clipped.setClipPercentileHigh(80);
        tree.addRoot(plain);
        tree.addRoot(clipped);
        return tree;
    }

    /** The arguments of every gating pass the session requested, and that pass's result. */
    private static final class RecordingPass implements GatingSession.GatingPass {
        final List<GatingSession.PassInput> inputs = new ArrayList<>();
        final List<GatingEngine.AssignmentResult> results = new ArrayList<>();

        @Override
        public void request(GatingSession.PassInput input) {
            inputs.add(input);
            results.add(input.index() == null ? null : GatingEngine.assignAll(
                    input.tree(), input.index(), input.stats(), input.roiMask()));
        }

        GatingSession.PassInput lastInput() { return inputs.get(inputs.size() - 1); }
        GatingEngine.AssignmentResult last() { return results.get(results.size() - 1); }
    }

    private static final Supplier<List<PathObject>> NO_ANNOTATIONS = List::of;

    /** [pos, neg] counts of {@code root}, looked up on the tree the pass walked. */
    private static int[] counts(GatingEngine.AssignmentResult result, GateNode root) {
        Branch pos = root.getBranches().get(0);
        Branch neg = root.getBranches().get(1);
        assertEquals(pos.getCount(), result.getTally().clean(pos), "tally and tree agree");
        assertEquals(neg.getCount(), result.getTally().clean(neg), "tally and tree agree");
        return new int[]{pos.getCount(), neg.getCount()};
    }

    private static int[] expectedCounts(GateTree tree, int root, CellIndex index, boolean[] statsMask,
                                        boolean[] roi) {
        GateTree copy = tree.deepCopy();
        GatingEngine.assignAll(copy, index, MarkerStats.compute(index, statsMask), roi);
        GateNode r = copy.getRoots().get(root);
        return new int[]{r.getBranches().get(0).getCount(), r.getBranches().get(1).getCount()};
    }

    private static double meanCd3(CellIndex index, MarkerStats stats) {
        MeasuredColumn col = index.column("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, stats);
        return col.mean();
    }

    // ---- (i) gate edit, quality filter change, undo --------------------------------------

    @Test
    void undoOfAFilterChangeRestoresFilterMaskStatisticsAndCounts() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        CellIndex index = slideA();
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(index);
        session.resync(NO_ANNOTATIONS);

        // Pass 1: nothing filtered. Root 0 is CD3 >= 5.5 over ten cells.
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
        int[] unfilteredClipped = counts(pass.last(), session.tree().getRoots().get(1));

        // A gate edit...
        clock.addAndGet(1_000);
        session.recordEditCoalesced(GatingSession.EditSource.GATE);
        session.tree().getRoots().get(0).setThreshold(4.5);

        // ...then, 100ms later, a quality-filter drag of three ticks: one undo step.
        for (double min : new double[]{25, 35, 45}) {
            clock.addAndGet(100);
            session.recordEditCoalesced(GatingSession.EditSource.QUALITY_FILTER);
            session.tree().getQualityFilter().setRange("area",
                    new QualityFilter.Range(min, Double.POSITIVE_INFINITY));
            session.recomputeQualityMask();
            session.adoptStats(MarkerStats.compute(index, session.combinedMask()));
        }
        session.resync(NO_ANNOTATIONS);
        // Pass 2: area >= 45 keeps cells 4..9 (CD3 5..10).
        assertEquals(6, countTrue(session.qualityMask()));
        assertArrayEquals(new int[]{6, 0}, counts(pass.last(), session.tree().getRoots().get(0)),
                "CD3 >= 4.5 among the six cells that pass the filter");
        int[] filteredClipped = counts(pass.last(), session.tree().getRoots().get(1));
        assertFalse(Arrays.equals(unfilteredClipped, filteredClipped),
                "fixture check: root 1's counts must depend on the statistics");

        assertTrue(session.undo(), "the drag is one step");
        session.resync(NO_ANNOTATIONS);

        // Filter, mask and statistics are all the restored tree's.
        assertTrue(session.tree().getQualityFilter().range("area").isOpen(), "filter restored");
        assertEquals(N, countTrue(session.qualityMask()), "quality mask recomputed from the restored filter");
        assertEquals(5.5, meanCd3(index, session.stats()), 1e-12, "statistics over all ten cells again");
        assertSame(session.stats(), pass.lastInput().stats(), "the pass runs on the resynced statistics");
        assertEquals(4.5, session.tree().getRoots().get(0).getThreshold(), "the gate edit is a separate step");

        // Pass 3 counts accordingly, per branch, on both roots.
        assertArrayEquals(new int[]{6, 4}, counts(pass.last(), session.tree().getRoots().get(0)));
        assertArrayEquals(expectedCounts(session.tree(), 1, index, null, null),
                counts(pass.last(), session.tree().getRoots().get(1)));
        assertArrayEquals(unfilteredClipped, counts(pass.last(), session.tree().getRoots().get(1)),
                "root 1 counts as it did before any filter");

        // And a second undo reverts the gate edit, not the filter again.
        assertTrue(session.undo());
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
    }

    // ---- (ii) ROI toggle, undo -----------------------------------------------------------

    @Test
    void undoOfAnRoiToggleRestoresTheFlagTheMaskAndTheCounts() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        CellIndex index = slideA();
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(index);
        // x = 0..40: cells 0..4, CD3 1..5.
        PathObject left = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(-5, -5, 50, 10, PLANE));
        Supplier<List<PathObject>> annotations = () -> List.of(left);

        session.resync(annotations);
        assertNull(session.roiMask(), "filter off: no mask");
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));

        session.setRoiFilterEnabled(true);
        session.resync(annotations);
        assertTrue(session.tree().isRoiFilterEnabled());
        assertEquals(5, countTrue(session.roiMask()));
        assertNotNull(session.regions());
        assertSame(session.roiMask(), pass.lastInput().roiMask());
        assertArrayEquals(new int[]{0, 5}, counts(pass.last(), session.tree().getRoots().get(0)),
                "only CD3 1..5 are inside the annotation");
        assertArrayEquals(expectedCounts(session.tree(), 1, index, session.roiMask(), session.roiMask()),
                counts(pass.last(), session.tree().getRoots().get(1)));

        assertTrue(session.undo(), "the toggle is one step");
        session.resync(annotations);
        assertFalse(session.tree().isRoiFilterEnabled(), "flag restored");
        assertNull(session.roiMask(), "mask dropped with the flag");
        assertNull(session.regions());
        assertNull(pass.lastInput().roiMask());
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
        assertEquals(5.5, meanCd3(index, session.stats()), 1e-12,
                "statistics are no longer narrowed to the annotation");

        assertTrue(session.redo());
        session.resync(annotations);
        assertEquals(5, countTrue(session.roiMask()));
        assertArrayEquals(new int[]{0, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
    }

    // ---- (iii) image switch --------------------------------------------------------------

    @Test
    void switchingImagesRunsAPassOverTheNewCellsWithoutAnyEdit() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        session.replaceTree(twoRootsOnCd3());

        session.adoptIndex(slideA());
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));

        CellIndex b = slideB();
        session.adoptIndex(b);
        session.resync(NO_ANNOTATIONS);
        assertSame(b, pass.lastInput().index(), "the pass runs on the new image's cells");
        assertSame(b, session.index());
        assertArrayEquals(new int[]{4, 2}, counts(pass.last(), session.tree().getRoots().get(0)),
                "slide B: four cells at 100, two at 1");
        assertArrayEquals(expectedCounts(session.tree(), 1, b, session.qualityMask(), null),
                counts(pass.last(), session.tree().getRoots().get(1)));

        session.adoptIndex(null);
        session.resync(NO_ANNOTATIONS);
        assertNull(pass.lastInput().index(), "closing the image hands the pass nothing to gate");
        assertNull(session.stats());
        assertNull(session.qualityMask());
    }

    // ---- migration inside the resync -----------------------------------------------------

    private static GateNode legacy(String channel, double z) {
        GateNode g = new GateNode(channel, z);
        g.setStatistic(Statistic.MEAN);
        g.setThresholdIsZScore(true);
        return g;
    }

    /**
     * The restored tree's own filter decides the statistics a legacy gate converts through.
     * The live tree's filter narrowed CD3 to 5..10; converting the restored gate through those
     * statistics would land its threshold on the wrong cells.
     */
    @Test
    void anUndoTargetMigratesAgainstItsOwnFilterNotTheLiveOne() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        CellIndex index = slideA();

        GateTree legacyTree = new GateTree();
        legacyTree.addRoot(legacy("CD3", 1.0));
        legacyTree.addRoot(legacy("CD3", -1.0));
        session.replaceTree(legacyTree);        // no image yet: nothing to convert against

        GateTree filtered = twoRootsOnCd3();
        filtered.getQualityFilter().setRange("area", new QualityFilter.Range(45, Double.POSITIVE_INFINITY));
        session.replaceTree(filtered);

        session.adoptIndex(index);
        session.resync(NO_ANNOTATIONS);
        assertEquals(6, countTrue(session.qualityMask()));

        assertTrue(session.undo());
        Optional<GatingSession.MigrationNotice> notice = session.resync(NO_ANNOTATIONS);

        MeasuredColumn unfiltered = index.column("CD3", Compartment.WHOLE_CELL, Statistic.MEAN,
                MarkerStats.compute(index, null));
        GateNode up = session.tree().getRoots().get(0);
        GateNode down = session.tree().getRoots().get(1);
        assertFalse(up.isThresholdIsZScore());
        assertEquals(unfiltered.mean() + unfiltered.std(), up.getThreshold(), 1e-9);
        assertEquals(unfiltered.mean() - unfiltered.std(), down.getThreshold(), 1e-9);
        assertTrue(notice.isPresent());
        assertFalse(notice.get().warning());

        // The pass that followed gated the converted numbers: mean 5.5, std ~3.03.
        assertArrayEquals(new int[]{2, 8}, counts(pass.last(), up), "CD3 9, 10 above 8.53");
        assertArrayEquals(new int[]{8, 2}, counts(pass.last(), down), "CD3 3..10 above 2.47");
    }

    @Test
    void loadAndImageOpenBothMigrateBeforeTheFirstPass() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        CellIndex index = slideA();
        MeasuredColumn col = index.column("CD3", Compartment.WHOLE_CELL, Statistic.MEAN,
                MarkerStats.compute(index, null));

        // Image first, then a load.
        session.adoptIndex(index);
        session.resync(NO_ANNOTATIONS);
        GateTree loaded = new GateTree();
        loaded.addRoot(legacy("CD3", 0.0));
        loaded.addRoot(legacy("CD3", 1.0));
        session.replaceTree(loaded);
        assertTrue(session.resync(NO_ANNOTATIONS).isPresent());
        assertEquals(col.mean(), session.tree().getRoots().get(0).getThreshold(), 1e-9);
        assertFalse(session.tree().getRoots().get(1).isThresholdIsZScore());
        assertFalse(pass.lastInput().tree().getRoots().get(1).isThresholdIsZScore(),
                "the pass never sees a flagged gate");

        // Load first, then the image.
        GatingSession second = new GatingSession(() -> 0L, pass);
        GateTree early = new GateTree();
        early.addRoot(legacy("CD3", 0.0));
        early.addRoot(legacy("CD3", 0.0));
        second.replaceTree(early);
        second.resync(NO_ANNOTATIONS);          // no index: waits, keeps the flag
        assertTrue(second.tree().getRoots().get(0).isThresholdIsZScore());
        second.adoptIndex(index);
        assertTrue(second.resync(NO_ANNOTATIONS).isPresent());
        assertEquals(col.mean(), second.tree().getRoots().get(1).getThreshold(), 1e-9);
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), second.tree().getRoots().get(1)));
    }

    /**
     * A gate whose channel the image lacks keeps its flag and is found again on every resync.
     * Its notice is shown once per image, not on every undo, redo and load.
     */
    @Test
    void anUnchangedMissingChannelNoticeIsShownOncePerImage() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        GateTree tree = twoRootsOnCd3();
        tree.addRoot(legacy("CD99", 1.0));
        session.replaceTree(tree);

        session.adoptIndex(slideA());
        Optional<GatingSession.MigrationNotice> first = session.resync(NO_ANNOTATIONS);
        assertTrue(first.isPresent());
        assertTrue(first.get().warning());
        assertTrue(session.tree().getRoots().get(2).isThresholdIsZScore(), "flag kept");

        session.recordEdit();
        session.tree().getRoots().get(0).setThreshold(2.5);
        assertTrue(session.resync(NO_ANNOTATIONS).isEmpty(), "same notice, not repeated");
        assertTrue(session.undo());
        assertTrue(session.resync(NO_ANNOTATIONS).isEmpty(), "not repeated on undo either");

        session.adoptIndex(slideB());
        assertTrue(session.resync(NO_ANNOTATIONS).isPresent(), "a new image says it again");
    }

    /**
     * A flagged gate on a channel this image lacks, re-pointed in the editor to one it
     * carries, is converted by the channel-change path before the next pass reads it.
     */
    @Test
    void repointingAFlaggedGateToAPresentChannelConvertsItThroughTheSameEntryPoint() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        CellIndex index = slideA();
        GateTree tree = twoRootsOnCd3();
        GateNode flagged = legacy("CD99", 1.0);
        tree.addRoot(flagged);
        session.replaceTree(tree);
        session.adoptIndex(index);
        session.resync(NO_ANNOTATIONS);
        assertTrue(flagged.isThresholdIsZScore());

        flagged.setChannel("CD3");
        Optional<GatingSession.MigrationNotice> notice = session.migrateLegacyZScores();

        MeasuredColumn col = index.column("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, session.stats());
        assertTrue(notice.isPresent());
        assertFalse(flagged.isThresholdIsZScore());
        assertEquals(col.mean() + col.std(), flagged.getThreshold(), 1e-9);
    }

    private static int countTrue(boolean[] mask) {
        int c = 0;
        for (boolean b : mask) if (b) c++;
        return c;
    }
}
