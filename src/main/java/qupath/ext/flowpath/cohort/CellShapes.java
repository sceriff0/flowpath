package qupath.ext.flowpath.cohort;

import qupath.lib.roi.interfaces.ROI;

import java.awt.Shape;
import java.awt.geom.Ellipse2D;

/**
 * How a detection is drawn as a boundary cell, in level-0 pixels — the one rule the viewer's
 * boundary overlay and the evidence crop share. A detection with no area (a point ROI, whose
 * {@code getShape()} throws in QuPath 0.7) is a dot at its centroid, filled rather than outlined;
 * calling {@code getShape()} on it would turn a whole overlay or crop into an exception.
 */
public final class CellShapes {

    private CellShapes() {}

    /** The ROI's own outline when it has an area, else a dot of {@code pointRadius} at its centroid. */
    public static Shape shapeOf(ROI roi, double pointRadius) {
        if (isOutlined(roi)) return roi.getShape();
        return new Ellipse2D.Double(roi.getCentroidX() - pointRadius, roi.getCentroidY() - pointRadius,
                2 * pointRadius, 2 * pointRadius);
    }

    /** Whether {@link #shapeOf} is an outline to stroke (true) or a dot to fill (false). */
    public static boolean isOutlined(ROI roi) {
        return roi.isArea();
    }
}
