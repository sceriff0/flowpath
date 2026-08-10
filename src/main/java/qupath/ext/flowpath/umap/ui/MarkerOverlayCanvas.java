package qupath.ext.flowpath.umap.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.Arrays;

/**
 * Canvas-based 2D scatter plot for marker expression overlay on UMAP.
 * Colors points by z-score or raw intensity using a continuous color scale.
 */
public class MarkerOverlayCanvas extends Canvas {

    private static final int MAX_DISPLAY_POINTS = 30000;
    private static final double PADDING = 10;

    public enum ColorScale {
        BLUE_WHITE_RED(ColorScaleMapper.Scale.BLUE_WHITE_RED),
        VIRIDIS(ColorScaleMapper.Scale.VIRIDIS);

        final ColorScaleMapper.Scale mapperScale;
        ColorScale(ColorScaleMapper.Scale s) { this.mapperScale = s; }
    }

    private double[] xValues;
    private double[] yValues;
    private double[] markerValues;
    private String markerName;
    private ColorScale colorScale = ColorScale.BLUE_WHITE_RED;
    private double dotSize = 2.0;
    private double colorMin, colorMax;

    // Shared view state with UmapCanvas
    private double viewMinX, viewMaxX, viewMinY, viewMaxY;
    private boolean viewOverride = false;
    private double dataMinX, dataMaxX, dataMinY, dataMaxY;

