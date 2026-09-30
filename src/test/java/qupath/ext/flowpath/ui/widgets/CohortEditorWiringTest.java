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

    /**
     * A flagged slide's ridge is marked on the histogram and its name listed in the fp-flagged
     * legend, in All slides only. Asked of the shown gate by identity, so a same-channel sibling
     * with no flags shows none.
     */
    @Test
    void theFlaggedSlidesAreMarkedAndNamedInAllSlidesOnly() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CellIndex index = Cells.of(40).marker("CD3", i -> i).marker("CD8", i -> 40 - i).area(50.0).build();
        GateNode gate = new GateNode("CD3", 10);
        GateNode sibling = new GateNode("CD3", 12);
        for (GateNode g : List.of(gate, sibling)) g.setStatistic(Statistic.MEAN);
        RectangleGate rect = new RectangleGate("CD3", "CD8", 5, 20, 5, 20);
        rect.setStatisticX(Statistic.MEAN);
        rect.setStatisticY(Statistic.MEAN);
        FxTestSupport.onFxRun(() -> {
            GateEditorPane pane = pane(index, g -> List.of(
                    slide("a", false, 5, true), slide("b", true, 6, true), slide("c", false, 7, true)));
            pane.setFlaggedSlides(g -> g == gate || g == rect ? java.util.Set.of("a", "c") : java.util.Set.of());
            pane.setGateNode(gate);
            HistogramCanvas histogram = find(pane, HistogramCanvas.class).get(0);
            javafx.scene.control.Label legend = flaggedLegend(pane);
            assertEquals(0, histogram.flaggedCurveCount(), "This slide flags nothing");
            assertFalse(legend.isVisible());

            pane.setViewMode(CohortSession.ViewMode.ALL_SLIDES);
            assertEquals(2, histogram.flaggedCurveCount());
            assertEquals(3, histogram.cohortCurveCount());
            assertTrue(legend.isVisible() && legend.isManaged());
            assertEquals("Flagged: a.tif, c.tif", legend.getText());

            pane.setGateNode(sibling);
            histogram = find(pane, HistogramCanvas.class).get(0);
            assertEquals(0, histogram.flaggedCurveCount(), "the same-channel sibling has no flags");
            assertFalse(flaggedLegend(pane).isVisible());

            pane.setGateNode(rect);
            assertEquals("Flagged: a.tif, c.tif", flaggedLegend(pane).getText());
            assertTrue(flaggedLegend(pane).isVisible());
            pane.setViewMode(CohortSession.ViewMode.THIS_SLIDE);
            assertFalse(flaggedLegend(pane).isVisible());
        });
    }

    private static javafx.scene.control.Label flaggedLegend(Parent root) {
        List<javafx.scene.control.Label> labels = find(root, javafx.scene.control.Label.class).stream()
                .filter(l -> l.getStyleClass().contains("fp-flagged")).toList();
        assertEquals(1, labels.size(), "one legend, under the canvas");
        return labels.get(0);
    }
}
