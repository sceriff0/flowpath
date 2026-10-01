package qupath.ext.flowpath.cohort;

import java.util.prefs.Preferences;

/** FlowPath's cohort preference, on {@code java.util.prefs} like {@code AnalysisWindowPrefs}. */
public final class CohortPrefs {

    public static final int DEFAULT_SAMPLED_CELLS = 20000;
    static final String SAMPLED_CELLS_KEY = "sampledCellsPerSlide";

    static final String LEGEND_EXPANDED_KEY = "legendExpanded";

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

    /** Whether the Cohort window's legend strip is open; open until the user collapses it. */
    public static boolean legendExpanded(Preferences node) {
        return node.getBoolean(LEGEND_EXPANDED_KEY, true);
    }

    public static void setLegendExpanded(Preferences node, boolean expanded) {
        node.putBoolean(LEGEND_EXPANDED_KEY, expanded);
    }
}
