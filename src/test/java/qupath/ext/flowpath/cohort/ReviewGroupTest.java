package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.SlideSetting;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewGroupTest {

    static AlignmentModel model(GateTree tree, List<SlideSample> samples) {
        return AlignmentModel.build(tree.getReferenceSlideId(), samples, AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty());
    }

    @Test
    void itemsGroupByGateTopDownAndSameChannelRootsStayApart() {
        GateTree tree = ReviewScorerTest.tree();                 // two CD8 roots
        List<ReviewItem> items = ReviewScorer.score(tree, ReviewScorerTest.cohort(), model(tree, ReviewScorerTest.cohort())).items();
        List<ReviewGroup> groups = ReviewGroup.of(items);
        assertEquals(List.of(new ReviewGroup.Key(0, "CD8"), new ReviewGroup.Key(1, "CD8")),
                groups.stream().map(ReviewGroup::key).toList());
        assertTrue(groups.get(1).slideIds().contains("s1"), "root 1 sits on a peak on s1");
    }

    @Test
    void oneAnswerReviewsTheWholeGroupAndEmptiesIt() {
        GateTree tree = ReviewScorerTest.tree();
        List<SlideSample> samples = ReviewScorerTest.cohort();
        AlignmentModel m = model(tree, samples);
        ReviewGroup group = ReviewGroup.of(ReviewScorer.score(tree, samples, m).items()).get(1);
        int reviewed = ReviewAnswers.looksRightAll(tree, group, m::alignment);
        assertEquals(group.items().size(), reviewed);
        for (String slide : group.slideIds()) {
            assertInstanceOf(SlideSetting.Reviewed.class, tree.getRoots().get(1).slideSetting(slide));
        }
        assertNull(tree.getRoots().get(0).slideSetting("s1"), "the same-channel sibling is untouched");
        assertTrue(ReviewScorer.score(tree, samples, m).items().stream().noneMatch(i -> i.key().rootIndex() == 1));
    }

    @Test
    void theSelectedGroupIsAValueAndNamesItsFlaggedSlides() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = CohortSessionTest.sampledSession(tree);
        s.selectGroup(new ReviewGroup.Key(1, "CD8"));
        GateTree undone = tree.deepCopy();
        s.adopt(CohortSession.score(s.snapshot(undone), undone.deepCopy()));
        assertEquals(new ReviewGroup.Key(1, "CD8"), s.selectedGroup().key());
        assertEquals(s.selectedGroup().slideIds(), s.flaggedSlides(1, "CD8"));
        assertTrue(s.flaggedSlides(5, "nothing").isEmpty());
    }

    /**
     * The list is the last rescore's; between an answer and the next rescore it can still name a
     * slide answered since. "All look right" leaves such a slide's answer — an Adjust's Manual, a
     * Skip — as it is (the rule is {@code ReviewScorer.answered}'s) and does not count it.
     */
    @Test
    void anAnswerGivenSinceTheListWasScoredIsLeftAlone() {
        GateTree tree = ReviewScorerTest.tree();
        List<SlideSample> samples = ReviewScorerTest.cohort();
        AlignmentModel m = model(tree, samples);
        ReviewGroup group = ReviewGroup.of(ReviewScorer.score(tree, samples, m).items()).get(1);
        assertTrue(group.slideIds().size() >= 2, "the fixture flags root 1 on several slides");
        String skipped = group.slideIds().iterator().next();
        tree.getRoots().get(1).setSlideSetting(skipped, new SlideSetting.Skip());
        int reviewed = ReviewAnswers.looksRightAll(tree, group, m::alignment);
        assertEquals(group.items().size() - 1, reviewed);
        assertInstanceOf(SlideSetting.Skip.class, tree.getRoots().get(1).slideSetting(skipped));
    }
}
