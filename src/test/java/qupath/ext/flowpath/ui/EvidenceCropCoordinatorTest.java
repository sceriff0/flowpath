package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.EvidenceCrop;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateValues;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceCropCoordinatorTest {

    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void runAll() { while (!queue.isEmpty()) queue.remove(0).run(); }
    }

    static ReviewItem item(int n) {
        return item(n, n);
    }

    static ReviewItem item(int n, double applied) {
        return new ReviewItem(new ReviewItem.Key("s" + n, 0, "CD8"), "s" + n + ".tif", new GateNode("CD8", 1),
                List.of(ReviewItem.Flag.ON_PEAK), List.of("x"), GateValues.of(new double[]{applied}));
    }

    /** A 1 × 1 crop whose one pixel names the slide it was rendered for. */
    static EvidenceCrop.Crop cropOf(ReviewItem item) {
        return new EvidenceCrop.Crop(1, 1, new int[]{Integer.parseInt(item.key().slideId().substring(1))}, null);
    }

    @Test
    void theShownItemRendersThenTheNextThreeArePrefetchedAndAHitIsNotRerendered() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        List<String> rendered = new ArrayList<>();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx,
                item -> () -> { rendered.add(item.key().slideId()); return cropOf(item); });
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(0), List.of(item(1), item(2), item(3), item(4)), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals(List.of("s0", "s1", "s2", "s3"), rendered, "the shown item first, then exactly three ahead");
        assertEquals(1, shown.size());
        c.show(item(1), List.of(), shown::add);
        assertEquals(2, shown.size(), "a prefetched crop is shown at once");
        crops.runAll();
        assertEquals(4, rendered.size(), "and never rendered twice");
    }

    @Test
    void theCacheHoldsSixtyFourAndARenderFailureIsShownAsText() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx,
                item -> () -> { if (item.key().slideId().equals("s99")) throw new java.io.IOException("server gone"); return cropOf(item); });
        for (int i = 0; i < 70; i++) { c.show(item(i), List.of(), crop -> {}); crops.runAll(); fx.runAll(); }
        assertEquals(EvidenceCropCoordinator.CACHE_SIZE, c.cachedCount());
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(99), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals("server gone", shown.get(0).error());
    }

    @Test
    void anErrorIsCaughtAndShownToo() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, item -> () -> { throw new OutOfMemoryError(); });
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(0), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals("OutOfMemoryError", shown.get(0).error());
    }

    @Test
    void aCropLandingAfterTheUserMovedOnNeverShowsOnTheOtherItem() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, item -> () -> cropOf(item));
        List<EvidenceCrop.Crop> forA = new ArrayList<>(), forB = new ArrayList<>();
        c.show(item(1), List.of(), forA::add);
        c.show(item(2), List.of(), forB::add);
        crops.runAll();
        fx.runAll();
        assertTrue(forA.isEmpty(), "item 1's crop landed after item 2 was chosen");
        assertEquals(1, forB.size());
        assertEquals(2, forB.get(0).argb()[0]);
    }

    @Test
    void aMovedThresholdRerendersAndTheOldNumbersCropIsNotShown() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        List<Double> renderedAt = new ArrayList<>();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx,
                item -> () -> { renderedAt.add(item.applied().axis(0)[0]); return cropOf(item); });
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(1, 5.0), List.of(), crop -> fail("the crop for the old cut must not show"));
        c.show(item(1, 6.0), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals(List.of(5.0, 6.0), renderedAt, "same item, new applied values: a new render");
        assertEquals(1, shown.size());
    }

    @Test
    void cancellingLeavesNothingToShow() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, item -> () -> cropOf(item));
        c.show(item(1), List.of(), crop -> fail("nothing is selected any more"));
        c.cancel();
        crops.runAll();
        fx.runAll();
        assertEquals(1, c.cachedCount(), "the render is still cached for when the item is chosen again");
    }
}
