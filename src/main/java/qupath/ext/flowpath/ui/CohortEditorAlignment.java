package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.ui.editor.EditorAlignment;

import java.util.function.Supplier;

/**
 * The open slide's alignment per gate axis, for the editor's display seam. Read live on every
 * call — the tree, the open slide and the cohort's model are the session's current ones — and
 * answered by {@link TreeResolver#correctionFor}, the same rule the live pass resolves with, so
 * the editor cannot draw an axis as corrected that the pass gates uncorrected. Identity when
 * correction is off, the open slide is the reference, or the tree is foreign (the open slide id
 * is then null).
 * <p>
 * Toolkit-free so it can be tested: {@code FlowPathPane} cannot be constructed in the suite.
 */
final class CohortEditorAlignment implements EditorAlignment {

    private final Supplier<GateTree> tree;
    private final Supplier<String> currentSlideId;
    private final CohortSession cohort;
    private final Supplier<AlignmentLookup> alignments;

    /**
     * @param currentSlideId the open slide's id as the resolver sees it (null for a foreign tree)
     * @param alignments     the lookup the live pass resolves with
     */
    CohortEditorAlignment(Supplier<GateTree> tree, Supplier<String> currentSlideId, CohortSession cohort,
                          Supplier<AlignmentLookup> alignments) {
        this.tree = tree;
        this.currentSlideId = currentSlideId;
        this.cohort = cohort;
        this.alignments = alignments;
    }

    @Override
    public Alignment forAxis(GateNode gate, int axis) {
        return TreeResolver.correctionFor(tree.get(), gate, axis, currentSlideId.get(), alignments.get());
    }

    /**
     * The cohort's answer, never {@code slideName(tree.getReferenceSlideId())}: entry ids restart in
     * every project, so for a foreign tree that would name whichever slide here shares the id.
     */
    @Override
    public String referenceName() {
        return cohort.state().referenceName();
    }

    @Override
    public String correctionOffReason() {
        return cohort.state().available() ? cohort.correctionOffReason() : null;
    }

    @Override
    public String currentSlideName() {
        String slideId = currentSlideId.get();
        return slideId == null ? null : cohort.slideName(slideId);
    }

    @Override
    public boolean isReferenceSlide() {
        String slideId = currentSlideId.get();
        return slideId != null && slideId.equals(tree.get().getReferenceSlideId());
    }
}
