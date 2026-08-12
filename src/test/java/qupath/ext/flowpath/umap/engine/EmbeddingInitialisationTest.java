package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;
import smile.graph.NearestNeighborGraph;

import java.util.Arrays;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The initialisation decision is a pure function of a neighbour graph, so it is tested
 * as one: no toolkit, no compute service, no SMILE fit. What must hold is that the class
 * reads SMILE's branch the same way SMILE does, that its steered graph really does answer
 * the other way, and that it damages exactly one node and repairs it.
 */
class EmbeddingInitialisationTest {

    /**
     * A ring of {@code n} nodes, each joined to the neighbour on either side. Connected
     * by construction, which is the case that fails without steering. Node
     * {@code densest} is given shorter edges so the densest-node choice has a unique
     * answer to find.
     */
    private static NearestNeighborGraph ring(int n, int densest) {
        int[][] neighbours = new int[n][2];
        double[][] distances = new double[n][2];
        for (int i = 0; i < n; i++) {
            neighbours[i][0] = (i + 1) % n;
            neighbours[i][1] = (i - 1 + n) % n;
            double d = i == densest ? 0.25 : 1.0;
            distances[i][0] = d;
            distances[i][1] = d;
        }
        return new NearestNeighborGraph(2, neighbours, distances);
    }

    /**
     * A small connected graph with deliberately uneven edge lengths, built so that
     * detaching its densest node (0) rewrites the distance vectors of rows 1 and 2 —
     * including their smallest entry, which is what moves a row's rho — and leaves rows 3
     * and 4 alone. The ring fixture cannot show this: its distances are uniform, so
     * compacting a row past the detached entry and padding with the survivor happens to
     * reproduce the same multiset.
     */
    private static NearestNeighborGraph unevenlyWeighted() {
        int[][] neighbours = { {1, 2}, {0, 2}, {0, 3}, {2, 4}, {3, 2} };
        double[][] distances = { {0.1, 0.2}, {0.1, 1.0}, {0.2, 1.5}, {1.5, 2.0}, {2.0, 3.0} };
        return new NearestNeighborGraph(2, neighbours, distances);
    }

    /** How many connected components SMILE would see — computed exactly as SMILE does. */
    private static int components(NearestNeighborGraph nng) {
        return nng.graph(false).bfcc().length;
    }

    @Test
    void aSmallConnectedGraphIsTheOneSmileSendsToTheNativeLayout() {
        var init = EmbeddingInitialisation.forGraph(ring(8, 3));
        assertEquals(EmbeddingInitialisation.Route.SPECTRAL, init.natural(),
                "a small connected graph is exactly what routes SMILE to ARPACK");
        assertTrue(init.isSteered());
    }

    @Test
    void theSteeredGraphAnswersPcaWhereTheOriginalAnsweredSpectral() {
        var original = ring(8, 3);
        assertEquals(1, components(original), "the fixture must start out connected");

        var init = EmbeddingInitialisation.forGraph(original);
        assertNotEquals(original, init.graph(), "steering must not mutate the graph handed in");
        assertEquals(1, components(original), "the original graph must be left alone");
        assertEquals(2, components(init.graph()),
                "the whole point: cc.length != 1 is what sends SMILE down the pure-Java branch");
        assertEquals(EmbeddingInitialisation.Route.PCA,
                EmbeddingInitialisation.forGraph(init.graph()).natural(),
                "re-reading the steered graph must give the answer it was steered to");
    }

    @Test
    void exactlyOneNodeIsDetachedAndItIsTheDensestOne() {
        var init = EmbeddingInitialisation.forGraph(ring(8, 3));
        assertEquals(EmbeddingReport.Steering.detaching(3, 2), init.steering(),
                "node 3 has the smallest total neighbour distance, so it is the best-constrained");

        int[][] cc = init.graph().graph(false).bfcc();
        int[] singleton = Arrays.stream(cc).filter(c -> c.length == 1).findFirst().orElseThrow();
        assertArrayEquals(new int[] {3}, singleton, "only the chosen node may be cut loose");
        assertEquals(8, Arrays.stream(cc).mapToInt(c -> c.length).sum(),
                "every other node must stay in one piece with the rest");
    }