    public MarkerOverlayCanvas() {
        super(400, 400);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());
    }

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

        if (xValues != null && xValues.length > 0) {
            dataMinX = Double.MAX_VALUE; dataMaxX = -Double.MAX_VALUE;
            dataMinY = Double.MAX_VALUE; dataMaxY = -Double.MAX_VALUE;
            for (int i = 0; i < xValues.length; i++) {
                if (Double.isFinite(xValues[i]) && Double.isFinite(yValues[i])) {
                    dataMinX = Math.min(dataMinX, xValues[i]);
                    dataMaxX = Math.max(dataMaxX, xValues[i]);
                    dataMinY = Math.min(dataMinY, yValues[i]);
                    dataMaxY = Math.max(dataMaxY, yValues[i]);
                }
            }
            if (dataMinX == Double.MAX_VALUE) { dataMinX = 0; dataMaxX = 1; dataMinY = 0; dataMaxY = 1; }
            if (dataMaxX <= dataMinX) dataMaxX = dataMinX + 1;
            if (dataMaxY <= dataMinY) dataMaxY = dataMinY + 1;
            double padX = (dataMaxX - dataMinX) * 0.05;
            double padY = (dataMaxY - dataMinY) * 0.05;
            dataMinX -= padX; dataMaxX += padX;
            dataMinY -= padY; dataMaxY += padY;
        }
        repaint();
    }

    public void setMarkerValues(double[] values, String name, ColorScale scale) {
        this.markerValues = values;
        this.markerName = name;
        this.colorScale = scale;

        if (values != null && values.length > 0) {
            // Use 1st-99th percentile to avoid outlier compression
            double[] finite = sortedFiniteSample(values, PERCENTILE_SAMPLE_SIZE);
            if (finite.length == 0) {
                colorMin = 0; colorMax = 1;
            } else {
                double[] range = computePercentileRange(finite);
                colorMin = range[0];
                colorMax = range[1];
                // Guard against divide-by-zero in mapColor when all values collapse
                // to a single point (e.g. degenerate data).
                if (colorMax <= colorMin) colorMax = colorMin + 1;
            }
        }
        repaint();
    }

    /**
     * Cap on how many values are sorted to estimate the display percentiles.
     * <p>
     * The colour range only needs the 1st/99th percentile, and those are stable to
     * well under a display step by ~50K samples. Sorting every value instead made
     * changing the marker dropdown an O(N log N) operation <em>on the FX thread</em> —
     * a multi-second freeze on a large slide for what is the most casual interaction
     * in the panel.
     */
    private static final int PERCENTILE_SAMPLE_SIZE = 50_000;

    /**
     * Collect finite values into a sorted array, striding the input when it is larger
     * than {@code maxSamples}.
     * <p>
     * A fixed stride (rather than random sampling) keeps the result deterministic, so
     * the legend does not shift between two renders of the same data. UMAP output
     * order carries no relationship to marker intensity, so a stride is unbiased here.
     *
     * @return sorted finite values; empty if the input has none
     */
    static double[] sortedFiniteSample(double[] values, int maxSamples) {
        int n = values.length;
        int step = Math.max(1, n / Math.max(1, maxSamples));
        int capacity = n / step + 1;
        double[] out = new double[capacity];
        int count = 0;
        for (int i = 0; i < n && count < capacity; i += step) {
            double v = values[i];
            if (Double.isFinite(v)) out[count++] = v;
        }
        Arrays.sort(out, 0, count);
        return count == out.length ? out : Arrays.copyOf(out, count);
    }

    /**
     * Compute the 1st/99th percentile color range from a sorted, finite-valued array.
     * <p>
     * Uses {@code Math.round} (not floor-via-cast) on the percentile index, then clamps
     * to valid array bounds. The high index is also reduced by 1 so that for an array
     * of length 100 we don't pick the absolute maximum (index 99) as the "99th
     * percentile" — we want the value just inside the extreme. The previous
     * floor-via-cast on {@code length * 0.01} always rounded down to 0 for any
     * {@code length <= 99}, returning the absolute minimum instead of a percentile.
     * <p>
     * For very small arrays the two indices may collide; callers should guard against
     * a zero-width range (e.g. by adding 1 to the high) before using the result for
     * normalized color mapping. This helper does not enforce a non-zero range so that
     * single-value or degenerate inputs return a faithful {@code {v, v}} pair.
     *
     * @param finite sorted, finite-only values (must be non-empty)
     * @return a two-element array {@code {low, high}} where {@code high >= low}
     * @throws IllegalArgumentException if {@code finite} is null or empty
     */
    static double[] computePercentileRange(double[] finite) {
        if (finite == null || finite.length == 0) {
            throw new IllegalArgumentException("finite must be non-empty");
        }
        int loIdx = Math.min(finite.length - 1, (int) Math.round(finite.length * 0.01));
        int hiIdx = Math.max(0, (int) Math.round(finite.length * 0.99) - 1);
        if (hiIdx < loIdx) hiIdx = loIdx;
        return new double[]{finite[loIdx], finite[hiIdx]};
    }

    public void setDotSize(double size) { this.dotSize = size; repaint(); }

    public void syncView(double vMinX, double vMaxX, double vMinY, double vMaxY, boolean override) {
        this.viewMinX = vMinX;
        this.viewMaxX = vMaxX;
        this.viewMinY = vMinY;
        this.viewMaxY = vMaxY;
        this.viewOverride = override;
        repaint();
    }

    public double getColorMin() { return colorMin; }
    public double getColorMax() { return colorMax; }
    public ColorScale getColorScale() { return colorScale; }

    private double eMinX() { return viewOverride ? viewMinX : dataMinX; }
    private double eMaxX() { return viewOverride ? viewMaxX : dataMaxX; }
    private double eMinY() { return viewOverride ? viewMinY : dataMinY; }
    private double eMaxY() { return viewOverride ? viewMaxY : dataMaxY; }

    private void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, w, h);

        if (xValues == null || markerValues == null || xValues.length == 0) {
            gc.setFill(Color.gray(0.5));
            gc.setFont(Font.font(12));
            String msg = markerName != null ? markerName : "Select a marker";
            gc.fillText(msg, w / 2 - 35, h / 2);
            return;
        }

        double plotW = w - 2 * PADDING;
        double plotH = h - 2 * PADDING;
        double emx = eMinX(), eMx = eMaxX(), emy = eMinY(), eMy = eMaxY();
        double rangeX = eMx - emx, rangeY = eMy - emy;

        int step = Math.max(1, xValues.length / MAX_DISPLAY_POINTS);
        double halfDot = dotSize / 2;

        for (int i = 0; i < xValues.length; i += step) {
            if (Double.isNaN(xValues[i]) || Double.isNaN(yValues[i])) continue;

            double px = PADDING + ((xValues[i] - emx) / rangeX) * plotW;
            double py = PADDING + plotH - ((yValues[i] - emy) / rangeY) * plotH;

            if (px < -dotSize || px > w + dotSize || py < -dotSize || py > h + dotSize) continue;

            double val = i < markerValues.length ? markerValues[i] : 0;
            gc.setFill(mapColor(val));
            gc.fillOval(px - halfDot, py - halfDot, dotSize, dotSize);
        }

        // Title
        gc.setFill(Color.gray(0.7));
        gc.setFont(Font.font(10));
        if (markerName != null) {
            gc.fillText(markerName, PADDING + 2, PADDING + 12);
        }

        // Border
        gc.setStroke(Color.gray(0.3));
        gc.setLineWidth(1);
        gc.strokeRect(PADDING, PADDING, plotW, plotH);
    }

    private Color mapColor(double value) {
        double t = (value - colorMin) / (colorMax - colorMin);
        return ColorScaleMapper.map(t, colorScale.mapperScale, 0.8);
    }
}
