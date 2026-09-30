package qupath.ext.flowpath.mirage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class MiragePrefsTest {

    private final Preferences node = Preferences.userRoot().node("flowpath-test-mirage-" + System.nanoTime());

    @AfterEach
    void clear() throws BackingStoreException {
        node.removeNode();
    }

    @Test
    void theCellsChoiceDefaultsToCellAndNucleusAndIsRemembered() {
        assertEquals(MirageRun.Geometry.CELL_AND_NUCLEUS, MiragePrefs.geometry(node));
        MiragePrefs.setGeometry(node, MirageRun.Geometry.WHOLE_CELL);
        assertEquals(MirageRun.Geometry.WHOLE_CELL, MiragePrefs.geometry(node));
    }

    @Test
    void anUnknownStoredValueFallsBackToTheDefault() {
        node.put(MiragePrefs.GEOMETRY_KEY, "NUCLEUS_ONLY_FROM_A_FUTURE_VERSION");
        assertEquals(MirageRun.Geometry.CELL_AND_NUCLEUS, MiragePrefs.geometry(node));
    }

    @Test
    void theLastFolderIsRemembered() {
        assertNull(MiragePrefs.lastFolder(node));
        MiragePrefs.setLastFolder(node, java.nio.file.Path.of("/data/mirage_run"));
        assertEquals(java.nio.file.Path.of("/data/mirage_run"), MiragePrefs.lastFolder(node));
    }
}
