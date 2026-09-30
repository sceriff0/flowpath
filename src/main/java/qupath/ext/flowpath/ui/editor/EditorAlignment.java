package qupath.ext.flowpath.ui.editor;

import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.cohort.Alignment;

/**
 * How the open slide's values map into the gate's reference units, per gate axis. The host
 * answers {@link #forAxis} from {@code TreeResolver.correctionFor}, so the editor and the engine
 * cannot disagree about which axes are corrected.
 */
public interface EditorAlignment {

    /** Reference units to the open slide's units for {@code gate}'s {@code axis}; identity when uncorrected. */
    Alignment forAxis(GateNode gate, int axis);

    /** The reference slide's name, or null outside a cohort. */
    String referenceName();

    EditorAlignment IDENTITY = new EditorAlignment() {
        @Override public Alignment forAxis(GateNode gate, int axis) { return Alignment.identity(); }
        @Override public String referenceName() { return null; }
    };
}
