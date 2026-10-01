package qupath.ext.flowpath.ui.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.testing.CohortFixtures;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static qupath.ext.flowpath.ui.cohort.CohortGridModel.CellMark.*;

class CohortGridModelTest {

    private static CohortGridModel.Row row(CohortGridModel m, String id) {
        return m.rows().stream().filter(r -> r.slideId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void twoRootsOnOneChannelAreTwoColumns() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortGridModel m = CohortGridModel.derive(CohortFixtures.sampled(tree), tree, null, false);
        assertEquals(List.of("#1 CD8", "#2 CD8"), m.columns().stream().map(CohortGridModel.Column::header).toList());
        assertEquals(List.of(0, 1), m.columns().stream().map(CohortGridModel.Column::rootIndex).toList());
    }

    @Test
    void theReferenceRowIsOkAndMarked() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortGridModel m = CohortGridModel.derive(CohortFixtures.sampled(tree), tree, null, false);
        CohortGridModel.Row ref = row(m, "ref");
        assertTrue(ref.reference());
        // Root 1 sits on the negative peak, so the fixture flags it on ref too (brief-sanctioned fallback).
        assertEquals(OK, ref.marks().get(0));
        assertEquals(LOOK, ref.marks().get(1), "the fixture flags root 1 on its negative peak");
        assertFalse(ref.canExclude(), "the reference cannot be excluded (Review Focus 1)");
    }

