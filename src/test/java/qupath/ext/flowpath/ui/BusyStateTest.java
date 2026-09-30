package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the panel offers while each background worker runs, as a table.
 * <p>
 * The case that put this class here is the middle row: a derivation in flight used to disable
 * nothing, so Ctrl+E snapshotted a tree beside statistics that were about to be replaced, and
 * the editor stayed live over a gate the session had already swapped out.
 */
class BusyStateTest {

    private record Case(String name, BusyState state, boolean editing, boolean export, String message) {}

    @Test
    void whatEachStateBlocksAndSays() {
        List<Case> cases = List.of(
                new Case("idle", BusyState.IDLE, false, false, null),
                new Case("reading an image's cells",
                        new BusyState(true, false, false, false, false), true, true, "Reading detections…"),
                new Case("recomputing masks and statistics",
                        new BusyState(false, true, false, false, false), true, true, "Recomputing statistics…"),
                new Case("writing the CSV",
                        new BusyState(false, false, true, false, false), false, true, null),
                new Case("a read and an export at once",
                        new BusyState(true, false, true, false, false), true, true, "Reading detections…"),
                new Case("a derivation and an export at once",
                        new BusyState(false, true, true, false, false), true, true, "Recomputing statistics…"));

        for (Case c : cases) {
            assertEquals(c.editing(), c.state().editingBlocked(), c.name() + ": editing");
            assertEquals(c.export(), c.state().exportBlocked(), c.name() + ": export");
            assertEquals(Optional.ofNullable(c.message()), c.state().message(), c.name() + ": message");
        }
    }

    /** A read is the more specific thing to say when both are running. */
    @Test
    void readingIsReportedAheadOfRecomputing() {
        assertEquals(Optional.of("Reading detections…"),
                new BusyState(true, true, false, false, false).message());
    }

    /** An export never blocks editing: it works from a snapshot taken when it started. */
    @Test
    void anExportLeavesTheEditorAlone() {
        assertFalse(new BusyState(false, false, true, false, false).editingBlocked());
        assertTrue(new BusyState(false, false, true, false, false).exportBlocked());
    }

    @Test
    void samplingBlocksOnlyABatchRunAndABatchRunBlocksExportButNotEditing() {
        BusyState sampling = new BusyState(false, false, false, true, false);
        assertFalse(sampling.editingBlocked());
        assertFalse(sampling.exportBlocked());
        // Final ruling I4: a run started mid-sampling would gate the slides not yet sampled
        // uncorrected, against alignments the review never showed.
        assertTrue(sampling.batchBlocked());
        assertFalse(sampling.batchAllowed(true));
        assertEquals(Optional.empty(), sampling.message(),
                "long-running: its progress rides on the normal status line (CohortState.message), not over it");

        BusyState batch = new BusyState(false, false, false, true, true);
        assertFalse(batch.editingBlocked());
        assertTrue(batch.exportBlocked());
        assertTrue(batch.batchBlocked());
        assertEquals(Optional.empty(), batch.message());
        assertTrue(new BusyState(false, false, true, false, false).batchBlocked(), "one background writer at a time");
    }

    /** The one run predicate the button and the status line read: not blocked, and a gate to run. */
    @Test
    void aRunIsAllowedOnlyWhenIdleWithAnEnabledGate() {
        assertTrue(BusyState.IDLE.batchAllowed(true));
        assertFalse(BusyState.IDLE.batchAllowed(false));
        for (BusyState busy : List.of(new BusyState(true, false, false, false, false),
                new BusyState(false, true, false, false, false), new BusyState(false, false, true, false, false),
                new BusyState(false, false, false, true, false), new BusyState(false, false, false, false, true))) {
            assertTrue(busy.batchBlocked(), busy.toString());
            assertFalse(busy.batchAllowed(true), busy.toString());
        }
    }
}
