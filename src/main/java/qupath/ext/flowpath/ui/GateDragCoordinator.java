package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One gate drag in the tree view: which gate is being dragged, whether the row under the
 * cursor would take it, and what happens when it is dropped there.
 * <p>
 * <b>Why this is not in {@code FlowPathCell}.</b> A {@code TreeCell} is recycled per row and
 * exists in a dozen copies, so the cell the drag <em>started</em> on is not the cell it is
 * dropped on and may well have been scrolled away and reused by then. The drag's identity has
 * to live somewhere that outlives a row; that somewhere is here, handed to every cell by
 * {@code FlowPathPane}'s cell factory. The cells themselves hold no state and decide nothing:
 * they resolve the row under the cursor to a {@link Branch} (or to {@code null}, the tree's
 * background) and ask {@link #accepts} and {@link #drop}.
 * <p>
 * <b>Why not on the dragboard.</b> JavaFX's {@code Dragboard} carries serialisable content,
 * and what a drop needs is the <em>identity</em> of a live {@link GateNode} in the session's
 * tree — a name or an index would have to be resolved back, and two roots on one channel
 * produce byte-identical names (this codebase's recurring blind spot). The dragboard carries a
 * label for the platform's benefit; the gate itself never leaves this field.
 * <p>
 * Toolkit-free and table-tested, like {@link BusyState} and {@code GatingSession}: the rule for
 * what a drop does is not something to read off a JavaFX event handler.
 */
final class GateDragCoordinator {

    private final Supplier<GateTree> tree;
    private final BooleanSupplier editingBlocked;
    private final Runnable recordUndo;
    private final Consumer<GateNode> onMoved;

    /** The gate being dragged, or {@code null} when no drag is in progress. */
    private GateNode dragged;

    /**
     * @param tree           the session's live gate tree — read on every call rather than held,
     *                       because an undo or a load replaces the whole tree object
     * @param editingBlocked {@link BusyState#editingBlocked()}: a drop is a tree edit, and is
     *                       refused for the same reason the gate editor is greyed out
     * @param recordUndo     records the tree as one undo step; called BEFORE the move is applied
     * @param onMoved        the move was applied — rebuild the tree view, reselect the gate it
     *                       is handed, and request a gating pass
     */
    GateDragCoordinator(Supplier<GateTree> tree, BooleanSupplier editingBlocked,
                        Runnable recordUndo, Consumer<GateNode> onMoved) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.editingBlocked = Objects.requireNonNull(editingBlocked, "editingBlocked");
        this.recordUndo = Objects.requireNonNull(recordUndo, "recordUndo");
        this.onMoved = Objects.requireNonNull(onMoved, "onMoved");
    }

    /**
     * A drag has started from {@code gate}'s row.
     *
     * @return {@code true} when the drag may start — refused while editing is blocked, so a
     *         gate cannot even be picked up mid-derivation
     */
    boolean begin(GateNode gate) {
        if (gate == null || editingBlocked.getAsBoolean()) return false;
        dragged = gate;
        return true;
    }

    /** The gate being dragged, or {@code null}. */
    GateNode dragged() {
        return dragged;
    }

    /**
     * Whether dropping the dragged gate on {@code target} would be taken — the hover cue and
     * the drop itself ask this same question, so a row that highlights is a row that accepts.
     *
     * @param target the branch under the cursor, or {@code null} for the tree's background
     *               (which promotes the gate to a root)
     */
    boolean accepts(Branch target) {
        if (dragged == null || editingBlocked.getAsBoolean()) return false;
        return tree.get().checkMove(dragged, target).allowed();
    }

    /**
     * Drop the dragged gate on {@code target}: record the undo step, apply the move, end the
     * drag and report the moved gate. A refused drop does <em>none</em> of that — no undo step,
     * no gating pass, not even an end to the drag, so the user can keep dragging to a row that
     * will take it.
     *
     * @return {@code true} when the move was applied
     */
    boolean drop(Branch target) {
        if (!accepts(target)) return false;
        GateNode gate = dragged;
        // Before the mutation, per the undo contract: recordEdit() snapshots the tree as it is
        // now, so recording afterwards would restore the tree the move already left behind.
        recordUndo.run();
        // accepts() just returned true and nothing has run in between, so this is always true;
        // the guard is here so a future edit cannot leave a recorded undo step with no move.
        if (!tree.get().move(gate, target)) return false;
        dragged = null;
        onMoved.accept(gate);
        return true;
    }

    /** The drag ended without a drop (or after one): forget the gate. */
    void end() {
        dragged = null;
    }
}
