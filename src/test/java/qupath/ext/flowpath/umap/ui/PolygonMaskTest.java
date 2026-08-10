package qupath.ext.flowpath.umap.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the bounding-box-accelerated, parallel gate mask against the
 * straightforward per-point implementation it replaced. The optimization must be
 * behaviour-preserving: same mask, just computed faster.
 */
class PolygonMaskTest {

    private static final List<double[]> SQUARE = List.of(
            new double[]{0, 0}, new double[]{10, 0}, new double[]{10, 10}, new double[]{0, 10}
    );

    /** The pre-optimization implementation, kept here as the oracle. */
    private static boolean[] reference(double[] xs, double[] ys, List<double[]> verts) {
        boolean[] mask = new boolean[xs.length];
        if (verts.size() < 3) return mask;
        for (int i = 0; i < xs.length; i++) {
            mask[i] = Double.isFinite(xs[i]) && Double.isFinite(ys[i])
                    && UmapCanvas.pointInPolygon(xs[i], ys[i], verts);
        }
        return mask;
    }

    @Test
    void matchesReferenceOnRandomCloud() {
        int n = 20_000;
        Random rng = new Random(7L);
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = rng.nextDouble() * 30 - 10;
            ys[i] = rng.nextDouble() * 30 - 10;
        }

        assertArrayEquals(reference(xs, ys, SQUARE),
                PolygonSelector.computeInsideMask(xs, ys, SQUARE));
    }

    @Test
    void matchesReferenceOnConcavePolygon() {
        // An arrowhead — concave, so a naive convex test would disagree with ray-casting.
        List<double[]> arrow = List.of(
                new double[]{0, 0}, new double[]{10, 5}, new double[]{0, 10}, new double[]{3, 5}
        );
        int n = 5_000;
        Random rng = new Random(11L);
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = rng.nextDouble() * 14 - 2;
            ys[i] = rng.nextDouble() * 14 - 2;
        }

        assertArrayEquals(reference(xs, ys, arrow),
                PolygonSelector.computeInsideMask(xs, ys, arrow));
    }

    @Test
    void nonFiniteCoordinatesAreExcluded() {
        // The box test rejects NaN implicitly (comparisons against NaN are false);
        // infinities must be rejected too rather than sneaking through.
        double[] xs = {5, Double.NaN, 5, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double[] ys = {5, 5, Double.NaN, 5, 5};

        boolean[] mask = PolygonSelector.computeInsideMask(xs, ys, SQUARE);

        assertTrue(mask[0], "a finite interior point is inside");
        for (int i = 1; i < xs.length; i++) {
            assertFalse(mask[i], "non-finite coordinate at " + i + " must not be gated in");
        }
    }

    @Test
    void degeneratePolygonYieldsEmptyMask() {
        double[] xs = {1, 2, 3};
        double[] ys = {1, 2, 3};

        for (List<double[]> verts : List.<List<double[]>>of(
                List.of(),
                List.of(new double[]{0, 0}),
                List.of(new double[]{0, 0}, new double[]{10, 10}))) {
            boolean[] mask = PolygonSelector.computeInsideMask(xs, ys, verts);
            assertEquals(3, mask.length);
            for (boolean b : mask) assertFalse(b, "fewer than 3 vertices selects nothing");
        }
    }

    @Test
    void pointsOutsideBoundingBoxAreRejected() {
        // Directly exercises the fast-reject path.
        double[] xs = {-100, 100, 5};
        double[] ys = {5, 5, 5};

        boolean[] mask = PolygonSelector.computeInsideMask(xs, ys, SQUARE);

        assertFalse(mask[0]);
        assertFalse(mask[1]);
        assertTrue(mask[2]);
    }

    @Test
    void emptyInputIsHandled() {
        assertEquals(0, PolygonSelector.computeInsideMask(new double[0], new double[0], SQUARE).length);
    }
}
