package qupath.ext.flowpath.ui.cohort;

import java.util.Objects;

/**
 * What a ☆ / "Use X" click does to the tree's reference (spec 2026-09-30 §4.1): the clicked slide
 * always ends up the reference. A tree with gates and no reference first takes the slide the user
 * says the gates were drawn on — its thresholds are that slide's numbers — and then rebases onto
 * the clicked slide once the cohort has a model for the first; each step is its own undo step.
 * Toolkit-free; {@code FlowPathPane} asks the dialog and applies the answer.
 *
 * @param step     what to do
 * @param confirm  the slide to confirm as the tree's first reference, or null
 * @param rebaseTo the slide to rebase onto (after the confirmation, if any), or null
 */
public record ReferenceChoice(Step step, String confirm, String rebaseTo) {

    public enum Step { NOTHING, CONFIRM, CONFIRM_THEN_REBASE, REBASE }

    /** What a pending second half ({@link Step#CONFIRM_THEN_REBASE}'s rebase) does when a rescore lands. */
    public enum Landing {
        /** Nothing is pending. */
        NONE,
        /** The confirmed reference is gone (an undo, a load): forget the rebase silently. */
        DROP,
        /** The cohort has no model for the confirmed reference yet: keep it pending. */
        WAIT,
        /** The confirmed reference cannot be rebased from: say why and forget the rebase. */
        REFUSE,
        /** Rebase now. */
        REBASE
    }

    /**
     * What the user is told when a pending rebase onto {@code toName} is dropped or refused —
     * always, whatever the reason, so ☆ never silently does less than it said. {@code staysName}
     * is the reference the tree keeps (null when an undo left it with none); {@code reason} is the
     * refusal, or null.
     */
    public static String notMadeReference(String toName, String staysName, String reason) {
        String line = toName + " was not made the reference; "
                + (staysName == null ? "there is no reference slide" : staysName + " stays the reference");
        return reason == null ? line : line + ". " + reason;
    }

    private static final ReferenceChoice NOTHING = new ReferenceChoice(Step.NOTHING, null, null);

    /**
     * @param current   the tree's reference, or null
     * @param clicked   the slide whose ☆ was clicked
     * @param origin    the slide the user says the gates were drawn on (null when the tree has no
     *                  gates or the dialog was cancelled)
     * @param hasGates  whether the tree has gates (only then is the origin asked)
     * @param cancelled whether the origin question was cancelled
     */
    public static ReferenceChoice decide(String current, String clicked, String origin, boolean hasGates, boolean cancelled) {
        if (clicked == null) return NOTHING;
        if (current != null) {
            return current.equals(clicked) ? NOTHING : new ReferenceChoice(Step.REBASE, null, clicked);
        }
        if (!hasGates) return new ReferenceChoice(Step.CONFIRM, clicked, null);
        if (cancelled || origin == null) return NOTHING;
        return origin.equals(clicked)
                ? new ReferenceChoice(Step.CONFIRM, clicked, null)
                : new ReferenceChoice(Step.CONFIRM_THEN_REBASE, origin, clicked);
    }

    /**
     * @param pendingFrom    the reference a pending rebase starts from, or null when none is pending
     * @param treeReference  the tree's reference now
     * @param modelReference the reference the adopted alignment model was built for
     * @param refused        whether {@code CohortSession.rebaseRefusal(pendingFrom)} refuses
     * @param sampling       whether slides are still being sampled (a refusal may yet clear)
     */
    public static Landing landing(String pendingFrom, String treeReference, String modelReference,
                                  boolean refused, boolean sampling) {
        if (pendingFrom == null) return Landing.NONE;
        if (!pendingFrom.equals(treeReference)) return Landing.DROP;
        if (!Objects.equals(pendingFrom, modelReference)) return Landing.WAIT;
        if (refused) return sampling ? Landing.WAIT : Landing.REFUSE;
        return Landing.REBASE;
    }
}
