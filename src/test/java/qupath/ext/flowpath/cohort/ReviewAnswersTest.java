package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewAnswersTest {

    static final Alignment SHIFT = Alignment.auto(30, 0.01);
    static final AlignmentLookup LOOKUP = (slide, col) -> "s1".equals(slide) ? SHIFT : null;

    static GateTree tree() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        for (double t : new double[]{400, 600}) {
            GateNode g = new GateNode("CD8", t);
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        return tree;
    }

    @Test
    void looksRightRecordsTheAppliedNumberForThatGateOnly() {
        GateTree tree = tree();
        GateNode b = tree.getRoots().get(1);
        ReviewAnswers.looksRight(tree, b, "s1", LOOKUP);
        SlideSetting.Reviewed r = assertInstanceOf(SlideSetting.Reviewed.class, b.slideSetting("s1"));
        assertTrue(r.appliedValues().matches(GateValues.of(new double[]{SHIFT.apply(600)})));
        assertNull(tree.getRoots().get(0).slideSetting("s1"), "the same-channel sibling root is untouched");
        assertTrue(ReviewScorer.answered(b, "s1", TreeResolver.resolve(tree, "s1", LOOKUP).applied(b).applied()),
                "the answered item is hidden by the one rule");
    }

    @Test
    void adjustKeepsTheReferenceAndRecordsTheDraggedValueInThisSlidesUnits() {
        GateTree tree = tree();
        GateNode b = tree.getRoots().get(1);
        GateValues baseline = GateValues.read(b);
        b.setThreshold(650);                                  // the user's drag, in reference units
        ReviewAnswers.adjust(tree, b, "s1", LOOKUP, baseline);
        assertEquals(600, b.getThreshold(), "no other slide moves");
        SlideSetting.Manual m = assertInstanceOf(SlideSetting.Manual.class, b.slideSetting("s1"));
        assertEquals(SHIFT.apply(650), m.values().axis(0)[0], 1e-9);
        assertEquals(SHIFT.apply(650), TreeResolver.resolve(tree, "s1", LOOKUP).resolvedOf(b).getThreshold(), 1e-9);
        assertEquals(400, tree.getRoots().get(0).getThreshold());
        assertNull(tree.getRoots().get(0).slideSetting("s1"), "the same-channel sibling root is untouched");
    }

    @Test
    void skipRecordsSkip() {
        GateTree tree = tree();
        ReviewAnswers.skip(tree.getRoots().get(0), "s1");
        assertInstanceOf(SlideSetting.Skip.class, tree.getRoots().get(0).slideSetting("s1"));
        assertNull(tree.getRoots().get(1).slideSetting("s1"));
    }

    @Test
    void anAnswerRecordsTheSlidesNameOnlyWhenTheTreeLacksIt() {
        GateTree tree = tree();
        tree.setSlideNames(Map.of("ref", "ref.tif"));
        ReviewAnswers.recordSlideName(tree, "s1", "s1.tif");
        assertEquals(Map.of("ref", "ref.tif", "s1", "s1.tif"), tree.getSlideNames());
        ReviewAnswers.recordSlideName(tree, "s1", "renamed.tif");
        assertEquals("s1.tif", tree.getSlideNames().get("s1"), "a recorded name is never overwritten");
        ReviewAnswers.recordSlideName(tree, "s2", null);
        ReviewAnswers.recordSlideName(tree, null, "x.tif");
        assertEquals(2, tree.getSlideNames().size(), "nothing to record without both an id and a name");
    }

    /**
     * A quadrant's sliders move one axis per tick, and every tick puts the reference back: the
     * axis this tick did not move keeps the value an earlier tick recorded for this slide.
     */
    @Test
    void aQuadrantAdjustKeepsTheAxisThisTickDidNotMove() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        qupath.ext.flowpath.model.QuadrantGate q = new qupath.ext.flowpath.model.QuadrantGate("CD8", "CD4", 400, 500);
        q.setStatisticX(Statistic.MEAN);
        q.setStatisticY(Statistic.MEAN);
        tree.addRoot(q);
        GateNode sibling = new GateNode("CD8", 300);
        sibling.setStatistic(Statistic.MEAN);
        tree.addRoot(sibling);
        GateValues baseline = GateValues.read(q);

        q.setThresholdX(450);                               // tick 1: X only
        ReviewAnswers.adjust(tree, q, "s1", LOOKUP, baseline);
        q.setThresholdY(550);                               // tick 2: Y only; X is back at 400
        ReviewAnswers.adjust(tree, q, "s1", LOOKUP, baseline);

        SlideSetting.Manual m = assertInstanceOf(SlideSetting.Manual.class, q.slideSetting("s1"));
        assertEquals(SHIFT.apply(450), m.values().axis(0)[0], 1e-9, "X kept from tick 1");
        assertEquals(SHIFT.apply(550), m.values().axis(1)[0], 1e-9);
        assertEquals(400, q.getThresholdX());
        assertEquals(500, q.getThresholdY());
        assertNull(sibling.slideSetting("s1"));
    }
}
