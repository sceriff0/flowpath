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

    public enum Flag {
        NO_LANDMARK, UNUSUAL_STAINING, ON_PEAK, CANT_JUDGE, MARKER_RULE;

        public String token() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Resolved automatically — listed, never an item. */
    public record Info(String slideId, String slideName, GateNode gate, String message) {}
}
