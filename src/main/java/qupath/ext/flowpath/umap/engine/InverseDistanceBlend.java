package qupath.ext.flowpath.umap.engine;

/**
 * Places a point at the inverse-distance-weighted mean of neighbours whose positions
 * are already known.
 *
 * <p>Two places in the UMAP engine need exactly this, for the same reason: a cell that
 * did not take part in the layout optimisation still has to land somewhere defensible.
 * <ul>
 *   <li>{@link UmapComputeService} projects the cells held out of a subsample onto the
 *       embedding trained on it.</li>
 *   <li>{@link EmbeddingInitialisation} repairs the one node it detached from the
 *       neighbour graph to keep SMILE off its native initialisation path.</li>
 * </ul>
 * The two arrive by different routes — one queries a {@link NeighborIndex}, the other
 * reads a row straight out of the neighbour graph — but the placement rule must be the
 * same rule, not two spellings of it that can drift apart.
 *
 * <p>Weights are {@code 1 / (distance + eps)}: nearer neighbours dominate, and the
 * epsilon keeps a coincident neighbour (distance exactly zero, which duplicate cells
 * do produce) from turning the weight into an infinity and the mean into a NaN.
 */
final class InverseDistanceBlend {

    /**
     * Guard against a division by zero for a coincident neighbour. Small enough that it
     * does not perturb any real distance, large enough that {@code 1/eps} stays finite.
     */
    private static final double EPSILON = 1e-10;

    private InverseDistanceBlend() {
    }

    /**
     * Write into {@code out} the inverse-distance-weighted mean of the neighbours'
     * positions.
     *
     * @param neighbours indices into {@code positions}; a negative entry is an unfilled
     *                   slot and is skipped, as is any entry equal to {@code exclude}
     * @param distances  Euclidean (not squared) distance to each neighbour, in the same
     *                   order as {@code neighbours}
     * @param positions  {@code [n][d]} known positions
     * @param exclude    an index to ignore, or a negative number to ignore nothing. Used
     *                   when the point being placed may appear in its own neighbour list,
     *                   where blending in its own stale position would be circular.
     * @param out        receives the result; its length decides how many dimensions are
     *                   blended. May alias a row of {@code positions} — the accumulation
     *                   is done off to the side and written only once, at the end.
     * @return true when a position was written; false when no neighbour carried any
     *         weight, in which case {@code out} is left exactly as it was
     */
    static boolean place(int[] neighbours, double[] distances, double[][] positions,
                         int exclude, double[] out) {
        int d = out.length;
        double[] accumulated = new double[d];
        double totalWeight = 0;
        for (int i = 0; i < neighbours.length; i++) {
            int neighbour = neighbours[i];
            if (neighbour < 0 || neighbour == exclude) continue;
            double w = 1.0 / (distances[i] + EPSILON);
            double[] position = positions[neighbour];
            for (int j = 0; j < d; j++) {
                accumulated[j] += w * position[j];
            }
            totalWeight += w;
        }
        if (totalWeight <= 0) return false;
        for (int j = 0; j < d; j++) {
            out[j] = accumulated[j] / totalWeight;
        }
        return true;
    }
}
