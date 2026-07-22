package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.model.*;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the contract between the MIRAGE pipeline's {@code cells.geojson} and FlowPath.
 * <p>
 * The rest of the CSV tests construct cells with tidy lowercase keys
 * ({@code "CD3"}, {@code "area"}). That is <em>not</em> what QuPath hands FlowPath after
 * importing a MIRAGE GeoJSON. {@code bin/export_geojson.py} writes a QuPath-native
 * measurement array whose names are:
 * <ul>
 *   <li>{@code "Centroid X µm"}, {@code "Centroid Y µm"} — micrometres, not pixels</li>
 *   <li>the bare marker, {@code "DAPI"} — whole-cell mean</li>
 *   <li>{@code "<marker>: <Nucleus|Cytoplasm|Cell>: <Mean|Median|Sum>"} — per-compartment
 *       ({@code bin/quantify.py::compute_compartment_intensities}; Median/Sum only with
 *       {@code --expanded_quantification})</li>
 *   <li>{@code "Area µm²"}, {@code "Perimeter µm"}, {@code "Eccentricity"},
 *       {@code "Solidity"}, {@code "Convex Area µm²"},
 *       {@code "Major/Minor Axis Length µm"}</li>
 * </ul>
 * Every one of those reaches FlowPath through {@code CellIndex}'s fuzzy resolution
 * (exact → {@code "[layer] key"} → case-insensitive prefix). None of it was covered,
 * so a rename on either side would have shipped as silently-NaN columns rather than a
 * test failure.
 */
class MirageInputFidelityTest {

    @TempDir
    Path tempDir;

    // ---- fixtures: cells shaped exactly like a MIRAGE export --------------------

