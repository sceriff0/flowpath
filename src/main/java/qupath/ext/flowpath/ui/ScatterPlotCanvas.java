package qupath.ext.flowpath.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Canvas-based 2D scatter plot for visualizing cells on two marker axes.
 * Supports overlay of gate boundaries (polygon, rectangle, ellipse).
 * Subsamples for display if cell count exceeds MAX_DISPLAY_POINTS.
 */
public class ScatterPlotCanvas extends Canvas {

    private static final int MAX_DISPLAY_POINTS = 20000;
    private static final double PADDING_LEFT = 45;
    private static final double PADDING_RIGHT = 10;
    private static final double PADDING_TOP = 10;
    private static final double PADDING_BOTTOM = 30;
    private static final double DOT_SIZE = 2.0;

    private double[] xValues;
    private double[] yValues;
    private double minX, maxX, minY, maxY;
    private String labelX = "X";
    private String labelY = "Y";

    /**
     * The gate whose geometry both outlines the overlay and decides every dot's colour.
     * <p>
     * One field, not four parallel shape arrays: the shapes were always mutually
     * exclusive, and holding the gate itself is what makes {@link #branchAt} literally
     * the model's {@link GateNode#branchFor} rather than a second copy of it. When
     * {@link #setGateOverlay} is used, this <em>is</em> the gate the engine classifies
     * with, so plot and phenotype cannot drift apart.
     */
    private GateNode overlayGate;

    // Axis range overrides (null = use auto-computed from data)
    private Double overrideMinX, overrideMaxX, overrideMinY, overrideMaxY;

    private Color insideColor = Color.rgb(0, 200, 0, 0.6);
    private Color outsideColor = Color.rgb(128, 128, 128, 0.3);
    // Quadrant colors: [Q1(++), Q2(-+), Q3(+-), Q4(--)] — null means use inside/outside
    private Color[] quadrantColors;

    // Drawing interaction
    public enum DrawingMode { NONE, POLYGON, RECTANGLE, ELLIPSE }

    private DrawingMode drawingMode = DrawingMode.NONE;
    private final List<double[]> drawingVertices = new ArrayList<>();
    private double[] dragStart;
    private double[] dragCurrent;
    private Consumer<List<double[]>> onPolygonDrawn;
    private Consumer<double[]> onRectangleDrawn;
    private Consumer<double[]> onEllipseDrawn;

    // Handle editing existing overlays
    private int dragHandleIndex = -1;
    private static final double HANDLE_RADIUS = 6.0;
    private static final double HANDLE_HIT_RADIUS = 8.0;

