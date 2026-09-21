package qupath.ext.flowpath.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which root gate the tree toolbar's "Color by…" picker is showing, remembered as a
 * <em>value</em> rather than as the picker's selected index.
 * <p>
 * <b>Why not the index.</b> {@code FlowPathPane.refreshColorByRootCombo()} used to take
 * {@code getSelectedIndex()}, refill the picker and {@code select(prev)} — so anything that
 * renumbered the enabled roots silently moved the selection onto a <em>different</em> root and
 * repainted the whole slide by it. Dragging a gate to reorder the tree does exactly that, and
 * so does enabling, disabling or deleting a root above the selected one. This is the same
 * failure {@code DenominatorRef} and {@code PopulationRef} exist to prevent in the Analysis
 * window, one layer out: anything the user selects is keyed on a value, never on a position or
 * a {@code Branch} pointer.
 * <p>
 * <b>Why the key is channel <em>plus ordinal</em> and not the channel alone.</b> The picker's
 * entries are the enabled roots' first channel names, and {@code GateNode} names a root from
 * its channel alone — so two enabled roots on one channel are byte-identical entries, this
 * codebase's named blind spot. A name-keyed selection would collapse them onto whichever came
 * first. The ordinal counts earlier entries carrying the same name, which keeps the two apart
 * and is still a value a tree deep-copy reproduces exactly.
 * <p>
 * <b>The one case the ordinal cannot follow</b> is a <em>same-named</em> root being inserted
 * ahead of the selected one — nothing else distinguishes two roots on one channel, so the
 * selection then lands on its neighbour. This is the same limit {@code PopulationRef}'s
 * {@code rootIndex} lives with, for the same reason, and it is bounded: the entries either
 * side of it are the same channel, so the slide is repainted by a gate on the column the user
 * asked for. Restoring by raw index had no such bound.
 * <p>
 * Toolkit-free and table-tested: {@code FlowPathPane} cannot be constructed in the suite (it
 * needs a live {@code QuPathGUI}), so the rule lives here where it can be driven directly, and
 * the pane is left as the adapter that calls it.
 */
final class ColorByRootSelection {

    /** The value key of each current entry, in the picker's own order. */
    private List<String> keys = List.of();

    /** The key of the entry the user is on, or {@code null} when nothing is selected. */
    private String selected;

    /**
     * The value key of each of {@code rootNames}, in order: the name, plus how many earlier
     * entries carried that same name.
     */
    static List<String> keysFor(List<String> rootNames) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> keys = new ArrayList<>(rootNames.size());
        for (String name : rootNames) {
            keys.add(name + "#" + (seen.merge(name, 1, Integer::sum) - 1));
        }
        return List.copyOf(keys);
    }

    /**
     * Adopt {@code rootNames} as the picker's new entries.
     *
     * @return the index the remembered root is now at, or {@code -1} when it is no longer
     *         among the entries (it was deleted or disabled) — in which case the selection is
     *         forgotten rather than left pointing at whichever root inherited its position
     */
    int rebuild(List<String> rootNames) {
        keys = keysFor(rootNames);
        int at = selected == null ? -1 : keys.indexOf(selected);
        if (at < 0) selected = null;
        return at;
    }

    /**
     * Entry {@code index} is now selected. A negative or out-of-range index is ignored, so the
     * transient {@code -1} JavaFX reports while a {@code ComboBox}'s items are being replaced
     * cannot erase the selection a rebuild is in the middle of restoring; use {@link #clear()}
     * to forget it deliberately.
     */
    void selected(int index) {
        if (index >= 0 && index < keys.size()) selected = keys.get(index);
    }

    /** Forget the selection: there is nothing to colour by. */
    void clear() {
        selected = null;
    }

    /** The remembered root's key, or {@code null}. */
    String selectedKey() {
        return selected;
    }
}
