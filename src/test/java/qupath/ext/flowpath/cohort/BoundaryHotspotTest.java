package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;

import static org.junit.jupiter.api.Assertions.*;

class BoundaryHotspotTest {

    @Test
    void boundaryCellsAreWithinATenthOfAnAlignedAsinhUnitOfTheCut() {
        double[] raw = {0.8, 0.9, 1.0, 1.1, 1.2, Double.NaN, 1.0};
        boolean[] parent = {true, true, true, true, true, true, false};
        assertArrayEquals(new boolean[]{false, true, true, true, false, false, false},
                BoundaryHotspot.boundaryCells(raw, parent, Alignment.identity(), 1.0, 1.0));
    }

    @Test
    void onABrighterSlideTheBandSitsAtTheAppliedCut() {
        Alignment brighter = Alignment.between(new Landmarks(1.0, 0.0, Double.NaN), new Landmarks(1.0, 0.5, Double.NaN));
        double applied = brighter.apply(1.0);
        boolean[] b = BoundaryHotspot.boundaryCells(new double[]{1.0, applied}, null, brighter, 1.0, 1.0);
        assertFalse(b[0], "the raw reference number is not the boundary on this slide");
        assertTrue(b[1]);
    }

    @Test
    void theHotspotIsTheCentreOfTheTileWithMostFlaggedCells() {
        CellIndex index = Cells.of(5).at(new double[]{10, 20, 1500, 1600, 1700}, new double[]{10, 20, 1500, 1550, 1600})
                .marker("CD3", 1.0).build();
        BoundaryHotspot.Hotspot h = BoundaryHotspot.hotspot(index, new boolean[]{true, true, true, true, true},
                BoundaryHotspot.TILE_PIXELS);
        assertEquals(1536.0, h.centerX());
        assertEquals(1536.0, h.centerY());
        assertEquals(3, h.cells());
        assertNull(BoundaryHotspot.hotspot(index, new boolean[5], BoundaryHotspot.TILE_PIXELS));
    }

    static final double[] CD8 = {0.5, 1.0, 2.0, 2.9, 3.0, 3.1, 5.0, 6.9, 7.0, 7.1};

    /** Two enabled roots on one channel, cutting at 3 and 7: only the item's own gate may draw the band. */
    static GateTree twoRootsOnCd8() {
        GateTree tree = new GateTree();
        for (double t : new double[]{3, 7}) {
            GateNode g = new GateNode("CD8", t);
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        return tree;
    }

    @Test
    void theBandAndItsColoursComeFromTheItemsOwnGateNotItsSameChannelSibling() {
        CellIndex index = Cells.of(CD8.length).marker("CD8", CD8).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(CD8.length));
        GateTree tree = twoRootsOnCd8();
        GateNode second = tree.getRoots().get(1);
        second.setPositiveColor(0x00C800);
        second.setNegativeColor(0x0000C8);

        BoundaryHotspot.Boundary b = BoundaryHotspot.of(tree, second, "s1", null, index, stats, null, key -> 1.0);

        assertArrayEquals(new boolean[]{false, false, false, false, false, false, false, true, true, true}, b.cells(),
                "the band is at the second root's cut (7), not the first's (3)");
        assertEquals(0x0000C8, b.rgb()[7] & 0xFFFFFF, "6.9 is below the second root's cut");
        assertEquals(0x00C800, b.rgb()[8] & 0xFFFFFF);

        BoundaryHotspot.Boundary first = BoundaryHotspot.of(tree, tree.getRoots().get(0), "s1", null, index, stats,
                null, key -> 1.0);
        assertArrayEquals(new boolean[]{false, false, false, true, true, true, false, false, false, false}, first.cells());
    }

    @Test
    void aRegionGateFallsBackToItsWholeParentPopulation() {
        CellIndex index = Cells.of(4).marker("CD3", 1, 2, 3, 4).marker("CD4", 1, 2, 3, 4).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));
        GateTree tree = new GateTree();
        RectangleGate rect = new RectangleGate("CD3", "CD4", 0, 2, 0, 2);
        tree.addRoot(rect);
        tree.addRoot(new GateNode("CD3", 2));
        boolean[] base = {true, false, true, true};
        BoundaryHotspot.Boundary b = BoundaryHotspot.of(tree, rect, "s1", null, index, stats, base, key -> 1.0);
        assertArrayEquals(base, b.cells());
        assertNotSame(base, b.cells(), "a copy, never the caller's mask");
    }
}
