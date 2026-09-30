package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.cohort.CellShapes;
import qupath.ext.flowpath.model.CellIndex;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The review visual: a translucent veil over the tissue and the boundary cells outlined in their
 * branch colour. It paints and nothing else — no PathClass, no hierarchy event — so it can neither
 * trigger {@code IngestCoordinator} nor dirty the .qpdata. Toggled with {@code B}.
 * <p>
 * A detection with no area (a point ROI, whose {@code getShape()} throws in QuPath 0.7) is drawn
 * as a small filled dot at its centroid instead of an outline ({@link CellShapes}, the rule the
 * evidence crop shares).
 */
final class BoundaryOverlay implements PathOverlay {

    /** Drawn over image pixels, not text on the theme: a fixed swatch, like the canvases. */
    private static final Color VEIL = new Color(0, 0, 0, 110);
    private static final double POINT_RADIUS = 2;

    private record Outline(Shape shape, Color color, boolean filled) {}

    private volatile List<Outline> outlines = List.of();
    private volatile boolean visible;

    /** {@code boundary} and {@code packedRgb} are positional against {@code index}'s objects. */
    void setCells(CellIndex index, boolean[] boundary, int[] packedRgb) {
        List<Outline> out = new ArrayList<>();
        for (int i = 0; i < boundary.length; i++) {
            if (!boundary[i]) continue;
            PathObject o = index.getObject(i);
            ROI roi = o == null ? null : o.getROI();
            if (roi == null) continue;
            Color color = new Color(packedRgb[i] & 0xFFFFFF);
            out.add(new Outline(CellShapes.shapeOf(roi, POINT_RADIUS), color, !CellShapes.isOutlined(roi)));
        }
        outlines = List.copyOf(out);
    }

    void setVisible(boolean v) { visible = v; }
    boolean isVisible() { return visible; }
    void toggle() { visible = !visible; }
    int cellCount() { return outlines.size(); }

    @Override
    public void paintOverlay(Graphics2D g, ImageRegion region, double downsample, ImageData<BufferedImage> imageData,
                             boolean paintCompletely) {
        if (!visible) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            Rectangle2D bounds = new Rectangle2D.Double(region.getX(), region.getY(), region.getWidth(), region.getHeight());
            g2.setColor(VEIL);
            g2.fill(bounds);
            g2.setStroke(new BasicStroke((float) Math.max(1.0, 2.0 * downsample)));
            for (Outline o : outlines) {
                if (!o.shape().intersects(bounds)) continue;
                g2.setColor(o.color());
                if (o.filled()) g2.fill(o.shape());
                g2.draw(o.shape());
            }
        } finally {
            g2.dispose();
        }
    }
}
