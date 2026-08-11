package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.io.PhenotypeCsvExporter;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResolvedGate#branchOf} is meant to be the <em>only</em> implementation of "which
 * branch does this cell fall into". These tests pin that: over a randomised population,
 * every consumer that has an opinion about a cell's branch — the tree walk that writes
 * phenotypes and branch counts, and the CSV {@code _sign} column — must agree with it.
 * <p>
 * A second implementation reappearing anywhere shows up here as a mismatch on the first
 * cell where the two spellings differ, which is normally a boundary case.
 */
class SinglePredicateTest {

    private static final List<String> MARKERS = List.of("A", "B");

    private static CellIndex randomIndex(int n, long seed) {
        Random rng = new Random(seed);
        // Drawn cell-major so the sequence is identical to the per-cell loop this replaced;
        // a different draw order would silently be a different population.
        double[][] values = new double[MARKERS.size()][n];
        for (int i = 0; i < n; i++) {
            for (int m = 0; m < MARKERS.size(); m++) {
                // A third of the values land on a coarse grid so cells sit exactly on gate
                // boundaries often enough to matter.
                values[m][i] = i % 3 == 0 ? Math.round(rng.nextGaussian() * 2) / 2.0 : rng.nextGaussian() * 2;
            }
        }
        return Cells.columns(MARKERS, values).atGrid(3, 5).build();
    }

    /** Unique branch names, and axes pinned to the bare marker column the index carries. */
    private static <T extends GateNode> T prepare(T gate, boolean zScore) {
        List<Branch> branches = gate.getBranches();
        for (int i = 0; i < branches.size(); i++) {
            branches.get(i).setName("B" + i);
        }
        gate.setThresholdIsZScore(zScore);
        if (gate instanceof Region2DGate region) {
            region.setCompartmentX(Compartment.WHOLE_CELL); region.setStatisticX(Statistic.MEAN);
            region.setCompartmentY(Compartment.WHOLE_CELL); region.setStatisticY(Statistic.MEAN);
        } else if (gate instanceof QuadrantGate quad) {
            quad.setCompartmentX(Compartment.WHOLE_CELL); quad.setStatisticX(Statistic.MEAN);
            quad.setCompartmentY(Compartment.WHOLE_CELL); quad.setStatisticY(Statistic.MEAN);
        } else {
            gate.setCompartment(Compartment.WHOLE_CELL);
            gate.setStatistic(Statistic.MEAN);
        }
        return gate;
    }

    private static List<GateNode> everyGateType() {
        List<GateNode> gates = new ArrayList<>();
        gates.add(new GateNode("A", 0.25));
        gates.add(new QuadrantGate("A", "B", 0.5, -0.5));
        gates.add(new RectangleGate("A", "B", -1, 1, -1, 1));
        gates.add(new EllipseGate("A", "B", 0, 0, 1.5, 0.75));
        PolygonGate poly = new PolygonGate("A", "B");
        poly.setVertices(List.of(new double[]{-2, -2}, new double[]{2, -2},
                new double[]{2, 1}, new double[]{0, 2}, new double[]{-2, 1}));
        gates.add(poly);
        return gates;
    }

    // ---- the walk ----

    @Test
    void theTreeWalkAgreesWithBranchOfForEveryGateTypeAndBothCoordinateSpaces() {
        CellIndex index = randomIndex(500, 4242L);

        for (boolean zScore : new boolean[]{false, true}) {
            for (GateNode gate : everyGateType()) {
                prepare(gate, zScore);
                GateTree tree = new GateTree();
                tree.setQualityFilter(null);   // outliers here must come only from gate clipping
                tree.addRoot(gate);
                MarkerStats stats = MarkerStats.compute(index);

                // The predicate, compiled exactly the way assignAll compiles it.
                ResolvedGate rg = ResolvedGate.compile(tree.getRoots(), index, stats, null).get(0);
                GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

                int[] expectedCounts = new int[gate.getBranches().size()];
                for (int i = 0; i < index.size(); i++) {
                    int branch = rg.branchOf(i);
                    String where = gate.getGateType() + (zScore ? " (z)" : " (raw)") + " cell " + i;
                    assertEquals(branch < 0, result.getOutlier()[i], where + ": outlier flag");
                    int landed = branch < 0 ? rg.branchIgnoringClip(i) : branch;
                    assertEquals(gate.getBranches().get(landed).getName(), result.getPhenotypes()[i],
                            where + ": phenotype");
                    if (branch >= 0) expectedCounts[landed]++;
                }
                for (int b = 0; b < expectedCounts.length; b++) {
                    assertEquals(expectedCounts[b], gate.getBranches().get(b).getCount(),
                            gate.getGateType() + " branch " + b + " count");
                }
            }
        }
    }

