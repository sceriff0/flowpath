package qupath.ext.flowpath.model;

/**
 * What the user decided for one gate on one slide, keyed on the gate by
 * {@code ProjectImageEntry.getID()}. Stored on the node, so drag-and-drop, deep copies, undo and
 * the serializer carry it with no gate-id system.
 */
public sealed interface SlideSetting {

    /** Raw values in this slide's own units, used as-is ("Adjust"). */
    record Manual(GateValues values) implements SlideSetting {}

    /** The gate does not judge this slide: its cells are UNMEASURED, never negative. */
    record Skip() implements SlideSetting {}

    /**
     * "Looks right", for exactly these applied values. A review is of a number: when the applied
     * values change, {@code appliedValues.matches(...)} fails and the item returns.
     */
    record Reviewed(GateValues appliedValues) implements SlideSetting {}
}
