package qupath.ext.flowpath.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * A bounded undo/redo history over immutable-by-convention snapshots of {@code T}.
 * <p>
 * Callers hand the <em>current</em> value to {@link #record}, {@link #recordCoalesced},
 * {@link #undo} and {@link #redo}; this class never holds a reference to "the current
 * state" itself — that stays owned by the caller (e.g. {@code FlowPathPane.gateTree}).
 * Each recorded entry is produced by applying {@code snapshotFn} to the value passed in,
 * so callers should pass a deep-copy function (e.g. {@code GateTree::deepCopy}) to avoid
 * aliasing a value that later mutates in place.
 * <p>
 * The clock is injected so the 500ms coalescing window in {@link #recordCoalesced} is
 * assertable without sleeping in tests.
 */
public final class UndoHistory<T> {

    /**
     * How many steps of history the FlowPath gating tree keeps.
     * <p>
     * Named because it is a product decision — how far back a user can walk their gating
     * edits — not an implementation detail of this class. It lived as a bare {@code 50}
     * at the one construction site in {@code FlowPathPane}, where nothing pinned it:
     * changing it to 20 broke no test. Both the pane and {@code UndoStackTest} now read
     * it from here, so the cap under test is the cap that ships.
     */
    public static final int DEFAULT_MAX_DEPTH = 50;

    private final int maxDepth;
    private final UnaryOperator<T> snapshotFn;
    private final LongSupplier clock;

    private final Deque<T> undoStack = new ArrayDeque<>();
    private final Deque<T> redoStack = new ArrayDeque<>();
    private long lastRecordTime = 0;

    public UndoHistory(int maxDepth, UnaryOperator<T> snapshotFn, LongSupplier clock) {
        this.maxDepth = maxDepth;
        this.snapshotFn = snapshotFn;
        this.clock = clock;
    }

    /**
     * Push a snapshot of {@code current} onto the undo stack, clear the redo stack
     * (a fresh edit invalidates any previously undone future), and enforce the
     * depth cap by dropping the oldest entry if needed.
     */
    public void record(T current) {
        undoStack.push(snapshotFn.apply(current));
        if (undoStack.size() > maxDepth) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    /**
     * Like {@link #record}, but coalesces bursts of rapid edits (e.g. dragging a
     * slider) into a single undo step: only records if at least 500ms have elapsed
     * since the last recorded snapshot.
     */
    public void recordCoalesced(T current) {
        long now = clock.getAsLong();
        if (now - lastRecordTime > 500) {
            record(current);
            lastRecordTime = now;
        }
    }

    /**
     * Undo one step: push {@code current} onto the redo stack and return the
     * previous state, or {@link Optional#empty()} if there is nothing to undo.
     */
    public Optional<T> undo(T current) {
        if (undoStack.isEmpty()) return Optional.empty();
        redoStack.push(snapshotFn.apply(current));
        T previous = undoStack.pop();
        lastRecordTime = 0;
        return Optional.of(previous);
    }

    /**
     * Redo one step: push {@code current} onto the undo stack and return the
     * next state, or {@link Optional#empty()} if there is nothing to redo.
     */
    public Optional<T> redo(T current) {
        if (redoStack.isEmpty()) return Optional.empty();
        undoStack.push(snapshotFn.apply(current));
        T next = redoStack.pop();
        lastRecordTime = 0;
        return Optional.of(next);
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Drop all history and reset the coalescing window. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        lastRecordTime = 0;
    }
}
