package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A 2D polygon gate that classifies cells based on whether their (channelX, channelY)
 * marker values fall inside a user-drawn polygon. Produces 2 branches: inside/outside.
 */
public class PolygonGate extends Region2DGate {

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
     * Point-in-polygon test using ray casting algorithm.
     */
    @Override
    public boolean contains(double x, double y) {
        if (vertices.size() < 3) return false;
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
