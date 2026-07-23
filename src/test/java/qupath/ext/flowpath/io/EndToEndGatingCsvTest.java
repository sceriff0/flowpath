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
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that verify the full pipeline:
 * synthetic cells → GatingEngine → PhenotypeCsvExporter → CSV with known outputs.
 *
 * Each test uses hand-crafted data where the expected phenotype for every cell
 * can be computed by inspection. This catches integration bugs between the engine
 * and the CSV exporter (wrong phenotype, missing signs, wrong exclusions, etc.).
 */
class EndToEndGatingCsvTest {

    @TempDir
    Path tempDir;

    // ========== Helpers ==========

    private static CellIndex buildIndex(List<String> markers, double[][] markerValues, double[] areas) {
        int nCells = markerValues[0].length;
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < nCells; i++) {
            ROI roi = ROIs.createPointsROI(i * 10.0, i * 10.0, ImagePlane.getDefaultPlane());
            PathObject obj = PathObjects.createDetectionObject(roi);
            for (int m = 0; m < markers.size(); m++) {
                obj.getMeasurements().put(markers.get(m), markerValues[m][i]);
            }
            obj.getMeasurements().put("area", areas != null ? areas[i] : 100.0);
            cells.add(obj);
        }
        return CellIndex.build(cells, markers);
    }

    private static boolean[] allTrueMask(int n) {
        boolean[] mask = new boolean[n];
        Arrays.fill(mask, true);
        return mask;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /** Run the full pipeline: engine + CSV export, return parsed rows (excluding header). */
    private CsvResult runPipeline(GateTree tree, CellIndex index, MarkerStats stats,
                                  String filename) throws IOException {
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File csvFile = tempDir.resolve(filename).toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        List<String> header = parseCsvLine(lines.get(0));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(parseCsvLine(lines.get(i)));
        }
        return new CsvResult(header, rows, result);
    }

    record CsvResult(List<String> header, List<List<String>> rows, AssignmentResult result) {
        String cellValue(int rowIdx, String column) {
            int col = header.indexOf(column);
            assertTrue(col >= 0, "Column '" + column + "' not found in header: " + header);
            return rows.get(rowIdx).get(col);
        }
    }

    // ========== Test 1: Threshold gate with known split ==========

    @Test
    void thresholdGateSplitsCorrectly() throws IOException {
        // 6 cells, CD45 values: [2, 4, 6, 8, 10, 12], threshold=7 (raw)
        // Expected: cells 0-2 (values 2,4,6) -> CD45-; cells 3-5 (values 8,10,12) -> CD45+
        List<String> markers = List.of("CD45");
        double[][] values = { {2, 4, 6, 8, 10, 12} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(6));

        GateNode gate = new GateNode("CD45", 7.0);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        CsvResult csv = runPipeline(tree, index, stats, "threshold.csv");
        assertEquals(6, csv.rows.size());

        assertEquals("CD45-", csv.cellValue(0, "phenotype"));
        assertEquals("CD45-", csv.cellValue(1, "phenotype"));
        assertEquals("CD45-", csv.cellValue(2, "phenotype"));
        assertEquals("CD45+", csv.cellValue(3, "phenotype"));
        assertEquals("CD45+", csv.cellValue(4, "phenotype"));
        assertEquals("CD45+", csv.cellValue(5, "phenotype"));

        // Check signs
        assertEquals("-", csv.cellValue(0, "CD45_sign"));
        assertEquals("+", csv.cellValue(3, "CD45_sign"));
    }

    // ========== Test 2: Nested threshold gates (2-level hierarchy) ==========

    @Test
    void nestedThresholdGatesProduceCorrectPhenotypes() throws IOException {
        // Root: CD45 threshold=5 (raw)
        // Child on CD45+ branch: CD3 threshold=3 (raw)
        //
        // Cell 0: CD45=1, CD3=1 -> CD45- (phenotype=CD45-, CD45_sign=-, CD3_sign=- because CD3 raw=1 < threshold 3)
        // Cell 1: CD45=6, CD3=2 -> CD45+ then CD3- (phenotype=CD3-, CD45_sign=+, CD3_sign=-)
        // Cell 2: CD45=7, CD3=4 -> CD45+ then CD3+ (phenotype=CD3+, CD45_sign=+, CD3_sign=+)
        // Cell 3: CD45=8, CD3=5 -> CD45+ then CD3+ (phenotype=CD3+, CD45_sign=+, CD3_sign=+)
        // Note: _sign is now an independent positivity call per marker (any threshold passed = "+"),
        // not "what role did this marker play in the leaf path". Cell 0's CD3 is below the tree's
        // CD3 threshold, so CD3_sign="-" even though the cell's leaf phenotype didn't traverse the CD3 gate.
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = { {1, 6, 7, 8}, {1, 2, 4, 5} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(4));

        GateNode root = new GateNode("CD45", 5.0);
        root.setThresholdIsZScore(false);
        GateNode child = new GateNode("CD3", 3.0);
        child.setThresholdIsZScore(false);
        root.getPositiveChildren().add(child);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        CsvResult csv = runPipeline(tree, index, stats, "nested.csv");
        assertEquals(4, csv.rows.size());

        assertEquals("CD45-", csv.cellValue(0, "phenotype"));
        assertEquals("-", csv.cellValue(0, "CD45_sign"));
        assertEquals("-", csv.cellValue(0, "CD3_sign")); // CD3 raw=1 < tree threshold 3

        assertEquals("CD3-", csv.cellValue(1, "phenotype"));
        assertEquals("+", csv.cellValue(1, "CD45_sign"));
        assertEquals("-", csv.cellValue(1, "CD3_sign"));

        assertEquals("CD3+", csv.cellValue(2, "phenotype"));
        assertEquals("+", csv.cellValue(2, "CD45_sign"));
        assertEquals("+", csv.cellValue(2, "CD3_sign"));

        assertEquals("CD3+", csv.cellValue(3, "phenotype"));
    }

    // ========== Test 3: Quadrant gate ==========

    @Test
    void quadrantGateAssignsFourQuadrants() throws IOException {
        // 4 cells at the corners of a quadrant gate with thresholds X=5, Y=5
        // Cell 0: CD45=8, CD3=8 -> Q1 (++) -> phenotype default is "Q1 (++)"
        // Cell 1: CD45=2, CD3=8 -> Q2 (-+)
        // Cell 2: CD45=8, CD3=2 -> Q3 (+-)
        // Cell 3: CD45=2, CD3=2 -> Q4 (--)
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = { {8, 2, 8, 2}, {8, 8, 2, 2} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(4));

        QuadrantGate gate = new QuadrantGate("CD45", "CD3");
        gate.setThresholdX(5.0);
        gate.setThresholdY(5.0);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        CsvResult csv = runPipeline(tree, index, stats, "quadrant.csv");
        assertEquals(4, csv.rows.size());

        // Verify each cell lands in the correct quadrant
        String p0 = csv.cellValue(0, "phenotype");
        String p1 = csv.cellValue(1, "phenotype");
        String p2 = csv.cellValue(2, "phenotype");
        String p3 = csv.cellValue(3, "phenotype");

        // QuadrantGate branch names: "CD45+/CD3+", "CD45-/CD3+", "CD45+/CD3-", "CD45-/CD3-"
        assertEquals("CD45+/CD3+", p0, "Cell 0 (8,8) should be in ++ quadrant");
        assertEquals("CD45-/CD3+", p1, "Cell 1 (2,8) should be in -+ quadrant");
        assertEquals("CD45+/CD3-", p2, "Cell 2 (8,2) should be in +- quadrant");
        assertEquals("CD45-/CD3-", p3, "Cell 3 (2,2) should be in -- quadrant");

        // Verify marker signs in CSV
        assertEquals("+", csv.cellValue(0, "CD45_sign"));
        assertEquals("+", csv.cellValue(0, "CD3_sign"));
        assertEquals("-", csv.cellValue(1, "CD45_sign"));
        assertEquals("+", csv.cellValue(1, "CD3_sign"));
    }

    // ========== Test 4: Polygon gate ==========

    @Test
    void polygonGateClassifiesInsideOutside() throws IOException {
        // Triangle polygon with vertices: (0,0), (10,0), (5,10)
        // Cell 0: CD45=5, CD3=3 -> inside triangle
        // Cell 1: CD45=20, CD3=20 -> outside triangle
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = { {5, 20}, {3, 20} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(2));

        PolygonGate gate = new PolygonGate("CD45", "CD3");
        gate.setThresholdIsZScore(false);  // region is in raw marker units
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{10, 0}, new double[]{5, 10}));

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        CsvResult csv = runPipeline(tree, index, stats, "polygon.csv");
        assertEquals(2, csv.rows.size());

        // Pin *which* branch each cell lands in. Asserting only that the two differ
        // would pass just as happily with the containment test inverted.
        String insideName = gate.getBranches().get(0).getName();
        String outsideName = gate.getBranches().get(1).getName();
        assertEquals(insideName, csv.cellValue(0, "phenotype"),
                "cell 0 (5,3) is inside the triangle");
        assertEquals(outsideName, csv.cellValue(1, "phenotype"),
                "cell 1 (20,20) is outside the triangle");

        // Both axis columns of a region gate are marked for a contained cell.
        assertEquals("+", csv.cellValue(0, "CD45_sign"));
        assertEquals("+", csv.cellValue(0, "CD3_sign"));
        assertEquals("-", csv.cellValue(1, "CD45_sign"));
        assertEquals("-", csv.cellValue(1, "CD3_sign"));
    }

    // ========== Test 5: Rectangle gate ==========

    @Test
    void rectangleGateClassifiesInsideOutside() throws IOException {
        // Rectangle from (2,2) to (8,8)
        // Cell 0: (5,5) -> inside; Cell 1: (1,1) -> outside; Cell 2: (10,10) -> outside
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = { {5, 1, 10}, {5, 1, 10} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(3));

        RectangleGate gate = new RectangleGate("CD45", "CD3", 2, 8, 2, 8);
        gate.setThresholdIsZScore(false);  // region is in raw marker units

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        CsvResult csv = runPipeline(tree, index, stats, "rectangle.csv");
        assertEquals(3, csv.rows.size());

        String insideName = gate.getBranches().get(0).getName();
        String outsideName = gate.getBranches().get(1).getName();
        assertEquals(insideName, csv.cellValue(0, "phenotype"), "(5,5) is inside [2,8]x[2,8]");
        assertEquals(outsideName, csv.cellValue(1, "phenotype"), "(1,1) is below the region");
        assertEquals(outsideName, csv.cellValue(2, "phenotype"), "(10,10) is above the region");
    }

    // ========== Test 6: Ellipse gate ==========

    @Test
    void ellipseGateClassifiesInsideOutside() throws IOException {
        // Ellipse centered at (5,5) with radii (3,3) — essentially a circle
        // Cell 0: (5,5) -> inside (distance=0); Cell 1: (5,7) -> inside (distance=2);
        // Cell 2: (5,9) -> outside (distance=4 > radius 3)
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = { {5, 5, 5}, {5, 7, 9} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(3));

        EllipseGate gate = new EllipseGate("CD45", "CD3", 5, 5, 3, 3);
        gate.setThresholdIsZScore(false);  // region is in raw marker units

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        CsvResult csv = runPipeline(tree, index, stats, "ellipse.csv");
        assertEquals(3, csv.rows.size());

        String insideName = gate.getBranches().get(0).getName();
        String outsideName = gate.getBranches().get(1).getName();
        assertEquals(insideName, csv.cellValue(0, "phenotype"), "(5,5) is the centre");
        assertEquals(insideName, csv.cellValue(1, "phenotype"), "(5,7) is 2 < radius 3");
        assertEquals(outsideName, csv.cellValue(2, "phenotype"), "(5,9) is 4 > radius 3");
    }

    // ========== Test 7: Disabled gate is skipped ==========

    @Test
    void disabledGateIsSkippedLeavingCellsUnclassified() throws IOException {
        // Same setup as threshold test, but gate is disabled
        List<String> markers = List.of("CD45");
        double[][] values = { {2, 8} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(2));

        GateNode gate = new GateNode("CD45", 5.0);
        gate.setThresholdIsZScore(false);
        gate.setEnabled(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        CsvResult csv = runPipeline(tree, index, stats, "disabled.csv");
        assertEquals(2, csv.rows.size());

        // Both cells should be Unclassified since the only gate is disabled
        assertEquals("Unclassified", csv.cellValue(0, "phenotype"));
        assertEquals("Unclassified", csv.cellValue(1, "phenotype"));
    }

    // ========== Test 8: Mixed enabled/disabled gates ==========

    @Test
    void mixedEnabledDisabledGates() throws IOException {
        // Root 1 (CD45, enabled): threshold=5 -> splits cells
        // Root 2 (CD3, disabled): threshold=5 -> should be skipped
        // Cell 0: CD45=2, CD3=8 -> CD45- (CD3 gate skipped)
        // Cell 1: CD45=8, CD3=2 -> CD45+ (CD3 gate skipped)
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = { {2, 8}, {8, 2} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(2));

        GateNode gate1 = new GateNode("CD45", 5.0);
        gate1.setThresholdIsZScore(false);
        gate1.setEnabled(true);

        GateNode gate2 = new GateNode("CD3", 5.0);
        gate2.setThresholdIsZScore(false);
        gate2.setEnabled(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate1);
        tree.addRoot(gate2);

        CsvResult csv = runPipeline(tree, index, stats, "mixed.csv");
        assertEquals(2, csv.rows.size());

        // CD45 gate runs, CD3 gate is skipped
        assertEquals("CD45-", csv.cellValue(0, "phenotype"));
        assertEquals("CD45+", csv.cellValue(1, "phenotype"));
    }

    // ========== Test 9: Quality filter + threshold gate combined ==========

    @Test
    void qualityFilterExcludesCellsBeforeGating() throws IOException {
        // 4 cells with areas [10, 50, 100, 200], QF minArea=40
        // Cell 0 (area=10) excluded; Cells 1-3 pass QF
        // Gate: CD45 threshold=5 (raw), values [3, 3, 7, 7]
        // Cell 1: CD45- (3 < 5), Cell 2: CD45+ (7 >= 5), Cell 3: CD45+ (7 >= 5)
        List<String> markers = List.of("CD45");
        double[][] values = { {3, 3, 7, 7} };
        double[] areas = {10, 50, 100, 200};
        CellIndex index = buildIndex(markers, values, areas);

        QualityFilter qf = new QualityFilter();
        qf.setMinArea(40);

        boolean[] mask = GatingEngine.computeQualityMask(index, qf);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("CD45", 5.0);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(qf);
        tree.addRoot(gate);

        CsvResult csv = runPipeline(tree, index, stats, "qf_threshold.csv");

        // All 4 cells appear in CSV — cell 0 is QF-excluded but still receives a
        // would-have-been phenotype (CD45-, value 3 < 5) and is flagged via Outlier=True.
        assertEquals(4, csv.rows.size());
        assertEquals("CD45-", csv.cellValue(0, "phenotype"));
        assertEquals("True", csv.cellValue(0, "Outlier"));
        assertEquals("False", csv.cellValue(0, "Out_of_annotation"));
        assertEquals("CD45-", csv.cellValue(1, "phenotype"));
        assertEquals("False", csv.cellValue(1, "Outlier"));
        assertEquals("CD45+", csv.cellValue(2, "phenotype"));
        assertEquals("CD45+", csv.cellValue(3, "phenotype"));
    }

    // ========== Test 10: 3-level hierarchy (CD45 > CD3 > CD8) ==========

    @Test
    void threeLevelHierarchyProducesCorrectLeafPhenotypes() throws IOException {
        // CD45 threshold=5, CD3 threshold=3 (on CD45+ branch), CD8 threshold=4 (on CD3+ branch)
        //
        // Cell 0: CD45=2 -> CD45-
        // Cell 1: CD45=6, CD3=2 -> CD3-
        // Cell 2: CD45=7, CD3=4, CD8=3 -> CD8-
        // Cell 3: CD45=8, CD3=5, CD8=6 -> CD8+
        List<String> markers = List.of("CD45", "CD3", "CD8");
        double[][] values = { {2, 6, 7, 8}, {1, 2, 4, 5}, {1, 1, 3, 6} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(4));

        GateNode root = new GateNode("CD45", 5.0);
        root.setThresholdIsZScore(false);
        GateNode level2 = new GateNode("CD3", 3.0);
        level2.setThresholdIsZScore(false);
        GateNode level3 = new GateNode("CD8", 4.0);
        level3.setThresholdIsZScore(false);

        root.getPositiveChildren().add(level2);
        level2.getPositiveChildren().add(level3);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        CsvResult csv = runPipeline(tree, index, stats, "three_level.csv");
        assertEquals(4, csv.rows.size());

        // _sign is now an independent positivity call: every marker that has a
        // threshold somewhere in the tree is evaluated against the cell's raw value.
        assertEquals("CD45-", csv.cellValue(0, "phenotype"));
        assertEquals("-", csv.cellValue(0, "CD45_sign"));
        assertEquals("-", csv.cellValue(0, "CD3_sign")); // raw 1 < threshold 3
        assertEquals("-", csv.cellValue(0, "CD8_sign")); // raw 1 < threshold 4

        assertEquals("CD3-", csv.cellValue(1, "phenotype"));
        assertEquals("+", csv.cellValue(1, "CD45_sign"));
        assertEquals("-", csv.cellValue(1, "CD3_sign"));
        assertEquals("-", csv.cellValue(1, "CD8_sign")); // raw 1 < threshold 4

        assertEquals("CD8-", csv.cellValue(2, "phenotype"));
        assertEquals("+", csv.cellValue(2, "CD45_sign"));
        assertEquals("+", csv.cellValue(2, "CD3_sign"));
        assertEquals("-", csv.cellValue(2, "CD8_sign"));

        assertEquals("CD8+", csv.cellValue(3, "phenotype"));
        assertEquals("+", csv.cellValue(3, "CD45_sign"));
        assertEquals("+", csv.cellValue(3, "CD3_sign"));
        assertEquals("+", csv.cellValue(3, "CD8_sign"));
    }

    // ========== Test 11: Quadrant gate with nested child ==========

    @Test
    void quadrantGateWithChildOnOneBranch() throws IOException {
        // Quadrant gate: CD45 vs CD3, thresholds X=5, Y=5
        // Child on Q1 (++) branch: CD8 threshold=4
        //
        // Cell 0: CD45=8, CD3=8, CD8=6 -> Q1(++) then CD8+ (phenotype=CD8+)
        // Cell 1: CD45=8, CD3=8, CD8=2 -> Q1(++) then CD8- (phenotype=CD8-)
        // Cell 2: CD45=2, CD3=8, CD8=9 -> Q2(-+) (no child) -> stays Q2(-+)
        List<String> markers = List.of("CD45", "CD3", "CD8");
        double[][] values = { {8, 8, 2}, {8, 8, 8}, {6, 2, 9} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(3));

        QuadrantGate qgate = new QuadrantGate("CD45", "CD3");
        qgate.setThresholdX(5.0);
        qgate.setThresholdY(5.0);
        qgate.setThresholdIsZScore(false);

        GateNode cd8child = new GateNode("CD8", 4.0);
        cd8child.setThresholdIsZScore(false);
        // Add CD8 child to the first branch (Q1 = ++)
        qgate.getBranches().get(0).getChildren().add(cd8child);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(qgate);

        CsvResult csv = runPipeline(tree, index, stats, "quadrant_nested.csv");
        assertEquals(3, csv.rows.size());

        // Cell 0: Q1 -> CD8+ (value=6 >= 4)
        assertEquals("CD8+", csv.cellValue(0, "phenotype"));
        assertEquals("+", csv.cellValue(0, "CD45_sign"));
        assertEquals("+", csv.cellValue(0, "CD3_sign"));
        assertEquals("+", csv.cellValue(0, "CD8_sign"));

        // Cell 1: Q1 -> CD8- (value=2 < 4)
        assertEquals("CD8-", csv.cellValue(1, "phenotype"));
        assertEquals("-", csv.cellValue(1, "CD8_sign"));

        // Cell 2: Q2 (CD45-/CD3+), no children -> stays at Q2 phenotype
        assertEquals("CD45-/CD3+", csv.cellValue(2, "phenotype"));
    }

    // ========== Test 12: Full complex tree ==========

    @Test
    void complexTreeWithAllGateTypes() throws IOException {
        // Complex tree:
        // Root: CD45 threshold=5 (raw)
        //   CD45+ branch:
        //     Child: CD3 threshold=3 (raw)
        //       CD3+ branch:
        //         Grandchild: Rectangle gate on CD8 vs CD4, bounds (4,4)-(10,10)
        //
        // 5 cells:
        // Cell 0: CD45=2 -> CD45-
        // Cell 1: CD45=6, CD3=2 -> CD3-
        // Cell 2: CD45=7, CD3=4, CD8=5, CD4=5 -> CD3+ then Rectangle inside (5,5 in [4,10]x[4,10])
        // Cell 3: CD45=8, CD3=5, CD8=1, CD4=1 -> CD3+ then Rectangle outside (1,1 not in [4,10]x[4,10])
        // Cell 4: CD45=9, CD3=6, CD8=7, CD4=7 -> CD3+ then Rectangle inside
        List<String> markers = List.of("CD45", "CD3", "CD8", "CD4");
        double[][] values = {
            {2, 6, 7, 8, 9},   // CD45
            {1, 2, 4, 5, 6},   // CD3
            {1, 1, 5, 1, 7},   // CD8
            {1, 1, 5, 1, 7}    // CD4
        };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(5));

        GateNode root = new GateNode("CD45", 5.0);
        root.setThresholdIsZScore(false);

        GateNode cd3Gate = new GateNode("CD3", 3.0);
        cd3Gate.setThresholdIsZScore(false);
        root.getPositiveChildren().add(cd3Gate);

        RectangleGate rectGate = new RectangleGate("CD8", "CD4", 4, 10, 4, 10);
        rectGate.setThresholdIsZScore(false);  // region is in raw marker units
        cd3Gate.getPositiveChildren().add(rectGate);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        CsvResult csv = runPipeline(tree, index, stats, "complex.csv");
        assertEquals(5, csv.rows.size());

        assertEquals("CD45-", csv.cellValue(0, "phenotype"));
        assertEquals("CD3-", csv.cellValue(1, "phenotype"));

        // Cells 2 and 4 should be inside the rectangle
        String insideName = rectGate.getBranches().get(0).getName();
        String outsideName = rectGate.getBranches().get(1).getName();

        assertEquals(insideName, csv.cellValue(2, "phenotype"));
        assertEquals(outsideName, csv.cellValue(3, "phenotype"));
        assertEquals(insideName, csv.cellValue(4, "phenotype"));
    }

    // ========== Test: Two enabled roots produce composite phenotype in CSV ==========

    @Test
    void twoRootsCompositePhenotypeInCsv() throws IOException {
        // Root1: CD45 threshold=5, Root2: PANCK threshold=5
        // Cell 0: CD45=8, PANCK=8  -> "CD45+: PANCK+"
        // Cell 1: CD45=8, PANCK=2  -> "CD45+: PANCK-"
        // Cell 2: CD45=2, PANCK=8  -> "CD45-: PANCK+"
        List<String> markers = List.of("CD45", "PANCK");
        double[][] values = { {8, 8, 2}, {8, 2, 8} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(3));

        GateNode root1 = new GateNode("CD45", 5.0);
        root1.setThresholdIsZScore(false);
        GateNode root2 = new GateNode("PANCK", 5.0);
        root2.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root1);
        tree.addRoot(root2);

        CsvResult csv = runPipeline(tree, index, stats, "composite.csv");
        assertEquals(3, csv.rows.size());

        // Verify composite phenotype format
        assertEquals("CD45+: PANCK+", csv.cellValue(0, "phenotype"));
        assertEquals("CD45+: PANCK-", csv.cellValue(1, "phenotype"));
        assertEquals("CD45-: PANCK+", csv.cellValue(2, "phenotype"));

        // Verify sign columns from BOTH roots are populated
        assertEquals("+", csv.cellValue(0, "CD45_sign"));
        assertEquals("+", csv.cellValue(0, "PANCK_sign"));
        assertEquals("+", csv.cellValue(1, "CD45_sign"));
        assertEquals("-", csv.cellValue(1, "PANCK_sign"));
        assertEquals("-", csv.cellValue(2, "CD45_sign"));
        assertEquals("+", csv.cellValue(2, "PANCK_sign"));
    }
}
