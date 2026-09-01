package qupath.ext.flowpath.analysis.ui;

/**
 * How wide a run of text is at a given font size and weight — the one fact a {@link
 * PlotSurface} needs from outside itself to centre a label or size a legend swatch, and the
 * one fact that differs between the two backends' worlds. {@link CanvasSurface} draws on a
 * live JavaFX {@code Canvas}, so it can ask the toolkit for an exact answer ({@link
 * FxTextMeasurer}); {@link SvgSurface} is built and tested with no toolkit at all, so it is
 * handed a {@link TextMeasurer} rather than reaching for one — {@link ApproxTextMeasurer} in
 * the common case, or the same {@link FxTextMeasurer} an on-screen canvas used, when Task 12's
 * export wants the SVG to lay out exactly as what was on screen.
 */
public interface TextMeasurer {

    /** The width, in pixels, of {@code text} rendered at {@code fontSize} and this weight. */
    double width(String text, double fontSize, boolean bold);
}
