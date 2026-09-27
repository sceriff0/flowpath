package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.ReviewAnswers;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.SlideSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * One open review item and what editing and answering it does to the tree — toolkit-free, so the
 * decisions {@code FlowPathPane} applies are table-tested here rather than in a pane the suite
 * cannot build.
 * <p>
 * While an item is open in This slide view, a drag on its threshold or quadrant gate is this
 * slide's {@code Manual} and never moves the reference numbers, so no other slide moves. A region
 * gate has no per-slide Adjust (v1): its item is answered Looks right or Skip, and a drag on it
 * edits the reference as it does outside a review.
 * <p>
 * Undo: opening takes a mark, and every answer folds the steps since into one, so a whole item —
 * any number of drag bursts, then Enter or S — is one undo step back to the item as opened.
 */
final class ReviewFlow {

    private final GatingSession session;

    private ReviewItem.Key active;
    /** The gate's reference numbers when the item was opened; a drag is mapped from, and restored to, these. */
    private GateValues baseline;
    private long mark;
    /** The columns the gate cut when opened; an edit that changes them is no longer this item's. */
    private List<String> columns;

    /** Told when an edit outside the item closed it (see {@link #ReviewFlow}). */
    private Runnable onEnded = () -> {};

    /**
     * Listens to {@code session}: any undo step that is not a gate edit — a quality-filter drag,
     * the ROI toggle, an enabled checkbox, adding or moving a gate — closes the open item first,
     * so the item's answer can never fold that edit into its one undo step.
     */
    ReviewFlow(GatingSession session) {
        this.session = session;
        session.setOnNonGateEdit(() -> {
            if (active == null) return;
            end();
            onEnded.run();
        });
    }

    /** Called when an edit outside the item closed it, so the host can take its visuals down. */
    void setOnEnded(Runnable callback) {
        this.onEnded = callback == null ? () -> {} : callback;
    }

    /**
     * Open {@code key}: its live gate's numbers are the baseline, and the undo history is marked.
     *
     * @return the live gate, or null when the tree no longer holds it (nothing is opened)
     */
    GateNode open(ReviewItem.Key key) {
        GateNode gate = CohortSession.liveGate(session.tree(), key);
        if (gate == null) {
            end();
            return null;
        }
        active = key;
        baseline = GateValues.read(gate);
        columns = columnsOf(gate);
        mark = session.undoMark();
        return gate;
    }

    /** Close the item without answering: drags made since stay, as the edits they were. */
    void end() {
        active = null;
        baseline = null;
        columns = null;
        mark = -1;
    }

    ReviewItem.Key active() {
        return active;
    }

    /** The open item's live gate, only while its slide is {@code openSlideId}. */
    GateNode gate(String openSlideId) {
        if (active == null || !active.slideId().equals(openSlideId)) return null;
        return CohortSession.liveGate(session.tree(), active);
    }

    /**
     * Whether the editor may move {@code shown}'s cut on {@code openSlideId}. Not when This slide
     * view shows a gate that has its own Manual or Skip here and is not the open item's: a drag
     * would move the reference — every other slide's cut — while this slide's applied cut, the
     * one drawn, stays put. Inside the open item a drag is this slide's Manual, so it is editable
     * even though its first tick writes one.
     */
    boolean cutEditable(GateNode shown, String openSlideId, boolean thisSlideView) {
        if (!thisSlideView || shown == null || openSlideId == null || !adjustable(shown)) return true;
        SlideSetting setting = shown.slideSetting(openSlideId);
        if (!(setting instanceof SlideSetting.Manual) && !(setting instanceof SlideSetting.Skip)) return true;
        return gate(openSlideId) == shown;
    }

    /** Whether a drag on the open item's gate is a per-slide Adjust: threshold and quadrant gates only. */
    static boolean adjustable(GateNode gate) {
        return gate != null && !(gate instanceof Region2DGate);
    }

    /**
     * The editor wrote an edit into {@code edited} and reported it. It is recorded as the editor's
     * coalesced step, as any gate edit is; then, when it is a drag on the open item's adjustable
     * gate in This slide view, it becomes this slide's {@code Manual} (its name recorded with it)
     * and the gate's reference numbers go back to the baseline.
     *
     * @return whether the edit was taken as this slide's Manual
     */
    boolean gateEdited(GateNode edited, String openSlideId, String slideName, AlignmentLookup lookup,
                       boolean thisSlideView) {
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
        GateNode gate = gate(openSlideId);
        if (gate == null) {
            // The item's gate is gone from the tree by its key (a root channel switch renames
            // its path): nothing on this slide is this item any more.
            if (active != null && active.slideId().equals(openSlideId)) end();
            return false;
        }
        if (gate != edited) return false;
        if (!columnsOf(gate).equals(columns)) {
            // A channel, compartment or statistic switch: the baseline was on another column,
            // so the edit is an ordinary one and the item is left.
            end();
            return false;
        }
        // Only a move of the cut is an Adjust; a clip, colour or correction edit is not.
        if (!thisSlideView || !adjustable(gate) || GateValues.read(gate).matches(baseline)) return false;
        ReviewAnswers.adjust(session.tree(), gate, openSlideId, lookup, baseline);
        ReviewAnswers.recordSlideName(session.tree(), openSlideId, slideName);
        return true;
    }

    /**
     * Enter: an Adjust already written by the drags is kept; otherwise Looks right records the
     * applied values. Either way the item is one undo step, and it closes.
     *
     * @return whether there was an open item on this slide to answer
     */
    boolean answerEnter(String openSlideId, String slideName, AlignmentLookup lookup) {
        GateNode gate = gate(openSlideId);
        if (gate == null) return false;
        session.recordSlideEdit(openSlideId, slideName, () -> {
            if (!(gate.slideSetting(openSlideId) instanceof SlideSetting.Manual)) {
                ReviewAnswers.looksRight(session.tree(), gate, openSlideId, lookup);
            }
        });
        close();
        return true;
    }

    /** S: skip the slide for this gate, as the item's one undo step. */
    boolean answerSkip(String openSlideId, String slideName) {
        GateNode gate = gate(openSlideId);
        if (gate == null) return false;
        session.recordSlideEdit(openSlideId, slideName, () -> ReviewAnswers.skip(gate, openSlideId));
        close();
        return true;
    }

    /** Each axis's column key, in order: what "the same item" means across edits. */
    private static List<String> columnsOf(GateNode gate) {
        List<String> out = new ArrayList<>();
        List<String> channels = gate.getChannels();
        for (int k = 0; k < GateAxis.axisCount(gate); k++) {
            String channel = k < channels.size() ? channels.get(k) : null;
            out.add(channel == null ? null : CellIndex.keyFor(channel, gate.compartmentAt(k), gate.statisticAt(k)));
        }
        return out;
    }

    private void close() {
        session.collapseSince(mark);
        end();
    }
}
