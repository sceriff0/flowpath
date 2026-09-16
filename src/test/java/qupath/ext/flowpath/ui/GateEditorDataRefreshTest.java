package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Slider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * New statistics or masks reach the controls of the gate on screen without an editor rebuild.
 * <p>
 * The pane skips {@code setGateNode} when only the data changed (an ROI toggle, an annotation
 * edit), because a rebuild discards a half-drawn polygon. The data setters must then carry the
 * whole update: here, the quadrant sliders' travel, which is the scatter plot's clip window.
 * They used to redraw the plot only, so after the statistics moved the plot showed one window
 * and the sliders spanned another. Pinned across two updates, since one update could pass
 * with a slider that happened to be built on the right window.
 */
class GateEditorDataRefreshTest {

    private static final int N = 40;

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static boolean[] cellsBetween(int from, int toExclusive) {
        boolean[] mask = new boolean[N];
        Arrays.fill(mask, from, toExclusive, true);
        return mask;
    }

    private static <T extends Node> void collect(Parent root, Class<T> type, List<T> out) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) out.add(type.cast(child));
            if (child instanceof Parent p) collect(p, type, out);
        }
    }

    private static <T extends Node> T only(Parent root, Class<T> type, int nth) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        assertTrue(found.size() > nth, "expected a " + type.getSimpleName() + " at " + nth);
        return found.get(nth);
    }

    /** {minX, maxX, minY, maxY} of the scatter, and of the two sliders, read on the FX thread. */
    private static double[][] windows(GateEditorPane pane) {
        return FxTestSupport.onFx(() -> {
            ScatterPlotCanvas scatter = only(pane, ScatterPlotCanvas.class, 0);
            Slider x = only(pane, Slider.class, 0);
            Slider y = only(pane, Slider.class, 1);
            return new double[][]{scatter.axisWindow(),
                    {x.getMin(), x.getMax(), y.getMin(), y.getMax()}};
        });
    }

    @Test
    void quadrantSlidersFollowTheScatterWindowWhenOnlyTheStatisticsChange() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        CellIndex index = Cells.of(N).marker("CD3", i -> i).marker("CD4", i -> 2.0 * i).area(50.0).build();
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        // Bare marker columns are whole-cell means; pinned up front so the editor reads them
        // from its first build rather than re-pinning a Median default while it builds.
        gate.setStatisticX(Statistic.MEAN);
        gate.setStatisticY(Statistic.MEAN);
        gate.setThresholdX(20);    // inside every window below, so the slider is not widened
        gate.setThresholdY(40);
        CompartmentCapability capability =
                CompartmentCapability.scan(Arrays.asList(index.getObjects()));

        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3", "CD4"));
            pane.setCompartmentCapability(capability);
            pane.setCellIndex(index);
            pane.setMarkerStats(MarkerStats.compute(index, Cells.allTrue(N)));
            pane.setGateNode(gate);
        });
        double[][] built = windows(pane);
        assertArrayEquals(built[0], built[1], 1e-9, "fixture check: built in agreement");

        double[] previous = built[0];
        for (boolean[] mask : List.of(cellsBetween(10, 30), cellsBetween(15, 35))) {
            // What a resync hands the editor for an ROI toggle or an annotation re-derive:
            // statistics over the new population, and no setGateNode.
            FxTestSupport.onFxRun(() -> {
                pane.setRoiMask(mask);
                pane.setMarkerStats(MarkerStats.compute(index, mask));
            });
            double[][] now = windows(pane);
            assertFalse(Arrays.equals(previous, now[0]), "fixture check: the clip window moved");
            assertArrayEquals(now[0], now[1], 1e-9,
                    "slider travel is the window the scatter now shows");
            previous = now[0];
        }
        assertEquals(20, gate.getThresholdX(), "re-ranging does not move the threshold");
        assertEquals(40, gate.getThresholdY());
    }
}
