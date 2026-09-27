package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two enabled roots on the <b>same</b> channel (CD3), one correction-eligible and one not, per
 * the Global Constraint "two enabled roots, same channel where a per-gate value is involved" —
 * a single-channel pair like {@code GateTreeFixtures.twoRootsOnCd3AndCd8} cannot tell "resolved
 * per gate" apart from "resolved per channel".
 */
class CsvExportJobResolutionTest {

    @Test
    void theSnapshotHoldsTheOpenSlidesAppliedThresholdsPerGate(@TempDir Path dir) {
        GateNode correctedRoot = new GateNode("CD3", 10.0);
        correctedRoot.setStatistic(Statistic.MEAN);
        GateNode uncorrectedRoot = new GateNode("CD3", 20.0);
        uncorrectedRoot.setStatistic(Statistic.MEAN);
        uncorrectedRoot.setCorrectStaining(false);

        GateTree tree = new GateTree();
        tree.addRoot(correctedRoot);
        tree.addRoot(uncorrectedRoot);
        tree.setReferenceSlideId("ref");

        CellIndex index = Cells.of(4).marker("CD3", 1, 2, 3, 4).build();
        Alignment shift = Alignment.between(new Landmarks(1.0, 1.0, Double.NaN), new Landmarks(1.0, 1.3, Double.NaN));

        CsvExportJob.Snapshot snapshot = CsvExportJob.Snapshot.of(dir.resolve("x.csv").toFile(), tree, index,
                MarkerStats.compute(index), null, null, "s1", (s, c) -> "CD3".equals(c) ? shift : null);

        assertEquals(shift.apply(10.0), snapshot.tree().getRoots().get(0).getThreshold(), 1e-12,
                "root A (correction on) resolved to the applied value");
        assertEquals(20.0, snapshot.tree().getRoots().get(1).getThreshold(),
                "root B (correction off) keeps the reference value even though the lookup has one");

        assertEquals(10.0, tree.getRoots().get(0).getThreshold(), "the live tree is untouched");
        assertEquals(20.0, tree.getRoots().get(1).getThreshold(), "the live tree is untouched");
    }
}
