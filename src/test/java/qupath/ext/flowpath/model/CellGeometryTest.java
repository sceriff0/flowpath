package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.ext.flowpath.testing.Cells;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the two coordinate spaces apart.
 * <p>
 * FlowPath receives micrometres (the {@code "Centroid X µm"} measurement) and pixels (the
 * polygon ROI) in the same {@code cells.geojson} and used to conflate them. These tests
 * hold the separation in place, and hold the scale cross-check — the one consistency
 * check MIRAGE structurally cannot perform on itself — to its stated behaviour.
 *
 * <h2>On the QuPath 0.7 calibration API used here</h2>
 * {@code new PixelCalibration.Builder().pixelSizeMicrons(w, h).build()} is the only public
 * way to construct a calibrated instance; {@code PixelCalibration.getDefaultInstance()}
 * is uncalibrated. Verified against the {@code qupath-core-0.7.0} jar actually on the
 * classpath: for an uncalibrated instance {@code hasPixelSizeMicrons()} is {@code false}
 * and {@code getPixelWidthMicrons()} returns {@code Double.NaN} — <em>not</em> {@code 1.0}.
 * The {@code 1.0} that circulates in folklore is {@code getPixelWidth()}, whose unit is
 * {@code "px"}. {@code uncalibratedReportsNoCalibration} asserts that directly so the
 * assumption is checked rather than trusted.
 */
class CellGeometryTest {

    /** MIRAGE's default {@code params.pixel_size} (nextflow.config). */
    private static final double PIXEL_SIZE = 0.325;

    private static PixelCalibration calibrated(double microns) {
        return new PixelCalibration.Builder().pixelSizeMicrons(microns, microns).build();
    }

    private static PixelCalibration uncalibrated() {
        return PixelCalibration.getDefaultInstance();
    }

    /**
     * Cells shaped like a MIRAGE export: the ROI sits in pixel space, and the
     * {@code "Centroid X/Y µm"} measurements carry the same position in micrometres,
     * scaled by {@code micronsPerPixel}. Passing a wrong {@code micronsPerPixel} here is
     * precisely how a mis-set {@code params.pixel_size} reaches FlowPath.
     */
    private static List<PathObject> mirageCells(int n, double micronsPerPixel) {
        return Cells.of(n).at(i -> (i + 1) * 40.0, i -> (i + 1) * 25.0)
                .centroidsMicronsFromRoi(micronsPerPixel)
                .marker("DAPI", 100.0)
                .detections();
    }

    /** A native QuPath detection: a ROI, and no centroid measurement at all. */
    private static PathObject nativeCell(double xPixels, double yPixels) {
        return Cells.of(1).at(xPixels, yPixels).marker("DAPI", 100.0).only();
    }

    private static CellGeometry geometryOf(List<PathObject> cells, PixelCalibration cal) {
        return CellIndex.build(cells, List.of("DAPI"), null, cal).geometry();
    }

    // ---- a pure-µm MIRAGE-shaped export ----------------------------------------

    @Test
    void mirageExportResolvesToMicronsAndKeepsPixelsAvailable() {
        List<PathObject> cells = mirageCells(10, PIXEL_SIZE);
        CellGeometry geom = geometryOf(cells, calibrated(PIXEL_SIZE));

        assertEquals(CoordinateSpace.MICRONS, geom.sourceSpace());
        assertEquals(0, geom.roiFallbackCount(), "every cell carried both centroids");

        // Micrometres come straight from the measurement...
        assertEquals(40.0 * PIXEL_SIZE, geom.micronsX(0), 1e-9);
        assertEquals(25.0 * PIXEL_SIZE, geom.micronsY(0), 1e-9);
        // ...and pixels come straight from the ROI, needing no calibration to recover.
        assertEquals(40.0, geom.pixelsX(0), 1e-9);
        assertEquals(25.0, geom.pixelsY(0), 1e-9);
    }

    @Test
    void micronsAreAvailableFromPixelsWhenOnlyTheRoiExists() {
        // Native QuPath detections on a calibrated image: no µm measurement, but the
        // calibration makes micrometres derivable anyway.
        CellGeometry geom = geometryOf(List.of(nativeCell(40, 25)), calibrated(PIXEL_SIZE));

        assertEquals(CoordinateSpace.PIXELS, geom.sourceSpace());
        assertEquals(40.0, geom.pixelsX(0), 1e-9);
        assertEquals(40.0 * PIXEL_SIZE, geom.micronsX(0), 1e-9);
        assertEquals(25.0 * PIXEL_SIZE, geom.micronsY(0), 1e-9);
    }

    // ---- no centroid measurement: joint fallback, space recorded ----------------

    @Test
    void noCentroidMeasurementFallsBackToRoiOnBothAxes() {
        CellGeometry geom = geometryOf(List.of(nativeCell(12.5, 34.5)), uncalibrated());

        assertEquals(CoordinateSpace.PIXELS, geom.sourceSpace(), "the space is recorded");
        assertEquals(1, geom.roiFallbackCount(), "the cell took the ROI fallback");
        assertEquals(12.5, geom.pixelsX(0), 1e-9);
        assertEquals(34.5, geom.pixelsY(0), 1e-9);
        assertTrue(Double.isNaN(geom.micronsX(0)),
                "with no calibration and no measurement, micrometres are unknowable — "
                        + "and must say so rather than hand back pixels");
    }

