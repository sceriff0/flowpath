package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot isolation: an export run against a {@link CsvExportJob.Snapshot} must reflect the
 * tree exactly as it was when the snapshot was taken, not any edit made afterwards. This is
 * the toolkit-free half of Task 4 -- {@code CsvExportCoordinatorTest} covers the
 * background/FX-thread orchestration built on top of {@link CsvExportJob#run}.
 */
class CsvExportJobTest {

    @TempDir
    Path tempDir;

    /** Parse a CSV line respecting quoted fields, as {@code PhenotypeCsvExporterTest} does. */
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
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /**
     * Two enabled roots on two channels, so the snapshot really is exercised across more than
     * a single-root tree. Cell 1 sits right where mutating either threshold after the
     * snapshot is taken would flip its sign.
     */
    private static GateTree twoRoots(double cd3Threshold, double cd8Threshold) {
        GateNode cd3Root = new GateNode("CD3", cd3Threshold);
        cd3Root.setStatistic(Statistic.MEAN);
        GateNode cd8Root = new GateNode("CD8", cd8Threshold);
        cd8Root.setStatistic(Statistic.MEAN);

        GateTree tree = new GateTree();
        tree.addRoot(cd3Root);
        tree.addRoot(cd8Root);
        return tree;
    }

    @Test
    void mutatingTheLiveTreeAfterTheSnapshotIsTakenDoesNotReachTheFile() throws IOException {
        CellIndex index = Cells.of(4)
                .marker("CD3", 1.0, 4.0, 6.0, 9.0)
                .marker("CD8", 1.0, 4.0, 6.0, 9.0)
                .area(50.0)
                .build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));

        GateTree tree = twoRoots(5.5, 2.5);
        GateNode cd3Root = tree.getRoots().get(0);
        GateNode cd8Root = tree.getRoots().get(1);
        assertTrue(cd3Root.isEnabled() && cd8Root.isEnabled(), "both roots enabled");

        File file = tempDir.resolve("snapshot-isolation.csv").toFile();
        CsvExportJob.Snapshot snapshot =
                CsvExportJob.Snapshot.of(file, tree, index, stats, null, null);

        // Edits made to the LIVE tree after the snapshot was taken -- neither must reach the
        // file. Cell index 1 (CD3 = CD8 = 4.0) is negative at the original thresholds (5.5,
        // 2.5) and would be positive at the mutated ones (1.0, 100.0).
        cd3Root.setThreshold(1.0);
        cd8Root.setThreshold(100.0);

        CsvExportJob.run(snapshot);

        List<String> lines = Files.readAllLines(file.toPath());
        List<String> header = parseCsvLine(lines.get(0));
        int cd3SignCol = header.indexOf("CD3_sign");
        int cd8SignCol = header.indexOf("CD8_sign");
        assertTrue(cd3SignCol >= 0 && cd8SignCol >= 0, header.toString());

        List<String> cellOneRow = parseCsvLine(lines.get(2)); // cell_id 1, header is line 0
        assertEquals("-", cellOneRow.get(cd3SignCol),
                "reflects the snapshot's CD3 threshold (5.5), not the mutated one (1.0)");
        assertEquals("+", cellOneRow.get(cd8SignCol),
                "reflects the snapshot's CD8 threshold (2.5), not the mutated one (100.0)");

        // The live tree itself was in fact mutated -- proving the isolation is in the
        // snapshot, not that the mutation silently failed.
        assertEquals(1.0, cd3Root.getThreshold(), 0.0);
        assertEquals(100.0, cd8Root.getThreshold(), 0.0);
    }

    @Test
    void theSnapshotDeepCopiesTheTreeRatherThanReferencingTheLiveOne() {
        GateTree tree = twoRoots(5.5, 2.5);
        CellIndex index = Cells.of(2).marker("CD3", 1.0, 2.0).marker("CD8", 1.0, 2.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(2));

        CsvExportJob.Snapshot snapshot = CsvExportJob.Snapshot.of(
                tempDir.resolve("unused.csv").toFile(), tree, index, stats, null, null);

        assertNotSame(tree, snapshot.tree(), "the tree is copied, not aliased");
        assertNotSame(tree.getRoots().get(0), snapshot.tree().getRoots().get(0));
    }

    @Test
    void aNullRoiMaskStaysNull() {
        GateTree tree = twoRoots(5.5, 2.5);
        CellIndex index = Cells.of(2).marker("CD3", 1.0, 2.0).marker("CD8", 1.0, 2.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(2));

        CsvExportJob.Snapshot snapshot = CsvExportJob.Snapshot.of(
                tempDir.resolve("unused.csv").toFile(), tree, index, stats, null, null);

        assertNull(snapshot.roiMask());
    }

    @Test
    void theRoiMaskArrayIsClonedNotAliased() {
        GateTree tree = twoRoots(5.5, 2.5);
        CellIndex index = Cells.of(2).marker("CD3", 1.0, 2.0).marker("CD8", 1.0, 2.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(2));
        boolean[] liveMask = {true, false};

        CsvExportJob.Snapshot snapshot = CsvExportJob.Snapshot.of(
                tempDir.resolve("unused.csv").toFile(), tree, index, stats, liveMask, null);

        liveMask[1] = true; // mutate the live array after the snapshot was taken
        assertFalse(snapshot.roiMask()[1], "the snapshot holds its own copy of the mask");
    }

    @Test
    void aFailingWriteThrowsRatherThanSwallowing() {
        GateTree tree = twoRoots(5.5, 2.5);
        CellIndex index = Cells.of(2).marker("CD3", 1.0, 2.0).marker("CD8", 1.0, 2.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(2));

        // A directory in place of a file: the write must fail, not silently no-op.
        File asDirectory = tempDir.toFile();
        CsvExportJob.Snapshot snapshot =
                CsvExportJob.Snapshot.of(asDirectory, tree, index, stats, null, null);

        assertThrows(IOException.class, () -> CsvExportJob.run(snapshot));
    }
}
