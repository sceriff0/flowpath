package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.model.GateTree;

import java.util.ArrayList;
import java.util.List;

/** Public door onto this package's package-private test fixtures, for tests in other packages. */
public final class CohortTestDoor {
    private CohortTestDoor() {}

    public static GateTree tree() { return ReviewScorerTest.tree(); }

    public static CohortSession sampled(GateTree tree) { return CohortSessionTest.sampledSession(tree); }

    /** The same four slides as {@code ReviewScorerTest.cohort()}, but {@code slideId} has no CD8. */
    public static CohortSession sampledWithout(GateTree tree, String slideId, String channel) {
        if (!"CD8".equals(channel)) throw new IllegalArgumentException("the fixture only knows how to drop CD8");
        long[] seeds = {1, 2, 3, 4};
        double[] shifts = {0.0, 0.05, -0.05, 1.2};
        String[] ids = {"ref", "s1", "s2", "odd"};
        List<SlideSample> samples = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            samples.add(ReviewScorerTest.slide(ids[i], seeds[i], shifts[i], 3000, !ids[i].equals(slideId)));
        }
        CohortSession s = new CohortSession();
        s.setProjectSlides(CohortSessionTest.refs("ref", "s1", "s2", "odd"));
        s.samplingStarted();
        for (SlideSample sample : samples) s.landed(new CohortSampler.Sampled(sample));
        s.samplingFinished();
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
        return s;
    }
}
