package qupath.ext.flowpath.analysis.ui;

import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Exact text metrics from JavaFX's own layout, via one reused off-scene {@link Text} node —
 * the figure {@link CanvasSurface} hands to a live plot, and the figure Task 12's export hands
 * to {@link SvgSurface} so an exported figure lays out identically to what was on screen,
 * rather than to {@link ApproxTextMeasurer}'s per-character guess.
 * <p>
 * <b>Must be constructed on the JavaFX application thread.</b> {@link Text}, like every {@code
 * javafx.scene.*} node, asserts this internally and throws off it. If that construction throws
 * — most commonly because no toolkit has been started at all, which is the ordinary case for a
 * headless test or CI run — this measurer does not propagate the failure. It falls back to
 * delegating every {@link #width} call to an {@link ApproxTextMeasurer} instead, so a class
 * that merely constructs an {@code FxTextMeasurer} defensively (rather than one that actually
 * needs exact metrics off-thread) cannot bring a headless run down.
 */
public final class FxTextMeasurer implements TextMeasurer {

    private final Text text;
    private final TextMeasurer fallback;

    public FxTextMeasurer() {
        Text t;
        try {
            t = new Text();
        } catch (Throwable failed) {
            t = null;
        }
        this.text = t;
        this.fallback = t == null ? new ApproxTextMeasurer() : null;
    }

    @Override
    public double width(String value, double fontSize, boolean bold) {
        if (fallback != null) {
            return fallback.width(value, fontSize, bold);
        }
        text.setText(value == null ? "" : value);
        text.setFont(Font.font(null, bold ? FontWeight.BOLD : FontWeight.NORMAL, fontSize));
        return text.getLayoutBounds().getWidth();
    }
}
