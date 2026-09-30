package qupath.ext.flowpath.ui.cohort;

import javafx.scene.input.KeyCode;

/** The review keys; see {@link #of}. */
public enum ReviewKey {
    LOOKS_RIGHT, SKIP, NEXT, PREVIOUS, BACK, TOGGLE_OVERLAY, REVIEW_GROUP, OPEN_IN_VIEWER;

    /**
     * As {@link #of(KeyCode, boolean, boolean)}, with Shift told apart: Shift+Enter (and no
     * other modifier) answers the selected gate's whole group. It is matched before the plain
     * keys, so it can never answer a single item; Shift with any other key is nothing.
     */
    public static ReviewKey of(KeyCode code, boolean shift, boolean otherModifier, boolean textFieldFocused) {
        if (shift && !otherModifier && !textFieldFocused && code == KeyCode.ENTER) return REVIEW_GROUP;
        return of(code, shift || otherModifier, textFieldFocused);
    }

    /**
     * The review action a key press asks for, or null. Plain keys only — every existing
     * FlowPath shortcut carries a modifier, so none of these collide — and never while a text
     * field has focus, where Enter and letters belong to the field.
     */
    public static ReviewKey of(KeyCode code, boolean anyModifier, boolean textFieldFocused) {
        if (anyModifier || textFieldFocused || code == null) return null;
        return switch (code) {
            case ENTER -> LOOKS_RIGHT;
            case S -> SKIP;
            case N -> NEXT;
            case P -> PREVIOUS;
            case ESCAPE -> BACK;
            case B -> TOGGLE_OVERLAY;
            case V -> OPEN_IN_VIEWER;
            default -> null;
        };
    }
}
