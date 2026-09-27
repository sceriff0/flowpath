package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.batch.BatchRunner;
import qupath.ext.flowpath.batch.BatchSlide;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class BatchRunCoordinatorTest {

    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void runNext() { queue.remove(0).run(); }
        void runAll() { while (!queue.isEmpty()) runNext(); }
    }

    private static BatchSlide slide(String id) {
        ImageData<BufferedImage> data = new ImageData<>(new WrappedBufferedImageServer(id,
                new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        data.getHierarchy().addObjects(Cells.of(10).marker("CD3", i -> i).marker("CD8", i -> i).detections());
        return new BatchSlide() {
            @Override public String id() { return id; }
            @Override public String name() { return id + ".tif"; }
            @Override public ImageData<BufferedImage> read() { return data; }
            @Override public void save(ImageData<BufferedImage> d) {}
        };
    }

    private static final class Host implements BatchRunCoordinator.Host {
        final List<String> events = new ArrayList<>();
        BatchRunCoordinator.Outcome outcome;
        @Override public void progress(int done, int total, String name) { events.add(done + "/" + total + " " + name); }
        @Override public void finished(BatchRunCoordinator.Outcome o) { outcome = o; events.add("finished"); }
        @Override public void failed(Throwable error) { events.add("failed " + error.getMessage()); }
    }

    private static BatchRunner.Settings settings(Path dir) {
        return new BatchRunner.Settings(GateTreeFixtures.twoRootsOnCd3AndCd8(3, 5), AlignmentLookup.NONE, dir.toFile(), null, false);
    }

    @Test
    void oneSlidePerTaskThenTheOutputsAndOneRunAtATime(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        List<String> written = new ArrayList<>();
        BatchRunCoordinator c = new BatchRunCoordinator(bg, fx, host);
        c.run(List.of(slide("a"), slide("b")), settings(dir), (d, r) -> written.add(r.size() + " results"));
        assertTrue(c.running());
        c.run(List.of(slide("z")), settings(dir), (d, r) -> fail("a second run is ignored"));
        assertEquals(1, bg.queue.size(), "one slide per task");
        bg.runNext(); fx.runAll();
        assertEquals(1, bg.queue.size(), "the next slide is submitted only after the previous one landed");
        bg.runNext(); fx.runAll();
        bg.runAll(); fx.runAll();
        assertEquals(List.of("1/2 a.tif", "2/2 b.tif", "finished"), host.events);
        assertEquals(List.of("2 results"), written);
        assertFalse(c.running());
        assertFalse(host.outcome.cancelled());
        assertEquals(2, host.outcome.total());
        assertEquals(dir.toFile(), host.outcome.outputDir());
    }

    @Test
    void cancelStopsBeforeTheNextSlideAndStillWritesWhatWasGated(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        List<Integer> written = new ArrayList<>();
        BatchRunCoordinator c = new BatchRunCoordinator(bg, fx, host);
        c.run(List.of(slide("a"), slide("b")), settings(dir), (d, r) -> written.add(r.size()));
        bg.runNext();
        c.cancel();
        fx.runAll(); bg.runAll(); fx.runAll();
        assertTrue(host.outcome.cancelled());
        assertEquals(1, host.outcome.results().size());
        assertEquals(List.of(1), written, "the slide already gated is still written out");
        assertFalse(c.running());
    }

    @Test
    void aCancelArrivingAfterTheLastSlideIsNotReportedAsACancelledRun(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        BatchRunCoordinator c = new BatchRunCoordinator(bg, fx, host);
        c.run(List.of(slide("a")), settings(dir), (d, r) -> {});
        bg.runNext();
        c.cancel();
        fx.runAll(); bg.runAll(); fx.runAll();
        assertFalse(host.outcome.cancelled(), "every slide was gated");
    }

    @Test
    void anErrorWhileWritingIsReportedAndTheButtonIsFreed(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        BatchRunCoordinator c = new BatchRunCoordinator(bg, fx, host);
        c.run(List.of(slide("a")), settings(dir), (d, r) -> { throw new OutOfMemoryError("Java heap space"); });
        bg.runAll(); fx.runAll(); bg.runAll(); fx.runAll();
        assertEquals("failed Java heap space", host.events.get(host.events.size() - 1));
        assertFalse(c.running());

        c.run(List.of(slide("a")), settings(dir), (d, r) -> {});
        assertTrue(c.running(), "a failed run does not block the next one");
    }

    private static int count(qupath.ext.flowpath.batch.BatchResult r, int rootIndex, String branch) {
        return r.stats().rows(qupath.ext.flowpath.model.PopulationStats.Scope.WHOLE_SLIDE).stream()
                .filter(row -> row.rootIndex() == rootIndex && row.branchName().equals(branch))
                .findFirst().orElseThrow().count();
    }

    @Test
    void aTreeEditMadeMidRunNeverReachesTheRun(@TempDir Path dir) {
        // CD3 (0), CD8 (1), a second CD3 root (2): two roots on one channel.
        GateTree live = GateTreeFixtures.twoRootsOnCd3AndCd8(3, 5);
        qupath.ext.flowpath.model.GateNode second = new qupath.ext.flowpath.model.GateNode("CD3", 3);
        second.setStatistic(qupath.ext.flowpath.model.Statistic.MEAN);
        live.addRoot(second);
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        BatchRunCoordinator c = new BatchRunCoordinator(bg, fx, host);
        c.run(List.of(slide("a"), slide("b")),
                new BatchRunner.Settings(live, AlignmentLookup.NONE, dir.toFile(), null, false), (d, r) -> {});
        bg.runNext(); fx.runAll();

        live.getRoots().get(0).setThreshold(8);      // the user edits while slide b waits
        live.getRoots().get(2).setThreshold(9);
        bg.runNext(); fx.runAll(); bg.runAll(); fx.runAll();

        qupath.ext.flowpath.batch.BatchResult b = host.outcome.results().get(1);
        assertEquals(7, count(b, 0, "CD3+"), "slide b is gated at the threshold the run started with (CD3 = 3..9)");
        assertEquals(7, count(b, 2, "CD3+"), "and so is the same-channel sibling");
    }

    @Test
    void closeBetweenASlideAndItsLandingReportsNothingAndSubmitsNothing(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        BatchRunCoordinator c = new BatchRunCoordinator(bg, fx, host);
        c.run(List.of(slide("a"), slide("b")), settings(dir), (d, r) -> fail("no outputs after close"));
        bg.runNext();                 // slide a gated; its landing is queued on the FX thread
        c.close();
        fx.runAll();

        assertEquals(List.of(), host.events, "no progress, finished or failed");
        assertTrue(bg.queue.isEmpty(), "no further slide or write submitted");
        assertTrue(fx.queue.isEmpty());
        c.run(List.of(slide("z")), settings(dir), (d, r) -> fail("a closed coordinator starts nothing"));
        assertTrue(bg.queue.isEmpty());
    }

    // ---- starting a run: refusal and confirmation ------------------------------------------

    @Test
    void aForeignTreeIsRefusedWithTheForeignTreeMessage() {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(3, 5);
        tree.setSlideNames(Map.of("a", "a slide of another project.tif"));
        BatchRunCoordinator.Start start = BatchRunCoordinator.check(tree, List.of(slide("a"), slide("b")), 0);
        BatchRunCoordinator.Refused refused = assertInstanceOf(BatchRunCoordinator.Refused.class, start);
        assertTrue(refused.message().startsWith(CohortSession.FOREIGN_TREE), refused.message());
    }

    @Test
    void hasEnabledGateIsTheOneRuleForTheButtonAndTheCheck() {
        assertFalse(BatchRunCoordinator.hasEnabledGate(new GateTree()));
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(3, 5);
        assertTrue(BatchRunCoordinator.hasEnabledGate(tree));
        tree.getRoots().get(0).setEnabled(false);
        assertTrue(BatchRunCoordinator.hasEnabledGate(tree), "one enabled root is enough");
        tree.getRoots().get(1).setEnabled(false);
        assertFalse(BatchRunCoordinator.hasEnabledGate(tree));
    }

    @Test
    void aTreeWithNoEnabledGateOrAProjectWithNoImagesIsRefused() {
        GateTree empty = new GateTree();
        assertInstanceOf(BatchRunCoordinator.Refused.class, BatchRunCoordinator.check(empty, List.of(slide("a")), 0));
        GateTree disabled = GateTreeFixtures.twoRootsOnCd3AndCd8(3, 5);
        disabled.getRoots().forEach(r -> r.setEnabled(false));
        assertInstanceOf(BatchRunCoordinator.Refused.class, BatchRunCoordinator.check(disabled, List.of(slide("a")), 0));
        assertInstanceOf(BatchRunCoordinator.Refused.class,
                BatchRunCoordinator.check(GateTreeFixtures.twoRootsOnCd3AndCd8(3, 5), List.of(), 0));
    }

    @Test
    void theConfirmationSaysHowManyItemsAreStillUnreviewedIfAny() {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(3, 5);
        List<BatchSlide> slides = List.of(slide("a"), slide("b"));

        String none = assertInstanceOf(BatchRunCoordinator.Confirm.class,
                BatchRunCoordinator.check(tree, slides, 0)).message();
        assertTrue(none.startsWith("Gate all 2 slide(s)"), none);
        assertFalse(none.contains("unreviewed"), none);

        String one = assertInstanceOf(BatchRunCoordinator.Confirm.class,
                BatchRunCoordinator.check(tree, slides, 1)).message();
        assertTrue(one.contains("1 review item is still unreviewed"), one);

        String five = assertInstanceOf(BatchRunCoordinator.Confirm.class,
                BatchRunCoordinator.check(tree, slides, 5)).message();
        assertTrue(five.contains("5 review items are still unreviewed"), five);
        assertTrue(five.contains("the slides they concern run on the thresholds shown now"), five);
    }
}
