package qupath.ext.flowpath.umap.engine;

import smile.graph.NearestNeighborGraph;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * Which embedding initialisation SMILE will actually perform for a neighbour graph, and
 * what has to change about that graph to make it the pure-Java one.
 *
 * <h2>The decision this owns</h2>
 * SMILE's {@code UMAP.fit} does not take an initialisation argument. It picks one, from
 * two properties of the graph handed to it and nothing else: the number of vertices, and
 * whether the undirected neighbour graph is a single connected component. A small,
 * connected graph gets a <em>spectral</em> layout; anything else gets a PCA layout
 * computed in pure Java. There is no option, no overload and no exception handler to
 * reach past it — the branch is a private {@code if} in the middle of {@code fit}.
 *
 * <p>That matters because the spectral layout is not merely slower: it calls through to
 * ARPACK, a native library. FlowPath ships no natives, and cannot: Maven Central
 * publishes ARPACK binaries for four platforms and {@code macosx-arm64} is not among
 * them, so on an Apple-silicon machine the dependency route ends in an
 * {@code UnsatisfiedLinkError} instead of a {@code NoClassDefFoundError} — 11MB heavier
 * and just as broken. Every UMAP a user could plausibly run first (a few hundred cells,
 * one tissue region, well-mixed markers) landed squarely in the spectral branch. "I
 * cannot even start the UMAP" was not a UI bug. It was this.
 *
 * <p>So FlowPath makes the decision itself. It already builds the graph it hands to
 * SMILE, which means it controls both inputs to SMILE's hidden branch. This class reads
 * the branch the way SMILE reads it, and when the answer would be spectral, hands back a
 * graph that answers PCA instead.
 *
 * <h2>What steering costs, and why it is repaid</h2>
 * The graph is made to answer "PCA" by detaching exactly one node: its own neighbour
 * entries become self-loops, and every other row that referenced it has that reference
 * replaced. The graph then has two connected components, {@code cc.length != 1}, and
 * SMILE takes the pure-Java branch for all {@code n} cells.
 *
 * <p>The detached node pays most of it. Carrying no edges into the fuzzy simplicial set,
 * it receives no attractive updates during layout optimisation — only the repulsion of
 * negative sampling — and drifts to wherever that pushes it. Its coordinates after
 * {@code fit} are meaningless, so {@link #impute} discards them and recomputes the
 * position from the node's <em>true</em> k nearest neighbours in the original,
 * un-perturbed graph, weighted by inverse distance. That is the same placement rule
 * {@link UmapComputeService} uses for cells held out of a subsample, shared through
 * {@link InverseDistanceBlend} rather than written twice.
 *
 * <p>It is not the only cell affected, and saying otherwise would be an understatement in
 * an instrument people draw conclusions from. Every row that listed the detached node has
 * its distance vector rewritten (see {@link #detach}), and {@code smoothKnnDist} reads a
 * row's <em>whole</em> distance vector to pick that row's sigma — so those rows carry
 * slightly different membership strengths than they would have. Measured at N=500, k=15
 * on four seeds: 15 to 22 rows, 3–4.5% of cells. The effect is second-order (an edge
 * weight, not a position, and no cell is moved by it) but it is real, so
 * {@link #reweightedRows()} counts it and the count travels on the outcome beside the
 * imputed cell rather than being left for someone to rediscover.
 *
 * <p>The node is chosen as the one with the smallest total distance to its k neighbours
 * — the deepest point of the densest region. Two reasons, both about damage. Its
 * neighbours are close and mutually close, so the imputed position is tightly
 * constrained rather than an average over a sparse neighbourhood. And a dense region is
 * exactly where the graph has redundant paths, so removing one node's edges cannot
 * disconnect anything that was connected through it. The choice is a pure function of
 * the graph: no {@code Math.random}, no clock, no iteration order. An embedding that
 * moved between two identical runs would be worse than one that failed, because the user
 * would have no way to tell which of the two they were looking at.
 *
 * <h2>When nothing needs steering</h2>
 * Above {@link #SPECTRAL_CANDIDATE_LIMIT} vertices SMILE does not even test connectivity
 * — it goes straight to PCA — and a graph that already has more than one component is
 * likewise safe. In both cases {@link #graph()} returns the original object unchanged,
 * {@link #detachedNode()} is empty and {@link #impute} does nothing. Steering is applied
 * only where it is the difference between an embedding and a stack trace.
 */
final class EmbeddingInitialisation {

    /**
     * SMILE's {@code UMAP.LARGE_DATA_SIZE}. At or below this many vertices SMILE tests
     * the graph for connectivity and will attempt a spectral layout if it is connected;
     * above it, it does not test and never attempts one.
     *
     * <p>The count is the <em>training</em> size — the subsample actually handed to
     * {@code fit} — not the number of cells in the image. A 200,000-cell image
     * subsampled to 8,000 is a small graph as far as this decision is concerned.
     */
    static final int SPECTRAL_CANDIDATE_LIMIT = 10_000;

    /** The initialisation SMILE performs for a given graph. */
    enum Route {
        /**
         * Spectral layout via {@code ARPACK.syev}. Native, absent here, and fatal:
         * {@code NoClassDefFoundError: org/bytedeco/arpackng/global/arpack}.
         */
        SPECTRAL,
        /** PCA layout. Pure Java, deterministic, and the only one that works here. */
        PCA
    }

    /** The perturbed graph, paired with the size of the perturbation. */
    private record Detached(NearestNeighborGraph graph, int reweightedRows) {}

    private final NearestNeighborGraph original;
    private final Route natural;
    private final NearestNeighborGraph steered;
    private final int detached;
    private final int reweightedRows;

    private EmbeddingInitialisation(NearestNeighborGraph original, Route natural,
                                    NearestNeighborGraph steered, int detached,
                                    int reweightedRows) {
        this.original = original;
        this.natural = natural;
        this.steered = steered;
        this.detached = detached;
        this.reweightedRows = reweightedRows;
    }

    /**
     * Read SMILE's branch for {@code nng} and, if it would be spectral, prepare the
     * steered graph that makes it PCA.
     *
     * <p>Connectivity is computed exactly as SMILE computes it,
     * {@code nng.graph(false).bfcc()}, so the prediction cannot disagree with the
     * behaviour. That does mean the traversal runs twice, once here and once inside
     * {@code fit}. It is a breadth-first sweep over at most
     * {@link #SPECTRAL_CANDIDATE_LIMIT} vertices, milliseconds against a layout
     * optimisation measured in seconds, and the alternative is predicting the branch by a
     * cheaper proxy that could be wrong — which is the whole failure being fixed.
     */
    static EmbeddingInitialisation forGraph(NearestNeighborGraph nng) {
        Route natural = routeFor(nng);
        if (natural == Route.PCA) {
            return new EmbeddingInitialisation(nng, natural, nng, -1, 0);
        }
        int detached = densestNode(nng);
        Detached steered = detach(nng, detached);
        return new EmbeddingInitialisation(nng, natural, steered.graph(), detached,
                steered.reweightedRows());
    }

    private static Route routeFor(NearestNeighborGraph nng) {
        if (nng.size() > SPECTRAL_CANDIDATE_LIMIT) return Route.PCA;
        return nng.graph(false).bfcc().length == 1 ? Route.SPECTRAL : Route.PCA;
    }

    /** The initialisation SMILE would perform for the graph as it was handed in. */
    Route natural() {
        return natural;
    }

    /** True when {@link #graph()} is a modified copy rather than the graph handed in. */
    boolean isSteered() {
        return detached >= 0;
    }

    /**
     * The graph to hand {@code UMAP.fit}. Guaranteed to route to {@link Route#PCA}; the
     * original object when it already did.
     */
    NearestNeighborGraph graph() {
        return steered;
    }

    /**
     * The node detached to force the pure-Java branch, as a row index into the training
     * matrix. Empty when no steering was needed — and so also the answer to "did any cell
     * pay for this run".
     *
     * <p>Private. It and {@link #reweightedRows()} are the two halves of one fact, and
     * while both were reachable separately {@code Steering.detaching(init.detachedNode()
     * .orElse(0), 0)} — a centre reported without its blast radius — was spellable at
     * every call site. {@link #steering()} is the only way out, so it is not.
     */
    private OptionalInt detachedNode() {
        return detached < 0 ? OptionalInt.empty() : OptionalInt.of(detached);
    }

    /**
     * How many <em>other</em> rows had their distance vector rewritten because they listed
     * the detached node, and therefore carry slightly different membership strengths than
     * they would have. Zero when nothing was steered.
     *
     * <p>Free to obtain: {@link #detach} already visits every entry of every row, so this
     * is a counter on a loop that had to run anyway, not a second pass. It is reported
     * because the alternative — an outcome naming one affected cell when the real number
     * is nearer 4% of them — is an understatement, and understatements in an instrument
     * are how people end up trusting a number they should have questioned.
     *
     * <p>Private, for the reason given on {@link #detachedNode()}: it leaves only in
     * {@link #steering()}'s company.
     */
    private int reweightedRows() {
        return reweightedRows;
    }

    /**
     * This decision's own account of itself, for {@link EmbeddingReport}.
     * <p>
     * The centre and the blast radius leave together, from the object that knows both.
     * A caller assembling the pair by hand could name the detached node and forget the
     * rows around it — the exact understatement {@link EmbeddingReport.Steering} refuses
     * to represent — so it is never assembled by hand.
     */
    EmbeddingReport.Steering steering() {
        return detached < 0 ? EmbeddingReport.Steering.none()
                : EmbeddingReport.Steering.detaching(detached, reweightedRows);
    }

    /**
     * Overwrite the detached node's coordinates with the inverse-distance-weighted mean
     * of its true k nearest neighbours, read from the original graph.
     *
     * <p>Call once, on the coordinates {@code UMAP.fit} returned for {@link #graph()}.
     * The position that arrives is an artefact of detachment; this is what makes the
     * steering honest rather than merely effective.
     *
     * @param embedding the fitted coordinates, {@code [n][d]}, modified in place
     * @return true when a coordinate was rewritten
     */
    boolean impute(double[][] embedding) {
        if (detached < 0) return false;
        return InverseDistanceBlend.place(original.neighbors()[detached],
                original.distances()[detached], embedding, detached, embedding[detached]);
    }

    /**
     * The node at the centre of the densest neighbourhood: smallest total distance to its
     * own k neighbours, ties broken by the lower index so the answer is a function of the
     * graph alone.
     */
    private static int densestNode(NearestNeighborGraph nng) {
        double[][] distances = nng.distances();
        int best = 0;
        double bestTotal = Double.POSITIVE_INFINITY;
        for (int i = 0; i < distances.length; i++) {
            double total = 0;
            for (double d : distances[i]) total += d;
            if (total < bestTotal) {
                bestTotal = total;
                best = i;
            }
        }
        return best;
    }

    /**
     * A copy of {@code nng} in which {@code node} shares no edge with any other vertex.
     *
     * <p>Both directions have to go. SMILE builds the connectivity graph undirected, so
     * an edge survives if <em>either</em> endpoint lists the other. The detached row is
     * filled with self-references at distance zero, and every other row that named the
     * node is compacted leftwards past it and padded with a repeat of its own furthest
     * remaining neighbour.
     *
     * <p>Padding with a duplicate rather than with a self-loop is deliberate: it keeps
     * each surviving row at k entries, keeps the distances ascending (which
     * {@code smoothKnnDist} reads positionally when it picks each row's rho), and adds no
     * edge that was not already there — the duplicate collapses onto the existing edge
     * when the adjacency list is built.
     *
     * <p><b>What that does and does not leave alone.</b> Structurally a rewritten row
     * loses exactly one edge, the one to the detached node, and gains nothing. Its
     * <em>weights</em> are another matter, and it would be convenient but false to say
     * the graph is otherwise untouched. {@code smoothKnnDist} reads each row's entire
     * distance vector to solve for that row's sigma, and compacting past the detached
     * entry and padding with a repeat of the furthest survivor changes the multiset it
     * reads. Every membership strength on such a row therefore shifts a little. If the
     * detached node happened to be a row's <em>nearest</em> neighbour, that row's rho
     * moves too, which is the larger of the two effects — it is the local-connectivity
     * offset subtracted from every distance in the row.
     *
     * <p>No cell is repositioned by this and no edge is invented; the rows keep the same
     * neighbours in the same order, weighted slightly differently. The count of rows in
     * that condition is returned rather than hidden, because "one cell was affected" is
     * an understatement of what steering costs and this is a scientific instrument.
     *
     * @return the perturbed graph and the number of rows whose distance vector was
     *         rewritten, which is the number of rows that had listed {@code node}
     */
    private static Detached detach(NearestNeighborGraph nng, int node) {
        int n = nng.size();
        int[][] neighbours = new int[n][];
        double[][] distances = new double[n][];
        for (int i = 0; i < n; i++) {
            neighbours[i] = nng.neighbors()[i].clone();
            distances[i] = nng.distances()[i].clone();
        }

        Arrays.fill(neighbours[node], node);
        Arrays.fill(distances[node], 0.0);

        int reweighted = 0;
        for (int i = 0; i < n; i++) {
            if (i == node) continue;
            int[] row = neighbours[i];
            double[] dist = distances[i];
            int kept = 0;
            for (int j = 0; j < row.length; j++) {
                if (row[j] == node) continue;
                row[kept] = row[j];
                dist[kept] = dist[j];
                kept++;
            }
            if (kept == row.length) continue;   // never listed the detached node
            reweighted++;
            if (kept == 0) {
                // Only reachable if a row named nothing but the detached node. It becomes
                // isolated too — still not spectral, still correct, just a second cell
                // whose position is a fiction. Cannot happen for a k-NN graph with
                // distinct neighbours, and is handled rather than assumed away.
                Arrays.fill(row, i);
                Arrays.fill(dist, 0.0);
            } else {
                for (int j = kept; j < row.length; j++) {
                    row[j] = row[kept - 1];
                    dist[j] = dist[kept - 1];
                }
            }
        }
        return new Detached(new NearestNeighborGraph(nng.k(), neighbours, distances,
                nng.index()), reweighted);
    }
}
