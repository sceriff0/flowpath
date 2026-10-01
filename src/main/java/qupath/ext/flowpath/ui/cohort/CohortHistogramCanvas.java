package qupath.ext.flowpath.ui.cohort;

import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import qupath.ext.flowpath.model.cohort.LogScale;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Cohort window's detail histogram (spec U2): the reference's and this slide's distributions
 * of the selected gate's axis-0 column on the alignment's shared log grid, their L1 landmarks
 * (dashed), any picked peak (◆), and the reference threshold with an arrow to the threshold this
 * slide applies. The x-axis is labelled in raw units ({@code scale.fromLog(u)}).
 *
 * <p>Peak picking: {@link #arm} a target, and the next primary click reports
 * {@code (target, log value under the cursor)} and disarms; Esc disarms. The canvas decides
 * nothing else — what a pick means is the host's.
 *
 * <p>Colours are fixed swatches on a fixed dark plot background, like the gate editor's
 * canvases, so they read the same under either QuPath theme (CLAUDE.md "Styling follows
 * QuPath's theme": canvases are a deliberate exception).
 */
public final class CohortHistogramCanvas extends Canvas {

    public enum PickTarget { NONE, SLIDE, REFERENCE }

    static final double WIDTH = 440, HEIGHT = 160;
    private static final double PAD_LEFT = 12, PAD_RIGHT = 12, PAD_TOP = 18, PAD_BOTTOM = 24;
    private static final int TICKS = 5;

    // Fixed swatches on the fixed dark plot background (see the class javadoc).
    private static final Color BACKGROUND = Color.rgb(30, 30, 30);
    private static final Color AXIS = Color.gray(0.55);
    private static final Color TEXT = Color.gray(0.85);
    private static final Color REFERENCE = Color.gray(0.70);
    private static final Color SLIDE = Color.rgb(90, 160, 255);
    private static final Color APPLIED = Color.rgb(255, 165, 40);
    private static final Color GUIDE = Color.rgb(255, 255, 255, 0.6);

    private CohortGridModel.HistogramView view;
    private boolean region;
    private PickTarget armed = PickTarget.NONE;
    private double hoverX = Double.NaN;
    private BiConsumer<PickTarget, Double> onPicked = (t, u) -> {};
    private Consumer<PickTarget> onArmedChanged = t -> {};

    public CohortHistogramCanvas() {
        super(WIDTH, HEIGHT);
        setFocusTraversable(true);
        setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || armed == PickTarget.NONE || view == null) return;
            PickTarget target = armed;
            double u = logAtCanvasX(e.getX());
            arm(PickTarget.NONE);
            onPicked.accept(target, u);
            e.consume();
        });
        setOnMouseMoved(e -> {
            if (armed == PickTarget.NONE) return;
            hoverX = e.getX();
            draw();
        });
        setOnMouseExited(e -> {
            hoverX = Double.NaN;
            draw();
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && armed != PickTarget.NONE) {
                arm(PickTarget.NONE);
                e.consume();
            }
        });
        draw();
    }

    /** As {@link #show(CohortGridModel.HistogramView, boolean)} for a threshold gate. */
    public void show(CohortGridModel.HistogramView v) {
        show(v, false);
    }

    /**
     * @param region a 2D region gate: it has no cut to draw, so absent thresholds are not drawn
     *               as off-scale either
     */
    public void show(CohortGridModel.HistogramView v, boolean region) {
        this.view = v;
        this.region = region;
        if (v == null) arm(PickTarget.NONE);
        draw();
    }

    /** {@link PickTarget#NONE} disarms. */
    public void arm(PickTarget t) {
        PickTarget next = t == null ? PickTarget.NONE : t;
        if (next != PickTarget.NONE && view == null) next = PickTarget.NONE;
        boolean changed = next != armed;
        armed = next;
        setCursor(armed == PickTarget.NONE ? Cursor.DEFAULT : Cursor.CROSSHAIR);
        if (armed == PickTarget.NONE) hoverX = Double.NaN;
        else requestFocus();
        draw();
        if (changed) onArmedChanged.accept(armed);
    }

    public PickTarget armed() { return armed; }

    /** Called with the target and the LOG value under the click. */
    public void setOnPicked(BiConsumer<PickTarget, Double> c) { onPicked = Objects.requireNonNull(c); }

    /** Called whenever the armed target changes, by {@link #arm}, a click or Esc. */
    void setOnArmedChanged(Consumer<PickTarget> c) { onArmedChanged = Objects.requireNonNull(c); }

    /** The log value at plot x {@code x} in {@code [0, width]}: {@code gridMin} at 0, {@code gridMax} at {@code width}. */
    public static double logAt(double x, double width, double gridMin, double gridMax) {
        if (!(width > 0) || !(gridMax > gridMin)) return gridMin;
        return gridMin + x / width * (gridMax - gridMin);
    }

    /** The inverse of {@link #logAt}. */
    public static double xOf(double u, double width, double gridMin, double gridMax) {
        if (!(width > 0) || !(gridMax > gridMin)) return 0;
        return (u - gridMin) / (gridMax - gridMin) * width;
    }

    /** The log value under canvas x {@code x}, clamped to the grid: what a click there reports. */
    double logAtCanvasX(double x) {
        if (view == null) return Double.NaN;
        double w = plotWidth();
        return logAt(Math.max(0, Math.min(w, x - PAD_LEFT)), w, view.gridMin(), view.gridMax());
    }

    private double plotWidth() { return getWidth() - PAD_LEFT - PAD_RIGHT; }

    private double canvasX(double u) {
        return PAD_LEFT + xOf(u, plotWidth(), view.gridMin(), view.gridMax());
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();
        g.setLineDashes();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);
        g.setFont(Font.font(10));
        if (view == null) return;
        double top = PAD_TOP, bottom = h - PAD_BOTTOM;

        // The x-axis in raw units: ticks evenly spaced on the log grid, labelled scale.fromLog(u).
        g.setStroke(AXIS);
        g.setLineWidth(1);
        g.strokeLine(PAD_LEFT, bottom, w - PAD_RIGHT, bottom);
        g.setFill(TEXT);
        g.setTextAlign(TextAlignment.CENTER);
        LogScale scale = view.scale();
        for (int i = 0; i <= TICKS; i++) {
            double u = view.gridMin() + i * (view.gridMax() - view.gridMin()) / TICKS;
            double x = canvasX(u);
            g.strokeLine(x, bottom, x, bottom + 3);
            g.setTextAlign(i == 0 ? TextAlignment.LEFT : i == TICKS ? TextAlignment.RIGHT : TextAlignment.CENTER);
            g.fillText(CohortGridModel.number(scale.fromLog(u)), x, bottom + 14);
        }

        stepCurve(g, view.reference(), REFERENCE, top, bottom);
        stepCurve(g, view.slide(), SLIDE, top, bottom);

        g.setLineDashes(4, 3);
        landmark(g, view.referenceL1(), REFERENCE, top, bottom);
        landmark(g, view.slideL1(), SLIDE, top, bottom);
        g.setLineDashes();
        diamond(g, view.pickedReferencePeak(), REFERENCE, top);
        diamond(g, view.pickedSlidePeak(), SLIDE, top);

        if (!region) {
            double ref = threshold(g, view.referenceThreshold(), REFERENCE, top, bottom);
            double app = threshold(g, view.appliedThreshold(), APPLIED, top, bottom);
            if (Double.isFinite(ref) && Double.isFinite(app) && Math.abs(app - ref) > 6) arrow(g, ref, app, top + 10);
        }

        if (armed == PickTarget.NONE) {
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(SLIDE);
            g.fillText("this slide", w - PAD_RIGHT, 12);
            g.setFill(REFERENCE);
            g.fillText("reference  ", w - PAD_RIGHT - 46, 12);
        } else {
            g.setFill(TEXT);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(armed == PickTarget.SLIDE ? "Click this slide's negative peak (Esc cancels)"
                    : "Click the reference's negative peak (Esc cancels)", PAD_LEFT, 12);
            if (Double.isFinite(hoverX)) {
                double x = PAD_LEFT + Math.max(0, Math.min(plotWidth(), hoverX - PAD_LEFT));
                g.setStroke(GUIDE);
                g.strokeLine(x, top, x, bottom);
                g.setTextAlign(TextAlignment.RIGHT);
                g.fillText(CohortGridModel.number(scale.fromLog(logAtCanvasX(hoverX))), w - PAD_RIGHT, 12);
            }
        }
    }

    /** One histogram as a step curve, normalised to its own maximum; nothing for a missing or empty one. */
    private void stepCurve(GraphicsContext g, long[] counts, Color color, double top, double bottom) {
        if (counts == null || counts.length == 0) return;
        long max = 0;
        for (long c : counts) max = Math.max(max, c);
        if (max == 0) return;
        int n = counts.length;
        double step = (view.gridMax() - view.gridMin()) / n;
        g.setStroke(color);
        g.setLineWidth(1.5);
        g.beginPath();
        g.moveTo(canvasX(view.gridMin()), bottom);
        for (int i = 0; i < n; i++) {
            double y = bottom - (bottom - top) * counts[i] / (double) max;
            g.lineTo(canvasX(view.gridMin() + i * step), y);
            g.lineTo(canvasX(view.gridMin() + (i + 1) * step), y);
        }
        g.lineTo(canvasX(view.gridMax()), bottom);
        g.stroke();
        g.setLineWidth(1);
    }

    private void landmark(GraphicsContext g, double u, Color color, double top, double bottom) {
        if (!Double.isFinite(u) || u < view.gridMin() || u > view.gridMax()) return;
        double x = canvasX(u);
        g.setStroke(color);
        g.strokeLine(x, top, x, bottom);
    }

    private void diamond(GraphicsContext g, double u, Color color, double top) {
        if (!Double.isFinite(u) || u < view.gridMin() || u > view.gridMax()) return;
        double x = canvasX(u), y = top - 2, r = 5;
        g.setFill(color);
        g.fillPolygon(new double[]{x, x + r, x, x - r}, new double[]{y - r, y, y + r, y}, 4);
    }

    /**
     * A threshold as a solid line, its canvas x returned. One outside the grid — or NaN, which on
     * the ln scale is a raw cut below 1 (outside the scale's domain) — is drawn as an off-scale
     * marker at the nearest edge with its label, never skipped silently; NaN is returned for it.
     */
    private double threshold(GraphicsContext g, double u, Color color, double top, double bottom) {
        boolean below = !Double.isFinite(u) || u < view.gridMin();
        if (below || u > view.gridMax()) {
            String label = !Double.isFinite(u) ? (view.scale() == LogScale.LN1P ? "< 0" : "< 1")
                    : CohortGridModel.number(view.scale().fromLog(u));
            double x = below ? PAD_LEFT : getWidth() - PAD_RIGHT;
            double y = (top + bottom) / 2 + (color == APPLIED ? 14 : -14);
            double d = below ? -1 : 1;
            g.setFill(color);
            g.fillPolygon(new double[]{x + d * 6, x, x}, new double[]{y, y - 5, y + 5}, 3);
            g.setTextAlign(below ? TextAlignment.LEFT : TextAlignment.RIGHT);
            g.fillText(label, x - d * 3, y - 7);
            return Double.NaN;
        }
        double x = canvasX(u);
        g.setStroke(color);
        g.setLineWidth(2);
        g.strokeLine(x, top, x, bottom);
        g.setLineWidth(1);
        return x;
    }

    /** From the reference threshold to the applied one, at height {@code y}. */
    private void arrow(GraphicsContext g, double from, double to, double y) {
        double d = Math.signum(to - from);
        g.setStroke(APPLIED);
        g.strokeLine(from, y, to - d * 5, y);
        g.setFill(APPLIED);
        g.fillPolygon(new double[]{to, to - d * 6, to - d * 6}, new double[]{y, y - 4, y + 4}, 3);
    }
}
