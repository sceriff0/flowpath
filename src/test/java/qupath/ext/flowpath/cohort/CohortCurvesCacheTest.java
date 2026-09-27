package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Two CD8 roots on one channel; the shown gate is a CD4 child under root 1 (see {@link CohortCurvesTest#tree}). */
class CohortCurvesCacheTest {

    private final List<GateNode> computedFor = new ArrayList<>();
    private final CohortCurvesCache cache = new CohortCurvesCache((tree, gate, samples, lookup, current) -> {
        computedFor.add(gate);
        return List.of(new CohortCurves.SlideValues("s", "s.tif", false, new double[]{computedFor.size()}, null));
    });

    private final GateNode child = childGate();
    private final GateTree tree = CohortCurvesTest.tree(child);
    private final List<SlideSample> samples = List.of(CohortCurvesTest.sample("ref", true), CohortCurvesTest.sample("s1", true));
    private final Object model = new Object();

    private static GateNode childGate() {
        GateNode g = new GateNode("CD4", 5);
        g.setStatistic(Statistic.MEAN);
        return g;
    }

    private List<CohortCurves.SlideValues> ask() {
        return cache.get(tree, child, List.copyOf(samples), model, null, "s1");
    }

    @Test
    void anUnchangedKeyReturnsTheCachedAnswer() {
        List<CohortCurves.SlideValues> first = ask();
        assertSame(first, ask(), "same key, and a fresh copy of the same samples list");
        assertEquals(1, computedFor.size());
    }

    @Test
    void theShownGatesOwnThresholdIsNotInTheKey() {
        List<CohortCurves.SlideValues> first = ask();
        child.setThreshold(99);
        assertSame(first, ask(), "its curves are its parent population, which a threshold drag does not move");
    }

    @Test
    void theOtherSameChannelRootIsNotOnThePath() {
        List<CohortCurves.SlideValues> first = ask();
        tree.getRoots().get(0).setThreshold(35);
        assertSame(first, ask(), "root 0 is not an ancestor of the shown gate");
    }

    @Test
    void anAncestorsThresholdInvalidates() {
        List<CohortCurves.SlideValues> first = ask();
        tree.getRoots().get(1).setThreshold(25);
        assertNotSame(first, ask());
        assertEquals(2, computedFor.size());
    }

    @Test
    void anAncestorsEnabledFlagClippingOrSlideSettingInvalidates() {
        GateNode parent = tree.getRoots().get(1);
        List<Runnable> edits = List.of(
                () -> parent.setEnabled(false),
                () -> parent.setClipPercentileLow(5),
                () -> parent.setExcludeOutliers(!parent.isExcludeOutliers()),
                () -> parent.setSlideSetting("s1", new SlideSetting.Skip()));
        ask();
        int calls = 1;
        for (Runnable edit : edits) {
            edit.run();
            ask();
            assertEquals(++calls, computedFor.size());
        }
    }

    @Test
    void otherSamplesInvalidate() {
        List<CohortCurves.SlideValues> first = ask();
        List<CohortCurves.SlideValues> other = cache.get(tree, child,
                List.of(samples.get(0), CohortCurvesTest.sample("s1", true)), model, null, "s1");
        assertNotSame(first, other);
        assertEquals(2, computedFor.size());
    }

    @Test
    void anotherAlignmentModelInvalidates() {
        ask();
        cache.get(tree, child, samples, new Object(), null, "s1");
        assertEquals(2, computedFor.size());
    }

    @Test
    void anotherGateOrItsSkipOrCorrectionOrOpenSlideInvalidates() {
        ask();
        GateNode sibling = childGate();
        tree.getRoots().get(1).getBranches().get(0).getChildren().add(sibling);
        cache.get(tree, sibling, samples, model, null, "s1");
        assertSame(sibling, computedFor.get(1), "the same-channel sibling is its own key");
        ask();
        child.setSlideSetting("ref", new SlideSetting.Skip());
        ask();
        child.setCorrectStaining(false);
        ask();
        cache.get(tree, child, samples, model, null, "ref");
        assertEquals(6, computedFor.size());
    }
}
