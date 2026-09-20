package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading an image's detections off the FX thread, and reading them again when the detection
 * set changes under an open FlowPath.
 * <p>
 * Every executor here is driven by hand — the background queue, the debounce timer and the
 * "FX thread" (the test thread itself) — so "the older ingest finishes last" and "the burst
 * ends" are orderings the test chooses, never ones it hopes a scheduler produces. Counts are
 * asserted on two enabled roots on the same channel, from the pass the resync requested.
 */
class IngestCoordinatorTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    // ---- hand-driven executors -------------------------------------------------------------

    /** Background work, queued until the test runs it, in whatever order the test picks. */
    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        int pending() { return queue.size(); }
        void runAll() { while (!queue.isEmpty()) queue.remove(0).run(); }
        void runNewestFirst() { while (!queue.isEmpty()) queue.remove(queue.size() - 1).run(); }
    }

    /** A debounce timer that fires only when told to, honouring cancellation. */
    private static final class ManualScheduler implements IngestCoordinator.Scheduler {
        private final List<Runnable> live = new ArrayList<>();
        int scheduled;

        @Override
        public Runnable schedule(Runnable task, long delayMs) {
            assertEquals(IngestCoordinator.REFRESH_DEBOUNCE_MS, delayMs);
            scheduled++;
            live.add(task);
            return () -> live.remove(task);
        }

        int live() { return live.size(); }

        /** The quiet period elapsed: run every timer still armed. */
        void elapse() {
            List<Runnable> due = new ArrayList<>(live);
            live.clear();
            due.forEach(Runnable::run);
        }
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

        GatingEngine.AssignmentResult last() { return results.get(results.size() - 1); }
    }

    private static final class RecordingHost implements IngestCoordinator.Host {
        final List<IngestCoordinator.Cleared> cleared = new ArrayList<>();
        final List<IngestResult> ingested = new ArrayList<>();
        final List<Boolean> resyncedWithNewIndex = new ArrayList<>();
        final List<IngestCoordinator.Busy> busy = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();

        @Override public List<PathObject> annotations(ImageData<?> imageData) {
            return new ArrayList<>(imageData.getHierarchy().getAnnotationObjects());
        }
        @Override public void cleared(IngestCoordinator.Cleared why) { cleared.add(why); }
        @Override public void ingested(ImageData<?> imageData, IngestResult result) { ingested.add(result); }
        @Override public void resynced(Optional<GatingSession.MigrationNotice> notice, boolean newIndex) {
            resyncedWithNewIndex.add(newIndex);
        }
        @Override public void busyChanged(IngestCoordinator.Busy state) { busy.add(state); }
        @Override public void failed(Throwable error) { failures.add(error); }

        IngestCoordinator.Busy lastBusy() { return busy.isEmpty() ? IngestCoordinator.Busy.IDLE : busy.get(busy.size() - 1); }
    }

    /** Everything one test drives. */
    private static final class Rig {
        final ManualExecutor background = new ManualExecutor();
        final ManualScheduler scheduler = new ManualScheduler();
        final RecordingPass pass = new RecordingPass();
        final RecordingHost host = new RecordingHost();
        final AtomicBoolean firingOwnEvent = new AtomicBoolean();
        final GatingSession session = new GatingSession(() -> 0L, pass);
        /** How many detections each actual {@code DetectionIngest.read} call was given, in order. */
        final List<Integer> reads = new ArrayList<>();
        final IngestCoordinator coordinator = new IngestCoordinator(session, background, scheduler,
                Runnable::run, firingOwnEvent::get, host, (detections, imageData) -> {
                    reads.add(detections.size());
                    return DetectionIngest.read(detections, imageData);
                });

        Rig() {
            session.replaceTree(twoRootsOnCd3());
            session.settle();
        }

        GateNode root(int i) { return session.tree().getRoots().get(i); }
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** Two roots on CD3, at 5.5 and 2.5, no clipping. */
    private static GateTree twoRootsOnCd3() {
        GateTree tree = new GateTree();
        GateNode high = new GateNode("CD3", 5.5);
        high.setStatistic(Statistic.MEAN);
        GateNode low = new GateNode("CD3", 2.5);
        low.setStatistic(Statistic.MEAN);
        tree.addRoot(high);
        tree.addRoot(low);
        return tree;
    }

    /** An image whose hierarchy holds {@code detections}. */
    private static ImageData<BufferedImage> imageWith(String name, List<PathObject> detections) {
        ImageData<BufferedImage> imageData = new ImageData<>(new WrappedBufferedImageServer(
                name, new BufferedImage(200, 20, BufferedImage.TYPE_INT_RGB)));
        imageData.getHierarchy().addObjects(detections);
        return imageData;
    }

    /** CD3 = 1..n, cell i at x = 10*i. */
    private static List<PathObject> cd3Cells(int n) {
        return Cells.of(n).marker("CD3", i -> i + 1.0).area(i -> 50.0).at(i -> i * 10.0, i -> 5.0).detections();
    }

    /** One more cell, carrying the same measurement keys. */
    private static PathObject cd3Cell(double cd3, double x) {
        return Cells.of(1).marker("CD3", cd3).area(50.0).at(x, 5.0).only();
    }

    /** [pos, neg] of {@code root} in the last pass, checked against that pass's tally. */
    private static int[] counts(Rig rig, GateNode root) {
        Branch pos = root.getBranches().get(0);
        Branch neg = root.getBranches().get(1);
        GatingEngine.AssignmentResult result = rig.pass.last();
        assertEquals(pos.getCount(), result.getTally().clean(pos), "tally and tree agree");
        assertEquals(neg.getCount(), result.getTally().clean(neg), "tally and tree agree");
        return new int[]{pos.getCount(), neg.getCount()};
    }

    private static boolean indexHolds(CellIndex index, PathObject object) {
        return Arrays.asList(index.getObjects()).contains(object);
    }

    // ---- opening an image ------------------------------------------------------------------

    @Test
    void openingAnImageReadsItOnTheBackgroundAndAppliesTheResultOnTheFxThread() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));

        rig.coordinator.open(image);
        assertNull(rig.session.index(), "nothing is read on the calling thread");
        assertEquals(1, rig.background.pending());
        assertEquals(IngestCoordinator.Busy.LOADING, rig.host.lastBusy());
        assertTrue(rig.host.ingested.isEmpty());

        rig.background.runAll();
        assertEquals(10, rig.session.index().size());
        assertEquals(1, rig.host.ingested.size());
        assertEquals(IngestCoordinator.Busy.IDLE, rig.host.lastBusy());
        assertEquals(Boolean.TRUE, rig.host.resyncedWithNewIndex.get(rig.host.resyncedWithNewIndex.size() - 1));
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(new int[]{8, 2}, counts(rig, rig.root(1)));
    }

    /**
     * The stale-result guard: two opens, the older read finishes last, and the newer image's
     * cells are the ones that stay.
     */
    @Test
    void anOlderIngestFinishingLastNeverReplacesTheNewerImage() {
        Rig rig = new Rig();
        ImageData<BufferedImage> a = imageWith("a", cd3Cells(10));
        List<PathObject> bCells = cd3Cells(4);
        ImageData<BufferedImage> b = imageWith("b", bCells);

        rig.coordinator.open(a);
        rig.coordinator.open(b);
        assertEquals(2, rig.background.pending());

        rig.background.runNewestFirst();     // b lands, then a lands late

        assertEquals(4, rig.session.index().size(), "image b's cells, not image a's");
        assertTrue(indexHolds(rig.session.index(), bCells.get(0)));
        assertEquals(1, rig.host.ingested.size(), "the superseded read is never applied");
        assertEquals(IngestCoordinator.Busy.IDLE, rig.host.lastBusy());
        assertArrayEquals(new int[]{0, 4}, counts(rig, rig.root(0)));
        assertArrayEquals(new int[]{2, 2}, counts(rig, rig.root(1)));
    }

    /** Switching images drops the old cells at once, so no gate edit can reach them meanwhile. */
    @Test
    void switchingImagesClearsTheOldIndexBeforeTheNewOneLands() {
        Rig rig = new Rig();
        rig.coordinator.open(imageWith("a", cd3Cells(10)));
        rig.background.runAll();
        assertNotNull(rig.session.index());

        rig.coordinator.open(imageWith("b", cd3Cells(4)));
        assertNull(rig.session.index());
        assertNull(rig.pass.inputs.get(rig.pass.inputs.size() - 1).index(),
                "the pass requested meanwhile has nothing to gate");
        assertEquals(IngestCoordinator.Cleared.LOADING, rig.host.cleared.get(rig.host.cleared.size() - 1));
    }

    @Test
    void noImageAndNoDetectionsAreClearedWithoutBackgroundWork() {
        Rig rig = new Rig();
        rig.coordinator.open(imageWith("a", cd3Cells(10)));
        rig.background.runAll();

        rig.coordinator.open(null);
        assertNull(rig.session.index());
        assertEquals(IngestCoordinator.Cleared.NO_IMAGE, rig.host.cleared.get(rig.host.cleared.size() - 1));
        assertEquals(0, rig.background.pending());

        rig.coordinator.open(imageWith("empty", List.of()));
        assertEquals(IngestCoordinator.Cleared.NO_DETECTIONS, rig.host.cleared.get(rig.host.cleared.size() - 1));
        assertEquals(0, rig.background.pending());
        assertEquals(IngestCoordinator.Busy.IDLE, rig.host.lastBusy());
    }

    /**
     * A first-load failure (baseline == null: nothing was ever read for this image) must clear
     * the editor rather than leave it re-enabled, once the busy state clears, over the previous
     * image's gate and channel combos with the index still null -- {@link
     * IngestCoordinator.Cleared#LOADING} deliberately keeps those on screen while a read is in
     * flight, but there is no read left in flight here to land and replace them.
     */
    @Test
    void aFirstLoadFailureClearsTheEditor() {
        ManualExecutor background = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        RecordingPass pass = new RecordingPass();
        RecordingHost host = new RecordingHost();
        AtomicBoolean firingOwnEvent = new AtomicBoolean();
        GatingSession session = new GatingSession(() -> 0L, pass);
        IngestCoordinator coordinator = new IngestCoordinator(session, background, scheduler,
                Runnable::run, firingOwnEvent::get, host,
                (detections, imageData) -> { throw new RuntimeException("boom"); });

        coordinator.open(imageWith("a", cd3Cells(10)));
        assertEquals(IngestCoordinator.Busy.LOADING, host.lastBusy());
        background.runAll();

        assertEquals(1, host.failures.size(), "the failure is still reported");
        assertEquals(List.of(IngestCoordinator.Cleared.LOADING, IngestCoordinator.Cleared.FAILED),
                host.cleared, "LOADING when the read started, FAILED once it failed");
        assertNull(session.index());
        assertEquals(IngestCoordinator.Busy.IDLE, host.lastBusy());
    }

    @Test
    void closingDropsAnInFlightIngestAndStopsListening() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.coordinator.open(image);
        rig.coordinator.close();
        rig.background.runAll();
        assertNull(rig.session.index(), "a read that lands after close is dropped");
        assertTrue(rig.host.ingested.isEmpty());

        image.getHierarchy().addObject(cd3Cell(100, 150));
        assertEquals(0, rig.scheduler.live(), "a closed coordinator no longer listens");
        rig.coordinator.open(image);
        assertEquals(0, rig.background.pending(), "and opens nothing");
    }

    // ---- the detection set changes under an open image -------------------------------------

    @Test
    void anAddedDetectionIsIngestedAfterTheDebounceAndCounted() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.coordinator.open(image);
        rig.background.runAll();
        CellIndex before = rig.session.index();
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));

        PathObject added = cd3Cell(100, 150);
        image.getHierarchy().addObject(added);
        assertSame(before, rig.session.index(), "nothing happens before the quiet period ends");
        assertEquals(0, rig.background.pending());

        rig.scheduler.elapse();
        rig.background.runAll();

        assertEquals(11, rig.session.index().size());
        assertTrue(indexHolds(rig.session.index(), added));
        assertArrayEquals(new int[]{6, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(new int[]{9, 2}, counts(rig, rig.root(1)));
        assertEquals(2, rig.session.tree().getRoots().size(), "the gate tree is kept");
    }

    @Test
    void aRemovedDetectionIsDroppedFromTheIndexAndTheCounts() {
        Rig rig = new Rig();
        List<PathObject> cells = cd3Cells(10);
        ImageData<BufferedImage> image = imageWith("a", cells);
        rig.coordinator.open(image);
        rig.background.runAll();

        PathObject removed = cells.get(9);   // CD3 = 10
        image.getHierarchy().removeObject(removed, false);
        rig.scheduler.elapse();
        rig.background.runAll();

        assertEquals(9, rig.session.index().size());
        assertFalse(indexHolds(rig.session.index(), removed));
        assertArrayEquals(new int[]{4, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(new int[]{7, 2}, counts(rig, rig.root(1)));
    }

    @Test
    void aBurstOfChangesIsOneReingest() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.coordinator.open(image);
        rig.background.runAll();

        for (int i = 0; i < 5; i++) image.getHierarchy().addObject(cd3Cell(100, 110 + i));
        assertEquals(1, rig.scheduler.live(), "each change re-arms the one timer");

        rig.scheduler.elapse();
        assertEquals(1, rig.background.pending());
        rig.background.runAll();
        assertEquals(15, rig.session.index().size());
        assertEquals(2, rig.host.ingested.size(), "the open, then one re-read for the burst");
    }

    /** FlowPath's own classification write fires a hierarchy event; it must not loop back. */
    @Test
    void flowPathsOwnClassificationWriteDoesNotReingest() {
        Rig rig = new Rig();
        List<PathObject> cells = cd3Cells(10);
        ImageData<BufferedImage> image = imageWith("a", cells);
        rig.coordinator.open(image);
        rig.background.runAll();
        CellIndex before = rig.session.index();

        rig.firingOwnEvent.set(true);
        image.getHierarchy().fireHierarchyChangedEvent(this);
        rig.firingOwnEvent.set(false);
        image.getHierarchy().fireObjectClassificationsChangedEvent(this, cells);

        assertEquals(0, rig.scheduler.live());
        rig.scheduler.elapse();
        rig.background.runAll();
        assertSame(before, rig.session.index());
        assertEquals(1, rig.host.ingested.size());
    }

    /**
     * With the ROI filter off, an annotation-only edit cannot change the detection set or any
     * mask FlowPath keeps, so there is nothing for a refresh to do -- it must never even arm
     * the debounce timer, let alone copy the detection list or flip the busy state (the
     * status-bar flicker this is what avoids).
     */
    @Test
    void anAnnotationOnlyChangeDoesNotReingest() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.coordinator.open(image);
        rig.background.runAll();
        CellIndex before = rig.session.index();
        int busyChangesBefore = rig.host.busy.size();

        image.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(-5, 0, 50, 10, PLANE)));

        assertEquals(0, rig.scheduler.live(), "an annotation-only edit with the filter off arms no refresh");
        assertEquals(busyChangesBefore, rig.host.busy.size(), "and never flips the busy state");
        assertEquals(0, rig.background.pending());

        rig.scheduler.elapse();
        rig.background.runAll();

        assertSame(before, rig.session.index(), "same cells: the index is kept");
        assertEquals(1, rig.host.ingested.size());
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
    }

    /**
     * A structure event that names no changed objects at all could still be a detection
     * add/remove the platform reported without an object list, so it must always get the full
     * debounce-and-compare -- unlike a named-objects-only annotation edit, which the coordinator
     * can rule out up front.
     */
    @Test
    void aStructureEventNamingNoObjectsAlwaysGetsTheFullCheck() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.coordinator.open(image);
        rig.background.runAll();

        image.getHierarchy().fireHierarchyChangedEvent(this);   // OTHER_STRUCTURE_CHANGE, no objects

        assertEquals(1, rig.scheduler.live(), "cannot be ruled out: the debounce timer is armed");
    }

    /**
     * With the annotation filter on, an annotation change recomputes the mask and statistics
     * in the background and keeps the index; the editor is not rebuilt for it.
     */
    @Test
    void anAnnotationChangeUnderTheRoiFilterRederivesInTheBackgroundAndKeepsTheIndex() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.session.setRoiFilterEnabled(true);
        rig.coordinator.open(image);
        rig.background.runAll();
        CellIndex before = rig.session.index();
        assertNull(rig.session.roiMask(), "no annotation yet: nothing filtered");
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));

        image.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(-5, 0, 50, 10, PLANE)));   // cells 0..4, CD3 1..5
        int passesBefore = rig.pass.inputs.size();
        rig.scheduler.elapse();
        assertEquals(passesBefore, rig.pass.inputs.size(), "the recompute runs off the FX thread");
        rig.background.runAll();

        assertSame(before, rig.session.index());
        assertEquals(1, rig.host.ingested.size());
        assertNotNull(rig.session.roiMask());
        assertEquals(Boolean.FALSE,
                rig.host.resyncedWithNewIndex.get(rig.host.resyncedWithNewIndex.size() - 1));
        assertArrayEquals(new int[]{0, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(new int[]{3, 2}, counts(rig, rig.root(1)));
    }

    /**
     * The index holds copies of the values, so a measurement change on a detection is read
     * again although the set of cells is the same — and an annotation edit that settles while
     * that read runs neither discards it nor loses the owed read.
     */
    @Test
    void aMeasurementChangeOnADetectionIsReadAgainWhenAnotherChangeArrivesInFlight() {
        Rig rig = new Rig();
        List<PathObject> cells = cd3Cells(10);
        ImageData<BufferedImage> image = imageWith("a", cells);
        rig.coordinator.open(image);
        rig.background.runAll();
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));

        PathObject first = cells.get(0);    // CD3 = 1
        first.getMeasurements().put("CD3", 100.0);
        image.getHierarchy().fireObjectMeasurementsChangedEvent(this, List.of(first));
        rig.scheduler.elapse();
        assertEquals(1, rig.background.pending());

        image.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(-5, 0, 50, 10, PLANE)));
        rig.scheduler.elapse();
        assertEquals(1, rig.background.pending(), "the running read is not superseded");
        rig.background.runAll();

        assertEquals(10, rig.session.index().size());
        assertArrayEquals(new int[]{6, 4}, counts(rig, rig.root(0)));
        assertArrayEquals(new int[]{9, 1}, counts(rig, rig.root(1)));
        assertEquals(2, rig.host.ingested.size());
    }

    /**
     * An annotation edit during a first load neither discards the read in flight nor queues a
     * second one: it is checked once the read lands, against the cells it produced.
     */
    @Test
    void anAnnotationEditDuringTheFirstReadIsCheckedAfterItLandsNotReadAgain() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.coordinator.open(image);
        image.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(-5, 0, 50, 10, PLANE)));
        rig.scheduler.elapse();
        assertEquals(1, rig.background.pending(), "no second job queued behind the read");
        assertEquals(IngestCoordinator.Busy.LOADING, rig.host.lastBusy());

        rig.background.runAll();
        assertEquals(1, rig.reads.size(), "exactly one read");
        assertEquals(1, rig.host.ingested.size(), "exactly one read result applied");
        assertEquals(IngestCoordinator.Busy.IDLE, rig.host.lastBusy());
        assertArrayEquals(new int[]{5, 5}, counts(rig, rig.root(0)));
        assertArrayEquals(new int[]{8, 2}, counts(rig, rig.root(1)));
    }

    /** Switching A→B→C: A's and B's jobs stop before reading, so C's is not queued behind them. */
    @Test
    void supersededReadsReturnEarlyWithoutReading() {
        Rig rig = new Rig();
        rig.coordinator.open(imageWith("a", cd3Cells(10)));
        rig.coordinator.open(imageWith("b", cd3Cells(4)));
        rig.coordinator.open(imageWith("c", cd3Cells(6)));
        assertEquals(3, rig.background.pending());

        rig.background.runAll();
        assertEquals(List.of(6), rig.reads, "only the newest image (c, six cells) is read");
        assertEquals(6, rig.session.index().size());
        assertEquals(1, rig.host.ingested.size());
        assertEquals(IngestCoordinator.Busy.IDLE, rig.host.lastBusy());
    }

    /** A change pending when the image switches belongs to the old image. */
    @Test
    void aPendingRefreshDoesNotSurviveAnImageSwitch() {
        Rig rig = new Rig();
        ImageData<BufferedImage> a = imageWith("a", cd3Cells(10));
        rig.coordinator.open(a);
        rig.background.runAll();

        a.getHierarchy().addObject(cd3Cell(100, 150));
        rig.coordinator.open(imageWith("b", cd3Cells(4)));
        rig.scheduler.elapse();
        rig.background.runAll();

        assertEquals(4, rig.session.index().size());
        assertEquals(2, rig.host.ingested.size());
    }

    /** A refresh requested while the first read is still running is not lost to the guard. */
    @Test
    void aChangeDuringTheFirstReadIsPickedUpByTheRefresh() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.coordinator.open(image);
        image.getHierarchy().addObject(cd3Cell(100, 150));
        rig.scheduler.elapse();
        rig.background.runAll();

        assertEquals(11, rig.session.index().size());
        assertEquals(IngestCoordinator.Busy.IDLE, rig.host.lastBusy());
        assertArrayEquals(new int[]{6, 5}, counts(rig, rig.root(0)));
    }
}
