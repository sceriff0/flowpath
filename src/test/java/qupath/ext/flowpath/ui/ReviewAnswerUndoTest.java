package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.ReviewAnswers;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every review answer is one undo step, recorded before the mutation, and the slide's name is
 * recorded in that same step (Task 11 ruling), so undo takes both back together.
 */
class ReviewAnswerUndoTest {

    static final Alignment SHIFT = Alignment.between(new Landmarks(100, 1.0, Double.NaN), new Landmarks(100, 1.3, Double.NaN));
    static final AlignmentLookup LOOKUP = (slide, col) -> "s1".equals(slide) ? SHIFT : null;

    private static GatingSession session() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        tree.setSlideNames(Map.of("ref", "ref.tif"));
        for (double t : new double[]{400, 600}) {
            GateNode g = new GateNode("CD8", t);
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        GatingSession session = new GatingSession(() -> 0L, input -> {});
        session.replaceTree(tree);
        session.settle();
        return session;
    }

    @Test
    void anAnswerAndTheSlidesNameAreOneUndoStep() {
        GatingSession session = session();
        session.recordSlideEdit("s1", "s1.tif",
                () -> ReviewAnswers.looksRight(session.tree(), session.tree().getRoots().get(1), "s1", LOOKUP));
        assertInstanceOf(SlideSetting.Reviewed.class, session.tree().getRoots().get(1).slideSetting("s1"));
        assertEquals("s1.tif", session.tree().getSlideNames().get("s1"));

        assertTrue(session.undo());
        assertNull(session.tree().getRoots().get(1).slideSetting("s1"));
        assertNull(session.tree().getRoots().get(0).slideSetting("s1"));
        assertFalse(session.tree().getSlideNames().containsKey("s1"), "the name goes with the answer");
        assertEquals(2, session.tree().getRoots().size());
        assertTrue(session.undo());
        assertTrue(session.tree().getRoots().isEmpty(), "exactly one step: the one before it is the load");

        assertTrue(session.redo());
        assertTrue(session.redo());
        assertInstanceOf(SlideSetting.Reviewed.class, session.tree().getRoots().get(1).slideSetting("s1"));
        assertEquals("s1.tif", session.tree().getSlideNames().get("s1"));
    }

    /**
     * Adjust is a drag (the editor's own coalesced step) and then Enter (the answer's step).
     * Undoing the answer returns to the tree the answer was given on: the drag still in the
     * reference numbers, no Manual; undoing again returns to the item as it was opened.
     */
    @Test
    void adjustIsTheDragsStepThenTheAnswersStep() {
        GatingSession session = session();
        GateNode b = session.tree().getRoots().get(1);
        GateValues baseline = GateValues.read(b);
        b.setThreshold(650);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);

        session.recordSlideEdit("s1", "s1.tif",
                () -> ReviewAnswers.adjust(session.tree(), session.tree().getRoots().get(1), "s1", LOOKUP, baseline));
        assertEquals(600, session.tree().getRoots().get(1).getThreshold());
        assertInstanceOf(SlideSetting.Manual.class, session.tree().getRoots().get(1).slideSetting("s1"));

        assertTrue(session.undo());
        assertEquals(650, session.tree().getRoots().get(1).getThreshold());
        assertNull(session.tree().getRoots().get(1).slideSetting("s1"));
        assertTrue(session.undo());
        assertEquals(600, session.tree().getRoots().get(1).getThreshold());
        assertEquals(400, session.tree().getRoots().get(0).getThreshold(), "the same-channel sibling never moved");
    }
}
