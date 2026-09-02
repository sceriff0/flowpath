package qupath.ext.flowpath.analysis.ui;

import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.function.Supplier;

/**
 * Exact text metrics from JavaFX's own layout, via one reused off-scene {@link Text} node —
 * the figure {@link CanvasSurface} hands to a live plot, and the figure the SVG export path
 * hands to {@link SvgSurface} so an exported figure lays out identically to what was on
 * screen, rather than to {@link ApproxTextMeasurer}'s per-character guess.
 * <p>
 * <b>Must be constructed on the JavaFX application thread.</b> {@link Text}, like every {@code
 * javafx.scene.*} node, asserts this internally and throws off it. If that construction throws
 * — most commonly because no toolkit has been started at all, which is the ordinary case for a
 * headless test or CI run — this measurer does not propagate the failure. It falls back to
 * delegating every {@link #width} call to an {@link ApproxTextMeasurer} instead, so a class
 * that merely constructs an {@code FxTextMeasurer} defensively (rather than one that actually
 * needs exact metrics off-thread) cannot bring a headless run down. The catch below is
 * deliberately {@link Throwable}, not {@link Exception}: a toolkit failure can surface as an
 * {@link Error} subtype rather than a checked or runtime exception — {@code PlotTheme}'s
 * {@code detect} route hit exactly this shape, {@code ExceptionInInitializerError} from a
 * QuPath static initialiser reached outside a running QuPath instance — and narrowing this
 * catch would let that class of failure escape the fallback it exists to trigger.
 */
public final class FxTextMeasurer implements TextMeasurer {

    private final Text text;
    private final TextMeasurer fallback;

    public FxTextMeasurer() {
        this(Text::new);
    }

    /**
     * Test seam: lets {@code FxTextMeasurerTest} supply a {@link Text} factory that throws
     * (a {@link RuntimeException} or an {@link Error}) without needing a real off-toolkit
     * environment to provoke {@link Text}'s own constructor into failing, which this repo's
     * test JVM does not reliably do — its graphics toolkit initialises lazily even with no
     * {@code Stage} started. Package-private because no caller outside this package should
     * ever need to substitute the factory; production code always goes through the public
     * no-arg constructor, which supplies {@code Text::new}.
     */
    FxTextMeasurer(Supplier<Text> textFactory) {
        Text t;
        try {
            t = textFactory.get();
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
