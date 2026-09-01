package qupath.ext.flowpath.analysis.ui;

import javafx.application.ColorScheme;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import qupath.lib.gui.prefs.QuPathStyleManager;

import java.util.List;

/**
 * One colour palette for the four Analysis canvases, chosen once and looked up rather than
 * hardcoded per canvas. Before this class every canvas carried its own {@code Color.rgb(30,
 * 30, 30)} background and its own ad-hoc greys, and they drifted: {@code
 * MarkerPositivityCanvas} drew its "Ungated" segment at {@code rgb(80,80,90)} on that same
 * {@code rgb(30,30,30)} background — a contrast ratio so low the one segment the plot exists
 * to make visible (a marker nobody gated on {@code UNMEASURED}, not a marker that came back
 * negative) was the least visible thing on it.
 * <p>
 * <b>Detection route: QuPath's own theme signal, not luminance sampling.</b> {@link
 * #detect(Node)} first calls {@link QuPathStyleManager#getStyleColorScheme()} — a public
 * static accessor on QuPath's own preferences package ({@code qupath.lib.gui.prefs}) that
 * reports the {@link ColorScheme} the *user selected*, falling back internally to the OS
 * preference when QuPath has no style chosen yet. That is a strictly better signal than
 * sampling a pixel: QuPath's theme is a single global choice, not a per-node property, and a
 * user who picks a dark QuPath skin over a light OS theme (or the reverse) gets the plot that
 * matches the skin they are looking at, not the OS they happen to be running. Outside a
 * running QuPath instance — this class's own unit tests, most obviously — that call throws
 * ({@code ExceptionInInitializerError}, verified empirically: {@code
 * QuPathStyleManager}'s static initialiser reaches for QuPath's user-preferences store, which
 * does not exist in a bare JUnit process). Route 2 below exists for exactly that case, so
 * {@code detect} is caught with {@code Throwable} rather than a narrower type — a
 * {@code LinkageError} is not a checked exception and would otherwise escape the fallback it
 * is here to trigger.
 * <p>
 * <b>Fallback route: luminance sampling.</b> When the QuPath signal is unavailable, {@code
 * detect} walks {@code node}'s parent chain for the first {@link Region} whose {@link
 * Region#getBackground()} paints a solid {@link Color}, then falls back to the enclosing
 * {@link Scene}'s fill. A {@code null} node, or a chain with nothing paintable, cannot be
 * sampled at all and resolves to {@link #LIGHT} — deliberately, not {@link #DARK}: a light
 * plot floating on a dark theme is merely a cosmetic mismatch, whereas a dark plot on a light
 * theme (the bug this class exists to fix) reads as broken.
 * <p>
 * <b>Analysis-only this release.</b> {@code ui/HistogramCanvas} and {@code
 * ui/ScatterPlotCanvas} — the two 1D/2D gate-editing plots outside the Analysis window — and
 * {@code umap/ui/UmapCanvas} keep their own existing colours for now. Nothing in this task
 * wires them to {@code PlotTheme}; that is deliberately left to whichever later task touches
 * each of those files, so this change stays reviewable as "one palette, defined" rather than
 * "one palette, defined and applied in five places at once".
 * <p>
 * <b>Contrast standard: WCAG 1.4.11, not {@link Color#getBrightness()}.</b> Every data-bearing
 * colour ({@link #positive}, {@link #negative}, {@link #ungated}, every {@link #series} entry)
 * is held to a WCAG 1.4.11 Non-text Contrast floor of 3:1 relative-luminance contrast against
 * its own {@link #background} — the standard that applies to graphical objects, which is what
 * a chart mark is. {@code PlotThemeTest} measures this directly (its {@code contrastRatio}
 * helper), not via {@code getBrightness()}: that accessor is HSB brightness, i.e. {@code
 * max(r,g,b)}, and it misjudges a colour like {@code #2563EB} — a strong blue that reads
 * clearly against white — as barely brighter than white, purely because its blue channel sits
 * near maximum. Two of the design values that came out of choosing this palette by eye failed
 * that 3:1 floor once actually measured: {@link #LIGHT}'s {@link #negative} ({@code #9AA3AF},
 * 2.55:1) and its seventh {@link #series} entry ({@code #CA8A04}, 2.94:1). Both are replaced
 * below with a uniformly-scaled darker shade of the same colour — hue and saturation held
 * fixed, only luminance lowered, just past 3:1 rather than far past it — so {@link #negative}
 * is still recognisably the same neutral grey, not a different colour chosen to pass a test.
 * Every other constant below is the value the palette was designed with; restoring a value
 * that already cleared 3:1 (e.g. {@link #LIGHT}'s {@code #D97706} ungated colour, 3.19:1, or
 * its {@code #2563EB} series entry, 5.17:1) to something darker would only have made the
 * palette worse for no measurable reason.
 *
 * @param background the plot's fill colour
 * @param axis       colour for axis lines and tick marks
 * @param gridline   colour for background gridlines, fainter than {@link #axis}
 * @param text       primary label colour (axis titles, tick labels)
 * @param mutedText  secondary label colour (e.g. an empty-state "No data" message)
 * @param positive   colour for a gate's positive / inside branch
 * @param negative   colour for a gate's negative / outside branch — neutral grey, not a hue,
 *                   so it never competes with {@link #series} for attention
 * @param ungated    colour for cells a gate never measured ({@code UNMEASURED}, see
 *                   {@code ResolvedGate}) — distinct from both {@link #negative} and
 *                   {@link #background} by construction; see
 *                   {@code ungatedIsDistinguishableFromNegativeAndFromTheBackground}
 * @param series     the categorical palette for population/leaf charts, looked up through
 *                   {@link #series(int)} rather than indexed directly so a caller never has
 *                   to guard against running out of colours
 */
