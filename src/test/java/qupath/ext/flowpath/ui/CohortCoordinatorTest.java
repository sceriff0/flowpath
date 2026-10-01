package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.SlideSource;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class CohortCoordinatorTest {

    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void runNext() { queue.remove(0).run(); }
        void runAll() { while (!queue.isEmpty()) runNext(); }
    }

    private static SlideSource source(String id) {
        return new SlideSource() {
            @Override public String id() { return id; }
            @Override public String name() { return id + ".tif"; }
            @Override public PathObjectHierarchy readHierarchy() {
                PathObjectHierarchy h = new PathObjectHierarchy();
                h.addObjects(Cells.of(60).marker("CD3", i -> i).marker("CD8", i -> i).detections());
                return h;
            }
        };
    }

    private static final class RecordingHost implements CohortCoordinator.Host {
        final List<String> events = new ArrayList<>();
        @Override public void sampled(CohortSampler.Outcome o) { events.add("sampled " + o.slideId()); }
        @Override public void samplingFinished() { events.add("finished"); }
        @Override public void scored(boolean changed) { events.add("scored"); }
        @Override public void scoringFailed() { events.add("failed"); }
        @Override public void cacheSettled(Path file, AlignmentModel.Cache cache, int sampledCellsPerSlide) {
            events.add("cache " + file.getFileName() + " " + cache.slides().keySet().stream().sorted().toList());
        }
    }

    @Test
    void theSamplerYieldsToOtherBackgroundWorkBetweenSlides() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        CohortSession session = new CohortSession();
        session.setProjectSlides(List.of(new CohortSession.SlideRef("a", "a.tif"), new CohortSession.SlideRef("b", "b.tif")));
        RecordingHost host = new RecordingHost();
        CohortCoordinator c = new CohortCoordinator(session, bg, fx, host);

        c.start(List.of(source("a"), source("b")), GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2), 0);
        assertTrue(c.sampling());
        assertEquals(1, bg.queue.size(), "one slide per task");
        List<String> order = new ArrayList<>();
        bg.execute(() -> order.add("a gating pass"));
        bg.runNext();                  // slide a
        fx.runAll();                   // lands a, queues slide b behind the gating pass
        bg.runNext();                  // the gating pass runs before slide b
        order.add("then slide b");
        bg.runAll();
        fx.runAll();
        assertEquals(List.of("a gating pass", "then slide b"), order);
        assertEquals(List.of("sampled a", "sampled b", "finished"), host.events);
        assertFalse(c.sampling());
        assertEquals(2, session.samples().size());
    }

    @Test
    void aNewStartSupersedesTheRunInFlight() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        CohortSession session = new CohortSession();
        session.setProjectSlides(List.of(new CohortSession.SlideRef("a", "a.tif"), new CohortSession.SlideRef("b", "b.tif")));
        RecordingHost host = new RecordingHost();
        CohortCoordinator c = new CohortCoordinator(session, bg, fx, host);
        c.start(List.of(source("a"), source("b")), GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2), 0);
        bg.runNext();
        c.start(List.of(source("b")), GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2), 0);
        bg.runAll();
        fx.runAll();
        bg.runAll();
        fx.runAll();
        assertEquals(List.of("sampled b", "finished"), host.events, "the superseded slide a never lands");
    }

    /** Final review item 4: sampling one re-included slide keeps every other slide's sample. */
    @Test
    void sampleMoreKeepsTheExistingSamplesAndLandsTheNewOne() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        CohortSession session = new CohortSession();
        session.setProjectSlides(List.of(new CohortSession.SlideRef("a", "a.tif"), new CohortSession.SlideRef("b", "b.tif"),
                new CohortSession.SlideRef("c", "c.tif")));
        RecordingHost host = new RecordingHost();
        CohortCoordinator c = new CohortCoordinator(session, bg, fx, host);
        c.start(List.of(source("a"), source("b")), GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2), 0);
        bg.runAll();
        fx.runAll();
        bg.runAll();
        fx.runAll();
        var sampleA = session.sample("a");
        assertNotNull(sampleA);

        c.sampleMore(List.of(source("c")), GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2), 0, null);
        assertTrue(c.sampling(), "joins the same sampling flag BusyState reads");
        assertSame(sampleA, session.sample("a"), "nothing is cleared when the run starts");
        assertNotNull(session.sample("b"));
        bg.runAll();
        fx.runAll();
        bg.runAll();
        fx.runAll();
        assertFalse(c.sampling());
        assertSame(sampleA, session.sample("a"));
        assertNotNull(session.sample("c"));
        assertEquals(List.of("sampled a", "sampled b", "finished", "sampled c", "finished"), host.events);
    }

    /** Added while a run is in flight: queued behind it, once, and the run finishes once. */
    @Test
    void sampleMoreDuringARunJoinsItsQueue() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        CohortSession session = new CohortSession();
        session.setProjectSlides(List.of(new CohortSession.SlideRef("a", "a.tif"), new CohortSession.SlideRef("b", "b.tif"),
                new CohortSession.SlideRef("c", "c.tif")));
        RecordingHost host = new RecordingHost();
        CohortCoordinator c = new CohortCoordinator(session, bg, fx, host);
        c.start(List.of(source("a"), source("b")), GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2), 0);
        bg.runNext();
        c.sampleMore(List.of(source("b"), source("c")), GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2), 0, null);
        for (int i = 0; i < 5; i++) {
            bg.runAll();
            fx.runAll();
        }
        assertEquals(List.of("sampled a", "sampled b", "sampled c", "finished"), host.events);
        assertEquals(3, session.samples().size());
    }

    /** A cancel (another project) drops the additive run too. */
    @Test
    void cancelDropsAnAdditiveRun() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        CohortSession session = new CohortSession();
        session.setProjectSlides(List.of(new CohortSession.SlideRef("a", "a.tif"), new CohortSession.SlideRef("b", "b.tif")));
        RecordingHost host = new RecordingHost();
        CohortCoordinator c = new CohortCoordinator(session, bg, fx, host);
        c.sampleMore(List.of(source("b")), GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2), 0, null);
        c.cancel();
        bg.runAll();
        fx.runAll();
        assertTrue(host.events.isEmpty(), host.events.toString());
        assertFalse(c.sampling());
    }

    @Test
    void onlyTheNewestRescoreIsAdopted() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        CohortSession session = new CohortSession();
        RecordingHost host = new RecordingHost();
        CohortCoordinator c = new CohortCoordinator(session, bg, fx, host);
        c.rescore(GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2));
        c.rescore(GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2));
        bg.runAll();
        fx.runAll();
        assertEquals(List.of("scored"), host.events);
    }

    /** A host that rescores on every sampled slide, as the pane's does. */
    private static final class RescoringHost implements CohortCoordinator.Host {
        final List<String> events = new ArrayList<>();
        CohortCoordinator coordinator;
        @Override public void sampled(CohortSampler.Outcome o) {
            events.add("sampled " + o.slideId());
            coordinator.rescore(withReference("a"));
        }
        @Override public void samplingFinished() { events.add("finished"); }
        @Override public void scored(boolean changed) { events.add("scored"); }
        @Override public void scoringFailed() { events.add("failed"); }
        @Override public void cacheSettled(Path file, AlignmentModel.Cache cache, int sampledCellsPerSlide) {
            events.add("cache " + file + " " + cache.slides().keySet().stream().sorted().toList());
        }
    }

    private static CohortCoordinator coordinator(RescoringHost host, ManualExecutor bg, ManualExecutor fx) {
        CohortSession session = new CohortSession();
        session.setProjectSlides(List.of(new CohortSession.SlideRef("a", "a.tif"), new CohortSession.SlideRef("b", "b.tif")));
        CohortCoordinator c = new CohortCoordinator(session, bg, fx, host);
        host.coordinator = c;
        return c;
    }

    private static void drain(ManualExecutor bg, ManualExecutor fx) {
        while (!bg.queue.isEmpty() || !fx.queue.isEmpty()) {
            bg.runAll();
            fx.runAll();
        }
    }

    /**
     * Review fix 3: the cache is written from the first scoring adopted after the run finished —
     * the one that includes the last slide — into the file captured when the run started.
     */
    @Test
    void theCacheIsWrittenOnceFromTheFirstScoringAfterTheRunFinished() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        RescoringHost host = new RescoringHost();
        CohortCoordinator c = coordinator(host, bg, fx);
        c.start(List.of(source("a"), source("b")), withReference("a"), 0, Path.of("project-a"));
        drain(bg, fx);
        List<String> caches = host.events.stream().filter(e -> e.startsWith("cache")).toList();
        assertEquals(List.of("cache project-a [a, b]"), caches, "both slides' landmarks, once: " + host.events);
        int finished = host.events.indexOf("finished");
        int cache = host.events.indexOf(caches.get(0));
        assertTrue(cache > finished, "never before the last slide's scoring lands: " + host.events);
        assertEquals("scored", host.events.get(cache - 1), "written from the adopt, not from the finish");
    }

    /** Review fix 1: a run superseded by another project's never writes its cache anywhere. */
    @Test
    void aSupersededRunWritesNoCacheAndTheNewRunWritesItsOwnFile() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        RescoringHost host = new RescoringHost();
        CohortCoordinator c = coordinator(host, bg, fx);
        c.start(List.of(source("a"), source("b")), withReference("a"), 0, Path.of("project-a"));
        bg.runNext();
        c.start(List.of(source("a"), source("b")), withReference("a"), 0, Path.of("project-b"));
        drain(bg, fx);
        List<String> caches = host.events.stream().filter(e -> e.startsWith("cache")).toList();
        assertEquals(1, caches.size(), host.events.toString());
        assertTrue(caches.get(0).startsWith("cache project-b "), caches.get(0));
    }

    @Test
    void aCancelledRunWritesNoCache() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        RescoringHost host = new RescoringHost();
        CohortCoordinator c = coordinator(host, bg, fx);
        c.start(List.of(source("a")), withReference("a"), 0, Path.of("project-a"));
        bg.runAll();
        fx.runAll();                    // lands a, finishes; its rescore is queued
        c.cancel();
        drain(bg, fx);
        assertTrue(host.events.stream().noneMatch(e -> e.startsWith("cache")), host.events.toString());
    }

    private static qupath.ext.flowpath.model.GateTree withReference(String id) {
        qupath.ext.flowpath.model.GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(1, 2);
        tree.setReferenceSlideId(id);
        return tree;
    }

    /**
     * Task 8 review carry-over: a failed pass ends {@link CohortCoordinator#scoring()} AND tells the
     * host, so the banner's " · re-aligning…" is re-rendered away; a superseded failure says nothing.
     */
    @Test
    void aFailedScoringEndsScoringAndTellsTheHost() {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        CohortSession session = new CohortSession();
        RecordingHost host = new RecordingHost();
        CohortCoordinator c = new CohortCoordinator(session, bg, fx, host,
                (snapshot, tree) -> { throw new IllegalStateException("boom"); });
        c.rescore(withReference("a"));
        assertTrue(c.scoring());
        bg.runAll();
        fx.runAll();
        assertFalse(c.scoring());
        assertEquals(List.of("failed"), host.events);

        c.rescore(withReference("a"));
        bg.runAll();
        c.rescore(withReference("a"));   // supersedes the failed one before it lands
        fx.runAll();
        assertTrue(c.scoring(), "the newer request is still out");
        assertEquals(List.of("failed"), host.events, "a superseded failure is not reported");
    }
}
