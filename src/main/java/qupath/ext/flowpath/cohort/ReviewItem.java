package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateValues;

import java.util.List;
import java.util.Locale;

/**
 * One slide × gate that needs a look, and why, in words. Remembered across recomputes by its
 * {@link Key} — a value, never the {@code GateNode}, since undo swaps in fresh nodes (CLAUDE.md
 * "anything the user selects is keyed on a value"). {@code gate} is the node of the tree that was
 * scored — a background copy — so the click-through finds the live gate by key
 * ({@code CohortSession.liveGate}), never through this field.
 */
public record ReviewItem(Key key, String slideName, GateNode gate, List<Flag> flags, List<String> reasons,
                         GateValues applied) {

    public record Key(String slideId, int rootIndex, String gatePath) {}

    /**
     * The review flags, declared in severity order ({@code ordinal()} sorts a slide's flags).
     * Each carries the glyph the grid shows, a plain label and where its rule comes from.
     */
    public enum Flag {
        PEAK_LOCK("\u21C6", "Aligned on the positive peak?", "Heuristic on UniFORM's stated assumption (Wang et al. 2025)"),
        NO_NEGATIVE_PEAK("\u2205", "No negative peak", "Hahne et al. 2010 inspection rule"),
        CANT_JUDGE("#", "Too few cells", "FlowPath heuristic"),
        OTSU_DISCORDANCE("\u2260", "Otsu disagrees after correction", "Harris et al. 2022 metric; 10% cut is FlowPath's"),
        SHIFT_OUTLIER("\u2195", "Shift unusual for the cohort", "Hahne et al. 2010; 3-MAD cut is FlowPath's"),
        BELOW_RANGE("<1", "Many values outside the log scale", "FlowPath heuristic on UniFORM's \u2265 1 rule"),
        MARKER_RULE("\u00B1", "Lineage double positives", "FlowPath heuristic"),
        ON_PEAK("\u22C0", "Threshold sits on a peak", "FlowPath heuristic");

        private final String glyph;
        private final String label;
        private final String source;

        Flag(String glyph, String label, String source) {
            this.glyph = glyph;
            this.label = label;
            this.source = source;
        }

        public String glyph() { return glyph; }
        public String label() { return label; }
        public String source() { return source; }

        public String token() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Resolved automatically — listed, never an item. */
    public record Info(String slideId, String slideName, GateNode gate, String message) {}
}
