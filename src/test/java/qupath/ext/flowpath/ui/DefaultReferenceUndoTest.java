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
 * reference and what the cohort's state then reports — redo puts the reference back, and the
 * next cohort refresh (the next ingest) re-applies the default as a fresh undo step. Re-applying
 * it inside the resync instead would record a new step on every undo into the pre-state and
 * wipe the redo stack, so the step could never be undone at all.
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

        assertTrue(session.applyDefaultReference("ref", cohort.projectNames()));
        assertFalse(session.applyDefaultReference("s1", cohort.projectNames()), "only the first sighting picks a reference");
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

        // Undone again, the next cohort refresh re-applies the default, as its own undo step.
        assertTrue(session.undo());
        assertTrue(session.applyDefaultReference("ref", cohort.projectNames()));
        assertEquals("ref", session.tree().getReferenceSlideId());
        assertTrue(session.undo());
        assertNull(session.tree().getReferenceSlideId(), "the re-applied default is one step, not folded away");
    }

    @Test
    void noOpenSlideSetsNothingAndRecordsNothing() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        assertFalse(session.applyDefaultReference(null, Map.of("1", "a.tif")));
        assertNull(session.tree().getReferenceSlideId());
        assertFalse(session.undo(), "no step was recorded");
    }
}
