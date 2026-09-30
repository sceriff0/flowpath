package qupath.ext.flowpath.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
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

    /** An undo entry and when it was recorded, counted by {@link #recorded}; see {@link #undoMark()}. */
    private record Entry<T>(long seq, T value) {}

    private final Deque<Entry<T>> undoStack = new ArrayDeque<>();
    /** Every entry ever pushed onto the undo stack, counted; never decreases, never trimmed. */
    private long recorded;
    private final Deque<T> redoStack = new ArrayDeque<>();
    private long lastRecordTime = 0;
    /** Which source the current coalescing burst belongs to; see {@link #recordCoalesced(Object, Object)}. */
    private Object lastSource = NO_BURST;

    /** Sentinel for "no burst in progress", distinct from every caller's source, null included. */
    private static final Object NO_BURST = new Object();

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
        undoStack.push(new Entry<>(++recorded, snapshotFn.apply(current)));
        if (undoStack.size() > maxDepth) {
            undoStack.removeLast();
        }
        redoStack.clear();
        // A discrete edit ends whatever burst was in progress, so the next coalesced edit
        // is a step of its own rather than folded into one that started before it.
        lastSource = NO_BURST;
    }

    /**
     * Like {@link #record}, but coalesces bursts of rapid edits (e.g. dragging a
     * slider) into a single undo step: only records after more than 500ms of quiet
     * since the previous call.
     * <p>
     * Equivalent to {@link #recordCoalesced(Object, Object)} with a {@code null} source.
     */
    public void recordCoalesced(T current) {
        recordCoalesced(current, null);
    }

    /**
     * Coalesce a burst of rapid edits from one {@code source} into a single undo step.
     * <p>
     * Records when the previous call was more than 500ms ago <em>or</em> came from a
     * different source (compared with {@link Objects#equals}). Without the source, a
     * quality-filter drag started just after a gate edit would fold into the gate edit's
     * step, and one undo would revert both.
     * <p>
     * The window <b>slides</b>: every call, coalesced or not, restarts it. Measured from the
     * burst's first tick instead, a slider drag lasting 1.5s became three undo steps.
     */
    public void recordCoalesced(T current, Object source) {
        long now = clock.getAsLong();
        if (now - lastRecordTime > 500 || !Objects.equals(source, lastSource)) {
            record(current);
            lastSource = source;
        }
        lastRecordTime = now;
    }

    /**
     * Record {@code current} as a step of its own — ending any burst in progress, like
     * {@link #record} — and open a burst for {@code source} at the same instant, so a
     * {@link #recordCoalesced(Object, Object)} from that source straight after it is folded
     * into this step rather than recorded again.
     * <p>
     * For an edit recorded before its write whose completion is reported afterwards as an
     * ordinary coalesced edit: replacing a gate by drawing another shape is recorded here,
     * and the editor's change report that follows must not add a second, no-op step.
     */
    public void recordStartingBurst(T current, Object source) {
        record(current);
        lastSource = source;
        lastRecordTime = clock.getAsLong();
    }

    /**
     * Undo one step: push {@code current} onto the redo stack and return the
     * previous state, or {@link Optional#empty()} if there is nothing to undo.
     */
    public Optional<T> undo(T current) {
        if (undoStack.isEmpty()) return Optional.empty();
        redoStack.push(snapshotFn.apply(current));
        T previous = undoStack.pop().value();
        lastRecordTime = 0;
        lastSource = NO_BURST;
        return Optional.of(previous);
    }

    /**
     * Redo one step: push {@code current} onto the undo stack and return the
     * next state, or {@link Optional#empty()} if there is nothing to redo.
     */
    public Optional<T> redo(T current) {
        if (redoStack.isEmpty()) return Optional.empty();
        undoStack.push(new Entry<>(++recorded, snapshotFn.apply(current)));
        T next = redoStack.pop();
        lastRecordTime = 0;
        lastSource = NO_BURST;
        return Optional.of(next);
    }

    /**
     * A point in the history to fold later steps back to (see {@link #collapseSince}): a count
     * of every step pushed so far, which only grows, so neither the depth cap trimming old
     * steps nor an undo moves it.
     */
    public long undoMark() {
        return recorded;
    }

    /**
     * Fold every step recorded after {@code mark} into the first of them: the undo stack keeps
     * only the state as it stood when the first step after the mark was recorded, so one undo
     * returns there however many steps (drag bursts, an answer) followed. Nothing happens when
     * at most one step was recorded since. Ends any burst in progress.
     */
    public void collapseSince(long mark) {
        int after = 0;
        for (Entry<T> e : undoStack) {
            if (e.seq() <= mark) break;
            after++;
        }
        for (int i = 1; i < after; i++) undoStack.pop();
        lastSource = NO_BURST;
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
        lastSource = NO_BURST;
    }
}
