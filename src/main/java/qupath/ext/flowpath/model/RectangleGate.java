package qupath.ext.flowpath.model;

/**
 * A 2D rectangle gate that classifies cells based on whether their (channelX, channelY)
 * marker values fall inside a rectangular region. Produces 2 branches: inside/outside.
 */
public final class RectangleGate extends Region2DGate {

    private double minX, maxX, minY, maxY;

    public RectangleGate() {
        super();
    }

    public RectangleGate(String channelX, String channelY, double minX, double maxX, double minY, double maxY) {
        super(channelX, channelY);
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override public String getGateType() { return "rectangle"; }

    public double getMinX() { return minX; }
    public void setMinX(double v) { this.minX = v; }
    public double getMaxX() { return maxX; }
    public void setMaxX(double v) { this.maxX = v; }
    public double getMinY() { return minY; }
    public void setMinY(double v) { this.minY = v; }
    public double getMaxY() { return maxY; }
    public void setMaxY(double v) { this.maxY = v; }

    @Override
    public boolean contains(double x, double y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    @Override
    public GateNode deepCopy() {
        RectangleGate copy = new RectangleGate();
        copyAxesTo(copy);
        copy.minX = this.minX; copy.maxX = this.maxX;
        copy.minY = this.minY; copy.maxY = this.maxY;
        copySharedFieldsTo(copy);
        copyBranchesTo(copy);
        return copy;
    }
}
