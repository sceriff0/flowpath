package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhenotypeCsvExporterTest {

    @TempDir
    Path tempDir;

    // ---- helpers ----


    /**
     * Parse a CSV line respecting quoted fields (handles commas inside quotes).
     */
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
                        i++; // skip escaped quote
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

    // ---- tests ----

    @Test
    void basicExportHasCorrectHeaderAndRowCount() throws IOException {
        List<String> markers = List.of("CD45");
        double[][] values = { {2, 4, 6, 8} };
        CellIndex index = Cells.columns(markers, values).build();
        boolean[] mask = Cells.allTrue(4);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("CD45", 5.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        File csvFile = tempDir.resolve("basic.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        assertFalse(lines.isEmpty(), "CSV should not be empty");

        // Check header
        String header = lines.get(0);
        assertTrue(header.contains("cell_id"), "Header should contain cell_id");
        assertTrue(header.contains("phenotype"), "Header should contain phenotype");
        assertTrue(header.contains("Out_of_annotation"), "Header should contain Out_of_annotation");
        assertTrue(header.contains("Outlier"), "Header should contain Outlier");
        assertTrue(header.contains("centroid_x"), "Header should contain centroid_x");
        assertTrue(header.contains("centroid_y"), "Header should contain centroid_y");
        // Morphology is whatever this export carries. These cells are marker values only,
        // so there is no area or perimeter column -- an all-blank one would assert the
        // measurement exists and is missing per cell, which is a different claim.
        assertFalse(header.contains("perimeter"),
                "no perimeter in this fixture, so no perimeter column: " + header);
        assertFalse(header.contains("solidity"), header);
        assertTrue(header.contains("CD45_raw"), "Header should contain CD45_raw");
        assertTrue(header.contains("CD45_zscore"), "Header should contain CD45_zscore");
        assertTrue(header.contains("CD45_sign"), "Header should contain CD45_sign");

        // Every cell is a row — now includes excluded cells flagged via the two columns
        assertEquals(result.getExcluded().length, lines.size() - 1,
                "CSV should contain one data row per cell, including excluded ones");
    }

    /**
     * The other half of the same rule: morphology the export <em>does</em> carry is
     * emitted, including the fields FlowPath has no name for. A MIRAGE run writes
     * {@code Major Axis Length µm} and {@code Minor Axis Length µm}; they used to be read
     * into the index and then dropped on the way out, because the CSV header was the fixed
     * four FlowPath knew about.
     */
    @Test
    void morphologyColumnsAreWhateverTheExportCarries() throws IOException {
        CellIndex index = Cells.of(6)
                .mirageMedianMarker("CD45", i -> 10.0 + i)
                .mirageMorphology(i -> 50.0 + i * 10)
                .morphology("Major Axis Length µm", i -> 8.0 + i * 0.5)
                .build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(index.size()));
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        File csvFile = tempDir.resolve("morphology.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);
        String header = Files.readAllLines(csvFile.toPath()).get(0);

        assertTrue(header.contains("area"), header);
        assertTrue(header.contains("eccentricity"), header);
        assertTrue(header.contains("major_axis_length"),
                "a column in the file must reach the CSV: " + header);
        assertFalse(header.contains("convex_area"),
                "convex area backs the solidity derivation, not a column of its own: " + header);
    }

    @Test
    void excludedCellsAppearInCsvWithOutlierFlag() throws IOException {
        // 6 cells with areas 10,20,30,60,70,80. QF minArea=50 excludes first 3.
        // All 6 cells should appear in the CSV — the excluded ones with Outlier=True.
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5, 6} };
        double[] areas = {10, 20, 30, 60, 70, 80};
        CellIndex index = Cells.columns(markers, values).area(areas).build();

        QualityFilter qf = new QualityFilter();
        qf.setMinArea(50);

        GateNode gate = new GateNode("CD45", 3.5);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(qf);
        tree.addRoot(gate);

        boolean[] mask = GatingEngine.computeQualityMask(index, qf);
        MarkerStats stats = MarkerStats.compute(index, mask);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        File csvFile = tempDir.resolve("excluded.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        assertEquals(6, lines.size() - 1, "CSV should contain all 6 cells (header + 6 rows)");

        List<String> headerFields = parseCsvLine(lines.get(0));
        int outCol = headerFields.indexOf("Out_of_annotation");
        int outlierCol = headerFields.indexOf("Outlier");
        int phenoCol = headerFields.indexOf("phenotype");
        assertTrue(outCol >= 0 && outlierCol >= 0, "Out_of_annotation and Outlier columns should exist");

        // Cells 0-2: QF-excluded (Outlier=True), still get phenotype (CD45- since values 1,2,3 < 3.5)
        for (int i = 0; i < 3; i++) {
            List<String> row = parseCsvLine(lines.get(i + 1));
            assertEquals("False", row.get(outCol), "Cell " + i + " not spatially out of annotation");
            assertEquals("True", row.get(outlierCol), "Cell " + i + " is QF-filtered -> Outlier=True");
            assertEquals("CD45-", row.get(phenoCol), "Cell " + i + " still receives a would-have-been phenotype");
        }
        // Cells 3-5: pass QF (Outlier=False), phenotype CD45+ (values 4,5,6 >= 3.5)
        for (int i = 3; i < 6; i++) {
            List<String> row = parseCsvLine(lines.get(i + 1));
            assertEquals("False", row.get(outCol));
            assertEquals("False", row.get(outlierCol));
            assertEquals("CD45+", row.get(phenoCol));
        }
    }

    @Test
    void markerSignsAreCorrect() throws IOException {
        // CD45 values [1,3,7,9], threshold=5 (raw). Cells 0,1 negative, cells 2,3 positive.
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 3, 7, 9} };
        CellIndex index = Cells.columns(markers, values).build();
        boolean[] mask = Cells.allTrue(4);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("CD45", 5.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        File csvFile = tempDir.resolve("signs.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        assertEquals(5, lines.size(), "Header + 4 data rows");

        // Find the CD45_sign column index
        List<String> headerFields = parseCsvLine(lines.get(0));
        int signCol = headerFields.indexOf("CD45_sign");
        assertTrue(signCol >= 0, "CD45_sign column should exist");

        // Cells 0,1 should be "-", cells 2,3 should be "+"
        String[] expectedSigns = {"-", "-", "+", "+"};
        for (int i = 0; i < 4; i++) {
            List<String> row = parseCsvLine(lines.get(i + 1));
            assertEquals(expectedSigns[i], row.get(signCol),
                    "Cell " + i + " CD45_sign should be " + expectedSigns[i]);
        }
    }

    @Test
    void nestedGateMarkerSigns() throws IOException {
        // Root: CD45 threshold=5 (raw), child: CD3 threshold=3 (raw) on positive branch
        // CD45=[1,6,7,8], CD3=[1,2,4,5]
        // cell0: CD45- (val=1 < 5)
        // cell1: CD45+ (val=6), CD3- (val=2 < 3)
        // cell2: CD45+ (val=7), CD3+ (val=4 >= 3)
        // cell3: CD45+ (val=8), CD3+ (val=5 >= 3)
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = {
                {1, 6, 7, 8},  // CD45
                {1, 2, 4, 5}   // CD3
        };
        CellIndex index = Cells.columns(markers, values).build();
        boolean[] mask = Cells.allTrue(4);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode root = new GateNode("CD45", 5.0);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        GateNode child = new GateNode("CD3", 3.0);
        child.setStatistic(Statistic.MEAN);
        child.setThresholdIsZScore(false);
        root.getPositiveChildren().add(child);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        File csvFile = tempDir.resolve("nested.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        assertEquals(5, lines.size(), "Header + 4 data rows");

        List<String> headerFields = parseCsvLine(lines.get(0));
        int cd45SignCol = headerFields.indexOf("CD45_sign");
        int cd3SignCol = headerFields.indexOf("CD3_sign");
        assertTrue(cd45SignCol >= 0, "CD45_sign column should exist");
        assertTrue(cd3SignCol >= 0, "CD3_sign column should exist");

        // cell0: CD45-, phenotype="CD45-" -> CD45 sign="-".
        // CD3 has a threshold (3.0) somewhere in the tree, and cell0's CD3 raw=1 < 3,
        // so CD3 sign="-" — independent of whether the cell's leaf path traversed CD3.
        List<String> row0 = parseCsvLine(lines.get(1));
        assertEquals("-", row0.get(cd45SignCol), "Cell 0 CD45_sign should be -");
        assertEquals("-", row0.get(cd3SignCol), "Cell 0 CD3_sign should be - (raw=1 < threshold 3)");

        // cell1: CD3-, phenotype="CD3-" -> CD45 sign="+", CD3 sign="-"
        List<String> row1 = parseCsvLine(lines.get(2));
        assertEquals("+", row1.get(cd45SignCol), "Cell 1 CD45_sign should be +");
        assertEquals("-", row1.get(cd3SignCol), "Cell 1 CD3_sign should be -");

        // cell2: CD3+, phenotype="CD3+" -> CD45 sign="+", CD3 sign="+"
        List<String> row2 = parseCsvLine(lines.get(3));
        assertEquals("+", row2.get(cd45SignCol), "Cell 2 CD45_sign should be +");
        assertEquals("+", row2.get(cd3SignCol), "Cell 2 CD3_sign should be +");

        // cell3: CD3+, phenotype="CD3+" -> CD45 sign="+", CD3 sign="+"
        List<String> row3 = parseCsvLine(lines.get(4));
        assertEquals("+", row3.get(cd45SignCol), "Cell 3 CD45_sign should be +");
        assertEquals("+", row3.get(cd3SignCol), "Cell 3 CD3_sign should be +");
    }

    @Test
    void csvEscapesSpecialCharacters() throws IOException {
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 8} };
        CellIndex index = Cells.columns(markers, values).build();
        boolean[] mask = Cells.allTrue(2);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("CD45", 5.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);
        gate.setPositiveName("CD45+, bright");

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        File csvFile = tempDir.resolve("escape.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        assertEquals(3, lines.size(), "Header + 2 data rows");

        // The positive cell (value=8 >= 5) should have phenotype "CD45+, bright"
        // which must be quoted in CSV as: "CD45+, bright"
        // Find the row for cell 1 (value=8, positive)
        // Cell 0 has value=1 -> negative, cell 1 has value=8 -> positive
        String positiveLine = lines.get(2); // cell 1
        assertTrue(positiveLine.contains("\"CD45+, bright\""),
                "Phenotype with comma should be quoted in CSV output");

        // Verify parsing works correctly
        List<String> headerFields = parseCsvLine(lines.get(0));
        int phenoCol = headerFields.indexOf("phenotype");
        List<String> row = parseCsvLine(lines.get(2));
        assertEquals("CD45+, bright", row.get(phenoCol),
                "Parsed phenotype should preserve the comma-containing name");
    }

    @Test
    void emptyTreeExportsAllAsUnclassified() throws IOException {
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4} };
        CellIndex index = Cells.columns(markers, values).build();
        boolean[] mask = Cells.allTrue(4);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        File csvFile = tempDir.resolve("empty_tree.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        // Empty tree has no marker columns, so header is just the base columns
        assertEquals(5, lines.size(), "Header + 4 data rows");

        List<String> headerFields = parseCsvLine(lines.get(0));
        int phenoCol = headerFields.indexOf("phenotype");
        assertTrue(phenoCol >= 0, "phenotype column should exist");

        for (int i = 1; i < lines.size(); i++) {
            List<String> row = parseCsvLine(lines.get(i));
            assertEquals("Unclassified", row.get(phenoCol),
                    "All cells should be Unclassified with empty tree");
        }
    }

    @Test
    void allCellsExcludedStillExportedWithOutlierFlag() throws IOException {
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3} };
        CellIndex index = Cells.columns(markers, values).build(); // default area=100

        // QF with impossible minArea excludes everything
        QualityFilter qf = new QualityFilter();
        qf.setMinArea(Double.MAX_VALUE);

        GateNode gate = new GateNode("CD45", 2.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(qf);
        tree.addRoot(gate);

        boolean[] mask = GatingEngine.computeQualityMask(index, qf);
        MarkerStats stats = MarkerStats.compute(index, mask);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        // Verify all are excluded
        for (boolean ex : result.getExcluded()) {
            assertTrue(ex, "All cells should be excluded");
        }

        File csvFile = tempDir.resolve("all_excluded.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        assertEquals(4, lines.size(), "Header + 3 rows (excluded cells still exported)");
        assertTrue(lines.get(0).startsWith("cell_id,"), "First line should be the header");

        List<String> headerFields = parseCsvLine(lines.get(0));
        int outlierCol = headerFields.indexOf("Outlier");
        for (int i = 1; i < 4; i++) {
            List<String> row = parseCsvLine(lines.get(i));
            assertEquals("True", row.get(outlierCol), "Every excluded-by-QF cell has Outlier=True");
        }
    }

    @Test
    void outOfAnnotationFlagIsSetForRoiExcludedCells() throws IOException {
        // 4 cells; we pass a hand-rolled ROI mask that excludes cells 0 and 1.
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 7, 8} };
        CellIndex index = Cells.columns(markers, values).build();
        boolean[] mask = Cells.allTrue(4);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("CD45", 5.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        boolean[] roiMask = {false, false, true, true};
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats, roiMask);

        File csvFile = tempDir.resolve("roi.csv").toFile();
        PhenotypeCsvExporter.export(csvFile, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csvFile.toPath());
        assertEquals(5, lines.size(), "Header + 4 data rows (all cells present)");

        List<String> headerFields = parseCsvLine(lines.get(0));
        int outCol = headerFields.indexOf("Out_of_annotation");
        int outlierCol = headerFields.indexOf("Outlier");
        int phenoCol = headerFields.indexOf("phenotype");

        // Cells 0,1: Out_of_annotation=True, Outlier=False; still get phenotype CD45-
        for (int i = 0; i < 2; i++) {
            List<String> row = parseCsvLine(lines.get(i + 1));
            assertEquals("True", row.get(outCol), "Cell " + i + " outside ROI -> Out_of_annotation=True");
            assertEquals("False", row.get(outlierCol));
            assertEquals("CD45-", row.get(phenoCol));
        }
        // Cells 2,3: inside ROI; phenotype CD45+ (values 7,8 >= 5)
        for (int i = 2; i < 4; i++) {
            List<String> row = parseCsvLine(lines.get(i + 1));
            assertEquals("False", row.get(outCol));
            assertEquals("False", row.get(outlierCol));
            assertEquals("CD45+", row.get(phenoCol));
        }
    }
}
