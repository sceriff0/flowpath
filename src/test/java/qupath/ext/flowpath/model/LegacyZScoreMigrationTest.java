package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model-level contract of {@link LegacyZScoreMigration}: which gates it visits, what it
 * does to a column it cannot convert through, and what it reports. The end-to-end pin — a
 * legacy JSON tree classifying the same cells after migration — is
 * {@code io.LegacyZScoreTreeTest}.
 */
class LegacyZScoreMigrationTest {

    private static final int N = 10;

    /** A = 1..10 (mean 5.5), B = 10, 20 .. 100, FLAT = 7 everywhere. */
    private static CellIndex index() {
        return Cells.of(N)
                .marker("A", i -> i + 1.0)
                .marker("B", i -> 10.0 * (i + 1))
                .marker("FLAT", 7.0)
                .area(100.0).build();
    }

    private static GateNode legacyThreshold(String channel, double z) {
        GateNode g = new GateNode(channel, z);
        g.setStatistic(Statistic.MEAN);
        g.setThresholdIsZScore(true);
        return g;
    }

    @Test
    void aThresholdIsConvertedThroughItsOwnColumnAndTheFlagCleared() {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        MeasuredColumn a = index.column("A", Compartment.WHOLE_CELL, Statistic.MEAN, stats);
        GateNode gate = legacyThreshold("A", 0.5);
        GateTree tree = new GateTree();
        tree.addRoot(gate);

        assertTrue(LegacyZScoreMigration.needsMigration(tree));
        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertEquals(1, result.converted());
        assertTrue(result.unconvertible().isEmpty());
        assertFalse(gate.isThresholdIsZScore());
        assertEquals(0.5 * a.std() + a.mean(), gate.getThreshold(), 1e-12);
        assertFalse(LegacyZScoreMigration.needsMigration(tree));
    }

    @Test
    void disabledGatesAndDeepDescendantsInEveryRootAreMigrated() {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateNode root0 = legacyThreshold("A", 0.0);
        GateNode disabled = legacyThreshold("B", 1.0);
        disabled.setEnabled(false);
        GateNode deep = legacyThreshold("A", -1.0);
        root0.getNegativeChildren().add(disabled);
        disabled.getPositiveChildren().add(deep);
        GateNode root1 = legacyThreshold("B", 0.0);
        root1.setEnabled(false);
        GateTree tree = new GateTree();
        tree.addRoot(root0);
        tree.addRoot(root1);

        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertEquals(4, result.converted());
        for (GateNode g : List.of(root0, disabled, deep, root1)) {
            assertFalse(g.isThresholdIsZScore(), g.getChannel());
        }
        MeasuredColumn b = index.column("B", Compartment.WHOLE_CELL, Statistic.MEAN, stats);
        assertEquals(b.std() + b.mean(), disabled.getThreshold(), 1e-9,
                "a disabled gate still classifies the moment it is re-enabled");
    }

    @Test
    void aZeroSpreadColumnIsLeftUnconvertedButClearedAndReported() {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateNode flat = legacyThreshold("FLAT", 0.25);
        QuadrantGate halfFlat = new QuadrantGate("A", "FLAT", 0.5, 0.5);
        halfFlat.setStatisticX(Statistic.MEAN);
        halfFlat.setStatisticY(Statistic.MEAN);
        halfFlat.setThresholdIsZScore(true);
        GateNode ok = legacyThreshold("A", 0.0);
        GateTree tree = new GateTree();
        tree.addRoot(flat);
        tree.addRoot(halfFlat);
        tree.addRoot(ok);

        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertEquals(1, result.converted());
        assertEquals(List.of(flat, halfFlat), result.unconvertible());
        assertFalse(flat.isThresholdIsZScore());
        assertFalse(halfFlat.isThresholdIsZScore());
        assertEquals(0.25, flat.getThreshold(), "nothing to convert through, so left alone");
        assertEquals(0.5, halfFlat.getThresholdX(),
                "all or nothing: one convertible axis does not move a 2D gate half-way");
        assertEquals(0.5, halfFlat.getThresholdY());
        String message = result.message();
        assertTrue(message.contains("Converted 1 gate"), message);
        assertTrue(message.contains("2 gates could not be converted"), message);
        assertTrue(message.contains("FLAT") && message.contains("A/FLAT"), message);
    }

    /**
     * A channel this image lacks keeps the flag: the engine cannot use the gate here anyway,
     * and clearing it would make the saved tree gate on standard deviations as intensities on
     * a slide that does carry the channel. Opened against such an index, it converts.
     */
    @Test
    void aChannelThisImageDoesNotCarryKeepsTheFlagAndConvertsWhereItExists() {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateNode missing = legacyThreshold("CD99", 1.5);
        QuadrantGate halfMissing = new QuadrantGate("FLAT", "CD99", 0.5, 0.5);
        halfMissing.setThresholdIsZScore(true);
        GateTree tree = new GateTree();
        tree.addRoot(missing);
        tree.addRoot(halfMissing);

        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertEquals(0, result.converted());
        assertTrue(result.unconvertible().isEmpty(),
                "a missing channel wins over a flat column on the other axis");
        assertEquals(List.of(missing, halfMissing), result.missingChannel());
        assertFalse(result.changedTree());
        assertEquals(1.5, missing.getThreshold());
        assertTrue(missing.isThresholdIsZScore(), "the flag survives on this image");
        assertTrue(halfMissing.isThresholdIsZScore());
        assertTrue(LegacyZScoreMigration.needsMigration(tree));
        assertTrue(result.message().contains("CD99"), result.message());

        // Found again, unchanged, on a repeat call against the same image.
        LegacyZScoreMigration.Result again = LegacyZScoreMigration.migrate(tree, index, stats);
        assertEquals(result.message(), again.message());
        assertFalse(again.changedTree());

        // Another slide carries CD99: now it converts through that column.
        CellIndex other = Cells.of(N).marker("CD99", i -> 2.0 * i).area(100.0).build();
        MarkerStats otherStats = MarkerStats.compute(other, Cells.allTrue(N));
        MeasuredColumn cd99 = other.column("CD99", Compartment.WHOLE_CELL, Statistic.MEAN, otherStats);
        GateTree single = new GateTree();
        single.addRoot(missing);
        LegacyZScoreMigration.Result there = LegacyZScoreMigration.migrate(single, other, otherStats);
        assertEquals(1, there.converted());
        assertFalse(missing.isThresholdIsZScore());
        assertEquals(1.5 * cd99.std() + cd99.mean(), missing.getThreshold(), 1e-9);
    }

    @Test
    void aRegionWithNoShapeMigratesWithoutAColumnToConvertThrough() {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        RectangleGate cleared = new RectangleGate("FLAT", "FLAT", 0, 0, 0, 0);
        cleared.setThresholdIsZScore(true);
        PolygonGate empty = new PolygonGate();
        empty.setChannelX("FLAT");
        empty.setChannelY("A");
        empty.setThresholdIsZScore(true);
        GateTree tree = new GateTree();
        tree.addRoot(cleared);
        tree.addRoot(empty);

        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertEquals(2, result.converted());
        assertTrue(result.unconvertible().isEmpty());
        assertEquals(0.0, cleared.getMaxX(), "a cleared rectangle stays cleared");
    }

    @Test
    void aTreeWithNoFlaggedGateReportsNothing() {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("A", 3.0));

        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertTrue(result.isEmpty());
        assertFalse(result.changedTree());
        assertNull(result.message());
        assertEquals(3.0, tree.getRoots().get(0).getThreshold());
    }
}
