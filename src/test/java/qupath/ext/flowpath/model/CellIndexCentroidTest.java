package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.ext.flowpath.testing.Cells;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CellIndexCentroidTest {

    @Test
    void buildWithBasicMeasurements() {
        var c1 = Cells.detection();
        var c2 = Cells.detection();
        c1.getMeasurements().put("CD45", 1.0);
        c2.getMeasurements().put("CD45", 2.0);

        var index = CellIndex.build(List.of(c1, c2), List.of("CD45"));

        assertEquals(2, index.size());
        assertEquals(1.0, index.getMarkerValues(0)[0]);
        assertEquals(2.0, index.getMarkerValues(0)[1]);
    }

    @Test
    void markerIndexReturnsMinusOneForUnknown() {
        var c = Cells.detection();
        c.getMeasurements().put("CD45", 1.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertEquals(0, index.getMarkerIndex("CD45"));
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
    void markerValueFromLayerPrefix() {
        var c = Cells.detection();
        c.getMeasurements().put("[Layer0] CD45", 5.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertEquals(5.0, index.getMarkerValues(0)[0]);
    }

    @Test
    void missingMarkerValueReturnsNaN() {
        var c = Cells.detection();
        var index = CellIndex.build(List.of(c), List.of("MISSING"));

        assertTrue(Double.isNaN(index.getMarkerValues(0)[0]));
    }

    @Test
    void emptyDetectionList() {
        var index = CellIndex.build(Collections.emptyList(), List.of("CD45"));
        assertEquals(0, index.size());
    }

    @Test
    void centroidFromMeasurements() {
        // ROI sits at (7, 9) but explicit centroid measurements are present, so the
        // measurements win — this keeps FlowPath CSV round-trips byte-faithful,
        // including whatever units the exporter used.
        var c = Cells.detectionAt(7, 9);
        c.getMeasurements().put("Centroid X", 100.0);
        c.getMeasurements().put("Centroid Y", 200.0);
        var index = CellIndex.build(List.of(c), List.of());

        assertEquals(100.0, index.getCentroidX(0));
        assertEquals(200.0, index.getCentroidY(0));
    }

    @Test
    void centroidFallsBackToRoiWhenNoMeasurement() {
        // Native QuPath detections carry no "Centroid X/Y" measurement — their
        // position lives on the ROI. Without this fallback every centroid was NaN,
        // which blanked the CSV export's centroid columns and left no coordinate to
        // navigate the viewer to.
        var c = Cells.detectionAt(12.5, 34.5);
        c.getMeasurements().put("CD45", 1.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertEquals(12.5, index.getCentroidX(0));
        assertEquals(34.5, index.getCentroidY(0));
    }

    @Test
    void centroidFallsBackJointlyNeverPerAxis() {
        // Supersedes the old centroidFallsBackPerAxis expectation, which encoded a unit
        // bug: it took X from the measurement (micrometres) and Y from the ROI (pixels),
        // producing a "position" that was half in one coordinate space and half in the
        // other. A position is a pair, so the axes resolve together — an export offering
        // only one centroid measurement offers no usable centroid, and BOTH axes come
        // from the ROI.
        var c = Cells.detectionAt(12.5, 34.5);
        c.getMeasurements().put("Centroid X", 100.0);
        var index = CellIndex.build(List.of(c), List.of());

        assertEquals(12.5, index.getCentroidX(0),
                "X must abandon its measurement when Y has none — not mix spaces");
        assertEquals(34.5, index.getCentroidY(0));
        assertEquals(CoordinateSpace.PIXELS, index.geometry().sourceSpace(),
                "and the space the fallback landed in must be recorded");
    }

    @Test
    void centroidFromLayerPrefixedMeasurement() {
        var c = Cells.detectionAt(7, 9);
        c.getMeasurements().put("[Layer0] Centroid X", 100.0);
        c.getMeasurements().put("[Layer0] Centroid Y", 200.0);
        var index = CellIndex.build(List.of(c), List.of());

        assertEquals(100.0, index.getCentroidX(0));
        assertEquals(200.0, index.getCentroidY(0));
    }

    @Test
    void markerAbsentFromKeySampleStillResolves() {
        // Keys are resolved once from a sample of the first 20 detections. A marker
        // that appears only on a later cell must still be picked up via the
        // per-cell fallback rather than silently becoming NaN.
        var cells = new java.util.ArrayList<PathObject>();
        for (int i = 0; i < 25; i++) {
            var c = Cells.detection();
            c.getMeasurements().put("CD45", (double) i);
            cells.add(c);
        }
        cells.get(24).getMeasurements().put("RARE", 99.0);

        var index = CellIndex.build(cells, List.of("CD45", "RARE"));

        assertEquals(99.0, index.getMarkerValues(1)[24],
                "A marker outside the key sample must still resolve per-cell");
        assertTrue(Double.isNaN(index.getMarkerValues(1)[0]),
                "Cells genuinely lacking the marker stay NaN");
    }

    @Test
    void getMarkerValuesRawExposesBackingArray() {
        var c = Cells.detection();
        c.getMeasurements().put("CD45", 5.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertSame(index.getMarkerValuesRaw(0), index.getMarkerValuesRaw(0),
                "Raw accessor must return the backing array, not a copy");
        assertEquals(5.0, index.getMarkerValuesRaw(0)[0]);
    }

    @Test
    void toMatrixTransposesCorrectly() {
        var c1 = Cells.detection();
        var c2 = Cells.detection();
        c1.getMeasurements().put("CD45", 1.0);
        c1.getMeasurements().put("CD3", 10.0);
        c2.getMeasurements().put("CD45", 2.0);
        c2.getMeasurements().put("CD3", 20.0);

        var index = CellIndex.build(List.of(c1, c2), List.of("CD45", "CD3"));
        double[][] matrix = index.toMatrix();

        // matrix[cell][marker]
        assertEquals(2, matrix.length);
        assertEquals(2, matrix[0].length);
        assertEquals(1.0, matrix[0][0]); // cell0, CD45
        assertEquals(10.0, matrix[0][1]); // cell0, CD3
        assertEquals(2.0, matrix[1][0]); // cell1, CD45
        assertEquals(20.0, matrix[1][1]); // cell1, CD3
    }

    @Test
    void toMatrixReplacesNaNWithColumnMean() {
        var c1 = Cells.detection();
        var c2 = Cells.detection();
        var c3 = Cells.detection();
        // Test with all-present values to verify basic matrix construction
        c1.getMeasurements().put("CD45", 2.0);
        c2.getMeasurements().put("CD45", 4.0);
        c3.getMeasurements().put("CD45", 6.0);

        var index = CellIndex.build(List.of(c1, c2, c3), List.of("CD45"));
        double[][] matrix = index.toMatrix();

        assertEquals(3, matrix.length);
        assertEquals(2.0, matrix[0][0]);
        assertEquals(4.0, matrix[1][0]);
        assertEquals(6.0, matrix[2][0]);
    }

    @Test
    void toMatrixSingleCellSingleMarker() {
        var c = Cells.detection();
        c.getMeasurements().put("CD45", 42.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));
        double[][] matrix = index.toMatrix();

        assertEquals(1, matrix.length);
        assertEquals(1, matrix[0].length);
        assertEquals(42.0, matrix[0][0]);
    }

    @Test
    void multipleMarkersStoredIndependently() {
        var c = Cells.detection();
        c.getMeasurements().put("CD45", 3.0);
        c.getMeasurements().put("CD3", 7.0);
        var index = CellIndex.build(List.of(c), List.of("CD45", "CD3"));

        assertEquals(3.0, index.getMarkerValues(0)[0]);
        assertEquals(7.0, index.getMarkerValues(1)[0]);
    }

    @Test
    void getMarkerNamesReturnsAllMarkers() {
        var c = Cells.detection();
        c.getMeasurements().put("CD45", 1.0);
        c.getMeasurements().put("CD3", 2.0);
        var index = CellIndex.build(List.of(c), List.of("CD45", "CD3"));

        String[] names = index.getMarkerNames();
        assertEquals(2, names.length);
        assertEquals("CD45", names[0]);
        assertEquals("CD3", names[1]);
    }

    // --- Array-accessor contract ---
    //
    // These accessors return the backing arrays, NOT defensive copies. The standalone
    // qUMAP CellIndex used to clone them, which was affordable there but is not here:
    // the gating engine fetches whole marker columns on every preview pass, and the UMAP
    // compute service, the feature scaler and the CSV exporter each pre-fetch every
    // column. Cloning would allocate a complete copy of the dataset (N x M x 8 bytes —
    // over a gigabyte on a multi-million-cell slide) purely to read it.
    //
    // The tests below pin that contract down so it cannot regress silently in either
    // direction: a future "fix" that reintroduces cloning will fail here and have to
    // argue with the reason rather than with a surprised profiler.

    @Test
    void getMarkerValuesSharesTheBackingColumn() {
        var c = Cells.detection();
        c.getMeasurements().put("CD45", 5.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertSame(index.getMarkerValues(0), index.getMarkerValues(0),
                "Repeated calls must hand back the same array, not fresh copies");
        assertSame(index.getMarkerValues(0), index.getMarkerValuesRaw(0),
                "getMarkerValuesRaw is an explicit alias for the same no-copy contract");
        assertEquals(5.0, index.getMarkerValues(0)[0]);
    }

    @Test
    void getObjectsSharesTheBackingArray() {
        var c1 = Cells.detection();
        var c2 = Cells.detection();
        var index = CellIndex.build(List.of(c1, c2), List.of());

        assertSame(index.getObjects(), index.getObjects(),
                "Repeated calls must hand back the same array, not fresh copies");
        assertSame(c1, index.getObjects()[0]);
        assertSame(c1, index.getObject(0));
    }

    @Test
    void getMarkerNamesSharesTheBackingArray() {
        var c = Cells.detection();
        c.getMeasurements().put("CD45", 1.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertSame(index.getMarkerNames(), index.getMarkerNames(),
                "Repeated calls must hand back the same array, not fresh copies");
        assertEquals("CD45", index.getMarkerNames()[0]);
    }

    @Test
    void missingCentroidMeasurementUsesRoiNotNaN() {
        // Supersedes the old missingCentroidReturnsNaN expectation, which encoded
        // the bug: a detection always has a ROI, so its position is always known.
        var c = Cells.detectionAt(3, 4);
        c.getMeasurements().put("CD45", 1.0);
        var index = CellIndex.build(List.of(c), List.of("CD45"));

        assertEquals(3.0, index.getCentroidX(0));
        assertEquals(4.0, index.getCentroidY(0));
    }
}
