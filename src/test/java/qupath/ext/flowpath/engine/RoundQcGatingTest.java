package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestOptions;
import qupath.ext.flowpath.testing.MirageSample;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.Statistic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plan's fixture: MIRAGE's two-cell export. Cell 2 (index 1) lost its nucleus in the
 * [CD3, CD8] round (retention 0.04); with retention >= 0.5 it must be Unmeasured for a CD3 gate
 * only, while PANCK (reference round) still gates it. Two enabled roots, as every
 * per-population change is tested here.
 */
class RoundQcGatingTest {

    static CellIndex index() throws Exception {
        return DetectionIngest.read(MirageSample.cells(), IngestOptions.none()).index();
    }

    static GateTree tree(boolean retentionFilter) {
        GateTree tree = new GateTree();
        QualityFilter f = new QualityFilter();
        if (retentionFilter) f.setMin("qcround/nuclear_retention", 0.5);
        tree.setQualityFilter(f);
        GateNode cd3 = new GateNode("CD3", 100);
        cd3.setStatistic(Statistic.MEAN);
        GateNode panck = new GateNode("PANCK", 100);
        panck.setStatistic(Statistic.MEAN);
        tree.addRoot(cd3);
        tree.addRoot(panck);
        return tree;
    }

    static MarkerStats stats(CellIndex index, GateTree tree) {
        CleanMask clean = CleanMask.of(index, tree.getQualityFilter(), false, List.of());
        return MarkerStats.compute(index, clean.combined(), clean.rounds());
    }

    @Test
    void aCellFailingARoundIsUnmeasuredOnlyForThatRoundsMarkers() throws Exception {
        CellIndex index = index();
        GateTree tree = tree(true);
        GatingEngine.AssignmentResult r = GatingEngine.assignAll(tree, index, stats(index, tree));

        GateNode cd3 = tree.getRoots().get(0);
        GateNode panck = tree.getRoots().get(1);
        // Cell 0: CD3 210 (positive); cell 1: CD3 12, but its CD3 round failed -> counted nowhere.
        assertEquals(1, cd3.getBranches().get(0).getCount());
        assertEquals(0, cd3.getBranches().get(1).getCount(), "the failed cell is not CD3-negative");
        // PANCK came from the reference round: both cells gate (5 negative, 300 positive).
        assertEquals(1, panck.getBranches().get(0).getCount());
        assertEquals(1, panck.getBranches().get(1).getCount());
        assertTrue(r.getUnmeasured()[1]);
        assertFalse(r.getUnmeasured()[0]);
        assertTrue(r.getPhenotypes()[1].contains("PANCK"), r.getPhenotypes()[1]);
    }

    @Test
    void withoutARoundRangeNothingIsMasked() throws Exception {
        CellIndex index = index();
        GateTree tree = tree(false);
        GatingEngine.AssignmentResult r = GatingEngine.assignAll(tree, index, stats(index, tree));
        assertEquals(1, tree.getRoots().get(0).getBranches().get(1).getCount(), "cell 1 is CD3-negative");
        assertFalse(r.getUnmeasured()[1]);
    }

    /** The histogram and percentile clip read the same population the gate counts. */
    @Test
    void statisticsLeaveOutAFailedRoundsCells() throws Exception {
        CellIndex index = index();
        MarkerStats masked = stats(index, tree(true));
        MarkerStats open = stats(index, tree(false));
        var cd3Masked = index.column("CD3", null, Statistic.MEAN, masked);
        var cd3Open = index.column("CD3", null, Statistic.MEAN, open);
        assertEquals(210.0, cd3Masked.mean(), 1e-9);
        assertEquals((210.0 + 12.0) / 2, cd3Open.mean(), 1e-9);
        var panck = index.column("PANCK", null, Statistic.MEAN, masked);
        assertEquals((5.0 + 300.0) / 2, panck.mean(), 1e-9, "a reference-round marker keeps both cells");
    }

    @Test
    void theCsvSignIsBlankForTheFailedRound() throws Exception {
        CellIndex index = index();
        GateTree tree = tree(true);
        GateReadout readout = GateReadout.compile(tree, index, stats(index, tree));
        GateNode cd3 = tree.getRoots().get(0);
        assertEquals(ResolvedGate.UNMEASURED, readout.branchIgnoringClip(cd3, 1));
        assertTrue(readout.branchIgnoringClip(tree.getRoots().get(1), 1) >= 0);
    }

    /** Round QC removes cells from a gate silently unless the readout says how many, and why. */
    @Test
    void eachGateCountsItsUnmeasuredCellsByReason() throws Exception {
        CellIndex index = index();
        GateTree tree = tree(true);
        GatingEngine.assignAll(tree, index, stats(index, tree));
        GateNode cd3 = tree.getRoots().get(0);
        GateNode panck = tree.getRoots().get(1);
        assertEquals(1, cd3.getUnmeasuredCount());
        assertEquals(1, cd3.getUnmeasuredByRoundQc());
        assertEquals(0, cd3.getUnmeasuredSkipped());
        assertEquals(0, panck.getUnmeasuredCount());
    }

    @Test
    void aSkippedSlideAndAMissingValueAreCountedApartFromRoundQc() throws Exception {
        CellIndex index = qupath.ext.flowpath.testing.Cells.of(3)
                .marker("CD3", 1, 200, 300).absentOn(i -> i == 0)
                .marker("PANCK", 5, 6, 7)
                .build();
        GateTree tree = tree(false);
        tree.getRoots().get(1).setSkippedOnSlide(true);
        GatingEngine.assignAll(tree, index, MarkerStats.compute(index));
        GateNode cd3 = tree.getRoots().get(0);
        GateNode panck = tree.getRoots().get(1);
        assertEquals(1, cd3.getUnmeasuredCount(), "no CD3 value on cell 0");
        assertEquals(0, cd3.getUnmeasuredByRoundQc());
        assertEquals(3, panck.getUnmeasuredCount());
        assertEquals(3, panck.getUnmeasuredSkipped(), "a Skip is not blamed on QC");
    }

    /** A new pass starts from zero, like the branch counts. */
    @Test
    void unmeasuredCountsResetEveryPass() throws Exception {
        CellIndex index = index();
        GateTree tree = tree(true);
        GatingEngine.assignAll(tree, index, stats(index, tree));
        tree.getQualityFilter().setRange("qcround/nuclear_retention", QualityFilter.Range.OPEN);
        GatingEngine.assignAll(tree, index, stats(index, tree));
        assertEquals(0, tree.getRoots().get(0).getUnmeasuredCount());
    }
}
