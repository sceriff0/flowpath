package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the CSV half of the FlowPath → MIRAGE round trip.
 * <p>
 * {@code mirage/bin/join_flowpath.py} reads this file back. It hard-fails unless
 * {@code phenotype}, {@code centroid_x} and {@code centroid_y} are all present, inverts
 * the centroids as {@code / pixel_size - 0.5}, and — critically — chooses its join
 * strategy on the mere <em>presence</em> of a {@code label} column: present means an
 * exact join on segmentation identity, absent means a fuzzy mutual-nearest centroid join.
 * It refuses to align positionally at all, because FlowPath's {@code cell_id} is a
 * collection index rather than a cell identity.
 */
class PhenotypeCsvGeometryTest {

    private static final double PIXEL_SIZE = 0.325;

    @TempDir
    Path tempDir;

    private static PixelCalibration calibrated(double microns) {
        return new PixelCalibration.Builder().pixelSizeMicrons(microns, microns).build();
    }

    private static PathObject cell(double xPixels, double yPixels, Double label) {
        Cells cell = Cells.of(1).at(xPixels, yPixels)
                .centroidsMicronsFromRoi(PIXEL_SIZE)
                .marker("CD3", 10.0 * xPixels);
        if (label != null) cell.measurement("label", label);
        return cell.only();
    }

    /** A native QuPath detection: a ROI and nothing else. */
    private static PathObject roiOnlyCell(double xPixels, double yPixels) {
        return Cells.of(1).at(xPixels, yPixels).marker("CD3", 10.0 * xPixels).only();
    }

    private record Csv(List<String> header, List<List<String>> rows) {
        boolean has(String col) {
            return header.contains(col);
        }
        String val(int row, String col) {
            int i = header.indexOf(col);
            assertTrue(i >= 0, "column '" + col + "' missing; header = " + header);
            return rows.get(row).get(i);
        }
        double num(int row, String col) {
            String v = val(row, col);
            assertFalse(v.isEmpty(), "column '" + col + "' unexpectedly blank on row " + row);
            return Double.parseDouble(v);
        }
    }

    private Csv export(List<PathObject> cells, PixelCalibration cal) throws IOException {
        CellIndex index = CellIndex.build(cells, List.of("CD3"), null, cal);
        boolean[] all = new boolean[index.size()];
        Arrays.fill(all, true);
        MarkerStats stats = MarkerStats.compute(index, all);

        GateNode gate = new GateNode("CD3");
        gate.setThresholdIsZScore(false);
        gate.setThreshold(100.0);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File f = tempDir.resolve("phenotypes.csv").toFile();
        PhenotypeCsvExporter.export(f, index, result, tree, stats);

        List<String> lines = Files.readAllLines(f.toPath());
        List<String> hdr = new ArrayList<>(List.of(lines.get(0).split(",", -1)));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(new ArrayList<>(List.of(lines.get(i).split(",", -1))));
        }
        // Every row must have exactly as many fields as the header — the conditional
        // label column must not desynchronise them.
        for (int r = 0; r < rows.size(); r++) {
            assertEquals(hdr.size(), rows.get(r).size(),
                    "row " + r + " field count must match the header");
        }
        return new Csv(hdr, rows);
    }

    // ---- the label column -------------------------------------------------------

    @Test
    void labelColumnIsEmittedWhenTheMeasurementIsPresent() throws IOException {
        Csv csv = export(List.of(cell(40, 25, 7.0), cell(80, 50, 42.0)), calibrated(PIXEL_SIZE));

        assertTrue(csv.has("label"),
                "join_flowpath.py can only do an exact join if this column exists; "
                        + "header = " + csv.header());
        assertEquals("7", csv.val(0, "label"), "labels are identities, written as integers");
        assertEquals("42", csv.val(1, "label"));
    }

    @Test
    void labelColumnIsAbsentEntirelyWhenNoMeasurementExists() throws IOException {
        Csv csv = export(List.of(cell(40, 25, null), cell(80, 50, null)), calibrated(PIXEL_SIZE));

        assertFalse(csv.has("label"),
                "an all-blank label column is worse than none: join_flowpath.py branches "
                        + "on presence, so a blank one would select the exact join and "
                        + "match nothing. Header = " + csv.header());
    }

    @Test
    void labelSurvivesAlongsideTheContractColumns() throws IOException {
        Csv csv = export(List.of(cell(40, 25, 7.0)), calibrated(PIXEL_SIZE));

        // The three join_flowpath.py hard-fails without.
        assertTrue(csv.has("phenotype"));
        assertTrue(csv.has("centroid_x"));
        assertTrue(csv.has("centroid_y"));
        assertTrue(csv.has("cell_id"));
    }

    // ---- centroid_x / centroid_y are micrometres --------------------------------

    @Test
    void centroidColumnsCarryMicronsForAMirageExport() throws IOException {
        Csv csv = export(List.of(cell(40, 25, 1.0)), calibrated(PIXEL_SIZE));

        assertEquals(40.0 * PIXEL_SIZE, csv.num(0, "centroid_x"), 1e-4);
        assertEquals(25.0 * PIXEL_SIZE, csv.num(0, "centroid_y"), 1e-4);
        // And the pixel space is stated explicitly beside it, so a consumer need not
        // invert the calibration to get back to mask coordinates.
        assertEquals(40.0, csv.num(0, "centroid_x_px"), 1e-4);
        assertEquals(25.0, csv.num(0, "centroid_y_px"), 1e-4);
    }

    @Test
    void centroidColumnsBecomeMicronsForNativeDetectionsOnACalibratedImage() throws IOException {
        // Previously these wrote pixels under the same header, and join_flowpath.py
        // divided them by pixel_size anyway — silently mis-scaling the join.
        Csv csv = export(List.of(roiOnlyCell(40, 25)), calibrated(PIXEL_SIZE));

        assertEquals(40.0 * PIXEL_SIZE, csv.num(0, "centroid_x"), 1e-4);
        assertEquals(25.0 * PIXEL_SIZE, csv.num(0, "centroid_y"), 1e-4);
        assertEquals(40.0, csv.num(0, "centroid_x_px"), 1e-4);
    }

    @Test
    void centroidColumnsStayInPixelsWhenMicronsAreUnknowable() throws IOException {
        // Uncalibrated image, no µm measurement. Micrometres genuinely cannot be known,
        // so the column keeps the position it has always held rather than going blank.
        Csv csv = export(List.of(roiOnlyCell(40, 25)), PixelCalibration.getDefaultInstance());

        assertEquals(40.0, csv.num(0, "centroid_x"), 1e-4);
        assertEquals(25.0, csv.num(0, "centroid_y"), 1e-4);
    }

    @Test
    void theRoundTripMirageInvertsRecoversTheOriginalPixels() throws IOException {
        // join_flowpath.py computes `flow[["centroid_x","centroid_y"]] / pixel_size - 0.5`
        // to return to mask coordinates. Exercise that inversion against what we wrote.
        Csv csv = export(List.of(cell(1000, 800, 3.0)), calibrated(PIXEL_SIZE));

        double recoveredX = csv.num(0, "centroid_x") / PIXEL_SIZE - 0.5;
        double recoveredY = csv.num(0, "centroid_y") / PIXEL_SIZE - 0.5;

        assertEquals(1000.0 - 0.5, recoveredX, 1e-3);
        assertEquals(800.0 - 0.5, recoveredY, 1e-3);
    }
}
