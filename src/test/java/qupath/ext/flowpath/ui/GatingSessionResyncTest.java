package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
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
        session.tree().getRoots().get(0).setThreshold(4.5);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);

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

    // ---- counts are carried across an undo/redo/load until the next pass lands -----------

    /**
     * {@code GateNode.deepCopy()} does not carry {@code Branch.getCount()} -- counts are
     * {@code transient}, filled only by a gating walk -- so the tree {@code undo()} swaps in
     * (a deep copy taken when the edit it undoes was recorded) reads 0/0% until the pass that
     * follows lands, which runs in the background and can take seconds on a large slide. The
     * outgoing (post-edit) tree's counts are carried onto it first, so what shows meanwhile is
     * stale-but-plausible rather than a blank zero -- checked BEFORE {@code resync} is even
     * called, which is the whole point: this is what the tree view reads before the next pass
     * exists at all.
     */
    @Test
    void undoCarriesTheOutgoingTreesCountsUntilTheNextPassLands() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(slideA());
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));

        // Same structure, only the threshold changes -- root 0's own edit, so the two trees
        // pair exactly.
        clock.addAndGet(1_000);
        session.tree().getRoots().get(0).setThreshold(8.5);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{2, 8}, counts(pass.last(), session.tree().getRoots().get(0)),
                "the post-edit (outgoing) counts, about to be undone away from");

        assertTrue(session.undo());
        Branch pos = session.tree().getRoots().get(0).getBranches().get(0);
        Branch neg = session.tree().getRoots().get(0).getBranches().get(1);
        assertEquals(2, pos.getCount(), "carried over from the outgoing tree, not reset to 0");
        assertEquals(8, neg.getCount(), "carried over from the outgoing tree, not reset to 0");

        // The pass that follows supplies the actual, correct numbers for the restored gate.
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
    }

    /** Redo carries counts the same way undo does. */
    @Test
    void redoCarriesTheOutgoingTreesCountsUntilTheNextPassLands() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(slideA());
        session.resync(NO_ANNOTATIONS);

        clock.addAndGet(1_000);
        session.tree().getRoots().get(0).setThreshold(8.5);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
        session.resync(NO_ANNOTATIONS);
        assertTrue(session.undo());
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));

        assertTrue(session.redo());
        Branch pos = session.tree().getRoots().get(0).getBranches().get(0);
        Branch neg = session.tree().getRoots().get(0).getBranches().get(1);
        assertEquals(5, pos.getCount(), "carried over from the tree just undone to, not reset to 0");
        assertEquals(5, neg.getCount());

        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{2, 8}, counts(pass.last(), session.tree().getRoots().get(0)));
    }

    /**
     * Loading a tree is {@code replaceTree}, the same carry applies to it, and a structural
     * mismatch (a different number of roots here) must leave the loaded tree's fresh-from-
     * deserialization zero counts alone rather than transfer a count onto some unrelated branch.
     */
    @Test
    void loadingATreeCarriesCountsOnlyWhenTheStructurePairs() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(slideA());
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));

        // A "load" of the exact same structure (as re-opening the file just saved would be).
        session.replaceTree(twoRootsOnCd3());
        assertEquals(5, session.tree().getRoots().get(0).getBranches().get(0).getCount(),
                "carried over from the tree just replaced");
        assertEquals(5, session.tree().getRoots().get(0).getBranches().get(1).getCount());

        // A load whose structure does NOT pair (one root instead of two): left at zero.
        GateTree oneRoot = new GateTree();
        GateNode single = new GateNode("CD3", 5.5);
        single.setStatistic(Statistic.MEAN);
        oneRoot.addRoot(single);
        session.replaceTree(oneRoot);
        assertEquals(0, session.tree().getRoots().get(0).getBranches().get(0).getCount(),
                "no correspondence to borrow: left at deepCopy's own default");
        assertEquals(0, session.tree().getRoots().get(0).getBranches().get(1).getCount());
    }

    // ---- gate edits are reported after the write --------------------------------------

    /**
     * The editor writes into a gate and only then tells the pane. Recording the tree at that
     * point snapshots the edited value, so undo restored nothing. The session records the
     * tree as it was when the previous edit settled instead.
     */
    @Test
    void oneDiscreteGateEditIsUndoneByOneUndo() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(slideA());
        session.resync(NO_ANNOTATIONS);
        int[] root1Before = counts(pass.last(), session.tree().getRoots().get(1));

        // A typed threshold: written, then reported.
        clock.addAndGet(5_000);
        session.tree().getRoots().get(0).setThreshold(8.5);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{2, 8}, counts(pass.last(), session.tree().getRoots().get(0)),
                "CD3 9 and 10 at or above 8.5");

        assertTrue(session.undo());
        session.resync(NO_ANNOTATIONS);
        assertEquals(5.5, session.tree().getRoots().get(0).getThreshold(), "the pre-edit value");
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
        assertArrayEquals(root1Before, counts(pass.last(), session.tree().getRoots().get(1)));
    }

    /**
     * The editor's build-time write-back (a stored signal the export lacks, pinned to one it
     * carries) is settled without an undo step, which is what {@code FlowPathPane} does when
     * the editor reports it through {@code onNodeNormalised}. The next edit's undo step then
     * restores only that edit: the tree keeps the column the editor draws. Unsettled, the undo
     * step reverted the pin along with the edit and the gate went back to an unreadable column.
     */
    @Test
    void anEditAfterABuildTimeWriteBackUndoesOnlyTheEdit() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        GateTree tree = twoRootsOnCd3();
        tree.getRoots().get(0).setStatistic(Statistic.MEDIAN);   // not in a bare-mean export
        session.replaceTree(tree);
        session.adoptIndex(slideA());
        session.resync(NO_ANNOTATIONS);

        // Opening the gate pins it, and the pane settles that write.
        session.tree().getRoots().get(0).setStatistic(Statistic.MEAN);
        session.settle();

        clock.addAndGet(5_000);
        session.tree().getRoots().get(0).setThreshold(8.5);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);

        assertTrue(session.undo());
        GateNode undone = session.tree().getRoots().get(0);
        assertEquals(5.5, undone.getThreshold(), "the edit is undone");
        assertEquals(Statistic.MEAN, undone.getStatistic(), "the pin is not");
    }

    /** A channel change is written and reported the same way. */
    @Test
    void aChannelChangeIsUndoneByOneUndo() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(Cells.of(N).marker("CD3", i -> i + 1.0).marker("CD8", i -> 10.0 - i)
                .area(i -> 10.0 * (i + 1)).at(i -> i * 10.0, i -> 0.0).build());
        session.resync(NO_ANNOTATIONS);

        clock.addAndGet(5_000);
        session.tree().getRoots().get(1).setChannel("CD8");
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
        session.resync(NO_ANNOTATIONS);

        assertTrue(session.undo());
        session.resync(NO_ANNOTATIONS);
        assertEquals("CD3", session.tree().getRoots().get(1).getChannel());
        assertArrayEquals(expectedCounts(session.tree(), 1, session.index(), null, null),
                counts(pass.last(), session.tree().getRoots().get(1)));
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
    }

    /** A threshold drag lasting well over the 500ms window is one step back to the pre-drag value. */
    @Test
    void aLongGateDragIsOneStepBackToThePreDragValue() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(slideA());
        session.resync(NO_ANNOTATIONS);

        clock.addAndGet(5_000);
        for (int tick = 1; tick <= 10; tick++) {       // 1s of drag, 100ms apart
            session.tree().getRoots().get(0).setThreshold(5.5 + tick * 0.3);
            session.recordAppliedEdit(GatingSession.EditSource.GATE);
            session.settle();                           // the pane requests a pass per tick
            clock.addAndGet(100);
        }
        session.resync(NO_ANNOTATIONS);
        assertArrayEquals(new int[]{2, 8}, counts(pass.last(), session.tree().getRoots().get(0)),
                "threshold 8.5 after the drag");

        assertTrue(session.undo());
        session.resync(NO_ANNOTATIONS);
        assertEquals(5.5, session.tree().getRoots().get(0).getThreshold(), "one undo: the pre-drag value");
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));

        assertTrue(session.undo());
        assertTrue(session.tree().getRoots().isEmpty(), "the next step back is the load, not a drag tick");
    }

    /**
     * An edit recorded before its write (adding a gate) settles when the pane requests its
     * pass, so a gate edit after it undoes only itself.
     */
    @Test
    void aGateEditAfterAnAddUndoesOnlyItself() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(slideA());
        session.resync(NO_ANNOTATIONS);

        session.recordEdit();
        session.tree().addRoot(new GateNode("CD3", 1.5));
        session.settle();

        clock.addAndGet(100);
        session.tree().getRoots().get(0).setThreshold(9.5);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);

        assertTrue(session.undo());
        session.resync(NO_ANNOTATIONS);
        assertEquals(3, session.tree().getRoots().size(), "the added gate stays");
        assertEquals(5.5, session.tree().getRoots().get(0).getThreshold());
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

    // ---- replacing a region gate by drawing another shape --------------------------------

    /** CD3 = 1..10 and CD8 = 10..1 on slide A's cells. */
    private static CellIndex slideWithCd8() {
        return Cells.of(N).marker("CD3", i -> i + 1.0).marker("CD8", i -> 10.0 - i)
                .area(i -> 10.0 * (i + 1)).at(i -> i * 10.0, i -> 0.0).build();
    }

    /** Two region roots on the same CD3 x CD8 channels: a rectangle and a polygon. */
    private static GateTree twoRegionRoots() {
        GateTree tree = new GateTree();
        tree.addRoot(onMeans(new RectangleGate("CD3", "CD8", 0.5, 5.5, 0.0, 11.0)));   // CD3 1..5
        PolygonGate polygon = onMeans(new PolygonGate("CD3", "CD8"));
        polygon.setVertices(new ArrayList<>(List.of(
                new double[]{7.5, 0.0}, new double[]{11.0, 0.0}, new double[]{11.0, 11.0}, new double[]{7.5, 11.0})));
        tree.addRoot(polygon);                                                   // CD3 8..10
        return tree;
    }

    /** Both axes on the bare whole-cell mean column {@code Cells.marker} builds. */
    private static <G extends Region2DGate> G onMeans(G gate) {
        gate.setStatisticX(Statistic.MEAN);
        gate.setStatisticY(Statistic.MEAN);
        return gate;
    }

    /**
     * What {@code Region2DGateEditor.onShapeDrawn} and {@code FlowPathPane.replaceGateNode} do
     * when a shape of another type is drawn over root {@code root}: record the replacement,
     * swap it in and request a pass (which settles), write the drawn shape if the replacement
     * was created without it, then report the gate changed.
     */
    private static void drawReplacement(GatingSession session, int root, Region2DGate replacement,
                                        java.util.function.Consumer<Region2DGate> applyDrawn) {
        session.recordReplacement();
        session.tree().getRoots().set(root, replacement);
        session.settle();
        if (applyDrawn != null) applyDrawn.accept(replacement);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
        session.settle();
    }

    /**
     * Drawing a rectangle, ellipse or polygon over a gate of another type is one undo step:
     * the first Ctrl+Z returns the original gate. The replacement used to be recorded, then
     * settled, then recorded again by the editor's change report — a no-op step for a
     * rectangle or ellipse (created already drawn) and an extra "empty polygon" step for a
     * polygon (created empty, then written). Two passes, two same-channel roots.
     */
    @Test
    void drawingAnotherShapeOverAGateIsUndoneByOneUndo() {
        record Case(String name, int root, java.util.function.Supplier<Region2DGate> create,
                    java.util.function.Consumer<Region2DGate> apply) {}
        List<Case> cases = List.of(
                new Case("ellipse over the rectangle", 0,
                        () -> onMeans(new EllipseGate("CD3", "CD8", 9.0, 2.0, 1.5, 1.5)), null),
                new Case("rectangle over the polygon", 1,
                        () -> onMeans(new RectangleGate("CD3", "CD8", 0.5, 2.5, 0.0, 11.0)), null),
                new Case("polygon over the rectangle", 0,
                        () -> onMeans(new PolygonGate("CD3", "CD8")),
                        g -> ((PolygonGate) g).setVertices(new ArrayList<>(List.of(
                                new double[]{0.0, 0.0}, new double[]{3.5, 0.0},
                                new double[]{3.5, 11.0}, new double[]{0.0, 11.0})))));
        for (Case c : cases) {
            AtomicLong clock = new AtomicLong(10_000);
            RecordingPass pass = new RecordingPass();
            GatingSession session = new GatingSession(clock::get, pass);
            CellIndex index = slideWithCd8();
            session.replaceTree(twoRegionRoots());
            session.adoptIndex(index);
            session.resync(NO_ANNOTATIONS);
            assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)), c.name());
            assertArrayEquals(new int[]{3, 7}, counts(pass.last(), session.tree().getRoots().get(1)), c.name());

            // A drag on the other root just before, so a burst is in progress.
            clock.addAndGet(5_000);
            GateNode other = session.tree().getRoots().get(1 - c.root());
            other.setExcludeOutliers(true);
            session.recordAppliedEdit(GatingSession.EditSource.GATE);
            session.settle();
            clock.addAndGet(100);

            Class<?> originalType = session.tree().getRoots().get(c.root()).getClass();
            drawReplacement(session, c.root(), c.create().get(), c.apply());
            session.resync(NO_ANNOTATIONS);
            assertNotEquals(originalType, session.tree().getRoots().get(c.root()).getClass(), c.name());

            assertTrue(session.undo(), c.name());
            session.resync(NO_ANNOTATIONS);
            GateTree expected = twoRegionRoots();
            expected.getRoots().get(1 - c.root()).setExcludeOutliers(true);
            assertEquals(originalType, session.tree().getRoots().get(c.root()).getClass(),
                    c.name() + ": one undo returns the original gate");
            assertTrue(session.tree().getRoots().get(1 - c.root()).isExcludeOutliers(),
                    c.name() + ": the edit before the replacement is its own step");
            assertArrayEquals(expectedCounts(expected, c.root(), index, null, null),
                    counts(pass.last(), session.tree().getRoots().get(c.root())), c.name());
            assertArrayEquals(expectedCounts(expected, 1 - c.root(), index, null, null),
                    counts(pass.last(), session.tree().getRoots().get(1 - c.root())), c.name());

            assertTrue(session.undo(), c.name());
            session.resync(NO_ANNOTATIONS);
            assertFalse(session.tree().getRoots().get(1 - c.root()).isExcludeOutliers(),
                    c.name() + ": the second undo reverts the earlier edit");
        }
    }

    // ---- statistics are reused while the population they describe is unchanged ---------

    /**
     * Statistics sort every marker column over every cell, and undo, redo, load and the ROI
     * toggle resync on the FX thread. They are a pure function of the index and the combined
     * mask, so a resync whose masks did not change keeps them: a Ctrl+Z of a threshold nudge
     * must not re-sort a million-cell slide. Pinned with a quality filter and the ROI filter
     * both on, across a gate edit, its undo and its redo, on two same-channel roots.
     */
    @Test
    void aGateOnlyUndoReusesTheSameStatistics() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        CellIndex index = slideA();
        GateTree tree = twoRootsOnCd3();
        tree.getQualityFilter().setRange("area", new QualityFilter.Range(25, Double.POSITIVE_INFINITY));
        tree.setRoiFilterEnabled(true);
        session.replaceTree(tree);
        session.adoptIndex(index);
        // x = 0..70: cells 0..7; with area >= 25, cells 2..7 (CD3 3..8).
        PathObject left = PathObjects.createAnnotationObject(ROIs.createRectangleROI(-5, -5, 80, 10, PLANE));
        Supplier<List<PathObject>> annotations = () -> List.of(left);
        session.resync(annotations);
        MarkerStats computed = session.stats();
        assertEquals(5.5, meanCd3(index, computed), 1e-12, "statistics over CD3 3..8");

        clock.addAndGet(5_000);
        session.tree().getRoots().get(0).setThreshold(7.5);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
        session.resync(annotations);
        assertSame(computed, session.stats(), "a gate edit changes no mask");
        assertArrayEquals(new int[]{1, 5}, counts(pass.last(), session.tree().getRoots().get(0)));

        assertTrue(session.undo());
        session.resync(annotations);
        assertSame(computed, session.stats(), "a gate-only undo reuses the statistics");
        assertSame(computed, pass.lastInput().stats());
        assertArrayEquals(new int[]{3, 3}, counts(pass.last(), session.tree().getRoots().get(0)));
        assertArrayEquals(expectedCounts(session.tree(), 1, index, session.combinedMask(), session.roiMask()),
                counts(pass.last(), session.tree().getRoots().get(1)));

        assertTrue(session.redo());
        session.resync(annotations);
        assertSame(computed, session.stats(), "and so does its redo");
        assertArrayEquals(new int[]{1, 5}, counts(pass.last(), session.tree().getRoots().get(0)));

        // The annotation moved: the ROI mask is different, so the statistics are recomputed.
        PathObject narrower = PathObjects.createAnnotationObject(ROIs.createRectangleROI(-5, -5, 60, 10, PLANE));
        session.resync(() -> List.of(narrower));
        assertNotSame(computed, session.stats(), "a different ROI mask recomputes");
        assertEquals(4.5, meanCd3(index, session.stats()), 1e-12, "statistics over CD3 3..6");
    }

    /** An undo that restores a different quality filter recomputes, and a later gate-only one reuses again. */
    @Test
    void anUndoRestoringADifferentQualityFilterRecomputesTheStatistics() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        CellIndex index = slideA();
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(index);
        session.resync(NO_ANNOTATIONS);

        clock.addAndGet(5_000);
        session.recordEdit();
        session.tree().getQualityFilter().setRange("area", new QualityFilter.Range(45, Double.POSITIVE_INFINITY));
        session.resync(NO_ANNOTATIONS);
        MarkerStats filtered = session.stats();
        assertEquals(7.5, meanCd3(index, filtered), 1e-12);

        assertTrue(session.undo());
        session.resync(NO_ANNOTATIONS);
        assertNotSame(filtered, session.stats(), "the restored filter selects other cells");
        assertEquals(5.5, meanCd3(index, session.stats()), 1e-12, "statistics over all ten cells");
        assertSame(session.stats(), pass.lastInput().stats());
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
        assertArrayEquals(expectedCounts(session.tree(), 1, index, null, null),
                counts(pass.last(), session.tree().getRoots().get(1)));

        assertTrue(session.redo());
        session.resync(NO_ANNOTATIONS);
        assertEquals(7.5, meanCd3(index, session.stats()), 1e-12, "redo recomputes for the narrower filter");
        assertArrayEquals(new int[]{5, 1}, counts(pass.last(), session.tree().getRoots().get(0)));
    }

    /**
     * A quality-filter tick recomputes the mask at once and the statistics in the background.
     * A resync before those land must not keep the old statistics just because the tree's
     * filter already matches the current mask: they describe the mask before the tick.
     */
    @Test
    void aResyncWhileADragsStatisticsArePendingRecomputes() {
        AtomicLong clock = new AtomicLong(10_000);
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(clock::get, pass);
        CellIndex index = slideA();
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(index);
        session.resync(NO_ANNOTATIONS);
        MarkerStats before = session.stats();

        session.recordEditCoalesced(GatingSession.EditSource.QUALITY_FILTER);
        session.tree().getQualityFilter().setRange("area", new QualityFilter.Range(45, Double.POSITIVE_INFINITY));
        session.settle();
        session.recomputeQualityMask();            // statistics not adopted yet

        session.resync(NO_ANNOTATIONS);
        assertNotSame(before, session.stats());
        assertEquals(7.5, meanCd3(index, session.stats()), 1e-12, "statistics for the filter the tree has");
        assertArrayEquals(expectedCounts(session.tree(), 1, index, session.qualityMask(), null),
                counts(pass.last(), session.tree().getRoots().get(1)));

        // Statistics handed in from the background describe a mask the session cannot check,
        // so the next resync recomputes rather than trust them.
        MarkerStats adopted = MarkerStats.compute(index, session.qualityMask());
        session.adoptStats(adopted);
        session.resync(NO_ANNOTATIONS);
        assertNotSame(adopted, session.stats());
        assertEquals(7.5, meanCd3(index, session.stats()), 1e-12);
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

    // ---- a derivation computed on another thread ------------------------------------------

    /**
     * The heavy half of a resync (masks and statistics) can be computed from inputs captured
     * earlier and adopted later, but only while the tree and index still describe what it was
     * computed from — two passes: one before, one through the adopted derivation.
     */
    @Test
    void aDerivationThatStillDescribesTheTreeIsAdoptedAsComputed() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        CellIndex index = slideA();
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(index);
        session.resync(NO_ANNOTATIONS);

        GatingSession.DerivationInputs inputs = session.derivationInputs(index, NO_ANNOTATIONS);
        GatingSession.Derived derived = GatingSession.derive(inputs);
        session.resync(derived, NO_ANNOTATIONS);

        assertSame(derived.stats(), session.stats(), "a matching derivation is adopted, not recomputed");
        assertSame(derived.stats(), pass.lastInput().stats());
        assertArrayEquals(new int[]{5, 5}, counts(pass.last(), session.tree().getRoots().get(0)));
        assertArrayEquals(expectedCounts(session.tree(), 1, index, session.qualityMask(), null),
                counts(pass.last(), session.tree().getRoots().get(1)));
    }

    /**
     * The quality filter moved while the derivation ran: its statistics describe a filter the
     * tree no longer has, so the resync recomputes rather than gate the new filter against them.
     */
    @Test
    void aDerivationWhoseFilterTheTreeNoLongerHasIsRecomputed() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        CellIndex index = slideA();
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(index);
        session.resync(NO_ANNOTATIONS);

        GatingSession.Derived stale = GatingSession.derive(session.derivationInputs(index, NO_ANNOTATIONS));
        session.tree().getQualityFilter().setRange("area", new QualityFilter.Range(45, Double.POSITIVE_INFINITY));
        session.resync(stale, NO_ANNOTATIONS);

        assertNotSame(stale.stats(), session.stats());
        assertEquals(6, countTrue(session.qualityMask()));
        assertEquals(7.5, meanCd3(index, session.stats()), 1e-12, "statistics over CD3 5..10");
        assertArrayEquals(new int[]{5, 1}, counts(pass.last(), session.tree().getRoots().get(0)));
        assertArrayEquals(expectedCounts(session.tree(), 1, index, session.qualityMask(), null),
                counts(pass.last(), session.tree().getRoots().get(1)));
    }

    /** The ROI filter was toggled, or the index replaced, while the derivation ran. */
    @Test
    void aDerivationForAnotherIndexOrRoiFlagIsRecomputed() {
        RecordingPass pass = new RecordingPass();
        GatingSession session = new GatingSession(() -> 0L, pass);
        CellIndex a = slideA();
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(a);
        session.resync(NO_ANNOTATIONS);

        GatingSession.Derived forA = GatingSession.derive(session.derivationInputs(a, NO_ANNOTATIONS));
        CellIndex b = slideB();
        session.adoptIndex(b);
        session.resync(forA, NO_ANNOTATIONS);
        assertSame(b, pass.lastInput().index());
        assertEquals(6, countTrue(session.qualityMask()));
        assertArrayEquals(new int[]{4, 2}, counts(pass.last(), session.tree().getRoots().get(0)));

        PathObject left = PathObjects.createAnnotationObject(ROIs.createRectangleROI(-5, -5, 30, 10, PLANE));
        Supplier<List<PathObject>> annotations = () -> List.of(left);
        GatingSession.Derived roiOff = GatingSession.derive(session.derivationInputs(b, annotations));
        session.setRoiFilterEnabled(true);
        session.resync(roiOff, annotations);
        assertNotNull(session.roiMask(), "the toggle's mask is computed, not the stale unfiltered one");
        assertEquals(3, countTrue(session.roiMask()));
        assertArrayEquals(new int[]{1, 2}, counts(pass.last(), session.tree().getRoots().get(0)));
        assertArrayEquals(expectedCounts(session.tree(), 1, b, session.roiMask(), session.roiMask()),
                counts(pass.last(), session.tree().getRoots().get(1)));
    }

    private static int countTrue(boolean[] mask) {
        int c = 0;
        for (boolean b : mask) if (b) c++;
        return c;
    }
}
