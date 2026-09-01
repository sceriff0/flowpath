package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Hit testing is arithmetic over the same layout the draw uses — no toolkit needed. */
class PlotHitTestTest {

    @Test
    void aPointInsideTheFirstBarNamesThatPopulation() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());
        int n = canvas.barLabels().size();
        assertTrue(n > 0, "precondition: the fixture produces bars");
        double x = canvas.centreXOfBar(0);
        PlotHit hit = canvas.hitAtForTest(x, canvas.getHeight() / 2);
        assertNotNull(hit, "a click on a bar must resolve to something");
        assertEquals(canvas.barLabels().get(0), hit.title());
        assertTrue(hit.detail().contains("cells"), hit.detail());
        assertNotNull(hit.population(), "a composition bar is a population you can select");
    }

    @Test
    void aPointOutsideThePlotResolvesToNothing() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());
        assertNull(canvas.hitAtForTest(2, 2), "the left margin is not a bar");
        assertNull(canvas.hitAtForTest(canvas.getWidth() - 1, canvas.getHeight() - 1));
    }

    @Test
    void clickingABarNotifiesTheHandlerOnceWithThatPopulation() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());
        AtomicReference<PopulationRef> picked = new AtomicReference<>();
        canvas.setOnPopulationPicked(picked::set);
        canvas.pickForTest(canvas.centreXOfBar(0), canvas.getHeight() / 2);
        assertNotNull(picked.get());
        assertEquals(canvas.barLabels().get(0), picked.get().path());
    }

    @Test
    void markerPositivityNamesTheSegmentUnderTheCursor() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());
        double x = canvas.centreXOfBar(0);
        PlotHit low = canvas.hitAtForTest(x, canvas.getHeight() - 40);   // near the baseline
        assertNotNull(low);
        assertTrue(low.detail().startsWith("Positive")
                        || low.detail().startsWith("Negative")
                        || low.detail().startsWith("Ungated"),
                "the tooltip must say which segment, not just which marker: " + low.detail());
    }
}
