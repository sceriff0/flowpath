package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CohortSessionTest {

    static List<CohortSession.SlideRef> refs(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new CohortSession.SlideRef(id, id + ".tif")).toList();
    }

    static CohortSession sampledSession(GateTree tree) {
        CohortSession s = new CohortSession();
        s.setProjectSlides(refs("ref", "s1", "s2", "odd"));
        s.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort()) s.landed(new CohortSampler.Sampled(sample));
        s.samplingFinished();
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        return s;
    }

    @Test
    void fewerThanTwoSlidesOffersNothing() {
        CohortSession s = new CohortSession();
        s.setProjectSlides(refs("only"));
        assertEquals(CohortState.UNAVAILABLE, s.state());
        assertNull(s.lookup().alignment("only", "CD8"));
    }

    @Test
    void samplingProgressIsReportedAndFailuresCounted() {
        CohortSession s = new CohortSession();
        s.setProjectSlides(refs("ref", "s1", "s2"));
        s.samplingStarted();
        s.landed(new CohortSampler.Failed("s2", "s2.tif", "no detections on this slide"));
        CohortState st = s.state();
        assertTrue(st.sampling());
        assertEquals("Sampling slides 1/3…", st.message());
        s.samplingFinished();
        assertEquals("1 slide(s) could not be sampled", s.state().message());
        assertEquals(1, s.state().failed());
    }

    /** Review Focus 5. */
    @Test
    void aMissingReferenceSlideDisablesCorrectionWithAMessage() {
        GateTree tree = ReviewScorerTest.tree();
        tree.setReferenceSlideId("deleted");
        CohortSession s = sampledSession(tree);
        CohortState st = s.state();
        assertTrue(st.correctionDisabled());
        assertEquals(CohortSession.REFERENCE_MISSING, st.message());
        assertNull(st.referenceName());
        TreeResolver.Applied applied = TreeResolver.resolve(tree, "s1", s.lookup()).applied(tree.getRoots().get(0));
        assertEquals(List.of(TreeResolver.Source.UNCORRECTED), applied.sources());
        assertEquals(tree.getRoots().get(0).getThreshold(), applied.applied().axis(0)[0]);
    }

    @Test
    void theSelectionIsAValueThatSurvivesARescoreOnFreshNodes() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampledSession(tree);
        ReviewItem first = s.review().items().get(0);
        s.select(first.key());

        GateTree undone = tree.deepCopy();   // what an undo swaps in: fresh GateNodes
        s.adopt(CohortSession.score(s.snapshot(undone), undone.deepCopy()));
        assertEquals(first.key(), s.selected().key());
        GateNode live = CohortSession.liveGate(undone, first.key());
        assertSame(undone.getRoots().get(first.key().rootIndex()), live);
    }

    @Test
    void stepWrapsAroundTheItems() {
        CohortSession s = sampledSession(ReviewScorerTest.tree());
        List<ReviewItem> items = s.review().items();
        assertTrue(items.size() >= 2, "fixture check");
        s.select(items.get(items.size() - 1).key());
        assertEquals(items.get(0).key(), s.step(+1));
        assertEquals(items.get(items.size() - 1).key(), s.step(-1));
    }

    @Test
    void adoptReportsWhetherAlignmentsChanged() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampledSession(tree);
        assertFalse(s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy())), "same samples, same alignments");
    }

    @Test
    void contradictoryStatesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CohortState(false, true, 0, 0, 0, 0, null, null, false, null, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CohortState(true, false, 3, 2, 0, 0, null, null, false, null, false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CohortState(true, false, 0, 2, 0, 0, null, null, false, null, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CohortState(true, false, 0, 2, 0, 0, null, null, true, null, false, true));
    }

    @Test
    void rebasingMovesCorrectedNumbersOntoTheNewReference() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        GateNode a = new GateNode("CD8", 400.0);
        a.setStatistic(Statistic.MEAN);
        GateNode b = new GateNode("CD8", 600.0);
        b.setStatistic(Statistic.MEAN);
        b.setCorrectStaining(false);
        tree.addRoot(a);
        tree.addRoot(b);
        Alignment shift = Alignment.between(new Landmarks(100, 1.0, Double.NaN), new Landmarks(100, 1.3, Double.NaN));
        CohortSession.rebaseReference(tree, "s1", (slide, col) -> "s1".equals(slide) ? shift : null);
        assertEquals("s1", tree.getReferenceSlideId());
        assertEquals(shift.apply(400.0), a.getThreshold(), 1e-9);
        assertEquals(600.0, b.getThreshold(), "correction off: the number is the same on every slide");
    }
}
