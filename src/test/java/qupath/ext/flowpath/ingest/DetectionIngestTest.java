package qupath.ext.flowpath.ingest;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.ScaleVerdict;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ingest seam: does it name what it could not resolve?
 * <p>
 * {@link qupath.ext.flowpath.io.MirageInputFidelityTest} pins that a MIRAGE-shaped export
 * <em>resolves</em>. This pins the other half — that everything which does <em>not</em>
 * resolve is reported rather than silently absorbed. Before {@link DetectionIngest} every
 * case below produced exactly one symptom: an empty histogram, which reads as "no cells
 * are positive" rather than "this axis never resolved".
 */
class DetectionIngestTest {

    // ---- fixtures ---------------------------------------------------------------

    /**
     * A {@code bin/export_geojson.py} export from a <em>default</em> (non-expanded) run:
     * Median for every compartment and no Mean/Sum, with the shape block MIRAGE writes.
     * Marker {@code j} of cell {@code i} reads {@code 10 + i + j}.
     */
    private static Cells mirageExportCells(int n, String... markers) {
        Cells cells = Cells.of(n).at(i -> i, i -> i * 2.0).centroidsMicronsFromRoi(1.0);
        for (int j = 0; j < markers.length; j++) {
            int column = j;
            cells.mirageMedianMarker(markers[j], i -> 10.0 + i + column);
        }
        return cells.mirageMorphology(i -> 42.0, i -> 50.0);
    }

    private static List<PathObject> mirageExport(int n, String... markers) {
        return mirageExportCells(n, markers).detections();
    }

    /** One cell of that same export shape, at a position of its own. */
    private static PathObject mirageCell(double x, double y, String marker, double value) {
        return Cells.of(1).at(new double[]{x}, new double[]{y})
                .centroidsMicronsFromRoi(1.0)
                .mirageMedianMarker(marker, value)
                .mirageMorphology(i -> 42.0, i -> 50.0)
                .detections().get(0);
    }

    private static IngestResult read(List<PathObject> cells, String... channels) {
        return DetectionIngest.read(cells,
                IngestOptions.none().withChannelNames(List.of(channels)));
    }

    // ---- the baseline: a clean export must report nothing ------------------------

    @Test
    void aCleanMirageExportProducesAnEmptyReport() {
        var cells = mirageExport(40, "CD3", "CD8", "DAPI");
        IngestResult r = read(cells, "CD3", "CD8", "DAPI");

        assertEquals(List.of("CD3", "CD8", "DAPI"), r.markerNames(), "panel order preserved");
        assertTrue(r.capability().isRich(), "per-compartment keys make this a rich export");
        assertEquals(40, r.index().size());

        IngestReport report = r.report();
        assertTrue(report.isClean(),
                "a clean MIRAGE export must report nothing; got: " + report.findings());
        assertEquals(List.of(), report.findings());
        assertEquals("", report.summary(), "nothing reaches the status bar");
        assertEquals(List.of(), report.notes(), "no literal zeros in this fixture");
        assertEquals(IngestReport.Source.IMAGE_CHANNELS, report.discovery().winner());
        assertFalse(report.discovery().disagreed(),
                "the image and the GeoJSON describe the same panel");
        assertEquals(ScaleVerdict.Status.NO_CALIBRATION, report.scaleVerdict().status(),
                "an uncalibrated image is ordinary, not a finding");
    }

    @Test
    void aCleanExportWithNoChannelMetadataIsStillClean() {
        // The common case: GeoJSON imported without a matching OME-TIFF. One opinion is
        // not a disagreement.
        IngestResult r = DetectionIngest.read(mirageExport(10, "CD3", "CD8"), IngestOptions.none());

        assertEquals(List.of("CD3", "CD8"), r.markerNames());
        assertEquals(IngestReport.Source.MEASUREMENTS, r.report().discovery().winner());
        assertFalse(r.report().discovery().disagreed());
        assertTrue(r.report().isClean(), "got: " + r.report().findings());
    }

    // ---- dropped channels --------------------------------------------------------

    @Test
    void aChannelWithNoMeasurementBehindItIsDroppedAndNamed() {
        var cells = mirageExport(10, "CD3", "CD8");
        IngestResult r = read(cells, "CD3", "CD8", "Phantom");

        assertEquals(List.of("CD3", "CD8"), r.markerNames(), "Phantom never reaches the panel");
        assertEquals(List.of("Phantom"), r.report().droppedChannels(),
                "and the report says so — this was previously silent");
        assertFalse(r.report().isClean());
        assertTrue(r.report().summary().contains("Phantom"),
                "the status bar names it: " + r.report().summary());
    }

