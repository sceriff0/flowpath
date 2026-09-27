package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A review item, driven through {@link ReviewFlow} as {@code FlowPathPane} drives it: while it is
 * open in This slide view a drag is this slide's Manual and never moves the reference, and the
 * whole item — any number of drag bursts, then the answer — is one undo step back to the item as
 * opened, the slide's name recorded in it. Two enabled roots on one channel throughout.
 */
class ReviewAnswerUndoTest {

    static final Alignment SHIFT = Alignment.between(new Landmarks(100, 1.0, Double.NaN), new Landmarks(100, 1.3, Double.NaN));
    static final AlignmentLookup LOOKUP = (slide, col) -> "s1".equals(slide) ? SHIFT : null;
    static final ReviewItem.Key SECOND = new ReviewItem.Key("s1", 1, "CD8");

    private final AtomicLong clock = new AtomicLong(10_000);
    private final GatingSession session = new GatingSession(clock::get, input -> {});
    private final ReviewFlow flow = new ReviewFlow(session);

    ReviewAnswerUndoTest() {
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
    }

    private GateNode root(int i) { return session.tree().getRoots().get(i); }

    /** One drag tick as the editor reports it: written into the gate, then reported; the pane then settles. */
    private boolean drag(double threshold, boolean thisSlideView) {
        GateNode g = root(1);
        g.setThreshold(threshold);
        boolean manual = flow.gateEdited(g, "s1", "s1.tif", LOOKUP, thisSlideView);
        session.settle();
        return manual;
    }

    private void assertOpenedState() {
        assertEquals(600, root(1).getThreshold());
        assertNull(root(1).slideSetting("s1"));
        assertEquals(400, root(0).getThreshold());
        assertNull(root(0).slideSetting("s1"));
        assertFalse(session.tree().getSlideNames().containsKey("s1"));
    }

    @Test
    void anAdjustOfTwoDragBurstsAndEnterIsOneUndoStepBackToTheOpenedItem() {
        assertSame(root(1), flow.open(SECOND));
        assertTrue(drag(650, true));
        assertTrue(drag(660, true));
        clock.addAndGet(1_000);                      // a pause: the editor's next burst is a new step
        assertTrue(drag(700, true));

        assertEquals(600, root(1).getThreshold(), "the reference never moved, so no other slide did");
        SlideSetting.Manual m = assertInstanceOf(SlideSetting.Manual.class, root(1).slideSetting("s1"));
        assertEquals(SHIFT.apply(700), m.values().axis(0)[0], 1e-9);
        assertEquals(SHIFT.apply(700), TreeResolver.resolve(session.tree(), "s1", LOOKUP).resolvedOf(root(1)).getThreshold(), 1e-9);
        assertEquals(SHIFT.apply(400), TreeResolver.resolve(session.tree(), "s1", LOOKUP).resolvedOf(root(0)).getThreshold(), 1e-9,
                "the same-channel sibling keeps its corrected number");

        assertTrue(flow.answerEnter("s1", "s1.tif", LOOKUP));
        assertInstanceOf(SlideSetting.Manual.class, root(1).slideSetting("s1"), "Enter keeps the Adjust");
        assertEquals("s1.tif", session.tree().getSlideNames().get("s1"));
        assertNull(flow.active(), "answered, so closed");

        assertTrue(session.undo());
        assertOpenedState();
        assertTrue(session.undo());
        assertTrue(session.tree().getRoots().isEmpty(), "one step: the one before it is the load");

        assertTrue(session.redo());
        assertTrue(session.redo());
        assertEquals(SHIFT.apply(700), ((SlideSetting.Manual) root(1).slideSetting("s1")).values().axis(0)[0], 1e-9);
    }

    @Test
    void looksRightWithoutADragIsOneStepAndRecordsTheName() {
        flow.open(SECOND);
        assertTrue(flow.answerEnter("s1", "s1.tif", LOOKUP));
        SlideSetting.Reviewed r = assertInstanceOf(SlideSetting.Reviewed.class, root(1).slideSetting("s1"));
        assertEquals(SHIFT.apply(600), r.appliedValues().axis(0)[0], 1e-9);
        assertEquals("s1.tif", session.tree().getSlideNames().get("s1"));
        assertTrue(session.undo());
        assertOpenedState();
        assertTrue(session.undo());
        assertTrue(session.tree().getRoots().isEmpty());
    }

    @Test
    void skipAfterADragIsStillOneStep() {
        flow.open(SECOND);
        drag(650, true);
        assertTrue(flow.answerSkip("s1", "s1.tif"));
        assertInstanceOf(SlideSetting.Skip.class, root(1).slideSetting("s1"));
        assertTrue(session.undo());
        assertOpenedState();
    }

    @Test
    void outsideThisSlideViewOrWithNoItemOpenADragEditsTheReference() {
        assertFalse(drag(650, true), "no item open");
        assertEquals(650, root(1).getThreshold());
        flow.open(SECOND);
        assertFalse(drag(700, false), "All slides view");
        assertEquals(700, root(1).getThreshold());
        assertNull(root(1).slideSetting("s1"));
    }

    @Test
    void anEditThatDoesNotMoveTheCutWritesNoManual() {
        flow.open(SECOND);
        root(1).setClipPercentileHigh(98.0);
        assertFalse(flow.gateEdited(root(1), "s1", "s1.tif", LOOKUP, true));
        assertNull(root(1).slideSetting("s1"));
    }

    @Test
    void aChannelSwitchLeavesTheItem() {
        flow.open(SECOND);
        root(1).setChannel("CD3");
        root(1).setThreshold(5);
        assertFalse(flow.gateEdited(root(1), "s1", "s1.tif", LOOKUP, true));
        assertNull(flow.active());
        assertEquals(5, root(1).getThreshold(), "an ordinary edit of the reference");
    }

    @Test
    void aRegionGateHasNoPerSlideAdjust() {
        GateTree tree = session.tree().deepCopy();
        RectangleGate rect = new RectangleGate("CD8", "CD4", 0, 10, 0, 10);
        tree.addRoot(rect);
        session.replaceTree(tree);
        session.settle();
        var entry = qupath.ext.flowpath.model.GateWalk.enabled(session.tree()).get(2);
        GateNode live = flow.open(new ReviewItem.Key("s1", entry.rootIndex(), entry.gatePath()));
        assertSame(root(2), live);
        ((RectangleGate) live).setMaxX(20);
        assertFalse(flow.gateEdited(live, "s1", "s1.tif", LOOKUP, true));
        assertEquals(20, ((RectangleGate) live).getMaxX(), "a region drag stays a reference edit");
        assertNull(live.slideSetting("s1"));
        assertFalse(ReviewFlow.adjustable(live));
    }

    @Test
    void anItemOnAnotherSlideIsNotAnsweredHere() {
        flow.open(SECOND);
        assertFalse(flow.answerEnter("s2", "s2.tif", LOOKUP));
        assertNull(root(1).slideSetting("s1"));
    }
}
