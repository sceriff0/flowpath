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
        assertTrue(m.rows().stream().allMatch(r -> r.marks().stream().allMatch(k -> k == NOT_CORRECTED || k == LOOK)));
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
}
