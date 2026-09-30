package qupath.ext.flowpath.ui.cohort;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewKeyTest {

    /** The review keys: plain letters and Enter, never while a text field has focus. */
    @Test
    void reviewKeysMapWithoutModifiersAndNeverInATextField() {
        assertEquals(ReviewKey.LOOKS_RIGHT, ReviewKey.of(KeyCode.ENTER, false, false));
        assertEquals(ReviewKey.SKIP, ReviewKey.of(KeyCode.S, false, false));
        assertEquals(ReviewKey.NEXT, ReviewKey.of(KeyCode.N, false, false));
        assertEquals(ReviewKey.PREVIOUS, ReviewKey.of(KeyCode.P, false, false));
        assertEquals(ReviewKey.BACK, ReviewKey.of(KeyCode.ESCAPE, false, false));
        assertEquals(ReviewKey.TOGGLE_OVERLAY, ReviewKey.of(KeyCode.B, false, false));
        assertEquals(ReviewKey.OPEN_IN_VIEWER, ReviewKey.of(KeyCode.V, false, false));
        assertNull(ReviewKey.of(KeyCode.V, true, false), "Ctrl+V is Paste, not Open in viewer");
        assertNull(ReviewKey.of(KeyCode.S, true, false), "Ctrl+S is Save, not Skip");
        assertNull(ReviewKey.of(KeyCode.ENTER, false, true), "Enter in a text field is the field's");
        assertNull(ReviewKey.of(KeyCode.S, false, true), "typing an S is not Skip");
        assertNull(ReviewKey.of(KeyCode.X, false, false));
    }

    /** Shift+Enter answers the group; Shift with any other key, or another modifier, is nothing. */
    @Test
    void shiftEnterIsTheGroupsAnswerAndNothingElseTakesShift() {
        assertEquals(ReviewKey.REVIEW_GROUP, ReviewKey.of(KeyCode.ENTER, true, false, false));
        assertEquals(ReviewKey.LOOKS_RIGHT, ReviewKey.of(KeyCode.ENTER, false, false, false));
        assertNull(ReviewKey.of(KeyCode.ENTER, true, true, false), "Ctrl+Shift+Enter is not a review key");
        assertNull(ReviewKey.of(KeyCode.ENTER, true, false, true), "never in a text field");
        assertNull(ReviewKey.of(KeyCode.S, true, false, false), "Shift+S is not Skip");
        assertNull(ReviewKey.of(KeyCode.Z, true, false, false));
    }
}