    public ScatterPlotCanvas() {
        super(380, 300);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());

        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return 380; }
    @Override public double prefHeight(double w) { return 300; }
    @Override public double minWidth(double h) { return 200; }
    @Override public double minHeight(double w) { return 150; }
    @Override public double maxWidth(double h) { return Double.MAX_VALUE; }
    @Override public double maxHeight(double w) { return Double.MAX_VALUE; }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        repaint();
    }

    public void setData(double[] xValues, double[] yValues, String labelX, String labelY) {
        this.xValues = xValues;
        this.yValues = yValues;
        this.labelX = labelX;
        this.labelY = labelY;

        if (xValues == null || yValues == null || xValues.length == 0) {
            repaint();
            return;
        }

        // Compute ranges
        minX = Double.MAX_VALUE; maxX = -Double.MAX_VALUE;
        minY = Double.MAX_VALUE; maxY = -Double.MAX_VALUE;
        for (int i = 0; i < xValues.length; i++) {
            if (!Double.isNaN(xValues[i]) && !Double.isNaN(yValues[i])) {
                minX = Math.min(minX, xValues[i]);
                maxX = Math.max(maxX, xValues[i]);
                minY = Math.min(minY, yValues[i]);
                maxY = Math.max(maxY, yValues[i]);
            }
        }
        if (maxX <= minX) maxX = minX + 1;
        if (maxY <= minY) maxY = minY + 1;

        // Add 5% padding
        double padX = (maxX - minX) * 0.05;
        double padY = (maxY - minY) * 0.05;
        minX -= padX; maxX += padX;
        minY -= padY; maxY += padY;

        repaint();
    }

    /**
     * Show {@code gate} — its shape as the overlay outline, its geometry as the rule that
     * colours every dot. Pass the live gate: an in-place edit followed by a repaint is
     * then reflected without any copying, and the plot can never describe a different
     * shape than the one being gated on.
     * <p>
     * A gate whose shape is not drawable yet (a polygon with fewer than three vertices, a
     * rectangle or ellipse with no extent) is still installed. It classifies every cell as
     * outside, so the plot must draw every dot that way; leaving the overlay unset instead
     * painted the whole population as selected.
     */
    public void setGateOverlay(GateNode gate) {
        this.overlayGate = gate;
        repaint();
    }

    /** Show a standalone polygon. {@code vertices} is held live, not copied. */
    public void setPolygonOverlay(List<double[]> vertices) {
        PolygonGate gate = new PolygonGate();
        gate.setVertices(vertices);
        setGateOverlay(gate);
    }

    public void setRectangleOverlay(double minX, double maxX, double minY, double maxY) {
        RectangleGate gate = new RectangleGate();
        gate.setMinX(minX); gate.setMaxX(maxX);
        gate.setMinY(minY); gate.setMaxY(maxY);
        setGateOverlay(gate);
    }

    public void setEllipseOverlay(double cx, double cy, double rx, double ry) {
        EllipseGate gate = new EllipseGate();
        gate.setCenterX(cx); gate.setCenterY(cy);
        gate.setRadiusX(rx); gate.setRadiusY(ry);
        setGateOverlay(gate);
    }

    public void setCrosshairOverlay(double thresholdX, double thresholdY) {
        QuadrantGate gate = new QuadrantGate();
        gate.setThresholdX(thresholdX);
        gate.setThresholdY(thresholdY);
        setGateOverlay(gate);
    }

    public void clearOverlay() {
        setGateOverlay(null);
    }

    // ---- Typed views of the overlay, for drawing and handle editing ----

    private PolygonGate polygonOverlay() {
        return overlayGate instanceof PolygonGate g ? g : null;
    }

    private RectangleGate rectOverlay() {
        return overlayGate instanceof RectangleGate g ? g : null;
    }

    private EllipseGate ellipseOverlay() {
        return overlayGate instanceof EllipseGate g ? g : null;
    }

    private QuadrantGate crosshairOverlay() {
        return overlayGate instanceof QuadrantGate g ? g : null;
    }

    /**
     * Is there enough shape to draw an outline and offer edit handles? Colouring never
     * asks this — a degenerate region gate colours every dot "outside", which is what it
     * classifies — but stroking a two-vertex polygon or hanging drag handles off a
     * zero-size rectangle at the origin would only mislead.
     */
    private boolean hasDrawableShape() {
        if (overlayGate instanceof PolygonGate g) return g.getVertices().size() >= 3;
        if (overlayGate instanceof RectangleGate g) {
            return g.getMaxX() - g.getMinX() > 1e-10 && g.getMaxY() - g.getMinY() > 1e-10;
        }
        if (overlayGate instanceof EllipseGate g) {
            return g.getRadiusX() > 1e-10 && g.getRadiusY() > 1e-10;
        }
        return overlayGate != null;
    }

    public void setAxisRange(Double minX, Double maxX, Double minY, Double maxY) {
        this.overrideMinX = minX;
        this.overrideMaxX = maxX;
        this.overrideMinY = minY;
        this.overrideMaxY = maxY;
        repaint();
    }

    public void clearAxisRange() {
        this.overrideMinX = null;
        this.overrideMaxX = null;
        this.overrideMinY = null;
        this.overrideMaxY = null;
        repaint();
    }

    public void setInsideColor(Color c) { this.insideColor = c; repaint(); }
    public void setOutsideColor(Color c) { this.outsideColor = c; repaint(); }

    /** Set 4 quadrant colors for crosshair overlay: Q1(++), Q2(-+), Q3(+-), Q4(--). */
    public void setQuadrantColors(Color q1, Color q2, Color q3, Color q4) {
        this.quadrantColors = new Color[]{q1, q2, q3, q4};
        repaint();
    }

    // ---- Effective axis bounds (override if set, otherwise auto-computed) ----

    private double effectiveMinX() {
        return overrideMinX != null ? overrideMinX : minX;
    }
    private double effectiveMaxX() {
        return overrideMaxX != null ? overrideMaxX : maxX;
    }
    private double effectiveMinY() {
        return overrideMinY != null ? overrideMinY : minY;
    }
    private double effectiveMaxY() {
        return overrideMaxY != null ? overrideMaxY : maxY;
    }

    // ---- Coordinate conversion helpers ----

    public static double valueToPixel(double value, double min, double max, double plotSize) {
        if (max <= min) return 0;
        return ((value - min) / (max - min)) * plotSize;
    }

    public static double pixelToValue(double pixel, double min, double max, double plotSize) {
        if (plotSize <= 0) return min;
        return min + (pixel / plotSize) * (max - min);
    }

    public double dataXToScreenX(double dataX) {
        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        return PADDING_LEFT + valueToPixel(dataX, effectiveMinX(), effectiveMaxX(), plotW);
    }

    public double dataYToScreenY(double dataY) {
        double plotH = getHeight() - PADDING_TOP - PADDING_BOTTOM;
        return PADDING_TOP + plotH - valueToPixel(dataY, effectiveMinY(), effectiveMaxY(), plotH);
    }

    public double screenXToDataX(double screenX) {
        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        return pixelToValue(screenX - PADDING_LEFT, effectiveMinX(), effectiveMaxX(), plotW);
    }

    public double screenYToDataY(double screenY) {
        double plotH = getHeight() - PADDING_TOP - PADDING_BOTTOM;
        return pixelToValue(PADDING_TOP + plotH - screenY, effectiveMinY(), effectiveMaxY(), plotH);
    }

    // ---- Drawing mode API ----

    public void setDrawingMode(DrawingMode mode) {
        this.drawingMode = mode;
        drawingVertices.clear();
        dragStart = null;
        dragCurrent = null;
        dragHandleIndex = -1;
        repaint();
    }

    public DrawingMode getDrawingMode() { return drawingMode; }

    public void setOnPolygonDrawn(Consumer<List<double[]>> cb) { this.onPolygonDrawn = cb; }
    public void setOnRectangleDrawn(Consumer<double[]> cb) { this.onRectangleDrawn = cb; }
    public void setOnEllipseDrawn(Consumer<double[]> cb) { this.onEllipseDrawn = cb; }

    // ---- Mouse interaction ----

    private void handleMousePressed(MouseEvent e) {
        if (drawingMode == DrawingMode.NONE) return;

        double sx = e.getX();
        double sy = e.getY();

        // Check for handle hit first (editing existing overlay), but not while drawing a new polygon
        int handle = (drawingMode == DrawingMode.POLYGON && !drawingVertices.isEmpty()) ? -1 : findHandle(sx, sy);
        if (handle >= 0) {
            dragHandleIndex = handle;
            dragStart = new double[]{sx, sy};
            dragCurrent = new double[]{sx, sy};
            e.consume();
            return;
        }

        if (drawingMode == DrawingMode.POLYGON) {
            if (e.getClickCount() == 2) {
                // Double-click: close polygon if enough vertices
                if (drawingVertices.size() >= 3 && onPolygonDrawn != null) {
                    onPolygonDrawn.accept(new ArrayList<>(drawingVertices));
                }
                drawingVertices.clear();
                repaint();
            } else if (e.getClickCount() == 1) {
                // Single-click: add vertex
                double dx = screenXToDataX(sx);
                double dy = screenYToDataY(sy);
                drawingVertices.add(new double[]{dx, dy});
                repaint();
            }
        } else {
            // RECTANGLE or ELLIPSE: start drag
            dragStart = new double[]{sx, sy};
            dragCurrent = new double[]{sx, sy};
        }
        e.consume();
    }

    private void handleMouseDragged(MouseEvent e) {
        if (drawingMode == DrawingMode.NONE) return;

        double sx = e.getX();
        double sy = e.getY();

        if (dragHandleIndex >= 0) {
            // Dragging an existing handle — update overlay
            updateHandleDrag(sx, sy);
            repaint();
            e.consume();
            return;
        }

        if (drawingMode == DrawingMode.RECTANGLE || drawingMode == DrawingMode.ELLIPSE) {
            dragCurrent = new double[]{sx, sy};
            repaint();
        }
        e.consume();
    }

    private void handleMouseReleased(MouseEvent e) {
        if (drawingMode == DrawingMode.NONE) return;

        double sx = e.getX();
        double sy = e.getY();

        if (dragHandleIndex >= 0) {
            updateHandleDrag(sx, sy);
            fireHandleDragComplete();
            dragHandleIndex = -1;
            dragStart = null;
            dragCurrent = null;
            repaint();
            e.consume();
            return;
        }

        if (drawingMode == DrawingMode.RECTANGLE && dragStart != null) {
            double x1 = screenXToDataX(dragStart[0]);
            double y1 = screenYToDataY(dragStart[1]);
            double x2 = screenXToDataX(sx);
            double y2 = screenYToDataY(sy);
            double rMinX = Math.min(x1, x2), rMaxX = Math.max(x1, x2);
            double rMinY = Math.min(y1, y2), rMaxY = Math.max(y1, y2);
            if (rMaxX - rMinX > 1e-10 && rMaxY - rMinY > 1e-10 && onRectangleDrawn != null) {
                onRectangleDrawn.accept(new double[]{rMinX, rMaxX, rMinY, rMaxY});
            }
            dragStart = null;
            dragCurrent = null;
            repaint();
        } else if (drawingMode == DrawingMode.ELLIPSE && dragStart != null) {
            double x1 = screenXToDataX(dragStart[0]);
            double y1 = screenYToDataY(dragStart[1]);
            double x2 = screenXToDataX(sx);
            double y2 = screenYToDataY(sy);
            double cx = (x1 + x2) / 2.0;
            double cy = (y1 + y2) / 2.0;
            double rx = Math.abs(x2 - x1) / 2.0;
            double ry = Math.abs(y2 - y1) / 2.0;
            if (rx > 1e-10 && ry > 1e-10 && onEllipseDrawn != null) {
                onEllipseDrawn.accept(new double[]{cx, cy, rx, ry});
            }
            dragStart = null;
            dragCurrent = null;
            repaint();
        }
        e.consume();
    }

    // ---- Handle hit-testing and dragging ----

    private int findHandle(double sx, double sy) {
        if (!hasDrawableShape()) return -1;
        PolygonGate polygon = polygonOverlay();
        if (polygon != null) {
            List<double[]> vertices = polygon.getVertices();
            for (int i = 0; i < vertices.size(); i++) {
                double hx = dataXToScreenX(vertices.get(i)[0]);
                double hy = dataYToScreenY(vertices.get(i)[1]);
                if (Math.hypot(sx - hx, sy - hy) <= HANDLE_HIT_RADIUS) return i;
            }
        }
        if (rectOverlay() != null) {
            // Handles: 0=topLeft, 1=topRight, 2=bottomRight, 3=bottomLeft
            double[][] corners = getRectHandleScreenPositions();
            for (int i = 0; i < corners.length; i++) {
                if (Math.hypot(sx - corners[i][0], sy - corners[i][1]) <= HANDLE_HIT_RADIUS) return i;
            }
        }
        if (ellipseOverlay() != null) {
            // Handles: 0=top, 1=right, 2=bottom, 3=left (cardinal points)
            double[][] cardinals = getEllipseHandleScreenPositions();
            for (int i = 0; i < cardinals.length; i++) {
                if (Math.hypot(sx - cardinals[i][0], sy - cardinals[i][1]) <= HANDLE_HIT_RADIUS) return i;
            }
        }
        return -1;
    }

    private double[][] getRectHandleScreenPositions() {
        RectangleGate rect = rectOverlay();
        if (rect == null) return new double[0][];
        double x0 = dataXToScreenX(rect.getMinX());
        double x1 = dataXToScreenX(rect.getMaxX());
        double y0 = dataYToScreenY(rect.getMaxY()); // maxY -> top of screen
        double y1 = dataYToScreenY(rect.getMinY()); // minY -> bottom of screen
        return new double[][]{{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
    }

    private double[][] getEllipseHandleScreenPositions() {
        EllipseGate ellipse = ellipseOverlay();
        if (ellipse == null) return new double[0][];
        double cx = dataXToScreenX(ellipse.getCenterX());
        double cy = dataYToScreenY(ellipse.getCenterY());
        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        double plotH = getHeight() - PADDING_TOP - PADDING_BOTTOM;
        double erx = valueToPixel(ellipse.getRadiusX(), 0, effectiveMaxX() - effectiveMinX(), plotW);
        double ery = valueToPixel(ellipse.getRadiusY(), 0, effectiveMaxY() - effectiveMinY(), plotH);
        // top, right, bottom, left
        return new double[][]{{cx, cy - ery}, {cx + erx, cy}, {cx, cy + ery}, {cx - erx, cy}};
    }

    private void updateHandleDrag(double sx, double sy) {
        double dx = screenXToDataX(sx);
        double dy = screenYToDataY(sy);

        PolygonGate polygon = polygonOverlay();
        RectangleGate rect = rectOverlay();
        EllipseGate ellipse = ellipseOverlay();

        if (polygon != null && dragHandleIndex >= 0 && dragHandleIndex < polygon.getVertices().size()) {
            polygon.getVertices().get(dragHandleIndex)[0] = dx;
            polygon.getVertices().get(dragHandleIndex)[1] = dy;
        } else if (rect != null && dragHandleIndex >= 0 && dragHandleIndex < 4) {
            // Move corner: 0=topLeft, 1=topRight, 2=bottomRight, 3=bottomLeft
            switch (dragHandleIndex) {
                case 0 -> { rect.setMinX(dx); rect.setMaxY(dy); }
                case 1 -> { rect.setMaxX(dx); rect.setMaxY(dy); }
                case 2 -> { rect.setMaxX(dx); rect.setMinY(dy); }
                case 3 -> { rect.setMinX(dx); rect.setMinY(dy); }
            }
            // Ensure min < max
            if (rect.getMinX() > rect.getMaxX()) {
                double t = rect.getMinX(); rect.setMinX(rect.getMaxX()); rect.setMaxX(t);
            }
            if (rect.getMinY() > rect.getMaxY()) {
                double t = rect.getMinY(); rect.setMinY(rect.getMaxY()); rect.setMaxY(t);
            }
        } else if (ellipse != null && dragHandleIndex >= 0 && dragHandleIndex < 4) {
            // Cardinal points: 0=top, 1=right, 2=bottom, 3=left
            switch (dragHandleIndex) {
                case 0 -> ellipse.setRadiusY(Math.abs(ellipse.getCenterY() - dy)); // top
                case 1 -> ellipse.setRadiusX(Math.abs(dx - ellipse.getCenterX())); // right
                case 2 -> ellipse.setRadiusY(Math.abs(dy - ellipse.getCenterY())); // bottom
                case 3 -> ellipse.setRadiusX(Math.abs(ellipse.getCenterX() - dx)); // left
            }
        }
    }

    private void fireHandleDragComplete() {
        PolygonGate polygon = polygonOverlay();
        RectangleGate rect = rectOverlay();
        EllipseGate ellipse = ellipseOverlay();
        if (polygon != null && polygon.getVertices().size() >= 3 && onPolygonDrawn != null) {
            onPolygonDrawn.accept(new ArrayList<>(polygon.getVertices()));
        } else if (rect != null && onRectangleDrawn != null) {
            onRectangleDrawn.accept(new double[]{rect.getMinX(), rect.getMaxX(), rect.getMinY(), rect.getMaxY()});
        } else if (ellipse != null && onEllipseDrawn != null) {
            onEllipseDrawn.accept(new double[]{ellipse.getCenterX(), ellipse.getCenterY(),
                    ellipse.getRadiusX(), ellipse.getRadiusY()});
        }
    }

    private void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, w, h);

        if (xValues == null || yValues == null || xValues.length == 0) {
            gc.setFill(Color.gray(0.5));
            gc.setFont(Font.font(12));
            gc.fillText("No data", w / 2 - 20, h / 2);
            return;
        }

        double plotW = w - PADDING_LEFT - PADDING_RIGHT;
        double plotH = h - PADDING_TOP - PADDING_BOTTOM;

        // Draw dots — skip points outside the axis range so they don't smear
        // onto axis labels or the canvas border. Track drawn vs. input count
        // so we can surface "outside clip range" messaging when none render.
        int step = Math.max(1, xValues.length / MAX_DISPLAY_POINTS);
        double eMinX = effectiveMinX(), eMaxX = effectiveMaxX();
        double eMinY = effectiveMinY(), eMaxY = effectiveMaxY();
        int validInput = 0;
        int pointsDrawn = 0;
        for (int i = 0; i < xValues.length; i += step) {
            if (Double.isNaN(xValues[i]) || Double.isNaN(yValues[i])) continue;
            validInput++;
            if (xValues[i] < eMinX || xValues[i] > eMaxX
                    || yValues[i] < eMinY || yValues[i] > eMaxY) continue;
            double px = PADDING_LEFT + valueToPixel(xValues[i], eMinX, eMaxX, plotW);
            double py = PADDING_TOP + plotH - valueToPixel(yValues[i], eMinY, eMaxY, plotH);

            gc.setFill(getPointColor(xValues[i], yValues[i]));
            gc.fillOval(px - DOT_SIZE / 2, py - DOT_SIZE / 2, DOT_SIZE, DOT_SIZE);
            pointsDrawn++;
        }

        // Draw gate overlay
        drawOverlay(gc, plotW, plotH);

        // Draw in-progress drawing preview
        drawDrawingPreview(gc, plotW, plotH);

        // Border
        gc.setStroke(Color.gray(0.3));
        gc.setLineWidth(1);
        gc.strokeRect(PADDING_LEFT, PADDING_TOP, plotW, plotH);

        // Axis labels
        gc.setFill(Color.gray(0.7));
        gc.setFont(Font.font(9));
        gc.fillText(String.format("%.2f", effectiveMinX()), PADDING_LEFT, h - 3);
        gc.fillText(String.format("%.2f", effectiveMaxX()), PADDING_LEFT + plotW - 30, h - 3);
        gc.fillText(labelX, PADDING_LEFT + plotW / 2 - 10, h - 3);

        gc.save();
        gc.translate(10, PADDING_TOP + plotH / 2);
        gc.rotate(-90);
        gc.fillText(labelY, 0, 0);
        gc.restore();

        // Cells exist but all sit outside the current clip percentile range
        // (common for tail populations on a correlated child marker). Tell
        // the user instead of leaving the plot apparently empty — they can
        // widen the gate's clip percentiles to see the data.
        if (validInput > 0 && pointsDrawn == 0) {
            gc.setFill(Color.gray(0.7));
            gc.setFont(Font.font(12));
            String msg = String.format("%,d cells outside %s / %s clip range",
                    validInput, labelX, labelY);
            double approxW = msg.length() * 6.5;
            double centerX = PADDING_LEFT + plotW / 2 - approxW / 2;
            gc.fillText(msg, Math.max(PADDING_LEFT + 4, centerX), PADDING_TOP + plotH / 2);
        }
    }

    private Color getPointColor(double x, double y) {
        int branch = branchAt(x, y);
        if (quadrantColors != null && overlayGate instanceof QuadrantGate) {
            return quadrantColors[branch];
        }
        return branch == 0 ? insideColor : outsideColor;
    }

    /**
     * Which branch of the overlay gate a point at plot-space {@code (x, y)} is drawn as.
     * <p>
     * This is the whole hit test, and it is one delegation: the geometry belongs to the
     * gate. The canvas used to re-implement rectangle bounds, the ellipse equation,
     * polygon ray casting and the quadrant comparison here, which is how a dot could be
     * painted as selected while {@code GatingEngine} put the same cell in another branch.
     * With no overlay at all, everything is branch 0.
     */
    int branchAt(double x, double y) {
        return overlayGate == null ? 0 : overlayGate.branchFor(x, y);
    }

    private void drawOverlay(GraphicsContext gc, double plotW, double plotH) {
        if (!hasDrawableShape()) return;
        gc.setStroke(Color.YELLOW);
        gc.setLineWidth(1.5);

        PolygonGate polygon = polygonOverlay();
        if (polygon != null) {
            List<double[]> vertices = polygon.getVertices();
            double[] xp = new double[vertices.size()];
            double[] yp = new double[vertices.size()];
            for (int i = 0; i < vertices.size(); i++) {
                xp[i] = dataXToScreenX(vertices.get(i)[0]);
                yp[i] = dataYToScreenY(vertices.get(i)[1]);
            }
            gc.strokePolygon(xp, yp, xp.length);

            // Draw handles at vertices
            drawHandles(gc, xp, yp);
        }

        RectangleGate rect = rectOverlay();
        if (rect != null) {
            double rx = dataXToScreenX(rect.getMinX());
            double ry = dataYToScreenY(rect.getMaxY());
            double rw = valueToPixel(rect.getMaxX(), effectiveMinX(), effectiveMaxX(), plotW)
                    - valueToPixel(rect.getMinX(), effectiveMinX(), effectiveMaxX(), plotW);
            double rh = valueToPixel(rect.getMaxY(), effectiveMinY(), effectiveMaxY(), plotH)
                    - valueToPixel(rect.getMinY(), effectiveMinY(), effectiveMaxY(), plotH);
            gc.strokeRect(rx, ry, rw, rh);

            // Draw handles at corners
            double[][] corners = getRectHandleScreenPositions();
            drawHandles(gc, arrayCol(corners, 0), arrayCol(corners, 1));
        }

        EllipseGate ellipse = ellipseOverlay();
        if (ellipse != null) {
            double cx = dataXToScreenX(ellipse.getCenterX());
            double cy = dataYToScreenY(ellipse.getCenterY());
            double erx = valueToPixel(ellipse.getRadiusX(), 0, effectiveMaxX() - effectiveMinX(), plotW);
            double ery = valueToPixel(ellipse.getRadiusY(), 0, effectiveMaxY() - effectiveMinY(), plotH);
            gc.strokeOval(cx - erx, cy - ery, erx * 2, ery * 2);

            // Draw handles at cardinal points
            double[][] cardinals = getEllipseHandleScreenPositions();
            drawHandles(gc, arrayCol(cardinals, 0), arrayCol(cardinals, 1));
        }

        QuadrantGate crosshair = crosshairOverlay();
        if (crosshair != null) {
            double vx = dataXToScreenX(crosshair.getThresholdX());
            double hy = dataYToScreenY(crosshair.getThresholdY());
            gc.strokeLine(vx, PADDING_TOP, vx, PADDING_TOP + plotH);
            gc.strokeLine(PADDING_LEFT, hy, PADDING_LEFT + plotW, hy);
        }
    }

    private void drawHandles(GraphicsContext gc, double[] hx, double[] hy) {
        gc.setFill(Color.WHITE);
        gc.setStroke(Color.YELLOW);
        gc.setLineWidth(1.0);
        for (int i = 0; i < hx.length; i++) {
            gc.fillOval(hx[i] - HANDLE_RADIUS, hy[i] - HANDLE_RADIUS, HANDLE_RADIUS * 2, HANDLE_RADIUS * 2);
            gc.strokeOval(hx[i] - HANDLE_RADIUS, hy[i] - HANDLE_RADIUS, HANDLE_RADIUS * 2, HANDLE_RADIUS * 2);
        }
        gc.setLineWidth(1.5);
    }

    private static double[] arrayCol(double[][] arr, int col) {
        double[] result = new double[arr.length];
        for (int i = 0; i < arr.length; i++) result[i] = arr[i][col];
        return result;
    }

    private void drawDrawingPreview(GraphicsContext gc, double plotW, double plotH) {
        gc.setStroke(Color.CYAN);
        gc.setLineWidth(1.5);
        gc.setLineDashes(6, 4);

        if (drawingMode == DrawingMode.POLYGON && !drawingVertices.isEmpty()) {
            // Draw lines connecting vertices
            for (int i = 0; i < drawingVertices.size() - 1; i++) {
                double x1 = dataXToScreenX(drawingVertices.get(i)[0]);
                double y1 = dataYToScreenY(drawingVertices.get(i)[1]);
                double x2 = dataXToScreenX(drawingVertices.get(i + 1)[0]);
                double y2 = dataYToScreenY(drawingVertices.get(i + 1)[1]);
                gc.strokeLine(x1, y1, x2, y2);
            }
            // Draw small circles at each vertex
            gc.setFill(Color.CYAN);
            for (double[] v : drawingVertices) {
                double sx = dataXToScreenX(v[0]);
                double sy = dataYToScreenY(v[1]);
                gc.fillOval(sx - 4, sy - 4, 8, 8);
            }
        }

        if ((drawingMode == DrawingMode.RECTANGLE) && dragStart != null && dragCurrent != null) {
            double x = Math.min(dragStart[0], dragCurrent[0]);
            double y = Math.min(dragStart[1], dragCurrent[1]);
            double rw = Math.abs(dragCurrent[0] - dragStart[0]);
            double rh = Math.abs(dragCurrent[1] - dragStart[1]);
            gc.strokeRect(x, y, rw, rh);
        }

        if ((drawingMode == DrawingMode.ELLIPSE) && dragStart != null && dragCurrent != null) {
            double x = Math.min(dragStart[0], dragCurrent[0]);
            double y = Math.min(dragStart[1], dragCurrent[1]);
            double ew = Math.abs(dragCurrent[0] - dragStart[0]);
            double eh = Math.abs(dragCurrent[1] - dragStart[1]);
            gc.strokeOval(x, y, ew, eh);
        }

        gc.setLineDashes(null);
    }
}
