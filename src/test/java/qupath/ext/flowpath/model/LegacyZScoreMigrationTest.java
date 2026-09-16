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

    @Test
    void aChannelThisImageDoesNotCarryIsReportedNotConverted() {
        CellIndex index = index();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(N));
        GateNode missing = legacyThreshold("CD99", 1.5);
        GateTree tree = new GateTree();
        tree.addRoot(missing);

        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);

        assertEquals(0, result.converted());
        assertEquals(List.of(missing), result.unconvertible());
        assertEquals(1.5, missing.getThreshold());
        assertFalse(missing.isThresholdIsZScore());
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
        assertNull(result.message());
        assertEquals(3.0, tree.getRoots().get(0).getThreshold());
    }
}
