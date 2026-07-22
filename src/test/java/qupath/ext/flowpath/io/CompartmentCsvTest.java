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
 * The CSV must describe the column the gate actually used.
 * <p>
 * {@code GatingEngine} evaluates against {@code CellIndex.resolvedKey(channel,
 * compartment, statistic)} — {@code "CD3: Nucleus: Mean"} rather than the bare
 * {@code "CD3"}. Before this was fixed the exporter read the bare whole-cell column
 * for every marker triplet, so a nuclear gate produced a file whose {@code _sign}
 * column contradicted its own {@code phenotype} column, and two gates on different
 * compartments of one marker collapsed into a single indistinguishable triplet.
 * <p>
 * Column naming: one triplet per <em>resolved column</em>. Whole-cell mean resolves
 * to the bare marker name, so default gates and ungated markers keep the historical
 * {@code CD3_raw} names; only non-default selections add {@code CD3_Nucleus_Mean_raw}.
 */
class CompartmentCsvTest {

    @TempDir
    Path tempDir;

    // ---- fixture ------------------------------------------------------------

    /**
     * Four cells. Whole-cell CD3 is constant (50) so it carries no signal at all and
     * has zero variance; nuclear and cytoplasmic CD3 both separate the cells, in
     * opposite directions. CD8 varies on the bare/whole-cell column.
     */
    private static CellIndex index() {
        double[] nuc = {10, 20, 200, 300};
        double[] cyt = {300, 200, 20, 10};
        double[] cd8 = {10, 20, 30, 40};
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("CD3", 50.0);
            m.put("CD3: Cell: Mean", 50.0);
            m.put("CD3: Nucleus: Mean", nuc[i]);
            m.put("CD3: Nucleus: Median", nuc[i] / 2.0);
            m.put("CD3: Cytoplasm: Mean", cyt[i]);
            m.put("CD8", cd8[i]);
            m.put("CD8: Cell: Mean", cd8[i]);
            m.put("CD8: Nucleus: Mean", cd8[i] * 10.0);
            cells.add(cell(m));
        }
        return CellIndex.build(cells, List.of("CD3", "CD8"));
    }

    private static PathObject cell(Map<String, Double> meas) {
        PathObject o = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        meas.forEach((k, v) -> o.getMeasurements().put(k, v));
        o.getMeasurements().put("area", 100.0);
        return o;
    }

    private static boolean[] allTrue(int n) {
        boolean[] m = new boolean[n];
        Arrays.fill(m, true);
        return m;
    }

    private static GateNode thresholdGate(String channel, Compartment c, Statistic s,
                                          boolean zScore, double threshold) {
        GateNode g = new GateNode(channel);
        g.setCompartment(c);
        g.setStatistic(s);
        g.setThresholdIsZScore(zScore);
        g.setThreshold(threshold);
        return g;
    }

    private record Csv(List<String> header, List<List<String>> rows, AssignmentResult result) {
        String val(int row, String col) {
            int idx = header.indexOf(col);
            assertTrue(idx >= 0, "column '" + col + "' missing; header = " + header);
            return rows.get(row).get(idx);
        }
        double num(int row, String col) {
            String v = val(row, col);
            assertFalse(v.isEmpty(), "column '" + col + "' unexpectedly blank on row " + row);
            return Double.parseDouble(v);
        }
    }

    private Csv run(CellIndex index, MarkerStats stats, GateNode... roots) throws IOException {
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        for (GateNode r : roots) tree.addRoot(r);
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File f = tempDir.resolve("out.csv").toFile();
        PhenotypeCsvExporter.export(f, index, result, tree, stats);
        List<String> lines = Files.readAllLines(f.toPath());
        List<String> hdr = new ArrayList<>(List.of(lines.get(0).split(",", -1)));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(new ArrayList<>(List.of(lines.get(i).split(",", -1))));
        }
        return new Csv(hdr, rows, result);
    }

    // ---- F1: the triplet reports the gated column ---------------------------

    @Test
    void nuclearRawGateExportsNuclearValuesAndConsistentSigns() throws IOException {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, allTrue(idx.size()));
        Csv csv = run(idx, stats, thresholdGate("CD3", Compartment.NUCLEAR, Statistic.MEAN, false, 100));

        assertEquals(10.0, csv.num(0, "CD3_Nucleus_Mean_raw"), 1e-6,
                "the _raw column must carry the nuclear value the gate compared, not whole-cell 50");
        assertEquals(200.0, csv.num(2, "CD3_Nucleus_Mean_raw"), 1e-6);

        // The whole point: sign must never contradict phenotype.
        assertEquals("-", csv.val(0, "CD3_Nucleus_Mean_sign"));
        assertEquals("-", csv.val(1, "CD3_Nucleus_Mean_sign"));
        assertEquals("+", csv.val(2, "CD3_Nucleus_Mean_sign"));
        assertEquals("+", csv.val(3, "CD3_Nucleus_Mean_sign"));
        for (int i = 0; i < 4; i++) {
            String pheno = csv.val(i, "phenotype");
            String sign = csv.val(i, "CD3_Nucleus_Mean_sign");
            assertEquals(pheno.endsWith("+") ? "+" : "-", sign,
                    "row " + i + ": phenotype '" + pheno + "' disagrees with sign '" + sign + "'");
        }
    }

    @Test
    void nuclearZScoreGateExportsZScoreOfTheNuclearColumn() throws IOException {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, allTrue(idx.size()));
        Csv csv = run(idx, stats, thresholdGate("CD3", Compartment.NUCLEAR, Statistic.MEAN, true, 0.0));

        String key = "CD3: Nucleus: Mean";
        assertTrue(stats.getStd(key) > 1e-10, "fixture must give the nuclear column real variance");
        // Bare CD3 has zero variance, so the old bare-key path produced a blank cell here.
        // CSV values are written with %.4f, so allow one rounding step.
        assertEquals(stats.toZScore(key, 10.0), csv.num(0, "CD3_Nucleus_Mean_zscore"), 1e-4);
        assertEquals(stats.toZScore(key, 300.0), csv.num(3, "CD3_Nucleus_Mean_zscore"), 1e-4);
    }

    @Test
    void statisticSelectionGetsItsOwnColumn() throws IOException {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, allTrue(idx.size()));
        Csv csv = run(idx, stats, thresholdGate("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, false, 50));

        assertEquals(5.0, csv.num(0, "CD3_Nucleus_Median_raw"), 1e-6);
        assertEquals(150.0, csv.num(3, "CD3_Nucleus_Median_raw"), 1e-6);
        assertEquals("+", csv.val(3, "CD3_Nucleus_Median_sign"));
    }

    // ---- F2: compartments of one marker no longer collapse -------------------

    @Test
    void twoCompartmentsOfOneMarkerGetSeparateTriplets() throws IOException {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, allTrue(idx.size()));

        GateNode nuc = thresholdGate("CD3", Compartment.NUCLEAR, Statistic.MEAN, false, 100);
        nuc.getBranches().get(0).setName("nucHigh");
        nuc.getBranches().get(1).setName("nucLow");
        GateNode cyt = thresholdGate("CD3", Compartment.CYTOPLASMIC, Statistic.MEAN, false, 100);
        cyt.getBranches().get(0).setName("cytHigh");
        cyt.getBranches().get(1).setName("cytLow");
        nuc.getBranches().get(1).getChildren().add(cyt);

        Csv csv = run(idx, stats, nuc);

        // Cell 0 is cytoplasm-high / nucleus-low; cell 3 is the reverse.
        assertEquals(10.0, csv.num(0, "CD3_Nucleus_Mean_raw"), 1e-6);
        assertEquals(300.0, csv.num(0, "CD3_Cytoplasm_Mean_raw"), 1e-6);
        assertEquals("-", csv.val(0, "CD3_Nucleus_Mean_sign"));
        assertEquals("+", csv.val(0, "CD3_Cytoplasm_Mean_sign"));

        assertEquals(300.0, csv.num(3, "CD3_Nucleus_Mean_raw"), 1e-6);
        assertEquals(10.0, csv.num(3, "CD3_Cytoplasm_Mean_raw"), 1e-6);
        assertEquals("+", csv.val(3, "CD3_Nucleus_Mean_sign"));
        assertEquals("-", csv.val(3, "CD3_Cytoplasm_Mean_sign"));
    }

    // ---- backward compatibility ---------------------------------------------

    @Test
    void wholeCellMeanGateKeepsTheHistoricalBareColumnNames() throws IOException {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, allTrue(idx.size()));
        Csv csv = run(idx, stats, thresholdGate("CD8", Compartment.WHOLE_CELL, Statistic.MEAN, false, 25));

        assertTrue(csv.header().contains("CD8_raw"), "whole-cell mean must not be renamed");
        assertFalse(csv.header().contains("CD8_Cell_Mean_raw"),
                "whole-cell mean resolves to the bare key, so no extra column should appear");
        assertEquals(30.0, csv.num(2, "CD8_raw"), 1e-6);
        assertEquals("+", csv.val(2, "CD8_sign"));
    }

    @Test
    void ungatedMarkersStillGetTheirBareColumns() throws IOException {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, allTrue(idx.size()));
        Csv csv = run(idx, stats, thresholdGate("CD3", Compartment.NUCLEAR, Statistic.MEAN, false, 100));

        assertTrue(csv.header().contains("CD3_raw"), "bare CD3 column must survive");
        assertTrue(csv.header().contains("CD8_raw"), "ungated CD8 must still be exported");
        assertEquals(50.0, csv.num(0, "CD3_raw"), 1e-6);
        assertEquals("", csv.val(0, "CD8_sign"), "an ungated marker has no sign");
    }

    // ---- F4: region gates carry compartment/statistic ------------------------

    @Test
    void rectangleGateHonoursPerAxisCompartment() throws IOException {
        CellIndex idx = index();
        MarkerStats stats = MarkerStats.compute(idx, allTrue(idx.size()));

        // X = nuclear CD3 in [150, 400], Y = whole-cell CD8 in [0, 100] -> cells 2 and 3.
        RectangleGate rg = new RectangleGate("CD3", "CD8", 150, 400, 0, 100);
        rg.setCompartmentX(Compartment.NUCLEAR);
        rg.setStatisticX(Statistic.MEAN);
        rg.setThresholdIsZScore(false);

        Csv csv = run(idx, stats, rg);

        assertTrue(csv.val(0, "phenotype").endsWith("(out)"),
                "cell 0 has nuclear CD3 = 10, well below the region: " + csv.val(0, "phenotype"));
        assertTrue(csv.val(2, "phenotype").endsWith("(in)"),
                "cell 2 has nuclear CD3 = 200, inside the region: " + csv.val(2, "phenotype"));
        assertEquals(10.0, csv.num(0, "CD3_Nucleus_Mean_raw"), 1e-6);
        assertEquals("-", csv.val(0, "CD3_Nucleus_Mean_sign"));
        assertEquals("+", csv.val(2, "CD3_Nucleus_Mean_sign"));
        assertEquals("+", csv.val(2, "CD8_sign"), "region gates mark both axis columns");
    }

    @Test
    void polygonAndEllipseGatesExposeCompartmentAccessors() {
        PolygonGate pg = new PolygonGate("CD3", "CD8");
        pg.setCompartmentX(Compartment.NUCLEAR);
        pg.setStatisticY(Statistic.SUM);
        assertEquals(List.of(Compartment.NUCLEAR, Compartment.WHOLE_CELL), pg.getCompartments());
        assertEquals(List.of(Statistic.MEAN, Statistic.SUM), pg.getStatistics());

        EllipseGate eg = new EllipseGate("CD3", "CD8", 0, 0, 1, 1);
        eg.setCompartmentY(Compartment.CYTOPLASMIC);
        assertEquals(List.of(Compartment.WHOLE_CELL, Compartment.CYTOPLASMIC), eg.getCompartments());
    }

    @Test
    void regionGateCompartmentsSurviveDeepCopy() {
        RectangleGate rg = new RectangleGate("CD3", "CD8", 1, 2, 3, 4);
        rg.setCompartmentX(Compartment.NUCLEAR);
        rg.setStatisticX(Statistic.MEDIAN);
        rg.setCompartmentY(Compartment.CYTOPLASMIC);
        rg.setStatisticY(Statistic.SUM);

        RectangleGate copy = (RectangleGate) rg.deepCopy();

        assertEquals(Compartment.NUCLEAR, copy.getCompartmentX());
        assertEquals(Statistic.MEDIAN, copy.getStatisticX());
        assertEquals(Compartment.CYTOPLASMIC, copy.getCompartmentY());
        assertEquals(Statistic.SUM, copy.getStatisticY());
    }

    @Test
    void regionGateCompartmentsRoundTripThroughJson() throws IOException {
        GateTree tree = new GateTree();
        PolygonGate pg = new PolygonGate("CD3", "CD8");
        pg.setVertices(List.of(new double[]{0, 0}, new double[]{1, 0}, new double[]{1, 1}));
        pg.setCompartmentX(Compartment.NUCLEAR);
        pg.setStatisticX(Statistic.SUM);
        pg.setCompartmentY(Compartment.CYTOPLASMIC);
        pg.setStatisticY(Statistic.MEDIAN);
        tree.addRoot(pg);

        File f = tempDir.resolve("tree.json").toFile();
        FlowPathSerializer.save(tree, f);
        GateTree loaded = FlowPathSerializer.load(f);

        PolygonGate out = (PolygonGate) loaded.getRoots().get(0);
        assertEquals(Compartment.NUCLEAR, out.getCompartmentX());
        assertEquals(Statistic.SUM, out.getStatisticX());
        assertEquals(Compartment.CYTOPLASMIC, out.getCompartmentY());
        assertEquals(Statistic.MEDIAN, out.getStatisticY());
    }

    @Test
    void legacyRegionGateJsonDefaultsToWholeCellMean() throws IOException {
        // A v1/v2 file has no compartment keys on a region gate.
        String json = """
            {"version": 2, "roiFilterEnabled": false, "gates": [
              {"type": "rectangle", "channelX": "CD3", "channelY": "CD8",
               "minX": 0, "maxX": 1, "minY": 0, "maxY": 1,
               "branches": [{"name": "In"}, {"name": "Out"}]}
            ]}""";
        File f = tempDir.resolve("legacy.json").toFile();
        Files.writeString(f.toPath(), json);

        RectangleGate rg = (RectangleGate) FlowPathSerializer.load(f).getRoots().get(0);
        assertEquals(Compartment.WHOLE_CELL, rg.getCompartmentX());
        assertEquals(Compartment.WHOLE_CELL, rg.getCompartmentY());
        assertEquals(Statistic.MEAN, rg.getStatisticX());
        assertEquals(Statistic.MEAN, rg.getStatisticY());
    }
}
