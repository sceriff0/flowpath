package qupath.ext.flowpath.testing;

import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.CohortTestDoor;
import qupath.ext.flowpath.model.GateTree;

/** Public doors onto the cohort package's test fixtures, for tests in other packages. */
public final class CohortFixtures {
    private CohortFixtures() {}

    /** Two roots on CD8 (one in the valley, one on the negative peak), reference "ref". */
    public static GateTree twoCd8Roots() { return CohortTestDoor.tree(); }

    /** Slides ref, s1, s2, odd sampled and scored against {@code tree}. */
    public static CohortSession sampled(GateTree tree) { return CohortTestDoor.sampled(tree); }

    /** As {@link #sampled}, but {@code slideId}'s index carries no {@code channel}. */
    public static CohortSession sampledWithout(GateTree tree, String slideId, String channel) {
        return CohortTestDoor.sampledWithout(tree, slideId, channel);
    }
}
