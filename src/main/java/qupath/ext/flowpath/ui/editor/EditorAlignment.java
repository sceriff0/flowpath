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

    /**
     * The reference slide's name, or null outside a cohort — and null too when no slide of this
     * project is the reference (a foreign tree, a deleted reference): the cohort's own answer.
     */
    String referenceName();

    /**
     * Why correction between slides is off although a reference is set — a tree from another
     * project, a reference missing from or excluded in this one — or null when it is not off.
     * The cohort's own message, so the gating panel says what the Cohort window's banner says.
     */
    default String correctionOffReason() { return null; }

    /** The open slide's name, or null when no slide resolves (none open, or a foreign tree). */
    default String currentSlideName() { return null; }

    /** Whether the open slide is the tree's reference. */
    default boolean isReferenceSlide() { return false; }

    EditorAlignment IDENTITY = new EditorAlignment() {
        @Override public Alignment forAxis(GateNode gate, int axis) { return Alignment.identity(); }
        @Override public String referenceName() { return null; }
    };
}
