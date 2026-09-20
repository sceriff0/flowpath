package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Randomised equivalence test for the Task 2 fusion of {@link PolygonGate#contains}'s two
 * edge-loops (boundary detection, then even-odd crossing) into one pass. {@link #oldContains}
 * below is a byte-for-byte copy of the pre-fusion implementation (degenerate check, then
 * {@code onBoundary}, then {@code insideByEvenOdd}), kept only as the test oracle -- it must
 * never be "improved" back into production code. Any divergence between the fused
 * implementation and this oracle over thousands of random points, across a normal convex
 * polygon, a bowtie (self-intersecting), a collinear set and a polygon with a duplicate vertex,
 * would mean the fusion changed behaviour.
 */
class PolygonGateContainsEquivalenceTest {

    // ---- oracle: verbatim pre-fusion logic -----------------------------------------------

    private static boolean oldContains(List<double[]> vertices, double x, double y) {
        if (oldIsDegenerate(vertices)) return false;
        if (oldOnBoundary(vertices, x, y)) return true;
        return oldInsideByEvenOdd(vertices, x, y);
    }

    private static boolean oldIsDegenerate(List<double[]> vertices) {
        int n = vertices.size();
        if (n < 3) return true;
        double x0 = vertices.get(0)[0], y0 = vertices.get(0)[1];
        Double dx = null, dy = null;
        for (int i = 1; i < n; i++) {
            double dxi = vertices.get(i)[0] - x0;
            double dyi = vertices.get(i)[1] - y0;
            if (dxi != 0.0 || dyi != 0.0) {
                dx = dxi;
                dy = dyi;
                break;
            }
        }
        if (dx == null) return true;
        for (int i = 1; i < n; i++) {
            double dxi = vertices.get(i)[0] - x0;
            double dyi = vertices.get(i)[1] - y0;
            if (dx * dyi - dy * dxi != 0.0) return false;
        }
        return true;
    }

    private static boolean oldOnBoundary(List<double[]> vertices, double x, double y) {
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ax = vertices.get(j)[0], ay = vertices.get(j)[1];
            double bx = vertices.get(i)[0], by = vertices.get(i)[1];
            double cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
            if (cross == 0.0
                    && x >= Math.min(ax, bx) && x <= Math.max(ax, bx)
                    && y >= Math.min(ay, by) && y <= Math.max(ay, by)) {
                return true;
            }
        }
        return false;
    }

    private static boolean oldInsideByEvenOdd(List<double[]> vertices, double x, double y) {
        boolean inside = false;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = vertices.get(i)[0], yi = vertices.get(i)[1];
            double xj = vertices.get(j)[0], yj = vertices.get(j)[1];
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    // ---- fixtures --------------------------------------------------------------------------

    private static PolygonGate gateOf(List<double[]> vertices) {
        PolygonGate gate = new PolygonGate("X", "Y");
        gate.setVertices(vertices);
        return gate;
    }

    private static List<double[]> convex() {
        return List.of(new double[]{0, 0}, new double[]{4, 0}, new double[]{4, 4}, new double[]{0, 4});
    }

    private static List<double[]> concave() {
        // An arrow/chevron shape: definitely non-convex, exercises multiple crossings per ray.
        return List.of(new double[]{0, 0}, new double[]{4, 0}, new double[]{4, 4}, new double[]{2, 2},
                new double[]{0, 4});
    }

    private static List<double[]> bowtie() {
        return List.of(new double[]{0, 0}, new double[]{4, 4}, new double[]{4, 0}, new double[]{0, 4});
    }

    private static List<double[]> collinear() {
        return List.of(new double[]{0, 0}, new double[]{1, 1}, new double[]{2, 2}, new double[]{3, 3});
    }

    private static List<double[]> duplicateVertex() {
        return List.of(new double[]{0, 0}, new double[]{0, 0}, new double[]{4, 0},
                new double[]{4, 4}, new double[]{0, 4});
    }

    private static List<double[]> allSamePoint() {
        return List.of(new double[]{2, 2}, new double[]{2, 2}, new double[]{2, 2});
    }

    private static List<double[]> twoVertices() {
        return List.of(new double[]{0, 0}, new double[]{4, 4});
    }

    // ---- randomised equivalence ------------------------------------------------------------

    @Test
    void fusedContainsAgreesWithOldTwoPassLogicOverRandomPoints() {
        List<List<double[]>> polygons = List.of(
                convex(), concave(), bowtie(), collinear(), duplicateVertex(),
                allSamePoint(), twoVertices());

        Random random = new Random(42);
        for (List<double[]> vertices : polygons) {
            PolygonGate gate = gateOf(vertices);
            for (int i = 0; i < 3000; i++) {
                double x = -1.0 + random.nextDouble() * 6.0;
                double y = -1.0 + random.nextDouble() * 6.0;
                boolean expected = oldContains(vertices, x, y);
                boolean actual = gate.contains(x, y);
                assertEquals(expected, actual,
                        () -> "polygon " + vertices + " at (" + x + ", " + y + ")");
            }
        }
    }

    // Points exactly on vertices and edge midpoints are the cases most likely to expose a
    // fused implementation that reordered arithmetic and lost bit-for-bit equality with the
    // oracle's exact-zero cross-product test.
    @Test
    void fusedContainsAgreesWithOldTwoPassLogicOnVerticesAndEdgeMidpoints() {
        List<List<double[]>> polygons = List.of(
                convex(), concave(), bowtie(), collinear(), duplicateVertex());

        for (List<double[]> vertices : polygons) {
            PolygonGate gate = gateOf(vertices);
            int n = vertices.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double ax = vertices.get(j)[0], ay = vertices.get(j)[1];
                double bx = vertices.get(i)[0], by = vertices.get(i)[1];

                // vertex itself
                assertEquals(oldContains(vertices, bx, by), gate.contains(bx, by),
                        () -> "vertex (" + bx + ", " + by + ") of " + vertices);

                // edge midpoint
                double mx = (ax + bx) / 2.0, my = (ay + by) / 2.0;
                assertEquals(oldContains(vertices, mx, my), gate.contains(mx, my),
                        () -> "midpoint (" + mx + ", " + my + ") of edge in " + vertices);
            }
        }
    }
}
