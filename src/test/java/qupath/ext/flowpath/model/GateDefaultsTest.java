package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every gate type must agree on the default comparison space.
 *
 * <p>There is one space now: the column as measured. The retired z-score flag used to decide
 * whether {@code GatingEngine} compared raw intensities or values FlowPath standardised
 * itself, and it defaulted to z-score, so every new gate was born in a space that could not
 * be reproduced from the export. New gates never carry it; only a legacy file does, until
 * {@link LegacyZScoreMigration} converts it. The flag lives on {@link GateNode} so there is
 * exactly one declaration for that migration to read.
 */
class GateDefaultsTest {

    @Test
    void everyGateTypeDefaultsToRaw() {
        assertFalse(new GateNode("CD3").isThresholdIsZScore(), "threshold gate");
        assertFalse(new GateNode("CD3", 1.0).isThresholdIsZScore(), "threshold gate (with threshold)");
        assertFalse(new GateNode().isThresholdIsZScore(), "threshold gate (no-arg)");
        assertFalse(new QuadrantGate("CD3", "CD8").isThresholdIsZScore(), "quadrant gate");
        assertFalse(new QuadrantGate().isThresholdIsZScore(), "quadrant gate (no-arg)");
        assertFalse(new PolygonGate("CD3", "CD8").isThresholdIsZScore(), "polygon gate");
        assertFalse(new PolygonGate().isThresholdIsZScore(), "polygon gate (no-arg)");
        assertFalse(new RectangleGate("CD3", "CD8", 0, 1, 0, 1).isThresholdIsZScore(), "rectangle gate");
        assertFalse(new RectangleGate().isThresholdIsZScore(), "rectangle gate (no-arg)");
        assertFalse(new EllipseGate("CD3", "CD8", 0, 0, 1, 1).isThresholdIsZScore(), "ellipse gate");
        assertFalse(new EllipseGate().isThresholdIsZScore(), "ellipse gate (no-arg)");
    }

    /**
     * A legacy tree can be snapshotted (undo, live preview) before it meets an index, and the
     * copy must still tell the migration which numbers are in z-space.
     */
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
