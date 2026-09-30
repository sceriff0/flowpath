package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortCurves;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GateEditorAllSlidesTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void allSlidesAsksForTheShownGatesCohortValuesAndThisSlideDoesNot() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CellIndex index = Cells.of(30).marker("CD3", i -> i).area(50.0).build();
        // Two gates on the same channel: the provider must be asked for the one shown, by identity.
        GateNode gate = new GateNode("CD3", 10);
        GateNode sameChannel = new GateNode("CD3", 10);
        for (GateNode g : List.of(gate, sameChannel)) g.setStatistic(Statistic.MEAN);
        List<GateNode> asked = new ArrayList<>();
        List<CohortSession.ViewMode> reported = new ArrayList<>();
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3"));
            pane.setCompartmentCapability(CompartmentCapability.scan(Arrays.asList(index.getObjects())));
            pane.setCellIndex(index);
            pane.setMarkerStats(MarkerStats.compute(index, Cells.allTrue(30)));
            pane.setCohortAvailable(true);
            pane.setCohortValues(g -> { asked.add(g); return List.of(
                    new CohortCurves.SlideValues("a", "a.tif", true, new double[]{1, 2}, null)); });
            pane.setOnViewModeChanged(reported::add);
            pane.setGateNode(gate);
        });
        assertTrue(asked.isEmpty(), "This slide is the default and asks for nothing");
        FxTestSupport.onFxRun(() -> pane.setViewMode(CohortSession.ViewMode.ALL_SLIDES));
        assertEquals(1, asked.size());
        assertSame(gate, asked.get(0));
        assertEquals(CohortSession.ViewMode.ALL_SLIDES, pane.viewMode());
        assertEquals(List.of(), reported, "a programmatic switch is not a user report");

        asked.clear();
        FxTestSupport.onFxRun(() -> pane.setGateNode(sameChannel));
        assertFalse(asked.isEmpty(), "the mode outlives the gate shown");
        assertTrue(asked.stream().allMatch(g -> g == sameChannel), "asked for the gate now shown, not its same-channel twin");

        asked.clear();
        FxTestSupport.onFxRun(() -> pane.setCohortAvailable(false));
        FxTestSupport.onFxRun(pane::refreshEditor);
        assertTrue(asked.isEmpty(), "no cohort, no cohort values, whatever the mode");
    }

    @Test
    void theUsersToggleReportsTheMode() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CellIndex index = Cells.of(30).marker("CD3", i -> i).area(50.0).build();
        GateNode gate = new GateNode("CD3", 10);
        gate.setStatistic(Statistic.MEAN);
        List<CohortSession.ViewMode> reported = new ArrayList<>();
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3"));
            pane.setCompartmentCapability(CompartmentCapability.scan(Arrays.asList(index.getObjects())));
            pane.setCellIndex(index);
            pane.setMarkerStats(MarkerStats.compute(index, Cells.allTrue(30)));
            pane.setCohortAvailable(true);
            pane.setOnViewModeChanged(reported::add);
            pane.setGateNode(gate); // the pane is disabled until a gate is shown
            pane.allSlidesButton.fire();
            pane.allSlidesButton.fire(); // clicking the selected toggle keeps it selected, reports nothing
            pane.thisSlideButton.fire();
        });
        assertEquals(List.of(CohortSession.ViewMode.ALL_SLIDES, CohortSession.ViewMode.THIS_SLIDE), reported);
        assertEquals(CohortSession.ViewMode.THIS_SLIDE, pane.viewMode());
        assertTrue(pane.thisSlideButton.isSelected());
    }
}