    @Test
    void aFlaggedCellIsLookAndCountsTowardsTheRow() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        ReviewItem item = s.review().items().get(0);
        CohortGridModel m = CohortGridModel.derive(s, tree, null, false);
        CohortGridModel.Row r = row(m, item.key().slideId());
        assertEquals(LOOK, r.marks().get(item.key().rootIndex()));
        assertTrue(r.lookCount() >= 1);
    }

    @Test
    void slideSettingsWinOverAlignment() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        tree.getRoots().get(0).setSlideSetting("s2", new SlideSetting.Skip());
        tree.getRoots().get(1).setSlideSetting("s2", new SlideSetting.Manual(GateValues.of(new double[]{123})));
        CohortGridModel m = CohortGridModel.derive(s, tree, null, false);
        assertEquals(List.of(SKIPPED, ADJUSTED), row(m, "s2").marks());
    }

    @Test
    void noReferenceMeansNotCorrectedEverywhereAndASuggestion() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        tree.setReferenceSlideId(null);
        CohortSession s = CohortFixtures.sampled(tree);
        CohortGridModel m = CohortGridModel.derive(s, tree, null, false);
        assertTrue(m.rows().stream().allMatch(r -> r.marks().stream().allMatch(k -> k == NOT_CORRECTED)));
        assertTrue(m.banner().headline().startsWith("No reference slide"));
        assertEquals(s.suggestedReferenceId(), m.banner().suggestedId());
        assertTrue(m.banner().notes().contains(CohortGridModel.SCOPE_NOTE));
    }

    @Test
    void excludedRowsAreGreyAndCarryNoMarks() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        s.setExcluded(Set.of("odd"));
        CohortGridModel m = CohortGridModel.derive(s, tree, null, false);
        CohortGridModel.Row odd = row(m, "odd");
        assertEquals(CohortGridModel.RowStatus.EXCLUDED, odd.status());
        assertEquals(List.of(NONE, NONE), odd.marks());
        assertFalse(odd.canBeReference());
    }

    @Test
    void onlyLooksKeepsRowsWithSomethingToReview() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        CohortGridModel m = CohortGridModel.derive(s, tree, null, true);
        assertFalse(m.rows().isEmpty());
        assertTrue(m.rows().stream().allMatch(r -> r.lookCount() > 0));
    }

    @Test
    void selectionSurvivesTwoPasses() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        ReviewItem.Key key = s.review().items().get(0).key();
        GateTree pass1 = tree.deepCopy();
        GateTree pass2 = pass1.deepCopy();
        CohortGridModel m1 = CohortGridModel.derive(s, pass1, key, false);
        CohortGridModel m2 = CohortGridModel.derive(s, pass2, key, false);
        assertEquals(key, m1.detail().key());
        assertEquals(key, m2.detail().key());
        assertEquals(m1.detail().title(), m2.detail().title());
    }

    @Test
    void detailShowsReferenceAndAppliedValues() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        String path = GateWalk.enabled(tree).get(0).gatePath();
        ReviewItem.Key key = new ReviewItem.Key("odd", 0, path);
        CohortGridModel m = CohortGridModel.derive(s, tree, key, false);
        assertNotNull(m.detail());
        assertNotEquals(m.detail().referenceValue(), m.detail().appliedValue(), "odd is shifted, so its applied cut moved");
    }

    @Test
    void aSlideLackingTheChannelIsNotMeasuredEvenThoughTheReviewScoredACopy() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampledWithout(tree, "s2", "CD8");
        CohortGridModel m = CohortGridModel.derive(s, tree, null, false);
        assertEquals(List.of(NOT_MEASURED, NOT_MEASURED), row(m, "s2").marks());
    }

    @Test
    void aReviewScoredForAnotherReferenceIsNotTrusted() {
        for (String liveRef : new String[]{null, "s1"}) {
            GateTree tree = CohortFixtures.twoCd8Roots();
            CohortSession s = CohortFixtures.sampled(tree);          // scored with reference "ref"
            assertFalse(s.review().items().isEmpty(), "fixture check");
            tree.setReferenceSlideId(liveRef);                       // the rescore has not landed
            ReviewItem.Key key = s.review().items().get(0).key();
            CohortGridModel m = CohortGridModel.derive(s, tree, key, false);
            assertTrue(m.rows().stream().allMatch(r -> !r.marks().contains(LOOK) && r.lookCount() == 0),
                    "no stale LOOK for reference " + liveRef);
            if (liveRef == null) {
                assertTrue(m.rows().stream().allMatch(r -> r.marks().stream().allMatch(k -> k == NOT_CORRECTED)));
            }
            assertNotNull(m.detail());
            assertTrue(m.detail().reasons().isEmpty());
        }
    }

    @Test
    void aNotMeasuredDetailCarriesTheScorersMessage() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampledWithout(tree, "s2", "CD8");
        ReviewItem.Key key = new ReviewItem.Key("s2", 0, GateWalk.enabled(tree).get(0).gatePath());
        CohortGridModel m = CohortGridModel.derive(s, tree, key, false);
        assertEquals(NOT_MEASURED, m.detail().mark());
        assertEquals(1, m.detail().reasons().size());
        assertTrue(m.detail().reasons().get(0).contains("not measured"));
    }

    /** Final review item 5: the model carries the selected cell, so the grid can highlight it. */
    @Test
    void theSelectedCellIsCarriedOnItsRow() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        String path1 = GateWalk.enabled(tree).get(1).gatePath();
        ReviewItem.Key key = new ReviewItem.Key("s2", 1, path1);
        CohortGridModel m = CohortGridModel.derive(s, tree, key, false);
        assertEquals(1, row(m, "s2").selectedColumn());
        for (String other : List.of("ref", "s1", "odd")) assertEquals(-1, row(m, other).selectedColumn(), other);

        // N / P moved the selection: the next derive moves the highlight with it, over a fresh tree copy.
        ReviewItem.Key next = new ReviewItem.Key("odd", 0, GateWalk.enabled(tree).get(0).gatePath());
        CohortGridModel moved = CohortGridModel.derive(s, tree.deepCopy(), next, false);
        assertEquals(-1, row(moved, "s2").selectedColumn());
        assertEquals(0, row(moved, "odd").selectedColumn());
        assertEquals(next, moved.detail().key());

        assertTrue(CohortGridModel.derive(s, tree, null, false).rows().stream().allMatch(r -> r.selectedColumn() == -1));
    }

    /** Final review item 7: ☆ is clickable on a sampling row while there is no reference, not for a rebase. */
    @Test
    void aSamplingRowCanBeConfirmedButNotRebasedOnto() {
        assertTrue(CohortGridModel.canBeReference(CohortGridModel.RowStatus.SAMPLING, false, null));
        assertFalse(CohortGridModel.canBeReference(CohortGridModel.RowStatus.SAMPLING, false, "ref"));
        assertTrue(CohortGridModel.canBeReference(CohortGridModel.RowStatus.READY, false, "ref"));
        assertFalse(CohortGridModel.canBeReference(CohortGridModel.RowStatus.READY, true, "ref"));
        assertFalse(CohortGridModel.canBeReference(CohortGridModel.RowStatus.EXCLUDED, false, null));
        assertFalse(CohortGridModel.canBeReference(CohortGridModel.RowStatus.FAILED, false, null));

        GateTree tree = CohortFixtures.twoCd8Roots();
        tree.setReferenceSlideId(null);
        CohortSession s = new CohortSession();
        s.setProjectSlides(List.of(new CohortSession.SlideRef("a", "a.tif"), new CohortSession.SlideRef("b", "b.tif")));
        s.setLiveTree(tree);
        s.samplingStarted();
        CohortGridModel m = CohortGridModel.derive(s, tree, null, false);
        assertEquals(CohortGridModel.RowStatus.SAMPLING, row(m, "a").status());
        assertTrue(row(m, "a").canBeReference(), "spec §8: ☆ still clickable while sampling");
    }

    /** Final review item 8: the card names the reference and the guarded look count. */
    @Test
    void theCardNamesTheReferenceAndWhatToLookAt() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        int n = CohortGridModel.toLookAt(s, tree);
        assertTrue(n > 0, "fixture check");
        assertEquals("Cohort · 4 slides · ★ ref.tif · " + n + " to look at", CohortGridModel.cardLine(s, tree, true));

        s.setExcluded(Set.of("odd"));
        assertTrue(CohortGridModel.cardLine(s, tree, true).startsWith("Cohort · 3 slides · ★ ref.tif · "));

        // A review scored for another reference counts nothing, as the grid shows nothing.
        GateTree moved = tree.deepCopy();
        moved.setReferenceSlideId("s1");
        assertEquals(0, CohortGridModel.toLookAt(s, moved));

        s.samplingStarted();
        assertEquals(s.statusLine(true), CohortGridModel.cardLine(s, tree, true), "while sampling: the status line");
    }

    @Test
    void withNoReferenceTheCardPromptsForTheSuggestion() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        tree.setReferenceSlideId(null);
        CohortSession s = CohortFixtures.sampled(tree);
        assertEquals("Pick a reference slide — suggested: " + s.slideName(s.suggestedReferenceId()),
                CohortGridModel.cardLine(s, tree, true));
    }

    /** Final review item 9: the footer carries the "channels missing on some slides" notes. */
    @Test
    void theFooterCarriesTheMissingChannelNotes() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortGridModel m = CohortGridModel.derive(CohortFixtures.sampledWithout(tree, "s2", "CD8"), tree, null, false);
        assertEquals(1, m.missingChannels().size(), "two roots on one channel say it once: " + m.missingChannels());
        assertTrue(m.missingChannels().get(0).startsWith("s2.tif — CD8 is not measured"));
        assertTrue(CohortGridModel.derive(CohortFixtures.sampled(tree), tree, null, false).missingChannels().isEmpty());
    }
}
