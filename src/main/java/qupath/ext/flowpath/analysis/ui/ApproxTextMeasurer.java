package qupath.ext.flowpath.analysis.ui;

/**
 * A toolkit-free stand-in for real text metrics: {@code length * fontSize * (bold ? 0.60 :
 * 0.55)}, the same per-character fraction {@code PlotCanvas.drawCategoryLabels} and {@code
 * drawAxes} already eyeballed for their own centring math. It exists because {@link
 * SvgSurface} must be constructible with no {@code Stage} and no {@code FxTestSupport} — the
 * JavaFX text layout {@link FxTextMeasurer} uses is unavailable there, and a headless CI run
 * cannot be made to depend on it. Wherever a live canvas is available,
 * {@link FxTextMeasurer} gives the exact figure this only approximates.
 */
public final class ApproxTextMeasurer implements TextMeasurer {

    @Override
    public double width(String text, double fontSize, boolean bold) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() * fontSize * (bold ? 0.60 : 0.55);
    }
}