    /**
     * One cell as {@code export_geojson.py} writes it. {@code markers} supplies the
     * per-marker whole-cell means; per-compartment keys are derived so nuclear is
     * 2x and cytoplasmic 0.5x the whole-cell value, giving each compartment a
     * distinguishable column.
     */
    private static PathObject mirageCell(double centroidX, double centroidY,
                                         double areaUm2, Map<String, Double> markers) {
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(centroidX, centroidY, ImagePlane.getDefaultPlane()));
        var m = o.getMeasurements();
        m.put("Centroid X µm", centroidX);
        m.put("Centroid Y µm", centroidY);
        markers.forEach((name, value) -> {
            m.put(name, value);                                 // bare == whole-cell mean
            m.put(name + ": Cell: Mean", value);
            m.put(name + ": Nucleus: Mean", value * 2.0);
            m.put(name + ": Cytoplasm: Mean", value * 0.5);
            m.put(name + ": Nucleus: Median", value * 1.5);     // --expanded
            m.put(name + ": Cell: Sum", value * areaUm2);       // --expanded
        });
        m.put("Area µm²", areaUm2);
        m.put("Eccentricity", 0.6);
        m.put("Perimeter µm", 30.0);
        m.put("Solidity", 0.84);
        m.put("Convex Area µm²", areaUm2 / 0.84);
        m.put("Major Axis Length µm", 10.0);
        m.put("Minor Axis Length µm", 5.0);
        return o;
    }

    private static boolean[] allTrue(int n) {
        boolean[] m = new boolean[n];
        Arrays.fill(m, true);
        return m;
    }

    private record Csv(List<String> header, List<List<String>> rows) {
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

    private Csv export(CellIndex index, MarkerStats stats, GateTree tree) throws IOException {
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File f = tempDir.resolve("mirage.csv").toFile();
        PhenotypeCsvExporter.export(f, index, result, tree, stats);
        List<String> lines = Files.readAllLines(f.toPath());
        List<String> hdr = new ArrayList<>(List.of(lines.get(0).split(",", -1)));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(new ArrayList<>(List.of(lines.get(i).split(",", -1))));
        }
        return new Csv(hdr, rows);
    }

    // ---- morphology: QuPath-native names must resolve ---------------------------

    @Test
    void qupathNativeMorphologyKeysResolve() {
        CellIndex idx = CellIndex.build(
                List.of(mirageCell(12.5, 25.0, 42.0, Map.of("DAPI", 100.0))),
                List.of("DAPI"));

        assertEquals(42.0, idx.getArea(0), 1e-4, "\"Area µm²\" must resolve via prefix match");
        assertEquals(30.0, idx.getPerimeter(0), 1e-4, "\"Perimeter µm\"");
        assertEquals(0.6, idx.getEccentricity(0), 1e-4, "\"Eccentricity\" (capitalised)");
        assertEquals(0.84, idx.getSolidity(0), 1e-4, "Area µm² / Convex Area µm²");
        assertEquals(12.5, idx.getCentroidX(0), 1e-4, "\"Centroid X µm\" — micrometres");
        assertEquals(25.0, idx.getCentroidY(0), 1e-4);
        assertEquals(100.0, idx.getMarkerValues(0)[0], 1e-4, "bare marker == whole-cell mean");
    }

    @Test
    void solidityFallsBackToTheExportedMeasurementWhenConvexAreaIsAbsent() {
        // MIRAGE only emits Convex Area when the upstream column survives; Solidity
        // is emitted independently. Losing solidity strips it from the quality filter.
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        o.getMeasurements().put("CD3", 10.0);
        o.getMeasurements().put("Area µm²", 42.0);
        o.getMeasurements().put("Solidity", 0.9);

        CellIndex idx = CellIndex.build(List.of(o), List.of("CD3"));
        assertEquals(0.9, idx.getSolidity(0), 1e-4,
                "with no Convex Area, the directly exported Solidity must be used");
    }

    @Test
    void solidityIsNaNWhenNeitherConvexAreaNorSolidityIsPresent() {
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        o.getMeasurements().put("CD3", 10.0);
        o.getMeasurements().put("Area µm²", 42.0);

        CellIndex idx = CellIndex.build(List.of(o), List.of("CD3"));
        assertTrue(Double.isNaN(idx.getSolidity(0)),
                "absent solidity must stay NaN, never a fabricated 0 or 1");
    }

    // ---- per-compartment keys ----------------------------------------------------

    @Test
    void perCompartmentKeysResolveToDistinctColumns() {
        CellIndex idx = CellIndex.build(
                List.of(mirageCell(0, 0, 100.0, Map.of("CD3", 50.0))),
                List.of("CD3"));

        assertEquals(50.0, idx.getResolvedColumn("CD3", Compartment.WHOLE_CELL, Statistic.MEAN)[0], 1e-4);
        assertEquals(100.0, idx.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEAN)[0], 1e-4);
        assertEquals(25.0, idx.getResolvedColumn("CD3", Compartment.CYTOPLASMIC, Statistic.MEAN)[0], 1e-4);
        assertEquals(75.0, idx.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEDIAN)[0], 1e-4);
        assertEquals(5000.0, idx.getResolvedColumn("CD3", Compartment.WHOLE_CELL, Statistic.SUM)[0], 1e-4);
    }

    @Test
    void capabilityScanReportsExactlyWhatMirageWrote() {
        var cap = CompartmentCapability.scan(
                List.of(mirageCell(0, 0, 100.0, Map.of("CD3", 50.0))), 10);

        assertTrue(cap.isRich(), "a MIRAGE compartment export is a rich GeoJSON");
        assertEquals(EnumSet.allOf(Compartment.class), EnumSet.copyOf(cap.compartmentsFor("CD3")));
        assertEquals(EnumSet.of(Statistic.MEAN, Statistic.MEDIAN, Statistic.SUM),
                EnumSet.copyOf(cap.statisticsFor("CD3")),
                "--expanded_quantification adds Median and Sum");
    }

    @Test
    void legacyWholeCellOnlyExportIsNotMistakenForRich() {
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        o.getMeasurements().put("CD3", 50.0);
        o.getMeasurements().put("Area µm²", 100.0);

        var cap = CompartmentCapability.scan(List.of(o), 10);
        assertFalse(cap.isRich(), "no compartment keys -> legacy, selectors stay pinned");
        assertTrue(cap.compartmentsFor("CD3").isEmpty());
    }

    @Test
    void structuredOnlyExportStillResolvesTheWholeCellDefault() {
        // "CD3: Cell: Mean" *is* the whole-cell mean. If a producer emits only the
        // structured keys, the default selection must read them rather than go NaN
        // while CompartmentCapability advertises the marker as available.
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        o.getMeasurements().put("CD3: Cell: Mean", 50.0);
        o.getMeasurements().put("CD3: Nucleus: Mean", 80.0);
        o.getMeasurements().put("Area µm²", 100.0);

        CellIndex idx = CellIndex.build(List.of(o), List.of("CD3"));
        assertEquals(50.0, idx.getMarkerValues(0)[0], 1e-4,
                "the base column must fall back to the structured whole-cell mean");
        assertEquals(50.0, idx.getResolvedColumn("CD3", Compartment.WHOLE_CELL, Statistic.MEAN)[0], 1e-4);
        assertEquals(80.0, idx.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEAN)[0], 1e-4);
    }

    @Test
    void layerPrefixedCompartmentKeysResolve() {
        // import_phenotype.groovy prefixes measurements with "[Layer0] ".
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        o.getMeasurements().put("[Layer0] CD3", 50.0);
        o.getMeasurements().put("[Layer0] CD3: Nucleus: Mean", 80.0);
        o.getMeasurements().put("[Layer0] Area µm²", 100.0);

        CellIndex idx = CellIndex.build(List.of(o), List.of("CD3"));
        assertEquals(50.0, idx.getMarkerValues(0)[0], 1e-4);
        assertEquals(80.0, idx.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEAN)[0], 1e-4);
        assertEquals(100.0, idx.getArea(0), 1e-4);
    }

    // ---- end-to-end: MIRAGE cells -> nuclear gate -> CSV -------------------------

    @Test
    void mirageShapedCellsGateAndExportEndToEnd() throws IOException {
        // Four cells whose whole-cell CD3 rises 10, 20, 30, 40; nuclear is 2x.
        // A nuclear raw gate at 50 splits after the second cell.
        List<PathObject> cells = new ArrayList<>();
        double[] cd3 = {10, 20, 30, 40};
        for (int i = 0; i < 4; i++) {
            cells.add(mirageCell(i * 10.0, i * 20.0, 100.0 + i,
                    Map.of("CD3", cd3[i], "DAPI", 500.0)));
        }
        CellIndex idx = CellIndex.build(cells, List.of("CD3", "DAPI"));
        MarkerStats stats = MarkerStats.compute(idx, allTrue(4));

        GateNode gate = new GateNode("CD3");
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);
        gate.setThreshold(50.0);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        Csv csv = export(idx, stats, tree);

        assertEquals(4, csv.rows().size(), "one row per cell");

        // Morphology and spatial columns survive the whole trip in µm.
        assertEquals(0.0, csv.num(0, "centroid_x"), 1e-4);
        assertEquals(60.0, csv.num(3, "centroid_y"), 1e-4);
        assertEquals(100.0, csv.num(0, "area"), 1e-4);
        assertEquals(30.0, csv.num(0, "perimeter"), 1e-4);
        assertEquals(0.84, csv.num(0, "solidity"), 1e-4);

        // The gated column is reported under its own resolved header, carrying
        // nuclear values (2x), not the whole-cell ones.
        assertEquals(20.0, csv.num(0, "CD3_Nucleus_Mean_raw"), 1e-4);
        assertEquals(80.0, csv.num(3, "CD3_Nucleus_Mean_raw"), 1e-4);
        assertEquals(10.0, csv.num(0, "CD3_raw"), 1e-4, "bare CD3 stays whole-cell");

        // Sign agrees with phenotype on every row — the invariant the CSV is read for.
        String[] expectedSigns = {"-", "-", "+", "+"};
        for (int i = 0; i < 4; i++) {
            assertEquals(expectedSigns[i], csv.val(i, "CD3_Nucleus_Mean_sign"), "row " + i);
            assertEquals(csv.val(i, "phenotype").endsWith("+") ? "+" : "-",
                    csv.val(i, "CD3_Nucleus_Mean_sign"),
                    "row " + i + ": phenotype and sign must never disagree");
        }

        // DAPI is constant, ungated: raw present, z-score blank (std 0), sign blank.
        assertEquals(500.0, csv.num(0, "DAPI_raw"), 1e-4);
        assertEquals("", csv.val(0, "DAPI_zscore"), "zero-variance column has no z-score");
        assertEquals("", csv.val(0, "DAPI_sign"), "an ungated marker has no sign");
    }

    @Test
    void everyCsvNumericFieldParsesUnderAForeignLocale() throws IOException {
        // The JVM default locale here is en_IT; a comma decimal separator would make
        // the file unparseable by pandas.read_csv without breaking any other test.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            CellIndex idx = CellIndex.build(
                    List.of(mirageCell(1.5, 2.5, 3.25, Map.of("CD3", 3.14159)),
                            mirageCell(4.5, 5.5, 6.75, Map.of("CD3", 2.71828))),
                    List.of("CD3"));
            MarkerStats stats = MarkerStats.compute(idx, allTrue(2));

            GateNode gate = new GateNode("CD3", 3.0);
            gate.setThresholdIsZScore(false);
            GateTree tree = new GateTree();
            tree.setQualityFilter(null);
            tree.addRoot(gate);

            Csv csv = export(idx, stats, tree);

            // Splitting on "," is only safe because no field contains a decimal comma.
            for (int r = 0; r < csv.rows().size(); r++) {
                assertEquals(csv.header().size(), csv.rows().get(r).size(),
                        "row " + r + " field count must match the header — a decimal "
                                + "comma would have split a number into two fields");
            }
            assertEquals(3.1416, csv.num(0, "CD3_raw"), 1e-4);
            assertEquals(3.25, csv.num(0, "area"), 1e-4);
            assertEquals(1.5, csv.num(0, "centroid_x"), 1e-4);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void headerFieldsAreFullyQuotedWhenAChannelNameContainsAComma() throws IOException {
        // OME-TIFF channel names carry clone/antibody detail and can contain commas.
        // The suffix must live inside the quotes: `"CD3, clone UCHT1_raw"`, never
        // `"CD3, clone UCHT1"_raw` — text after a closing quote is not valid CSV.
        String marker = "CD3, clone UCHT1";
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        o.getMeasurements().put(marker, 10.0);
        o.getMeasurements().put("Area µm²", 100.0);

        CellIndex idx = CellIndex.build(List.of(o), List.of(marker));
        MarkerStats stats = MarkerStats.compute(idx, allTrue(1));

        GateNode gate = new GateNode(marker, 5.0);
        gate.setThresholdIsZScore(false);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, idx, stats);
        File f = tempDir.resolve("comma_header.csv").toFile();
        PhenotypeCsvExporter.export(f, idx, result, tree, stats);

        String header = Files.readAllLines(f.toPath()).get(0);
        assertTrue(header.contains("\"" + marker + "_raw\""),
                "the suffix must be inside the quotes; header was: " + header);
        assertFalse(header.contains("\"" + marker + "\"_raw"),
                "quote must not close before the suffix; header was: " + header);
    }

    // ---- documented precision limit ---------------------------------------------

    @Test
    void measurementsRoundTripAtFloatPrecisionNotDouble() {
        // QuPath's MeasurementList stores float. MIRAGE rounds Mean to 4 decimals so
        // this is invisible there, but --expanded Sum (integrated density) routinely
        // exceeds 2^24, where float spacing is > 1. This test states the limit so the
        // loss is a known property of the CSV rather than a suspected export bug.
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        o.getMeasurements().put("CD3", 1234.5679);
        o.getMeasurements().put("CD3: Cell: Sum", 123456789.0);
        o.getMeasurements().put("Area µm²", 100.0);

        CellIndex idx = CellIndex.build(List.of(o), List.of("CD3"));

        assertEquals(1234.5679, idx.getMarkerValues(0)[0], 1e-3,
                "a 4-decimal Mean survives float storage at CSV precision");

        double sum = idx.getResolvedColumn("CD3", Compartment.WHOLE_CELL, Statistic.SUM)[0];
        assertEquals(123456789.0, sum, 16.0, "Sum lands within one float ULP at 1.2e8");
        assertNotEquals(123456789.0, sum,
                "and it is NOT exact — do not treat _raw on a Sum column as integer-precise");
    }
}