    @Test
    void outlierClippingStillLandsTheCellInTheBranchThePredicateNames() {
        CellIndex index = randomIndex(400, 99L);
        GateNode gate = prepare(new GateNode("A", 0.0), false);
        gate.setExcludeOutliers(true);
        gate.setClipPercentileLow(20);
        gate.setClipPercentileHigh(80);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);   // outliers here must come only from gate clipping
        tree.addRoot(gate);
        MarkerStats stats = MarkerStats.compute(index);
        ResolvedGate rg = ResolvedGate.compile(tree.getRoots(), index, stats, null).get(0);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        int clipped = 0;
        for (int i = 0; i < index.size(); i++) {
            int branch = rg.branchOf(i);
            if (branch < 0) {
                clipped++;
                assertTrue(result.getOutlier()[i], "cell " + i + " should be flagged as an outlier");
                assertTrue(result.getExcluded()[i], "cell " + i + " should be excluded");
                // Still labelled, so the CSV has a phenotype for the row.
                assertEquals(gate.getBranches().get(rg.branchIgnoringClip(i)).getName(),
                        result.getPhenotypes()[i], "clipped cell " + i + " phenotype");
            }
        }
        assertTrue(clipped > 0, "the 20-80 clip range should have rejected some cells");
    }

    // ---- the CSV sign column ----

    @Test
    void csvSignAgreesWithBranchOfForAOneDimensionalCut() throws IOException {
        assertSignMatchesBranch(prepare(new GateNode("A", 0.4), false), "A");
        assertSignMatchesBranch(prepare(new GateNode("A", 0.4), true), "A");
    }

    @Test
    void csvSignAgreesWithBranchOfForARegionGate() throws IOException {
        assertSignMatchesBranch(prepare(new RectangleGate("A", "B", -1, 1, -1, 1), false), "A");
        assertSignMatchesBranch(prepare(new EllipseGate("A", "B", 0, 0, 1.5, 0.75), true), "B");
    }

    @Test
    void csvSignAgreesWithBranchOfForAQuadrantAxis() throws IOException {
        assertSignMatchesBranch(prepare(new QuadrantGate("A", "B", 0.5, -0.5), false), "A");
        assertSignMatchesBranch(prepare(new QuadrantGate("A", "B", 0.5, -0.5), false), "B");
    }

    /**
     * Export the CSV and check the {@code <marker>_sign} column against the predicate.
     * <p>
     * "+" means positive on <em>that axis</em>: branch 0 for a threshold or region gate,
     * and for a quadrant the two of the four branches that sit on the positive side of the
     * axis being checked.
     */
    private void assertSignMatchesBranch(GateNode gate, String marker) throws IOException {
        CellIndex index = randomIndex(300, 7L);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);   // outliers here must come only from gate clipping
        tree.addRoot(gate);
        MarkerStats stats = MarkerStats.compute(index);
        ResolvedGate rg = ResolvedGate.compile(tree.getRoots(), index, stats, null).get(0);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        File csv = Files.createTempFile("flowpath-sign", ".csv").toFile();
        csv.deleteOnExit();
        PhenotypeCsvExporter.export(csv, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csv.toPath());
        List<String> header = Arrays.asList(lines.get(0).split(",", -1));
        int signCol = header.indexOf(marker + "_sign");
        assertTrue(signCol >= 0, "no " + marker + "_sign column in " + header);

        int axis = gate.getChannels().indexOf(marker);
        for (int i = 0; i < index.size(); i++) {
            String sign = lines.get(i + 1).split(",", -1)[signCol];
            boolean expectPositive = positiveOnAxis(gate, rg.branchOf(i), axis);
            assertEquals(expectPositive ? "+" : "-", sign,
                    gate.getGateType() + " cell " + i + ": " + marker + "_sign");
        }
    }

    private static boolean positiveOnAxis(GateNode gate, int branch, int axis) {
        if (gate instanceof QuadrantGate) {
            // Branches are PP, NP, PN, NN.
            return axis == 0 ? (branch == 0 || branch == 2) : (branch == 0 || branch == 1);
        }
        return branch == 0;
    }
}
