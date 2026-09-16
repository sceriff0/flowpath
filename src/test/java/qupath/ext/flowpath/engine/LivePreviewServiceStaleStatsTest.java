package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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

        @Override public void execute(Runnable command) { queue.add(command); }
        void runAll() { while (!queue.isEmpty()) queue.poll().run(); }
        int pending() { return queue.size(); }
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
}
