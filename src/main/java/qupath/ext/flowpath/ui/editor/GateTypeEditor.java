package qupath.ext.flowpath.ui.editor;

import javafx.scene.Node;

/**
 * The controls for one gate of one type: a threshold gate's histogram and slider, a quadrant
 * gate's two sliders and scatter, a region gate's drawing toolbar and scatter.
 * <p>
 * One instance serves one gate for one showing. {@code GateEditorPane} builds it when a gate is
 * opened and disposes it when the gate changes, so every widget reference lives and dies with
 * the editor that created it. The pane used to hold those references itself, in nine fields
 * every builder had to reset by hand; one that forgot left a control from the previous gate
 * wired to the next one. A disposed editor writes to nothing, whatever event reaches it.
 * <p>
 * Obtain one through {@link GateTypeEditors#forGate}, the one place the gate type is decided.
 */
public interface GateTypeEditor {

    /** Build this gate's controls, with their data-driven state already filled in. Called once. */
    Node build();

    /**
     * Bring every data-driven control in line with new statistics, masks or clip percentiles,
     * without rebuilding — a rebuild would discard a polygon half-drawn on the scatter plot.
     */
    void refresh();

    /** Stop writing to the gate. Called when the pane shows a different gate, or none. */
    void dispose();

    /** A branch colour changed: repaint whatever this editor draws in branch colours. */
    default void branchColorsChanged() {}

    /** The gating pass updated branch counts: show them, if this editor shows any. */
    default void updatePopulationCounts() {}
}
