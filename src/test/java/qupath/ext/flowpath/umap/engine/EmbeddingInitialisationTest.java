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
        assertEquals(OptionalInt.of(3), init.detachedNode(),
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
        int detached = init.detachedNode().orElseThrow();
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
        assertEquals(OptionalInt.empty(), init.detachedNode());
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
        assertEquals(OptionalInt.empty(), init.detachedNode());
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
                    EmbeddingInitialisation.forGraph(ring(8, 3)).detachedNode());
        }
    }

    @Test
    void aTieIsBrokenByTheLowerIndexRatherThanLeftToChance() {
        var uniform = ring(8, -1);   // every node equally dense
        assertEquals(OptionalInt.of(0), EmbeddingInitialisation.forGraph(uniform).detachedNode());
    }
}
