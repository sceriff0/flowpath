package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.UnmeasuredReason;

import static org.junit.jupiter.api.Assertions.*;

/** The tree view's unmeasured readout names each reason apart (a Skip is never blamed on QC). */
class UnmeasuredReadoutTest {

    @Test
    void theTooltipSplitsTheCountByReason() {
        GateNode gate = new GateNode("CD3", 100);
        for (int i = 0; i < 3; i++) gate.recordUnmeasured(UnmeasuredReason.ROUND_QC);
        gate.recordUnmeasured(UnmeasuredReason.SKIPPED);
        gate.recordUnmeasured(UnmeasuredReason.NO_VALUE);
        gate.recordUnmeasured(UnmeasuredReason.NO_VALUE);
        String text = FlowPathCell.unmeasuredTooltip(gate);
        assertTrue(text.startsWith("6 cells this gate could not judge"), text);
        assertTrue(text.contains("3 failed round QC"), text);
        assertTrue(text.contains("1 skipped on this slide"), text);
        assertTrue(text.contains("2 have no value"), text);
    }

    @Test
    void aGateWithNothingUnmeasuredSaysNothing() {
        assertEquals(0, new GateNode("CD3", 100).getUnmeasuredCount());
        GateNode gate = new GateNode("CD3", 100);
        gate.recordUnmeasured(UnmeasuredReason.ROUND_QC);
        String text = FlowPathCell.unmeasuredTooltip(gate);
        assertFalse(text.contains("skipped"), text);
        assertFalse(text.contains("no value"), text);
    }
}
