package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.Statistic;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatingEngineTest {

    // ---- helper ----

    /**
     * Build a synthetic CellIndex with the given markers, marker values, and area values.
     * Each cell is placed at coordinates (i*10, i*10).
     *
     * @param markers      list of marker names
     * @param markerValues [markerIndex][cellIndex] intensity values
     * @param areas        per-cell area values, or null to default to 100.0
     */
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

    /**
     * Build a CellIndex where cells are placed at specific (x, y) coordinates.
     */
    private static CellIndex buildIndexWithCoords(List<String> markers, double[][] markerValues,
                                                   double[] xs, double[] ys) {
        int nCells = markerValues[0].length;
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < nCells; i++) {
            ROI roi = ROIs.createPointsROI(xs[i], ys[i], ImagePlane.getDefaultPlane());
            PathObject obj = PathObjects.createDetectionObject(roi);
            for (int m = 0; m < markers.size(); m++) {
                obj.getMeasurements().put(markers.get(m), markerValues[m][i]);
            }
            obj.getMeasurements().put("area", 100.0);
            cells.add(obj);
        }
        return CellIndex.build(cells, markers);
    }

    /**
     * Create an all-true quality mask of the given size.
     */
    private static boolean[] allTrueMask(int n) {
        boolean[] mask = new boolean[n];
        Arrays.fill(mask, true);
        return mask;
    }

    // ---- tests ----

    @Test
    void assignAllBasicPositiveNegativeSplit() {
        // 10 cells, CD45 values 1..10, threshold 5.5 (raw), no z-score
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5, 6, 7, 8, 9, 10} };
        CellIndex index = buildIndex(markers, values, null);
        boolean[] mask = allTrueMask(10);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("CD45", 5.5);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        int posCount = 0;
        int negCount = 0;
        for (String p : result.getPhenotypes()) {
            assertNotNull(p);
            if (p.equals("CD45+")) posCount++;
            else if (p.equals("CD45-")) negCount++;
        }
        // Values >= 5.5: 6,7,8,9,10 = 5 positive; 1,2,3,4,5 = 5 negative
        assertEquals(5, posCount, "Expected 5 cells positive (>= 5.5)");
        assertEquals(5, negCount, "Expected 5 cells negative (< 5.5)");
    }

    @Test
    void assignAllWithZScore() {
        // 10 cells with values 1..10, mean=5.5, z-score threshold=0 splits at the mean
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5, 6, 7, 8, 9, 10} };
        CellIndex index = buildIndex(markers, values, null);
        boolean[] mask = allTrueMask(10);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("CD45", 0.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(true);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        // mean = 5.5, so values >= 5.5 have z >= 0 -> positive
        // Values 6,7,8,9,10 -> positive (5); values 1,2,3,4,5 -> negative (5)
        int posCount = 0;
        int negCount = 0;
        for (String p : result.getPhenotypes()) {
            assertNotNull(p);
            if (p.equals("CD45+")) posCount++;
            else if (p.equals("CD45-")) negCount++;
        }
        assertEquals(5, posCount, "Expected 5 positive cells with z-score >= 0");
        assertEquals(5, negCount, "Expected 5 negative cells with z-score < 0");
    }

    @Test
    void qualityFilterExcludesByArea() {
        // 10 cells, areas 10,20,...,100. QF minArea=50 should exclude first 4.
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5, 6, 7, 8, 9, 10} };
        double[] areas = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        CellIndex index = buildIndex(markers, values, areas);

        QualityFilter qf = new QualityFilter();
        qf.setMinArea(50);

        boolean[] mask = GatingEngine.computeQualityMask(index, qf);

        int passing = 0;
        int excluded = 0;
        for (boolean b : mask) {
            if (b) passing++;
            else excluded++;
        }
        // Areas < 50: 10,20,30,40 = 4 excluded; areas >= 50: 50..100 = 6 passing
        assertEquals(6, passing, "Expected 6 cells passing with area >= 50");
        assertEquals(4, excluded, "Expected 4 cells excluded with area < 50");
    }

    @Test
    void roiMaskExcludesCellsOutsideRegion() {
        // Place 6 cells at known coordinates; rectangle ROI covers (0,0)-(55,55)
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5, 6} };
        double[] xs = {5, 15, 25, 50, 80, 100};
        double[] ys = {5, 15, 25, 50, 80, 100};
        CellIndex index = buildIndexWithCoords(markers, values, xs, ys);

        // Create a rectangular ROI from (0,0) to (55,55)
        ROI rectRoi = ROIs.createRectangleROI(0, 0, 55, 55, ImagePlane.getDefaultPlane());

        boolean[] mask = GatingEngine.computeRoiMask(index, rectRoi);

        // Cells inside (55x55): (5,5), (15,15), (25,25), (50,50) = 4 inside
        // Cells outside: (80,80), (100,100) = 2 outside
        assertTrue(mask[0], "Cell at (5,5) should be inside ROI");
        assertTrue(mask[1], "Cell at (15,15) should be inside ROI");
        assertTrue(mask[2], "Cell at (25,25) should be inside ROI");
        assertTrue(mask[3], "Cell at (50,50) should be inside ROI");
        assertFalse(mask[4], "Cell at (80,80) should be outside ROI");
        assertFalse(mask[5], "Cell at (100,100) should be outside ROI");
    }

    @Test
    void outlierExclusionMarksOutliersExcluded() {
        // 100 cells: 98 with values 1..98, plus 2 outliers at -100 and +200
        List<String> markers = List.of("CD45");
        double[] vals = new double[100];
        for (int i = 0; i < 98; i++) {
            vals[i] = i + 1; // 1..98
        }
        vals[98] = -100.0; // outlier low
        vals[99] = 200.0;  // outlier high
        double[][] values = { vals };

        CellIndex index = buildIndex(markers, values, null);
        boolean[] mask = allTrueMask(100);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("CD45", 50.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);
        gate.setExcludeOutliers(true);
        gate.setClipPercentileLow(1.0);
        gate.setClipPercentileHigh(99.0);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        // The two outlier cells should be excluded and flagged, but still receive a phenotype
        boolean[] excluded = result.getExcluded();
        boolean[] outlier = result.getOutlier();
        assertTrue(excluded[98], "Low outlier (-100) should be excluded");
        assertTrue(excluded[99], "High outlier (+200) should be excluded");
        assertTrue(outlier[98], "Low outlier should be flagged as outlier");
        assertTrue(outlier[99], "High outlier should be flagged as outlier");
        assertNotNull(result.getPhenotypes()[98], "Outlier cell still gets a would-have-been phenotype");
        assertNotNull(result.getPhenotypes()[99], "Outlier cell still gets a would-have-been phenotype");
    }

    @Test
    void nestedGatingOnlySeesParentPositiveCells() {
        // 10 cells: CD45 values 1..10, CD3 values 10..1 (reversed)
        // Root gate: CD45 threshold=5.5 (raw) -> cells 6-10 are CD45+
        // Child gate on positive branch: CD3 threshold=3.5 (raw)
        // Of cells 6-10, CD3 values are 5,4,3,2,1 -> CD3+ for cells 6,7 (values 5,4 >= 3.5)
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = {
            {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},  // CD45
            {10, 9, 8, 7, 6, 5, 4, 3, 2, 1}    // CD3
        };
        CellIndex index = buildIndex(markers, values, null);
        boolean[] mask = allTrueMask(10);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        GateNode childGate = new GateNode("CD3", 3.5);
        childGate.setStatistic(Statistic.MEAN);
        childGate.setThresholdIsZScore(false);
        root.getPositiveChildren().add(childGate);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        String[] phenotypes = result.getPhenotypes();

        // Cells 0-4 (CD45 values 1-5): CD45- (no children on negative branch)
        for (int i = 0; i < 5; i++) {
            assertEquals("CD45-", phenotypes[i], "Cell " + i + " should be CD45-");
        }

        // Cells 5-6 (CD45 values 6,7 -> CD45+, CD3 values 5,4 >= 3.5 -> CD3+)
        assertEquals("CD3+", phenotypes[5], "Cell 5 should be CD3+ (CD3 value=5)");
        assertEquals("CD3+", phenotypes[6], "Cell 6 should be CD3+ (CD3 value=4)");

        // Cells 7-9 (CD45 values 8,9,10 -> CD45+, CD3 values 3,2,1 < 3.5 -> CD3-)
        assertEquals("CD3-", phenotypes[7], "Cell 7 should be CD3- (CD3 value=3)");
        assertEquals("CD3-", phenotypes[8], "Cell 8 should be CD3- (CD3 value=2)");
        assertEquals("CD3-", phenotypes[9], "Cell 9 should be CD3- (CD3 value=1)");
    }

    @Test
    void missingChannelSkipsGate() {
        // Gate on "NONEXISTENT" channel. All cells should remain Unclassified.
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5} };
        CellIndex index = buildIndex(markers, values, null);
        boolean[] mask = allTrueMask(5);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateNode gate = new GateNode("NONEXISTENT", 0.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        for (int i = 0; i < 5; i++) {
            assertEquals("Unclassified", result.getPhenotypes()[i],
                    "Cell " + i + " should be Unclassified when channel is missing");
        }
    }

    @Test
    void emptyTreeLeavesAllUnclassified() {
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5} };
        CellIndex index = buildIndex(markers, values, null);
        boolean[] mask = allTrueMask(5);
        MarkerStats stats = MarkerStats.compute(index, mask);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        for (int i = 0; i < 5; i++) {
            assertEquals("Unclassified", result.getPhenotypes()[i],
                    "Cell " + i + " should be Unclassified with empty tree");
        }
        for (boolean ex : result.getExcluded()) {
            assertFalse(ex, "No cells should be excluded with empty tree");
        }
    }

    @Test
    void allCellsExcludedByQualityFilter() {
        // QF with impossible minArea should exclude everything
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5} };
        CellIndex index = buildIndex(markers, values, null); // default area=100

        QualityFilter qf = new QualityFilter();
        qf.setMinArea(Double.MAX_VALUE);

        GateNode gate = new GateNode("CD45", 3.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(qf);
        tree.addRoot(gate);

        boolean[] mask = allTrueMask(5);
        MarkerStats stats = MarkerStats.compute(index, mask);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        for (int i = 0; i < 5; i++) {
            assertTrue(result.getExcluded()[i], "Cell " + i + " should be excluded");
            assertTrue(result.getOutlier()[i], "Quality-filtered cell should be flagged as Outlier");
            assertNotNull(result.getPhenotypes()[i], "Excluded cell still gets a would-have-been phenotype");
        }
    }

    @Test
    void combineMasksAndsCorrectly() {
        boolean[] a = {true, true, false, false};
        boolean[] b = {true, false, true, false};
        boolean[] result = GatingEngine.combineMasks(a, b);

        boolean[] expected = {true, false, false, false};
        assertArrayEquals(expected, result, "combineMasks should AND element-wise");
    }

    @Test
    void computeQualityMaskHandlesNaN() {
        // Cells with NaN area should pass since QualityFilter.passes() skips NaN comparisons
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3} };
        double[] areas = {50.0, Double.NaN, 150.0};
        CellIndex index = buildIndex(markers, values, areas);

        QualityFilter qf = new QualityFilter();
        qf.setMinArea(100);

        boolean[] mask = GatingEngine.computeQualityMask(index, qf);

        // area=50 < 100 -> fail; area=NaN -> pass (NaN check skips); area=150 >= 100 -> pass
        assertFalse(mask[0], "Cell with area=50 should fail minArea=100");
        assertTrue(mask[1], "Cell with NaN area should pass (NaN comparison bypassed)");
        assertTrue(mask[2], "Cell with area=150 should pass minArea=100");
    }

    // ---- ancestor mask tests ----

    @Test
    void computeAncestorMaskRootGateGetsAllCells() {
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(5));

        GateNode root = new GateNode("CD45", 3.0);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        GateTree tree = new GateTree();
        tree.getRoots().add(root);

        boolean[] mask = GatingEngine.computeAncestorMask(tree, root, index, stats, null);
        // Root gate: all cells should reach it
        for (int i = 0; i < 5; i++) {
            assertTrue(mask[i], "Root gate should be reachable by all cells");
        }
    }

    @Test
    void computeAncestorMaskChildGateOnlyGetsParentBranchCells() {
        // Parent: CD45 threshold=3.0 (raw). Cells 1,2,3 -> neg (< 3); cells 4,5 -> pos (>= 3)
        // But cells are 1-indexed in values: {1, 2, 3, 4, 5}
        // Cell values: 1,2 < 3 -> neg;  3,4,5 >= 3 -> pos
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = {
            {1, 2, 3, 4, 5},  // CD45
            {10, 20, 30, 40, 50}  // CD3
        };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(5));

        GateNode parent = new GateNode("CD45", 3.0);
        parent.setStatistic(Statistic.MEAN);
        parent.setThresholdIsZScore(false);
        GateNode child = new GateNode("CD3", 25.0);
        child.setStatistic(Statistic.MEAN);
        child.setThresholdIsZScore(false);

        // Add child under positive branch of parent
        parent.getBranches().get(0).getChildren().add(child);

        GateTree tree = new GateTree();
        tree.getRoots().add(parent);

        boolean[] mask = GatingEngine.computeAncestorMask(tree, child, index, stats, null);
        // Only cells with CD45 >= 3.0 should reach the child gate
        assertFalse(mask[0], "Cell CD45=1 should not reach child (neg branch)");
        assertFalse(mask[1], "Cell CD45=2 should not reach child (neg branch)");
        assertTrue(mask[2], "Cell CD45=3 should reach child (pos branch, >= 3)");
        assertTrue(mask[3], "Cell CD45=4 should reach child (pos branch)");
        assertTrue(mask[4], "Cell CD45=5 should reach child (pos branch)");
    }

    @Test
    void computeAncestorMaskRespectsBaseMask() {
        List<String> markers = List.of("CD45");
        double[][] values = { {1, 2, 3, 4, 5} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(5));

        GateNode root = new GateNode("CD45", 3.0);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        GateTree tree = new GateTree();
        tree.getRoots().add(root);

        // Base mask excludes cells 0 and 1
        boolean[] baseMask = {false, false, true, true, true};
        boolean[] mask = GatingEngine.computeAncestorMask(tree, root, index, stats, baseMask);
        assertFalse(mask[0], "Cell 0 excluded by base mask");
        assertFalse(mask[1], "Cell 1 excluded by base mask");
        assertTrue(mask[2]);
        assertTrue(mask[3]);
        assertTrue(mask[4]);
    }

    @Test
    void computeAncestorMaskChildOnNegativeBranch() {
        // Parent: CD45 threshold=3.0. Cells 1,2 < 3 -> neg; 3,4,5 >= 3 -> pos
        // Child is under NEGATIVE branch
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = {
            {1, 2, 3, 4, 5},
            {10, 20, 30, 40, 50}
        };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(5));

        GateNode parent = new GateNode("CD45", 3.0);
        parent.setStatistic(Statistic.MEAN);
        parent.setThresholdIsZScore(false);
        GateNode child = new GateNode("CD3", 15.0);
        child.setStatistic(Statistic.MEAN);
        child.setThresholdIsZScore(false);

        // Add child under NEGATIVE branch (index 1)
        parent.getBranches().get(1).getChildren().add(child);

        GateTree tree = new GateTree();
        tree.getRoots().add(parent);

        boolean[] mask = GatingEngine.computeAncestorMask(tree, child, index, stats, null);
        assertTrue(mask[0], "Cell CD45=1 should reach child (neg branch)");
        assertTrue(mask[1], "Cell CD45=2 should reach child (neg branch)");
        assertFalse(mask[2], "Cell CD45=3 should NOT reach child (pos branch)");
        assertFalse(mask[3], "Cell CD45=4 should NOT reach child (pos branch)");
        assertFalse(mask[4], "Cell CD45=5 should NOT reach child (pos branch)");
    }

    @Test
    void computeAncestorMaskGrandchild() {
        // 3-level chain: grandparent -> pos -> parent -> pos -> grandchild
        // Grandparent: CD45 >= 3 (cells 3,4,5 pass)
        // Parent: CD3 >= 35 (cells 4,5 pass, but only from CD45+ cells)
        // Grandchild should only see cells that are CD45+ AND CD3+
        List<String> markers = List.of("CD45", "CD3", "CD8");
        double[][] values = {
            {1, 2, 3, 4, 5},
            {10, 20, 30, 40, 50},
            {100, 200, 300, 400, 500}
        };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(5));

        GateNode grandparent = new GateNode("CD45", 3.0);
        grandparent.setStatistic(Statistic.MEAN);
        grandparent.setThresholdIsZScore(false);
        GateNode parent = new GateNode("CD3", 35.0);
        parent.setStatistic(Statistic.MEAN);
        parent.setThresholdIsZScore(false);
        GateNode grandchild = new GateNode("CD8", 250.0);
        grandchild.setStatistic(Statistic.MEAN);
        grandchild.setThresholdIsZScore(false);

        grandparent.getBranches().get(0).getChildren().add(parent);  // parent under CD45+
        parent.getBranches().get(0).getChildren().add(grandchild);   // grandchild under CD3+

        GateTree tree = new GateTree();
        tree.getRoots().add(grandparent);

        boolean[] mask = GatingEngine.computeAncestorMask(tree, grandchild, index, stats, null);
        assertFalse(mask[0], "CD45=1 -> excluded at grandparent");
        assertFalse(mask[1], "CD45=2 -> excluded at grandparent");
        assertFalse(mask[2], "CD45=3, CD3=30 -> excluded at parent (CD3 < 35)");
        assertTrue(mask[3], "CD45=4, CD3=40 -> passes both ancestors");
        assertTrue(mask[4], "CD45=5, CD3=50 -> passes both ancestors");
    }

    @Test
    void computeAncestorMaskDisabledAncestorPassesAll() {
        // If an ancestor gate is disabled, it should not filter cells
        List<String> markers = List.of("CD45", "CD3");
        double[][] values = {
            {1, 2, 3, 4, 5},
            {10, 20, 30, 40, 50}
        };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(5));

        GateNode parent = new GateNode("CD45", 3.0);
        parent.setStatistic(Statistic.MEAN);
        parent.setThresholdIsZScore(false);
        parent.setEnabled(false);  // disabled
        GateNode child = new GateNode("CD3", 25.0);
        child.setStatistic(Statistic.MEAN);
        child.setThresholdIsZScore(false);

        parent.getBranches().get(0).getChildren().add(child);

        GateTree tree = new GateTree();
        tree.getRoots().add(parent);

        boolean[] mask = GatingEngine.computeAncestorMask(tree, child, index, stats, null);
        // Disabled parent should not filter — all cells reach the child
        for (int i = 0; i < 5; i++) {
            assertTrue(mask[i], "Cell " + i + " should reach child (parent disabled)");
        }
    }

    // ---- multi-root composite phenotype tests ----

    @Test
    void twoEnabledRootsProduceCompositePhenotype() {
        // CD45 threshold=5, PANCK threshold=5
        // Cell 0: CD45=8, PANCK=8  -> CD45+: PANCK+
        // Cell 1: CD45=8, PANCK=2  -> CD45+: PANCK-
        // Cell 2: CD45=2, PANCK=8  -> CD45-: PANCK+
        // Cell 3: CD45=2, PANCK=2  -> CD45-: PANCK-
        List<String> markers = List.of("CD45", "PANCK");
        double[][] values = { {8, 8, 2, 2}, {8, 2, 8, 2} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(4));

        GateNode root1 = new GateNode("CD45", 5.0);
        root1.setStatistic(Statistic.MEAN);
        root1.setThresholdIsZScore(false);
        GateNode root2 = new GateNode("PANCK", 5.0);
        root2.setStatistic(Statistic.MEAN);
        root2.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root1);
        tree.addRoot(root2);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        String[] phenos = result.getPhenotypes();

        assertEquals("CD45+: PANCK+", phenos[0]);
        assertEquals("CD45+: PANCK-", phenos[1]);
        assertEquals("CD45-: PANCK+", phenos[2]);
        assertEquals("CD45-: PANCK-", phenos[3]);

        // perRootColors and rootLabels should be populated
        assertNotNull(result.getPerRootColors());
        assertEquals(2, result.getPerRootColors().size());
        assertNotNull(result.getRootLabels());
        assertEquals(List.of("CD45", "PANCK"), result.getRootLabels());
    }

    @Test
    void perRootColorsStoredCorrectly() {
        List<String> markers = List.of("CD45", "PANCK");
        double[][] values = { {8, 2}, {8, 2} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(2));

        GateNode root1 = new GateNode("CD45", 5.0);
        root1.setStatistic(Statistic.MEAN);
        root1.setThresholdIsZScore(false);
        // Set known colors
        int red = (255 << 16);
        int blue = 255;
        root1.getBranches().get(0).setColor(red);   // CD45+ = red
        root1.getBranches().get(1).setColor(blue);   // CD45- = blue

        GateNode root2 = new GateNode("PANCK", 5.0);
        root2.setStatistic(Statistic.MEAN);
        root2.setThresholdIsZScore(false);
        int green = (255 << 8);
        int gray = (128 << 16) | (128 << 8) | 128;
        root2.getBranches().get(0).setColor(green);  // PANCK+ = green
        root2.getBranches().get(1).setColor(gray);   // PANCK- = gray

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root1);
        tree.addRoot(root2);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        // Cell 0: CD45+=red, PANCK+=green
        assertEquals(red, result.getPerRootColors().get(0)[0]);
        assertEquals(green, result.getPerRootColors().get(1)[0]);
        // Default color should be last root's (PANCK+)
        assertEquals(green, result.getColors()[0]);

        // Cell 1: CD45-=blue, PANCK-=gray
        assertEquals(blue, result.getPerRootColors().get(0)[1]);
        assertEquals(gray, result.getPerRootColors().get(1)[1]);
        assertEquals(gray, result.getColors()[1]);
    }

    @Test
    void singleRootBehaviorUnchanged() {
        List<String> markers = List.of("CD45");
        double[][] values = { {8, 2} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(2));

        GateNode root = new GateNode("CD45", 5.0);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        assertEquals("CD45+", result.getPhenotypes()[0]);
        assertEquals("CD45-", result.getPhenotypes()[1]);
        // No perRootColors for single root
        assertNull(result.getPerRootColors());
        assertNull(result.getRootLabels());
    }

    @Test
    void disabledRootSkippedInComposite() {
        List<String> markers = List.of("CD45", "PANCK");
        double[][] values = { {8}, {8} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(1));

        GateNode root1 = new GateNode("CD45", 5.0);
        root1.setStatistic(Statistic.MEAN);
        root1.setThresholdIsZScore(false);
        GateNode root2 = new GateNode("PANCK", 5.0);
        root2.setStatistic(Statistic.MEAN);
        root2.setThresholdIsZScore(false);
        root2.setEnabled(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root1);
        tree.addRoot(root2);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        // Only one enabled root → simple phenotype, no composite
        assertEquals("CD45+", result.getPhenotypes()[0]);
        assertNull(result.getPerRootColors());
    }

    @Test
    void threeRootsProduceTripleComposite() {
        List<String> markers = List.of("A", "B", "C");
        double[][] values = { {8}, {2}, {8} };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(1));

        GateNode r1 = new GateNode("A", 5.0);
        r1.setStatistic(Statistic.MEAN);
        r1.setThresholdIsZScore(false);
        GateNode r2 = new GateNode("B", 5.0);
        r2.setStatistic(Statistic.MEAN);
        r2.setThresholdIsZScore(false);
        GateNode r3 = new GateNode("C", 5.0);
        r3.setStatistic(Statistic.MEAN);
        r3.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(r1);
        tree.addRoot(r2);
        tree.addRoot(r3);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        assertEquals("A+: B-: C+", result.getPhenotypes()[0]);
        assertEquals(3, result.getPerRootColors().size());
        assertEquals(List.of("A", "B", "C"), result.getRootLabels());
    }

    @Test
    void exclusionInFirstRootSkipsSecond() {
        List<String> markers = List.of("CD45", "PANCK");
        // 5 cells: cell 0 is an extreme outlier on CD45
        // Other cells provide range for percentile calculation
        double[][] values = {
            {-500, 5, 6, 7, 8},  // CD45: cell 0 is far outside p1-p99
            {8, 8, 8, 8, 8}      // PANCK: all high
        };
        CellIndex index = buildIndex(markers, values, null);
        MarkerStats stats = MarkerStats.compute(index, allTrueMask(5));

        GateNode root1 = new GateNode("CD45", 5.0);
        root1.setStatistic(Statistic.MEAN);
        root1.setThresholdIsZScore(false);
        root1.setExcludeOutliers(true);
        root1.setClipPercentileLow(1.0);
        root1.setClipPercentileHigh(99.0);

        GateNode root2 = new GateNode("PANCK", 5.0);
        root2.setStatistic(Statistic.MEAN);
        root2.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root1);
        tree.addRoot(root2);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        // Cell 0 should be excluded by root1's outlier check and flagged as outlier,
        // but still gets a would-have-been phenotype for CSV export.
        assertTrue(result.getExcluded()[0]);
        assertTrue(result.getOutlier()[0]);
        assertNotNull(result.getPhenotypes()[0]);
        // Cell 1 should have composite phenotype (not excluded)
        assertFalse(result.getExcluded()[1]);
        assertTrue(result.getPhenotypes()[1].contains(": "));
    }
}
