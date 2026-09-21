package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.model.Branch;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IngestCoordinator} and {@link DerivationCoordinator} against <b>one shared</b>
 * {@link GatingSession} — the structural gap every coordinator test up to now left open, each
 * having driven its own coordinator against a session nothing else touched.
 * <p>
 * In production the two share more than the session: they share the single
 * {@code flowpath-background} thread, so a detection re-read and a filter recompute queue
 * behind each other rather than racing, and either can be the one in flight when the other's
 * result lands. Both handle that by <em>re-requesting in the background</em> rather than
 * letting {@code GatingSession.resync}'s synchronous fallback re-sort every marker column on
 * the FX thread — and that is the arm only an interleaved test exercises at all.
 * <p>
 * So this rig wires both coordinators to one session and one hand-driven background queue, and
 * plays the interleaving in both orders: a read landing while a derivation is in flight, and a
 * derivation landing while a read is in flight. Neither test asserts a particular sequence of
 * intermediate jobs — the two supersede independently, and pinning the exact round trips would
 * pin an implementation detail — it asserts the property that matters: once the queue has
 * drained, every derived piece describes the cells that are actually there.
 * <p>
 * <b>Two enabled roots on the same channel</b>, as every per-branch assertion in this suite
 * must be. Both roots are on CD3, so their branches carry byte-identical names and only their
 * position tells them apart; their counts are deliberately different (0/5 against 3/2) so a
 * pairing that confused them cannot land on a number this test accepts.
 */
class CoordinatorHandoffTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    // ---- hand-driven executors -------------------------------------------------------------

    /**
     * The one background thread both coordinators share, driven by hand. {@link #drain()} runs
     * queued work <em>including work that work queues in turn</em>, under a hard bound: two
     * coordinators that re-request each other forever would otherwise hang the suite rather
     * than fail it.
     */
    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        int ran;

        @Override public void execute(Runnable command) { queue.add(command); }
        int pending() { return queue.size(); }
        void runOldest() { ran++; queue.remove(0).run(); }
        void runNewest() { ran++; queue.remove(queue.size() - 1).run(); }

        void drain() {
            int guard = 0;
            while (!queue.isEmpty()) {
                assertTrue(++guard <= 20,
                        "the two coordinators never settled: each landing kept re-requesting "
                                + "work in the background instead of adopting");
                runOldest();
            }
        }
    }

    /** A debounce timer that fires only when told to. */
    private static final class ManualScheduler implements IngestCoordinator.Scheduler {
        private final List<Runnable> live = new ArrayList<>();

        @Override public Runnable schedule(Runnable task, long delayMs) {
            live.add(task);
            return () -> live.remove(task);
        }

        void elapse() {
            List<Runnable> due = new ArrayList<>(live);
            live.clear();
            due.forEach(Runnable::run);
        }
    }

    private static final class RecordingPass implements GatingSession.GatingPass {
        final List<GatingEngine.AssignmentResult> results = new ArrayList<>();

        @Override
        public void request(GatingSession.PassInput input) {
            results.add(input.index() == null ? null : GatingEngine.assignAll(
                    input.tree(), input.index(), input.stats(), input.roiMask()));
        }

        GatingEngine.AssignmentResult last() { return results.get(results.size() - 1); }
    }

    /** Everything one test drives: one session, two coordinators, one background queue. */
    private static final class Rig {
        final ManualExecutor background = new ManualExecutor();
        final ManualScheduler scheduler = new ManualScheduler();
        final RecordingPass pass = new RecordingPass();
        final AtomicBoolean firingOwnEvent = new AtomicBoolean();
        final GatingSession session = new GatingSession(() -> 0L, pass);
        final List<Integer> reads = new ArrayList<>();
        final List<Boolean> ingestResyncedWithNewIndex = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        ImageData<?> image;

        final IngestCoordinator ingest;
        final DerivationCoordinator derivation;

        Rig() {
            session.replaceTree(twoRootsOnCd3());
            session.settle();
            ingest = new IngestCoordinator(session, background, scheduler, Runnable::run,
                    firingOwnEvent::get, new IngestCoordinator.Host() {
                        @Override public List<PathObject> annotations(ImageData<?> imageData) {
                            return annotationsOf(imageData);
                        }
                        @Override public void cleared(IngestCoordinator.Cleared why) { }
                        @Override public void ingested(ImageData<?> imageData, IngestResult result) { }
                        @Override public void resynced(Optional<GatingSession.MigrationNotice> n, boolean newIndex) {
                            ingestResyncedWithNewIndex.add(newIndex);
                        }
                        @Override public void busyChanged(IngestCoordinator.Busy state) { }
                        @Override public void failed(Throwable error) { failures.add(error); }
                    }, (detections, imageData) -> {
                        reads.add(detections.size());
                        return DetectionIngest.read(detections, imageData);
                    });
            derivation = new DerivationCoordinator(session, background, Runnable::run,
                    new DerivationCoordinator.Host() {
                        @Override public List<PathObject> annotations() { return annotationsOf(image); }
                        @Override public void resynced(Optional<GatingSession.MigrationNotice> notice) { }
                        @Override public void busyChanged(boolean deriving) { }
                        @Override public void failed(Throwable error) { failures.add(error); }
                    });
        }

        GateNode root(int i) { return session.tree().getRoots().get(i); }

        /** Open {@code imageData} and let the first read land, as a fresh panel would. */
        void openAndSettle(ImageData<?> imageData) {
            image = imageData;
            ingest.open(imageData);
            background.drain();
        }

        /** The annotation filter is switched on, exactly as the pane's checkbox does it. */
        void toggleRoiFilterOn() {
            session.setRoiFilterEnabled(true);
            derivation.request();
        }
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** Two roots on CD3, at 5.5 and 2.5: byte-identical branch names, different counts. */
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

    private static List<PathObject> annotationsOf(ImageData<?> imageData) {
        return imageData == null ? List.of()
                : new ArrayList<>(imageData.getHierarchy().getAnnotationObjects());
    }

    private static ImageData<BufferedImage> imageWith(String name, List<PathObject> detections) {
        ImageData<BufferedImage> imageData = new ImageData<>(new WrappedBufferedImageServer(
                name, new BufferedImage(200, 20, BufferedImage.TYPE_INT_RGB)));
        imageData.getHierarchy().addObjects(detections);
        return imageData;
    }

    /** CD3 = 1..n, cell i at x = 10*i. */
    private static List<PathObject> cd3Cells(int n) {
        return Cells.of(n).marker("CD3", i -> i + 1.0).area(i -> 50.0)
                .at(i -> i * 10.0, i -> 5.0).detections();
    }

    private static PathObject cd3Cell(double cd3, double x) {
        return Cells.of(1).marker("CD3", cd3).area(50.0).at(x, 5.0).only();
    }

    /** Covers x in [-5, 45]: cells 0..4, CD3 1..5. The cell added at x = 150 is outside it. */
    private static PathObject leftHalfAnnotation() {
        return PathObjects.createAnnotationObject(ROIs.createRectangleROI(-5, 0, 50, 10, PLANE));
    }

    /** [pos, neg] of {@code root} in the last pass, cross-checked against that pass's tally. */
    private static int[] counts(Rig rig, GateNode root) {
        Branch pos = root.getBranches().get(0);
        Branch neg = root.getBranches().get(1);
        GatingEngine.AssignmentResult result = rig.pass.last();
        assertEquals(pos.getCount(), result.getTally().clean(pos), "tally and tree agree");
        assertEquals(neg.getCount(), result.getTally().clean(neg), "tally and tree agree");
        return new int[]{pos.getCount(), neg.getCount()};
    }

    /**
     * The one end state both orderings must reach: eleven cells, the annotation filter in
     * force over the five on the left, and both roots counted over exactly those five.
     */
    private static void assertBothLanded(Rig rig) {
        assertTrue(rig.failures.isEmpty(), () -> "no coordinator may fail: " + rig.failures);
        assertNotNull(rig.session.index());
        assertEquals(11, rig.session.index().size(), "the added detection is in the index");
        assertTrue(rig.session.tree().isRoiFilterEnabled());
        assertNotNull(rig.session.roiMask(), "the annotation filter is in force");
        assertEquals(2, rig.reads.size(), "exactly two reads: the open and the re-read");
        assertFalse(rig.derivation.deriving(), "no derivation left in flight");
        assertEquals(IngestCoordinator.Busy.IDLE, rig.ingest.busy(), "no ingest left in flight");
        assertTrue(rig.ingestResyncedWithNewIndex.stream().filter(Boolean::booleanValue).count() >= 2,
                "the editor rebuild owed by the re-read must survive however many background "
                        + "round trips the handoff took");
        assertArrayEquals(new int[]{0, 5}, counts(rig, rig.root(0)), "root at 5.5 over CD3 1..5");
        assertArrayEquals(new int[]{3, 2}, counts(rig, rig.root(1)), "root at 2.5 over CD3 1..5");
    }

    // ---- the two interleavings ---------------------------------------------------------------

    /**
     * A detection edit starts a re-read; the user switches the annotation filter on while it is
     * in flight, which queues a derivation behind it. The read lands first, into a session
     * whose ROI flag it never saw.
     */
    @Test
    void aReadLandingWhileADerivationIsInFlightEndsWithBothApplied() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.openAndSettle(image);
        image.getHierarchy().addObject(leftHalfAnnotation());   // ignored: the filter is off

        image.getHierarchy().addObject(cd3Cell(100, 150));
        rig.scheduler.elapse();
        assertEquals(1, rig.background.pending(), "the re-read is queued");

        rig.toggleRoiFilterOn();
        assertEquals(2, rig.background.pending(), "the derivation is queued behind it");

        rig.background.runOldest();     // the read lands, against a session that has moved on
        rig.background.drain();

        assertBothLanded(rig);
    }

    /**
     * The reverse: the derivation is the one in flight and the read is queued behind it, but
     * the derivation's result is what lands first — into a session whose <em>cells</em> it
     * never saw, because the read that replaced them got there first.
     */
    @Test
    void aDerivationLandingWhileAReadIsInFlightEndsWithBothApplied() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.openAndSettle(image);
        image.getHierarchy().addObject(leftHalfAnnotation());

        rig.toggleRoiFilterOn();
        assertEquals(1, rig.background.pending(), "the derivation is queued");

        image.getHierarchy().addObject(cd3Cell(100, 150));
        rig.scheduler.elapse();
        assertEquals(2, rig.background.pending(), "the re-read is queued behind it");

        rig.background.runNewest();     // the read finishes first...
        rig.background.runOldest();     // ...and the older derivation lands into it
        rig.background.drain();

        assertBothLanded(rig);
    }

    /**
     * The freeze guard, stated over the shared session rather than over either coordinator
     * alone: after the first read has landed, every landing that does not already describe the
     * session must queue background work rather than resync on the calling thread. A gating
     * pass is the observable side effect of a resync, so "no new pass at this landing, and a
     * job left on the queue" is precisely "the derive did not happen here".
     */
    @Test
    void neitherCoordinatorResyncsOnTheCallingThreadWhenItsResultHasGoneStale() {
        Rig rig = new Rig();
        ImageData<BufferedImage> image = imageWith("a", cd3Cells(10));
        rig.openAndSettle(image);
        image.getHierarchy().addObject(leftHalfAnnotation());

        image.getHierarchy().addObject(cd3Cell(100, 150));
        rig.scheduler.elapse();
        rig.toggleRoiFilterOn();

        int passesBefore = rig.pass.results.size();
        rig.background.runOldest();     // the read lands: its derivation predates the toggle
        assertEquals(passesBefore, rig.pass.results.size(),
                "no pass, so no resync: the read's landing derived nothing on this thread");
        assertTrue(rig.background.pending() > 0, "it queued the work instead");

        int passesAfterRead = rig.pass.results.size();
        rig.background.runOldest();     // the derivation lands: its index predates the read
        assertEquals(passesAfterRead, rig.pass.results.size(),
                "no pass, so no resync: the derivation's landing derived nothing either");
        assertTrue(rig.background.pending() > 0, "it queued the work instead");

        rig.background.drain();
        assertBothLanded(rig);
    }
}
