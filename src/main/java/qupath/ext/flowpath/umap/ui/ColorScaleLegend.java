package qupath.ext.flowpath.umap.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.Locale;

/**
 * Vertical gradient legend for continuous color scales (z-score or raw intensity).
 */
public class ColorScaleLegend extends Canvas {

    private double minValue;
    private double maxValue;
    private MarkerOverlayCanvas.ColorScale colorScale;
    private String label;
    private String scaleType;

    public ColorScaleLegend() {
        super(30, 200);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return 30; }
    @Override public double prefHeight(double w) { return 200; }
    @Override public double minWidth(double h) { return 25; }
    @Override public double minHeight(double w) { return 80; }
    @Override public double maxWidth(double h) { return 40; }
    @Override public double maxHeight(double w) { return Double.MAX_VALUE; }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        repaint();
    }

    public void setScale(double min, double max, MarkerOverlayCanvas.ColorScale scale, String label) {
        setScale(min, max, scale, label, null);
    }

    public void setScale(double min, double max, MarkerOverlayCanvas.ColorScale scale, String label, String scaleType) {
        this.minValue = min;
        this.maxValue = max;
        this.colorScale = scale;
        this.label = label;
        this.scaleType = scaleType;
        repaint();
    }

    public void clear() {
        this.colorScale = null;
        repaint();
    }

    private void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        gc.setFill(Color.rgb(42, 42, 42));
        gc.fillRect(0, 0, w, h);

        if (colorScale == null) return;

        double barX = 5;
        double barW = 12;
        double barTop = 20;
        double barBottom = h - 20;
        double barH = barBottom - barTop;
        if (barH <= 0) return;

        // Draw gradient bar (top = max, bottom = min)
        for (int y = 0; y <= (int) barH; y++) {
            double t = 1.0 - (double) y / barH; // top=1, bottom=0
            gc.setFill(mapColor(t));
            gc.fillRect(barX, barTop + y, barW, 1);
        }

        // Border
        gc.setStroke(Color.gray(0.4));
        gc.setLineWidth(0.5);
        gc.strokeRect(barX, barTop, barW, barH);

        // Marker name above gradient
        if (label != null) {
            gc.setFont(Font.font(7));
            gc.setFill(Color.gray(0.6));
            gc.fillText(label, barX, barTop - 4);
        }

        // Value labels
        gc.setFill(Color.gray(0.7));
        gc.setFont(Font.font(8));
        gc.fillText(String.format(Locale.US, "%.1f", maxValue), barX + barW + 2, barTop + 8);
        gc.fillText(String.format(Locale.US, "%.1f", minValue), barX + barW + 2, barBottom);

        // Scale type label (below gradient)
        if (scaleType != null) {
            gc.setFont(Font.font(7));
            gc.setFill(Color.gray(0.5));
            gc.fillText(scaleType, barX, barBottom + 12);
        }
    }

    private Color mapColor(double t) {
        return ColorScaleMapper.map(t, colorScale.mapperScale, 1.0);
    }
}
