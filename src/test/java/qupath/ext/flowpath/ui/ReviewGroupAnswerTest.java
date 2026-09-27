package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.ReviewGroup;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shift+Enter, driven through {@link ReviewFlow} as {@code FlowPathPane} drives it: every item of
 * one gate's group reviewed, and every touched slide's name recorded, in ONE undo step. Two
 * enabled roots on one channel throughout, so the group is told apart by its root index.
 * <p>
 * With a review item open: an item of the group being answered is folded into that one step (an
 * Adjust its drags wrote stands as its slide's answer); an item of another gate is closed
 * unanswered, its drags left as the edits they were. Neither is an edit "outside" the item, so
 * neither fires the flow's {@code onEnded}.
 */
class ReviewGroupAnswerTest {

    static final Alignment SHIFT = Alignment.between(new Landmarks(100, 1.0, Double.NaN), new Landmarks(100, 1.3, Double.NaN));
    static final AlignmentLookup LOOKUP = (slide, col) -> "s1".equals(slide) ? SHIFT : null;
    static final Map<String, String> NAMES = Map.of("s1", "s1.tif", "s2", "s2.tif");

    private final AtomicLong clock = new AtomicLong(10_000);
    private final GatingSession session = new GatingSession(clock::get, input -> {});
    private final ReviewFlow flow = new ReviewFlow(session);
    private final AtomicInteger ended = new AtomicInteger();

    ReviewGroupAnswerTest() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        tree.setSlideNames(Map.of("ref", "ref.tif"));
        for (double t : new double[]{400, 600}) {
            GateNode g = new GateNode("CD8", t);
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        session.replaceTree(tree);
        session.settle();
        flow.setOnEnded(ended::incrementAndGet);
    }

    private GateNode root(int i) { return session.tree().getRoots().get(i); }

    private static ReviewItem item(String slide, int root) {
        return new ReviewItem(new ReviewItem.Key(slide, root, "CD8"), slide + ".tif", new GateNode("CD8", 1),
                List.of(ReviewItem.Flag.ON_PEAK), List.of("on a peak"), GateValues.of(new double[]{1}));
    }

    /** Root 1's group, on s1 and s2. */
    private static ReviewGroup secondRootGroup() {
        List<ReviewGroup> groups = ReviewGroup.of(List.of(item("s1", 0), item("s1", 1), item("s2", 1)));
        assertEquals(new ReviewGroup.Key(1, "CD8"), groups.get(1).key());
        return groups.get(1);
    }

    private boolean drag(int root, String slide, double threshold) {
        GateNode g = root(root);
        g.setThreshold(threshold);
        boolean manual = flow.gateEdited(g, slide, slide + ".tif", LOOKUP, true);
        session.settle();
        return manual;
    }

    private void assertLoadedState() {
        for (int r = 0; r < 2; r++) {
            assertNull(root(r).slideSetting("s1"));
            assertNull(root(r).slideSetting("s2"));
        }
        assertEquals(600, root(1).getThreshold());
        assertEquals(Map.of("ref", "ref.tif"), session.tree().getSlideNames());
    }

    @Test
    void oneUndoTakesBackEveryReviewedAndEveryNameAtOnce() {
        assertEquals(2, flow.answerGroup(secondRootGroup(), NAMES::get, LOOKUP));

        SlideSetting.Reviewed s1 = assertInstanceOf(SlideSetting.Reviewed.class, root(1).slideSetting("s1"));
        assertEquals(SHIFT.apply(600), s1.appliedValues().axis(0)[0], 1e-9, "the number applied on s1 now");
        SlideSetting.Reviewed s2 = assertInstanceOf(SlideSetting.Reviewed.class, root(1).slideSetting("s2"));
        assertEquals(600, s2.appliedValues().axis(0)[0], 1e-9, "s2 is uncorrected");
        assertNull(root(0).slideSetting("s1"), "the same-channel sibling's own item is not this group's");
        assertEquals("s1.tif", session.tree().getSlideNames().get("s1"));
        assertEquals("s2.tif", session.tree().getSlideNames().get("s2"));

        assertTrue(session.undo());
        assertLoadedState();
        assertTrue(session.undo());
        assertTrue(session.tree().getRoots().isEmpty(), "one step: the one before it is the load");
        assertTrue(session.redo());
        assertTrue(session.redo());
        assertInstanceOf(SlideSetting.Reviewed.class, root(1).slideSetting("s1"));
        assertInstanceOf(SlideSetting.Reviewed.class, root(1).slideSetting("s2"));
        assertEquals(0, ended.get());
    }

    @Test
    void anOpenItemOfTheGroupFoldsIntoTheOneStepAndKeepsItsAdjust() {
        assertNotNull(flow.open(new ReviewItem.Key("s1", 1, "CD8")));
        assertTrue(drag(1, "s1", 650));
        clock.addAndGet(1_000);
        assertTrue(drag(1, "s1", 700));

        assertEquals(1, flow.answerGroup(secondRootGroup(), NAMES::get, LOOKUP), "s1 is answered by its Adjust");
        assertNull(flow.active(), "the item is answered with its group, so closed");
        SlideSetting.Manual m = assertInstanceOf(SlideSetting.Manual.class, root(1).slideSetting("s1"));
        assertEquals(SHIFT.apply(700), m.values().axis(0)[0], 1e-9);
        assertInstanceOf(SlideSetting.Reviewed.class, root(1).slideSetting("s2"));
        assertEquals(0, ended.get(), "the group's answer is not an edit outside the item");

        assertTrue(session.undo());
        assertLoadedState();
        assertTrue(session.undo());
        assertTrue(session.tree().getRoots().isEmpty(), "the item's drags and the group are one step");
    }

    @Test
    void anOpenItemOfAnotherGateClosesUnansweredAndKeepsItsOwnSteps() {
        assertNotNull(flow.open(new ReviewItem.Key("s1", 0, "CD8")));
        assertTrue(drag(0, "s1", 450));

        assertEquals(2, flow.answerGroup(secondRootGroup(), NAMES::get, LOOKUP));
        assertNull(flow.active(), "closed: its answer can no longer fold the group's step into its own");
        assertEquals(0, ended.get());
        assertFalse(flow.answerEnter("s1", "s1.tif", LOOKUP), "nothing open to answer");

        assertTrue(session.undo());
        assertNull(root(1).slideSetting("s1"));
        assertNull(root(1).slideSetting("s2"));
        assertInstanceOf(SlideSetting.Manual.class, root(0).slideSetting("s1"),
                "the other gate's drag is its own step, still there");
        assertTrue(session.undo());
        assertNull(root(0).slideSetting("s1"));
        assertEquals(400, root(0).getThreshold());
    }
}