public record PlotTheme(Color background, Color axis, Color gridline, Color text,
                         Color mutedText, Color positive, Color negative,
                         Color ungated, List<Color> series) {

    public static final PlotTheme DARK = new PlotTheme(
            Color.web("#1E1E1E"),   // background — the rgb(30,30,30) every canvas already used
            Color.web("#4D4D4D"),   // axis
            Color.web("#2E2E2E"),   // gridline
            Color.web("#D9D9D9"),   // text
            Color.web("#8A8A8A"),   // mutedText
            Color.web("#57D9A3"),   // positive  (teal)
            Color.web("#8590A2"),   // negative  (neutral grey)
            Color.web("#FFAB00"),   // ungated   (amber)
            List.of(Color.web("#4C9AFF"), Color.web("#57D9A3"), Color.web("#FFAB00"),
                    Color.web("#FF5C5C"), Color.web("#B380FF"), Color.web("#00C7E6"),
                    Color.web("#FFD666"), Color.web("#8590A2")));

    // negative and series[6] are darkened from the design values #9AA3AF and #CA8A04 — see
    // the class javadoc's "Contrast standard" paragraph for why and by how much.
    public static final PlotTheme LIGHT = new PlotTheme(
            Color.web("#FFFFFF"),
            Color.web("#B3B3B3"),
            Color.web("#EDEDED"),
            Color.web("#1F2430"),
            Color.web("#6B7280"),
            Color.web("#1B9E77"),
            Color.web("#8C949F"),   // negative — darkened from #9AA3AF, still neutral grey
            Color.web("#D97706"),
            List.of(Color.web("#2563EB"), Color.web("#1B9E77"), Color.web("#D97706"),
                    Color.web("#DC2626"), Color.web("#7C3AED"), Color.web("#0891B2"),
                    Color.web("#C68704"), Color.web("#6B7280")));   // series[6] darkened from #CA8A04

    // The teal / neutral-grey / amber triple for positive / negative / ungated is chosen so
    // the three segments stay separable under deuteranopia and protanopia, where a green/red
    // pair would not.

    /**
     * One colour from {@link #series}, wrapping via {@link Math#floorMod} rather than
     * throwing — a chart with more categories than palette entries repeats colours instead
     * of crashing, and a negative index (e.g. a "no data" sentinel some caller subtracted
     * one from) still resolves rather than throwing {@code IndexOutOfBoundsException}.
     */
    public Color series(int index) {
        return series.get(Math.floorMod(index, series.size()));
    }

    /** {@code null} — an undetectable background — is treated as light, never dark. */
    public static boolean isDark(Color background) {
        return background != null && background.getBrightness() < 0.5;
    }

    /**
     * {@link #DARK} or {@link #LIGHT}, matching QuPath's own theme where that can be read,
     * else sampled from {@code node}'s background, else {@link #LIGHT}. See the class
     * javadoc for why detection is layered this way and what each route falls back from.
     */
    public static PlotTheme detect(Node node) {
        ColorScheme scheme = queryQuPathColorScheme();
        if (scheme != null) {
            return scheme == ColorScheme.DARK ? DARK : LIGHT;
        }
        return isDark(sampleBackground(node)) ? DARK : LIGHT;
    }

    /**
     * {@link QuPathStyleManager#getStyleColorScheme()}, or {@code null} when it cannot be
     * answered — outside a running QuPath instance that static accessor throws {@code
     * ExceptionInInitializerError} (an {@link Error}, not an {@link Exception}), so this
     * catches {@link Throwable} deliberately rather than letting the fallback route below go
     * unreached.
     */
    private static ColorScheme queryQuPathColorScheme() {
        try {
            return QuPathStyleManager.getStyleColorScheme();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Walks {@code node}'s parent chain for the first {@link Region} with a solid-colour
     * background, then falls back to the enclosing {@link Scene}'s fill. Returns {@code
     * null} — not a colour — when nothing paintable is found, so {@link #isDark(Color)}
     * resolves that case to {@link #LIGHT} the same way it resolves an explicit {@code null}.
     */
    private static Color sampleBackground(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof Region region) {
                Background bg = region.getBackground();
                if (bg != null && !bg.getFills().isEmpty()) {
                    Paint fill = bg.getFills().get(0).getFill();
                    if (fill instanceof Color c) {
                        return c;
                    }
                }
            }
        }
        Scene scene = node == null ? null : node.getScene();
        Paint fill = scene == null ? null : scene.getFill();
        return fill instanceof Color c ? c : null;
    }
}
