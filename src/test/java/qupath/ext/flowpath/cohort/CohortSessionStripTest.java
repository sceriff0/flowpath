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
        assertEquals("4/6 sampled · " + remaining + " to review · Sampling…", s.statusLine());
        s.samplingFinished();
        assertTrue(s.statusLine().endsWith(" · Ready to run"));
    }

    @Test
    void aSquareFiltersTheListAndASecondClickClears() {
        CohortSession s = CohortSessionTest.sampledSession(ReviewScorerTest.tree());
        s.setSlideFilter("s1");
        assertFalse(s.visibleItems().isEmpty());
        assertTrue(s.visibleItems().stream().allMatch(i -> i.key().slideId().equals("s1")));
        s.setSlideFilter("s1");
        assertNull(s.slideFilter());
        assertEquals(s.review().items(), s.visibleItems());
    }

    @Test
    void anUnavailableCohortHasNoStatusLine() {
        CohortSession s = new CohortSession();
        assertEquals("", s.statusLine());
        assertTrue(s.slideStrip().isEmpty());
    }

    /**
     * Task 16 carry: N/P must step only through the visible items. With a slide filter shown,
     * stepping past the last visible item wraps to the first visible one, never one filtered out.
     * "odd" is flagged on both roots (unusual staining), so it has two visible items to wrap over.
     */
    @Test
    void stepWrapsThroughOnlyTheVisibleItemsUnderASlideFilter() {
        CohortSession s = CohortSessionTest.sampledSession(ReviewScorerTest.tree());
        List<qupath.ext.flowpath.cohort.ReviewItem> all = s.review().items();
        assertTrue(all.stream().anyMatch(i -> !i.key().slideId().equals("odd")), "fixture check: more than one slide flagged");
        s.setSlideFilter("odd");
        List<qupath.ext.flowpath.cohort.ReviewItem> visible = s.visibleItems();
        assertEquals(2, visible.size(), "fixture check: 'odd' is flagged on both roots");
        assertTrue(visible.size() < all.size(), "fixture check: the filter actually narrows the list");

        s.select(visible.get(visible.size() - 1).key());
        assertEquals(visible.get(0).key(), s.step(+1), "wraps within the filtered slide, not the whole review");
        assertTrue(s.selected().key().slideId().equals("odd"));
        assertEquals(visible.get(visible.size() - 1).key(), s.step(-1));
    }

    /** Task 16 carry: the same rule applies to a selected group, not only a slide filter. */
    @Test
    void stepWrapsThroughOnlyTheVisibleItemsUnderAGroupSelection() {
        CohortSession s = CohortSessionTest.sampledSession(ReviewScorerTest.tree());
        List<ReviewGroup> groups = s.groups();
        assertTrue(groups.size() >= 2, "fixture check: more than one group");
        ReviewGroup group = groups.get(1);
        s.selectGroup(group.key());
        List<qupath.ext.flowpath.cohort.ReviewItem> visible = s.visibleItems();
        assertEquals(group.items(), visible);

        s.select(visible.get(visible.size() - 1).key());
        assertEquals(visible.get(0).key(), s.step(+1));
    }
}
