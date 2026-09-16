package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.GateReadout;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.LegacyZScoreMigration;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.testing.Cells;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A gate tree saved while FlowPath still derived its own z-score, end to end: loaded from
 * JSON, migrated once against the loaded cells, and gated and exported with no editor ever
 * opening a gate.
 * <p>
 * The oracle for "classifies identically" is the retired comparison itself, re-stated here
 * and nowhere in {@code src/main}: standardise each axis value through the axis's own
 * resolved column and ask the <em>unmigrated</em> gate's geometry. Every gate type is
 * present, nested, across two enabled roots.
 */
class LegacyZScoreTreeTest {

    @TempDir
    Path tempDir;

    private static final int N = 60;

    /** Three well-spread columns, values chosen so no cell lands exactly on a boundary. */
    private static CellIndex index() {
        return Cells.of(N)
                .marker("A", i -> 10.0 + ((i * 37) % N) * 3.1)
                .marker("B", i -> 200.0 + ((i * 23) % N) * 7.3)
                .marker("C", i -> 5.0 + ((i * 11) % N) * 0.9)
                .area(100.0).build();
    }

    /**
     * Two roots. Root 0: a threshold on A, with a quadrant (B/C) under its positive branch
     * whose ++ branch holds a rectangle (A/B), and an ellipse (B/C) under its negative
     * branch. Root 1: a polygon (A/C) whose inside holds a disabled threshold on C. No
     * statistic is named, so every axis reads the whole-cell mean the fixture writes.
     */
    private static final String LEGACY = """
            {"version": 3, "gates": [
              {"type": "threshold", "channel": "A", "threshold": 0.13, "thresholdIsZScore": true,
               "positiveChildren": [
                 {"type": "quadrant", "channelX": "B", "channelY": "C",
                  "thresholdX": -0.21, "thresholdY": 0.37, "thresholdIsZScore": true,
                  "branches": [
                    {"name": "B+/C+", "children": [
                      {"type": "rectangle", "channelX": "A", "channelY": "B",
                       "minX": -0.4, "maxX": 1.33, "minY": -1.07, "maxY": 0.61,
                       "thresholdIsZScore": true,
                       "branches": [{"name": "rectIn"}, {"name": "rectOut"}]}]},
                    {"name": "B-/C+"}, {"name": "B+/C-"}, {"name": "B-/C-"}]}],
               "negativeChildren": [
                 {"type": "ellipse", "channelX": "B", "channelY": "C",
                  "centerX": 0.11, "centerY": -0.23, "radiusX": 1.17, "radiusY": 0.83,
                  "thresholdIsZScore": true,
                  "branches": [{"name": "ellIn"}, {"name": "ellOut"}]}]},
              {"type": "polygon", "channelX": "A", "channelY": "C", "thresholdIsZScore": true,
               "vertices": [[-1.31, -1.12], [1.27, -0.93], [0.52, 1.41], [-0.77, 0.66]],
               "branches": [
                 {"name": "in", "children": [
                   {"type": "threshold", "channel": "C", "threshold": 0.29, "enabled": false,
                    "thresholdIsZScore": true}]},
                 {"name": "out"}]}
            ]}""";

    private GateTree loadLegacy() throws IOException {
        File file = tempDir.resolve("legacy.json").toFile();
        Files.writeString(file.toPath(), LEGACY, StandardCharsets.UTF_8);
        return FlowPathSerializer.load(file);
    }

    private static void collect(GateNode node, List<GateNode> out) {
        out.add(node);
        for (Branch b : node.getBranches()) {
            for (GateNode child : b.getChildren()) collect(child, out);
        }
    }

    private static List<GateNode> allGates(GateTree tree) {
        List<GateNode> out = new ArrayList<>();
        for (GateNode root : tree.getRoots()) collect(root, out);
        return out;
    }

    /** The retired comparison: z-score each axis through its own column, then the geometry. */
    private static int oldZSpaceBranch(GateNode legacy, CellIndex index, MarkerStats stats, int cell) {
        MeasuredColumn x = index.column(legacy, 0, stats);
        double zx = x.toZScore(x.valueAt(cell));
        if (legacy instanceof QuadrantGate || legacy instanceof Region2DGate) {
            MeasuredColumn y = index.column(legacy, 1, stats);
            return legacy.branchFor(zx, y.toZScore(y.valueAt(cell)));
        }
        return legacy.branchFor(zx, 0.0);
    }

