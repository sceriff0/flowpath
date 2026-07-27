package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2: compartment/statistic selection drives gating, with z-score and percentile
 * stats computed on the resolved column.
 */
class CompartmentGatingTest {

    /** Two cells carrying bare + per-compartment CD3 keys. Cell A is nuclear-high,
     *  cell B is cytoplasm-high; whole-cell mean is identical (50) for both. */
    private static CellIndex twoCellIndex() {
        PathObject a = cell(Map.of(
                "CD3", 50.0, "CD3: Cell: Mean", 50.0,
                "CD3: Nucleus: Mean", 100.0, "CD3: Cytoplasm: Mean", 1.0));
        PathObject b = cell(Map.of(
                "CD3", 50.0, "CD3: Cell: Mean", 50.0,
                "CD3: Nucleus: Mean", 1.0, "CD3: Cytoplasm: Mean", 100.0));
        return CellIndex.build(List.of(a, b), List.of("CD3"));
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

    private static AssignmentResult run(CellIndex index, GateNode gate) {
        MarkerStats stats = MarkerStats.compute(index, allTrue(index.size()));
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);
        return GatingEngine.assignAll(tree, index, stats);
    }

    @Test
    void wholeCellDefaultUnchanged() {
        // Default whole-cell mean (== bare "CD3" = 50 for both) -> both positive at raw t=10.
        GateNode gate = new GateNode("CD3", 10.0);
        gate.setThresholdIsZScore(false);
        gate.setStatistic(Statistic.MEAN);   // whole-cell mean resolves to bare "CD3"
        String[] ph = run(twoCellIndex(), gate).getPhenotypes();
        assertEquals("CD3+", ph[0]);
        assertEquals("CD3+", ph[1]);
    }

    @Test
    void nuclearCompartmentChangesAssignment() {
        // Nuclear: A=100 (>=10) positive, B=1 (<10) negative — differs from whole-cell.
        GateNode gate = new GateNode("CD3", 10.0);
        gate.setThresholdIsZScore(false);
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.MEAN);   // data carries Nucleus Mean, not Median
        String[] ph = run(twoCellIndex(), gate).getPhenotypes();
        assertEquals("CD3+", ph[0]);
        assertEquals("CD3-", ph[1]);
    }

    @Test
    void cytoplasmicCompartmentIsInverse() {
        GateNode gate = new GateNode("CD3", 10.0);
        gate.setThresholdIsZScore(false);
        gate.setCompartment(Compartment.CYTOPLASMIC);
        gate.setStatistic(Statistic.MEAN);   // data carries Cytoplasm Mean, not Median
        String[] ph = run(twoCellIndex(), gate).getPhenotypes();
        assertEquals("CD3-", ph[0]);
        assertEquals("CD3+", ph[1]);
    }

    @Test
    void zScoreUsesResolvedColumnStatsNotBare() {
        // Whole-cell column is [50,50] -> std 0 -> z=0 -> both positive at z>=0.
        // Nuclear column is [100,1] -> A z>0 positive, B z<0 negative.
        // This only holds if z-score is computed on the NUCLEAR column's stats.
        GateNode gate = new GateNode("CD3", 0.0);
        gate.setThresholdIsZScore(true);
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.MEAN);   // data carries Nucleus Mean, not Median
        String[] ph = run(twoCellIndex(), gate).getPhenotypes();
        assertEquals("CD3+", ph[0]);
        assertEquals("CD3-", ph[1]);

        // Sanity: with whole-cell z-score both are positive (std 0 -> z 0 >= 0).
        GateNode wc = new GateNode("CD3", 0.0);
        wc.setThresholdIsZScore(true);
        wc.setStatistic(Statistic.MEAN);   // whole-cell mean resolves to bare "CD3"
        String[] phWc = run(twoCellIndex(), wc).getPhenotypes();
        assertEquals("CD3+", phWc[0]);
        assertEquals("CD3+", phWc[1]);
    }

    @Test
    void quadrantPerAxisCompartments() {
        // X = CD3 nuclear, Y = CD3 cytoplasmic, raw threshold 10.
        // A: nuc=100 (X+), cyto=1 (Y-)  -> "CD3+/CD3-"
        // B: nuc=1   (X-), cyto=100 (Y+) -> "CD3-/CD3+"
        QuadrantGate q = new QuadrantGate("CD3", "CD3", 10.0, 10.0);
        q.setThresholdIsZScore(false);
        q.setCompartmentX(Compartment.NUCLEAR);
        q.setStatisticX(Statistic.MEAN);
        q.setCompartmentY(Compartment.CYTOPLASMIC);
        q.setStatisticY(Statistic.MEAN);

        String[] ph = run(twoCellIndex(), q).getPhenotypes();
        assertEquals("CD3+/CD3-", ph[0]);
        assertEquals("CD3-/CD3+", ph[1]);
    }
}
