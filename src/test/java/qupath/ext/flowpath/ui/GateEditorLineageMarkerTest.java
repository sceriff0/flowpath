package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The "Lineage marker" tick: threshold gates only, offered only in a cohort, written to the gate
 * before it is reported (so the host records it as one undoable gate edit and rescores). An editor
 * shows one gate by nature, so the two-root rule does not apply here; {@code MarkerRulesTest}
 * covers two same-channel roots.
 */
class GateEditorLineageMarkerTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static CheckBox lineageBox(Parent root) {
        List<CheckBox> found = new ArrayList<>();
        collect(root, found);
        return found.stream().filter(b -> "Lineage marker".equals(b.getText())).findFirst().orElseThrow();
    }

    private static void collect(Parent root, List<CheckBox> out) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof CheckBox b) out.add(b);
            if (child instanceof Parent p) collect(p, out);
        }
    }

    @Test
    void theTickIsOfferedOnThresholdGatesInACohortAndWritesBeforeReporting() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3", 10);
        gate.setStatistic(Statistic.MEAN);
        AtomicInteger reports = new AtomicInteger();
        boolean[] flagWhenReported = new boolean[1];
        FxTestSupport.onFxRun(() -> {
            GateEditorPane pane = new GateEditorPane();
            pane.setChannelNames(List.of("CD3"));
            pane.setOnNodeChanged(n -> { reports.incrementAndGet(); flagWhenReported[0] = n.isLineageMarker(); });
            pane.setGateNode(gate);
            CheckBox box = lineageBox(pane);
            assertFalse(box.isSelected(), "defaults off");
            assertFalse(box.isVisible(), "hidden outside a cohort");
            pane.setCohortAvailable(true);
            assertTrue(box.isVisible());
            assertEquals(0, reports.get(), "showing a gate is not an edit");
            box.setSelected(true);
        });
        assertTrue(gate.isLineageMarker());
        assertEquals(1, reports.get());
        assertTrue(flagWhenReported[0], "written before it is reported");
    }

    @Test
    void aTwoAxisGateIsNeverOfferedTheTick() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate quad = new QuadrantGate("CD3", "CD4", 10, 20);
        GateNode threshold = new GateNode("CD3", 10);
        threshold.setLineageMarker(true);
        boolean[] visible = FxTestSupport.onFx(() -> {
            GateEditorPane pane = new GateEditorPane();
            pane.setChannelNames(List.of("CD3", "CD4"));
            pane.setCohortAvailable(true);
            pane.setGateNode(quad);
            boolean onQuad = lineageBox(pane).isVisible();
            pane.setGateNode(threshold);
            boolean onThreshold = lineageBox(pane).isVisible();
            boolean shown = lineageBox(pane).isSelected();
            return new boolean[]{onQuad, onThreshold, shown};
        });
        assertFalse(visible[0], "quadrant");
        assertTrue(visible[1], "threshold");
        assertTrue(visible[2], "shows the gate's tick");
    }
}