    @Test
    void oneAxisMeasuredIsTreatedAsNeitherMeasured() {
        // The bug this module exists to prevent: X from the measurement (µm) and Y from
        // the ROI (px) is not a position. Both axes fall back together.
        PathObject c = Cells.of(1).at(12.5, 34.5)
                .measurement("Centroid X µm", 999.0)
                .marker("DAPI", 1.0)
                .only();

        CellGeometry geom = geometryOf(List.of(c), uncalibrated());

        assertEquals(CoordinateSpace.PIXELS, geom.sourceSpace());
        assertEquals(1, geom.roiFallbackCount());
        assertEquals(12.5, geom.pixelsX(0), 1e-9);
        assertEquals(34.5, geom.pixelsY(0), 1e-9);
        assertNotEquals(999.0, geom.sourceX(0), "the orphaned µm value must not be used");
    }

    @Test
    void aCellMissingItsMeasurementInAnOtherwiseMicronIndexStaysInMicrons() {
        // A partially populated export. The index is micrometres, so the one cell that
        // falls back is converted into micrometres rather than silently contributing
        // pixels to a micrometre column.
        List<PathObject> cells = new ArrayList<>(mirageCells(5, PIXEL_SIZE));
        cells.add(nativeCell(200.0, 100.0));

        CellGeometry geom = geometryOf(cells, calibrated(PIXEL_SIZE));

        assertEquals(CoordinateSpace.MICRONS, geom.sourceSpace());
        assertEquals(1, geom.roiFallbackCount());
        assertEquals(200.0 * PIXEL_SIZE, geom.micronsX(5), 1e-9,
                "the fallback cell is converted into the index's declared space");
        assertEquals(200.0, geom.pixelsX(5), 1e-9);
    }

    // ---- the scale cross-check --------------------------------------------------

    @Test
    void agreesWhenTheExportedMicronsMatchTheImageCalibration() {
        CellGeometry geom = geometryOf(mirageCells(50, PIXEL_SIZE), calibrated(PIXEL_SIZE));

        ScaleVerdict verdict = geom.scaleVerdict();
        assertEquals(ScaleVerdict.Status.AGREE, verdict.status(), verdict.describe());
        assertEquals(PIXEL_SIZE, verdict.observedMicronsPerPixel(), 1e-6);
        assertEquals(PIXEL_SIZE, verdict.expectedMicronsPerPixel(), 1e-6);
        assertTrue(verdict.sampledCells() > 0);
        assertFalse(verdict.isDisagreement());
    }

    @Test
    void disagreesWhenParamsPixelSizeWasMisset() {
        // MIRAGE was run with pixel_size = 0.65 against an image whose OME metadata says
        // 0.325 — every µm measurement is uniformly 2x too large, and nothing inside
        // MIRAGE can tell. This is the whole reason ScaleVerdict exists.
        CellGeometry geom = geometryOf(mirageCells(50, 0.65), calibrated(PIXEL_SIZE));

        ScaleVerdict verdict = geom.scaleVerdict();
        assertEquals(ScaleVerdict.Status.DISAGREE, verdict.status(), verdict.describe());
        assertTrue(verdict.isDisagreement());
        assertEquals(0.65, verdict.observedMicronsPerPixel(), 1e-6,
                "the verdict reports what the centroids actually imply");
        assertEquals(PIXEL_SIZE, verdict.expectedMicronsPerPixel(), 1e-6,
                "and what the image claims");
        assertEquals(1.0, verdict.relativeError(), 1e-6, "wrong by a factor of two");
        assertTrue(verdict.describe().contains("params.pixel_size"),
                "the message must point at the parameter to fix: " + verdict.describe());
    }

    @Test
    void disagreesWhenMicrometreColumnsActuallyHoldPixels() {
        // The unit-inference safety net: a unit-less "Centroid X" is assumed to be
        // micrometres, so an export that actually wrote pixels there is mis-declared.
        // The cross-check catches it — observed 1.0 µm/px against an expected 0.325.
        List<PathObject> cells = Cells.of(20).at(i -> (i + 1) * 40.0, i -> (i + 1) * 25.0)
                .measurement("Centroid X", i -> (i + 1) * 40.0)   // pixels, mislabelled
                .measurement("Centroid Y", i -> (i + 1) * 25.0)
                .marker("DAPI", 1.0)
                .detections();

        ScaleVerdict verdict = geometryOf(cells, calibrated(PIXEL_SIZE)).scaleVerdict();

        assertEquals(ScaleVerdict.Status.DISAGREE, verdict.status(), verdict.describe());
        assertEquals(1.0, verdict.observedMicronsPerPixel(), 1e-6);
    }

