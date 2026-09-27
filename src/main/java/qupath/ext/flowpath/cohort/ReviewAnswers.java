package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.SlideSetting;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three answers to a review item, as tree mutations. The caller records one undo step
 * ({@code GatingSession.recordEdit()}) before calling any of them, and records the slide's name
 * ({@link #recordSlideName}) in that same step.
 */
public final class ReviewAnswers {

    private ReviewAnswers() {}

    /**
     * "Looks right": a review of exactly the number applied on this slide now. A stale setting
     * is dropped first, so the recorded number is the one the pass applies once it stands.
     */
    public static void looksRight(GateTree tree, GateNode gate, String slideId, AlignmentLookup lookup) {
        gate.setSlideSetting(slideId, null);
        GateValues applied = TreeResolver.resolve(tree, slideId, lookup).applied(gate).applied();
        gate.setSlideSetting(slideId, new SlideSetting.Reviewed(applied));
    }

    /** "Skip slide for this gate": the gate does not judge this slide; its cells are unmeasured. */
    public static void skip(GateNode gate, String slideId) {
        gate.setSlideSetting(slideId, new SlideSetting.Skip());
    }

    /**
     * "Adjust": the gate holds the user's drag in reference units; record it as this slide's raw
     * {@code Manual} and put the reference numbers back to {@code baseline}, so no other slide
     * moves. The map into this slide's units is {@link TreeResolver#correctionFor}'s — the one the
     * pass corrects with — never one computed here. On a one-axis gate the second map is never
     * applied.
     */
    public static void adjust(GateTree tree, GateNode gate, String slideId, AlignmentLookup lookup, GateValues baseline) {
        GateValues dragged = GateValues.read(gate);
        GateValues onThisSlide = dragged.map(
                TreeResolver.correctionFor(tree, gate, 0, slideId, lookup)::apply,
                TreeResolver.correctionFor(tree, gate, 1, slideId, lookup)::apply);
        baseline.writeTo(gate);
        gate.setSlideSetting(slideId, new SlideSetting.Manual(onThisSlide));
    }

    /**
     * Record {@code slideId}'s image name on the tree when the tree has none for it (a slide
     * setting written for an id the tree never named). A recorded name is never overwritten: a
     * mismatch is what {@link CohortIdentity} reports as a foreign tree. Nothing is recorded
     * without both an id and a name.
     */
    public static void recordSlideName(GateTree tree, String slideId, String slideName) {
        if (slideId == null || slideName == null || tree.getSlideNames().containsKey(slideId)) return;
        Map<String, String> names = new LinkedHashMap<>(tree.getSlideNames());
        names.put(slideId, slideName);
        tree.setSlideNames(names);
    }
}
