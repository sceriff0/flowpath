package qupath.ext.flowpath.model;

import java.util.function.DoubleUnaryOperator;

/**
 * A 2D ellipse gate that classifies cells based on whether their (channelX, channelY)
 * marker values fall inside an elliptical region. Produces 2 branches: inside/outside.
 * <p>
 * Containment test: ((x-cx)/rx)^2 + ((y-cy)/ry)^2 &lt;= 1
 */
public final class EllipseGate extends Region2DGate {

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

    /**
     * {@inheritDoc}
     * <p>
     * The rim is inclusive ({@code <= 1}), per {@link Region2DGate#contains}. An ellipse
     * with a non-positive radius on either axis encloses nothing.
     */
    @Override
    public boolean contains(double x, double y) {
        if (radiusX <= 0 || radiusY <= 0) return false;
        double dx = (x - centerX) / radiusX;
        double dy = (y - centerY) / radiusY;
        return (dx * dx + dy * dy) <= 1.0;
    }

    /** {@inheritDoc} */
    @Override
    public void clearShape() {
        centerX = 0;
        centerY = 0;
        radiusX = 0;
        radiusY = 0;
    }

    /**
     * {@inheritDoc} A zero (or near-zero) radius on either axis is left alone -- checking X
     * alone would let an ellipse that is degenerate on Y (a real X radius but none on Y)
     * still get remapped, the same hazard {@link RectangleGate#remapCoordinates} guards
     * against. The bounding box on each axis is remapped and the centre/radius recomputed
     * from it: for a linear {@code fx}/{@code fy} (as {@code LegacyZScoreMigration} uses)
     * this reduces exactly to mapping the centre directly and scaling each radius by the
     * map's slope magnitude, which is the correct behaviour for a radius -- it has no
     * position to shift.
     */
    @Override
    public void remapCoordinates(DoubleUnaryOperator fx, DoubleUnaryOperator fy) {
        if (radiusX <= Region2DGate.MIN_DRAWABLE_EXTENT
                || radiusY <= Region2DGate.MIN_DRAWABLE_EXTENT) return;
        double loX = fx.applyAsDouble(centerX - radiusX);
        double hiX = fx.applyAsDouble(centerX + radiusX);
        double loY = fy.applyAsDouble(centerY - radiusY);
        double hiY = fy.applyAsDouble(centerY + radiusY);
        centerX = (loX + hiX) / 2;
        radiusX = Math.abs(hiX - loX) / 2;
        centerY = (loY + hiY) / 2;
        radiusY = Math.abs(hiY - loY) / 2;
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
