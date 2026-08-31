package qupath.ext.flowpath.umap.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UmapResultTest {

    private static PathObject createCell(String classification, double markerValue) {
        var obj = Cells.of(1).at(10, 20).marker("CD45", markerValue).only();
        if (classification != null) {
            obj.setPathClass(PathClass.fromString(classification));
        }
        return obj;
    }

    private static CellIndex buildIndex(List<PathObject> cells, List<String> markers) {
        return CellIndex.build(cells, markers);
    }

    @Test
    void exportHasFlowPathCompatibleHeader() throws IOException {
        var obj = createCell("T-cell", 5.0);
        var cells = List.of(obj);
        var markers = List.of("CD45");
        var index = buildIndex(cells, markers);
        var stats = MarkerStats.compute(index);

        var result = new UmapResult(
                new double[]{1.0}, new double[]{2.0},
                new PathObject[]{obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp, index, stats, null);

        List<String> lines = Files.readAllLines(temp.toPath());
        // The identity block comes from CellTable and is byte-identical to the one
        // gate_pheno.csv writes -- that shared prefix is what makes the two files
        // joinable. The UMAP-specific columns follow it.
        // The morphology block is whatever this export carries. These cells hold marker
        // values only, so the sole morphology column is the total intensity FlowPath sums
        // itself -- there is no area or perimeter to report.
        assertEquals("cell_id,phenotype,centroid_x,centroid_y,centroid_x_px,centroid_y_px"
                + ",total_intensity"
                + ",population,umap_x,umap_y,CD45_raw,CD45_zscore",
                lines.get(0));
    }

    /**
     * Both per-cell CSVs must place cells in the same coordinate space under the same
     * column names, or they cannot be joined to each other.
     * <p>
     * They could not before: this writer took centroids from {@code CellIndex.getCentroidX},
     * which returns the space the measurement arrived in, while {@code PhenotypeCsvExporter}
     * wrote micrometres -- both under a bare {@code centroid_x} with the unit recorded in
     * neither file. Asserting the shared header prefix pins the fix at the level the bug
     * lived at, rather than re-testing one writer's formatting.
     */
    @Test
    void sharesItsIdentityBlockWithThePhenotypeExport() throws IOException {
        var obj = createCell("T-cell", 5.0);
        var index = buildIndex(List.of(obj), List.of("CD45"));
        var stats = MarkerStats.compute(index);

        var result = new UmapResult(
                new double[]{1.0}, new double[]{2.0},
                new PathObject[]{obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp, index, stats, null);
        String header = Files.readAllLines(temp.toPath()).get(0);

        var expected = new StringWriter();
        qupath.ext.flowpath.io.CellTable.writeIdentityHeader(expected, index, index.hasLabels());
        assertTrue(header.startsWith(expected.toString()),
                "UMAP CSV must open with CellTable's identity block; was: " + header);
        assertTrue(header.contains("centroid_x_px"),
                "the pixel space must be named, not left implicit");
    }

    @Test
    void exportHasCorrectRowData() throws IOException {
        var obj = createCell("T-cell", 5.0);
        var cells = List.of(obj);
        var markers = List.of("CD45");
        var index = buildIndex(cells, markers);
        var stats = MarkerStats.compute(index);

        var result = new UmapResult(
                new double[]{-3.14159}, new double[]{2.71828},
                new PathObject[]{obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp, index, stats, null);

        List<String> lines = Files.readAllLines(temp.toPath());
        assertEquals(2, lines.size());
        String row = lines.get(1);
        // cell_id=0, phenotype=T-cell, population=(empty), centroid, umap, marker
        assertTrue(row.startsWith("0,T-cell,"), "Row should start with cell_id and phenotype");
        assertTrue(row.contains("-3.1416"), "Row should contain UMAP X coordinate");
        assertTrue(row.contains("2.7183"), "Row should contain UMAP Y coordinate");
    }

    @Test
    void exportUnclassifiedCells() throws IOException {
        var obj = createCell(null, 3.0);
        var cells = List.of(obj);
        var markers = List.of("CD45");
        var index = buildIndex(cells, markers);
        var stats = MarkerStats.compute(index);

        var result = new UmapResult(
                new double[]{0.0}, new double[]{0.0},
                new PathObject[]{obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp, index, stats, null);

        List<String> lines = Files.readAllLines(temp.toPath());
        assertTrue(lines.get(1).contains(",Unclassified,"));
    }

    @Test
    void exportWithPopulationTagsSplitsPhenotype() throws IOException {
        var obj1 = createCell("CD4+: Cluster A", 7.0);
        var obj2 = createCell("CD8+", 2.0);
        var cells = List.of(obj1, obj2);
        var markers = List.of("CD45");
        var index = buildIndex(cells, markers);
        var stats = MarkerStats.compute(index);

        var result = new UmapResult(
                new double[]{1.0, 2.0}, new double[]{3.0, 4.0},
                new PathObject[]{obj1, obj2}, new String[]{"CD45"},
                UmapParameters.defaults());

        var tag = new PopulationTag("Cluster A", 0xFF8800, new boolean[]{true, false});

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp, index, stats, List.of(tag));

        List<String> lines = Files.readAllLines(temp.toPath());
        // Addressed by column name rather than by adjacency: phenotype and population are
        // no longer neighbours now that the shared identity block sits between them, and a
        // positional assertion would have to be rewritten every time a column is added.
        List<String> header = List.of(lines.get(0).split(",", -1));
        int phenotypeCol = header.indexOf("phenotype");
        int populationCol = header.indexOf("population");
        assertTrue(phenotypeCol >= 0 && populationCol >= 0, "both columns present");

        String[] row0 = lines.get(1).split(",", -1);
        assertEquals("CD4+", row0[phenotypeCol]);
        assertEquals("Cluster A", row0[populationCol]);

        String[] row1 = lines.get(2).split(",", -1);
        assertEquals("CD8+", row1[phenotypeCol]);
        assertEquals("", row1[populationCol]);
    }

    @Test
    void exportIncludesMarkerRawAndZscore() throws IOException {
        var obj1 = createCell("A", 2.0);
        var obj2 = createCell("B", 8.0);
        var cells = List.of(obj1, obj2);
        var markers = List.of("CD45");
        var index = buildIndex(cells, markers);
        var stats = MarkerStats.compute(index);

        var result = new UmapResult(
                new double[]{0.0, 1.0}, new double[]{0.0, 1.0},
                new PathObject[]{obj1, obj2}, new String[]{"CD45"},
                UmapParameters.defaults());

        File temp = File.createTempFile("umap", ".csv");
        temp.deleteOnExit();
        result.exportToCsv(temp, index, stats, null);

        List<String> lines = Files.readAllLines(temp.toPath());
        // Cell 0 raw=2.0, cell 1 raw=8.0
        assertTrue(lines.get(1).contains("2.0000"), "Row 1 should contain raw value 2.0");
        assertTrue(lines.get(2).contains("8.0000"), "Row 2 should contain raw value 8.0");
        // z-scores should be present (non-empty) since std > 0
        String[] fields1 = lines.get(1).split(",");
        String zscoreField = fields1[fields1.length - 1];
        assertFalse(zscoreField.isEmpty(), "Z-score should not be empty when std > 0");
    }

    @Test
    void sizeMatchesArrayLength() {
        var result = new UmapResult(
                new double[]{1, 2, 3}, new double[]{4, 5, 6},
                new PathObject[3], new String[]{},
                UmapParameters.defaults());

        assertEquals(3, result.size());
    }

    @Test
    void constructorRejectsNullArrays() {
        assertThrows(NullPointerException.class, () ->
            new UmapResult(null, new double[]{1}, new PathObject[1], new String[]{}, UmapParameters.defaults()));
        assertThrows(NullPointerException.class, () ->
            new UmapResult(new double[]{1}, null, new PathObject[1], new String[]{}, UmapParameters.defaults()));
        assertThrows(NullPointerException.class, () ->
            new UmapResult(new double[]{1}, new double[]{1}, null, new String[]{}, UmapParameters.defaults()));
        assertThrows(NullPointerException.class, () ->
            new UmapResult(new double[]{1}, new double[]{1}, new PathObject[1], null, UmapParameters.defaults()));
    }

    @Test
    void gettersReturnDefensiveCopies() {
        var obj = createCell("A", 1.0);
        var result = new UmapResult(
                new double[]{1.0}, new double[]{2.0},
                new PathObject[]{obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        // Mutate returned arrays
        result.getUmapX()[0] = 999.0;
        result.getUmapY()[0] = 999.0;
        result.getObjects()[0] = null;
        result.getMarkerNames()[0] = "HACKED";

        // Verify internal state unchanged
        assertEquals(1.0, result.getUmapX()[0]);
        assertEquals(2.0, result.getUmapY()[0]);
        assertSame(obj, result.getObjects()[0]);
        assertEquals("CD45", result.getMarkerNames()[0]);
    }

    @Test
    void rawAccessorsReturnSameBackingReference() {
        var obj = createCell("A", 1.0);
        var result = new UmapResult(
                new double[]{1.0, 2.0, 3.0}, new double[]{4.0, 5.0, 6.0},
                new PathObject[]{obj, obj, obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        // Same reference each call — no clone
        assertSame(result.getUmapXRaw(), result.getUmapXRaw());
        assertSame(result.getUmapYRaw(), result.getUmapYRaw());
        assertSame(result.getObjectsRaw(), result.getObjectsRaw());
        assertSame(result.getMarkerNamesRaw(), result.getMarkerNamesRaw());
    }

    @Test
    void publicAccessorsStillReturnDistinctClones() {
        var obj = createCell("A", 1.0);
        var result = new UmapResult(
                new double[]{1.0}, new double[]{2.0},
                new PathObject[]{obj}, new String[]{"CD45"},
                UmapParameters.defaults());

        // Different references each call — defensive clone
        assertNotSame(result.getUmapX(), result.getUmapX());
        assertNotSame(result.getUmapY(), result.getUmapY());
        assertNotSame(result.getObjects(), result.getObjects());
        assertNotSame(result.getMarkerNames(), result.getMarkerNames());
    }

    @Test
    void rawAndCloneAccessorsReturnSameContent() {
        var obj = createCell("A", 1.0);
        var result = new UmapResult(
                new double[]{1.5, 2.5}, new double[]{3.5, 4.5},
                new PathObject[]{obj, obj}, new String[]{"CD45", "CD8"},
                UmapParameters.defaults());

        // Same content, different references for the clones
        assertEquals(result.getUmapX()[0], result.getUmapXRaw()[0]);
        assertEquals(result.getUmapY()[1], result.getUmapYRaw()[1]);
        assertSame(result.getObjects()[0], result.getObjectsRaw()[0]);
        assertEquals(result.getMarkerNames()[0], result.getMarkerNamesRaw()[0]);
        assertEquals(result.getMarkerNames()[1], result.getMarkerNamesRaw()[1]);
    }

    @Test
    void exportRejectsMismatchedCellIndex() {
        var obj1 = createCell("A", 1.0);
        var obj2 = createCell("B", 2.0);
        var cells = List.of(obj1, obj2);
        var markers = List.of("CD45");
        var index = buildIndex(cells, markers);

        // UmapResult has 1 cell, CellIndex has 2
        var result = new UmapResult(
                new double[]{0.0}, new double[]{0.0},
                new PathObject[]{obj1}, new String[]{"CD45"},
                UmapParameters.defaults());

        File temp;
        try {
            temp = File.createTempFile("umap", ".csv");
            temp.deleteOnExit();
        } catch (IOException e) {
            fail("Could not create temp file");
            return;
        }
        assertThrows(IllegalArgumentException.class, () ->
            result.exportToCsv(temp, index, null, null));
    }
}
