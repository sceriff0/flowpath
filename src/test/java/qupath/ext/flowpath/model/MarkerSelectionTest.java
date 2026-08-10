package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4.3: per-marker selection defaults + persistence round-trip + legacy fallback,
 * and M4.1: CellIndex.build resolving via a selection.
 */
class MarkerSelectionTest {

    private static PathObject createCell() {
        return PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
    }

    // ---- defaults ----

    @Test
    void defaultsAreWholeCellMeanIncluded() {
        MarkerSelection sel = new MarkerSelection();
        assertEquals(Compartment.WHOLE_CELL, sel.compartmentFor("CD3"));
        assertEquals(Statistic.MEAN, sel.statisticFor("CD3"));
        assertTrue(sel.isIncluded("CD3"));
    }

    @Test
    void defaultForPopulatesEveryMarker() {
        MarkerSelection sel = MarkerSelection.defaultFor(List.of("CD3", "CD8"));
        assertEquals(List.of("CD3", "CD8"), sel.markers());
        assertTrue(sel.isIncluded("CD3"));
        assertEquals(Compartment.WHOLE_CELL, sel.compartmentFor("CD8"));
    }

    @Test
    void entryNullsCoalesceToDefaults() {
        var e = new MarkerSelection.Entry(null, null, true);
        assertEquals(Compartment.WHOLE_CELL, e.compartment());
        assertEquals(Statistic.MEAN, e.statistic());
    }

    // ---- persistence round-trip ----

    @Test
    void serializeDeserializeRoundTrip() {
        MarkerSelection sel = new MarkerSelection();
        sel.put("CD3", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEDIAN, true));
        sel.put("CD8", new MarkerSelection.Entry(Compartment.CYTOPLASMIC, Statistic.SUM, false));

        MarkerSelection loaded = MarkerSelection.deserialize(sel.serialize());

        assertEquals(Compartment.NUCLEAR, loaded.compartmentFor("CD3"));
        assertEquals(Statistic.MEDIAN, loaded.statisticFor("CD3"));
        assertTrue(loaded.isIncluded("CD3"));

        assertEquals(Compartment.CYTOPLASMIC, loaded.compartmentFor("CD8"));
        assertEquals(Statistic.SUM, loaded.statisticFor("CD8"));
        assertFalse(loaded.isIncluded("CD8"));
    }

    @Test
    void legacyOrBlankPayloadLoadsEmptyWithDefaults() {
        // Null / blank / unrecognised -> empty selection that yields whole-cell mean.
        for (String payload : new String[]{null, "", "   ", "some legacy blob"}) {
            MarkerSelection loaded = MarkerSelection.deserialize(payload);
            assertTrue(loaded.isEmpty());
            assertEquals(Compartment.WHOLE_CELL, loaded.compartmentFor("CD3"));
            assertEquals(Statistic.MEAN, loaded.statisticFor("CD3"));
            assertTrue(loaded.isIncluded("CD3"));
        }
    }

    @Test
    void garbledLinesAreSkipped() {
        String payload = "qumap-markersel:v1\nCD3\tNucleus\tMean\t1\nGARBAGE LINE\nCD8\tbogus\tMean\t1";
        MarkerSelection loaded = MarkerSelection.deserialize(payload);
        assertEquals(Compartment.NUCLEAR, loaded.compartmentFor("CD3"));
        // CD8 had an invalid compartment token -> skipped -> default.
        assertEquals(Compartment.WHOLE_CELL, loaded.compartmentFor("CD8"));
    }

    // ---- CellIndex.build via selection ----

    @Test
    void buildResolvesSelectionPerMarker() {
        var c = createCell();
        c.getMeasurements().put("CD3: Nucleus: Mean", 100.0);
        c.getMeasurements().put("CD3: Cell: Mean", 24.0);

        MarkerSelection sel = new MarkerSelection();
        sel.put("CD3", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEAN, true));

        var index = CellIndex.build(List.of(c), List.of("CD3"), sel);
        assertEquals(100.0, index.getMarkerValues(0)[0]);
    }

    @Test
    void buildNullSelectionUsesWholeCellMeanFallback() {
        var c = createCell();
        c.getMeasurements().put("CD3", 7.0); // legacy bare key
        var index = CellIndex.build(List.of(c), List.of("CD3"), null);
        assertEquals(7.0, index.getMarkerValues(0)[0]);
    }
}
