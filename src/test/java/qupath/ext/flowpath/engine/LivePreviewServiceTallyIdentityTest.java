package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the seam between the tree the live-preview walk actually classifies against and the
 * tree every consumer of the resulting {@link BranchTally} holds.
 * <p>
 * {@code LivePreviewService.submitGatingWork} deep-copies the gate tree before handing it to
 * {@code GatingEngine.assignAll}, and {@code GateNode.deepCopy()} constructs fresh
 * {@link Branch} objects. {@link BranchTally} is deliberately
 * {@link java.util.IdentityHashMap}-keyed — two branches can share a name, so a tally belongs
 * to <em>this</em> branch object — which means the tally the walk fills is keyed on the
 * copy's branches, while {@code FlowPathPane.buildAnalysisInput()} pairs it with the
 * <em>live</em> tree. {@code GateTree.transferCounts} copies only the raw {@code int} back;
 * it never reconciles identity. Every per-branch lookup in {@link PopulationStats} therefore
 * missed, and {@link BranchTally} returns 0 on a miss by design: every count, every
 * percentage and every bar in the shipped Analysis window read zero.
 * <p>
 * The test that first went through this path asserted only the branch-independent
 * {@code cellsTotal()}/{@code cellsInRegion()} numbers — which are non-zero either way — and
 * documented the identity gap in a comment rather than closing it. So this test asserts a
 * <b>per-branch</b> number, looked up with a {@link Branch} reachable from the caller's own
 * live tree: exactly the lookup the Analysis window performs.
 */
class LivePreviewServiceTallyIdentityTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void perBranchCountsSurviveTheDeepCopyTheWalkRunsOn() throws Exception {
        int n = 10;
        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        // GateTree's default (non-null, empty-range) quality filter, not the null one the
        // AnalysisFixtures use: LivePreviewService deep-copies the tree on every pass and
        // GateTree.deepCopy() dereferences the filter unconditionally.
        GateTree tree = new GateTree();
        tree.addRoot(root);

        ImageData<?> imageData = new ImageData<>(new WrappedBufferedImageServer(
                "live-preview-tally-identity-test",
                new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));

        LivePreviewService service = new LivePreviewService();
        GatingEngine.AssignmentResult result;
        try {
            service.setCellIndex(index);
            service.setMarkerStats(stats);
            service.setImageData(imageData);
            service.setGateTree(tree);

            CountDownLatch latch = new CountDownLatch(1);
            service.setOnUpdateComplete(latch::countDown);
            service.requestUpdate();

            assertTrue(latch.await(10, TimeUnit.SECONDS),
                    "the debounced gating pass did not complete in time");
            result = service.getLastResult();
            assertNotNull(result, "a completed pass must leave a result behind");
        } finally {
            service.shutdown();
        }

        // The branches the CALLER holds -- the live tree's, never the walked copy's. This
        // is the only handle FlowPathPane.buildAnalysisInput() has.
        Branch positive = root.getBranches().get(0);
        Branch negative = root.getBranches().get(1);
        BranchTally tally = result.getTally();

        assertEquals(5, tally.total(positive), "CD45 > 5.5 for cells 6..10");
        assertEquals(5, tally.clean(positive), "no quality filter excludes any of them");
        assertEquals(5, tally.total(negative), "CD45 <= 5.5 for cells 1..5");
        assertEquals(5, tally.clean(negative), "no quality filter excludes any of them");