    @Test
    void toleratesSubPercentNoiseFromContourSimplification() {
        // Polygon simplification moves a centroid slightly off the mask centroid it came
        // from. That must not read as a scale error.
        CellGeometry geom = geometryOf(mirageCells(50, PIXEL_SIZE * 1.004), calibrated(PIXEL_SIZE));

        assertEquals(ScaleVerdict.Status.AGREE, geom.scaleVerdict().status(),
                geom.scaleVerdict().describe());
    }

    @Test
    void uncalibratedReportsNoCalibration() {
        // Also asserts the QuPath 0.7 contract this module relies on, so an upstream
        // change to it fails here loudly rather than silently turning an uncalibrated
        // image into a 1 µm/px one.
        PixelCalibration cal = uncalibrated();
        assertFalse(cal.hasPixelSizeMicrons());
        assertTrue(Double.isNaN(cal.getPixelWidthMicrons()),
                "QuPath 0.7 returns NaN, not 1.0, from getPixelWidthMicrons() when uncalibrated");

        CellGeometry geom = geometryOf(mirageCells(10, PIXEL_SIZE), cal);

        assertFalse(geom.isCalibrated());
        assertEquals(ScaleVerdict.Status.NO_CALIBRATION, geom.scaleVerdict().status());
        // The µm measurements are still perfectly usable; only the check is impossible.
        assertEquals(CoordinateSpace.MICRONS, geom.sourceSpace());
        assertEquals(40.0 * PIXEL_SIZE, geom.micronsX(0), 1e-9);
    }

    @Test
    void nullCalibrationReportsNoCalibration() {
        CellGeometry geom = geometryOf(mirageCells(10, PIXEL_SIZE), null);

        assertFalse(geom.isCalibrated());
        assertEquals(ScaleVerdict.Status.NO_CALIBRATION, geom.scaleVerdict().status());
    }

    @Test
    void noMicronMeasurementReportsNoMeasurement() {
        CellGeometry geom = geometryOf(List.of(nativeCell(40, 25)), calibrated(PIXEL_SIZE));

        assertEquals(ScaleVerdict.Status.NO_MEASUREMENT, geom.scaleVerdict().status());
        assertEquals("pixel size unverified (no µm centroid measurement)",
                geom.scaleVerdict().describe());
    }

    @Test
    void anisotropicCalibrationIsCheckedPerAxis() {
        // Y is wrong, X is right. Reporting only X would miss it.
        PixelCalibration cal = new PixelCalibration.Builder()
                .pixelSizeMicrons(0.325, 0.5).build();
        List<PathObject> cells = Cells.of(20).at(i -> (i + 1) * 40.0, i -> (i + 1) * 25.0)
                .measurement("Centroid X µm", i -> (i + 1) * 40.0 * 0.325)  // correct
                .measurement("Centroid Y µm", i -> (i + 1) * 25.0 * 0.25)   // half of 0.5
                .marker("DAPI", 1.0)
                .detections();

        ScaleVerdict verdict = geometryOf(cells, cal).scaleVerdict();

        assertEquals(ScaleVerdict.Status.DISAGREE, verdict.status(), verdict.describe());
        assertEquals(0.25, verdict.observedMicronsPerPixel(), 1e-6, "the worse axis is reported");
        assertEquals(0.5, verdict.expectedMicronsPerPixel(), 1e-6);
    }

    @Test
    void explicitPixelUnitIsNotMistakenForMicrons() {
        // QuPath writes "Centroid X px" for an uncalibrated image. Believing that to be
        // micrometres would make every downstream µm wrong by the pixel size.
        PathObject o = Cells.of(1).at(40.0, 25.0)
                .measurement("Centroid X px", 40.0)
                .measurement("Centroid Y px", 25.0)
                .marker("DAPI", 1.0)
                .only();

        CellGeometry geom = geometryOf(List.of(o), calibrated(PIXEL_SIZE));

        assertEquals(CoordinateSpace.PIXELS, geom.sourceSpace());
        assertEquals(40.0, geom.pixelsX(0), 1e-9);
        assertEquals(40.0 * PIXEL_SIZE, geom.micronsX(0), 1e-9);
    }

    @Test
    void emptyIndexIsHandled() {
        CellGeometry geom = geometryOf(List.of(), calibrated(PIXEL_SIZE));

        assertEquals(0, geom.size());
        assertEquals(0, geom.roiFallbackCount());
        assertEquals(ScaleVerdict.Status.NO_MEASUREMENT, geom.scaleVerdict().status());
    }

    @Test
    void verdictDescriptionUsesDotDecimalSeparator() {
        // The JVM default locale in this project is en_IT, whose decimal comma would both
        // read wrongly and split a CSV field. ScaleVerdict formats with Locale.US.
        String described = ScaleVerdict.disagree(0.65, 0.325, 100).describe();

        assertTrue(described.contains("0.6500"), described);
        assertTrue(described.contains("0.3250"), described);
        assertFalse(described.contains("0,"), "decimal comma leaked into: " + described);
    }
}
