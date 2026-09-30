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
 * The reference slide is a confirmed choice, never a silent default (spec 2026-09-30 §4.1):
 * {@link GatingSession#confirmReference} is one undo step, so undoing past it cannot leave the
 * tree and the cohort disagreeing about whether correction is on, and a tree with no reference
 * stays without one until the user confirms.
 */
class ReferenceConfirmationTest {

    private static final Map<String, String> NAMES = Map.of("1", "slide_A", "2", "slide_B", "3", "slide_C");

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
    void aLoadedTreeWithNoReferenceStaysWithoutOne() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree());
        assertNull(session.tree().getReferenceSlideId(), "no silent default (spec §4.1)");
    }

    @Test
    void confirmingIsOneUndoStepThatRecordsTheNames() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree());
        session.confirmReference("2", NAMES);
        assertEquals("2", session.tree().getReferenceSlideId());
        assertEquals("slide_B", session.tree().getSlideNames().get("2"));
        assertTrue(session.undo());
        assertNull(session.tree().getReferenceSlideId());
        assertTrue(session.tree().getSlideNames().isEmpty(), "one step: the names go with the reference");
        assertTrue(session.redo());
        assertEquals("2", session.tree().getReferenceSlideId());
    }

    @Test
    void confirmingOverAnExistingReferenceIsRefused() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.confirmReference("1", NAMES);
        assertThrows(IllegalStateException.class, () -> session.confirmReference("2", NAMES));
    }

    /** Undo past a confirmation leaves correction consistent: no reference, every slide on the reference numbers. */
    @Test
    void undoingTheConfirmationLeavesCorrectionConsistent() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree());
        CohortSession cohort = new CohortSession();
        cohort.setProjectSlides(List.of(new CohortSession.SlideRef("ref", "ref.tif"),
                new CohortSession.SlideRef("s1", "s1.tif")));
        cohort.samplingStarted();
        cohort.landed(new CohortSampler.Sampled(slide("ref", 1, 0.0)));
        cohort.landed(new CohortSampler.Sampled(slide("s1", 2, 0.4)));
        cohort.samplingFinished();

        session.confirmReference("ref", cohort.projectNames());
        assertEquals(Map.of("ref", "ref.tif", "s1", "s1.tif"), session.tree().getSlideNames());
        rescore(cohort, session.tree());
        assertEquals("ref.tif", cohort.state().referenceName());
        assertEquals(List.of(TreeResolver.Source.CORRECTED), sourcesOn(session.tree(), "s1", cohort, 0));
        assertEquals(List.of(TreeResolver.Source.CORRECTED), sourcesOn(session.tree(), "s1", cohort, 1));

        assertTrue(session.undo());
        assertNull(session.tree().getReferenceSlideId());
        rescore(cohort, session.tree());
        assertNull(cohort.state().referenceName());
        assertFalse(cohort.state().correctionDisabled(), "no reference is not a missing reference");
        for (int root = 0; root < 2; root++) {
            TreeResolver.Applied applied = TreeResolver.resolve(session.tree(), "s1", cohort.lookup())
                    .applied(session.tree().getRoots().get(root));
            assertEquals(List.of(TreeResolver.Source.REFERENCE), applied.sources());
            assertEquals(session.tree().getRoots().get(root).getThreshold(), applied.applied().axis(0)[0]);
        }

        assertTrue(session.redo());
        assertEquals("ref", session.tree().getReferenceSlideId());
        rescore(cohort, session.tree());
        assertEquals(List.of(TreeResolver.Source.CORRECTED), sourcesOn(session.tree(), "s1", cohort, 1));

        assertTrue(session.undo());
        cohort.setLiveTree(session.tree());
        assertEquals(CohortSession.NO_REFERENCE, cohort.state().message());
    }

    /**
     * Final review item 1: reference A, rebase to B, exclude A, then undo. The tree names A again,
     * which has no sample: correction must be off and say so, not answer an empty model as identity.
     */
    @Test
    void undoingBackToAnExcludedReferenceLeavesCorrectionDisabled() {
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree());
        CohortSession cohort = new CohortSession();
        cohort.setProjectSlides(List.of(new CohortSession.SlideRef("ref", "ref.tif"),
                new CohortSession.SlideRef("s1", "s1.tif"), new CohortSession.SlideRef("s2", "s2.tif")));
        cohort.samplingStarted();
        cohort.landed(new CohortSampler.Sampled(slide("ref", 1, 0.0)));
        cohort.landed(new CohortSampler.Sampled(slide("s1", 2, 0.4)));
        cohort.landed(new CohortSampler.Sampled(slide("s2", 3, -0.2)));
        cohort.samplingFinished();
        session.confirmReference("ref", cohort.projectNames());
        rescore(cohort, session.tree());
        assertNull(cohort.rebaseRefusal("ref"));
        session.recordSlideEdit("s1", "s1.tif",
                () -> CohortSession.rebaseReference(session.tree(), "s1", cohort.lookup()));
        rescore(cohort, session.tree());
        cohort.setExcluded(java.util.Set.of("ref"));
        rescore(cohort, session.tree());
        assertFalse(cohort.state().correctionDisabled(), "s1 is the reference; excluding the old one is fine");

        assertTrue(session.undo());
        assertEquals("ref", session.tree().getReferenceSlideId());
        rescore(cohort, session.tree());
        assertTrue(cohort.state().correctionDisabled());
        assertEquals(CohortSession.REFERENCE_EXCLUDED, cohort.state().message());
        assertEquals(List.of(TreeResolver.Source.UNCORRECTED), sourcesOn(session.tree(), "s1", cohort, 0));
        assertEquals(CohortSession.REBASE_NOT_ALIGNED, cohort.rebaseRefusal("ref"),
                "switching from here would copy unaligned numbers onto the new reference");
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
}
