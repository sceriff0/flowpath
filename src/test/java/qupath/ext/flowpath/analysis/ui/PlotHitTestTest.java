package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.AnalysisFixtures;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;
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

    /**
     * A rotated pass reserves a taller bottom margin ({@code PADDING_BOTTOM_ROTATED}, 64px)
     * than a horizontal one ({@code PADDING_BOTTOM}, 30px), so the plot rectangle a rotated
     * pass actually draws is <em>shorter</em> than an unrotated one. If {@code
     * categorySlotAt} rejected against the wrong (horizontal) band regardless of {@code
     * layout.rotated()}, a point in the 34px gap between the two bands would be wrongly
     * accepted as a hit instead of correctly rejected as below the rotated plot's own bottom
     * edge — exactly the failure mode {@code PlotCanvas}'s own javadoc warns this class of
     * bug reads as "fine on the fixtures" until a panel has long enough labels to rotate.
     */
    @Test
    void aPointBelowARotatedPlotsShorterFrameIsRejectedEvenThoughItWouldFitTheHorizontalOne() {
        String channel = "CD45_EXTREMELY_LONG_CHANNEL_NAME_THAT_FORCES_LABEL_ROTATION";
        int n = 20;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = i + 1;
        CellIndex index = Cells.columns(List.of(channel), new double[][] {values}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode root = new GateNode(channel, 10.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, null, 0).getTally();
        List<PopulationStats.Row> rows = PopulationStats.of(tree, tally, List.of(), null, null).rows();

        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(rows);
        assertTrue(canvas.paintedLayout().labels().rotated(),
                "precondition: the long channel name must force rotation, or this test proves nothing");

        double top = 10; // PADDING_TOP
        double rotatedBottom = canvas.getHeight() - 64;   // PADDING_BOTTOM_ROTATED
        double horizontalBottom = canvas.getHeight() - 30; // PADDING_BOTTOM
        assertTrue(rotatedBottom < horizontalBottom, "sanity: the rotated band must be the shorter one");

        double x = canvas.centreXOfBar(0);
        double yInTheGap = (rotatedBottom + horizontalBottom) / 2;
        assertNull(canvas.hitAtForTest(x, yInTheGap),
                "below the rotated plot's own bottom edge, even though the horizontal band would "
                        + "still contain it -- the hit-test must use the SAME layout draw() used");
        // And a point just inside the rotated plot's own bottom edge still resolves, so the
        // rejection above is about the narrower band, not merely "no bar ever hits down there."
        assertNotNull(canvas.hitAtForTest(x, rotatedBottom - 2));
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
