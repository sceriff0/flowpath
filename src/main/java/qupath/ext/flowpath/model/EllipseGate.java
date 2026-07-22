package qupath.ext.flowpath.model;

/**
 * A 2D ellipse gate that classifies cells based on whether their (channelX, channelY)
 * marker values fall inside an elliptical region. Produces 2 branches: inside/outside.
 * <p>
 * Containment test: ((x-cx)/rx)^2 + ((y-cy)/ry)^2 &lt;= 1
 */
public class EllipseGate extends Region2DGate {

    private double centerX, centerY, radiusX, radiusY;

    public EllipseGate() {
        super();
    }

    public EllipseGate(String channelX, String channelY,
                        double centerX, double centerY, double radiusX, double radiusY) {
        super(channelX, channelY);
        this.centerX = centerX;
        this.centerY = centerY;
        this.radiusX = radiusX;
        this.radiusY = radiusY;
    }

    @Override public String getGateType() { return "ellipse"; }

    public double getCenterX() { return centerX; }
    public void setCenterX(double v) { this.centerX = v; }
    public double getCenterY() { return centerY; }
    public void setCenterY(double v) { this.centerY = v; }
    public double getRadiusX() { return radiusX; }
    public void setRadiusX(double v) { this.radiusX = v; }
    public double getRadiusY() { return radiusY; }
    public void setRadiusY(double v) { this.radiusY = v; }

    @Override
    public boolean contains(double x, double y) {
        if (radiusX <= 0 || radiusY <= 0) return false;
        double dx = (x - centerX) / radiusX;
        double dy = (y - centerY) / radiusY;
        return (dx * dx + dy * dy) <= 1.0;
    }

    @Override
    public GateNode deepCopy() {
        EllipseGate copy = new EllipseGate();
        copyAxesTo(copy);
        copy.centerX = this.centerX; copy.centerY = this.centerY;
        copy.radiusX = this.radiusX; copy.radiusY = this.radiusY;
        copySharedFieldsTo(copy);
        copyBranchesTo(copy);
        return copy;
    }
}
