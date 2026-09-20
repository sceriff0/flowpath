package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * A 2D polygon gate that classifies cells based on whether their (channelX, channelY)
 * marker values fall inside a user-drawn polygon. Produces 2 branches: inside/outside.
 */
public final class PolygonGate extends Region2DGate {

    private List<double[]> vertices = new ArrayList<>(); // [[x0,y0], [x1,y1], ...]

    public PolygonGate() {
        super();
    }

    public PolygonGate(String channelX, String channelY) {
        super(channelX, channelY);
    }

    @Override public String getGateType() { return "polygon"; }

    public List<double[]> getVertices() { return vertices; }
    public void setVertices(List<double[]> v) { this.vertices = v; }

    /**
     * {@inheritDoc}
     * <p>
     * A degenerate polygon (fewer than 3 vertices, or every vertex collinear) is checked
     * first and encloses nothing, including points that would otherwise sit on one of its
     * "edges". Otherwise a point exactly on any edge or vertex -- including a
     * self-intersecting polygon's crossing segments -- is Inside per {@link Region2DGate};
     * the interior away from every edge follows the even-odd rule, so a self-intersecting
     * (bowtie) polygon's lobes are Inside and its notches Outside.
     * <p>
     * Degeneracy, boundary membership and the even-odd crossing count are all decided in one
     * pass over the edges rather than three (this method used to call three helpers, each
     * walking the whole vertex list on its own) -- this is on the live-preview hot path, run
     * for every cell on every gating pass. Degeneracy is recomputed each call with primitives
     * only (no boxed {@code Double}, matching every other value here); it is not cached,
     * because {@link #getVertices()} hands out the backing list, so a caller can mutate a
     * vertex in place without going through {@link #setVertices} or {@link #remapCoordinates}
     * -- there is no mutator to hook a cache invalidation to that is provably complete, and an
     * O(n) primitive recomputation is cheap enough on its own not to need one.
     */
    @Override
    public boolean contains(double x, double y) {
        int n = vertices.size();
        if (n < 3) return false;

        double x0 = vertices.get(0)[0], y0 = vertices.get(0)[1];
        double refDx = 0.0, refDy = 0.0;
        boolean haveRef = false;
        boolean collinear = true;
        boolean onEdge = false;
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            double[] a = vertices.get(j);
            double[] b = vertices.get(i);
            double ax = a[0], ay = a[1];
            double bx = b[0], by = b[1];

            // Boundary: exact cross product of the edge vector against the point must be
            // precisely 0.0, and the point must lie within the edge's closed bounding box.
            double cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
            if (cross == 0.0
                    && x >= Math.min(ax, bx) && x <= Math.max(ax, bx)
                    && y >= Math.min(ay, by) && y <= Math.max(ay, by)) {
                onEdge = true;
            }

            // Even-odd ray casting over the same edge.
            if (((by > y) != (ay > y)) && (x < (ax - bx) * (y - by) / (ay - by) + bx)) {
                inside = !inside;
            }

            // Degeneracy: is vertex i collinear with vertex 0, along the first direction
            // found away from it? Vertex 0 itself (i == 0) contributes a zero delta and is
            // skipped by construction, matching the old two-loop version exactly.
            if (collinear) {
                double dxi = bx - x0, dyi = by - y0;
                if (!haveRef) {
                    if (dxi != 0.0 || dyi != 0.0) {
                        refDx = dxi;
                        refDy = dyi;
                        haveRef = true;
                    }
                } else if (refDx * dyi - refDy * dxi != 0.0) {
                    collinear = false;
                }
            }
        }

        // collinear stays true both when every vertex lies on one line through vertex 0, and
        // when every vertex IS vertex 0 (haveRef never becomes true) -- the same two cases the
        // old isDegenerate() folded into one boolean.
        if (collinear) return false;
        if (onEdge) return true;
        return inside;
    }

    /** {@inheritDoc} An empty vertex list is already degenerate (fewer than 3 vertices). */
    @Override
    public void clearShape() {
        vertices = new ArrayList<>();
    }

    /**
     * {@inheritDoc} Each vertex is mapped independently; an empty polygon has none to map,
     * so no separate degenerate guard is needed here.
     * <p>
     * Builds a fresh array per vertex and assigns a fresh list to {@link #vertices}, rather
     * than overwriting each vertex's coordinates in place, matching {@link RectangleGate} and
     * {@link EllipseGate}, which reassign their fields instead of mutating shared state. Safe
     * because {@link #getVertices()} always returns whatever {@link #vertices} currently
     * holds, read fresh on every call: the one place that mutates a vertex array in place
     * ({@code ScatterPlotCanvas}'s drag-handle, moving one vertex interactively) does so
     * between remaps, never during one, and always re-fetches the array through
     * {@link #getVertices()} first rather than holding one across this call. Mutating in
     * place instead would let such a caller observe a half-mapped polygon -- some vertices
     * already in the new coordinate space, the rest still in the old one -- if it ever raced
     * this method, which reassignment rules out structurally.
     */
    @Override
    public void remapCoordinates(DoubleUnaryOperator fx, DoubleUnaryOperator fy) {
        List<double[]> remapped = new ArrayList<>(vertices.size());
        for (double[] v : vertices) {
            remapped.add(new double[]{fx.applyAsDouble(v[0]), fy.applyAsDouble(v[1])});
        }
        vertices = remapped;
    }

    @Override
    public GateNode deepCopy() {
        PolygonGate copy = new PolygonGate();
        copyAxesTo(copy);
        copy.vertices = new ArrayList<>();
        for (double[] v : this.vertices) {
            copy.vertices.add(new double[]{v[0], v[1]});
        }
        copySharedFieldsTo(copy);
        copyBranchesTo(copy);
        return copy;
    }
}
