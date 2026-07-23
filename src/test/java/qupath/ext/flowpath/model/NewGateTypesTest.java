package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.io.FlowPathSerializer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NewGateTypesTest {

    @TempDir
    Path tempDir;

    // ---- helpers ----

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

    // =====================================================================
    //  QuadrantGate Tests
    // =====================================================================

    @Test
    void quadrantEvaluateQuadrantCorrectly() {
        QuadrantGate gate = new QuadrantGate("CD45", "CD3", 5.0, 5.0);

        // PP: both >= threshold
        assertEquals(0, gate.evaluateQuadrant(5.0, 5.0), "On both thresholds should be PP (0)");
        assertEquals(0, gate.evaluateQuadrant(10.0, 10.0), "Above both thresholds should be PP (0)");

        // NP: X < threshold, Y >= threshold
        assertEquals(1, gate.evaluateQuadrant(3.0, 7.0), "X below, Y above should be NP (1)");
        assertEquals(1, gate.evaluateQuadrant(4.9, 5.0), "X just below, Y on threshold should be NP (1)");

        // PN: X >= threshold, Y < threshold
        assertEquals(2, gate.evaluateQuadrant(7.0, 3.0), "X above, Y below should be PN (2)");
        assertEquals(2, gate.evaluateQuadrant(5.0, 4.9), "X on threshold, Y just below should be PN (2)");

        // NN: both < threshold
        assertEquals(3, gate.evaluateQuadrant(3.0, 3.0), "Both below should be NN (3)");
        assertEquals(3, gate.evaluateQuadrant(0.0, 0.0), "Both zero should be NN (3)");
    }

    @Test
    void quadrantGateEngineAssignment() {
        // 8 cells with CD45 and CD3 values, threshold at 5.0 for both (raw, no z-score)
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = {
            {1, 2, 8, 9, 1, 2, 8, 9},   // CD45
            {1, 2, 1, 2, 8, 9, 8, 9}    // CD3
        };
        CellIndex index = buildIndex(markers, values, null);
        boolean[] mask = allTrueMask(8);
        MarkerStats stats = MarkerStats.compute(index, mask);

        QuadrantGate gate = new QuadrantGate("CD45", "CD3", 5.0, 5.0);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        // default QualityFilter (passes all)
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        String[] phenotypes = result.getPhenotypes();

        // Cells 0,1: CD45 < 5, CD3 < 5 -> NN
        assertEquals(gate.getBranchNN().getName(), phenotypes[0], "Cell 0 should be NN");
        assertEquals(gate.getBranchNN().getName(), phenotypes[1], "Cell 1 should be NN");

        // Cells 2,3: CD45 >= 5, CD3 < 5 -> PN
        assertEquals(gate.getBranchPN().getName(), phenotypes[2], "Cell 2 should be PN");
        assertEquals(gate.getBranchPN().getName(), phenotypes[3], "Cell 3 should be PN");

        // Cells 4,5: CD45 < 5, CD3 >= 5 -> NP
        assertEquals(gate.getBranchNP().getName(), phenotypes[4], "Cell 4 should be NP");
        assertEquals(gate.getBranchNP().getName(), phenotypes[5], "Cell 5 should be NP");

        // Cells 6,7: CD45 >= 5, CD3 >= 5 -> PP
        assertEquals(gate.getBranchPP().getName(), phenotypes[6], "Cell 6 should be PP");
        assertEquals(gate.getBranchPP().getName(), phenotypes[7], "Cell 7 should be PP");
    }

    @Test
    void quadrantDeepCopyIndependence() {
        QuadrantGate original = new QuadrantGate("CD45", "CD3", 5.0, 3.0);
        original.setThresholdIsZScore(false);
        original.setClipPercentileLow(2.0);
        original.setClipPercentileHigh(98.0);
        original.setExcludeOutliers(true);

        QuadrantGate copy = (QuadrantGate) original.deepCopy();

        // Verify copy matches original
        assertEquals("CD45", copy.getChannelX());
        assertEquals("CD3", copy.getChannelY());
        assertEquals(5.0, copy.getThresholdX());
        assertEquals(3.0, copy.getThresholdY());
        assertFalse(copy.isThresholdIsZScore());
        assertEquals(2.0, copy.getClipPercentileLow());
        assertEquals(98.0, copy.getClipPercentileHigh());
        assertTrue(copy.isExcludeOutliers());

        // Modify original
        original.setChannelX("PANCK");
        original.setChannelY("CD8");
        original.setThresholdX(99.0);
        original.setThresholdY(88.0);
        original.setThresholdIsZScore(true);

        // Copy should be unchanged
        assertEquals("CD45", copy.getChannelX(), "Copy channelX should not change when original is modified");
        assertEquals("CD3", copy.getChannelY(), "Copy channelY should not change when original is modified");
        assertEquals(5.0, copy.getThresholdX(), "Copy thresholdX should not change when original is modified");
        assertEquals(3.0, copy.getThresholdY(), "Copy thresholdY should not change when original is modified");
        assertFalse(copy.isThresholdIsZScore(), "Copy thresholdIsZScore should not change when original is modified");
    }

    @Test
    void quadrantGetBranchesReturnsFour() {
        QuadrantGate gate = new QuadrantGate("CD45", "CD3");
        List<Branch> branches = gate.getBranches();
        assertEquals(4, branches.size(), "QuadrantGate should have exactly 4 branches");
        assertNotNull(branches.get(0), "PP branch should not be null");
        assertNotNull(branches.get(1), "NP branch should not be null");
        assertNotNull(branches.get(2), "PN branch should not be null");
        assertNotNull(branches.get(3), "NN branch should not be null");
    }

    @Test
    void quadrantGetChannelsReturnsBoth() {
        QuadrantGate gate = new QuadrantGate("CD45", "CD3");
        List<String> channels = gate.getChannels();
        assertEquals(2, channels.size(), "QuadrantGate should report 2 channels");
        assertTrue(channels.contains("CD45"), "Channels should contain channelX");
        assertTrue(channels.contains("CD3"), "Channels should contain channelY");
    }

    // =====================================================================
    //  PolygonGate Tests
    // =====================================================================

    @Test
    void polygonContainsInsideTriangle() {
        PolygonGate gate = new PolygonGate("CD45", "CD3");
        // Triangle: (0,0), (10,0), (5,10)
        gate.setVertices(List.of(
            new double[]{0, 0},
            new double[]{10, 0},
            new double[]{5, 10}
        ));

        // Inside the triangle
        assertTrue(gate.contains(5, 3), "Point (5,3) should be inside triangle");
        assertTrue(gate.contains(5, 1), "Point (5,1) should be inside triangle");
        assertTrue(gate.contains(3, 2), "Point (3,2) should be inside triangle");

        // Outside the triangle
        assertFalse(gate.contains(20, 20), "Point (20,20) should be outside triangle");
        assertFalse(gate.contains(-1, -1), "Point (-1,-1) should be outside triangle");
        assertFalse(gate.contains(0, 10), "Point (0,10) should be outside triangle");
    }

    @Test
    void polygonContainsInsideSquare() {
        PolygonGate gate = new PolygonGate("CD45", "CD3");
        // Square: (0,0), (10,0), (10,10), (0,10)
        gate.setVertices(List.of(
            new double[]{0, 0},
            new double[]{10, 0},
            new double[]{10, 10},
            new double[]{0, 10}
        ));

        // Clearly inside
        assertTrue(gate.contains(5, 5), "Point (5,5) should be inside square");
        assertTrue(gate.contains(1, 1), "Point (1,1) should be inside square");
        assertTrue(gate.contains(9, 9), "Point (9,9) should be inside square");

        // Outside
        assertFalse(gate.contains(11, 5), "Point (11,5) should be outside square");
        assertFalse(gate.contains(-1, 5), "Point (-1,5) should be outside square");
        assertFalse(gate.contains(5, 11), "Point (5,11) should be outside square");
        assertFalse(gate.contains(5, -1), "Point (5,-1) should be outside square");
    }

    @Test
    void polygonLessThanThreeVerticesReturnsFalse() {
        PolygonGate gate = new PolygonGate("CD45", "CD3");

        // Empty polygon
        gate.setVertices(new ArrayList<>());
        assertFalse(gate.contains(5, 5), "Empty polygon should not contain any point");

        // Single vertex
        gate.setVertices(List.of(new double[]{5, 5}));
        assertFalse(gate.contains(5, 5), "Single-vertex polygon should not contain any point");

        // Two vertices (line)
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{10, 10}));
        assertFalse(gate.contains(5, 5), "Two-vertex polygon should not contain any point");
    }

    // =====================================================================
    //  RectangleGate Tests
    // =====================================================================

    @Test
    void rectangleContainsBoundsCheck() {
        RectangleGate gate = new RectangleGate("CD45", "CD3", 0.0, 10.0, 0.0, 10.0);

        // Inside
        assertTrue(gate.contains(5, 5), "Point (5,5) should be inside rectangle");

        // On edges (inclusive)
        assertTrue(gate.contains(0, 0), "Point (0,0) on corner should be inside");
        assertTrue(gate.contains(10, 10), "Point (10,10) on corner should be inside");
        assertTrue(gate.contains(0, 5), "Point (0,5) on left edge should be inside");
        assertTrue(gate.contains(10, 5), "Point (10,5) on right edge should be inside");

        // Outside
        assertFalse(gate.contains(15, 5), "Point (15,5) should be outside rectangle");
        assertFalse(gate.contains(-1, 5), "Point (-1,5) should be outside rectangle");
        assertFalse(gate.contains(5, -1), "Point (5,-1) should be outside rectangle");
        assertFalse(gate.contains(5, 11), "Point (5,11) should be outside rectangle");
    }

    @Test
    void rectangleEngineAssignment() {
        // 4 cells with 2D values. Rectangle gate on CD45=[2,8], CD3=[2,8].
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = {
            {5, 1, 5, 10},  // CD45
            {5, 5, 1, 5}    // CD3
        };
        CellIndex index = buildIndex(markers, values, null);
        boolean[] mask = allTrueMask(4);
        MarkerStats stats = MarkerStats.compute(index, mask);

        RectangleGate gate = new RectangleGate("CD45", "CD3", 2.0, 8.0, 2.0, 8.0);
        gate.setThresholdIsZScore(false);  // region is in raw marker units

        GateTree tree = new GateTree();
        // default QualityFilter (passes all)
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        String[] phenotypes = result.getPhenotypes();

        // Cell 0: (5,5) -> inside
        assertEquals(gate.getInsideBranch().getName(), phenotypes[0], "Cell 0 at (5,5) should be inside");
        // Cell 1: (1,5) -> CD45=1 < 2 -> outside
        assertEquals(gate.getOutsideBranch().getName(), phenotypes[1], "Cell 1 at (1,5) should be outside");
        // Cell 2: (5,1) -> CD3=1 < 2 -> outside
        assertEquals(gate.getOutsideBranch().getName(), phenotypes[2], "Cell 2 at (5,1) should be outside");
        // Cell 3: (10,5) -> CD45=10 > 8 -> outside
        assertEquals(gate.getOutsideBranch().getName(), phenotypes[3], "Cell 3 at (10,5) should be outside");
    }

    // =====================================================================
    //  EllipseGate Tests
    // =====================================================================

    @Test
    void ellipseContainsCenter() {
        EllipseGate gate = new EllipseGate("CD45", "CD3", 5.0, 5.0, 3.0, 4.0);
        assertTrue(gate.contains(5.0, 5.0), "Center of ellipse should be inside");
    }

    @Test
    void ellipseContainsEdge() {
        // Ellipse centered at (0,0) with radiusX=5, radiusY=10
        EllipseGate gate = new EllipseGate("CD45", "CD3", 0.0, 0.0, 5.0, 10.0);

        // Point on the edge along X axis: (5, 0) -> (5/5)^2 + (0/10)^2 = 1.0
        assertTrue(gate.contains(5.0, 0.0), "Point exactly on X-axis edge should be inside (<=1)");

        // Point on the edge along Y axis: (0, 10) -> (0/5)^2 + (10/10)^2 = 1.0
        assertTrue(gate.contains(0.0, 10.0), "Point exactly on Y-axis edge should be inside (<=1)");

        // Point slightly inside: (3, 0) -> (3/5)^2 + (0/10)^2 = 0.36
        assertTrue(gate.contains(3.0, 0.0), "Point inside ellipse should be inside");
    }

    @Test
    void ellipseRejectsOutside() {
        EllipseGate gate = new EllipseGate("CD45", "CD3", 0.0, 0.0, 5.0, 10.0);

        // Point beyond X radius: (6, 0) -> (6/5)^2 + 0 = 1.44 > 1
        assertFalse(gate.contains(6.0, 0.0), "Point beyond X radius should be outside");

        // Point beyond Y radius: (0, 11) -> 0 + (11/10)^2 = 1.21 > 1
        assertFalse(gate.contains(0.0, 11.0), "Point beyond Y radius should be outside");

        // Far away point
        assertFalse(gate.contains(100.0, 100.0), "Far away point should be outside");
    }

    @Test
    void ellipseZeroRadiusReturnsFalse() {
        // Zero radiusX
        EllipseGate gate1 = new EllipseGate("CD45", "CD3", 5.0, 5.0, 0.0, 10.0);
        assertFalse(gate1.contains(5.0, 5.0), "Ellipse with zero radiusX should not contain any point");

        // Zero radiusY
        EllipseGate gate2 = new EllipseGate("CD45", "CD3", 5.0, 5.0, 10.0, 0.0);
        assertFalse(gate2.contains(5.0, 5.0), "Ellipse with zero radiusY should not contain any point");

        // Both zero
        EllipseGate gate3 = new EllipseGate("CD45", "CD3", 5.0, 5.0, 0.0, 0.0);
        assertFalse(gate3.contains(5.0, 5.0), "Ellipse with both radii zero should not contain any point");

        // Negative radii
        EllipseGate gate4 = new EllipseGate("CD45", "CD3", 5.0, 5.0, -1.0, -1.0);
        assertFalse(gate4.contains(5.0, 5.0), "Ellipse with negative radii should not contain any point");
    }

    // =====================================================================
    //  Serialization Tests
    // =====================================================================

    @Test
    void quadrantGateSerializationRoundTrip() throws IOException {
        QuadrantGate gate = new QuadrantGate("CD45", "CD3", 2.5, 3.5);
        gate.setThresholdIsZScore(false);
        gate.setClipPercentileLow(2.0);
        gate.setClipPercentileHigh(98.0);
        gate.setExcludeOutliers(true);

        GateTree tree = new GateTree();
        // default QualityFilter (passes all)
        tree.addRoot(gate);

        File file = tempDir.resolve("quadrant.json").toFile();
        FlowPathSerializer.save(tree, file);
        GateTree loaded = FlowPathSerializer.load(file);

        assertEquals(1, loaded.getRoots().size());
        assertInstanceOf(QuadrantGate.class, loaded.getRoots().get(0));

        QuadrantGate loadedGate = (QuadrantGate) loaded.getRoots().get(0);
        assertEquals("CD45", loadedGate.getChannelX());
        assertEquals("CD3", loadedGate.getChannelY());
        assertEquals(2.5, loadedGate.getThresholdX());
        assertEquals(3.5, loadedGate.getThresholdY());
        assertFalse(loadedGate.isThresholdIsZScore());
        assertEquals(2.0, loadedGate.getClipPercentileLow());
        assertEquals(98.0, loadedGate.getClipPercentileHigh());
        assertTrue(loadedGate.isExcludeOutliers());

        // Verify branches preserved
        assertEquals(4, loadedGate.getBranches().size());
        assertEquals(gate.getBranchPP().getName(), loadedGate.getBranchPP().getName());
        assertEquals(gate.getBranchNP().getName(), loadedGate.getBranchNP().getName());
        assertEquals(gate.getBranchPN().getName(), loadedGate.getBranchPN().getName());
        assertEquals(gate.getBranchNN().getName(), loadedGate.getBranchNN().getName());
    }

}
