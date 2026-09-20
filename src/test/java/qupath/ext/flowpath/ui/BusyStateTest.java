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
                        new BusyState(true, false, false), true, true, "Reading detections…"),
                new Case("recomputing masks and statistics",
                        new BusyState(false, true, false), true, true, "Recomputing statistics…"),
                new Case("writing the CSV",
                        new BusyState(false, false, true), false, true, null),
                new Case("a read and an export at once",
                        new BusyState(true, false, true), true, true, "Reading detections…"),
                new Case("a derivation and an export at once",
                        new BusyState(false, true, true), true, true, "Recomputing statistics…"));

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
                new BusyState(true, true, false).message());
    }

    /** An export never blocks editing: it works from a snapshot taken when it started. */
    @Test
    void anExportLeavesTheEditorAlone() {
        assertFalse(new BusyState(false, false, true).editingBlocked());
        assertTrue(new BusyState(false, false, true).exportBlocked());
    }
}
