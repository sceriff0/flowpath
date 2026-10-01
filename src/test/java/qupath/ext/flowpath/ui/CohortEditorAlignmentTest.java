package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortIdentity;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.testing.CohortFixtures;
import qupath.ext.flowpath.ui.editor.EditorLabels;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final review I1: the gating panel's reference line answers from the cohort, so it agrees with
 * the Cohort window's banner and card — never a same-id slide of this project for a tree from
 * another one, never "No reference slide" for a reference that is set but missing.
 */
class CohortEditorAlignmentTest {

    private static CohortEditorAlignment seam(GateTree tree, CohortSession cohort, String openSlide) {
        return new CohortEditorAlignment(() -> tree, () -> CohortIdentity.resolutionSlideId(tree,
                cohort.projectNames(), openSlide), cohort, cohort::lookup);
    }

    private static String line(CohortEditorAlignment a) {
        return EditorLabels.referenceLine(a.correctionOffReason(), a.referenceName(), a.currentSlideName(),
                a.isReferenceSlide(), null);
    }

    @Test
    void thisProjectsTreeNamesItsReference() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortEditorAlignment a = seam(tree, CohortFixtures.sampled(tree), "s1");
        assertEquals("ref.tif", a.referenceName());
        assertNull(a.correctionOffReason());
        assertEquals("★ Reference: ref.tif", line(a));
    }

    /** Entry ids restart in every project: "ref" here is not the foreign tree's reference. */
    @Test
    void aForeignTreeNamesNoSlideOfThisProjectAndSaysWhy() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        tree.setSlideNames(Map.of("ref", "another-project-ref.tif", "s1", "another-project-s1.tif"));
        CohortSession cohort = CohortFixtures.sampled(tree);
        CohortEditorAlignment a = seam(tree, cohort, "s1");
        assertNull(a.referenceName(), "the slide sharing the id is not the reference");
        assertEquals(CohortSession.FOREIGN_TREE, a.correctionOffReason());
        assertEquals(CohortSession.FOREIGN_TREE, line(a), "the banner's words, not a reference name");
        assertFalse(a.isReferenceSlide());
    }

    @Test
    void aDeletedReferenceSaysItIsMissingNotThatThereIsNone() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession cohort = CohortFixtures.sampled(tree);
        tree.setReferenceSlideId("deleted");
        cohort.setLiveTree(tree);
        CohortEditorAlignment a = seam(tree, cohort, "s1");
        assertEquals(CohortSession.REFERENCE_MISSING, a.correctionOffReason());
        assertEquals(CohortSession.REFERENCE_MISSING, line(a));
    }

    @Test
    void anExcludedReferenceSaysSo() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession cohort = CohortFixtures.sampled(tree);
        cohort.setExcluded(java.util.Set.of("ref"));
        CohortEditorAlignment a = seam(tree, cohort, "s1");
        assertEquals(CohortSession.REFERENCE_EXCLUDED, a.correctionOffReason());
        assertEquals(CohortSession.REFERENCE_EXCLUDED, line(a));
    }
}
