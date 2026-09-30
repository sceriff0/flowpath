package qupath.ext.flowpath.cohort;

import java.util.prefs.Preferences;

/** FlowPath's cohort preference, on {@code java.util.prefs} like {@code AnalysisWindowPrefs}. */
public final class CohortPrefs {

    public static final int DEFAULT_SAMPLED_CELLS = 20000;
    static final String SAMPLED_CELLS_KEY = "sampledCellsPerSlide";

    private CohortPrefs() {}

    public static Preferences node() {
        return Preferences.userNodeForPackage(CohortPrefs.class);
    }

    /** Cells sampled per slide; 0 means every cell. */
    public static int sampledCellsPerSlide(Preferences node) {
        return Math.max(0, node.getInt(SAMPLED_CELLS_KEY, DEFAULT_SAMPLED_CELLS));
    }

    public static void setSampledCellsPerSlide(Preferences node, int cells) {
        node.putInt(SAMPLED_CELLS_KEY, Math.max(0, cells));
    }
}