    @Test
    void noRowStillPointsAtTheDetachedNodeAndEveryRowKeepsItsWidth() {
        // Detaching only the node's own row would leave it attached: SMILE builds the
        // connectivity graph undirected, so an edge survives if either endpoint names the
        // other. Rows that named it are compacted and padded, never truncated — a short
        // row would change k for that cell alone.
        var init = EmbeddingInitialisation.forGraph(ring(8, 3));
        int detached = init.steering().detachedRow().orElseThrow();
        int[][] neighbours = init.graph().neighbors();
        for (int i = 0; i < neighbours.length; i++) {
            assertEquals(2, neighbours[i].length, "row " + i + " must keep k entries");
            if (i == detached) continue;
            for (int nb : neighbours[i]) {
                assertNotEquals(detached, nb,
                        "row " + i + " still references the detached node: " + Arrays.toString(neighbours[i]));
            }
        }
    }

    @Test
    void detachingOneNodeRewritesTheDistanceVectorOfEveryRowThatListedIt() {
        // The claim this pins used to be the opposite one: that a rewritten row "loses
        // only the edge to the detached node, and nothing else about the graph moves".
        // Structurally true, and false where it matters — smoothKnnDist reads a row's
        // whole distance vector to solve for that row's sigma, so a row whose vector was
        // compacted and padded carries different membership strengths than it would have.
        // Rows 1 and 2 listed node 0; rows 3 and 4 did not.
        var original = unevenlyWeighted();
        var init = EmbeddingInitialisation.forGraph(original);
        assertEquals(EmbeddingReport.Steering.detaching(0, 2), init.steering(),
                "node 0 is the densest, and exactly the rows that listed it are rewritten");

        double[][] before = original.distances();
        double[][] after = init.graph().distances();

        // Row 1: {0.1 -> node 0, 1.0 -> node 2} becomes {1.0, 1.0}. Its minimum moves
        // from 0.1 to 1.0, which is the rho shift — the local-connectivity offset
        // subtracted from every distance in the row.
        assertArrayEquals(new double[] {1.0, 1.0}, after[1], 1e-12);
        assertNotEquals(before[1][0], after[1][0],
                "row 1's nearest-neighbour distance must have moved");

        // Row 2: {0.2 -> node 0, 1.5 -> node 3} becomes {1.5, 1.5}.
        assertArrayEquals(new double[] {1.5, 1.5}, after[2], 1e-12);

        // And the rows that never named it are untouched, weights included.
        assertArrayEquals(before[3], after[3], 0.0, "row 3 never listed the detached node");
        assertArrayEquals(before[4], after[4], 0.0, "row 4 never listed the detached node");
        assertArrayEquals(original.neighbors()[3], init.graph().neighbors()[3]);
        assertArrayEquals(original.neighbors()[4], init.graph().neighbors()[4]);
    }

    @Test
    void theReweightedCountIsTheNumberOfRowsThatNamedTheDetachedNode() {
        // In a ring each node is named by its two neighbours, so detaching one rewrites
        // exactly two rows — and the count is what the outcome reports, so it must be the
        // number of OTHER cells affected, not including the detached one itself.
        var init = EmbeddingInitialisation.forGraph(ring(8, 3));
        assertEquals(2, init.steering().reweightedRows());
    }

    @Test
    void nothingIsReweightedWhenNothingIsSteered() {
        int[][] neighbours = { {1}, {0}, {3}, {2} };
        double[][] distances = { {1}, {1}, {1}, {1} };
        var init = EmbeddingInitialisation.forGraph(new NearestNeighborGraph(1, neighbours, distances));
        assertFalse(init.isSteered());
        assertEquals(EmbeddingReport.Steering.none(), init.steering());
    }

    @Test
    void aGraphSmileWouldNotSendToArpackIsHandedBackUntouched() {
        // Two disjoint pairs: already more than one component, so SMILE already picks PCA
        // and there is nothing to pay for.
        int[][] neighbours = { {1}, {0}, {3}, {2} };
        double[][] distances = { {1}, {1}, {1}, {1} };
        var original = new NearestNeighborGraph(1, neighbours, distances);
        assertEquals(2, components(original));

        var init = EmbeddingInitialisation.forGraph(original);
        assertEquals(EmbeddingInitialisation.Route.PCA, init.natural());
        assertFalse(init.isSteered());
        assertSame(original, init.graph(), "no steering means no copy");
        assertEquals(EmbeddingReport.Steering.none(), init.steering());
    }

