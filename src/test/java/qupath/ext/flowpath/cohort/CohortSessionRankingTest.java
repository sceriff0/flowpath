package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateTree;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CohortSessionRankingTest {

    private static CohortSession sampled(GateTree tree, Set<String> excluded) {
        CohortSession s = new CohortSession();
        s.setProjectSlides(CohortSessionTest.refs("ref", "s1", "s2", "odd"));
        s.setExcluded(excluded);
        s.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort()) s.landed(new CohortSampler.Sampled(sample));
        s.samplingFinished();
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        return s;
    }

    @Test
    void aTreeWithNoReferenceStillGetsARankedSuggestion() {
        GateTree tree = ReviewScorerTest.tree();
        tree.setReferenceSlideId(null);
        CohortSession s = sampled(tree, Set.of());
        assertNotNull(s.ranking().suggestedId(), "ranking must run before any reference exists");
        assertEquals(s.ranking().suggestedId(), s.suggestedReferenceId());
        assertNotEquals("odd", s.suggestedReferenceId());
    }

    @Test
    void theSuggestionIsNeverTheOpenSlideByDefault() {
        GateTree tree = ReviewScorerTest.tree();
        tree.setReferenceSlideId(null);
        CohortSession s = new CohortSession();
        s.setProjectSlides(CohortSessionTest.refs("ref", "s1"));
        // Nothing sampled, nothing ranked: no suggestion at all, not "the open slide".
        s.setLiveTree(tree);
        assertNull(s.suggestedReferenceId());
    }

    @Test
    void anExcludedSlideIsNotSampledRankedOrReviewed() {
        CohortSession s = sampled(ReviewScorerTest.tree(), Set.of("odd"));
        assertNull(s.sample("odd"));
        assertNull(s.ranking().rank("odd"));
        assertTrue(s.review().items().stream().noneMatch(i -> i.key().slideId().equals("odd")));
        String key = AlignmentModel.columnsOf(ReviewScorerTest.tree()).iterator().next().key();
        assertNull(s.lookup().alignment("odd", key));
        assertEquals(CohortSession.SlideStatus.EXCLUDED,
                s.slideStrip().stream().filter(q -> q.slideId().equals("odd")).findFirst().orElseThrow().status());
    }

    @Test
    void excludingASampledSlideDropsItsSample() {
        CohortSession s = sampled(ReviewScorerTest.tree(), Set.of());
        assertNotNull(s.sample("odd"));
        s.setExcluded(Set.of("odd"));
        assertNull(s.sample("odd"));
        assertEquals(Set.of("odd"), s.excluded());
    }

    @Test
    void theSuggestionHidesWhenItIsAlreadyTheReference() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampled(tree, Set.of());
        String suggested = s.ranking().suggestedId();
        tree.setReferenceSlideId(suggested);
        s.setLiveTree(tree);
        assertNull(s.suggestedReferenceId());
    }
}
