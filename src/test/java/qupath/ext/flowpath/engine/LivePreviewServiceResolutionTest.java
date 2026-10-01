package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LivePreviewServiceResolutionTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void theLivePassGatesTheOpenSlideWithItsAppliedThresholdsPerBranch() throws Exception {
        double[] cd45 = new double[20];
        for (int i = 0; i < 20; i++) cd45[i] = i + 1;
        CellIndex index = Cells.columns(List.of("CD45"), new double[][]{cd45}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(20));

        GateNode a = new GateNode("CD45", 10.5);
        a.setStatistic(Statistic.MEAN);
        GateNode b = new GateNode("CD45", 15.5);
        b.setStatistic(Statistic.MEAN);
        b.setCorrectStaining(false);
        GateTree tree = new GateTree();
        tree.addRoot(a);
        tree.addRoot(b);
        tree.setReferenceSlideId("ref");

        Alignment shift = Alignment.auto(30, 0.01);
        double appliedA = shift.apply(10.5);
        long expectedA = java.util.Arrays.stream(cd45).filter(v -> v >= appliedA).count();
        assertNotEquals(10, expectedA, "fixture check: the alignment moves root A's cut");

        LivePreviewService service = new LivePreviewService();
        GatingEngine.AssignmentResult result;
        try {
            service.setCellIndex(index);
            service.setMarkerStats(stats);
            service.setImageData(new ImageData<>(new WrappedBufferedImageServer("resolution-test",
                    new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB))));
            service.setSlideContext("s1", (slide, column) -> "CD45".equals(column) ? shift : null);
            service.setGateTree(tree);
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnUpdateComplete(latch::countDown);
            service.requestUpdate();
            assertTrue(latch.await(FxTestSupport.timeoutSeconds(), TimeUnit.SECONDS));
            result = service.getLastResult();
        } finally {
            service.shutdown();
        }

        assertEquals(expectedA, result.getTally().total(a.getBranches().get(0)), "root A, corrected, on the LIVE branch");
        assertEquals(20 - expectedA, result.getTally().total(a.getBranches().get(1)));
        assertEquals(5, result.getTally().total(b.getBranches().get(0)), "root B, correction off: 16..20");
        assertEquals(expectedA, a.getBranches().get(0).getCount(), "transferCounts carried the same number");
        assertEquals(10.5, a.getThreshold(), "the live tree keeps its reference number");
    }
}
