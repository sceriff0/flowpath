package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A background statistics recompute must not overwrite statistics that were set after it
 * was submitted.
 * <p>
 * A quality-filter drag submits {@link LivePreviewService#recomputeStats()} against the filter
 * as it was at that moment. If the user then undoes, {@code FlowPathPane} computes statistics
 * under the <em>restored</em> filter and hands them over with
 * {@link LivePreviewService#setMarkerStats}. The recompute still in the queue used to land
 * afterwards and put the pre-undo statistics back, so the next gating pass — and the pane,
 * through {@code onStatsRecomputed} — ran on a filter the tree no longer has.
 * <p>
 * The executor is driven by hand, so "the recompute finishes after the explicit set" is an
 * ordering this test chooses rather than one it hopes a thread scheduler produces.
 */
class LivePreviewServiceStaleStatsTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    /** Runs nothing until {@link #runAll()} is called. */
    private static final class ManualExecutor extends AbstractExecutorService {
        private final Deque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;

        @Override public synchronized void execute(Runnable command) { queue.add(command); }
        void runAll() {
            Runnable next;
            while ((next = poll()) != null) next.run();
        }
        private synchronized Runnable poll() { return queue.poll(); }
        synchronized int pending() { return queue.size(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(queue); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @Test
    void aRecomputeSubmittedBeforeAnExplicitSetIsDiscarded() {
        CellIndex index = Cells.of(4).marker("CD3", 1, 2, 3, 4).area(10, 20, 30, 40).build();
        GateTree tree = new GateTree();
        MarkerStats restored = MarkerStats.compute(index, null);

        ManualExecutor executor = new ManualExecutor();
        LivePreviewService service = new LivePreviewService(executor);
        try {
            service.setCellIndex(index);
            service.setGateTree(tree);
            int[] recomputedCallbacks = {0};
            service.setOnStatsRecomputed(() -> recomputedCallbacks[0]++);

            service.recomputeStats();              // the drag's recompute, queued
            assertEquals(1, executor.pending());
            service.setMarkerStats(restored);      // the undo's statistics
            executor.runAll();                     // the drag's recompute lands late

            assertSame(restored, service.getMarkerStats(),
                    "statistics set after the recompute was submitted must survive it");
            FxTestSupport.onFxRun(() -> { });      // drain any runLater the recompute queued
            assertEquals(0, recomputedCallbacks[0],
                    "a discarded recompute must not tell the pane to adopt statistics");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aRecomputeWithNoLaterSetIsPublished() {
        CellIndex index = Cells.of(4).marker("CD3", 1, 2, 3, 4).area(10, 20, 30, 40).build();
        MarkerStats before = MarkerStats.compute(index, null);

        ManualExecutor executor = new ManualExecutor();
        LivePreviewService service = new LivePreviewService(executor);
        try {
            service.setCellIndex(index);
            service.setGateTree(new GateTree());
            service.setMarkerStats(before);
            service.recomputeStats();
            executor.runAll();
            org.junit.jupiter.api.Assertions.assertNotSame(before, service.getMarkerStats(),
                    "an undisturbed recompute still publishes its result");
        } finally {
            service.shutdown();
        }
    }

    /**
     * A gating pass walked on one index must not publish after the index was replaced — a
     * detection re-read that keeps the gate tree. The tree check alone let it through, and
     * {@code FlowPathPane.buildSnapshot} checks only the length, so a same-size re-read would
     * hand the UMAP the old cells' phenotypes positioned against the new cells. Two passes,
     * two roots on the same channel; the executor is driven by hand.
     */
    @Test
    void aPassWalkedOnAReplacedIndexIsDiscarded() throws Exception {
        // Same size, different cells: A is CD3 1..10, B is 100 on the first five and 0 after.
        CellIndex a = Cells.of(10).marker("CD3", i -> i + 1.0).build();
        CellIndex b = Cells.of(10).marker("CD3", i -> i < 5 ? 100.0 : 0.0).build();
        GateNode low = new GateNode("CD3", 3.5);
        low.setStatistic(Statistic.MEAN);
        GateNode high = new GateNode("CD3", 7.5);
        high.setStatistic(Statistic.MEAN);
        GateTree tree = new GateTree();
        tree.addRoot(low);
        tree.addRoot(high);
        ImageData<?> imageData = new ImageData<>(new WrappedBufferedImageServer(
                "live-preview-stale-index", new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));

        ManualExecutor executor = new ManualExecutor();
        LivePreviewService service = new LivePreviewService(executor);
        try {
            service.setCellIndex(a);
            service.setMarkerStats(MarkerStats.compute(a, null));
            service.setImageData(imageData);
            service.setGateTree(tree);
            AtomicInteger published = new AtomicInteger();
            service.setOnUpdateComplete(published::incrementAndGet);

            // Pass 1 is queued on A; the re-read of B lands (on the FX thread, tree kept)
            // before the walk runs. onUpdateStarted is posted just before the submit, so the
            // work is already queued when it runs.
            CountDownLatch started = new CountDownLatch(1);
            service.setOnUpdateStarted(() -> {
                service.setCellIndex(b);
                service.setMarkerStats(MarkerStats.compute(b, null));
                started.countDown();
            });
            service.requestUpdate();
            assertTrue(started.await(FxTestSupport.timeoutSeconds(), TimeUnit.SECONDS), "the first pass never started");
            assertEquals(1, executor.pending());
            executor.runAll();
            FxTestSupport.onFxRun(() -> { });      // drain the publish the walk queued

            assertNull(service.getLastResult(), "a pass walked on A must not publish once B is the index");
            assertEquals(0, published.get());
            assertNull(a.getObject(0).getPathClass(), "A's cells were not classified by the stale pass");

            // Pass 2 walks B and publishes, per branch, on both roots.
            CountDownLatch secondStarted = new CountDownLatch(1);
            service.setOnUpdateStarted(secondStarted::countDown);
            service.requestUpdate();
            assertTrue(secondStarted.await(FxTestSupport.timeoutSeconds(), TimeUnit.SECONDS), "the second pass never started");
            executor.runAll();
            FxTestSupport.onFxRun(() -> { });

            GatingEngine.AssignmentResult result = service.getLastResult();
            assertNotNull(result);
            assertEquals(1, published.get());
            assertEquals(5, result.getTally().total(low.getBranches().get(0)), "B: CD3 100 on five cells");
            assertEquals(5, result.getTally().total(low.getBranches().get(1)));
            assertEquals(5, result.getTally().total(high.getBranches().get(0)));
            assertEquals(5, result.getTally().total(high.getBranches().get(1)));
            assertEquals(low.getBranches().get(0).getCount(), result.getTally().total(low.getBranches().get(0)));
        } finally {
            service.shutdown();
        }
    }
}
