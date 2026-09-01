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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the region-breakdown fix made while wiring the Analysis window: a gating pass run
 * through {@link LivePreviewService} (the debounced background path every slider drag
 * takes) must carry the same per-region breakdown into its {@code BranchTally} that a
 * direct {@code GatingEngine.assignAll(..., regionOf, regionCount)} call would.
 * <p>
 * Before this fix, {@code submitGatingWork()} called the 4-arg {@code assignAll} overload,
 * which delegates with {@code regionOf = null, regionCount = 0} — so
 * {@code getLastResult().getTally().regionCount()} was permanently {@code 0} on every
 * annotated image, no matter what {@link #setRegions} was given. That silent zero would not
 * have thrown anywhere obvious: {@code FlowPathPane.buildAnalysisInput()}'s own
 * {@code tally.regionCount() != regionNames.size()} guard (added in the same change, for a
 * different race) would have caught the mismatch and quietly returned {@code null} —
 * turning a loud failure into "no data" on every annotated image, permanently, with nothing
 * in the UI to explain why. Hence a test, not just a guard and a comment.
 */
class LivePreviewServiceRegionTallyTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void gatingPassCarriesTheRegionBreakdownIntoTheTally() throws Exception {
        int n = 10;
        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        // Deliberately keeping GateTree's default, non-null QualityFilter (empty ranges,
        // so it excludes nothing) rather than the null this test's fixture siblings use:
        // LivePreviewService.submitGatingWork() deep-copies the tree on every pass, and
        // GateTree.deepCopy() dereferences the quality filter unconditionally. A null
        // filter is safe for AnalysisFixtures-style tests that call GatingEngine.assignAll
        // directly (never deepCopy()), but not through the live-preview path this test
        // exercises -- so this test uses the same non-null filter production code always
        // has, rather than the test-only shortcut.
        GateTree tree = new GateTree();
        tree.addRoot(root);

        // Cells 5-9 (CD45+) fall in region 0; cells 0-4 (CD45-) are in no region (-1) --
        // so a correct tally reports all 5 CD45+ cells in region 0 and no CD45- cells
        // anywhere, while a regionOf silently dropped to null would report 0 in region 0
        // regardless (every cell would fall through to the -1/no-region case).
        int[] regionOf = new int[n];
        for (int i = 0; i < n; i++) regionOf[i] = i >= 5 ? 0 : -1;

        ImageData<?> imageData = new ImageData<>(new WrappedBufferedImageServer(
                "live-preview-region-test", new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));

        LivePreviewService service = new LivePreviewService();
        try {
            service.setCellIndex(index);
            service.setMarkerStats(stats);
            service.setImageData(imageData);
            service.setGateTree(tree);
            service.setRegions(regionOf, 1);

            CountDownLatch latch = new CountDownLatch(1);
            service.setOnUpdateComplete(latch::countDown);
            service.requestUpdate();

            assertTrue(latch.await(10, TimeUnit.SECONDS),
                    "the debounced gating pass did not complete in time");

            GatingEngine.AssignmentResult result = service.getLastResult();
            assertNotNull(result, "a completed pass must leave a result behind");

            // The cheaper check first: the tally's own regionCount. Not sufficient alone --
            // see the per-cell assertion below, which is what actually distinguishes
            // "regionOf reached the walk" from "only regionCount happened to match" (a
            // regionOf silently dropped to null still leaves regionCount, the tally
            // constructor's own parameter, untouched).
            assertEquals(1, result.getTally().regionCount(),
                    "the tally's region count must match what setRegions(regionOf, 1) gave the walk");

            // Asserted at the cell-level denominator (cellsInRegion), not a branch's own
            // inRegion(...): submitGatingWork() deep-copies the tree before walking it, so
            // BranchTally's IdentityHashMap is keyed on that copy's Branch instances --
            // GateTree.transferCounts (called right after the walk) copies only the raw
            // Branch.getCount() int back onto the *original* tree's branches, never branch
            // identity. There is therefore no Branch reference reachable from outside
            // LivePreviewService that is == a key this tally actually holds, short of
            // reflecting into a private field or adding a new accessor -- which would be
            // restructuring the class for this test, not fixing the defect. cellsInRegion
            // is recorded once per cell directly from the regionOf array
            // (BranchTally.recordCell, called for every cell after the walk), independent
            // of any Branch, so it pins exactly the same failure mode: if regionOf were
            // silently dropped to null, every cell's region would fall through to -1 inside
            // GatingEngine.assignAll regardless of what regionCount was still given, and
            // this would read 0.
            assertEquals(5, result.getTally().cellsInRegion(0),
                    "all 5 CD45+ cells carry region 0 from regionOf -- if regionOf were "
                    + "silently dropped this would read 0 even with regionCount correct");
            assertEquals(10, result.getTally().cellsTotal(), "every cell was walked");
            assertEquals(5, result.getTally().cleanCellsInRegion(0),
                    "none of region 0's cells were excluded by the (default, empty) quality filter");
        } finally {
            service.shutdown();
        }
    }
}
