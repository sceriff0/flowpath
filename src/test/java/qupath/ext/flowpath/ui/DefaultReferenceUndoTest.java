package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.SlideSample;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pre-flight ruling C9: the default reference slide is an undo step, so undoing past it cannot
 * leave the tree and the cohort disagreeing about whether correction is on.
 * <p>
 * The chosen behaviour is the honest one: undo restores the pre-state — no reference, so every
 * slide gates on the reference numbers, which is what {@link TreeResolver} does with no
 * reference and what the cohort's state then reports ("No reference slide") — and redo puts the
 * reference back. The default is applied once per project (final review M6): an ingest after the
 * undo does not apply it again, since recording a step there wipes the redo stack. A tree loaded
 * with no reference gets the default inside the load's own step (final ruling I3).
 */
class DefaultReferenceUndoTest {

    private static SlideSample slide(String id, long seed, double shift) {
        int n = 3000;
        Random r = new Random(seed);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) raw[i] = 100 * Math.sinh((r.nextDouble() < 0.3 ? 4.0 : 1.0) + shift + 0.3 * r.nextGaussian());
        CellIndex index = Cells.of(n).marker("CD3", i -> 1.0).marker("CD8", raw).build();
        boolean[] clean = Cells.allTrue(n);
        return new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), n, "f-" + id);
    }

    /** Two enabled roots on one channel, no reference yet: a tree as a first cohort sighting finds it. */
    private static GateTree tree() {
        GateTree tree = new GateTree();
        for (double t : new double[]{400.0, 1200.0}) {
            GateNode g = new GateNode("CD8", t);
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        return tree;
    }

    private static void rescore(CohortSession cohort, GateTree tree) {
        cohort.adopt(CohortSession.score(cohort.snapshot(tree), tree.deepCopy()));
    }

    private static List<TreeResolver.Source> sourcesOn(GateTree tree, String slide, CohortSession cohort, int root) {
        return TreeResolver.resolve(tree, slide, cohort.lookup()).applied(tree.getRoots().get(root)).sources();
    }

    @Test
    void undoingTheDefaultReferenceLeavesCorrectionConsistent() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree());
        CohortSession cohort = new CohortSession();
        cohort.setProjectSlides(List.of(new CohortSession.SlideRef("ref", "ref.tif"),
                new CohortSession.SlideRef("s1", "s1.tif")));
        cohort.samplingStarted();
        cohort.landed(new CohortSampler.Sampled(slide("ref", 1, 0.0)));
        cohort.landed(new CohortSampler.Sampled(slide("s1", 2, 0.4)));
        cohort.samplingFinished();

        assertTrue(session.applyDefaultReference("ref", "p", cohort.projectNames()));
        assertFalse(session.applyDefaultReference("s1", "p", cohort.projectNames()), "only the first sighting picks a reference");
        assertEquals(Map.of("ref", "ref.tif", "s1", "s1.tif"), session.tree().getSlideNames(),
                "the project's names are recorded in the same step");
        rescore(cohort, session.tree());
        assertEquals("ref.tif", cohort.state().referenceName());
        assertEquals(List.of(TreeResolver.Source.CORRECTED), sourcesOn(session.tree(), "s1", cohort, 0));
        assertEquals(List.of(TreeResolver.Source.CORRECTED), sourcesOn(session.tree(), "s1", cohort, 1));

        // Undo restores the pre-state honestly: no reference, so no slide is corrected, and the
        // cohort's own state says so too once it is rescored (the pass after the undo does it).
        assertTrue(session.undo());
        assertNull(session.tree().getReferenceSlideId());
        assertTrue(session.tree().getSlideNames().isEmpty(), "one step: the names go with the reference");
        rescore(cohort, session.tree());
        assertNull(cohort.state().referenceName());
        assertFalse(cohort.state().correctionDisabled(), "no reference is not a missing reference");
        for (int root = 0; root < 2; root++) {
            TreeResolver.Applied applied = TreeResolver.resolve(session.tree(), "s1", cohort.lookup())
                    .applied(session.tree().getRoots().get(root));
            assertEquals(List.of(TreeResolver.Source.REFERENCE), applied.sources());
            assertEquals(session.tree().getRoots().get(root).getThreshold(), applied.applied().axis(0)[0]);
        }

        // Redo puts it back.
        assertTrue(session.redo());
        assertEquals("ref", session.tree().getReferenceSlideId());
        rescore(cohort, session.tree());
        assertEquals(List.of(TreeResolver.Source.CORRECTED), sourcesOn(session.tree(), "s1", cohort, 1));

        // Undone again, the next cohort refresh (an ingest) leaves it undone: the cohort says so.
        assertTrue(session.undo());
        assertFalse(session.applyDefaultReference("ref", "p", cohort.projectNames()));
        assertNull(session.tree().getReferenceSlideId());
        cohort.setLiveTree(session.tree());
        assertEquals(CohortSession.NO_REFERENCE, cohort.state().message());
    }

    /** Final review M6: an ingest after undoing the default reference must not wipe redo. */
    @Test
    void redoSurvivesAnIngestAfterUndoingTheDefault() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree());
        Map<String, String> names = Map.of("ref", "ref.tif", "s1", "s1.tif");
        assertTrue(session.applyDefaultReference("ref", "p", names));
        assertTrue(session.undo());
        // What every later ingest of the same project calls.
        assertFalse(session.applyDefaultReference("ref", "p", names));
        assertFalse(session.applyDefaultReference("s1", "p", names));
        assertTrue(session.redo(), "the redo stack survived the ingests");
        assertEquals("ref", session.tree().getReferenceSlideId());
    }

    /** Another project's first sighting is a first sighting again. */
    @Test
    void aNewProjectIsSeenAfresh() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree());
        assertTrue(session.applyDefaultReference("ref", "p", Map.of("ref", "ref.tif", "s1", "s1.tif")));
        GateTree fresh = tree();
        session.replaceTree(fresh);
        assertTrue(session.applyDefaultReference("1", "q", Map.of("1", "a.tif", "2", "b.tif")));
        assertEquals("1", session.tree().getReferenceSlideId());
    }

    /**
     * Final ruling I3: a tree loaded into an available cohort with no reference gets the open slide
     * as its default inside the load's own undo step — one undo takes the load and the default
     * back together, and the tree before the load (two same-channel roots, its own reference)
     * comes back exactly.
     */
    @Test
    void aLoadedTreeGetsTheDefaultReferenceInsideTheLoadStep() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree());
        Map<String, String> names = Map.of("ref", "ref.tif", "s1", "s1.tif");
        assertTrue(session.applyDefaultReference("ref", "p", names));
        session.tree().getRoots().get(1).setThreshold(999.0);
        session.settle();

        GateTree loaded = tree();
        loaded.getRoots().get(0).setThreshold(111.0);
        session.replaceTree(loaded, "s1", names);
        assertEquals("s1", session.tree().getReferenceSlideId(), "the open slide anchors the loaded tree");
        assertEquals(names, session.tree().getSlideNames());
        assertEquals(111.0, session.tree().getRoots().get(0).getThreshold());

        assertTrue(session.undo());
        assertEquals("ref", session.tree().getReferenceSlideId(), "one undo: the load and its default together");
        assertEquals(400.0, session.tree().getRoots().get(0).getThreshold());
        assertEquals(999.0, session.tree().getRoots().get(1).getThreshold());
        assertTrue(session.redo());
        assertEquals("s1", session.tree().getReferenceSlideId());
    }

    /** A loaded tree that names its own reference, or belongs to another project, is left as loaded. */
    @Test
    void aLoadedTreeKeepsItsOwnReferenceAndAForeignOneIsNotAnchored() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        Map<String, String> names = Map.of("ref", "ref.tif", "s1", "s1.tif");
        GateTree own = tree();
        own.setReferenceSlideId("ref");
        session.replaceTree(own, "s1", names);
        assertEquals("ref", session.tree().getReferenceSlideId());

        GateTree foreign = tree();
        foreign.setSlideNames(Map.of("s1", "another-project.tif"));
        session.replaceTree(foreign, "s1", names);
        assertNull(session.tree().getReferenceSlideId());
        assertEquals(Map.of("s1", "another-project.tif"), session.tree().getSlideNames());
    }

    /** Final ruling I3: with no reference the cohort says so; the open slide is no longer offered. */
    @Test
    void noReferenceIsReportedAndNoOpenSlideIsOffered() {
        CohortSession cohort = new CohortSession();
        cohort.setProjectSlides(List.of(new CohortSession.SlideRef("ref", "ref.tif"),
                new CohortSession.SlideRef("s1", "s1.tif")));
        cohort.setLiveTree(tree());
        assertEquals(CohortSession.NO_REFERENCE, cohort.state().message());
        assertFalse(cohort.state().correctionDisabled(), "no reference is not a missing reference");
        assertNull(cohort.suggestedReferenceId());
        assertNull(cohort.state().suggestedReferenceName());
        assertFalse(cohort.statusLine(true).contains("Ready to run"));
    }

    @Test
    void noOpenSlideSetsNothingAndRecordsNothing() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        assertFalse(session.applyDefaultReference(null, "p", Map.of("1", "a.tif")));
        assertNull(session.tree().getReferenceSlideId());
        assertFalse(session.undo(), "no step was recorded");
    }
}
