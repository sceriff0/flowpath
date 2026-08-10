package qupath.ext.flowpath.umap.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Canvas-based 2D scatter plot for UMAP visualization.
 * Supports per-point coloring, zoom/pan, subsampling, and population ring rendering.
 */
public class UmapCanvas extends Canvas {

    private static final int MAX_DISPLAY_POINTS = 30000;
    private static final double PADDING_LEFT = 10;
    private static final double PADDING_RIGHT = 10;
    private static final double PADDING_TOP = 10;
    private static final double PADDING_BOTTOM = 10;

    private double[] xValues;
    private double[] yValues;
    private int[] pointColors;         // packed RGB per point
    /** Per-point visibility; null means all visible. See {@link #setVisibleMask}. */
    private boolean[] visibleMask;
    private double minX, maxX, minY, maxY;

    // Zoom/pan
    private double viewMinX, viewMaxX, viewMinY, viewMaxY;
    private boolean viewOverride = false;
    private double panStartX, panStartY;
    private double panStartViewMinX, panStartViewMinY;
    private boolean panning = false;

    // Rendering
    private double dotSize = 2.0;

    // Population rings
    private List<int[]> ringColors;    // packed RGB per population
    private List<int[]> ringIndices;   // pre-resolved point indices per population

    // Viewer-selection highlight
    private int[] highlightIndices;

    /**
     * Cap on how many points any overlay pass (rings, highlight) draws.
     * <p>
     * Overlays used to walk all N points per population on every repaint, and repaint
     * fires on each scroll tick and each pan drag event — so five tagged populations on
     * a million-cell embedding cost five million iterations per frame. Masks are now
     * resolved to a strided index list once, when the mask is set, and the render pass
     * walks only that list.
     */
    private static final int MAX_OVERLAY_POINTS = 30000;

    /** Screen-space radius, in pixels, within which a click picks a point. */
    private static final double PICK_RADIUS = 12.0;

    private IntConsumer onPointPicked;

    // Polygon overlay
    private List<double[]> polygonVertices;
    private boolean polygonCompleted = false;
    private static final double HANDLE_RADIUS = 6.0;

    // View change listener
    private Runnable onViewChanged;

    public void setOnViewChanged(Runnable cb) { this.onViewChanged = cb; }

    private void fireViewChanged() {
        if (onViewChanged != null) onViewChanged.run();
    }

    public double getViewMinX() { return effectiveMinX(); }
    public double getViewMaxX() { return effectiveMaxX(); }
    public double getViewMinY() { return effectiveMinY(); }
    public double getViewMaxY() { return effectiveMaxY(); }
    public boolean isViewOverride() { return viewOverride; }

    public UmapCanvas() {
        super(400, 400);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());

        // Zoom with scroll wheel
        setOnScroll(e -> {
            if (xValues == null) return;
            double factor = e.getDeltaY() > 0 ? 0.9 : 1.1;
            double cx = screenXToDataX(e.getX());
            double cy = screenYToDataY(e.getY());

            double eMinX = effectiveMinX(), eMaxX = effectiveMaxX();
            double eMinY = effectiveMinY(), eMaxY = effectiveMaxY();

            viewMinX = cx + (eMinX - cx) * factor;
            viewMaxX = cx + (eMaxX - cx) * factor;
            viewMinY = cy + (eMinY - cy) * factor;
            viewMaxY = cy + (eMaxY - cy) * factor;
            viewOverride = true;
            repaint();
            fireViewChanged();
        });

        // Pan with middle mouse button
        setOnMousePressed(e -> {
            if (e.isMiddleButtonDown()) {
                panning = true;
                panStartX = e.getX();
                panStartY = e.getY();
                panStartViewMinX = effectiveMinX();
                panStartViewMinY = effectiveMinY();
                e.consume();
            }
        });
        setOnMouseDragged(e -> {
            if (panning) {
                double dx = screenXToDataX(panStartX) - screenXToDataX(e.getX());
                double dy = screenYToDataY(panStartY) - screenYToDataY(e.getY());
                double rangeX = effectiveMaxX() - effectiveMinX();
                double rangeY = effectiveMaxY() - effectiveMinY();
                viewMinX = panStartViewMinX + dx;
                viewMaxX = viewMinX + rangeX;
                viewMinY = panStartViewMinY + dy;
                viewMaxY = viewMinY + rangeY;
                viewOverride = true;
                repaint();
                fireViewChanged();
                e.consume();
            }
        });
        setOnMouseReleased(e -> {
            if (panning) {
                panning = false;
                e.consume();
            }
        });