    @Test
    void aChannelKeptOnlyByItsStructuredKeysIsReported() {
        // export_geojson.py OMITS a NaN measurement, so a marker whose whole-cell mean
        // failed to join upstream arrives with per-compartment keys and no bare column.
        // CellIndex resolves it through the structured key, so dropping the channel here
        // would be the two halves disagreeing; keeping it silently would hide the join
        // failure. It is kept AND reported.
        List<PathObject> cells = Cells.of(1)
                .marker("CD3", 50.0)
                .marker("CD8", Compartment.WHOLE_CELL, Statistic.MEDIAN, 30.0)
                .morphology("Area µm²", 42.0)
                .detections();

        IngestResult r = read(cells, "CD3", "CD8");

        assertEquals(List.of("CD3", "CD8"), r.markerNames());
        assertEquals(List.of("CD8"), r.report().channelsResolvedOnlyByStructuredKey());
        assertFalse(r.report().isClean());
        assertEquals(30.0,
                r.index().getResolvedColumn("CD8", Compartment.WHOLE_CELL, Statistic.MEDIAN)[0], 1e-6,
                "and it really does resolve — the report is not describing a broken column");
    }

    // ---- the two discovery paths disagreeing -------------------------------------

    @Test
    void whenTheImageAndTheMeasurementsDisagreeTheReportSaysWhichWon() {
        // The image declares CD3 and CD8; the export quantified CD3 and CD19. This is a
        // mismatched pair of files, and it used to be undetectable because the losing
        // candidate panel was never even constructed.
        var cells = mirageExport(10, "CD3", "CD19");
        IngestResult r = read(cells, "CD3", "CD8");

        var d = r.report().discovery();
        assertEquals(IngestReport.Source.IMAGE_CHANNELS, d.winner(),
                "the validated channel list still wins — behaviour is unchanged");
        assertEquals(List.of("CD3"), r.markerNames());
        assertTrue(d.disagreed());
        assertEquals(List.of(), d.onlyInChannels(), "CD8 was dropped, so it is not in the panel");
        assertEquals(List.of("CD19"), d.onlyInMeasurements(),
                "CD19 was quantified but the image never declares it");
        assertEquals(List.of("CD8"), r.report().droppedChannels());

        String summary = r.report().summary();
        assertFalse(summary.isEmpty());
        assertTrue(r.report().describe().contains("image channels won"),
                "the report names the winner: " + r.report().describe());
    }

    @Test
    void theMeasurementPathWinsOnlyWhenNoChannelValidates() {
        var cells = mirageExport(10, "CD3", "CD19");
        IngestResult r = read(cells, "Phantom1", "Phantom2");

        assertEquals(IngestReport.Source.MEASUREMENTS, r.report().discovery().winner());
        assertEquals(List.of("CD19", "CD3"), r.markerNames(), "measurement-derived panels sort");
        assertEquals(List.of("Phantom1", "Phantom2"), r.report().droppedChannels());
        assertTrue(r.report().describe().contains("measurements won"));
    }

    // ---- sample depth ------------------------------------------------------------

    @Test
    void aMarkerFirstAppearingAfterCellTwentyStillResolves() {
        // The 2.0.1 drift: CellIndex sampled 20 keys while the capability scan sampled
        // 100, so a marker whose keys first appeared at cell 50 was OFFERED in the gate
        // editor and resolved to nothing. The two depths are now the same constant.
        assertEquals(CompartmentCapability.DEFAULT_SAMPLE_SIZE, CellIndex.KEY_SAMPLE_SIZE,
                "the resolution sample and the capability scan must share one depth");

        var cells = new ArrayList<>(mirageExport(50, "CD3"));
        cells.addAll(mirageExport(50, "CD3", "LateMarker"));

        IngestResult r = DetectionIngest.read(cells, IngestOptions.none());
        assertTrue(r.markerNames().contains("LateMarker"), "discovered");
        assertTrue(r.capability().hasCompartments("LateMarker"), "and its compartments are offered");
        assertFalse(r.report().unresolvedMarkers().contains("LateMarker"),
                "and it resolves, so the gate editor is not offering a NaN column");
        // Cell 50 is the first of the second batch; mirageExport gives marker j of cell i
        // the value 10 + i + j, so LateMarker (j = 1) on its first cell reads 11.
        assertEquals(11.0, r.index().getMarkerValues(r.index().getMarkerIndex("LateMarker"))[50], 1e-6);
    }

    @Test
    void aMarkerPresentOnlyBeyondTheKeySampleIsReportedAsUnresolvable() {
        // Past the sample the resolution has to give up — that is the deliberate v2.0.1
        // tradeoff. What is new is that giving up is now stated instead of producing a
        // silently empty histogram.
        var cells = new ArrayList<>(mirageExport(CellIndex.KEY_SAMPLE_SIZE + 10, "CD3"));
        cells.addAll(mirageExport(5, "CD3", "VeryLateMarker"));

        IngestResult r = DetectionIngest.read(cells,
                IngestOptions.none().withChannelNames(List.of("CD3", "VeryLateMarker")));

        assertEquals(List.of("CD3"), r.markerNames(),
                "beyond the sample the channel cannot be validated, so it is dropped");
        assertEquals(List.of("VeryLateMarker"), r.report().droppedChannels());
        assertFalse(r.report().isClean());
    }

