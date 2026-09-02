package qupath.ext.flowpath.analysis.ui;

import javafx.scene.paint.Color;

/**
 * Every drawing primitive the Analysis window's four plots use, factored out of {@code
 * GraphicsContext} so a plot can be drawn exactly once and rendered onto either a live {@link
 * CanvasSurface} or an offscreen {@link SvgSurface} for export.
 * <p>
 * <b>This is the load-bearing decision of the vector-export work.</b> The alternative — a
 * separate SVG writer that walks the same population data a second time — would be a second
 * implementation of every plot's rendering, exactly the failure this codebase has already paid
 * for five times over with gate predicates kept in sync by comments (see {@code CLAUDE.md}).
 * One drawing routine against this interface, two backends underneath it, is the only version
 * of "the SVG matches what I saw on screen" that cannot silently drift: a bar drawn in the
 * wrong place draws wrong on both backends at once, and there is no second place a fix could
 * apply to only one of them.
 * <p>
 * The surface is deliberately narrow — the handful of calls {@code PlotCanvas} and its four
 * subclasses actually make against {@code GraphicsContext} (rects, lines, plain and rotated
 * text), not a general vector-graphics API. Anything richer (paths, gradients, clipping) has no
 * caller yet and would just be surface area {@link SvgSurface} has to keep faithful to {@link
 * CanvasSurface} for no reason.
 */
public interface PlotSurface {

    /** The surface's fixed drawing width, in pixels. */
    double width();

    /** The surface's fixed drawing height, in pixels. */
    double height();

    /** The colour every subsequent {@link #fillRect} and {@link #fillText} call uses. */
    void setFill(Color color);

    /** The colour every subsequent {@link #strokeRect} and {@link #strokeLine} call uses. */
    void setStroke(Color color);

    /** The line width every subsequent {@link #strokeRect} and {@link #strokeLine} call uses. */
    void setLineWidth(double width);

    /**
     * The font every subsequent {@link #fillText}, {@link #fillTextRotated} and {@link
     * #textWidth} call measures and draws against. Sans-serif is the only family either
     * backend supports — matching the family {@code PlotCanvas} already hardcodes via {@code
     * Font.font(...)} with no family argument.
     */
    void setFont(double sizePx, boolean bold);

    void fillRect(double x, double y, double w, double h);

    void strokeRect(double x, double y, double w, double h);

    void strokeLine(double x1, double y1, double x2, double y2);

    /** Draw {@code text} with its baseline at {@code (x, y)}, in the current fill and font. */
    void fillText(String text, double x, double y);

    /**
     * Draw {@code text} rotated {@code degrees} clockwise about {@code (x, y)} — the mapping
     * {@code PlotCanvas.drawAxes} already uses for its Y-axis label via {@code gc.translate} +
     * {@code gc.rotate}, folded into one call so an SVG backend can express it as a single
     * {@code transform} attribute instead of replaying a save/translate/rotate/restore sequence
     * that means nothing outside a retained graphics context.
     */
    void fillTextRotated(String text, double x, double y, double degrees);

    /** The width {@code text} would draw at, at the current font — for centring a label. */
    double textWidth(String text);

    /** The current font size set by the most recent {@link #setFont}. */
    double fontSize();
}
