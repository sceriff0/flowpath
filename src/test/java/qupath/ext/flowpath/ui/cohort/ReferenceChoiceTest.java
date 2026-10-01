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

    /** Every drop says so and names both slides: after an undo, a load, a project change or a refusal. */
    @Test
    void aDroppedRebaseNamesBothSlidesAndTheReason() {
        // DROP after a load: the tree's reference changed under the pending rebase.
        assertEquals(ReferenceChoice.Landing.DROP, ReferenceChoice.landing("A", "C", "C", false, false));
        assertEquals("slide_B was not made the reference; slide_C stays the reference",
                ReferenceChoice.notMadeReference("slide_B", "slide_C", null));
        // DROP after an undo of the confirmation: no reference left.
        assertEquals(ReferenceChoice.Landing.DROP, ReferenceChoice.landing("A", null, "A", false, false));
        assertEquals("slide_B was not made the reference; there is no reference slide",
                ReferenceChoice.notMadeReference("slide_B", null, null));
        // REFUSE: the confirmed slide stays, with the reason.
        assertEquals("slide_B was not made the reference; slide_A stays the reference. Not aligned yet",
                ReferenceChoice.notMadeReference("slide_B", "slide_A", "Not aligned yet"));
    }

    /** An image switch is not a drop: nothing but the tree's and the model's reference decides. */
    @Test
    void openingAnotherSlideDoesNotDropThePendingRebase() {
        // The landing has no notion of the open slide: a rescore after a switch still waits or rebases.
        assertEquals(ReferenceChoice.Landing.WAIT, ReferenceChoice.landing("A", "A", null, false, false));
        assertEquals(ReferenceChoice.Landing.REBASE, ReferenceChoice.landing("A", "A", "A", false, false));
    }
}