    @Test
    void aSelectionNamingAStatisticTheExportLacksIsReportedAsUnresolved() {
        // The 2.0.1 defect class, now visible. MIRAGE's default (non-expanded) run emits
        // Median only; a selection asking for Sum resolves to a measurement key that is not
        // in the file, and every cell reads NaN. Nothing refused it, and the only symptom
        // was an empty plot.
        var cells = mirageExport(10, "CD3");   // Median keys only
        IngestResult r = DetectionIngest.read(cells, IngestOptions.none()
                .withSelectionResolver((markers, cap) -> {
                    var sel = qupath.ext.flowpath.model.MarkerSelection.defaultFor(markers);
                    sel.put("CD3", new qupath.ext.flowpath.model.MarkerSelection.Entry(
                            Compartment.NUCLEAR, Statistic.SUM, true));
                    return sel;
                }));

        assertEquals(List.of("CD3"), r.report().unresolvedMarkers());
        assertFalse(r.report().isClean());
        assertTrue(r.report().describe().contains("empty histogram"),
                "the report must connect the cause to the symptom: " + r.report().describe());
        assertTrue(Double.isNaN(r.index().getMarkerValues(0)[0]),
                "and the column really is NaN — the report is not a false alarm");
    }

    // ---- a cell missing a key the sample resolved --------------------------------

    @Test
    void aCellMissingAKeyTheSampleResolvedIsCountedAndItsQcConsequenceStated() {
        // export_geojson.py omits a NaN measurement entirely. The cell reads NaN with no
        // rescan, and because QualityFilter.passes skips every NaN criterion, such a cell
        // PASSES QC rather than being excluded. That is the sentence the report carries.
        var cells = new ArrayList<>(mirageExport(10, "CD3", "CD8"));
        cells.add(mirageCell(99, 99, "CD3", 12.0));   // no CD8 at all
        cells.add(mirageCell(98, 98, "CD3", 13.0));

        IngestResult r = DetectionIngest.read(cells, IngestOptions.none());

        assertEquals(Map.of("CD8", 2), r.report().cellsMissingResolvedKey());
        assertTrue(Double.isNaN(r.index().getMarkerValues(r.index().getMarkerIndex("CD8"))[10]));
        assertFalse(r.report().isClean());
        assertTrue(r.report().describe().contains("PASSES the quality filter"),
                "the report must state the QC consequence: " + r.report().describe());
    }

    @Test
    void anOmittedMeasurementAndAGenuineZeroAreReportedSeparately() {
        // The distinction FlowPath's gating cannot represent. export_geojson.py OMITS a
        // NaN (unknown); quantify.py writes a literal 0.0 for a genuinely empty
        // compartment (known, and zero). Both look like "no signal" in a histogram.
        List<PathObject> cells = Cells.of(5).at(i -> i, i -> i)
                .morphology("Area µm²", 42.0)
                .marker("Anucleate", 0.0)                          // quantify.py: truly empty
                .marker("Joined", 5.0).absentOn(i -> i >= 3)       // export_geojson.py: omitted on 2
                .detections();

        IngestResult r = DetectionIngest.read(cells, IngestOptions.none());

        assertEquals(Map.of("Joined", 2), r.report().cellsMissingResolvedKey(),
                "omitted-upstream is a finding: the value is unknown");
        assertEquals(Map.of("Anucleate", 5), r.report().sampledZeroValueCells(),
                "a literal 0.0 is a separate count: the value is known, and it is zero");
        assertTrue(r.report().notes().get(0).contains("literal 0.0"));
        assertTrue(r.report().notes().get(0).contains("NOT the same"),
                "the note must say the two are different: " + r.report().notes());
    }

    @Test
    void literalZerosAloneDoNotMakeAReportUnclean() {
        // An anucleate cell is ordinary data. Flagging it would make every real export
        // dirty and train the user to ignore the warning.
        List<PathObject> cells = Cells.of(5).at(i -> i, i -> i)
                .morphology("Area µm²", 42.0)
                .marker("CD3", 0.0)
                .detections();
        IngestReport report = DetectionIngest.read(cells, IngestOptions.none()).report();

        assertTrue(report.isClean(), "got: " + report.findings());
        assertFalse(report.notes().isEmpty(), "but it is still on the record");
    }

    // ---- duplicate and null names ------------------------------------------------

