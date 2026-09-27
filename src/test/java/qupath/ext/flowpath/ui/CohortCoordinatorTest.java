package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.SlideSource;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

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
}
