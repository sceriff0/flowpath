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

    static final Object MODEL = new Object();

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
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL,
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
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL,
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
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL, item -> () -> { throw new OutOfMemoryError(); });
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(0), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals("OutOfMemoryError", shown.get(0).error());
    }

    @Test
    void aCropLandingAfterTheUserMovedOnNeverShowsOnTheOtherItem() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL, item -> () -> cropOf(item));
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
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL,
                item -> () -> { renderedAt.add(item.applied().axis(0)[0]); return cropOf(item); });
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(1, 5.0), List.of(), crop -> fail("the crop for the old cut must not show"));
        c.show(item(1, 6.0), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals(List.of(6.0), renderedAt, "same item, new applied values: a new key, and the old one's read is skipped");
        assertEquals(1, shown.size());
    }

    @Test
    void aReadAlreadyUnderWayWhenTheUserMovesOnIsCachedButNotShown() {
        ManualExecutor fx = new ManualExecutor();
        List<Runnable> queued = new ArrayList<>();
        EvidenceCropCoordinator[] ref = new EvidenceCropCoordinator[1];
        List<EvidenceCrop.Crop> forB = new ArrayList<>();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(queued::add, fx, () -> MODEL, item -> () -> {
            if (item.key().slideId().equals("s1")) ref[0].show(item(2), List.of(), forB::add);   // moved on mid-read
            return cropOf(item);
        });
        ref[0] = c;
        c.show(item(1), List.of(), crop -> fail("item 1 was left while it was read"));
        queued.remove(0).run();
        fx.runAll();
        assertEquals(1, c.cachedCount(), "item 1's finished read is kept for when it is chosen again");
        assertTrue(forB.isEmpty());
    }

    @Test
    void cancellingLeavesNothingToShow() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL, item -> () -> cropOf(item));
        c.show(item(1), List.of(), crop -> fail("nothing is selected any more"));
        c.cancel();
        crops.runAll();
        fx.runAll();
        assertEquals(0, c.cachedCount(), "cancelled before its read started: not read at all");
    }

    @Test
    void aFailureIsShownButNotCachedSoTheNextRequestTriesAgain() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        int[] calls = {0};
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL, item -> () -> {
            if (calls[0]++ == 0) throw new java.io.IOException("server busy");
            return cropOf(item);
        });
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(1), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals("server busy", shown.get(0).error());
        assertEquals(0, c.cachedCount());
        c.show(item(1), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals(2, calls[0], "rendered again");
        assertTrue(shown.get(1).ok());
    }

    @Test
    void aNewAlignmentModelIsANewKey() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        Object[] model = {new Object()};
        List<String> rendered = new ArrayList<>();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> model[0],
                item -> () -> { rendered.add(item.key().slideId()); return cropOf(item); });
        c.show(item(1), List.of(), crop -> {});
        crops.runAll();
        fx.runAll();
        c.show(item(1), List.of(), crop -> {});
        assertTrue(crops.queue.isEmpty(), "same model: a hit");
        model[0] = new Object();   // a rescore landed: the range may have moved
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(1), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals(List.of("s1", "s1"), rendered);
        assertEquals(1, shown.size());
    }

    @Test
    void fastSteppingSkipsTheReadsOfItemsSteppedPast() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        List<String> rendered = new ArrayList<>();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL,
                item -> () -> { rendered.add(item.key().slideId()); return cropOf(item); });
        c.show(item(0), List.of(item(1), item(2), item(3)), crop -> {});
        c.show(item(10), List.of(), crop -> {});
        crops.runAll();
        fx.runAll();
        assertEquals(List.of("s10"), rendered, "the four earlier jobs left the window before they started");
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(1), List.of(), shown::add);
        crops.runAll();
        fx.runAll();
        assertEquals(1, shown.size(), "a skipped item is read when asked for again");
    }

    @Test
    void anItemAskedForAgainWhileItsSkipIsLandingIsStillRead() {
        ManualExecutor crops = new ManualExecutor(), fx = new ManualExecutor();
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL, item -> () -> cropOf(item));
        c.show(item(1), List.of(), crop -> {});
        c.show(item(2), List.of(), crop -> {});
        crops.runAll();                          // item 1's job is skipped; its removal is queued on fx
        List<EvidenceCrop.Crop> shown = new ArrayList<>();
        c.show(item(1), List.of(), shown::add);   // still in flight: no second job queued here
        fx.runAll();
        crops.runAll();
        fx.runAll();
        assertEquals(1, shown.size());
        assertEquals(1, shown.get(0).argb()[0]);
    }

    @Test
    void aShutDownExecutorLeavesNothingInFlight() {
        ManualExecutor fx = new ManualExecutor();
        boolean[] reject = {true};
        List<Runnable> accepted = new ArrayList<>();
        Executor crops = command -> {
            if (reject[0]) throw new java.util.concurrent.RejectedExecutionException("shut down");
            accepted.add(command);
        };
        EvidenceCropCoordinator c = new EvidenceCropCoordinator(crops, fx, () -> MODEL, item -> () -> cropOf(item));
        assertDoesNotThrow(() -> c.show(item(1), List.of(), crop -> {}));
        reject[0] = false;
        c.show(item(1), List.of(), crop -> {});
        assertEquals(1, accepted.size(), "the rejected key was not left in flight");
    }
}
