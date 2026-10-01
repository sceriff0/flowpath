package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateTree;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static qupath.ext.flowpath.cohort.CohortSession.SlideStatus.*;

class CohortSessionStripTest {

    @Test
    void eachSlideGetsAStatusAndTheLineCountsTheWork() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = new CohortSession();
        s.setProjectSlides(CohortSessionTest.refs("ref", "s1", "s2", "odd", "bad", "late"));
        s.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort()) s.landed(new CohortSampler.Sampled(sample));
        s.landed(new CohortSampler.Failed("bad", "bad.tif", "no detections on this slide"));
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));

        List<CohortSession.SlideSquare> strip = s.slideStrip();
        assertEquals(List.of("ref", "s1", "s2", "odd", "bad", "late"), strip.stream().map(CohortSession.SlideSquare::slideId).toList());
        assertEquals(NEEDS_LOOK, strip.get(1).status(), "s1: root 1 sits on a peak");
        assertEquals(FAILED, strip.get(4).status());
        assertEquals("no detections on this slide", strip.get(4).failure());
        assertEquals(SAMPLING, strip.get(5).status());
        assertEquals(3000, strip.get(1).cells());
        int remaining = s.review().items().size();
        assertEquals("4/6 sampled · " + remaining + " to review · Sampling…", s.statusLine(true));
        s.samplingFinished();
        assertTrue(s.statusLine(true).endsWith(" · Ready to run"));
    }

    /**
     * Final ruling I4 (deferred item 36): "Ready to run" is the run button's own predicate, handed
     * in — the line never calls a run ready that the button refuses (busy, or no enabled gate).
     */
    @Test
    void readyToRunIsTheButtonsOwnAnswer() {
        CohortSession s = CohortSessionTest.sampledSession(ReviewScorerTest.tree());
        assertTrue(s.statusLine(true).endsWith(" · Ready to run"));
        assertTrue(s.statusLine(false).endsWith(" · Not ready to run"));
        assertFalse(s.statusLine(false).contains("Ready to run"));
    }

    /** Final ruling I3: a tree naming no reference slide is never "Ready to run". */
    @Test
    void aTreeWithNoReferenceIsNotReadyToRun() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = CohortSessionTest.sampledSession(tree);
        tree.setReferenceSlideId(null);
        s.setLiveTree(tree);
        assertTrue(s.statusLine(true).endsWith(" · No reference slide"), s.statusLine(true));
    }

    @Test
    void anUnavailableCohortHasNoStatusLine() {
        CohortSession s = new CohortSession();
        assertEquals("", s.statusLine(true));
        assertTrue(s.slideStrip().isEmpty());
    }

    /** With no group selected, N / P step through every item and wrap. */
    @Test
    void stepWrapsThroughEveryItemWithNoGroupSelected() {
        CohortSession s = CohortSessionTest.sampledSession(ReviewScorerTest.tree());
        List<qupath.ext.flowpath.cohort.ReviewItem> all = s.review().items();
        assertEquals(all, s.stepOrder());
        s.select(all.get(all.size() - 1).key());
        assertEquals(all.get(0).key(), s.step(+1));
        assertEquals(all.get(all.size() - 1).key(), s.step(-1));
    }

    /** Task 16 carry: with a group selected, N / P step only through its items. */
    @Test
    void stepWrapsThroughOnlyTheGroupsItemsUnderAGroupSelection() {
        // The flat slide has no negative peak, so both roots are flagged on it: two groups.
        CohortSession s = CohortSessionTest.sampledSession(ReviewScorerTest.tree(), ReviewScorerTest.flat());
        List<ReviewGroup> groups = s.groups();
        assertTrue(groups.size() >= 2, "fixture check: more than one group");
        ReviewGroup group = groups.get(1);
        s.selectGroup(group.key());
        List<qupath.ext.flowpath.cohort.ReviewItem> visible = s.stepOrder();
        assertEquals(group.items(), visible);

        s.select(visible.get(visible.size() - 1).key());
        assertEquals(visible.get(0).key(), s.step(+1));
    }
}
