package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;

import java.util.List;

/**
 * When a resync must rebuild the gate editor, and which selected gate survives it.
 * <p>
 * {@code GateEditorPane.setGateNode} tears down and rebuilds every control, including the
 * scatter plot, and with it any polygon the user is halfway through drawing. The pane used to
 * call it on every resync, so moving an annotation under the ROI filter threw the drawing
 * away. The editor's plots and sliders re-read new masks and statistics through its data
 * setters, so a rebuild is needed only when those setters cannot cover the change.
 * <p>
 * Toolkit-free so the rule is table-tested across passes; {@code FlowPathPane.render} is its
 * only caller.
 */
final class EditorRebuild {

    private EditorRebuild() {}

    /**
     * Whether the editor must be rebuilt after a resync.
     *
     * @param newIndex      the cells changed (an image read, a re-read or a clear): channel
     *                      lists and the columns every control was built from are different
     * @param treeRewritten a legacy migration rewrote gates in place, so the controls show
     *                      values the gate no longer has
     * @param shown         the gate the editor shows now, or {@code null}
     * @param selected      the gate that should be shown after the resync, as returned by
     *                      {@link #surviving}
     */
    static boolean needed(boolean newIndex, boolean treeRewritten, GateNode shown, GateNode selected) {
        return newIndex || treeRewritten || shown != selected;
    }

    /**
     * {@code selected} if it is still a node of {@code tree}, else {@code null}. Compared by
     * identity: an undo or a load swaps in a tree of fresh {@code GateNode}s, and a gate equal
     * in every field but not in the tree would be an editor writing to nothing.
     */
    static GateNode surviving(GateNode selected, GateTree tree) {
        if (selected == null || tree == null) return null;
        return contains(tree.getRoots(), selected) ? selected : null;
    }

    private static boolean contains(List<GateNode> nodes, GateNode target) {
        for (GateNode node : nodes) {
            if (node == target) return true;
            for (Branch branch : node.getBranches()) {
                if (contains(branch.getChildren(), target)) return true;
            }
        }
        return false;
    }
}
