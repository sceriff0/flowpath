package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every gate type must agree on the default comparison space.
 *
 * <p>The z-score flag decides whether {@code GatingEngine} compares raw intensities
 * or standardised values, and it decides which space the editor draws its scatter
 * and histogram in. When one gate type defaults differently from the others, a gate
 * added from the type chooser silently lands in a different coordinate space than
 * its neighbours, and a gate converted from one type to another inherits the wrong
 * default. The flag lives on {@link GateNode} so there is exactly one declaration
 * governing every subclass.
 */
class GateDefaultsTest {

    @Test
    void everyGateTypeDefaultsToZScore() {
        assertTrue(new GateNode("CD3").isThresholdIsZScore(), "threshold gate");
        assertTrue(new GateNode().isThresholdIsZScore(), "threshold gate (no-arg)");
        assertTrue(new QuadrantGate("CD3", "CD8").isThresholdIsZScore(), "quadrant gate");
        assertTrue(new QuadrantGate().isThresholdIsZScore(), "quadrant gate (no-arg)");
        assertTrue(new PolygonGate("CD3", "CD8").isThresholdIsZScore(), "polygon gate");
        assertTrue(new PolygonGate().isThresholdIsZScore(), "polygon gate (no-arg)");
        assertTrue(new RectangleGate("CD3", "CD8", 0, 1, 0, 1).isThresholdIsZScore(), "rectangle gate");
        assertTrue(new RectangleGate().isThresholdIsZScore(), "rectangle gate (no-arg)");
        assertTrue(new EllipseGate("CD3", "CD8", 0, 0, 1, 1).isThresholdIsZScore(), "ellipse gate");
        assertTrue(new EllipseGate().isThresholdIsZScore(), "ellipse gate (no-arg)");
    }

    @Test
    void zScoreFlagSurvivesDeepCopyForEveryGateType() {
        for (GateNode gate : new GateNode[]{
                new GateNode("CD3"),
                new QuadrantGate("CD3", "CD8"),
                new PolygonGate("CD3", "CD8"),
                new RectangleGate("CD3", "CD8", 0, 1, 0, 1),
                new EllipseGate("CD3", "CD8", 0, 0, 1, 1)}) {
            gate.setThresholdIsZScore(false);
            assertFalse(gate.deepCopy().isThresholdIsZScore(),
                    gate.getGateType() + " must carry the flag through deepCopy");

            gate.setThresholdIsZScore(true);
            assertTrue(gate.deepCopy().isThresholdIsZScore(),
                    gate.getGateType() + " must carry the flag through deepCopy");
        }
    }

    @Test
    void quadrantGateReadsAndWritesASingleZScoreFlag() {
        // The quadrant gate used to shadow GateNode's field with its own copy, so
        // the two could drift apart depending on which accessor was used.
        QuadrantGate gate = new QuadrantGate("CD3", "CD8");
        gate.setThresholdIsZScore(false);
        assertFalse(gate.isThresholdIsZScore());

        GateNode asBase = gate;
        asBase.setThresholdIsZScore(true);
        assertTrue(gate.isThresholdIsZScore(), "one flag, reachable through either static type");
    }
}
