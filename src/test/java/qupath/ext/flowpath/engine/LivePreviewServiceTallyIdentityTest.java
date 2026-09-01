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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
