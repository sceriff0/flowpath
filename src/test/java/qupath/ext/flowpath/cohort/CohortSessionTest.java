package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CohortSessionTest {

    static List<CohortSession.SlideRef> refs(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new CohortSession.SlideRef(id, id + ".tif")).toList();
    }

    static CohortSession sampledSession(GateTree tree) {
        return sampledSession(tree, new SlideSample[0]);
    }

    /** As {@link #sampledSession(GateTree)}, with {@code extra} slides in the project and sampled too. */
    static CohortSession sampledSession(GateTree tree, SlideSample... extra) {
        CohortSession s = new CohortSession();
        List<String> ids = new ArrayList<>(List.of("ref", "s1", "s2", "odd"));
        for (SlideSample e : extra) ids.add(e.slideId());
        s.setProjectSlides(refs(ids.toArray(String[]::new)));
        s.samplingStarted();
        List<SlideSample> samples = ReviewScorerTest.cohort();
        samples.addAll(List.of(extra));
        for (SlideSample sample : samples) s.landed(new CohortSampler.Sampled(sample));
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
        s.setLiveTree(ReviewScorerTest.tree());
        s.samplingStarted();
        s.landed(new CohortSampler.Failed("s2", "s2.tif", "no detections on this slide"));
        CohortState st = s.state();
        assertTrue(st.sampling());
        assertEquals("Sampling slides 1/3…", st.message());
        s.samplingFinished();
        assertEquals("1 slide(s) could not be sampled", s.state().message());
        assertEquals(1, s.state().failed());
        assertEquals(List.of("s2.tif"), s.failedSlideNames(), "what the run's confirmation names as uncorrected");
    }

    /** Final review item 13: the sampling message counts the slides being sampled, not the excluded ones. */
    @Test
    void theSamplingMessageLeavesExcludedSlidesOutOfItsDenominator() {
        CohortSession s = new CohortSession();
        s.setProjectSlides(refs("ref", "s1", "s2"));
        s.setLiveTree(ReviewScorerTest.tree());
        s.setExcluded(java.util.Set.of("s2"));
        s.samplingStarted();
        s.landed(new CohortSampler.Failed("s1", "s1.tif", "no detections on this slide"));
        assertEquals("Sampling slides 1/2…", s.state().message());
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

    /**
     * Final review item 1: an excluded reference has no sample, so the model it would be built on is
     * empty. Correction is disabled and says why — never an identity lookup that reads as an
     * all-clear with every cell "=" and the run "Ready".
     */
    @Test
    void anExcludedReferenceDisablesCorrectionWithAMessage() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampledSession(tree);
        s.setExcluded(java.util.Set.of("ref"));
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        CohortState st = s.state();
        assertTrue(st.correctionDisabled());
        assertEquals(CohortSession.REFERENCE_EXCLUDED, st.message());
        assertEquals("Reference slide is excluded — include it or pick another reference", st.message());
        assertFalse(s.statusLine(true).contains("Ready to run"), s.statusLine(true));
        String key = AlignmentModel.columnsOf(tree).iterator().next().key();
        assertNull(s.lookup().alignment("s1", key));
        assertSame(AlignmentLookup.NONE, s.lookupOn(s.model()));
        TreeResolver.Applied applied = TreeResolver.resolve(tree, "s1", s.lookup()).applied(tree.getRoots().get(0));
        assertEquals(List.of(TreeResolver.Source.UNCORRECTED), applied.sources());

        s.setExcluded(java.util.Set.of());
        assertFalse(s.state().correctionDisabled(), "including it again turns correction back on");
    }

    /** Final review item 1c: a rebase needs the current reference's own sample and model. */
    @Test
    void aRebaseIsRefusedWhileTheCurrentReferenceIsNotAligned() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampledSession(tree);
        assertNull(s.rebaseRefusal("ref"), "sampled and aligned: a rebase may go ahead");
        s.setExcluded(java.util.Set.of("ref"));
        assertEquals(CohortSession.REBASE_NOT_ALIGNED, s.rebaseRefusal("ref"));
        s.setExcluded(java.util.Set.of());
        assertEquals(CohortSession.REBASE_NOT_ALIGNED, s.rebaseRefusal("ref"), "included again but not yet sampled");
        CohortSession unscored = new CohortSession();
        unscored.setProjectSlides(refs("ref", "s1"));
        unscored.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort().subList(0, 2)) unscored.landed(new CohortSampler.Sampled(sample));
        assertEquals(CohortSession.REBASE_NOT_ALIGNED, unscored.rebaseRefusal("ref"), "sampled but the model is not built for it");
        assertNull(s.rebaseRefusal(null), "no reference: confirming is not a rebase");
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
        Alignment shift = Alignment.auto(30, 0.01);
        CohortSession.rebaseReference(tree, "s1", (slide, col) -> "s1".equals(slide) ? shift : null);
        assertEquals("s1", tree.getReferenceSlideId());
        assertEquals(shift.apply(400.0), a.getThreshold(), 1e-9);
        assertEquals(600.0, b.getThreshold(), "correction off: the number is the same on every slide");
    }

    @Test
    void aLookupTakenOnAModelKeepsAnsweringFromItAfterTheSessionMovesOn() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = new CohortSession();
        s.setProject("/projects/a", refs("ref", "s1", "s2", "odd"));
        s.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort()) s.landed(new CohortSampler.Sampled(sample));
        s.samplingFinished();
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        AlignmentModel captured = s.model();
        AlignmentLookup frozen = s.lookupOn(captured);
        assertSame(captured.alignment("s1", "CD8"), frozen.alignment("s1", "CD8"));

        s.setProject("/projects/b", refs("ref", "s1"));
        assertNull(s.lookup().alignment("s1", "CD8"), "the live lookup follows the session");
        assertSame(captured.alignment("s1", "CD8"), frozen.alignment("s1", "CD8"), "the frozen one stays on its model");
        assertNull(s.lookupOn(s.model()).alignment("s1", "CD8"));

        tree.setReferenceSlideId("deleted");
        CohortSession off = sampledSession(tree);
        assertNull(off.lookupOn(off.model()).alignment("s1", "CD8"), "a disabled correction answers nothing");
    }

    // ---- review round 1 ----------------------------------------------------------------

    /** Fix 1: QuPath entry ids restart per project; "s1" in project B is not "s1" in project A. */
    @Test
    void twoProjectsWithCollidingIdsDoNotShareSamplesOrAlignments() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = new CohortSession();
        s.setProject("/projects/a", refs("ref", "s1", "s2", "odd"));
        s.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort()) s.landed(new CohortSampler.Sampled(sample));
        s.samplingFinished();
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        assertNotNull(s.lookup().alignment("s1", "CD8"), "fixture check: project A is aligned");
        assertFalse(s.model().cache().isEmpty());

        s.setProject("/projects/b", List.of(new CohortSession.SlideRef("ref", "other-ref.tif"),
                new CohortSession.SlideRef("s1", "other-s1.tif")));
        assertTrue(s.samples().isEmpty(), "B's slides were never sampled");
        assertNull(s.sample("s1"));
        assertNull(s.model().alignment("s1", "CD8"));
        assertTrue(s.model().cache().isEmpty(), "A's landmarks are not B's");
        assertTrue(s.review().items().isEmpty());
        s.setLiveTree(tree);
        assertNull(s.lookup().alignment("s1", "CD8"));
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        assertNull(s.lookup().alignment("s1", "CD8"), "a rescore over no samples aligns nothing");

        // The same project again keeps what it has.
        s.setProject("/projects/b", List.of(new CohortSession.SlideRef("ref", "other-ref.tif"),
                new CohortSession.SlideRef("s1", "other-s1.tif"), new CohortSession.SlideRef("s3", "s3.tif")));
        assertEquals("s3.tif", s.slideName("s3"));
    }

    /** Fix 3: a score that cannot align anything still carries the persisted cache. */
    @Test
    void aShortCircuitedScoreKeepsTheCache() {
        AlignmentModel.Cache persisted = sampledSession(ReviewScorerTest.tree()).model().cache();
        assertFalse(persisted.slides().isEmpty(), "fixture check");

        CohortSession s = new CohortSession();
        s.setProjectSlides(refs("ref", "s1", "s2", "odd"));
        s.setCache(persisted);
        s.samplingStarted();
        s.landed(new CohortSampler.Sampled(ReviewScorerTest.cohort().get(1)));   // one sample: < 2
        s.samplingFinished();
        GateTree tree = ReviewScorerTest.tree();
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        assertEquals(persisted, s.model().cache(), "fewer than two samples: nothing aligned, nothing lost");

        tree.setReferenceSlideId(null);
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        assertEquals(persisted.slides(), s.model().cache().slides(), "no reference: the cached landmarks survive");
    }

    /** Fix 4: the model answers only for the reference it was built against. */
    @Test
    void theLookupAnswersNullAfterAReferenceChangeUntilItsRescoreLands() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampledSession(tree);
        assertNotNull(s.lookup().alignment("s2", "CD8"));

        tree.setReferenceSlideId("s1");
        s.setLiveTree(tree);                       // what applySlideContext does before the pass
        assertNull(s.lookup().alignment("s2", "CD8"), "the model is still aligned to 'ref'");

        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        assertNotNull(s.lookup().alignment("s2", "CD8"));
        assertEquals("s1", s.model().referenceSlideId());
    }

    /** Fix 5: the feedback-loop guard must also say yes. */
    @Test
    void adoptReportsAChangeWhenTheReferenceMovesOrASlideArrives() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = sampledSession(tree);
        tree.setReferenceSlideId("s1");
        assertTrue(s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy())), "every slide re-aligned to s1");

        CohortSession grow = new CohortSession();
        grow.setProjectSlides(refs("ref", "s1", "s2", "odd"));
        GateTree t = ReviewScorerTest.tree();
        List<SlideSample> cohort = ReviewScorerTest.cohort();
        grow.samplingStarted();
        grow.landed(new CohortSampler.Sampled(cohort.get(0)));
        grow.landed(new CohortSampler.Sampled(cohort.get(1)));
        grow.adopt(CohortSession.score(grow.snapshot(t), t.deepCopy()));
        grow.landed(new CohortSampler.Sampled(cohort.get(2)));
        assertTrue(grow.adopt(CohortSession.score(grow.snapshot(t), t.deepCopy())), "s2 has an alignment now");
    }

    /** Fix 5: alignments are compared on kind, shift and bin width. */
    @Test
    void alignmentsAreComparedOnEveryParameter() {
        Alignment a = Alignment.auto(50, 0.01);
        Alignment b = Alignment.auto(30, 0.01);
        assertNotEquals(a.apply(500.0), b.apply(500.0), "fixture check: they map differently");

        assertFalse(CohortSession.sameAlignment(a, b), "shift differs");
        assertFalse(CohortSession.sameAlignment(a, Alignment.landmark(50, 0.01)), "kind differs");
        assertFalse(CohortSession.sameAlignment(a, Alignment.auto(50, 0.02)), "bin width differs");
        assertTrue(CohortSession.sameAlignment(a, Alignment.auto(50, 0.01)));
        assertTrue(CohortSession.sameAlignment(null, null));
        assertFalse(CohortSession.sameAlignment(a, null));
        assertFalse(CohortSession.sameAlignment(Alignment.identity(),
                Alignment.auto(30, 0.01)));
    }

    /** Fix 2: a tree whose recorded names contradict the project is resolved with no slide state. */
    @Test
    void aForeignTreeTurnsCorrectionOffAndSaysSo() {
        GateTree tree = ReviewScorerTest.tree();
        tree.setSlideNames(Map.of("ref", "ref.tif", "s1", "another-project-s1.tif"));
        tree.getRoots().get(0).setSlideSetting("s1", new SlideSetting.Manual(GateValues.of(new double[]{1.0})));
        CohortSession s = sampledSession(tree);

        CohortState st = s.state();
        assertTrue(st.correctionDisabled());
        assertEquals(CohortSession.FOREIGN_TREE, st.message());
        assertNull(st.referenceName());
        assertNull(s.lookup().alignment("s1", "CD8"));
        assertTrue(s.review().items().isEmpty(), "scored as having no reference");

        String slideId = CohortIdentity.resolutionSlideId(tree, s.projectNames(), "s1");
        assertNull(slideId);
        TreeResolver.Applied applied = TreeResolver.resolve(tree, slideId, s.lookup()).applied(tree.getRoots().get(0));
        assertEquals(List.of(TreeResolver.Source.REFERENCE), applied.sources(), "the Manual on 's1' is not honoured");
        assertEquals(tree.getRoots().get(0).getThreshold(), applied.applied().axis(0)[0]);

        tree.setSlideNames(Map.of("ref", "ref.tif", "s1", "s1.tif"));
        s.setLiveTree(tree);
        assertFalse(s.state().correctionDisabled(), "names that agree: this project's tree");
    }

    @Test
    void theProjectScaleReachesTheModelAndTheRankingKey() {
        GateTree tree = ReviewScorerTest.tree();
        CohortSession s = new CohortSession();
        s.setProjectSlides(refs("ref", "s1", "s2", "odd"));
        s.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort()) s.landed(new CohortSampler.Sampled(sample));
        s.samplingFinished();
        assertEquals(qupath.ext.flowpath.model.cohort.LogScale.LN, s.scale());
        s.setScale(qupath.ext.flowpath.model.cohort.LogScale.LN1P);
        assertEquals(qupath.ext.flowpath.model.cohort.LogScale.LN1P, s.scale());
        CohortSession.Snapshot snap = s.snapshot(tree);
        assertEquals(qupath.ext.flowpath.model.cohort.LogScale.LN1P, snap.scale());
        CohortSession.Scored scored = CohortSession.score(snap, tree.deepCopy());
        assertEquals(qupath.ext.flowpath.model.cohort.LogScale.LN1P, scored.model().scale());

        java.util.Set<AlignmentModel.ColumnRef> cols = CohortSession.rankedColumns(tree);
        List<SlideSample> samples = ReviewScorerTest.cohort();
        assertNotEquals(CohortSession.rankingKey(samples, cols, qupath.ext.flowpath.model.cohort.LogScale.LN),
                CohortSession.rankingKey(samples, cols, qupath.ext.flowpath.model.cohort.LogScale.LN1P));
    }

    @Test
    void aPickedPeakMakesThatSlideALandmarkAlignment() {
        GateTree tree = ReviewScorerTest.tree();
        String column = AlignmentModel.columnsOf(tree).iterator().next().key();
        CohortSession s = new CohortSession();
        s.setProjectSlides(refs("ref", "s1", "s2", "odd"));
        s.samplingStarted();
        for (SlideSample sample : ReviewScorerTest.cohort()) s.landed(new CohortSampler.Sampled(sample));
        s.samplingFinished();
        s.setPeaks(Map.of("s1", Map.of(column, 3.0), "ref", Map.of(column, 1.0)));
        assertEquals(3.0, s.peaks().get("s1").get(column));
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        assertEquals(Alignment.Kind.LANDMARK, s.model().alignment("s1", column).kind());
    }
}