        // And the same numbers through the class the Analysis window actually reads, paired
        // with the live tree exactly as buildAnalysisInput() pairs them.
        List<PopulationStats.Row> rows =
                PopulationStats.of(tree, tally, List.of(), null, null)
                        .rows(PopulationStats.Scope.WHOLE_SLIDE);
        assertEquals(2, rows.size(), "one row per branch of the single root gate");
        for (PopulationStats.Row row : rows) {
            assertEquals(5, row.count(), () -> row.path() + " must report its 5 cells, not 0");
            assertEquals(5, row.cleanCount(), () -> row.path() + " clean count");
            assertEquals(50.0, row.percentOfTotal(), 1e-9,
                    () -> row.path() + " is half the slide");
        }
    }

    /**
     * The rebind above is only as good as the pairing underneath it, and
     * {@code GateTree.pairBranches} pairs <b>positionally</b> — root <i>i</i>'s branch
     * <i>k</i> to root <i>i</i>'s branch <i>k</i>. The test above cannot see whether that
     * map is right: one root whose two branches hold five cells each stays correct under
     * any permutation of the pairing, so a rebind that swapped positive with negative, or
     * that folded two roots into one, would still report 5 everywhere.
     * <p>
     * Two roots on the <em>same</em> channel with <em>different</em> cuts is the input that
     * makes a mis-pairing visible and is also this codebase's named blind spot: both roots
     * emit byte-identical branch names ({@code "CD45+"}/{@code "CD45-"}), because
     * {@code GateNode} derives them from the channel alone, so nothing but position tells
     * them apart — and the four counts (10/10/5/15) are pairwise distinct, so any wrong
     * pairing lands on a number no assertion here accepts.
     */
    @Test
    void perBranchCountsSurviveTheDeepCopyForTwoRootsOnOneChannel() throws Exception {
        int n = 20;
        double[] cd45 = new double[n];
        for (int i = 0; i < n; i++) cd45[i] = i + 1;
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {cd45}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        // The AnalysisFixtures.twoRootsSameChannelInput() shape: one channel, two cuts.
        GateNode rootA = new GateNode("CD45", 10.5);
        rootA.setStatistic(Statistic.MEAN);
        rootA.setThresholdIsZScore(false);

        GateNode rootB = new GateNode("CD45", 15.5);
        rootB.setStatistic(Statistic.MEAN);
        rootB.setThresholdIsZScore(false);

        // GateTree's default (non-null) quality filter, as above: deepCopy() dereferences it.
        GateTree tree = new GateTree();
        tree.addRoot(rootA);
        tree.addRoot(rootB);

        ImageData<?> imageData = new ImageData<>(new WrappedBufferedImageServer(
                "live-preview-tally-identity-two-roots",
                new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));

        LivePreviewService service = new LivePreviewService();
        GatingEngine.AssignmentResult result;
        try {
            service.setCellIndex(index);
            service.setMarkerStats(stats);
            service.setImageData(imageData);
            service.setGateTree(tree);

            CountDownLatch latch = new CountDownLatch(1);
            service.setOnUpdateComplete(latch::countDown);
            service.requestUpdate();

            assertTrue(latch.await(10, TimeUnit.SECONDS),
                    "the debounced gating pass did not complete in time");
            result = service.getLastResult();
            assertNotNull(result, "a completed pass must leave a result behind");
        } finally {
            service.shutdown();
        }

        BranchTally tally = result.getTally();

        assertEquals(rootA.getBranches().get(0).getName(), rootB.getBranches().get(0).getName(),
                "the premise: the two roots' branches are indistinguishable by name, so only "
                        + "the positional pairing can tell them apart");

        // Looked up on the LIVE tree's Branch objects -- the copy the walk filled is gone.
        assertEquals(10, tally.total(rootA.getBranches().get(0)), "root A: CD45 > 10.5");
        assertEquals(10, tally.total(rootA.getBranches().get(1)), "root A: CD45 <= 10.5");
        assertEquals(5, tally.total(rootB.getBranches().get(0)), "root B: CD45 > 15.5");
        assertEquals(15, tally.total(rootB.getBranches().get(1)), "root B: CD45 <= 15.5");
    }

    /**
     * Exercises the discard arm of {@code submitGatingWork}'s
     * {@code catch (IllegalArgumentException)}, which nothing else in the suite reaches.
     * <p>
     * {@code addRoot}/{@code addChildGate} mutate the live {@code GateTree} <em>in place</em>,
     * so the {@code this.gateTree != originalTree} guard the publish step opens with cannot
     * see a structural edit at all — the reference is the same object. The walk, meanwhile,
     * ran on a copy taken before the edit, so its tally is keyed to a structure the live tree
     * no longer has. {@code BranchTally.rebindTo} refuses that rather than migrating half of
     * it; without the catch, the refusal is an exception thrown inside a
     * {@code Platform.runLater} that no {@code Future} is ever inspected for, and with a
     * lenient rebind in its place the window would show counts attributed to the wrong gates.
     * Either way the correct outcome is the same: drop this pass, keep the previous one on
     * screen, and let the edit's own queued pass produce the new numbers.
     * <p>
     * The ordering is forced rather than slept on: {@code submitGatingWork} deep-copies the
     * tree and only then enqueues {@code onUpdateStarted}, and the publish step is enqueued
     * later still (from the executor thread, after the walk). The FX queue is FIFO, so
     * mutating the tree inside {@code onUpdateStarted} lands strictly between the copy and
     * the publish on every run.
     */
    @Test
    void aStructuralChangeWhileAPassRunsIsDiscardedRatherThanPublished() throws Exception {
        int n = 10;
        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.addRoot(root);

        ImageData<?> imageData = new ImageData<>(new WrappedBufferedImageServer(
                "live-preview-tally-identity-structural-change",
                new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));

        AtomicReference<Throwable> escaped = new AtomicReference<>();
        AtomicReference<Thread.UncaughtExceptionHandler> previousHandler = new AtomicReference<>();
        FxTestSupport.onFxRun(() -> {
            previousHandler.set(Thread.currentThread().getUncaughtExceptionHandler());
            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> escaped.set(e));
        });

        LivePreviewService service = new LivePreviewService();
        try {
            service.setCellIndex(index);
            service.setMarkerStats(stats);
            service.setImageData(imageData);
            service.setGateTree(tree);

            // A first, undisturbed pass -- the one that must survive the second pass's failure.
            CountDownLatch firstPass = new CountDownLatch(1);
            service.setOnUpdateComplete(firstPass::countDown);
            service.requestUpdate();
            assertTrue(firstPass.await(10, TimeUnit.SECONDS),
                    "the first gating pass did not complete in time");
            GatingEngine.AssignmentResult firstResult = service.getLastResult();
            assertNotNull(firstResult);
            assertEquals(5, firstResult.getTally().total(root.getBranches().get(0)),
                    "the pass that must remain published carries real per-branch counts");

            // Second pass: grow the live tree a root after its copy was taken, before the
            // copy's tally is rebound onto it.
            GateNode addedMidPass = new GateNode("CD45", 7.5);
            addedMidPass.setStatistic(Statistic.MEAN);
            addedMidPass.setThresholdIsZScore(false);

            CountDownLatch mutated = new CountDownLatch(1);
            CountDownLatch secondPassPublished = new CountDownLatch(1);
            service.setOnUpdateStarted(() -> {
                tree.addRoot(addedMidPass);
                mutated.countDown();
            });
            service.setOnUpdateComplete(secondPassPublished::countDown);
            service.requestUpdate();

            assertTrue(mutated.await(10, TimeUnit.SECONDS),
                    "the second pass never started, so nothing was exercised");
            // An absence, bounded: this latch can only fall if a pass keyed to a structure
            // the tree no longer has was published anyway.
            assertFalse(secondPassPublished.await(3, TimeUnit.SECONDS),
                    "a pass whose tally cannot be rebound must not reach applyResult");

            // Drain anything still queued on the FX thread before reading the verdict.
            FxTestSupport.onFxRun(() -> { });

            assertNull(escaped.get(),
                    "the structural change must be handled, not thrown out of a runLater "
                            + "whose Future nobody inspects");
            assertSame(firstResult, service.getLastResult(),
                    "the discarded pass must leave the previous pass published, never a "
                            + "half-migrated tally");
        } finally {
            service.shutdown();
            FxTestSupport.onFxRun(() ->
                    Thread.currentThread().setUncaughtExceptionHandler(previousHandler.get()));
        }
    }
}
