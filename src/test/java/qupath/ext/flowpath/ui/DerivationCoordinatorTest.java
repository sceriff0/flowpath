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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three resyncs that still change a mask — the annotation-filter toggle, an undo or redo
 * across a filter change, and a load — computing their masks and statistics off the FX thread.
 * <p>
 * Every executor is driven by hand (the background queue and the "FX thread", which is the
 * test thread itself), so "nothing ran yet", "the older derivation finishes last" and "the
 * derivation threw" are orderings the test chooses rather than ones it hopes a scheduler
 * produces. Counts are asserted per branch on two enabled roots on the <em>same</em> channel,
 * across at least two passes: one pass cannot tell a resync that recomputed from one that
 * happened to start in the right state.
 */
class DerivationCoordinatorTest {

    private static final int N = 10;
    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    // ---- hand-driven executor ----------------------------------------------------------

    /** Background work, queued until the test runs it, in whatever order the test picks. */
    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        int pending() { return queue.size(); }
        void runAll() { while (!queue.isEmpty()) queue.remove(0).run(); }
        void runNewestFirst() { while (!queue.isEmpty()) queue.remove(queue.size() - 1).run(); }
    }

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

    private static final class RecordingHost implements DerivationCoordinator.Host {
        final List<Optional<GatingSession.MigrationNotice>> resynced = new ArrayList<>();
        final List<Boolean> busy = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        List<PathObject> annotations = List.of();

        @Override public List<PathObject> annotations() { return annotations; }
        @Override public void resynced(Optional<GatingSession.MigrationNotice> notice) { resynced.add(notice); }
        @Override public void busyChanged(boolean deriving) { busy.add(deriving); }
        @Override public void failed(Throwable error) { failures.add(error); }

        boolean lastBusy() { return !busy.isEmpty() && busy.get(busy.size() - 1); }
    }

    /** Everything one test drives. */
    private static final class Rig {
        final ManualExecutor background = new ManualExecutor();
        final RecordingPass pass = new RecordingPass();
        final RecordingHost host = new RecordingHost();
        final AtomicLong clock = new AtomicLong(10_000);
        final GatingSession session = new GatingSession(clock::get, pass);
        /** Set to make the next derivation throw, as an out-of-memory sort or a bad ROI would. */
        RuntimeException failWith;
        /** Run once, inside the next derivation: what the user did while the sort was running. */
        Runnable duringDerivation;
        final DerivationCoordinator coordinator = new DerivationCoordinator(session, background,
                Runnable::run, host, (inputs, reusable) -> {
                    if (duringDerivation != null) {
                        Runnable once = duringDerivation;
                        duringDerivation = null;
                        once.run();
                    }
                    if (failWith != null) throw failWith;
                    return GatingSession.derive(inputs, reusable);
                });

        Rig(CellIndex index) {
            session.replaceTree(twoRootsOnCd3());
            session.adoptIndex(index);
            session.settle();
        }

        GateNode root(int i) { return session.tree().getRoots().get(i); }

        /** One request, run to completion: what a click does once the background catches up. */
        void requestAndRun() {
            coordinator.request();
            background.runAll();
        }
    }

    // ---- fixtures ----------------------------------------------------------------------

    /** CD3 = 1..10, area = 10..100, cell i at x = 10*i. */
    private static CellIndex slideA() {
        return Cells.of(N)
                .marker("CD3", i -> i + 1.0)
                .area(i -> 10.0 * (i + 1))
                .at(i -> i * 10.0, i -> 0.0)
                .build();
    }

    /**
     * Root 0: CD3 at 5.5, no clipping — its counts depend only on the masks.
     * Root 1: CD3 at 3.5 with outliers clipped at the 20th/80th percentile — its counts depend
     * on the <em>statistics</em>, so a derivation that never landed shows up as wrong counts.
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

    /** [pos, neg] counts of {@code root} in the last pass, checked against that pass's tally. */
    private static int[] counts(Rig rig, GateNode root) {
        Branch pos = root.getBranches().get(0);
        Branch neg = root.getBranches().get(1);
        GatingEngine.AssignmentResult result = rig.pass.last();
        assertEquals(pos.getCount(), result.getTally().clean(pos), "tally and tree agree");
        assertEquals(neg.getCount(), result.getTally().clean(neg), "tally and tree agree");
        return new int[]{pos.getCount(), neg.getCount()};
    }

    /** What a pass over {@code tree} would count, computed independently of the session. */
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

    private static int countTrue(boolean[] mask) {
        int c = 0;
        for (boolean b : mask) if (b) c++;
        return c;
    }

    private static PathObject rectangle(double width) {
        return PathObjects.createAnnotationObject(ROIs.createRectangleROI(-5, -5, width, 10, PLANE));
    }

    // ---- the annotation-filter toggle ---------------------------------------------------

    @Test
    void anRoiToggleDerivesOnTheBackgroundAndLandsPerBranchCounts() {
        Rig rig = new Rig(slideA());
        rig.requestAndRun();
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
        int[] unfilteredClipped = counts(rig, rig.root(1));
        MarkerStats unfiltered = rig.session.stats();

        // x = 0..40: cells 0..4, CD3 1..5.
        rig.host.annotations = List.of(rectangle(50));
        int renders = rig.host.resynced.size();
        rig.session.setRoiFilterEnabled(true);
        rig.coordinator.request();

        // Nothing heavy ran on the calling thread: the masks and statistics are still the old ones.
        assertEquals(1, rig.background.pending());
        assertNull(rig.session.roiMask(), "no mask computed on the calling thread");
        assertSame(unfiltered, rig.session.stats(), "no statistics computed on the calling thread");
        assertEquals(renders, rig.host.resynced.size(), "nothing rendered yet");
        assertTrue(rig.coordinator.deriving());
        assertTrue(rig.host.lastBusy());
        int passes = rig.pass.inputs.size();

        rig.background.runAll();

        assertFalse(rig.coordinator.deriving());
        assertFalse(rig.host.lastBusy());
        assertEquals(renders + 1, rig.host.resynced.size(), "one render, on the FX thread");
        assertEquals(passes + 1, rig.pass.inputs.size(), "one further pass");
        assertEquals(5, countTrue(rig.session.roiMask()));
        assertNotNull(rig.session.regions());
        assertSame(rig.session.roiMask(), rig.pass.lastInput().roiMask());
        assertEquals(3.0, meanCd3(rig.session.index(), rig.session.stats()), 1e-12,
                "statistics narrowed to CD3 1..5");
        assertArrayEquals(new int[]{0, 5}, counts(rig, rig.root(0)),
                "only CD3 1..5 are inside the annotation");
        assertArrayEquals(expectedCounts(rig.session.tree(), 1, rig.session.index(),
                        rig.session.roiMask(), rig.session.roiMask()),
                counts(rig, rig.root(1)));
        assertFalse(java.util.Arrays.equals(unfilteredClipped, counts(rig, rig.root(1))),
                "fixture check: root 1's counts depend on the statistics");

        // And toggling it back off lands the unfiltered counts again.
        rig.session.setRoiFilterEnabled(false);
        rig.requestAndRun();
        assertNull(rig.session.roiMask());
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(unfilteredClipped, counts(rig, rig.root(1)));
    }

    // ---- undo across a quality-filter change --------------------------------------------

    @Test
    void anUndoAcrossAQualityFilterChangeRecomputesOnTheBackgroundAndLands() {
        Rig rig = new Rig(slideA());
        rig.requestAndRun();
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
        int[] unfilteredClipped = counts(rig, rig.root(1));

        rig.clock.addAndGet(5_000);
        rig.session.recordEdit();
        rig.session.tree().getQualityFilter().setRange("area",
                new QualityFilter.Range(45, Double.POSITIVE_INFINITY));
        rig.requestAndRun();
        MarkerStats filtered = rig.session.stats();
        assertEquals(6, countTrue(rig.session.qualityMask()));
        assertEquals(7.5, meanCd3(rig.session.index(), filtered), 1e-12, "statistics over CD3 5..10");
        assertArrayEquals(new int[]{5, 1}, counts(rig, rig.root(0)));

        assertTrue(rig.session.undo(), "the filter change is one step");
        rig.coordinator.request();
        assertSame(filtered, rig.session.stats(), "not re-sorted on the calling thread");
        assertEquals(6, countTrue(rig.session.qualityMask()), "nor re-masked there");

        rig.background.runAll();
        assertTrue(rig.session.tree().getQualityFilter().range("area").isOpen(), "filter restored");
        assertNotSame(filtered, rig.session.stats(), "the restored filter selects other cells");
        assertEquals(N, countTrue(rig.session.qualityMask()));
        assertEquals(5.5, meanCd3(rig.session.index(), rig.session.stats()), 1e-12,
                "statistics over all ten cells again");
        assertSame(rig.session.stats(), rig.pass.lastInput().stats(), "the pass runs on them");
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(unfilteredClipped, counts(rig, rig.root(1)),
                "root 1 counts as it did before the filter");

        // Redo recomputes the other way, through the same path.
        assertTrue(rig.session.redo());
        rig.requestAndRun();
        assertEquals(7.5, meanCd3(rig.session.index(), rig.session.stats()), 1e-12);
        assertArrayEquals(new int[]{5, 1}, counts(rig, rig.root(0)));
        assertArrayEquals(expectedCounts(rig.session.tree(), 1, rig.session.index(),
                rig.session.qualityMask(), null), counts(rig, rig.root(1)));
    }

    /**
     * A gate-only undo still reuses the statistics rather than re-sorting every column — the
     * background is not an excuse to do the work anyway.
     */
    @Test
    void aGateOnlyUndoReusesTheStatisticsThroughTheBackgroundPath() {
        Rig rig = new Rig(slideA());
        rig.requestAndRun();
        MarkerStats computed = rig.session.stats();

        rig.clock.addAndGet(5_000);
        rig.session.tree().getRoots().get(0).setThreshold(8.5);
        rig.session.recordAppliedEdit(GatingSession.EditSource.GATE);
        rig.requestAndRun();
        assertSame(computed, rig.session.stats(), "a gate edit changes no mask");
        assertArrayEquals(new int[]{2, 8}, counts(rig, rig.root(0)));

        assertTrue(rig.session.undo());
        rig.requestAndRun();
        assertSame(computed, rig.session.stats(), "a gate-only undo reuses the statistics");
        assertSame(computed, rig.pass.lastInput().stats());
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(expectedCounts(rig.session.tree(), 1, rig.session.index(), null, null),
                counts(rig, rig.root(1)));
    }

    // ---- the stale-derivation guard ------------------------------------------------------

    /** Two rapid toggles: the newer derivation is the one that lands, whichever finishes first. */
    @Test
    void twoRapidConflictingRequestsApplyOnlyTheNewest() {
        for (boolean olderFinishesLast : new boolean[]{false, true}) {
            Rig rig = new Rig(slideA());
            rig.host.annotations = List.of(rectangle(50));
            rig.requestAndRun();
            int renders = rig.host.resynced.size();
            int passes = rig.pass.inputs.size();

            rig.session.setRoiFilterEnabled(true);
            rig.coordinator.request();
            rig.session.setRoiFilterEnabled(false);       // the user toggled straight back
            rig.coordinator.request();
            assertEquals(2, rig.background.pending());

            if (olderFinishesLast) {
                rig.background.runNewestFirst();
            } else {
                rig.background.runAll();
            }

            assertNull(rig.session.roiMask(), "the newest derivation is the one that landed");
            assertNull(rig.session.regions());
            assertNull(rig.pass.lastInput().roiMask());
            assertEquals(renders + 1, rig.host.resynced.size(), "the superseded derivation renders nothing");
            assertEquals(passes + 1, rig.pass.inputs.size(), "and requests no pass");
            assertFalse(rig.coordinator.deriving());
            assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
            assertArrayEquals(expectedCounts(rig.session.tree(), 1, rig.session.index(), null, null),
                    counts(rig, rig.root(1)));
        }
    }

    /**
     * An undo burst: three Ctrl+Z in a row, none of them waiting for the previous derivation.
     * Undo is never queued or refused — each step is taken at once, on the FX thread, exactly
     * as it was synchronously — only the derivation it asked for is superseded.
     */
    @Test
    void anUndoBurstTakesEveryStepAndLandsOnlyTheLastDerivation() {
        Rig rig = new Rig(slideA());
        rig.requestAndRun();

        for (double threshold : new double[]{6.5, 7.5, 8.5}) {
            rig.clock.addAndGet(5_000);
            rig.session.tree().getRoots().get(0).setThreshold(threshold);
            rig.session.recordAppliedEdit(GatingSession.EditSource.GATE);
            rig.requestAndRun();
        }
        assertArrayEquals(new int[]{2, 8}, counts(rig, rig.root(0)));
        int renders = rig.host.resynced.size();

        for (int i = 0; i < 3; i++) {
            assertTrue(rig.session.undo(), "every step is taken, not queued or refused");
            rig.coordinator.request();
        }
        assertEquals(5.5, rig.session.tree().getRoots().get(0).getThreshold(), "all three steps back");
        rig.background.runAll();

        assertEquals(renders + 1, rig.host.resynced.size(), "one render for the burst");
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(expectedCounts(rig.session.tree(), 1, rig.session.index(), null, null),
                counts(rig, rig.root(1)));
    }

    /**
     * A read of the same image landed while the derivation ran, so it describes cells the
     * session no longer holds. It is derived again <em>in the background</em> — letting
     * {@code resync}'s synchronous fallback handle it would put the sort back on the FX thread,
     * which is the whole point of this class.
     */
    @Test
    void aDerivationTheSessionHasMovedPastIsDerivedAgainInTheBackground() {
        Rig rig = new Rig(slideA());
        rig.requestAndRun();
        MarkerStats forA = rig.session.stats();

        rig.session.setRoiFilterEnabled(true);
        rig.host.annotations = List.of(rectangle(50));
        rig.coordinator.request();

        // The detections changed under it: six cells, CD3 1..6, and the same annotation now
        // holds five of them.
        CellIndex reread = Cells.of(6).marker("CD3", i -> i + 1.0).area(i -> 10.0 * (i + 1))
                .at(i -> i * 10.0, i -> 0.0).build();
        rig.session.rereadIndex(reread);

        rig.background.queue.remove(0).run();            // the stale derivation lands
        assertSame(forA, rig.session.stats(), "nothing stale is adopted");
        assertTrue(rig.coordinator.deriving(), "still busy: the work was requested again");
        assertEquals(1, rig.background.pending(), "and requested on the background, not run here");

        rig.background.runAll();
        assertFalse(rig.coordinator.deriving());
        assertSame(reread, rig.pass.lastInput().index());
        assertEquals(5, countTrue(rig.session.roiMask()));
        assertArrayEquals(new int[]{0, 5}, counts(rig, rig.root(0)), "CD3 1..5 inside, 6 outside");
        assertArrayEquals(expectedCounts(rig.session.tree(), 1, reread,
                rig.session.roiMask(), rig.session.roiMask()), counts(rig, rig.root(1)));
    }

    /**
     * The request arrives <em>while</em> the derivation is running, so it is too late to stop
     * before the sort — the result has to be dropped as it lands. The annotations are what
     * changed, which is the input a derivation carries rather than re-checks: nothing about the
     * tree tells the landing that its region mask describes an annotation the user has redrawn.
     */
    @Test
    void aRequestArrivingDuringADerivationSupersedesItAtTheLanding() {
        Rig rig = new Rig(slideA());
        rig.requestAndRun();
        int renders = rig.host.resynced.size();
        int passes = rig.pass.inputs.size();

        rig.host.annotations = List.of(rectangle(50));          // x <= 45: cells 0..4
        rig.session.setRoiFilterEnabled(true);
        rig.coordinator.request();
        rig.duringDerivation = () -> {
            rig.host.annotations = List.of(rectangle(80));      // the user redrew it: cells 0..7
            rig.coordinator.request();
        };
        rig.background.runAll();

        assertEquals(renders + 1, rig.host.resynced.size(), "the overtaken derivation renders nothing");
        assertEquals(passes + 1, rig.pass.inputs.size(), "and requests no pass");
        assertEquals(8, countTrue(rig.session.roiMask()), "the redrawn annotation is the one in force");
        assertFalse(rig.coordinator.deriving());
        assertArrayEquals(new int[]{3, 5}, counts(rig, rig.root(0)), "CD3 6, 7, 8 are at or above 5.5");
        assertArrayEquals(expectedCounts(rig.session.tree(), 1, rig.session.index(),
                rig.session.roiMask(), rig.session.roiMask()), counts(rig, rig.root(1)));
    }

    // ---- the failure path -----------------------------------------------------------------

    @Test
    void aThrowingDerivationIsReportedAndLeavesThePreviousState() {
        Rig rig = new Rig(slideA());
        rig.host.annotations = List.of(rectangle(50));
        rig.requestAndRun();
        MarkerStats before = rig.session.stats();
        int[] root0 = counts(rig, rig.root(0));
        int[] root1 = counts(rig, rig.root(1));
        int passes = rig.pass.inputs.size();

        rig.failWith = new IllegalStateException("sort failed");
        rig.session.setRoiFilterEnabled(true);
        rig.requestAndRun();

        assertEquals(1, rig.host.failures.size(), "the failure surfaces");
        assertSame(rig.failWith, rig.host.failures.get(0));
        assertFalse(rig.coordinator.deriving(), "controls are re-enabled");
        assertFalse(rig.host.lastBusy());
        assertSame(before, rig.session.stats(), "the session keeps its statistics");
        assertNull(rig.session.roiMask(), "and its masks");
        assertEquals(passes, rig.pass.inputs.size(), "no pass runs on a half-derived state");
        assertArrayEquals(root0, counts(rig, rig.root(0)));
        assertArrayEquals(root1, counts(rig, rig.root(1)));

        // The next request recovers: the failure left nothing behind to trip over.
        rig.failWith = null;
        rig.requestAndRun();
        assertEquals(1, rig.host.failures.size());
        assertEquals(5, countTrue(rig.session.roiMask()));
        assertArrayEquals(new int[]{0, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(expectedCounts(rig.session.tree(), 1, rig.session.index(),
                rig.session.roiMask(), rig.session.roiMask()), counts(rig, rig.root(1)));
    }

    // ---- no cells, and shutting down -------------------------------------------------------

    /**
     * With no image open there is nothing heavy to derive, so the resync happens at once rather
     * than through a background round trip that would leave the tree view showing the old tree.
     */
    @Test
    void aRequestWithoutCellsResyncsImmediately() {
        Rig rig = new Rig(slideA());
        rig.requestAndRun();

        rig.session.adoptIndex(null);
        rig.coordinator.request();
        assertEquals(0, rig.background.pending(), "nothing queued");
        assertEquals(2, rig.host.resynced.size(), "rendered on the calling thread");
        assertFalse(rig.coordinator.deriving());
        assertNull(rig.session.stats());
        assertNull(rig.pass.lastInput().index());
    }

    /** A derivation still in flight when the pane goes away lands nowhere. */
    @Test
    void aDerivationInFlightWhenTheCoordinatorClosesIsDropped() {
        Rig rig = new Rig(slideA());
        rig.requestAndRun();
        int renders = rig.host.resynced.size();
        int passes = rig.pass.inputs.size();

        rig.session.setRoiFilterEnabled(true);
        rig.coordinator.request();
        rig.coordinator.close();
        rig.background.runAll();

        assertEquals(renders, rig.host.resynced.size(), "nothing rendered into a closed pane");
        assertEquals(passes, rig.pass.inputs.size());
        assertNull(rig.session.roiMask());
        assertFalse(rig.coordinator.deriving());
    }
}
