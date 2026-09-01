package qupath.ext.flowpath.analysis.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * The on-screen {@link PlotSurface} backend: a thin adapter over the {@link GraphicsContext}
 * of a live {@code Canvas}, translating each call one-for-one into the {@code gc} calls {@code
 * PlotCanvas} used to make directly. It carries no drawing logic of its own — the whole point
 * of the {@link PlotSurface} split is that logic lives once, above this class, and this class
 * only forwards.
 */
public final class CanvasSurface implements PlotSurface {

    private final GraphicsContext gc;
    private final double width;
    private final double height;
    private final TextMeasurer measurer;

    // Tracked independently of gc's own font so textWidth() and fontSize() have something to
    // report before the first setFont() call — but that means they can answer for a font the
    // GraphicsContext is not actually drawing in until setFont() is called at least once. A
    // caller must call setFont() before fillText/fillTextRotated/textWidth for the two to
    // agree; PlotCanvas's own draw methods already do this today via gc.setFont(...) before
    // every fillText, so this is a caveat for whatever new caller wires this up next (Task 3),
    // not a bug reachable through the plots that exist now.
    private double fontSize = 10;
    private boolean bold = false;

    public CanvasSurface(GraphicsContext gc, double width, double height, TextMeasurer measurer) {
        this.gc = gc;
        this.width = width;
        this.height = height;
        this.measurer = measurer;
    }

    @Override public double width() { return width; }

    @Override public double height() { return height; }

    @Override public void setFill(Color color) { gc.setFill(color); }

    @Override public void setStroke(Color color) { gc.setStroke(color); }

    @Override public void setLineWidth(double lineWidth) { gc.setLineWidth(lineWidth); }

    @Override
    public void setFont(double sizePx, boolean isBold) {
        this.fontSize = sizePx;
        this.bold = isBold;
        gc.setFont(isBold
                ? Font.font(null, FontWeight.BOLD, sizePx)
                : Font.font(sizePx));
    }

    @Override public void fillRect(double x, double y, double w, double h) { gc.fillRect(x, y, w, h); }

    @Override public void strokeRect(double x, double y, double w, double h) { gc.strokeRect(x, y, w, h); }

    @Override public void strokeLine(double x1, double y1, double x2, double y2) { gc.strokeLine(x1, y1, x2, y2); }

    @Override public void fillText(String text, double x, double y) { gc.fillText(text, x, y); }

    /** The save/translate/rotate/restore sequence {@code PlotCanvas.drawAxes} already used. */
    @Override
    public void fillTextRotated(String text, double x, double y, double degrees) {
        gc.save();
        gc.translate(x, y);
        gc.rotate(degrees);
        gc.fillText(text, 0, 0);
        gc.restore();
    }

    @Override public double textWidth(String text) { return measurer.width(text, fontSize, bold); }

    @Override public double fontSize() { return fontSize; }
}
