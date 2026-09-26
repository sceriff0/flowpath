package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * A gate's geometry as per-axis lists of positions — the unit a slide setting stores and the
 * unit {@code TreeResolver} maps. Every number on axis k is a position on axis k's column, so
 * mapping an axis through a monotone function maps the gate: threshold {@code x=[t]};
 * quadrant {@code x=[tx], y=[ty]}; rectangle {@code x=[minX,maxX], y=[minY,maxY]}; ellipse by
 * bounding box {@code x=[cx-rx,cx+rx], y=[cy-ry,cy+ry]}; polygon {@code x=[x0..], y=[y0..]}.
 * Immutable.
 */
public final class GateValues {

    private static final double RELATIVE_TOLERANCE = 1e-9;

    private final double[][] axes;

    private GateValues(double[][] axes) {
        this.axes = new double[axes.length][];
        for (int k = 0; k < axes.length; k++) this.axes[k] = axes[k].clone();
    }

    public static GateValues of(double[] x) {
        return new GateValues(new double[][]{x});
    }

    public static GateValues of(double[] x, double[] y) {
        return new GateValues(new double[][]{x, y});
    }

    public static GateValues read(GateNode gate) {
        if (gate instanceof QuadrantGate q) {
            return of(new double[]{q.getThresholdX()}, new double[]{q.getThresholdY()});
        }
        if (gate instanceof RectangleGate r) {
            return of(new double[]{r.getMinX(), r.getMaxX()}, new double[]{r.getMinY(), r.getMaxY()});
        }
        if (gate instanceof EllipseGate e) {
            return of(new double[]{e.getCenterX() - e.getRadiusX(), e.getCenterX() + e.getRadiusX()},
                    new double[]{e.getCenterY() - e.getRadiusY(), e.getCenterY() + e.getRadiusY()});
        }
        if (gate instanceof PolygonGate p) {
            List<double[]> v = p.getVertices();
            double[] xs = new double[v.size()];
            double[] ys = new double[v.size()];
            for (int i = 0; i < v.size(); i++) {
                xs[i] = v.get(i)[0];
                ys[i] = v.get(i)[1];
            }
            return of(xs, ys);
        }
        return of(new double[]{gate.getThreshold()});
    }

    /** True when these values have the shape {@code gate}'s own values have. */
    public boolean fits(GateNode gate) {
        GateValues own = read(gate);
        if (own.axes.length != axes.length) return false;
        for (int k = 0; k < axes.length; k++) {
            if (own.axes[k].length != axes[k].length) return false;
        }
        return true;
    }

    public void writeTo(GateNode gate) {
        if (!fits(gate)) {
            throw new IllegalArgumentException("values of shape " + shape()
                    + " do not fit a " + gate.getGateType() + " gate of shape " + read(gate).shape());
        }
        double[] x = axes[0];
        if (gate instanceof QuadrantGate q) {
            q.setThresholdX(x[0]);
            q.setThresholdY(axes[1][0]);
        } else if (gate instanceof RectangleGate r) {
            r.setMinX(x[0]); r.setMaxX(x[1]);
            r.setMinY(axes[1][0]); r.setMaxY(axes[1][1]);
        } else if (gate instanceof EllipseGate e) {
            e.setCenterX((x[0] + x[1]) / 2); e.setRadiusX((x[1] - x[0]) / 2);
            e.setCenterY((axes[1][0] + axes[1][1]) / 2); e.setRadiusY((axes[1][1] - axes[1][0]) / 2);
        } else if (gate instanceof PolygonGate p) {
            List<double[]> vertices = new ArrayList<>(x.length);
            for (int i = 0; i < x.length; i++) vertices.add(new double[]{x[i], axes[1][i]});
            p.setVertices(vertices);
        } else {
            gate.setThreshold(x[0]);
        }
    }

    /** Axis 0 through {@code fx}, axis 1 (when present) through {@code fy}. */
    public GateValues map(DoubleUnaryOperator fx, DoubleUnaryOperator fy) {
        double[][] out = new double[axes.length][];
        for (int k = 0; k < axes.length; k++) {
            DoubleUnaryOperator f = k == 0 ? fx : fy;
            out[k] = new double[axes[k].length];
            for (int i = 0; i < axes[k].length; i++) out[k][i] = f.applyAsDouble(axes[k][i]);
        }
        return new GateValues(out);
    }

    public int axisCount() {
        return axes.length;
    }

    public double[] axis(int k) {
        return axes[k].clone();
    }

    /** Same shape, and every number equal within a relative 1e-9 (a JSON round trip, a bbox rebuild). */
    public boolean matches(GateValues other) {
        if (other == null || other.axes.length != axes.length) return false;
        for (int k = 0; k < axes.length; k++) {
            if (other.axes[k].length != axes[k].length) return false;
            for (int i = 0; i < axes[k].length; i++) {
                double a = axes[k][i];
                double b = other.axes[k][i];
                double scale = Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
                if (!(Math.abs(a - b) <= RELATIVE_TOLERANCE * scale)) return false;
            }
        }
        return true;
    }

    private String shape() {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < axes.length; k++) sb.append(k == 0 ? "" : ",").append(axes[k].length);
        return sb.append(']').toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GateValues other && Arrays.deepEquals(axes, other.axes);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(axes);
    }

    @Override
    public String toString() {
        return Arrays.deepToString(axes);
    }
}
