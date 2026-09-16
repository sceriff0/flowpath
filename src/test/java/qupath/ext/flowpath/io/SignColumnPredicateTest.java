package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.GateReadout;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;
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
 * The CSV {@code _sign} column is read off {@code ResolvedGate.branchOf}, the one gate
 * predicate, for every cell and every column — not off a comparison of its own.
 * <p>
 * The fixture is chosen to exercise the places a second implementation diverged: two
 * enabled roots on the <em>same</em> channel, a quadrant whose Y axis is unmeasured on some
 * cells (so the gate cannot judge them at all), a region gate, outlier clipping, and a
 * disabled subtree that must contribute nothing.
 */
class SignColumnPredicateTest {

    @TempDir
    Path tempDir;

    private static final int N = 20;

    private static CellIndex index() {
        return Cells.of(N)
                .marker("CD45", i -> i == 3 ? Double.NaN : i + 1.0)
                .marker("CD3", i -> i % 5 == 0 ? Double.NaN : 40.0 - 2.0 * i)
                .marker("CD8", i -> (i * 7) % N + 0.5)
                .area(100.0).build();
    }

    private static <G extends GateNode> G mean(G gate) {
        if (gate instanceof QuadrantGate q) {
            q.setStatisticX(Statistic.MEAN);
            q.setStatisticY(Statistic.MEAN);
        } else if (gate instanceof Region2DGate r) {
            r.setStatisticX(Statistic.MEAN);
            r.setStatisticY(Statistic.MEAN);
        } else {
            gate.setStatistic(Statistic.MEAN);
        }
        return gate;
    }

    private static GateTree tree() {
        GateNode rootA = mean(new GateNode("CD45", 10.5));
        rootA.setExcludeOutliers(true);
        rootA.setClipPercentileLow(10.0);
        rootA.setClipPercentileHigh(90.0);
        QuadrantGate quad = mean(new QuadrantGate("CD8", "CD3", 9.0, 20.0));
        rootA.getPositiveChildren().add(quad);

        GateNode rootB = mean(new GateNode("CD45", 15.5));   // same channel as root A
        RectangleGate rect = mean(new RectangleGate("CD3", "CD8", 5.0, 25.0, 2.0, 12.0));
        rootB.getNegativeChildren().add(rect);

        GateNode disabled = mean(new GateNode("CD8", 100.0));
        disabled.setEnabled(false);
        disabled.getPositiveChildren().add(mean(new GateNode("CD3", -100.0)));
        rootB.getPositiveChildren().add(disabled);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(rootA);
        tree.addRoot(rootB);
        return tree;
    }

    private record Cut(GateNode gate, int axis) {}

    private static void enabledCuts(GateNode node, CellIndex index, MarkerStats stats,
                                    String key, List<Cut> out) {
        if (!node.isEnabled()) return;
        for (int axis = 0; axis < node.getChannels().size(); axis++) {
            if (index.column(node, axis, stats).key().equals(key)) out.add(new Cut(node, axis));
        }
        for (Branch b : node.getBranches()) {
            for (GateNode child : b.getChildren()) enabledCuts(child, index, stats, key, out);
        }
    }

    /** Branch index to per-axis positivity, spelled out per gate type. */
    private static boolean positive(GateNode gate, int branch, int axis) {
        if (gate instanceof QuadrantGate) {
            return axis == 0 ? (branch == 0 || branch == 2) : (branch == 0 || branch == 1);
        }
        return branch == 0;   // threshold: positive; region: inside
    }

    @Test
    void everySignIsTheEnginesBranchReadPerAxis() throws IOException {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateTree tree = tree();
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File csv = tempDir.resolve("signs.csv").toFile();
        PhenotypeCsvExporter.export(csv, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        List<String> header = List.of(lines.get(0).split(",", -1));
        GateReadout readout = GateReadout.compile(tree, index, stats);

        int checked = 0;
        for (String column : List.of("CD45", "CD3", "CD8")) {
            int col = header.indexOf(column + "_sign");
            assertTrue(col >= 0, header.toString());
            List<Cut> cuts = new ArrayList<>();
            for (GateNode root : tree.getRoots()) enabledCuts(root, index, stats, column, cuts);
            assertFalse(cuts.isEmpty(), column + " is gated");
            for (int i = 0; i < N; i++) {
                boolean judged = false;
                boolean plus = false;
                for (Cut cut : cuts) {
                    int branch = readout.branchIgnoringClip(cut.gate(), i);
                    if (branch == GateReadout.UNMEASURED) continue;
                    judged = true;
                    plus |= positive(cut.gate(), branch, cut.axis());
                }
                String expected = plus ? "+" : judged ? "-" : "";
                assertEquals(expected, lines.get(i + 1).split(",", -1)[col],
                        column + "_sign, cell " + i);
                checked++;
            }
        }
        assertEquals(3 * N, checked);
    }

    @Test
    void signsOnKnownCells() throws IOException {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateTree tree = tree();
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File csv = tempDir.resolve("known.csv").toFile();
        PhenotypeCsvExporter.export(csv, index, result, tree, stats);

        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        List<String> header = List.of(lines.get(0).split(",", -1));
        int cd45 = header.indexOf("CD45_sign");
        int cd3 = header.indexOf("CD3_sign");

        // CD45 = 20 on cell 19: above both same-channel roots, and outlier-clipped by root A
        // (above its 90th percentile) -- a clipped cell still has its real branch.
        assertEquals("+", lines.get(20).split(",", -1)[cd45]);
        assertTrue(result.getOutlier()[19], "the fixture must clip cell 19");
        // CD45 = 5 on cell 4: below both.
        assertEquals("-", lines.get(5).split(",", -1)[cd45]);
        // CD45 unmeasured on cell 3: no gate can judge it, so blank rather than "-".
        assertEquals("", lines.get(4).split(",", -1)[cd45]);
        // CD3 unmeasured on cell 10: the quadrant and the rectangle both need it -> blank.
        assertEquals("", lines.get(11).split(",", -1)[cd3]);
        assertTrue(result.getUnmeasured()[10]);
    }
}
