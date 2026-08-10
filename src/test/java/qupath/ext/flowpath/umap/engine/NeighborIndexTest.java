package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class NeighborIndexTest {

    /**
     * Reference implementation: sort every point by squared distance and take the
     * first k. Deliberately naive — it is the oracle the optimized scan must match.
     */
    private static int[] bruteForce(double[][] points, double[] query, int k) {
        return IntStream.range(0, points.length)
                .boxed()
                .sorted(Comparator.comparingDouble(i -> squaredDistance(points[i], query)))
                .limit(k)
                .mapToInt(Integer::intValue)
                .toArray();
    }

    private static double squaredDistance(double[] a, double[] b) {
        double sum = 0;
        for (int d = 0; d < a.length; d++) {
            double diff = a[d] - b[d];
            sum += diff * diff;
        }
        return sum;
    }

    private static double[][] gaussian(int n, int dims, long seed) {
        Random rng = new Random(seed);
        double[][] points = new double[n][dims];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < dims; d++) {
                points[i][d] = rng.nextGaussian();
            }
        }
        return points;
    }

    /**
     * The whole point of replacing the KD-tree was to keep exact results while
     * removing the dimension-dependent cliff — so exactness is checked across the
     * dimensionalities a real marker panel spans, including ones that are not
     * multiples of the internal block size (exercising the tail loop).
     */
    @Test
    void matchesBruteForceAcrossDimensions() {
        for (int dims : new int[]{1, 2, 3, 4, 5, 7, 8, 15, 30, 41}) {
            double[][] points = gaussian(400, dims, 1234L + dims);
            NeighborIndex index = new NeighborIndex(points);
            Random rng = new Random(99L + dims);

            for (int trial = 0; trial < 20; trial++) {
                double[] query = new double[dims];
                for (int d = 0; d < dims; d++) query[d] = rng.nextGaussian();

                int k = 5;
                int[] got = new int[k];
                double[] dists = new double[k];
                index.kNearest(query, k, got, dists);

                int[] expected = bruteForce(points, query, k);
                assertArrayEquals(expected, got,
                        "dims=" + dims + " trial=" + trial + " neighbours must match brute force");

                // Distances must be ascending and agree with the reported indices.
                for (int i = 0; i < k; i++) {
                    assertEquals(squaredDistance(points[got[i]], query), dists[i], 1e-12,
                            "reported distance must match the reported index");
                    if (i > 0) {
                        assertTrue(dists[i] >= dists[i - 1], "distances must be ascending");
                    }
                }
            }
        }
    }

    @Test
    void queryingAStoredPointReturnsItselfFirst() {
        double[][] points = gaussian(256, 5, 42L);
        NeighborIndex index = new NeighborIndex(points);

        int k = 5;
        int[] neighbors = new int[k];
        double[] dists = new double[k];
        index.kNearest(points[0], k, neighbors, dists);

        assertEquals(0, neighbors[0], "a stored point must be its own nearest neighbour");
        assertEquals(0.0, dists[0], "…at distance zero");
    }

    /**
     * A constant column carries no distance information. It broke the old KD-tree's
     * node-count bound (the median split failed to shrink the partition); here it
     * must simply be ignored by the distance computation.
     */
    @Test
    void toleratesConstantColumn() {
        int n = 1024, dims = 4;
        double[][] points = gaussian(n, dims, 0L);
        for (int i = 0; i < n; i++) points[i][0] = 1.0;

        NeighborIndex index = new NeighborIndex(points);
        double[] query = points[7].clone();

        int k = 3;
        int[] neighbors = new int[k];
        double[] dists = new double[k];
        assertDoesNotThrow(() -> index.kNearest(query, k, neighbors, dists));
        assertEquals(7, neighbors[0]);
    }

    @Test
    void duplicatePointsAllReportZeroDistance() {
        double[][] points = new double[10][3];
        for (double[] p : points) Arrays.fill(p, 2.5);

        NeighborIndex index = new NeighborIndex(points);
        int k = 4;
        int[] neighbors = new int[k];
        double[] dists = new double[k];
        index.kNearest(new double[]{2.5, 2.5, 2.5}, k, neighbors, dists);

        for (int i = 0; i < k; i++) {
            assertTrue(neighbors[i] >= 0, "all slots must be filled when n > k");
            assertEquals(0.0, dists[i]);
        }
    }

    @Test
    void unfilledSlotsAreMarkedWhenKExceedsPointCount() {
        double[][] points = gaussian(3, 4, 7L);
        NeighborIndex index = new NeighborIndex(points);

        int k = 5;
        int[] neighbors = new int[k];
        double[] dists = new double[k];
        index.kNearest(points[0], k, neighbors, dists);

        for (int i = 0; i < 3; i++) {
            assertTrue(neighbors[i] >= 0, "the 3 real points must occupy the first slots");
        }
        for (int i = 3; i < k; i++) {
            assertEquals(-1, neighbors[i], "surplus slots stay marked unfilled");
            assertEquals(Double.MAX_VALUE, dists[i]);
        }
    }

    @Test
    void emptyIndexFillsNothing() {
        NeighborIndex index = new NeighborIndex(new double[0][]);
        assertEquals(0, index.size());

        int k = 3;
        int[] neighbors = new int[k];
        double[] dists = new double[k];
        assertDoesNotThrow(() -> index.kNearest(new double[]{0, 0}, k, neighbors, dists));
        for (int i = 0; i < k; i++) assertEquals(-1, neighbors[i]);
    }

    @Test
    void concurrentQueriesAgreeWithSerialQueries() {
        double[][] points = gaussian(500, 12, 5L);
        NeighborIndex index = new NeighborIndex(points);
        double[][] queries = gaussian(200, 12, 6L);

        int k = 5;
        int[][] serial = new int[queries.length][k];
        for (int q = 0; q < queries.length; q++) {
            index.kNearest(queries[q], k, serial[q], new double[k]);
        }

        // The projection path queries this index from a parallel stream; a shared
        // read-only index must give identical answers under concurrency.
        int[][] parallel = new int[queries.length][k];
        IntStream.range(0, queries.length).parallel().forEach(q ->
                index.kNearest(queries[q], k, parallel[q], new double[k]));

        for (int q = 0; q < queries.length; q++) {
            assertArrayEquals(serial[q], parallel[q], "query " + q + " differed under concurrency");
        }
    }
}