    @Test
    void theSizeThresholdIsTheLiteralSmileUses() {
        // SMILE's UMAP.LARGE_DATA_SIZE is private, so nothing links these two numbers but
        // this assertion. Should the constant here ever drift upward, every run below
        // SMILE's real threshold would go back to reaching for ARPACK and dying with
        // NoClassDefFoundError — with a green suite, because the boundary tests are
        // written against the symbol. Pinning the literal is what makes that drift a red
        // test instead of a returned bug. Same reasoning as AUTO_SUBSAMPLE_HARD_CAP.
        assertEquals(10_000, EmbeddingInitialisation.SPECTRAL_CANDIDATE_LIMIT,
                "must equal SMILE's private UMAP.LARGE_DATA_SIZE");
    }

    @Test
    void exactlyAtTheThresholdSmileStillTestsConnectivitySoSteeringIsStillNeeded() {
        // SMILE's guard is `n <= LARGE_DATA_SIZE`, so 10,000 is inside the spectral
        // window, not outside it. A `>=` here instead of `>` would leave the single most
        // borderline dataset crashing, and without this case nothing would notice.
        var init = EmbeddingInitialisation.forGraph(
                ring(EmbeddingInitialisation.SPECTRAL_CANDIDATE_LIMIT, 0));
        assertEquals(EmbeddingInitialisation.Route.SPECTRAL, init.natural());
        assertTrue(init.isSteered());
    }

    @Test
    void aboveSmilesSizeThresholdConnectivityIsNotEvenConsulted() {
        // SMILE skips the connectivity test entirely above LARGE_DATA_SIZE and goes
        // straight to PCA, so a large connected graph needs no sacrifice. Building it as
        // a ring makes it connected — if this class tested connectivity unconditionally
        // it would steer, and cost a cell for nothing.
        int n = EmbeddingInitialisation.SPECTRAL_CANDIDATE_LIMIT + 1;
        var init = EmbeddingInitialisation.forGraph(ring(n, 0));
        assertEquals(EmbeddingInitialisation.Route.PCA, init.natural());
        assertFalse(init.isSteered());
        assertEquals(EmbeddingReport.Steering.none(), init.steering());
    }

    @Test
    void theDetachedNodeIsPlacedAtTheInverseDistanceMeanOfItsTrueNeighbours() {
        // Node 3's real neighbours are 4 and 2, equidistant, so the repaired position is
        // their midpoint — read from the ORIGINAL graph, not the perturbed one, which no
        // longer knows node 3 had neighbours at all.
        var init = EmbeddingInitialisation.forGraph(ring(8, 3));
        double[][] embedding = new double[8][2];
        for (int i = 0; i < 8; i++) {
            embedding[i][0] = i;
            embedding[i][1] = 10 * i;
        }
        embedding[3][0] = -999;   // the artefact position detachment leaves behind
        embedding[3][1] = 999;

        assertTrue(init.impute(embedding));
        assertEquals(3.0, embedding[3][0], 1e-9);
        assertEquals(30.0, embedding[3][1], 1e-9);
    }

    @Test
    void imputationDoesNothingWhenNothingWasDetached() {
        int[][] neighbours = { {1}, {0}, {3}, {2} };
        double[][] distances = { {1}, {1}, {1}, {1} };
        var init = EmbeddingInitialisation.forGraph(new NearestNeighborGraph(1, neighbours, distances));
        double[][] embedding = { {1, 2}, {3, 4}, {5, 6}, {7, 8} };

        assertFalse(init.impute(embedding));
        assertArrayEquals(new double[] {1, 2}, embedding[0]);
        assertArrayEquals(new double[] {7, 8}, embedding[3]);
    }

    @Test
    void theSameGraphAlwaysLosesTheSameNode() {
        // An embedding that moves between two identical runs is worse than one that
        // fails: the user cannot tell which of the two they are looking at. The choice
        // must therefore come from the graph and nothing else — no RNG, no clock, no
        // iteration order.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(OptionalInt.of(3),
                    EmbeddingInitialisation.forGraph(ring(8, 3)).steering().detachedRow());
        }
    }

    @Test
    void aTieIsBrokenByTheLowerIndexRatherThanLeftToChance() {
        var uniform = ring(8, -1);   // every node equally dense
        assertEquals(OptionalInt.of(0),
                EmbeddingInitialisation.forGraph(uniform).steering().detachedRow());
    }
}
