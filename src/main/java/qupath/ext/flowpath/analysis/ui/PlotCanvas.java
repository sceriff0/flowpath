package qupath.ext.flowpath.analysis.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

/**
 * Shared drawing base for the Analysis window's four plots: axes, tick labels, a legend,
 * and the two coordinate mappings every one of them needs — a continuous value onto the
 * plot's Y axis, and a category index onto its X axis.
 * <p>
 * <b>No plot-specific logic lives here.</b> What a bar or a segment <em>means</em> — which
 * rows count, how they are grouped, what "positive" or "leaf" means for this plot — is
 * exactly the reduction each subclass owns in its own {@code setRows(...)}. This class only
 * knows how to put a number on the canvas once that decision has already been made, the same
 * separation {@code ScatterPlotCanvas.branchAt} draws between "which branch" (the model's
 * job) and "where on screen" (the canvas's job).
 * <p>
 * Hand-drawn on {@link Canvas}, matching {@code ui.HistogramCanvas}, {@code
 * ui.ScatterPlotCanvas} and {@code umap.ui.UmapCanvas} — <b>not</b> JavaFX's {@code
 * BarChart}/{@code PieChart}, which are light-themed by default and styled through CSS, so
 * they would not match the dark canvases beside them.
 */
public abstract class PlotCanvas extends Canvas {

    protected static final double PADDING_LEFT = 45;
    protected static final double PADDING_RIGHT = 10;
    protected static final double PADDING_TOP = 10;
    protected static final double PADDING_BOTTOM = 30;
    protected static final double LEGEND_ROW_HEIGHT = 14;

    protected PlotCanvas(double width, double height) {
        super(width, height);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double height) { return getWidth(); }
    @Override public double prefHeight(double width) { return getHeight(); }
    @Override public double minWidth(double height) { return 200; }
    @Override public double minHeight(double width) { return 150; }
    @Override public double maxWidth(double height) { return Double.MAX_VALUE; }
    @Override public double maxHeight(double width) { return Double.MAX_VALUE; }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        repaint();
    }

    /** Redraw with whatever data the subclass currently holds. */
    protected abstract void repaint();

    protected double plotWidth() { return getWidth() - PADDING_LEFT - PADDING_RIGHT; }
    protected double plotHeight() { return getHeight() - PADDING_TOP - PADDING_BOTTOM; }

    /**
     * Map a data value onto this plot's Y axis, in screen space (smaller Y is higher up, so
     * {@code max} draws at the top). Mirrors {@code ScatterPlotCanvas.valueToPixel}'s
     * degenerate-range behaviour: a non-positive range floors everything at the axis
     * bottom rather than dividing by zero.
     */
    protected double valueToY(double v, double min, double max) {
        double plotH = plotHeight();
        if (max <= min) return PADDING_TOP + plotH;
        double frac = (v - min) / (max - min);
        frac = Math.max(0, Math.min(1, frac));
        return PADDING_TOP + plotH * (1 - frac);
    }

    /**
     * The X centre of the {@code index}-th of {@code count} equal-width category slots.
     * {@code count <= 0} returns the axis origin rather than dividing by zero — there is
     * nothing to space out yet.
     */
    protected double categoryToX(int index, int count) {
        if (count <= 0) return PADDING_LEFT;
        double slot = plotWidth() / count;
        return PADDING_LEFT + slot * index + slot / 2.0;
    }

    /** The width of one of {@code count} equal category slots — a bar's own footprint. */
    protected double categoryWidth(int count) {
        return count <= 0 ? 0 : plotWidth() / count;
    }

    /** The plot border, an optional X-axis label centred below it, and a rotated Y label. */
    protected void drawAxes(GraphicsContext gc, String xLabel, String yLabel) {
        double plotW = plotWidth();
        double plotH = plotHeight();

        gc.setStroke(Color.gray(0.3));
        gc.setLineWidth(1);
        gc.strokeRect(PADDING_LEFT, PADDING_TOP, plotW, plotH);

        gc.setFill(Color.gray(0.7));
        gc.setFont(Font.font(9));
        if (xLabel != null && !xLabel.isEmpty()) {
            gc.fillText(xLabel, PADDING_LEFT + plotW / 2 - xLabel.length() * 2.5, getHeight() - 3);
        }
        if (yLabel != null && !yLabel.isEmpty()) {
            gc.save();
            gc.translate(10, PADDING_TOP + plotH / 2);
            gc.rotate(-90);
            gc.fillText(yLabel, 0, 0);
            gc.restore();
        }
    }

    /**
     * A small colour-swatch legend, one row per label, in the plot's top-right corner.
     * {@code colors} holds packed {@code 0xRRGGBB} values parallel to {@code labels}; a
     * missing or short entry falls back to grey rather than throwing.
     */
    protected void drawLegend(GraphicsContext gc, List<String> labels, int[] colors) {
        if (labels == null || labels.isEmpty()) return;
        double x = getWidth() - PADDING_RIGHT - 90;
        double y = PADDING_TOP + 4;
        gc.setFont(Font.font(9));
        for (int i = 0; i < labels.size(); i++) {
            int rgb = colors != null && i < colors.length ? colors[i] : 0x808080;
            Color c = Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            double rowY = y + i * LEGEND_ROW_HEIGHT;
            gc.setFill(c);
            gc.fillRect(x, rowY, 10, 10);
            gc.setFill(Color.gray(0.85));
            gc.fillText(labels.get(i), x + 14, rowY + 9);
        }
    }
}
