package qupath.ext.flowpath.umap.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UmapResultTest {

    private static PathObject createCell(String classification, double markerValue) {
        var obj = PathObjects.createDetectionObject(
                ROIs.createPointsROI(10, 20, ImagePlane.getDefaultPlane()));
        obj.getMeasurements().put("CD45", markerValue);
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
        assertEquals("cell_id,phenotype,population,centroid_x,centroid_y,umap_x,umap_y,CD45_raw,CD45_zscore",
                lines.get(0));
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
        // Header includes population column
        assertTrue(lines.get(0).contains("population"));
        // Cell 0: phenotype=CD4+, population=Cluster A
        assertTrue(lines.get(1).contains(",CD4+,Cluster A,"));
        // Cell 1: phenotype=CD8+, population=(empty)
        assertTrue(lines.get(2).contains(",CD8+,,"));
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
