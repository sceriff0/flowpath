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

    @Test
    void anExclusionLandingDuringAScoringIsNotUndoneByAdoptingIt() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = new CohortSession();
        s.setProjectSlides(CohortSessionTest.refs("ref", "s1", "s2", "odd"));
        s.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort()) s.landed(new CohortSampler.Sampled(sample));
        s.samplingFinished();
        CohortSession.Scored scored = CohortSession.score(s.snapshot(tree), tree.deepCopy());
        s.setExcluded(Set.of("odd"));
        s.adopt(scored);
        String key = AlignmentModel.columnsOf(tree).iterator().next().key();
        assertNull(s.lookup().alignment("odd", key));
        assertNotEquals("odd", s.suggestedReferenceId());
        assertTrue(s.review().items().stream().noneMatch(i -> i.key().slideId().equals("odd")));
    }

    @Test
    void excludingASlideSilencesItsHeldAlignmentAtOnce() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampled(tree, Set.of());
        String key = AlignmentModel.columnsOf(tree).iterator().next().key();
        assertNotNull(s.lookup().alignment("odd", key));
        s.setExcluded(Set.of("odd"));
        assertNull(s.lookup().alignment("odd", key));
        assertNull(s.lookupOn(s.model()).alignment("odd", key));
        assertTrue(s.review().items().stream().noneMatch(i -> i.key().slideId().equals("odd")));
        assertTrue(s.review().infos().stream().noneMatch(i -> i.slideId().equals("odd")));
    }

    @Test
    void aProjectChangeForgetsTheExclusions() {
        CohortSession s = new CohortSession();
        s.setProject("a", CohortSessionTest.refs("ref", "s1"));
        s.setExcluded(Set.of("s1"));
        s.setProject("b", CohortSessionTest.refs("ref", "s1"));
        assertEquals(Set.of(), s.excluded());
    }

    /** Final review item 6: the caller learns of a project change, to drop its own id-keyed selection. */
    @Test
    void setProjectSaysWhetherTheProjectChanged() {
        CohortSession s = new CohortSession();
        assertTrue(s.setProject("a", CohortSessionTest.refs("ref", "s1")));
        assertFalse(s.setProject("a", CohortSessionTest.refs("ref", "s1", "s2")), "new slides, same project");
        assertTrue(s.setProject("b", CohortSessionTest.refs("ref", "s1")));
        assertTrue(s.setProject(null, List.of()));
        assertFalse(s.setProject(null, List.of()));
    }

    /**
     * Final review item 2: the ranking counts only the columns a slide is corrected on — enabled
     * gates with Correct staining on. A disabled gate and an opted-out one are not "gated columns".
     */
    @Test
    void theRankingIgnoresDisabledAndOptedOutGates() {
        GateTree plain = ReviewScorerTest.tree();
        plain.setReferenceSlideId(null);
        GateTree extra = ReviewScorerTest.tree();
        extra.setReferenceSlideId(null);
        qupath.ext.flowpath.model.GateNode disabled = new qupath.ext.flowpath.model.GateNode("CD3", 0.5);
        disabled.setEnabled(false);
        extra.addRoot(disabled);
        qupath.ext.flowpath.model.GateNode optedOut = new qupath.ext.flowpath.model.GateNode("CD3", 0.5);
        optedOut.setStatistic(qupath.ext.flowpath.model.Statistic.MEAN);
        optedOut.setCorrectStaining(false);
        extra.addRoot(optedOut);

        assertEquals(1, CohortSession.rankedColumns(extra).size());
        ReferenceRanking.Result withExtras = sampled(extra, Set.of()).ranking();
        ReferenceRanking.Result without = sampled(plain, Set.of()).ranking();
        assertEquals(1, withExtras.gatedColumns());
        assertTrue(withExtras.uncorrectableColumns().isEmpty(), withExtras.uncorrectableColumns().toString());
        assertEquals(without.suggestedId(), withExtras.suggestedId());
        assertEquals(without.reason(), withExtras.reason());
    }

    /** Final review item 3: an unchanged cohort and column set reuse the last ranking, not recompute it. */
    @Test
    void aRescoreWithUnchangedInputsReusesTheRanking() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampled(tree, Set.of());
        ReferenceRanking.Result first = s.ranking();
        assertNotSame(ReferenceRanking.Result.NONE, first);
        CohortSession.Scored again = CohortSession.score(s.snapshot(tree), tree.deepCopy());
        assertSame(first, again.ranking(), "same samples, same columns: the memo answers");
        s.adopt(again);
        // A threshold nudge changes no ranked input either.
        tree.getRoots().get(0).setThreshold(tree.getRoots().get(0).getThreshold() * 1.1);
        assertSame(first, CohortSession.score(s.snapshot(tree), tree.deepCopy()).ranking());

        s.setExcluded(Set.of("odd"));
        CohortSession.Scored fewer = CohortSession.score(s.snapshot(tree), tree.deepCopy());
        assertNotSame(first, fewer.ranking(), "a different set of slides is ranked afresh");
        assertNull(fewer.ranking().rank("odd"));
    }
}
