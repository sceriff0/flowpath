package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.ext.flowpath.testing.Cells;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CellIndexTest {

    @Test
    void buildWithBasicMeasurements() {
        var c1 = Cells.detection();
        var c2 = Cells.detection();
        var c3 = Cells.detection();
        c1.getMeasurements().put("CD45", 1.0);
        c2.getMeasurements().put("CD45", 2.0);
        c3.getMeasurements().put("CD45", 3.0);
        c1.getMeasurements().put("MORPH: Area µm²", 10.0);
        c2.getMeasurements().put("MORPH: Area µm²", 20.0);
        c3.getMeasurements().put("MORPH: Area µm²", 30.0);

        var index = CellIndex.build(List.of(c1, c2, c3), List.of("CD45"));

        assertEquals(3, index.size());
        double[] vals = index.getMarkerValues(0);
        assertEquals(1.0, vals[0]);
        assertEquals(2.0, vals[1]);
        assertEquals(3.0, vals[2]);
        assertEquals(0, index.getMarkerIndex("CD45"));
    }

    @Test
    void markerIndexReturnsMinusOneForUnknown() {
        var c = Cells.detection();
        c.getMeasurements().put("CD45", 1.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertEquals(-1, index.getMarkerIndex("NONEXISTENT"));
    }

    @Test
    void getObjectReturnsOriginalPathObject() {
        var c1 = Cells.detection();
        var c2 = Cells.detection();
        var index = CellIndex.build(List.of(c1, c2), List.of());

        assertSame(c1, index.getObject(0));
        assertSame(c2, index.getObject(1));
    }

    @Test
    void areaFromMorphKey() {
        var c = Cells.detection();
        c.getMeasurements().put("MORPH: Area µm²", 42.0);
        var index = CellIndex.build(List.of(c), List.of());

        assertEquals(42.0, index.qualityField(QualityFilter.AREA).valueAt(0));
    }

    @Test
    void missingAreaReturnsNaN() {
        var c = Cells.detection();
        var index = CellIndex.build(List.of(c), List.of());

        assertNull(index.qualityField(QualityFilter.AREA),
                "an export without an area key has no area field to filter on");
    }

    @Test
    void missingMarkerValueReturnsNaN() {
        var c = Cells.detection();
        var index = CellIndex.build(List.of(c), List.of("MISSING"));

        assertTrue(Double.isNaN(index.getMarkerValues(0)[0]),
                "Absent markers should return NaN to distinguish from true zero");
    }

    @Test
    void emptyDetectionList() {
        var index = CellIndex.build(Collections.emptyList(), List.of("CD45"));

        assertEquals(0, index.size());
    }

    @Test
    void centroidFromMeasurements() {
        var c = Cells.detection();
        c.getMeasurements().put("Centroid X µm", 100.0);
        c.getMeasurements().put("Centroid Y µm", 200.0);
        var index = CellIndex.build(List.of(c), List.of());

        assertEquals(100.0, index.getCentroidX(0));
        assertEquals(200.0, index.getCentroidY(0));
    }

    @Test
    void getMarkerIndexResolvesEveryMarkerAndRejectsUnknownOnes() {
        // getMarkerIndex sits in the gating hot path (once per cell per gate), so it
        // is backed by a lookup map rather than a scan. Pin the semantics that the
        // scan provided: first-declared wins, unknown/null yields -1.
        PathObject cell = Cells.detection();
        List<String> markers = List.of("CD3", "CD8", "CD19", "Ki67");
        for (String m : markers) {
            cell.getMeasurements().put(m, 1.0);
        }
        CellIndex index = CellIndex.build(List.of(cell), markers);

        for (int i = 0; i < markers.size(); i++) {
            assertEquals(i, index.getMarkerIndex(markers.get(i)), markers.get(i));
        }
        assertEquals(-1, index.getMarkerIndex("NOT_A_MARKER"));
        assertEquals(-1, index.getMarkerIndex(null));
    }

    @Test
    void getMarkerIndexReturnsTheFirstOccurrenceOfADuplicatedMarker() {
        PathObject cell = Cells.detection();
        cell.getMeasurements().put("CD3", 1.0);
        CellIndex index = CellIndex.build(List.of(cell), List.of("CD3", "CD8", "CD3"));

        assertEquals(0, index.getMarkerIndex("CD3"),
                "a duplicated marker name resolves to its first column, as the scan did");
    }
}
