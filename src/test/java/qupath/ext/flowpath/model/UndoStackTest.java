package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real {@link UndoHistory} (as used by {@code FlowPathPane}) rather than a
 * local reimplementation, so an off-by-one in the cap or a dropped redo-clear would
 * actually fail this test.
 */
class UndoStackTest {

    /** The depth {@code FlowPathPane} actually constructs its history with — not a copy of it. */
    private static final int MAX_UNDO = UndoHistory.DEFAULT_MAX_DEPTH;

    /** A clock the test fully controls, so the 500ms coalescing window is assertable without sleeping. */
    private static UndoHistory<GateTree> newHistory(AtomicLong clockMillis) {
        return new UndoHistory<>(MAX_UNDO, GateTree::deepCopy, clockMillis::get);
    }

    @Test
    void undoRestoresPreviousState() {
        UndoHistory<GateTree> history = newHistory(new AtomicLong(0));

        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("CD45", 1.0));

        history.record(tree);
        tree.getRoots().get(0).setThreshold(5.0);
        assertEquals(5.0, tree.getRoots().get(0).getThreshold());

        Optional<GateTree> previous = history.undo(tree);
        assertTrue(previous.isPresent());
        tree = previous.get();
        assertEquals(1.0, tree.getRoots().get(0).getThreshold());
    }

    @Test
    void redoRestoresUndoneState() {
        UndoHistory<GateTree> history = newHistory(new AtomicLong(0));

        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("CD45", 1.0));

        history.record(tree);
        tree.getRoots().get(0).setThreshold(5.0);

        tree = history.undo(tree).orElseThrow();
        assertEquals(1.0, tree.getRoots().get(0).getThreshold());

        tree = history.redo(tree).orElseThrow();
        assertEquals(5.0, tree.getRoots().get(0).getThreshold());
    }

    @Test
    void newModificationClearsRedoStack() {
        UndoHistory<GateTree> history = newHistory(new AtomicLong(0));

        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("CD45", 1.0));

        history.record(tree);
        tree.getRoots().get(0).setThreshold(5.0);

        tree = history.undo(tree).orElseThrow();
        assertTrue(history.canRedo());

        history.record(tree);
        tree.getRoots().get(0).setThreshold(3.0);
        assertFalse(history.canRedo(), "A fresh record() must invalidate any pending redo");
    }

    @Test
    void undoStackCappedAtMax() {
        assertEquals(50, UndoHistory.DEFAULT_MAX_DEPTH,
            "the shipped undo depth is a product decision — how far back a user can walk "
            + "their gating edits — so changing it should be deliberate, not incidental");

        UndoHistory<GateTree> history = newHistory(new AtomicLong(0));

        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("CD45", 0.0));

        for (int i = 0; i < MAX_UNDO + 10; i++) {
            history.record(tree);
            tree.getRoots().get(0).setThreshold(i + 1);
        }

        // Undo exactly MAX_UNDO times successfully...
        for (int i = 0; i < MAX_UNDO; i++) {
            assertTrue(history.canUndo(), "expected undo step " + i + " to be available");
            tree = history.undo(tree).orElseThrow();
        }
        // ...and no more: the oldest entries beyond the cap were dropped.
        assertFalse(history.canUndo(), "history should be capped at " + MAX_UNDO + " entries");
    }

    @Test
    void recordCoalescedSkipsWithinWindowAndRecordsAfterAQuietGap() {
        AtomicLong clock = new AtomicLong();
        UndoHistory<GateTree> history = newHistory(clock);

        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("CD45", 0.0));

        clock.set(1000);
        history.recordCoalesced(tree); // records; the window now runs from 1000

        clock.set(1400);
        history.recordCoalesced(tree); // 400ms since the last call -> coalesced, window slides to 1400

        clock.set(1900);
        history.recordCoalesced(tree); // exactly 500ms since the last call -> still coalesced (needs > 500)

        clock.set(2401);
        history.recordCoalesced(tree); // 501ms of quiet -> a new burst, records a second entry

        assertTrue(history.canUndo(), "expected the first coalesced entry");
        tree = history.undo(tree).orElseThrow();
        assertTrue(history.canUndo(), "expected the second entry after 501ms of quiet");
        history.undo(tree);
        assertFalse(history.canUndo(), "expected exactly two coalesced entries, no more");
    }

    /**
     * The window slides with every call, so a drag lasting longer than 500ms is still one
     * step. It used to be measured from the burst's first tick: a 1.5s drag became three.
     */
    @Test
    void aBurstLongerThanTheWindowIsStillOneStep() {
        AtomicLong clock = new AtomicLong(1000);
        UndoHistory<GateTree> history = newHistory(clock);
        GateTree tree = new GateTree();

        for (int tick = 0; tick < 10; tick++) {       // 900ms of drag, 100ms apart
            history.recordCoalesced(tree, "filter");
            clock.addAndGet(100);
        }
        assertEquals(1, undoDepth(history, tree));
    }

    @Test
    void undoResetsTheCoalescingWindow() {
        AtomicLong clock = new AtomicLong();
        UndoHistory<GateTree> history = newHistory(clock);

        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("CD45", 0.0));

        clock.set(1000);
        history.recordCoalesced(tree); // records; lastRecordTime = 1000
        tree.getRoots().get(0).setThreshold(1.0);

        tree = history.undo(tree).orElseThrow(); // undo() resets the coalescing window to 0

        // Only 10ms after the previous recorded timestamp: this would be coalesced away
        // (10 < 500) if undo() had not reset the window back to 0.
        clock.set(1010);
        history.recordCoalesced(tree);
        assertTrue(history.canUndo(),
            "undo() must reset the coalescing window so a near-simultaneous edit still records");
    }

    /**
     * A burst coalesces only with itself. A quality-filter drag that starts 100ms after a
     * gate edit is a different action, and folding it into the gate edit's step would make
     * one Ctrl+Z revert both.
     */
    @Test
    void aBurstFromADifferentSourceStartsItsOwnStep() {
        AtomicLong clock = new AtomicLong();
        UndoHistory<GateTree> history = newHistory(clock);
        GateTree tree = new GateTree();

        clock.set(1000);
        history.recordCoalesced(tree, "gate");     // records
        clock.set(1100);
        history.recordCoalesced(tree, "gate");     // same burst -> coalesced away
        clock.set(1200);
        history.recordCoalesced(tree, "filter");   // different source -> records
        clock.set(1300);
        history.recordCoalesced(tree, "filter");   // same burst -> coalesced away

        assertEquals(2, undoDepth(history, tree), "one step per source burst");
    }

    /** A discrete edit ends a burst: the next coalesced edit is a step of its own. */
    @Test
    void aDiscreteRecordEndsTheBurst() {
        AtomicLong clock = new AtomicLong();
        UndoHistory<GateTree> history = newHistory(clock);
        GateTree tree = new GateTree();

        clock.set(1000);
        history.recordCoalesced(tree, "filter");   // records
        clock.set(1050);
        history.record(tree);                      // e.g. the ROI checkbox
        clock.set(1100);
        history.recordCoalesced(tree, "filter");   // must record again

        assertEquals(3, undoDepth(history, tree));
    }

    private static int undoDepth(UndoHistory<GateTree> history, GateTree current) {
        int depth = 0;
        while (history.canUndo()) {
            current = history.undo(current).orElseThrow();
            depth++;
        }
        return depth;
    }

    @Test
    void deepCopyPreservesQualityFilter() {
        GateTree tree = new GateTree();
        tree.getQualityFilter().setMinArea(100.0);

        GateTree copy = tree.deepCopy();
        copy.getQualityFilter().setMinArea(200.0);

        assertEquals(100.0, tree.getQualityFilter().range(QualityFilter.AREA).min(),
            "Original QualityFilter should not change after modifying copy");
    }
}
