package qupath.ext.flowpath.ui.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.ui.cohort.ReferenceChoice.Landing;
import qupath.ext.flowpath.ui.cohort.ReferenceChoice.Step;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ☆ on slide B must end with B as the reference: the click's slide, never the open slide. A tree
 * with gates drawn on A takes A first (its numbers are A's numbers), then rebases onto B.
 */
class ReferenceChoiceTest {

    private static void assertChoice(ReferenceChoice c, Step step, String confirm, String rebaseTo) {
        assertEquals(step, c.step());
        assertEquals(confirm, c.confirm());
        assertEquals(rebaseTo, c.rebaseTo());
    }

    @Test
    void noReferenceNoGatesConfirmsTheClickedSlide() {
        assertChoice(ReferenceChoice.decide(null, "B", null, false, false), Step.CONFIRM, "B", null);
    }

    @Test
    void noReferenceGatesDrawnElsewhereConfirmsTheOriginThenRebasesOntoTheClickedSlide() {
        assertChoice(ReferenceChoice.decide(null, "B", "A", true, false), Step.CONFIRM_THEN_REBASE, "A", "B");
    }

    @Test
    void noReferenceGatesDrawnOnTheClickedSlideConfirmsIt() {
        assertChoice(ReferenceChoice.decide(null, "B", "B", true, false), Step.CONFIRM, "B", null);
    }

    @Test
    void cancellingTheOriginQuestionDoesNothing() {
        assertChoice(ReferenceChoice.decide(null, "B", null, true, true), Step.NOTHING, null, null);
    }

    @Test
    void clickingTheCurrentReferenceDoesNothing() {
        assertChoice(ReferenceChoice.decide("A", "A", null, true, false), Step.NOTHING, null, null);
        assertChoice(ReferenceChoice.decide("A", "A", null, false, false), Step.NOTHING, null, null);
    }

    @Test
    void anotherSlideWithAReferenceIsARebase() {
        assertChoice(ReferenceChoice.decide("A", "B", null, true, false), Step.REBASE, null, "B");
        assertChoice(ReferenceChoice.decide("A", "B", null, false, false), Step.REBASE, null, "B");
    }

    @Test
    void noClickedSlideDoesNothing() {
        assertChoice(ReferenceChoice.decide(null, null, null, false, false), Step.NOTHING, null, null);
    }

    /** The second half lands once the cohort has a model for A — never before, never after an undo. */
    @Test
    void thePendingRebaseLandsOnlyOnTheConfirmedReferencesModel() {
        assertEquals(Landing.NONE, ReferenceChoice.landing(null, "A", "A", false, false));
        assertEquals(Landing.DROP, ReferenceChoice.landing("A", null, null, false, false), "undone");
        assertEquals(Landing.DROP, ReferenceChoice.landing("A", "C", "A", false, false), "reference changed meanwhile");
        assertEquals(Landing.WAIT, ReferenceChoice.landing("A", "A", null, true, false), "model not built for A yet");
        assertEquals(Landing.WAIT, ReferenceChoice.landing("A", "A", "C", false, false));
        assertEquals(Landing.WAIT, ReferenceChoice.landing("A", "A", "A", true, true), "A still being sampled");
        assertEquals(Landing.REFUSE, ReferenceChoice.landing("A", "A", "A", true, false));
        assertEquals(Landing.REBASE, ReferenceChoice.landing("A", "A", "A", false, false));
        assertEquals(Landing.REBASE, ReferenceChoice.landing("A", "A", "A", false, true));
    }
}
