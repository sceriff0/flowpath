package qupath.ext.flowpath.ui.editor;

import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Region2DGate;

/**
 * The one place a gate's type chooses its editor.
 * <p>
 * By {@code instanceof}: {@code GateNode} is sealed but concrete, because it is itself the
 * threshold gate, so "neither a quadrant nor a region gate" is how a threshold gate is named.
 */
public final class GateTypeEditors {

    private GateTypeEditors() {}

    public static GateTypeEditor forGate(GateNode gate, EditorContext context) {
        if (gate instanceof QuadrantGate quadrant) return new QuadrantGateEditor(quadrant, context);
        if (gate instanceof Region2DGate region) return new Region2DGateEditor(region, context);
        return new ThresholdGateEditor(gate, context);
    }
}
