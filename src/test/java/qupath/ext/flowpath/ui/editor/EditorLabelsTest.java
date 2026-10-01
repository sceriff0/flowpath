package qupath.ext.flowpath.ui.editor;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.cohort.Alignment;

import static org.junit.jupiter.api.Assertions.*;

/** The gating panel's reference line: which slide is the reference, and what that means here. */
class EditorLabelsTest {

    @Test
    void noReferenceSaysThresholdsAreNotCorrected() {
        assertEquals("No reference slide — thresholds are not corrected between slides",
                EditorLabels.referenceLine(null, "slide_B", false, Alignment.identity()));
    }

    @Test
    void onTheReferenceEditsMoveEverySlide() {
        assertEquals("★ Reference: slide_A — you are on it; edits move every slide",
                EditorLabels.referenceLine("slide_A", "slide_A", true, Alignment.identity()));
    }

    @Test
    void aCorrectedSlideShowsItsFactorAndHowItWasFound() {
        // exp(-20 * 0.01) = 0.8187...
        assertEquals("★ Reference: slide_A · this slide ×0.82 (automatic)",
                EditorLabels.referenceLine("slide_A", "slide_B", false, Alignment.auto(-20, 0.01)));
        assertEquals("★ Reference: slide_A · this slide ×0.82 (picked peak)",
                EditorLabels.referenceLine("slide_A", "slide_B", false, Alignment.landmark(-20, 0.01)));
    }

    @Test
    void anUncorrectedSlideSaysSo() {
        assertEquals("★ Reference: slide_A · this slide not corrected",
                EditorLabels.referenceLine("slide_A", "slide_B", false, Alignment.identity()));
    }

    /** The grid's format, ×%.2f: a strong correction rounds like it does in the grid. */
    @Test
    void theFactorUsesTheGridsFormat() {
        // exp(-300 * 0.01) = 0.0498
        assertEquals("★ Reference: slide_A · this slide ×0.05 (automatic)",
                EditorLabels.referenceLine("slide_A", "slide_B", false, Alignment.auto(-300, 0.01)));
        // exp(250 * 0.01) = 12.18
        assertEquals("★ Reference: slide_A · this slide ×12.18 (picked peak)",
                EditorLabels.referenceLine("slide_A", "slide_B", false, Alignment.landmark(250, 0.01)));
    }

    /** No open slide, or no gate shown: the reference alone, nothing claimed about this slide. */
    @Test
    void withoutAnOpenSlideOrAGateOnlyTheReferenceIsNamed() {
        assertEquals("★ Reference: slide_A", EditorLabels.referenceLine("slide_A", null, false, Alignment.auto(-20, 0.01)));
        assertEquals("★ Reference: slide_A", EditorLabels.referenceLine("slide_A", "slide_B", false, null));
    }
}