        setOnMouseClicked(e -> {
            // Double-click to reset view
            if (e.getClickCount() == 2 && !e.isMiddleButtonDown()) {
                resetView();
                return;
            }
            // Single primary click picks the nearest point. The polygon selector
            // consumes MOUSE_PRESSED while drawing, but MOUSE_CLICKED still reaches
            // us — the host decides whether a pick is appropriate right now.
            if (e.getClickCount() == 1 && e.getButton() == MouseButton.PRIMARY
                    && onPointPicked != null) {
                int idx = findNearestPoint(e.getX(), e.getY(), PICK_RADIUS);
                if (idx >= 0) onPointPicked.accept(idx);
            }
        });
    }

    /**
     * Register a callback invoked with the index of the point nearest a single
     * primary click, when one falls within {@link #PICK_RADIUS}. The callback is
     * responsible for deciding whether picking is currently appropriate (e.g.
     * suppressing it while a gate is being drawn).
     */
    public void setOnPointPicked(IntConsumer cb) { this.onPointPicked = cb; }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return 400; }
    @Override public double prefHeight(double w) { return 400; }
    @Override public double minWidth(double h) { return 150; }
    @Override public double minHeight(double w) { return 150; }
    @Override public double maxWidth(double h) { return Double.MAX_VALUE; }
    @Override public double maxHeight(double w) { return Double.MAX_VALUE; }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        repaint();
    }

    public void setData(double[] xValues, double[] yValues) {
        this.xValues = xValues;
        this.yValues = yValues;
        this.pointColors = null;
        this.visibleMask = null;
        this.viewOverride = false;
        // Overlay index lists are resolved against the previous point count; a new
        // embedding invalidates them. Leaving them would index the wrong cells (or
        // run off the end of a shorter array).
        this.ringColors = null;
        this.ringIndices = null;
        this.highlightIndices = null;

        if (xValues == null || yValues == null || xValues.length == 0) {
            repaint();
            return;
        }

        computeDataBounds();
        repaint();
    }

    private void computeDataBounds() {
        minX = Double.MAX_VALUE; maxX = -Double.MAX_VALUE;
        minY = Double.MAX_VALUE; maxY = -Double.MAX_VALUE;
        for (int i = 0; i < xValues.length; i++) {
            if (Double.isFinite(xValues[i]) && Double.isFinite(yValues[i])) {
                minX = Math.min(minX, xValues[i]);
                maxX = Math.max(maxX, xValues[i]);
                minY = Math.min(minY, yValues[i]);
                maxY = Math.max(maxY, yValues[i]);
            }
        }
        // If no finite values found, use safe defaults
        if (minX == Double.MAX_VALUE) { minX = 0; maxX = 1; minY = 0; maxY = 1; }
        if (maxX <= minX) maxX = minX + 1;
        if (maxY <= minY) maxY = minY + 1;

        double padX = (maxX - minX) * 0.05;
        double padY = (maxY - minY) * 0.05;
        minX -= padX; maxX += padX;
        minY -= padY; maxY += padY;
    }

    public void setPointColors(int[] colors) {
        this.pointColors = colors;
        repaint();
    }

    /**
     * Per-point visibility. {@code null} shows everything.
     * <p>
     * Hidden points are skipped in the draw loop rather than painted in the background
     * colour. Painting them would still cost a {@code fillOval} each and, worse, would
     * still occlude whatever is beneath them — the reason a user hides a dominant
     * population is precisely to see the sparse ones it was burying.
     */
    public void setVisibleMask(boolean[] mask) {
        this.visibleMask = mask;
        repaint();
    }

    public void setDotSize(double size) {
        this.dotSize = size;
        repaint();
    }

    public void setPopulationRings(List<int[]> ringColors, List<boolean[]> ringMasks) {
        this.ringColors = ringColors;
        if (ringMasks == null || xValues == null) {
            this.ringIndices = null;
        } else {
            this.ringIndices = ringMasks.stream()
                    .map(m -> maskToIndices(m, xValues.length, MAX_OVERLAY_POINTS))
                    .toList();
        }
        repaint();
    }

    /**
     * Highlight the points corresponding to the current QuPath viewer selection.
     * Pass null to clear.
     */
    public void setHighlightMask(boolean[] mask) {
        setHighlightIndices(xValues == null
                ? null
                : maskToIndices(mask, xValues.length, MAX_OVERLAY_POINTS));
    }

    /**
     * Highlight specific point indices. Pass null to clear. Preferred over
     * {@link #setHighlightMask} when the caller already knows the indices — building a
     * full boolean mask to highlight a single clicked cell would allocate one byte per
     * cell in the dataset.
     */
    public void setHighlightIndices(int[] indices) {
        this.highlightIndices = indices;
        repaint();
    }

    public void setPolygonOverlay(List<double[]> vertices) {
        this.polygonVertices = vertices;
        repaint();
    }

    public void clearPolygonOverlay() {
        this.polygonVertices = null;
        this.polygonCompleted = false;
        repaint();
    }

    public void setPolygonCompleted(boolean completed) {
        this.polygonCompleted = completed;
        repaint();
    }

    public void resetView() {
        viewOverride = false;
        repaint();
        fireViewChanged();
    }

    // --- Coordinate conversion ---

    private double effectiveMinX() { return viewOverride ? viewMinX : minX; }
    private double effectiveMaxX() { return viewOverride ? viewMaxX : maxX; }
    private double effectiveMinY() { return viewOverride ? viewMinY : minY; }
    private double effectiveMaxY() { return viewOverride ? viewMaxY : maxY; }

    public double dataXToScreenX(double dataX) {
        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        return PADDING_LEFT + ((dataX - effectiveMinX()) / (effectiveMaxX() - effectiveMinX())) * plotW;
    }

    public double dataYToScreenY(double dataY) {
        double plotH = getHeight() - PADDING_TOP - PADDING_BOTTOM;
        return PADDING_TOP + plotH - ((dataY - effectiveMinY()) / (effectiveMaxY() - effectiveMinY())) * plotH;
    }

    public double screenXToDataX(double screenX) {
        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        if (plotW <= 0) return effectiveMinX();
        return effectiveMinX() + ((screenX - PADDING_LEFT) / plotW) * (effectiveMaxX() - effectiveMinX());
    }

    public double screenYToDataY(double screenY) {
        double plotH = getHeight() - PADDING_TOP - PADDING_BOTTOM;
        if (plotH <= 0) return effectiveMinY();
        return effectiveMinY() + ((PADDING_TOP + plotH - screenY) / plotH) * (effectiveMaxY() - effectiveMinY());
    }

    /**
     * Find the index of the nearest point within a screen distance threshold,
     * or -1 if nothing is close enough.
     * <p>
     * The click is converted to data space once and the comparison is done there,
     * scaled by the constant pixels-per-data-unit factors. Projecting every point to
     * screen space instead (two divisions, two multiplies and four additions each)
     * cost several times more per point for an identical answer, since the transform
     * is affine and preserves ordering by distance along each axis.
     */
    public int findNearestPoint(double screenX, double screenY, double maxScreenDist) {
        if (xValues == null || yValues == null || xValues.length == 0) return -1;

        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        double plotH = getHeight() - PADDING_TOP - PADDING_BOTTOM;
        if (plotW <= 0 || plotH <= 0) return -1;

        double rangeX = effectiveMaxX() - effectiveMinX();
        double rangeY = effectiveMaxY() - effectiveMinY();
        if (rangeX <= 0 || rangeY <= 0) return -1;

        // Pixels per data unit — the click and every point share this scale.
        double scaleX = plotW / rangeX;
        double scaleY = plotH / rangeY;
        double queryX = screenXToDataX(screenX);
        double queryY = screenYToDataY(screenY);

        double bestDist = maxScreenDist * maxScreenDist;
        int bestIdx = -1;
        int n = Math.min(xValues.length, yValues.length);
        for (int i = 0; i < n; i++) {
            double dx = (xValues[i] - queryX) * scaleX;
            double dy = (yValues[i] - queryY) * scaleY;
            double d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * Resolve a boolean mask to a strided list of point indices, capped at
     * {@code maxPoints}. Done once per mask change so repaints never rescan the mask.
     *
     * @return the indices, or null when {@code mask} is null
     */
    private static int[] maskToIndices(boolean[] mask, int n, int maxPoints) {
        if (mask == null) return null;
        int len = Math.min(n, mask.length);
        int total = 0;
        for (int i = 0; i < len; i++) if (mask[i]) total++;
        if (total == 0) return new int[0];

        int step = Math.max(1, total / maxPoints);
        int[] out = new int[(total + step - 1) / step];
        int seen = 0, w = 0;
        for (int i = 0; i < len && w < out.length; i++) {
            if (!mask[i]) continue;
            if (seen % step == 0) out[w++] = i;
            seen++;
        }
        return w == out.length ? out : Arrays.copyOf(out, w);
    }

    private void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        // Background
        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, w, h);

        if (xValues == null || yValues == null || xValues.length == 0) {
            gc.setFill(Color.gray(0.5));
            gc.setFont(Font.font(12));
            gc.fillText("No UMAP data", w / 2 - 35, h / 2);
            return;
        }

        double plotW = w - PADDING_LEFT - PADDING_RIGHT;
        double plotH = h - PADDING_TOP - PADDING_BOTTOM;
        double eMinX = effectiveMinX(), eMaxX = effectiveMaxX();
        double eMinY = effectiveMinY(), eMaxY = effectiveMaxY();
        double rangeX = eMaxX - eMinX;
        double rangeY = eMaxY - eMinY;

        // Draw dots with subsampling
        int step = Math.max(1, xValues.length / MAX_DISPLAY_POINTS);
        double halfDot = dotSize / 2;

        for (int i = 0; i < xValues.length; i += step) {
            if (Double.isNaN(xValues[i]) || Double.isNaN(yValues[i])) continue;
            if (visibleMask != null && i < visibleMask.length && !visibleMask[i]) continue;

            double px = PADDING_LEFT + ((xValues[i] - eMinX) / rangeX) * plotW;
            double py = PADDING_TOP + plotH - ((yValues[i] - eMinY) / rangeY) * plotH;

            // Skip points outside visible area
            if (px < PADDING_LEFT - dotSize || px > w - PADDING_RIGHT + dotSize ||
                py < PADDING_TOP - dotSize || py > h - PADDING_BOTTOM + dotSize) continue;

            // Point color
            if (pointColors != null && i < pointColors.length) {
                int c = pointColors[i];
                gc.setFill(Color.rgb((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 0.7));
            } else {
                gc.setFill(Color.rgb(100, 150, 200, 0.7));
            }
            gc.fillOval(px - halfDot, py - halfDot, dotSize, dotSize);
        }

        // Population rings — walks the pre-resolved index list, not the whole dataset.
        if (ringColors != null && ringIndices != null) {
            double ringR = halfDot + 2;
            gc.setLineWidth(1.5);
            for (int p = 0; p < ringColors.size() && p < ringIndices.size(); p++) {
                int[] indices = ringIndices.get(p);
                if (indices == null) continue;
                int rc = ringColors.get(p)[0];
                gc.setStroke(Color.rgb((rc >> 16) & 0xFF, (rc >> 8) & 0xFF, rc & 0xFF));

                for (int idx : indices) {
                    if (Double.isNaN(xValues[idx]) || Double.isNaN(yValues[idx])) continue;

                    double px = PADDING_LEFT + ((xValues[idx] - eMinX) / rangeX) * plotW;
                    double py = PADDING_TOP + plotH - ((yValues[idx] - eMinY) / rangeY) * plotH;

                    if (px < PADDING_LEFT - dotSize || px > w - PADDING_RIGHT + dotSize ||
                        py < PADDING_TOP - dotSize || py > h - PADDING_BOTTOM + dotSize) continue;

                    gc.strokeOval(px - ringR, py - ringR, ringR * 2, ringR * 2);
                }
            }
        }

        // Viewer-selection highlight — drawn last so it reads on top of everything.
        if (highlightIndices != null && highlightIndices.length > 0) {
            double markR = Math.max(halfDot + 3, 4.0);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(2.0);
            for (int idx : highlightIndices) {
                if (Double.isNaN(xValues[idx]) || Double.isNaN(yValues[idx])) continue;

                double px = PADDING_LEFT + ((xValues[idx] - eMinX) / rangeX) * plotW;
                double py = PADDING_TOP + plotH - ((yValues[idx] - eMinY) / rangeY) * plotH;

                if (px < PADDING_LEFT - markR || px > w - PADDING_RIGHT + markR ||
                    py < PADDING_TOP - markR || py > h - PADDING_BOTTOM + markR) continue;

                gc.strokeOval(px - markR, py - markR, markR * 2, markR * 2);
            }
        }

        // Draw polygon overlay
        if (polygonVertices != null && polygonVertices.size() >= 2) {
            if (polygonCompleted && polygonVertices.size() >= 3) {
                // Completed: solid yellow outline + white/yellow handles (FlowPath style)
                gc.setStroke(Color.YELLOW);
                gc.setLineWidth(1.5);
                double[] xp = new double[polygonVertices.size()];
                double[] yp = new double[polygonVertices.size()];
                for (int i = 0; i < polygonVertices.size(); i++) {
                    xp[i] = dataXToScreenX(polygonVertices.get(i)[0]);
                    yp[i] = dataYToScreenY(polygonVertices.get(i)[1]);
                }
                gc.strokePolygon(xp, yp, xp.length);

                // White-filled, yellow-stroked handles
                for (int i = 0; i < xp.length; i++) {
                    gc.setFill(Color.WHITE);
                    gc.setStroke(Color.YELLOW);
                    gc.setLineWidth(1.0);
                    gc.fillOval(xp[i] - HANDLE_RADIUS, yp[i] - HANDLE_RADIUS,
                            HANDLE_RADIUS * 2, HANDLE_RADIUS * 2);
                    gc.strokeOval(xp[i] - HANDLE_RADIUS, yp[i] - HANDLE_RADIUS,
                            HANDLE_RADIUS * 2, HANDLE_RADIUS * 2);
                }
                gc.setLineWidth(1.5);
            } else {
                // Drawing preview: dashed cyan lines + cyan circles
                gc.setStroke(Color.CYAN);
                gc.setLineWidth(1.5);
                gc.setLineDashes(6, 4);
                for (int i = 0; i < polygonVertices.size() - 1; i++) {
                    gc.strokeLine(
                        dataXToScreenX(polygonVertices.get(i)[0]),
                        dataYToScreenY(polygonVertices.get(i)[1]),
                        dataXToScreenX(polygonVertices.get(i + 1)[0]),
                        dataYToScreenY(polygonVertices.get(i + 1)[1])
                    );
                }
                gc.setLineDashes(null);

                gc.setFill(Color.CYAN);
                for (double[] v : polygonVertices) {
                    double sx = dataXToScreenX(v[0]);
                    double sy = dataYToScreenY(v[1]);
                    gc.fillOval(sx - 4, sy - 4, 8, 8);
                }
            }
        }

        // Border
        gc.setStroke(Color.gray(0.3));
        gc.setLineWidth(1);
        gc.strokeRect(PADDING_LEFT, PADDING_TOP, plotW, plotH);
    }

    /**
     * Ray-casting point-in-polygon test.
     */
    public static boolean pointInPolygon(double x, double y, List<double[]> vertices) {
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
}
