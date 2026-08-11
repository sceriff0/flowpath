package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.testing.Cells;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MeasuredColumn} — the value handle that replaced the four-step
 * resolve / materialise / {@code ensureColumn} / read protocol.
 * <p>
 * The point of these tests is not that the arithmetic is new (it is not) but that it is
 * now <em>unconditional</em>: the old protocol let a caller skip registration and get
 * z-score 0.0 — "exactly at the mean" — for a column that had never been summarised.
 */
class MeasuredColumnTest {

    /** Ten cells whose nuclear CD3 rises linearly while whole-cell mean stays flat at 50. */
    private static CellIndex tenCellIndex() {
        return Cells.of(10)
                .marker("CD3", 50.0)
                .marker("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, 50.0)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEAN, i -> 10.0 * i)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, i -> 5.0 * i)
                .area(100.0)
                .build();
    }

    private static MarkerStats statsFor(CellIndex index) {
        return MarkerStats.compute(index, Cells.allTrue(index.size()));
    }

    // ---- same numbers as the old four-step protocol ----

    @Test
    void perCompartmentColumnMatchesTheOldFourStepProtocol() {
        CellIndex index = tenCellIndex();

        // The old protocol, spelled out: resolve, materialise, register, read.
        MarkerStats manual = statsFor(index);
        String key = index.resolvedKey("CD3", Compartment.NUCLEAR, Statistic.MEAN);
        double[] raw = index.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEAN);
        manual.ensureColumn(key, raw);

        // The new one-call path, against a *fresh* MarkerStats so it cannot be riding on
        // the registration the manual path just performed.
        MarkerStats viaHandle = statsFor(index);
        MeasuredColumn col = index.column("CD3", Compartment.NUCLEAR, Statistic.MEAN, viaHandle);

        assertEquals(key, col.key(), "handle must resolve to the same measurement key");
        assertSame(raw, col.values(), "values() must be the backing array, not a copy");

        for (int i = 0; i < index.size(); i++) {
            assertEquals(raw[i], col.valueAt(i), 0.0);
            assertEquals(manual.toZScore(key, raw[i]), col.zScoreAt(i), 1e-12);
        }
        assertEquals(manual.getMean(key), col.mean(), 1e-12);
        assertEquals(manual.getStd(key), col.std(), 1e-12);
        assertEquals(manual.getMin(key), col.min(), 1e-12);
        assertEquals(manual.getMax(key), col.max(), 1e-12);
        assertEquals(manual.getPercentileValue(key, 90), col.percentile(90), 1e-12);
        assertEquals(manual.percentileRankOf(key, raw[3]), col.percentileRankOf(raw[3]), 1e-12);
        assertEquals(raw[7], col.fromZScore(col.zScoreAt(7)), 1e-9);
    }

