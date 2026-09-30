package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.ReviewGroup;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final ruling I5: two sibling gates with the same label under one branch — what Duplicate makes —
 * used to share {@code (rootIndex, gatePath)}, so an answer to the copy's item landed on the
 * original. Paths now carry an ordinal among same-label siblings, descendants included. Two
 * enabled roots on one channel throughout, so the root index is exercised too.
 */
class SiblingGateKeyTest {

    private final GatingSession session = new GatingSession(new AtomicLong(10_000)::get, input -> {});
    private final ReviewFlow flow = new ReviewFlow(session);

    /** Two CD3 roots; under root 0's CD3+ a CD8 gate (with a CD4 child) and its duplicate. */
    SiblingGateKeyTest() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        tree.setSlideNames(Map.of("ref", "ref.tif", "s1", "s1.tif", "s2", "s2.tif"));
        for (double t : new double[]{10, 20}) {
            GateNode root = new GateNode("CD3", t);
            root.setStatistic(Statistic.MEAN);
            tree.addRoot(root);
        }
        GateNode cd8 = new GateNode("CD8", 5);
        cd8.getBranches().get(0).getChildren().add(new GateNode("CD4", 7));
        GateNode cd3Positive = tree.getRoots().get(0);
        cd3Positive.getBranches().get(0).getChildren().add(cd8);
        // What FlowPathPane.duplicateSelectedGate does: a deep copy, added to the same branch.
        cd3Positive.getBranches().get(0).getChildren().add(cd8.deepCopy());
        session.replaceTree(tree);
        session.settle();
    }

    private GateNode original() { return session.tree().getRoots().get(0).getBranches().get(0).getChildren().get(0); }

    private GateNode copy() { return session.tree().getRoots().get(0).getBranches().get(0).getChildren().get(1); }

    private GateWalk.Entry entryOf(GateNode gate) {
        for (GateWalk.Entry e : GateWalk.enabled(session.tree())) if (e.gate() == gate) return e;
        throw new AssertionError("not walked");
    }

    private ReviewItem item(String slide, GateNode gate) {
        GateWalk.Entry e = entryOf(gate);
        return new ReviewItem(new ReviewItem.Key(slide, e.rootIndex(), e.gatePath()), slide + ".tif", gate,
                List.of(ReviewItem.Flag.ON_PEAK), List.of("on a peak"), GateValues.read(gate));
    }

    @Test
    void everyEnabledGateHasItsOwnKeyDescendantsIncluded() {
        List<String> keys = new ArrayList<>();
        for (GateWalk.Entry e : GateWalk.enabled(session.tree())) keys.add(e.rootIndex() + ":" + e.gatePath());
        assertEquals(List.of("0:CD3", "0:CD3+/CD8", "0:CD3+/CD8+/CD4", "0:CD3+/CD8#2", "0:CD3+/CD8+#2/CD4", "1:CD3"), keys);
    }

    @Test
    void skipOnTheCopyLandsOnTheCopy() {
        ReviewItem.Key copyKey = item("s1", copy()).key();
        assertSame(copy(), flow.open(copyKey));
        assertTrue(flow.answerSkip("s1", "s1.tif"));
        assertInstanceOf(SlideSetting.Skip.class, copy().slideSetting("s1"));
        assertNull(original().slideSetting("s1"), "the original is untouched");
        assertTrue(session.undo());
        assertNull(copy().slideSetting("s1"), "one undo step takes it back");
    }

    @Test
    void shiftEnterClearsEachGroupOnItsOwnGate() {
        List<ReviewGroup> groups = ReviewGroup.of(List.of(item("s1", original()), item("s2", original()),
                item("s1", copy()), item("s2", copy())));
        assertEquals(2, groups.size(), "the original and its copy are two groups");
        ReviewGroup copyGroup = groups.get(1);
        assertEquals(new ReviewGroup.Key(0, "CD3+/CD8#2"), copyGroup.key());

        assertEquals(2, flow.answerGroup(copyGroup, id -> id + ".tif", AlignmentLookup.NONE));
        for (String slide : List.of("s1", "s2")) {
            assertInstanceOf(SlideSetting.Reviewed.class, copy().slideSetting(slide), slide);
            assertNull(original().slideSetting(slide), "the original's group is still open on " + slide);
        }
        assertEquals(2, flow.answerGroup(groups.get(0), id -> id + ".tif", AlignmentLookup.NONE));
        for (String slide : List.of("s1", "s2")) {
            assertInstanceOf(SlideSetting.Reviewed.class, original().slideSetting(slide), slide);
        }
    }

    /** Two gates that still answer to one key — a channel literally named "CD8#2" — are refused. */
    @Test
    void anAmbiguousKeyIsRefused() {
        original().getBranches().get(0).getChildren().clear();
        GateNode literal = new GateNode("CD8#2", 3);
        session.tree().getRoots().get(0).getBranches().get(0).getChildren().add(literal);
        assertNull(CohortSession.liveGate(session.tree(), new ReviewItem.Key("s1", 0, "CD3+/CD8#2")));
        assertSame(original(), CohortSession.liveGate(session.tree(), new ReviewItem.Key("s1", 0, "CD3+/CD8")));
    }
}