    @Test
    void twoChannelsCollapsingToOneMarkerAreReported() {
        var cells = mirageExport(5, "CD3");
        IngestResult r = read(cells, "CD3", "CD3: Cell: Median");

        assertEquals(List.of("CD3"), r.markerNames(), "one row per marker, as before");
        assertEquals(List.of("CD3"), r.report().duplicateMarkerNames(),
                "the collapse is now stated rather than silent");
        assertTrue(r.report().describe().contains("duplicate marker name"));
    }

    @Test
    void blankChannelNamesAreCountedNotJustSkipped() {
        var cells = mirageExport(5, "CD3");
        IngestResult r = DetectionIngest.read(cells, IngestOptions.none()
                .withChannelNames(java.util.Arrays.asList("CD3", null, "   ")));

        assertEquals(List.of("CD3"), r.markerNames());
        assertEquals(2, r.report().nullMarkerNames());
        assertFalse(r.report().isClean());
    }

    @Test
    void duplicateAndNullMarkerNamesInARequestedPanelAreCountedByTheBuild() {
        // The panel DetectionIngest builds can never hold a duplicate or a null, but a
        // caller that builds an index directly (the UMAP feature rebuild) can hand one in.
        // markerIndexByName's putIfAbsent silently kept the first column; now it counts.
        var cells = mirageExport(5, "CD3", "CD8");
        CellIndex idx = CellIndex.build(cells, java.util.Arrays.asList("CD3", "CD8", "CD3", null));

        var d = idx.diagnostics();
        assertEquals(List.of("CD3"), d.duplicateMarkerNames());
        assertEquals(1, d.nullMarkerNames());
        assertEquals(0, idx.getMarkerIndex("CD3"), "first-declared still wins — unchanged");
    }

    // ---- object types ------------------------------------------------------------

    @Test
    void aTileInTheDetectionCollectionIsReportedButNotFiltered() {
        // getDetectionObjects() returns cells, tiles and plain detections alike, so a
        // superpixel silently became a "cell". Filtering would change which objects an
        // existing project gates, so it is counted instead.
        var cells = new ArrayList<>(mirageExport(4, "CD3"));
        PathObject tile = PathObjects.createTileObject(
                ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()));
        tile.getMeasurements().put("CD3", 7.0);
        cells.add(tile);

        IngestResult r = DetectionIngest.read(cells, IngestOptions.none());

        assertEquals(5, r.index().size(), "nothing is refused — the tile is still indexed");
        assertEquals(1, r.report().tileObjects());
        assertFalse(r.report().isClean());
        assertTrue(r.report().describe().contains("tiles/superpixels"),
                r.report().describe());
    }

    @Test
    void realCellObjectsAndPlainDetectionsAreBothOrdinary() {
        // MIRAGE writes objectType "cell"; import_phenotype.groovy produces plain
        // detections. Neither is a defect, and flagging either would make every load dirty.
        PathObject cell = PathObjects.createCellObject(
                ROIs.createRectangleROI(0, 0, 4, 4, ImagePlane.getDefaultPlane()),
                ROIs.createRectangleROI(1, 1, 2, 2, ImagePlane.getDefaultPlane()));
        cell.getMeasurements().put("CD3", 5.0);
        cell.getMeasurements().put("Area µm²", 42.0);

        var cells = new ArrayList<>(mirageExport(3, "CD3"));
        cells.add(cell);

        IngestReport report = DetectionIngest.read(cells, IngestOptions.none()).report();
        assertEquals(1, report.cellObjects());
        assertEquals(3, report.otherObjects());
        assertEquals(0, report.tileObjects());
        assertTrue(report.isClean(), "got: " + report.findings());
    }

    // ---- degenerate input --------------------------------------------------------

    @Test
    void noDetectionsIsNotAFinding() {
        IngestResult r = DetectionIngest.read(List.of(), IngestOptions.none());
        assertEquals(0, r.index().size());
        assertEquals(List.of(), r.markerNames());
        assertEquals(IngestReport.Source.NONE, r.report().discovery().winner());
        assertTrue(r.report().isClean());
    }

    @Test
    void morphologyColumnsNeverReachThePanelAndLabelStaysOut() {
        // Constraint: "label" is a segmentation identity, not a panel member. The CSV
        // exporter writes it as its own column — that is a separate concern.
        var cells = mirageExport(3, "CD3");
        cells.get(0).getMeasurements().put("label", 17.0);

        IngestResult r = DetectionIngest.read(cells, IngestOptions.none());
        assertEquals(List.of("CD3"), r.markerNames());
        assertFalse(r.markerNames().contains("label"));
        assertTrue(DetectionIngest.isMorphologyName("Centroid X µm"));
        assertFalse(DetectionIngest.isMorphologyName("YAP1"),
                "prefix-matching x/y must not swallow real markers");
    }
}
