package qupath.ext.flowpath.analysis.ui;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared drawing base for the Analysis window's four plots: axes, tick labels, category
 * labels, a legend, and the two coordinate mappings every one of them needs — a continuous
 * value onto the plot's Y axis, and a category index onto its X axis.
 * <p>
 * <b>No plot-specific logic lives here.</b> What a bar or a segment <em>means</em> — which
 * rows count, how they are grouped, what "positive" or "leaf" means for this plot — is
 * exactly the reduction each subclass owns in its own {@code setRows(...)}. This class only
 * knows how to put a number on the canvas once that decision has already been made, the same
 * separation {@code ScatterPlotCanvas.branchAt} draws between "which branch" (the model's
 * job) and "where on screen" (the canvas's job).
 * <p>
 * <b>One drawing routine, two backends — and no way to write a second one.</b> A subclass
 * implements {@link #draw(PlotSurface, PlotTheme)} and nothing else: {@link #repaint()} and
 * {@link #toSvg()} are both {@code final}, and both funnel through the same private {@code
 * render} that calls {@link #drawBackground} and then {@code draw}. Neither entry point can
 * paint anything the other does not, because neither one paints at all — they only choose
 * which {@link PlotSurface} the one routine writes to. This matters more than it looks: "the
 * exported SVG does not match what I saw" is the failure mode the {@link PlotSurface} split
 * exists to prevent, and this codebase has already paid for that class of bug five times over
 * in the gating path, where a display implementation and a classification implementation of
 * the same rule were kept in step by comments and silently diverged (see {@code CLAUDE.md} →
 * "One gate predicate"). Making the divergence unwritable is cheaper than testing for it.
 * <p>
 * <b>Text is measured, never guessed.</b> Every centring and truncation decision below goes
 * through {@link PlotSurface#textWidth}, which resolves to real JavaFX metrics on a live
 * canvas ({@link FxTextMeasurer}). The previous version multiplied {@code label.length()} by
 * {@code 4.2} and drew whatever came out, so {@code CD45+/CD3+/CD8+} labels overlapped their
 * neighbours as soon as a tree had more than a handful of leaves, and a legend pinned at
 * {@code width - PADDING_RIGHT - 90} landed on top of the bars. Both are now structural
 * rather than cosmetic fixes: see {@link #layoutLabels} and {@link #legendHeight}.
 * <p>
 * <b>The same measurer serves both backends.</b> {@link #toSvg()} hands {@link SvgSurface}
 * this canvas's own {@link TextMeasurer} rather than a fresh {@link ApproxTextMeasurer}, so an
 * exported figure lays its text out exactly as the screen did rather than approximately. A
 * plot whose SVG truncated its labels at different places than the canvas would be a visible
 * instance of the divergence the paragraph above exists to rule out.
 * <p>
 * Hand-drawn on {@link Canvas}, matching {@code ui.HistogramCanvas}, {@code
 * ui.ScatterPlotCanvas} and {@code umap.ui.UmapCanvas} — <b>not</b> JavaFX's {@code
 * BarChart}/{@code PieChart}, which are light-themed by default and styled through CSS, so
 * they would not match the canvases beside them.
 */
public abstract class PlotCanvas extends Canvas {

    protected static final double PADDING_LEFT = 52;
    protected static final double PADDING_RIGHT = 12;
    protected static final double PADDING_TOP = 10;

    /**
     * The bottom margin a plot whose category labels sit horizontally reserves. Rotated
     * labels need more and say so through {@link LabelLayout#bottomPadding()}; nothing reads
     * this constant as "the" bottom padding any more, precisely so the two cases cannot be
     * confused.
     */
    protected static final double PADDING_BOTTOM = 30;

    /** The bottom margin rotated labels need — see {@link #drawCategoryLabels} for the geometry. */
    protected static final double PADDING_BOTTOM_ROTATED = 64;

    protected static final double LEGEND_ROW_HEIGHT = 14;

    /** Breathing room between the last legend row and the top of the plot frame. */
    private static final double LEGEND_STRIP_GAP = 6;

    private static final double LABEL_FONT_SIZE = 8;
    private static final double AXIS_TITLE_FONT_SIZE = 9;
    private static final double LEGEND_FONT_SIZE = 9;
    private static final double EMPTY_STATE_FONT_SIZE = 11;

    /** Gap left between a category label and the edge of its slot before rotating instead. */
    private static final double LABEL_SLOT_MARGIN = 4;

    /**
     * How wide a rotated label may be. Its far end sits {@link #ROTATED_LABEL_ANCHOR_GAP}
     * below the axis and it descends left at −45°, so a run of text this long reaches
     * {@code 4 + 84 × sin45° = 63.4px} below the axis, inside the {@link
     * #PADDING_BOTTOM_ROTATED} band of 64 with 0.6px to spare.
     * <p>
     * The three numbers are one decision and the fit is exact by arithmetic, not by luck:
     * {@code rotatedLabelsAreDrawnEntirelyInsideTheCanvas} asserts the reach against the band
     * with <b>zero</b> tolerance, so raising this cap, widening the gap or narrowing the band
     * fails a test rather than producing a label clipped against the canvas edge on somebody's
     * panel with long marker names.
     */
    static final double ROTATED_LABEL_MAX_WIDTH = 84;

    /**
     * How far below the axis a rotated label's <em>end</em> — the last character, the one
     * nearest its own bar — is anchored. Small on purpose, and twice over: the label has to
     * read as belonging to the tick above it rather than floating between two of them, and
     * every pixel here is a pixel the longest label cannot use inside {@link
     * #PADDING_BOTTOM_ROTATED}. At 4 the two just fit; at 6 a label elided to the full cap
     * reached 1.4px past the canvas edge.
     */
    static final double ROTATED_LABEL_ANCHOR_GAP = 4;

    /**
     * The angle rotated category labels are drawn at, and its cosine (= its sine). The three
     * band constants are package-private rather than private so {@code RotatedLabelRenderTest}
     * can assert the band arithmetic — {@code gap + cap × sin45 <= PADDING_BOTTOM_ROTATED} — on
     * the constants themselves. Asserting it only through a rendered fixture makes the check
     * depend on a label happening to measure close to the cap, which is not something a test
     * should be left to luck about.
     */
    private static final double ROTATED_LABEL_DEGREES = -45;
    static final double ROTATED_LABEL_DIAGONAL = Math.cos(Math.toRadians(ROTATED_LABEL_DEGREES));

    private PlotTheme theme = PlotTheme.LIGHT;

    private PaintedLayout painted;

    /**
     * How this canvas's Y axis reads — log or linear, clipped or not. Owned here and nowhere
     * else: {@link PlotControls} writes a new value through {@link #setScaleOptions} on every
     * toggle and never keeps a copy of its own, which is what makes the setting per-plot —
     * changing one canvas's scale cannot reach across to another the way a shared field or a
     * static default would.
     */
    private ScaleOptions scaleOptions = ScaleOptions.LINEAR;

    /**
     * Whether the axis {@link #scaleFor} most recently resolved actually clipped a value —
     * what {@link PlotControls} binds its "clipped" label's visibility to, so the label
     * reflects what the last paint actually drew rather than merely whether the clip toggle is
     * on. A canvas whose clip candidate lands on the data's own maximum (see {@link
     * AxisScale#of}) has the toggle on and nothing clipped, and the label must say so.
     * <p>
     * Reset to {@code false} at the top of every {@link #render} pass, the same guard {@link
     * #painted} uses and for the same reason: a pass that takes the empty-state branch, or one
     * whose subclass never calls {@link #scaleFor} at all, must not go on reporting whatever
     * the previous pass found.
     */
    private final SimpleBooleanProperty anyClipped =
            new SimpleBooleanProperty(this, "anyClipped", false);

    /**
     * Real metrics where a toolkit exists, {@link ApproxTextMeasurer} where it does not —
     * {@link FxTextMeasurer} degrades on its own, so a headless test that merely constructs a
     * canvas does not have to care which it got.
     */
    private final TextMeasurer measurer = new FxTextMeasurer();

    protected PlotCanvas(double width, double height) {
        super(width, height);
        widthProperty().addListener((obs, o, n) -> repaint());
        heightProperty().addListener((obs, o, n) -> repaint());
        // A canvas is constructed before it is added to anything, so at construction time
        // there is no window whose theme it could read. Adopt it the moment there is one.
        // setTheme() below is a no-op when the theme is unchanged, which is what stops this
        // listener from turning every re-parent into a repaint.
        sceneProperty().addListener((obs, o, scene) -> {
            if (scene != null) {
                setTheme(PlotTheme.detect(this));
            }
        });
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

    /** The palette this canvas is currently drawing in. */
    public PlotTheme theme() {
        return theme;
    }

    /**
     * Switch palettes and redraw. Unchanged means untouched: the scene listener in the
     * constructor fires on every re-parent, and without this guard each one would queue a
     * full repaint of a plot whose appearance cannot have changed.
     */
    public void setTheme(PlotTheme theme) {
        if (Objects.equals(this.theme, theme)) {
            return;
        }
        this.theme = theme;
        repaint();
    }

    /** How this canvas currently reads its Y axis. */
    public ScaleOptions scaleOptions() {
        return scaleOptions;
    }

    /**
     * Switch this canvas's axis to {@code options} and redraw. The one write path for {@link
     * #scaleOptions} — {@link PlotControls} calls this on every toggle and stores nothing of
     * its own, so there is exactly one place a plot's scale lives.
     */
    public void setScaleOptions(ScaleOptions options) {
        this.scaleOptions = Objects.requireNonNull(options, "options");
        repaint();
    }

    /**
     * Resolve {@code values} — the bar values a subclass is about to draw — into an axis under
     * this canvas's current {@link #scaleOptions}. The single place any subclass turns data
     * into an axis; see the class javadoc's "one drawing routine" paragraph for why a second
     * value→axis computation anywhere else would reintroduce exactly the divergence this
     * codebase keeps a list of.
     * <p>
     * Also records whether the resolved axis clipped anything, via {@link #anyClippedProperty},
     * so {@link PlotControls} can show its "clipped" label without a subclass having to publish
     * the same fact through a second channel.
     */
    protected AxisScale scaleFor(double[] values) {
        AxisScale scale = AxisScale.of(values, scaleOptions);
        anyClipped.set(scale.anyClipped());
        return scale;
    }

    /**
     * Whether the most recent paint's axis clipped at least one value. Package-private: only
     * {@link PlotControls}, in this same package, needs to bind a label to it.
     */
    ObservableBooleanValue anyClippedProperty() {
        return anyClipped;
    }

    /**
     * Redraw onto the live canvas. {@code final}, and so is {@link #toSvg()}: both delegate to
     * {@link #draw}, which is the only place a subclass can put a drawing instruction. See the
     * class javadoc for why that is worth enforcing in the type system rather than by
     * convention.
     */
    protected final void repaint() {
        render(new CanvasSurface(getGraphicsContext2D(), getWidth(), getHeight(), measurer));
    }

    /**
     * This plot as a standalone SVG document, at the canvas's current size and in its current
     * theme — the same drawing calls {@link #repaint()} sends to the screen, recorded instead
     * of painted. The measurer is this canvas's own, so text lays out identically to the
     * screen rather than to a per-character approximation.
     */
    public final String toSvg() {
        SvgSurface svg = new SvgSurface(getWidth(), getHeight(), measurer);
        render(svg);
        return svg.toSvg();
    }

    /**
     * The single rendering path. Both public entry points differ only in what they pass here.
     * <p>
     * The remembered geometry is cleared first, so a pass that drew an empty state — no axes,
     * no plot rectangle — leaves {@link #paintedLayout()} {@code null} rather than the stale
     * rectangle of whatever was on screen before it.
     * <p>
     * <b>Invariant: a surface handed to {@link #draw} is always the same size as the canvas.</b>
     * Every layout method here — {@link #plotWidth}, {@link #plotHeight}, {@link #categoryToX},
     * {@link #drawEmptyState}, {@link #drawBackground} — measures the <em>canvas</em>, never
     * {@code s.width()}/{@code s.height()}, and both callers construct their surface at exactly
     * {@code getWidth()} × {@code getHeight()}. That is what makes publishing {@code painted}
     * from an export pass harmless: the geometry an export lays out is identical to the
     * geometry on screen, so a hit-test cannot be handed a rectangle from a differently-sized
     * pass. Task 12 gets a higher-resolution PNG by scaling the rendered <em>snapshot</em>,
     * never by laying out at another size; a future caller who genuinely wants a
     * differently-sized export must first make the layout read the surface, not quietly pass a
     * surface whose dimensions disagree with the canvas.
     */
    private void render(PlotSurface surface) {
        painted = null;
        anyClipped.set(false);
        drawBackground(surface, theme);
        draw(surface, theme);
    }

    /**
     * The geometry one paint actually used: the {@link LabelLayout} it laid its category
     * labels out with, and the number of legend rows it reserved a strip for. Together they
     * are the only two arguments {@link #plotTop}, {@link #plotHeight} and {@link #fractionToY}
     * take, so holding this pair is holding the whole plot rectangle.
     *
     * @param labels     the layout that pass drew with
     * @param legendRows the legend row count that pass reserved a strip for
     */
    public record PaintedLayout(LabelLayout labels, int legendRows) {}

    /**
     * The geometry the last paint actually used, or {@code null} when the last paint drew no
     * plot at all — before the first render, and after any pass that drew an empty state.
     * <b>A caller must handle {@code null}</b>: a click can arrive before the first paint, and
     * a hit-test that assumed otherwise would throw on it rather than report no hit.
     * <p>
     * Read this rather than recomputing. {@link #layoutLabels} needs a {@link PlotSurface} to
     * measure text with, which a mouse handler does not have; building a throwaway one would
     * both measure on every pointer move and be free to disagree with what is actually on
     * screen if anything changed in between. Reading back what was drawn cannot disagree — the
     * same reason {@link #draw} is the single routine behind both {@link #repaint()} and
     * {@link #toSvg()}. Hit-testing is simply a third reader of one layout.
     * <p>
     * <b>Hit-testing is slot-based by design, so no bar extent is published here.</b> A pointer
     * resolves to a whole category slot via {@link #categoryToX} / {@link #categoryWidth}, not
     * to the drawn bar rectangle: the {@code 0.7} and {@code 0.6} bar-width factors the four
     * canvases apply are cosmetic, and a click just beside a thin bar should still select it.
     * Do not go looking for a bar extent in this record — its absence is the decision, not an
     * omission.
     */
    protected PaintedLayout paintedLayout() {
        return painted;
    }

    /**
     * Draw this plot's own content — everything except the background, which {@link #render}
     * has already laid down so that neither backend can end up with a transparent page.
     * Implementations must go through {@code s} exclusively: reaching for {@code
     * getGraphicsContext2D()} here would draw on the screen and vanish from the export, which
     * is precisely the divergence this class is shaped to prevent.
     */
    protected abstract void draw(PlotSurface s, PlotTheme theme);

    /**
     * How a plot's category labels are laid out along its X axis: horizontal when they fit,
     * rotated and elided when they do not, plus the bottom margin that choice costs.
     * <p>
     * The three travel together because they are one decision. Rotating without widening the
     * bottom margin puts labels over the plot frame; widening without rotating wastes the
     * space; and drawing the untruncated text after deciding to rotate re-creates exactly the
     * overlap rotation was meant to fix. A caller passes the whole record to {@link
     * #plotHeight} and {@link #drawCategoryLabels}, so the sizing and the drawing are always
     * looking at the same answer.
     *
     * @param rotated       whether the labels draw at −45° rather than horizontally
     * @param bottomPadding canvas height reserved below the plot frame for these labels
     * @param text          what to draw per slot — possibly elided, always one entry per slot
     */
    public record LabelLayout(boolean rotated, double bottomPadding, List<String> text) {}

    /**
     * Choose horizontal or rotated labels for {@code labels}, measuring rather than guessing.
     * <p>
     * A label fits when the widest of them clears its own slot with {@link #LABEL_SLOT_MARGIN}
     * to spare; the widest governs, not the average, because one long phenotype path among
     * short ones still collides with its neighbours. When they do not fit, every label is
     * elided to {@link #ROTATED_LABEL_MAX_WIDTH} — rotation alone is not enough, since a
     * 40-character path rising at −45° from the bottom of the canvas climbs straight back
     * through the plot frame it was rotated to stay clear of.
     * <p>
     * Sets the surface font before measuring, which {@link CanvasSurface} requires: it tracks
     * font size independently of the live {@code GraphicsContext} until the first {@code
     * setFont}, so measuring first would size text against a font the canvas is not drawing in.
     */
    protected LabelLayout layoutLabels(PlotSurface s, List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return new LabelLayout(false, PADDING_BOTTOM, List.of());
        }
        s.setFont(LABEL_FONT_SIZE, false);
        double slot = plotWidth() / labels.size();
        double widest = 0;
        for (String label : labels) {
            widest = Math.max(widest, s.textWidth(label));
        }
        if (widest <= slot - LABEL_SLOT_MARGIN) {
            return new LabelLayout(false, PADDING_BOTTOM, List.copyOf(labels));
        }
        List<String> elided = new ArrayList<>(labels.size());
        for (String label : labels) {
            elided.add(truncateToWidth(s, label, ROTATED_LABEL_MAX_WIDTH));
        }
        return new LabelLayout(true, PADDING_BOTTOM_ROTATED, List.copyOf(elided));
    }

    /**
     * The height of the strip a {@code rowCount}-row legend occupies above the plot.
     * <p>
     * A legend gets its own strip rather than floating inside the plot because a floating one
     * has to be positioned around the data, and the previous version did not try: it drew at a
     * fixed {@code width - PADDING_RIGHT - 90}, which is wherever the tallest bars happen to
     * be on a chart whose largest population sorts first. Reserving the space removes the
     * question — there is no overlap left to avoid.
     */
    protected double legendHeight(int rowCount) {
        return rowCount == 0 ? 0 : rowCount * LEGEND_ROW_HEIGHT + LEGEND_STRIP_GAP;
    }

    /** The plot frame's width — the canvas minus its left and right margins. */
    protected double plotWidth() {
        return getWidth() - PADDING_LEFT - PADDING_RIGHT;
    }

    /** The Y of the plot frame's top edge, below whatever legend strip is reserved. */
    protected double plotTop(int legendRows) {
        return PADDING_TOP + legendHeight(legendRows);
    }

    /**
     * The plot frame's height: whatever the canvas has left once the legend strip and the
     * category labels have taken their share. Both come out of the plot, never off the bottom
     * of the canvas, so a plot with a legend and rotated labels is a shorter plot rather than
     * one drawing past its own edge.
     * <p>
     * Floored at zero. A canvas sized smaller than its own margins would otherwise report a
     * negative height and draw every bar upside down; zero renders as an empty frame, which
     * looks like the mistake it is. Every consumer of the layout — including a later
     * hit-test — reads the same floored number, so nothing can disagree about where the plot is.
     */
    protected double plotHeight(LabelLayout layout, int legendRows) {
        return Math.max(0, getHeight() - plotTop(legendRows) - layout.bottomPadding());
    }

    /**
     * Map a {@code [0, 1]} axis fraction — from {@link AxisScale#toFraction} — onto this
     * plot's Y axis, in screen space (smaller Y is higher up, so a fraction of 1 draws at the
     * top). Takes the same {@code (layout, legendRows)} pair every other layout method takes,
     * so a tick, a bar and the axis frame cannot each resolve the plot to a different
     * rectangle.
     * <p>
     * <b>This is the ONLY value→Y mapping in this class, and every canvas's Y coordinate goes
     * through it.</b> {@link AxisScale#toFraction} is where "where does this value sit on the
     * axis" is decided — linear, logarithmic, and clamped for a clipped value — and this method
     * only ever turns that already-decided fraction into a pixel row. Before this, a value's
     * screen position was linear arithmetic private to this class ({@code valueToY}) with no
     * way to express a log axis or a percentile clip; folding that same linear formula back in
     * here, beside {@link AxisScale}'s own copy of it, would be exactly the two-implementations
     * failure {@code CLAUDE.md}'s "One gate predicate" section describes for the gating path —
     * a display mapping and a value mapping kept in step by comments instead of by construction.
     */
    protected double fractionToY(double fraction, LabelLayout layout, int legendRows) {
        double top = plotTop(legendRows);
        double plotH = plotHeight(layout, legendRows);
        return top + plotH * (1 - fraction);
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

    /**
     * Fill the whole plot with the theme's background. Called by {@link #repaint()} and
     * {@link #toSvg()} rather than by a subclass, so an SVG export can never come out with a
     * transparent page just because one plot forgot to paint one.
     * <p>
     * Sized from the canvas, not from {@code s.width()}/{@code s.height()}, so that every
     * dimension in this class comes from one place — see {@link #render} for the invariant that
     * makes the two interchangeable and for why relying on it silently would not be.
     */
    protected void drawBackground(PlotSurface s, PlotTheme t) {
        s.setFill(t.background());
        s.fillRect(0, 0, getWidth(), getHeight());
    }

    /**
     * The plot frame, an X-axis title centred beneath it, and a Y-axis title rotated up its
     * left margin. Both titles are centred on measured width, so a long axis title stays
     * centred rather than drifting the way {@code xLabel.length() * 2.5} made it drift.
     * <p>
     * <b>Rotated category labels take the X title's place, and it is dropped rather than drawn
     * over them.</b> A −45° label is anchored {@link #ROTATED_LABEL_ANCHOR_GAP} below the
     * <em>axis</em> and descends left from there, so its far end reaches within a couple of
     * pixels of the canvas bottom (see {@link #drawCategoryLabels}) — which is exactly where an
     * X title sits, so on a plot with rotated labels the two would overlap across the middle of
     * the axis. Dropping the title
     * is the cheaper loss of the two: rotation only happens when the labels are long, and a
     * label long enough to need rotating — {@code CD45+/CD3+/CD8+} — already says what the axis
     * is far better than the word "Population" does.
     */
    protected void drawAxes(PlotSurface s, PlotTheme t, LabelLayout layout, int legendRows,
                            String xLabel, String yLabel) {
        // Drawing the frame is what publishes the plot rectangle to paintedLayout(): this is
        // the one call that every plot makes exactly once per non-empty pass, and the frame it
        // strokes IS the rectangle a hit-test has to invert. Recording it anywhere else would
        // let the remembered geometry and the drawn geometry come from two different calls.
        // What makes publishing from here safe rather than merely convenient is that render()
        // clears the field before calling draw(): a pass that takes an empty-state branch never
        // reaches this line, so it publishes nothing instead of leaving the previous pass's
        // rectangle standing over a plot that is no longer on screen.
        this.painted = new PaintedLayout(layout, legendRows);
        double top = plotTop(legendRows);
        double plotW = plotWidth();
        double plotH = plotHeight(layout, legendRows);

        s.setStroke(t.axis());
        s.setLineWidth(1);
        s.strokeRect(PADDING_LEFT, top, plotW, plotH);

        s.setFont(AXIS_TITLE_FONT_SIZE, false);
        s.setFill(t.text());
        if (xLabel != null && !xLabel.isEmpty() && !layout.rotated()) {
            s.fillText(xLabel, PADDING_LEFT + (plotW - s.textWidth(xLabel)) / 2, getHeight() - 3);
        }
        if (yLabel != null && !yLabel.isEmpty()) {
            // Rotating −90° about the anchor makes the text run upwards, so the anchor is the
            // label's *lower* end: put it half a text-width below the plot's vertical centre
            // and the label straddles that centre.
            s.fillTextRotated(yLabel, 10, top + (plotH + s.textWidth(yLabel)) / 2, -90);
        }
    }

    /**
     * One label per category slot, beneath the plot — which bar is which. Plot-agnostic: the
     * canvas hands in whatever category names it already computed (population paths, region
     * names, scope names, marker names) via {@link #layoutLabels}, and this method only knows
     * where a slot's centre is, through {@link #categoryToX}.
     * <p>
     * <b>The anchor is the label's END, at {@code (cx, axisBottom + ROTATED_LABEL_ANCHOR_GAP)}.</b>
     * Horizontal labels are centred on their measured width; rotated ones hang their
     * <em>last</em> character just under the tick and run down and to the left, so the text
     * reads bottom-left to top-right and arrives at the bar it names. Since {@link
     * PlotSurface#fillText} places the origin where the text <em>starts</em>, and the −45°
     * advance direction is {@code (cos45, −sin45)} in screen coordinates, the origin handed to
     * {@link PlotSurface#fillTextRotated} is that anchor stepped back along the advance by the
     * label's measured width:
     * <pre>
     *   originX = cx − ROTATED_LABEL_DIAGONAL · w
     *   originY = axisBottom + ROTATED_LABEL_ANCHOR_GAP + ROTATED_LABEL_DIAGONAL · w
     * </pre>
     * Both offsets are written as the constants rather than as the numbers they currently hold
     * ({@link #ROTATED_LABEL_ANCHOR_GAP} and {@link #ROTATED_LABEL_DIAGONAL}), because this
     * prose has already gone stale against the code once: the gap moved 6 → 4 and two
     * sentences here kept saying 6, which is a 2px error for anyone implementing the inverse
     * from the description rather than from the fields.
     * <p>
     * Anchoring the <em>start</em> at the slot centre instead — the obvious reading, and the
     * one this class shipped first — mirrors the whole band rightwards, so the final slot's
     * label runs up to {@code 84 × cos45° ≈ 59px} past the right edge of the canvas and is
     * clipped. End-anchoring spends that overhang leftwards instead, into the {@code
     * PADDING_LEFT} margin, which is empty below the axis. Both ends were checked in a
     * rendered document, not only in arithmetic.
     * <p>
     * <b>Task 13 inverts this.</b> A hit-test that asks "which label is under the pointer"
     * must undo the same step-back, reading {@link #ROTATED_LABEL_ANCHOR_GAP} and {@link
     * #ROTATED_LABEL_DIAGONAL} from here rather than copying their present values, and working
     * from the same {@code axisBottom} — which is the plot rectangle's own bottom ({@code
     * plotTop + plotHeight}), never the canvas's — with the layout read back from {@link
     * #paintedLayout()} rather than measured again.
     * <p>
     * <b>The band fits exactly.</b> The deepest a label can reach below the axis is {@link
     * #ROTATED_LABEL_ANCHOR_GAP} plus {@link #ROTATED_LABEL_MAX_WIDTH} × sin45° = 63.4px,
     * inside the 64px {@link #PADDING_BOTTOM_ROTATED} band. That is asserted with no tolerance
     * at all, because "over by about a pixel, and only at the full elision cap" is the kind of
     * almost-right geometry that reads fine on the fixtures and then clips a label on a panel
     * with long marker names.
     */
    protected void drawCategoryLabels(PlotSurface s, PlotTheme t, LabelLayout layout,
                                      int legendRows) {
        List<String> labels = layout.text();
        if (labels.isEmpty()) return;
        int n = labels.size();
        double axisBottom = plotTop(legendRows) + plotHeight(layout, legendRows);
        s.setFont(LABEL_FONT_SIZE, false);
        s.setFill(t.mutedText());
        for (int i = 0; i < n; i++) {
            String label = labels.get(i);
            double cx = categoryToX(i, n);
            double w = s.textWidth(label);
            if (layout.rotated()) {
                s.fillTextRotated(label,
                        cx - ROTATED_LABEL_DIAGONAL * w,
                        axisBottom + ROTATED_LABEL_ANCHOR_GAP + ROTATED_LABEL_DIAGONAL * w,
                        ROTATED_LABEL_DEGREES);
            } else {
                s.fillText(label, cx - w / 2, axisBottom + 10);
            }
        }
    }

    /**
     * Gridlines and Y-axis value labels at {@code scale}'s own {@link AxisScale#ticks} — a log
     * axis therefore gets a decade ladder here instead of the 1-2-5 one a linear axis gets, see
     * {@link AxisScale#ticks} — placed by the same {@link #fractionToY} call the bars
     * themselves use, so a tick label can never point at a different height than the bar beside
     * it claims. The label text is {@link AxisScale#formatTick}, not a copy kept here, for the
     * same reason.
     * <p>
     * Drawn before the data, not after: a gridline painted over a bar reads as a seam in the
     * bar rather than as a gridline behind it.
     */
    protected void drawValueTicks(PlotSurface s, PlotTheme t, AxisScale scale,
                                  int targetCount, LabelLayout layout, int legendRows) {
        double[] ticks = scale.ticks(targetCount);
        double left = PADDING_LEFT;
        double right = PADDING_LEFT + plotWidth();
        s.setFont(LABEL_FONT_SIZE, false);
        for (double tick : ticks) {
            double y = fractionToY(scale.toFraction(tick), layout, legendRows);
            s.setStroke(t.gridline());
            s.setLineWidth(1);
            s.strokeLine(left, y, right, y);
            s.setFill(t.mutedText());
            String label = scale.formatTick(tick);
            s.fillText(label, left - 4 - s.textWidth(label), y + 3);
        }
    }

    /**
     * Overpaint a bar that {@link AxisScale#isClipped} has drawn all the way to the plot's top
     * with the conventional axis-break glyph, so a capped bar reads as "capped, and marked" —
     * never as a bar that merely stopped, which is indistinguishable on screen from a bar whose
     * true value was small. {@code top} is the Y the clipped bar was itself drawn to (its own
     * top edge), i.e. {@code fractionToY(1, layout, legendRows)} in linear mode or the
     * equivalent clamp in log mode — this method does not recompute it, so the band it paints
     * can never land anywhere but exactly where the bar's own top pixel is.
     * <p>
     * Two layers, both specified by the brief this implements: a {@code bandHeight}-tall strip
     * in {@code theme.background()} at full opacity, which is what makes the break read as a
     * deliberate cut rather than one more pixel of bar colour, and a 1px {@code theme.axis()}
     * zig-zag stroked across it — the standard axis-break glyph, built from {@link
     * PlotSurface#strokeLine} segments because {@link PlotSurface} is deliberately narrow (see
     * its own class javadoc) and gains no path primitive for one caller.
     */
    protected void drawClipMarker(PlotSurface s, PlotTheme t, double centreX, double barWidth,
                                  double top) {
        double bandHeight = 4;
        double left = centreX - barWidth / 2;
        double right = centreX + barWidth / 2;

        s.setFill(t.background());
        s.fillRect(left, top, right - left, bandHeight);

        s.setStroke(t.axis());
        s.setLineWidth(1);
        double toothWidth = 6;
        double x = left;
        boolean atBottomOfBand = true;
        double prevY = top + bandHeight;
        while (x < right) {
            double nextX = Math.min(x + toothWidth, right);
            double nextY = atBottomOfBand ? top : top + bandHeight;
            s.strokeLine(x, prevY, nextX, nextY);
            x = nextX;
            prevY = nextY;
            atBottomOfBand = !atBottomOfBand;
        }
    }

    /**
     * A colour-swatch legend, one row per label, inside the strip {@link #legendHeight}
     * reserved above the plot.
     * <p>
     * <b>{@code legendRows} must equal {@code labels.size()}, and this throws when it does
     * not.</b> It is the same number the caller passed to {@link #plotTop} and {@link
     * #plotHeight} to buy the strip; a legend that draws more rows than were reserved spills
     * over the plot frame — the exact defect the strip exists to make impossible — and one that
     * draws fewer leaves a band of dead space above every chart. Requiring the caller to state
     * the count it reserved, and rejecting a mismatch, is what makes the strip structural
     * rather than a convention the base class merely describes; the same reasoning as {@code
     * AnalysisState}'s compact constructor rejecting contradictory combinations and {@code
     * BranchTally.rebindTo} throwing rather than migrating half-way.
     * <p>
     * {@code colors} is parallel to {@code labels}; a short list falls back to the theme's
     * muted text colour rather than throwing, because an under-coloured swatch is a cosmetic
     * fault confined to the legend, where a miscounted row is a layout fault that reaches the
     * data.
     *
     * @throws IllegalArgumentException if {@code legendRows} does not match the number of labels
     */
    protected void drawLegend(PlotSurface s, PlotTheme t, int legendRows,
                              List<String> labels, List<Color> colors) {
        int rows = labels == null ? 0 : labels.size();
        if (rows != legendRows) {
            throw new IllegalArgumentException(
                    "legend has " + rows + " labels but " + legendRows + " rows were reserved: "
                            + "plotTop/plotHeight sized the strip for " + legendRows
                            + ", so drawing " + rows + " would "
                            + (rows > legendRows ? "spill over the plot frame" : "leave dead space")
                            + ". Pass the same row count to both.");
        }
        if (rows == 0) return;
        s.setFont(LEGEND_FONT_SIZE, false);
        for (int i = 0; i < labels.size(); i++) {
            double rowY = PADDING_TOP + i * LEGEND_ROW_HEIGHT;
            Color swatch = colors != null && i < colors.size() ? colors.get(i) : t.mutedText();
            s.setFill(swatch);
            s.fillRect(PADDING_LEFT, rowY, 10, 10);
            s.setFill(t.text());
            s.fillText(labels.get(i), PADDING_LEFT + 14, rowY + 9);
        }
    }

    /**
     * The message a plot shows instead of an empty frame, centred on its measured width in the
     * theme's muted text colour. Centred exactly rather than at {@code width / 2 - 20}, which
     * was right for one particular string ("No data") and visibly off for every other — and
     * every one of the four plots now says which of several different things went wrong rather
     * than sharing that one uninformative placeholder.
     */
    protected void drawEmptyState(PlotSurface s, PlotTheme t, String message) {
        if (message == null || message.isEmpty()) return;
        s.setFont(EMPTY_STATE_FONT_SIZE, false);
        s.setFill(t.mutedText());
        s.fillText(message, (getWidth() - s.textWidth(message)) / 2, getHeight() / 2);
    }

    /**
     * {@code text} shortened with a trailing ellipsis until it fits {@code maxWidth} at the
     * surface's current font, or {@code "…"} if even one character will not fit.
     * <p>
     * Measured per candidate rather than estimated from a character count: phenotype paths are
     * full of narrow {@code +}, {@code -} and {@code /} characters, so a count-based estimate
     * cuts them far shorter than it needs to. Text that already fits is returned unchanged —
     * an ellipsis on a label that had room is a lie about the data.
     */
    static String truncateToWidth(PlotSurface s, String text, double maxWidth) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        if (s.textWidth(text) <= maxWidth) {
            return text;
        }
        for (int len = text.length() - 1; len > 0; len--) {
            String candidate = text.substring(0, len) + "…";
            if (s.textWidth(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return "…";
    }

    /**
     * Tick values on a 1-2-5 ladder: roughly {@code targetCount} of them spanning
     * {@code [min, max]}, each one a round number a reader can hold in their head.
     * <p>
     * The previous {@code valueTicks} divided the range into {@code n - 1} equal parts, which
     * is arithmetically fine and unreadable in practice — a 41,733-cell population produced
     * axis labels reading {@code 0, 13911, 27822, 41733}, and at one decimal place, {@code
     * 41733.3}. Rounding the step instead of the range is what makes the labels land on
     * {@code 0, 20000, 40000}.
     * <p>
     * Ticks are multiples of the chosen step, so the last one generally falls short of
     * {@code max} rather than sitting on it — that is the trade: round numbers, or both
     * endpoints, not both. A range that is zero, negative or non-finite has no ladder to climb
     * and returns {@code {min}} alone. The result can legitimately be empty when a narrow
     * range straddles no multiple of its own step (e.g. {@code [0.2, 0.9]} at step 1); a
     * caller that must show something should widen its range rather than expect a tick.
     */
    static double[] niceTicks(double min, double max, int targetCount) {
        double range = max - min;
        if (!Double.isFinite(range) || range <= 0 || targetCount <= 0) {
            return new double[] { min };
        }
        double raw = range / targetCount;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double step = 10 * magnitude;
        for (double multiple : new double[] { 1, 2, 5, 10 }) {
            if (multiple * magnitude >= raw) {
                step = multiple * magnitude;
                break;
            }
        }
        double first = Math.ceil(min / step) * step;
        // Comparing against max + a step-relative epsilon, not max: first + i * step
        // accumulates enough error at, say, step 0.1 to drop the final tick otherwise.
        double limit = max + step * 1e-9;
        List<Double> ticks = new ArrayList<>();
        for (int i = 0; ; i++) {
            double value = first + i * step;
            if (value > limit) break;
            ticks.add(value);
        }
        double[] out = new double[ticks.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ticks.get(i);
        }
        return out;
    }

}
