package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.ColorUtils;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real {@link LivePreviewService} over a real {@code PathObjectHierarchy}, rather than
 * a hand-written copy of its comparison formula (that lives in {@code PhenotypeClassWriterTest
 * .aCallersOwnMemoryCatchesAColourChangeEvenAfterASecondWriterAgrees}, which pins the exact
 * mechanism but cannot catch a regression in how {@code LivePreviewService} actually wires
 * {@code lastAppliedColors} in). This class is the guarantee itself: the service must still
 * repaint and fire a hierarchy event after a second writer — a background batch run gating a
 * different slide that shares a phenotype name — has already mutated the shared {@code PathClass}
 * cache to a colour that happens to match what the service is about to apply.
 */
class LivePreviewServiceRecolorTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static CellIndex buildIndex(String channel) {
        return Cells.of(4).marker(channel, i -> i).area(100.0).build();
    }

    private static LivePreviewService startedService(CellIndex index, GateTree tree,
                                                      ImageData<BufferedImage> data) throws InterruptedException {
        LivePreviewService service = new LivePreviewService();
        service.setCellIndex(index);
        service.setMarkerStats(MarkerStats.compute(index, null));
        service.setImageData(data);
        service.setGateTree(tree);
        CountDownLatch latch = new CountDownLatch(1);
        service.setOnUpdateComplete(latch::countDown);
        service.requestUpdate();
        assertTrue(latch.await(FxTestSupport.timeoutSeconds(), TimeUnit.SECONDS), "the first pass never completed");
        return service;
    }

    @Test
    void switchingColorRootFiresAndRepaintsAfterASecondWriterAlreadyMatches() throws Exception {
        String channel = "LPSRT1_CD3";
        CellIndex index = buildIndex(channel);

        GateTree tree = new GateTree();
        GateNode rootA = new GateNode(channel, 1.0);
        rootA.setStatistic(Statistic.MEAN);
        rootA.setPositiveColor(0xFF0000); // red
        GateNode rootB = new GateNode(channel, 1.0);
        rootB.setStatistic(Statistic.MEAN);
        rootB.setPositiveColor(0x0000FF); // blue
        tree.addRoot(rootA);
        tree.addRoot(rootB);

        ImageData<BufferedImage> data = new ImageData<>(new WrappedBufferedImageServer(
                "live-preview-recolor-1", new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        data.getHierarchy().addObjects(List.of(index.getObjects()));
        AtomicInteger events = new AtomicInteger();
        data.getHierarchy().addListener(e -> events.incrementAndGet());

        LivePreviewService service = startedService(index, tree, data);
        try {
            GatingEngine.AssignmentResult result = service.getLastResult();
            assertTrue(events.get() > 0, "the first pass fires at least one hierarchy event");
            // Default colour root (-1) is the last contributing root: rootB, blue.
            int blue = ColorUtils.toQuPathColor(0x0000FF);
            int red = ColorUtils.toQuPathColor(0xFF0000);
            assertEquals(blue, index.getObject(3).getPathClass().getColor(),
                    "cell 3 (CD3=3, positive on both roots) starts in root -1's colour");

            // A second writer -- e.g. a background batch run gating a different slide that shares
            // this phenotype name -- writes root 0's (red) colour directly to the shared
            // PathClass cache, exactly as BatchRunner.writeBack would, on a CellIndex of its own.
            CellIndex otherSlide = buildIndex(channel);
            PhenotypeClassWriter.apply(result, otherSlide, 0);
            assertEquals(red, index.getObject(3).getPathClass().getColor(),
                    "fixture check: the second writer mutated the SHARED class this live index's cells also reference");

            // The user now switches the viewer to root 0 -- the exact colour (red) the second
            // writer already wrote to the shared cache, which is precisely the coincidence that
            // fooled PhenotypeClassWriter.apply()'s own shared-state signal (see its javadoc).
            int beforeSwitch = events.get();
            FxTestSupport.onFxRun(() -> service.setColorRootIndex(0));
            FxTestSupport.onFxRun(() -> { }); // drain recolorCells' own queued runLater

            assertTrue(events.get() > beforeSwitch,
                    "(a): the service's own memory must still detect this as a change and fire, "
                    + "even though the shared cache already agreed with the new colour");
            assertEquals(red, index.getObject(3).getPathClass().getColor(), "the cell ends up in the service's chosen colour");
        } finally {
            service.shutdown();
        }
    }

    /**
     * Pins the unconditional fire on {@code recolorCells} (a deliberate user click): switching
     * between two different roots whose colours are identical must still fire, even though no
     * plan-equality check -- against the shared cache OR against the service's own memory --
     * would ever call that a change.
     */
    @Test
    void recolorCellsAlwaysFiresEvenWhenTheColourPlanDidNotChange() throws Exception {
        String channel = "LPSRT2_CD3";
        CellIndex index = buildIndex(channel);

        GateTree tree = new GateTree();
        GateNode rootB = new GateNode(channel, 1.0);
        rootB.setStatistic(Statistic.MEAN);
        rootB.setPositiveColor(0x0000FF); // blue
        GateNode rootC = new GateNode(channel, 1.0);
        rootC.setStatistic(Statistic.MEAN);
        rootC.setPositiveColor(0x0000FF); // also blue: a genuinely different root, identical colour plan
        tree.addRoot(rootB);
        tree.addRoot(rootC);

        ImageData<BufferedImage> data = new ImageData<>(new WrappedBufferedImageServer(
                "live-preview-recolor-2", new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        data.getHierarchy().addObjects(List.of(index.getObjects()));
        AtomicInteger events = new AtomicInteger();
        data.getHierarchy().addListener(e -> events.incrementAndGet());

        LivePreviewService service = startedService(index, tree, data);
        try {
            GatingEngine.AssignmentResult result = service.getLastResult();
            // Fixture check: root 0 and root -1 (the last root, root 1) really do compute the
            // identical colour plan, so this switch is a pure no-op by colour.
            assertEquals(PhenotypeClassWriter.colorPlan(result, 0), PhenotypeClassWriter.colorPlan(result, -1));

            int beforeSwitch = events.get();
            FxTestSupport.onFxRun(() -> service.setColorRootIndex(0));
            FxTestSupport.onFxRun(() -> { }); // drain recolorCells' own queued runLater

            assertTrue(events.get() > beforeSwitch,
                    "recolorCells fires unconditionally on a deliberate colour-root switch, "
                    + "even when the colour plan itself did not change");
        } finally {
            service.shutdown();
        }
    }

    /**
     * The other two tests above reach the scenario through {@code setColorRootIndex} →
     * {@code recolorCells}, which fires unconditionally regardless of {@code lastAppliedColors} —
     * so neither would fail if {@code lastAppliedColors} were deleted and {@code applyResult}
     * reverted to a bare {@code if (changed) fire}. This test never calls
     * {@code setColorRootIndex} at all: it goes through {@code requestUpdate()} → {@code
     * applyResult} only, with the colour change coming from a live-tree edit (a {@code Branch}'s
     * colour, not the selected root) between two gating passes. A second writer mutates the
     * shared {@code PathClass} cache to the edited colour before the service's own next pass
     * lands, which is what makes {@code PhenotypeClassWriter.apply}'s own identity/recolour
     * signal report {@code false} for every cell — the ONLY thing that can still make
     * {@code applyResult} fire is comparing the new plan against {@code lastAppliedColors}.
     * <p>
     * The colour the cell ends up in is not asserted as proof of anything: it already equals the
     * edited colour the moment the second writer runs, because the {@code PathClass} instance is
     * shared — only the hierarchy-event count can tell whether the service's own pass actually
     * did the work.
     */
    @Test
    void aLiveTreeColourEditFiresOnTheNextPassEvenAfterASecondWriterAlreadyMatches() throws Exception {
        String channel = "LPSRT3_CD3";
        CellIndex index = buildIndex(channel);

        GateTree tree = new GateTree();
        GateNode root = new GateNode(channel, 1.0);
        root.setStatistic(Statistic.MEAN);
        root.setPositiveColor(0x0000FF); // blue
        tree.addRoot(root);

        ImageData<BufferedImage> data = new ImageData<>(new WrappedBufferedImageServer(
                "live-preview-recolor-3", new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        data.getHierarchy().addObjects(List.of(index.getObjects()));
        AtomicInteger events = new AtomicInteger();
        data.getHierarchy().addListener(e -> events.incrementAndGet());

        LivePreviewService service = startedService(index, tree, data);
        try {
            int blue = ColorUtils.toQuPathColor(0x0000FF);
            int red = ColorUtils.toQuPathColor(0xFF0000);
            assertEquals(blue, index.getObject(3).getPathClass().getColor(), "pass 1 painted the branch's colour, blue");

            // The user edits the branch's own colour on the LIVE tree -- root -1's remains the
            // channel/threshold selection, only the display colour changes.
            root.setPositiveColor(0xFF0000);

            // A second writer -- a batch launched right after the edit -- gates a fresh CellIndex
            // (a different slide) against the SAME, now-edited tree and writes red to the shared
            // PathClass cache before the service's own next pass has even started.
            CellIndex otherSlide = buildIndex(channel);
            GatingEngine.AssignmentResult batchResult = GatingEngine.assignAll(tree, otherSlide,
                    MarkerStats.compute(otherSlide, null));
            PhenotypeClassWriter.apply(batchResult, otherSlide, -1);
            assertEquals(red, index.getObject(3).getPathClass().getColor(),
                    "fixture check: the shared class this live index's cells reference is already red, "
                    + "before the service's own next pass has run at all");

            // The service's own next pass now walks the edited tree. Its own
            // PhenotypeClassWriter.apply call will find every cell's class reference unchanged
            // (same shared object) and already the right colour (the second writer just set it),
            // so its own signal reports no change -- comparing against lastAppliedColors (still
            // blue, from pass 1) is the only thing left that can trigger the event.
            int beforePass2 = events.get();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnUpdateComplete(latch::countDown);
            service.requestUpdate();
            assertTrue(latch.await(FxTestSupport.timeoutSeconds(), TimeUnit.SECONDS), "the second pass never completed");

            assertTrue(events.get() > beforePass2,
                    "applyResult must fire when its OWN remembered colours (still blue) differ from "
                    + "the new plan (red), even though PhenotypeClassWriter.apply's own shared-state "
                    + "signal reports no change");
        } finally {
            service.shutdown();
        }
    }
}
