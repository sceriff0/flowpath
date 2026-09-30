package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.ReviewItem;

import static org.junit.jupiter.api.Assertions.*;

class ReviewTargetTest {

    /** Two roots on one channel: the same slide and path, told apart by rootIndex only. */
    static final ReviewItem.Key A = new ReviewItem.Key("s1", 0, "CD8");
    static final ReviewItem.Key B = new ReviewItem.Key("s1", 1, "CD8");
    static final ReviewItem.Key C = new ReviewItem.Key("s2", 0, "CD8");

    @Test
    void enterAnswersTheSelectedItemAndThroughTheViewerOnlyWhenItIsTheOpenOne() {
        assertNull(ReviewTarget.of(null, null));
        assertEquals(new ReviewTarget(A, false), ReviewTarget.of(null, A), "selected, crop only: from the list");
        assertEquals(new ReviewTarget(A, true), ReviewTarget.of(A, A), "open in the viewer and selected");
        assertEquals(new ReviewTarget(B, false), ReviewTarget.of(A, B), "a same-channel sibling open is not the shown item");
        assertEquals(new ReviewTarget(C, false), ReviewTarget.of(A, C));
        assertEquals(new ReviewTarget(A, true), ReviewTarget.of(A, null), "nothing selected: the open item");
    }

    /** V on A, then N to B before A's slide lands: the landing must not open A, and Enter answers B. */
    @Test
    void aPendingClickThroughIsDroppedWhenTheUserMovesOn() {
        ReviewItem.Key pending = A;                                   // V on A: its slide is opening
        pending = ReviewTarget.pendingAfterSelecting(pending, B);    // N / click to B
        assertNull(pending);
        assertFalse(ReviewTarget.mayFocus(A, B), "even a late focus of A is refused while B is selected");
        assertEquals(new ReviewTarget(B, false), ReviewTarget.of(null, B));

        assertEquals(A, ReviewTarget.pendingAfterSelecting(A, A), "re-selecting the same item keeps it");
        assertTrue(ReviewTarget.mayFocus(A, A));
        assertNull(ReviewTarget.pendingAfterSelecting(null, A));
        assertFalse(ReviewTarget.mayFocus(A, null), "nothing selected (Esc): nothing opens");
    }
}
