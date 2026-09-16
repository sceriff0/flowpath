package qupath.ext.flowpath.ui.editor;

import javafx.collections.ObservableList;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;

/**
 * What a {@link GateTypeEditor} reads from, and reports to, the pane that hosts it.
 * <p>
 * The data getters answer <em>now</em>, not at build time: statistics and masks change under
 * an editor that stays on screen, and {@link GateTypeEditor#refresh} re-reads them here.
 */
public interface EditorContext {

    CellIndex cellIndex();

    MarkerStats markerStats();

    CompartmentCapability capability();

    /** The annotation-region mask, or {@code null} for every cell. */
    boolean[] roiMask();

    /** Cells in the shown gate's parent population, or {@code null} for a root gate. */
    boolean[] ancestorMask();

    /** The live channel list; channel pickers share it rather than copy it. */
    ObservableList<String> channelNames();

    /**
     * The gate the pane shows. Usually the editor's own gate; a region editor whose gate was
     * just replaced by a drawn shape of another type keeps running until its rebuild, and then
     * this is the replacement.
     */
    GateNode shownGate();

    /** True while the pane (or an editor) is setting controls programmatically. */
    boolean eventsSuppressed();

    /** Run {@code action} with control events suppressed. Re-entrant. */
    void withSuppressedEvents(Runnable action);

    /** The shown gate was edited and the edit is already written into it. */
    void gateChanged();

    /** The shown gate's branch names may have changed; re-derive the branch-name controls. */
    void branchNamesChanged();

    /** Rebuild the editor for {@code gate} now. */
    void show(GateNode gate);

    /**
     * Rebuild the editor for {@code gate} on the next pulse, if it is still the shown gate —
     * deferred so a rebuild does not tear down the control whose handler is running.
     */
    void showLater(GateNode gate);

    /**
     * Put {@code replacement} into the tree in place of {@code old}, carrying the old gate's
     * settings over, and show it.
     */
    void replaceGate(GateNode old, GateNode replacement);
}
