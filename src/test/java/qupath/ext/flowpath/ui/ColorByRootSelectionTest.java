package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The tree toolbar's "Color by…" picker remembers a root, not a position.
 * <p>
 * {@code FlowPathPane.refreshColorByRootCombo()} used to take the picker's selected
 * <em>index</em>, refill it and select that same index again. Anything that renumbers the
 * enabled roots therefore moved the selection onto a different root and repainted the whole
 * slide by it — and this round added drag-and-drop reordering, which renumbers them as an
 * ordinary gesture. That is the value-keyed-selection invariant, one layer out from the
 * {@code DenominatorRef}/{@code PopulationRef} pair the Analysis window uses for the same
 * reason.
 * <p>
 * <b>Two enabled roots throughout</b>, including two on the <em>same channel</em>: the picker's
 * entries are the roots' channel names, so two roots on one channel are byte-identical entries
 * and a name-keyed selection would collapse them onto whichever came first. A single-root test
 * cannot see any of this — with one entry there is only ever index 0 to restore.
 * <p>
 * Driven against {@link ColorByRootSelection} directly rather than through the pane, which
 * needs a live {@code QuPathGUI} and cannot be constructed in the suite; the pane is left as
 * the two-line adapter that calls this.
 */
class ColorByRootSelectionTest {

    @Test
    void movingTheSelectedRootFollowsTheRootRatherThanItsOldPosition() {
        ColorByRootSelection selection = new ColorByRootSelection();

        assertEquals(-1, selection.rebuild(List.of("CD3", "CD8")),
                "nothing is selected yet, so there is nothing to restore");
        selection.selected(1);                       // the user picks CD8
        assertEquals("CD8#0", selection.selectedKey());

        // The user drags CD8 above CD3. The entries are the same two names, renumbered.
        int restored = selection.rebuild(List.of("CD8", "CD3"));

        assertEquals(0, restored, "the picker follows CD8 to its new position");
        assertNotEquals(1, restored,
                "restoring the old index 1 would be CD3 -- a different root, and a repaint of "
                        + "the whole slide by it");
        assertEquals("CD8#0", selection.selectedKey(), "still the same root");
    }

    /**
     * The move again, but on this codebase's named blind spot: two enabled roots on one
     * channel, whose picker entries are indistinguishable by name. Only the ordinal tells them
     * apart, and it must survive a rebuild that does not reorder them.
     */
    @Test
    void twoRootsOnOneChannelStayDistinctAcrossARebuild() {
        ColorByRootSelection selection = new ColorByRootSelection();

        assertEquals(List.of("CD3#0", "CD3#1"),
                ColorByRootSelection.keysFor(List.of("CD3", "CD3")),
                "two roots on one channel must not collapse onto one key");

        selection.rebuild(List.of("CD3", "CD3"));
        selection.selected(1);                       // the second CD3 root
        assertEquals("CD3#1", selection.selectedKey());

        assertEquals(1, selection.rebuild(List.of("CD3", "CD3")),
                "an unchanged pair restores the second root, not the first");

        // A differently-named root joins ahead of both: the second CD3 is still the second CD3.
        assertEquals(2, selection.rebuild(List.of("CD8", "CD3", "CD3")),
                "the ordinal counts same-named entries only, so an unrelated root moving in "
                        + "front does not steal the selection");
        assertEquals("CD3#1", selection.selectedKey());
    }

    /**
     * A selected root that is deleted or disabled is forgotten, not silently transferred to
     * whichever root inherits its index — which is what {@code select(prev)} did whenever
     * {@code prev} still happened to be in range.
     */
    @Test
    void aSelectedRootThatDisappearsIsForgottenRatherThanTransferred() {
        ColorByRootSelection selection = new ColorByRootSelection();
        selection.rebuild(List.of("CD3", "CD8", "CD20"));
        selection.selected(2);                       // CD20
        assertEquals("CD20#0", selection.selectedKey());

        assertEquals(-1, selection.rebuild(List.of("CD3", "CD8")),
                "CD20 is gone; index 2 no longer exists and index 1 is a different root");
        assertNull(selection.selectedKey(), "and the stale key is not kept around");
    }

    /**
     * JavaFX reports a transient {@code -1} while a {@code ComboBox}'s items are replaced, and
     * the pane's listener passes every index it sees straight through. An out-of-range index
     * must not erase a selection a rebuild is in the middle of restoring — {@code clear()} is
     * the deliberate way to forget one.
     */
    @Test
    void anOutOfRangeIndexLeavesTheSelectionAlone() {
        ColorByRootSelection selection = new ColorByRootSelection();
        selection.rebuild(List.of("CD3", "CD8"));
        selection.selected(1);

        selection.selected(-1);
        assertEquals("CD8#0", selection.selectedKey());
        selection.selected(7);
        assertEquals("CD8#0", selection.selectedKey());

        selection.clear();
        assertNull(selection.selectedKey());
    }
}
