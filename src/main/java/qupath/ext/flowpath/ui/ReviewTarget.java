package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.cohort.ReviewItem;

/**
 * Which review item Enter and S answer, and whether a slide that has just landed may still be
 * focused — toolkit-free, so the rule {@code FlowPathPane} applies is table-tested rather than
 * living in a pane the suite cannot build.
 * <p>
 * The rule is "the item whose crop is shown": the list's selection. The viewer's open item is
 * answered through the viewer only when it <em>is</em> that item; an open item that is not the
 * selected one is never answered, so Enter can never write to a gate the list and crop are not
 * showing. With nothing selected, the viewer's open item (if any) is answered, as before crops.
 *
 * @param key      the item to answer
 * @param inViewer whether it is the item open in the viewer (its drags may already be an Adjust)
 */
record ReviewTarget(ReviewItem.Key key, boolean inViewer) {

    /**
     * @param viewerItem the item open in the viewer on its own slide, or null
     * @param selected   the list's selected item, or null
     * @return what Enter or S answers, or null when nothing
     */
    static ReviewTarget of(ReviewItem.Key viewerItem, ReviewItem.Key selected) {
        ReviewItem.Key shown = selected != null ? selected : viewerItem;
        if (shown == null) return null;
        return new ReviewTarget(shown, shown.equals(viewerItem));
    }

    /**
     * The pending click-through left after the user selects {@code chosen}: kept only when it is
     * the same item. A V on A then a click on B must not open A when A's slide lands.
     */
    static ReviewItem.Key pendingAfterSelecting(ReviewItem.Key pending, ReviewItem.Key chosen) {
        return pending != null && pending.equals(chosen) ? pending : null;
    }

    /** Whether a landing slide may open {@code key} in the viewer: only while it is still the selection. */
    static boolean mayFocus(ReviewItem.Key key, ReviewItem.Key selected) {
        return key != null && key.equals(selected);
    }
}
