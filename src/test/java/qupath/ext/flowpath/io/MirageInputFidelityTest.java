package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestOptions;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.model.*;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;

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
     * Cells as {@code export_geojson.py} writes them: micrometre centroids taken from the
     * ROI, {@link Cells#mirageMarker} for each marker's bare + per-compartment family
     * (nuclear 2x, cytoplasmic 0.5x, the {@code --expanded} Nucleus Median and Cell Sum),
     * and {@link Cells#mirageMorphology} for the µm-suffixed shape block.
     * <p>
     * This shape is the reason the shared fixture speaks the MIRAGE key grammar at all:
     * this class is the pin on what QuPath actually hands FlowPath, so the builder had to
     * absorb its shape rather than the other way round.
     */
    private static Cells mirageCells(int n) {
        return Cells.of(n).centroidsMicronsFromRoi(1.0);
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
        CellIndex idx = mirageCells(1).at(new double[]{12.5}, new double[]{25.0})
                .mirageMarker("DAPI", 100.0)
                .mirageMorphology(42.0)
                .build();

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
        CellIndex idx = Cells.of(1)
                .marker("CD3", 10.0)
                .morphology("Area µm²", 42.0)
                .morphology("Solidity", 0.9)
                .build();
        assertEquals(0.9, idx.getSolidity(0), 1e-4,
                "with no Convex Area, the directly exported Solidity must be used");
    }

    @Test
    void solidityIsNaNWhenNeitherConvexAreaNorSolidityIsPresent() {
        CellIndex idx = Cells.of(1)
                .marker("CD3", 10.0)
                .morphology("Area µm²", 42.0)
                .build();
        assertTrue(Double.isNaN(idx.getSolidity(0)),
                "absent solidity must stay NaN, never a fabricated 0 or 1");
    }

    // ---- per-compartment keys ----------------------------------------------------

    @Test
    void perCompartmentKeysResolveToDistinctColumns() {
        CellIndex idx = mirageCells(1).mirageMarker("CD3", 50.0).mirageMorphology(100.0).build();

        assertEquals(50.0, idx.getResolvedColumn("CD3", Compartment.WHOLE_CELL, Statistic.MEAN)[0], 1e-4);
        assertEquals(100.0, idx.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEAN)[0], 1e-4);
        assertEquals(25.0, idx.getResolvedColumn("CD3", Compartment.CYTOPLASMIC, Statistic.MEAN)[0], 1e-4);
        assertEquals(75.0, idx.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEDIAN)[0], 1e-4);
        assertEquals(5000.0, idx.getResolvedColumn("CD3", Compartment.WHOLE_CELL, Statistic.SUM)[0], 1e-4);
    }

    @Test
    void capabilityScanReportsExactlyWhatMirageWrote() {
        var cap = CompartmentCapability.scan(
                mirageCells(1).mirageMarker("CD3", 50.0).mirageMorphology(100.0).detections(), 10);

        assertTrue(cap.isRich(), "a MIRAGE compartment export is a rich GeoJSON");
        assertEquals(EnumSet.allOf(Compartment.class), EnumSet.copyOf(cap.compartmentsFor("CD3")));
        assertEquals(java.util.Set.of(Statistic.MEAN, Statistic.MEDIAN, Statistic.SUM),
                cap.statisticsFor("CD3"),
                "naming Mean and Sum in --quantify_statistics adds them alongside Median");
    }

    @Test
    void legacyWholeCellOnlyExportIsNotMistakenForRich() {
        var cap = CompartmentCapability.scan(
                Cells.of(1).marker("CD3", 50.0).morphology("Area µm²", 100.0).detections(), 10);
        assertFalse(cap.isRich(), "no compartment keys -> legacy, selectors stay pinned");
        assertTrue(cap.compartmentsFor("CD3").isEmpty());
    }

    @Test
    void structuredOnlyExportStillResolvesTheWholeCellDefault() {
        // "CD3: Cell: Mean" *is* the whole-cell mean. If a producer emits only the
        // structured keys, the default selection must read them rather than go NaN
        // while CompartmentCapability advertises the marker as available.
        CellIndex idx = Cells.of(1)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, 50.0)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEAN, 80.0)
                .morphology("Area µm²", 100.0)
                .build();
        assertEquals(50.0, idx.getMarkerValues(0)[0], 1e-4,
                "the base column must fall back to the structured whole-cell mean");
        assertEquals(50.0, idx.getResolvedColumn("CD3", Compartment.WHOLE_CELL, Statistic.MEAN)[0], 1e-4);
        assertEquals(80.0, idx.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEAN)[0], 1e-4);
    }

    @Test
    void nonExpandedMedianDefaultExportResolvesForAFreshGate() {
        // MIRAGE's *default* (non-expanded) run always emits "<marker>: Cell: Median"
        // for every compartment, plus the bare whole-cell mean, but NOT Cell Mean/Sum.
        // FlowPath's new default statistic is Median, so a freshly created gate must read
        // that structured column rather than go NaN. This is now the common production input.
        Cells cells = Cells.of(1)
                .marker("CD3", 50.0)                                                   // bare == whole-cell mean
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN, 30.0)         // always present, even non-expanded
                .morphology("Area µm²", 100.0);

        // Discovery advertises exactly Median for the Cell compartment (no Mean/Sum columns).
        var cap = CompartmentCapability.scan(cells.detections(), 10);
        assertTrue(cap.isRich(), "a per-compartment Median key makes this a rich export");
        assertEquals(java.util.Set.of(Statistic.MEDIAN), cap.statisticsFor("CD3"),
                "a default (non-expanded) run exposes only Median");

        CellIndex idx = cells.build();
        // The bare base column is still the whole-cell mean.
        assertEquals(50.0, idx.getMarkerValues(0)[0], 1e-4);

        // A fresh gate defaults to Median and resolves to the structured Cell Median column,
        // not the bare mean.
        GateNode gate = new GateNode("CD3");
        assertEquals(Statistic.MEDIAN, gate.getStatistic(), "fresh gates default to Median");
        assertEquals(30.0,
                idx.getResolvedColumn("CD3", gate.getCompartment(), gate.getStatistic())[0], 1e-4,
                "default gate reads Cell Median, not the bare whole-cell mean");
    }

    @Test
    void layerPrefixedCompartmentKeysResolve() {
        // import_phenotype.groovy prefixes measurements with "[Layer0] ".
        CellIndex idx = Cells.of(1)
                .marker("CD3", 50.0)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEAN, 80.0)
                .morphology("Area µm²", 100.0)
                .layerPrefixed()
                .build();
        assertEquals(50.0, idx.getMarkerValues(0)[0], 1e-4);
        assertEquals(80.0, idx.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEAN)[0], 1e-4);
        assertEquals(100.0, idx.getArea(0), 1e-4);
    }

    // ---- end-to-end: MIRAGE cells -> nuclear gate -> CSV -------------------------

    @Test
    void mirageShapedCellsGateAndExportEndToEnd() throws IOException {
        // Four cells whose whole-cell CD3 rises 10, 20, 30, 40; nuclear is 2x.
        // A nuclear raw gate at 50 splits after the second cell.
        double[] cd3 = {10, 20, 30, 40};
        CellIndex idx = mirageCells(4).at(i -> i * 10.0, i -> i * 20.0)
                .mirageMarker("CD3", cd3)
                .mirageMarker("DAPI", 500.0)
                .mirageMorphology(i -> 100.0 + i)
                .build();
        MarkerStats stats = MarkerStats.compute(idx, Cells.allTrue(4));

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

            CellIndex idx = mirageCells(2)
                    .at(new double[]{1.5, 4.5}, new double[]{2.5, 5.5})
                    .mirageMarker("CD3", 3.14159, 2.71828)
                    .mirageMorphology(3.25, 6.75)
                    .build();
            MarkerStats stats = MarkerStats.compute(idx, Cells.allTrue(2));

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
        CellIndex idx = Cells.of(1)
                .marker(marker, 10.0)
                .morphology("Area µm²", 100.0)
                .build();
        MarkerStats stats = MarkerStats.compute(idx, Cells.allTrue(1));

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

    // ---- the ingest report: a clean export must say nothing ---------------------

    @Test
    void aCleanMirageExportIngestsWithAnEmptyReport() {
        // The other side of every assertion above. Those pin that a MIRAGE export
        // RESOLVES; this pins that resolving it produces no complaint, so the report is
        // additive information rather than a new gate on whether data loads. If a future
        // change makes a clean export "dirty", the status bar starts crying wolf on every
        // image and the warning stops meaning anything.
        List<PathObject> cells = mirageCells(5).at(i -> i * 10.0, i -> i * 20.0)
                .mirageMarker("CD3", i -> 10.0 + i)
                .mirageMarker("DAPI", i -> 500.0 + i)
                .mirageMorphology(i -> 100.0 + i)
                .detections();

        IngestResult result = DetectionIngest.read(cells,
                IngestOptions.none().withChannelNames(List.of("CD3", "DAPI")));

        assertEquals(List.of("CD3", "DAPI"), result.markerNames());
        assertTrue(result.capability().isRich());
        assertTrue(result.report().isClean(),
                "a clean MIRAGE export must ingest silently; got: " + result.report().findings());
        assertEquals("", result.report().summary());
    }

    @Test
    void anOmittedMeasurementIsReportedWhileAGenuineZeroIsNot() {
        // The two things export_geojson.py and quantify.py write that look alike and mean
        // opposite things. export_geojson.py:112-114 OMITS a measurement whose value is
        // NaN — a failed upstream join, so the value is UNKNOWN. quantify.py writes a
        // literal 0.0 for a compartment with genuinely zero pixels — the value is KNOWN,
        // and it is zero. FlowPath's gating collapses them; the report does not.
        List<PathObject> cells = mirageCells(4).at(i -> i, i -> i)
                .mirageMarker("CD3", i -> 10.0 + i)
                .mirageMorphology(100.0)
                // quantify.py's `default=0.0` for an anucleate cell.
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, 0.0)
                // export_geojson.py simply never appends a NaN, so the key is absent on 2 of 4.
                .marker("DAPI", 500.0).absentOn(i -> i >= 2)
                .detections();

        IngestResult result = DetectionIngest.read(cells,
                IngestOptions.none().withChannelNames(List.of("CD3", "DAPI")));

        assertEquals(Map.of("DAPI", 2), result.report().cellsMissingResolvedKey(),
                "omitted upstream: NaN, and a NaN criterion PASSES the quality filter");
        assertFalse(result.report().isClean(), "the omission is a finding");
        assertTrue(result.report().sampledZeroValueCells().isEmpty(),
                "the whole-cell CD3 column carries no zeros — only the nuclear one does, "
                        + "and the default selection does not read it");
    }

    // ---- documented precision limit ---------------------------------------------

    @Test
    void measurementsRoundTripAtFloatPrecisionNotDouble() {
        // QuPath's MeasurementList stores float. MIRAGE rounds Mean to 4 decimals so
        // this is invisible there, but --expanded Sum (integrated density) routinely
        // exceeds 2^24, where float spacing is > 1. This test states the limit so the
        // loss is a known property of the CSV rather than a suspected export bug.
        CellIndex idx = Cells.of(1)
                .marker("CD3", 1234.5679)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.SUM, 123456789.0)
                .morphology("Area µm²", 100.0)
                .build();

        assertEquals(1234.5679, idx.getMarkerValues(0)[0], 1e-3,
                "a 4-decimal Mean survives float storage at CSV precision");

        double sum = idx.getResolvedColumn("CD3", Compartment.WHOLE_CELL, Statistic.SUM)[0];
        assertEquals(123456789.0, sum, 16.0, "Sum lands within one float ULP at 1.2e8");
        assertNotEquals(123456789.0, sum,
                "and it is NOT exact — do not treat _raw on a Sum column as integer-precise");
    }
}
