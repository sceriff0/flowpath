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
     */
    @Override
    public boolean contains(double x, double y) {
        if (isDegenerate()) return false;
        if (onBoundary(x, y)) return true;
        return insideByEvenOdd(x, y);
    }

    /** Fewer than 3 vertices, or every vertex lying on one line, encloses no area. */
    private boolean isDegenerate() {
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
        if (dx == null) return true; // every vertex is the same point
        for (int i = 1; i < n; i++) {
            double dxi = vertices.get(i)[0] - x0;
            double dyi = vertices.get(i)[1] - y0;
            // Exact cross product against the reference direction: zero means collinear.
            if (dx * dyi - dy * dxi != 0.0) return false;
        }
        return true;
    }

    /**
     * Is (x, y) exactly on one of the polygon's edges (including its vertices)? Exact
     * arithmetic only: the cross product of the edge vector and the vector to the point must
     * be precisely {@code 0.0}, and the point must lie within the edge's closed bounding box.
     */
    private boolean onBoundary(double x, double y) {
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

    /** Ray-casting interior test; the caller has already handled the boundary. */
    private boolean insideByEvenOdd(double x, double y) {
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

    /** {@inheritDoc} An empty vertex list is already degenerate, per {@link #isDegenerate}. */
    @Override
    public void clearShape() {
        vertices = new ArrayList<>();
    }

    /**
     * {@inheritDoc} Each vertex is mapped independently; an empty polygon has none to map,
     * so no separate degenerate guard is needed here.
     */
    @Override
    public void remapCoordinates(DoubleUnaryOperator fx, DoubleUnaryOperator fy) {
        for (double[] v : vertices) {
            v[0] = fx.applyAsDouble(v[0]);
            v[1] = fy.applyAsDouble(v[1]);
        }
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