    @Test
    void medianStatisticResolvesItsOwnColumnNotTheMean() {
        CellIndex index = tenCellIndex();
        MarkerStats stats = statsFor(index);

        MeasuredColumn mean = index.column("CD3", Compartment.NUCLEAR, Statistic.MEAN, stats);
        MeasuredColumn median = index.column("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, stats);

        assertEquals("CD3: Nucleus: Mean", mean.key());
        assertEquals("CD3: Nucleus: Median", median.key());
        assertEquals(90.0, mean.valueAt(9), 1e-9);
        assertEquals(45.0, median.valueAt(9), 1e-9);
        assertNotEquals(mean.std(), median.std(), "each statistic anchors on its own spread");
    }

    // ---- the whole-cell + mean bare-key fallback ----

    @Test
    void wholeCellMeanResolvesToTheBareColumn() {
        CellIndex index = tenCellIndex();
        MarkerStats stats = statsFor(index);

        MeasuredColumn col = index.column("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, stats);

        assertEquals("CD3", col.key(), "the default selection is addressed by the bare marker key");
        assertSame(index.getMarkerValues(index.getMarkerIndex("CD3")), col.values(),
                "the default selection must reuse the pre-built base column");
        assertEquals(50.0, col.valueAt(0), 1e-9);
    }

    @Test
    void nullCompartmentAndStatisticAreTheDefaultSelection() {
        CellIndex index = tenCellIndex();
        MarkerStats stats = statsFor(index);

        MeasuredColumn explicit = index.column("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, stats);
        MeasuredColumn implicit = index.column("CD3", null, null, stats);

        assertEquals(explicit.key(), implicit.key());
        assertSame(explicit.values(), implicit.values());
    }

    @Test
    void legacyBareOnlyMeasurementsStillResolve() {
        // A pre-compartment GeoJSON: a single "CD3" column and nothing structured.
        CellIndex index = Cells.of(4).marker("CD3", i -> 10.0 * i).area(100.0).build();
        MarkerStats stats = statsFor(index);

        MeasuredColumn col = index.column("CD3", Compartment.WHOLE_CELL, Statistic.MEAN, stats);

        assertEquals("CD3", col.key());
        assertEquals(30.0, col.valueAt(3), 1e-9);
        assertEquals(15.0, col.mean(), 1e-9);
    }

    // ---- the defect the type exists to prevent ----

    @Test
    void anUnregisteredColumnCanNoLongerReportZScoreZero() {
        CellIndex index = tenCellIndex();
        MarkerStats stats = statsFor(index);
        String key = index.resolvedKey("CD3", Compartment.NUCLEAR, Statistic.MEAN);

        // What the old protocol did when step 3 was skipped: no exception, just a
        // plausible-looking "this cell is exactly at the mean" for every cell, and
        // percentile clipping that silently no-ops.
        assertFalse(stats.hasColumn(key), "compute() only summarises the bare markers");
        assertEquals(0.0, stats.toZScore(key, 90.0), 0.0,
                "the silent wrong answer this refactor exists to make unreachable");
        assertTrue(Double.isNaN(stats.getPercentileValue(key, 50)));

        // Going through the handle registers the column as part of resolving it.
        MeasuredColumn col = index.column("CD3", Compartment.NUCLEAR, Statistic.MEAN, stats);

        assertTrue(stats.hasColumn(col.key()), "resolving a column registers its statistics");
        assertTrue(col.std() > 1e-10);
        assertTrue(col.hasSpread());
        assertNotEquals(0.0, col.zScoreAt(0), "cell 0 is well below the mean, not at it");
        assertEquals(-1.5666989, col.zScoreAt(0), 1e-6);
        assertFalse(Double.isNaN(col.percentile(50)), "clipping bounds are now usable");
    }

    @Test
    void resolvingRequiresStatistics() {
        CellIndex index = tenCellIndex();
        assertThrows(NullPointerException.class,
                () -> index.column("CD3", Compartment.NUCLEAR, Statistic.MEAN, null));
    }

    // ---- the gate-axis overload ----

    @Test
    void gateAxisOverloadResolvesEachAxisSelection() {
        CellIndex index = tenCellIndex();
        MarkerStats stats = statsFor(index);

        GateNode threshold = new GateNode("CD3", 1.0);
        threshold.setCompartment(Compartment.NUCLEAR);
        threshold.setStatistic(Statistic.MEDIAN);
        assertEquals("CD3: Nucleus: Median", index.column(threshold, 0, stats).key());
        assertNull(index.column(threshold, 1, stats), "a 1D gate has no second axis");

        QuadrantGate quadrant = new QuadrantGate("CD3", "CD3", 0, 0);
        quadrant.setCompartmentX(Compartment.NUCLEAR);
        quadrant.setStatisticX(Statistic.MEAN);
        quadrant.setCompartmentY(Compartment.NUCLEAR);
        quadrant.setStatisticY(Statistic.MEDIAN);
        assertEquals("CD3: Nucleus: Mean", index.column(quadrant, 0, stats).key());
        assertEquals("CD3: Nucleus: Median", index.column(quadrant, 1, stats).key());

        assertNull(index.column(new GateNode(null, 0.0), 0, stats),
                "a gate with no channel resolves to no column");
    }

    @Test
    void gatingRegistersEveryColumnItReads() {
        CellIndex index = tenCellIndex();
        MarkerStats stats = statsFor(index);

        GateNode gate = new GateNode("CD3", 0.0);
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.MEDIAN);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);

        String key = index.resolvedKey("CD3", Compartment.NUCLEAR, Statistic.MEDIAN);
        assertFalse(stats.hasColumn(key));

        GatingEngine.assignAll(tree, index, stats);

        assertTrue(stats.hasColumn(key),
                "the engine must summarise the column it gated on, not the bare marker");
        assertEquals(index.column("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, stats).mean(),
                stats.getMean(key), 1e-12);
    }

    // ---- concurrency ----

    @Test
    void concurrentResolutionOfTheSameColumnAgrees() throws Exception {
        CellIndex index = tenCellIndex();
        MarkerStats stats = statsFor(index);

        // A reference computed the same way, on its own MarkerStats, single-threaded.
        MeasuredColumn reference = index.column("CD3", Compartment.NUCLEAR, Statistic.MEAN,
                statsFor(index));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Callable<MeasuredColumn>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    // Every thread races to resolve-and-register the same column. The
                    // check-then-act race inside ensureColumn is benign by design: two
                    // threads may both compute it, and must compute the same numbers.
                    return index.column("CD3", Compartment.NUCLEAR, Statistic.MEAN, stats);
                });
            }
            List<Future<MeasuredColumn>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            for (Future<MeasuredColumn> f : results) {
                MeasuredColumn col = f.get();
                assertEquals(reference.key(), col.key());
                assertSame(index.getResolvedColumn("CD3", Compartment.NUCLEAR, Statistic.MEAN),
                        col.values(), "all threads must share one cached column");
                assertTrue(col.hasSpread(), "no thread may observe a half-registered column");
                assertEquals(reference.mean(), col.mean(), 1e-12);
                assertEquals(reference.std(), col.std(), 1e-12);
                assertEquals(reference.min(), col.min(), 1e-12);
                assertEquals(reference.max(), col.max(), 1e-12);
                for (int i = 0; i < index.size(); i++) {
                    assertEquals(reference.zScoreAt(i), col.zScoreAt(i), 1e-12);
                }
                assertEquals(reference.percentile(25), col.percentile(25), 1e-12);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentResolutionOfDifferentColumnsStaysIndependent() throws Exception {
        CellIndex index = tenCellIndex();
        MarkerStats stats = statsFor(index);

        MeasuredColumn refMean = index.column("CD3", Compartment.NUCLEAR, Statistic.MEAN, statsFor(index));
        MeasuredColumn refMedian = index.column("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, statsFor(index));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Callable<MeasuredColumn>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Statistic stat = (t % 2 == 0) ? Statistic.MEAN : Statistic.MEDIAN;
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return index.column("CD3", Compartment.NUCLEAR, stat, stats);
                });
            }
            List<Future<MeasuredColumn>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            for (Future<MeasuredColumn> f : results) {
                MeasuredColumn col = f.get();
                MeasuredColumn ref = col.key().endsWith("Mean") ? refMean : refMedian;
                assertEquals(ref.mean(), col.mean(), 1e-12);
                assertEquals(ref.std(), col.std(), 1e-12);
                assertEquals(ref.valueAt(9), col.valueAt(9), 1e-12);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
