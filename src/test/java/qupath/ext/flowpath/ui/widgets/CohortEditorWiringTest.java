package qupath.ext.flowpath.ui.widgets;

import javafx.scene.Node;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortCurves;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.ext.flowpath.ui.GateEditorPane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shown gate's editor hands the All slides values to its canvas. In this package so the
 * canvases' package-private counts are readable; the pane is driven through its public face.
 */
class CohortEditorWiringTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static CohortCurves.SlideValues slide(String id, boolean current, int n, boolean twoAxis) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = 10 + i;
        return new CohortCurves.SlideValues(id, id + ".tif", current, x, twoAxis ? x.clone() : null);
    }

    private static GateEditorPane pane(CellIndex index, Function<GateNode, List<CohortCurves.SlideValues>> values) {
        GateEditorPane pane = new GateEditorPane();
        pane.setChannelNames(List.of("CD3", "CD8"));
        pane.setCompartmentCapability(CompartmentCapability.scan(Arrays.asList(index.getObjects())));
        pane.setCellIndex(index);
        pane.setMarkerStats(MarkerStats.compute(index, Cells.allTrue(index.size())));
        pane.setCohortAvailable(true);
        pane.setCohortValues(values);
        return pane;
    }

    private static <T extends Node> List<T> find(Parent root, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) out.add(type.cast(child));
            if (child instanceof Parent p) out.addAll(find(p, type));
        }
        return out;
    }

    @Test
    void aThresholdEditorHandsEverySlidesCurveToItsHistogram() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CellIndex index = Cells.of(40).marker("CD3", i -> i).marker("CD8", i -> 40 - i).area(50.0).build();
        GateNode gate = new GateNode("CD3", 10);
        gate.setStatistic(Statistic.MEAN);
        FxTestSupport.onFxRun(() -> {
            GateEditorPane pane = pane(index, g -> List.of(
                    slide("a", false, 5, false), slide("b", true, 6, false), slide("c", false, 7, false)));
            pane.setGateNode(gate);
            HistogramCanvas histogram = find(pane, HistogramCanvas.class).get(0);
            assertEquals(0, histogram.cohortCurveCount(), "This slide draws no curves");
            pane.setViewMode(CohortSession.ViewMode.ALL_SLIDES);
            assertEquals(3, histogram.cohortCurveCount(), "the open slide's curve is one of them, highlighted");
            pane.setViewMode(CohortSession.ViewMode.THIS_SLIDE);
            assertEquals(0, histogram.cohortCurveCount());
        });
    }

    @Test
    void aTwoAxisEditorPoolsOnlyTheOtherSlidesTwoAxisPoints() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CellIndex index = Cells.of(40).marker("CD3", i -> i).marker("CD8", i -> 40 - i).area(50.0).build();
        RectangleGate gate = new RectangleGate("CD3", "CD8", 5, 20, 5, 20);
        gate.setStatisticX(Statistic.MEAN);
        gate.setStatisticY(Statistic.MEAN);
        FxTestSupport.onFxRun(() -> {
            GateEditorPane pane = pane(index, g -> List.of(
                    slide("open", true, 3, true),     // the open slide: its own coloured dots, not pooled
                    slide("b", false, 4, true),
                    slide("oneAxis", false, 5, false), // no y: nothing to pair, so not drawn
                    slide("d", false, 2, true)));
            pane.setGateNode(gate);
            pane.setViewMode(CohortSession.ViewMode.ALL_SLIDES);
            ScatterPlotCanvas scatter = find(pane, ScatterPlotCanvas.class).get(0);
            assertEquals(4 + 2, scatter.cohortPointCount());
            pane.setViewMode(CohortSession.ViewMode.THIS_SLIDE);
            assertEquals(0, scatter.cohortPointCount());
        });
    }
}
