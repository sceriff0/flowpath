package qupath.ext.flowpath.umap.engine;

import java.util.Arrays;

/**
 * Exact k-nearest-neighbour index over a fixed set of points in marker space.
 * Used by {@link UmapComputeService} to project non-sampled cells onto an
 * embedding trained on the subsample.
 *
 * <h2>Why not a KD-tree</h2>
 * <p>This replaces an earlier KD-tree. A KD-tree prunes the far side of a split
 * only when {@code (query[d] - split)^2} already exceeds the current k-th best
 * squared distance. In low dimensions that rejects most of the tree; above
 * roughly 15-20 dimensions it almost never fires, because distance is spread
 * across many axes and no single axis accounts for much of it. Marker panels
 * here run 10-40 dimensions, squarely in the regime where the tree visits
 * nearly every node anyway — paying recursion, an indirection through a
 * permutation array, and a scattered {@code double[][]} row layout for pruning
 * that does not happen.
 *
 * <p>This class does the same exact search with a layout built for it:
 * <ul>
 *   <li><b>Flat backing array.</b> Points live in one contiguous
 *       {@code double[n * dims]} rather than {@code double[n][dims]} (which in
 *       Java is an array of pointers to {@code n} separately-allocated rows).
 *       The scan is then a linear walk of memory the prefetcher can follow, and
 *       the inner distance loop is a shape the JIT auto-vectorizes.</li>
 *   <li><b>Blocked partial distances.</b> The accumulated distance is tested
 *       against the current worst every {@link #BLOCK} dimensions, so a clearly
 *       distant point is abandoned after a few terms instead of all {@code dims}.
 *       Checking per-block rather than per-dimension keeps the inner loop
 *       unrollable.</li>
 *   <li><b>Sorted top-k.</b> {@code k} is small (5 in the projection path), so an
 *       insertion-sorted array beats a heap and keeps the rejection threshold in
 *       {@code outDist[k-1]} — a single read, versus the KD-tree's linear rescan
 *       of the candidate set at every node.</li>
 * </ul>
 *
 * <p>Results are exact: this is a full scan with early abandonment, so it returns
 * the same neighbours a correct KD-tree would, without the dimension-dependent
 * performance cliff.
 *
 * <p>Not thread-safe to build, but {@link #kNearest} is read-only and safe to call
 * concurrently from many threads once constructed.
 */
final class NeighborIndex {

    /**
     * Dimensions accumulated between bound checks. 4 keeps the inner loop short
     * enough to unroll while still abandoning hopeless candidates early; the exact
     * value is not critical, but it should stay a small power of two.
     */
    private static final int BLOCK = 4;

    private final double[] flat;
    private final int n;
    private final int dims;

    /**
     * Build an index over {@code points}.
     * Input must be NaN-free — NaN silently poisons every distance comparison.
     *
     * @param points row-major {@code [numPoints][dims]} matrix (no NaN values)
     */
    NeighborIndex(double[][] points) {
        this.n = points.length;
        this.dims = n > 0 ? points[0].length : 0;
        this.flat = new double[n * dims];
        for (int i = 0; i < n; i++) {
            System.arraycopy(points[i], 0, flat, i * dims, dims);
        }
    }

    /** Number of indexed points. */
    int size() {
        return n;
    }

    /**
     * Find the {@code k} nearest neighbours of {@code query}.
     *
     * <p>On return, {@code outIndices} and {@code outDists} are sorted by ascending
     * distance. Unfilled slots (when {@code k > size()}) hold index {@code -1} and
     * {@code Double.MAX_VALUE}.
     *
     * @param query      the query vector, length {@code dims}
     * @param k          number of neighbours to find
     * @param outIndices output array for neighbour indices into the original points
     * @param outDists   output array for <em>squared</em> distances
     */
    void kNearest(double[] query, int k, int[] outIndices, double[] outDists) {
        Arrays.fill(outDists, 0, k, Double.MAX_VALUE);
        Arrays.fill(outIndices, 0, k, -1);
        if (n == 0 || k <= 0) return;

        // The rejection threshold: nothing worse than the current k-th best can
        // enter the result, so it doubles as the partial-distance bound below.
        double worst = Double.MAX_VALUE;

        for (int i = 0, base = 0; i < n; i++, base += dims) {
            double sum = 0;
            int d = 0;

            // Blocked accumulation with early abandonment.
            for (; d + BLOCK <= dims; d += BLOCK) {
                double a = query[d]     - flat[base + d];
                double b = query[d + 1] - flat[base + d + 1];
                double c = query[d + 2] - flat[base + d + 2];
                double e = query[d + 3] - flat[base + d + 3];
                sum += a * a + b * b + c * c + e * e;
                if (sum >= worst) break;
            }
            if (sum >= worst) continue;

            // Tail dimensions when dims is not a multiple of BLOCK.
            for (; d < dims; d++) {
                double diff = query[d] - flat[base + d];
                sum += diff * diff;
            }
            if (sum >= worst) continue;

            worst = insert(i, sum, k, outIndices, outDists);
        }
    }

    /**
     * Insert a candidate into the ascending-sorted top-k, shifting worse entries
     * right and dropping the last.
     *
     * @return the new rejection threshold (the k-th best distance)
     */
    private static double insert(int index, double dist, int k,
                                 int[] outIndices, double[] outDists) {
        int pos = k - 1;
        while (pos > 0 && outDists[pos - 1] > dist) {
            outDists[pos] = outDists[pos - 1];
            outIndices[pos] = outIndices[pos - 1];
            pos--;
        }
        outDists[pos] = dist;
        outIndices[pos] = index;
        return outDists[k - 1];
    }
}