    @Test
    void everyGateTypeClassifiesTheSameCellsAfterMigrationAsItDidInZSpace() throws IOException {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateTree tree = loadLegacy();
        List<GateNode> before = allGates(tree.deepCopy());
        assertEquals(6, before.size());
        assertTrue(before.stream().allMatch(GateNode::isThresholdIsZScore),
                "the legacy file's flag is still read");

        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertEquals(6, result.converted());
        assertTrue(result.unconvertible().isEmpty());
        List<GateNode> after = allGates(tree);
        GateReadout readout = GateReadout.compile(tree, index, stats);
        for (int g = 0; g < after.size(); g++) {
            GateNode migrated = after.get(g);
            assertFalse(migrated.isThresholdIsZScore());
            boolean[] seen = new boolean[migrated.getBranches().size()];
            for (int i = 0; i < N; i++) {
                int expected = oldZSpaceBranch(before.get(g), index, stats, i);
                assertEquals(expected, readout.branchIgnoringClip(migrated, i),
                        migrated.getGateType() + " gate " + g + ", cell " + i);
                seen[expected] = true;
            }
            int populated = 0;
            for (boolean s : seen) if (s) populated++;
            assertTrue(populated >= 2,
                    "gate " + g + " must split the cells, or agreement proves nothing");
        }
    }

    @Test
    void aLegacyTreeExportedWithoutOpeningTheEditorGatesOnRawValues() throws IOException {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateTree legacyCopy = loadLegacy();
        GateTree tree = loadLegacy();

        LegacyZScoreMigration.migrate(tree, index, stats);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File csv = tempDir.resolve("gate_pheno.csv").toFile();
        PhenotypeCsvExporter.export(csv, index, result, tree, stats);

        // A_sign, read back from the CSV: "+" exactly when the retired z-space comparison
        // put the cell inside/above any enabled gate imposed on A -- root 0's threshold, the
        // rectangle's X axis or the polygon's X axis.
        List<GateNode> legacyGates = allGates(legacyCopy);
        List<GateNode> onA = List.of(legacyGates.get(0), legacyGates.get(2), legacyGates.get(4));
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        List<String> header = List.of(lines.get(0).split(","));
        assertFalse(header.stream().anyMatch(h -> h.endsWith("_zscore")),
                "the computed z-score column is retired with the mode: " + header);
        int aSign = header.indexOf("A_sign");
        assertTrue(aSign >= 0, header.toString());
        int plus = 0;
        for (int i = 0; i < N; i++) {
            String[] row = lines.get(i + 1).split(",", -1);
            boolean expected = false;
            for (GateNode g : onA) expected |= oldZSpaceBranch(g, index, stats, i) == 0;
            assertEquals(expected ? "+" : "-", row[aSign], "cell " + i);
            if (expected) plus++;
        }
        assertTrue(plus > 0 && plus < N, "A_sign must split the cells");

        GateNode legacyRoot = legacyGates.get(0);
        // Root 0's split is visible in the phenotype itself: every cell the z-space root
        // called negative carries the ellipse's names (under its negative branch), and every
        // other cell the quadrant's or the rectangle's.
        for (int i = 0; i < N; i++) {
            boolean zPositive = oldZSpaceBranch(legacyRoot, index, stats, i) == 0;
            String phenotype = result.getPhenotypes()[i];
            assertNotNull(phenotype, "cell " + i);
            assertEquals(!zPositive, phenotype.startsWith("ell"),
                    "cell " + i + " phenotype " + phenotype);
        }
    }

    @Test
    void theEngineComparesAStillFlaggedGateAgainstRawValues() {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateNode gate = new GateNode("A", 100.0);
        gate.setStatistic(qupath.ext.flowpath.model.Statistic.MEAN);
        gate.setThresholdIsZScore(true);   // never migrated: the engine has no z-space to honour it
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        MeasuredColumn a = index.column(gate, 0, stats);
        for (int i = 0; i < N; i++) {
            assertEquals(a.valueAt(i) >= 100.0 ? "A+" : "A-", result.getPhenotypes()[i],
                    "cell " + i + " (A = " + a.valueAt(i) + ")");
        }
    }

    @Test
    void aMigratedTreeSavesWithoutTheRetiredFlag() throws IOException {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateTree tree = loadLegacy();
        LegacyZScoreMigration.migrate(tree, index, stats);

        File out = tempDir.resolve("migrated.json").toFile();
        FlowPathSerializer.save(tree, out);

        String text = Files.readString(out.toPath(), StandardCharsets.UTF_8);
        assertFalse(text.contains("thresholdIsZScore"), text);
        assertFalse(LegacyZScoreMigration.needsMigration(FlowPathSerializer.load(out)));
    }
}
