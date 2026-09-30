package qupath.ext.flowpath.mirage;

import java.nio.file.Path;
import java.util.prefs.Preferences;

/** "New project from MIRAGE…" preferences, on {@code java.util.prefs} like {@code CohortPrefs}. */
public final class MiragePrefs {

    static final String GEOMETRY_KEY = "mirageGeometry";
    static final String LAST_FOLDER_KEY = "mirageLastFolder";

    private MiragePrefs() {}

    public static Preferences node() {
        return Preferences.userNodeForPackage(MiragePrefs.class);
    }

    /** The cells choice last used; {@link MirageRun.Geometry#CELL_AND_NUCLEUS} before any, or for a value this version does not know. */
    public static MirageRun.Geometry geometry(Preferences node) {
        String stored = node.get(GEOMETRY_KEY, MirageRun.Geometry.CELL_AND_NUCLEUS.name());
        try {
            return MirageRun.Geometry.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return MirageRun.Geometry.CELL_AND_NUCLEUS;
        }
    }

    public static void setGeometry(Preferences node, MirageRun.Geometry geometry) {
        node.put(GEOMETRY_KEY, geometry.name());
    }

    /** The MIRAGE output folder last imported from, or {@code null}. */
    public static Path lastFolder(Preferences node) {
        String stored = node.get(LAST_FOLDER_KEY, null);
        return stored == null ? null : Path.of(stored);
    }

    public static void setLastFolder(Preferences node, Path folder) {
        node.put(LAST_FOLDER_KEY, folder.toString());
    }
}
