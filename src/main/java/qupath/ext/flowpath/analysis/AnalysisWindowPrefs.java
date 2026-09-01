package qupath.ext.flowpath.analysis;

import javafx.geometry.Rectangle2D;

import java.util.prefs.Preferences;

/**
 * What {@link AnalysisWindow} remembers about itself across a JVM restart: window geometry,
 * the active plot tab, the chosen scope, and the Y-axis remedies (log/clip/percentile) of
 * whichever plot the user had selected.
 * <p>
 * <b>This is not what makes a close/reopen within one session keep the user's arrangement.</b>
 * That already happens for free, because {@link AnalysisWindow#disposeStage} keeps the
 * {@link qupath.ext.flowpath.analysis.ui.AnalysisPane} instance alive — every selection lives
 * on as ordinary Java object state with no round trip through here at all. This class exists
 * for the two things in-memory state cannot survive: the JVM restarting (a fresh QuPath
 * launch, a fresh {@code AnalysisWindow}), and {@link AnalysisWindow#dispose()}, the true
 * teardown path {@code FlowPathPane} calls when the extension pane itself goes away. Confusing
 * the two is the exact failure mode to guard against — deleting the pane-survival change and
 * "compensating" by persisting every keystroke here would trade an in-memory fact for a
 * slower, lossier copy of the same fact.
 * <p>
 * Backed by {@link java.util.prefs.Preferences} — deliberately not any QuPath preferences API,
 * so this extension gains no dependency on one — under
 * {@link Preferences#userNodeForPackage(Class)} keyed off this class.
 * <p>
 * <b>{@link #load} never throws.</b> A missing key, a key that will not parse as its type, or
 * (uniquely dangerous here) a percentile outside {@code [50, 100]} all fall back to
 * {@link #defaults()}'s value for that one field rather than propagating — the last case
 * matters because {@link qupath.ext.flowpath.analysis.ui.ScaleOptions}'s compact constructor
 * rejects an out-of-range percentile outright, so a hand-edited or corrupted preferences entry
 * would otherwise throw the moment the Analysis window tried to open, rather than merely
 * losing one remembered setting.
 * <p>
 * <b>Screen-clamping is deliberately not part of {@link #load}.</b> Resolving "the primary
 * screen's visual bounds" means calling {@link javafx.stage.Screen#getPrimary()}, which needs
 * a live JavaFX platform — exactly the dependency this record exists to avoid, since the
 * brief's own tests construct it directly against a scratch {@code Preferences} node with no
 * toolkit running at all. {@link #clampToScreen(Rectangle2D)} is the separate, pure function
 * that does the actual arithmetic: it takes the bounds as a plain value (a {@code Rectangle2D}
 * needs no running toolkit to construct), so it is exactly as testable as {@link #load} itself
 * with a synthetic rectangle standing in for the real screen. {@link AnalysisWindow} is the
 * one production caller, applying it against {@code Screen.getPrimary().getVisualBounds()}
 * once it is actually about to show a real {@code Stage}.
 *
 * @param x            window X, in {@link Double#NaN} when never saved — see {@link #defaults()}
 * @param y            window Y, as {@link #x}
 * @param width        window width
 * @param height       window height
 * @param selectedTab  which plot tab was active (Composition=0 .. Marker Positivity=3)
 * @param scope        {@link qupath.ext.flowpath.model.PopulationStats.Scope#name()} of the
 *                      scope that was chosen — a plain string, not the enum itself, so this
 *                      class never has to import the model package
 * @param log          the selected tab's plot's {@link
 *                      qupath.ext.flowpath.analysis.ui.ScaleOptions#log()}
 * @param clip         as {@link #log}, for {@link
 *                      qupath.ext.flowpath.analysis.ui.ScaleOptions#clip()}
 * @param percentile   as {@link #log}, for {@link
 *                      qupath.ext.flowpath.analysis.ui.ScaleOptions#percentile()} — always in
 *                      {@code [50, 100]} once it has passed through {@link #load}
 */
public record AnalysisWindowPrefs(double x, double y, double width, double height,
                                   int selectedTab, String scope,
                                   boolean log, boolean clip, double percentile) {

    private static final double DEFAULT_WIDTH = 960;
    private static final double DEFAULT_HEIGHT = 640;
    private static final double MIN_PERCENTILE = 50;
    private static final double MAX_PERCENTILE = 100;

    /**
     * The window as it has never been saved: 960×640, geometry unset (so a first-ever open
     * gets the platform's own default placement rather than a hard-coded corner — see {@link
     * AnalysisWindow}'s handling of a {@link Double#NaN} x/y), the Composition tab, whole-slide
     * scope, and a plain linear axis.
     */
    public static AnalysisWindowPrefs defaults() {
        return new AnalysisWindowPrefs(Double.NaN, Double.NaN, DEFAULT_WIDTH, DEFAULT_HEIGHT,
                0, "WHOLE_SLIDE", false, false, MAX_PERCENTILE - 5);
    }

    /**
     * Read every field from {@code node}, falling back to {@link #defaults()}'s own value
     * field-by-field for anything missing or that will not parse — never for the whole record
     * at once, so a node with one corrupted key still returns every other field the user
     * actually saved. {@link Preferences#getDouble}/{@code getInt}/{@code getBoolean} already
     * implement exactly that per-field fallback for a value that fails to parse (per their own
     * javadoc), which is what makes this method a plain read rather than a hand-written parser
     * with its own failure modes to get wrong.
     * <p>
     * The one thing those built-in fallbacks cannot catch is a syntactically valid double that
     * is still out of range — {@code "999"} parses fine as a percentile and would sail straight
     * into {@link qupath.ext.flowpath.analysis.ui.ScaleOptions}'s compact constructor, which
     * throws outside {@code [50, 100]}. That repair happens here, explicitly, on the way out.
     */
    public static AnalysisWindowPrefs load(Preferences node) {
        AnalysisWindowPrefs d = defaults();
        double x = node.getDouble("x", d.x());
        double y = node.getDouble("y", d.y());
        double width = node.getDouble("width", d.width());
        double height = node.getDouble("height", d.height());
        if (!(width > 0)) width = d.width();
        if (!(height > 0)) height = d.height();
        int selectedTab = node.getInt("selectedTab", d.selectedTab());
        String scope = node.get("scope", d.scope());
        boolean log = node.getBoolean("log", d.log());
        boolean clip = node.getBoolean("clip", d.clip());
        double percentile = node.getDouble("percentile", d.percentile());
        if (!(percentile >= MIN_PERCENTILE && percentile <= MAX_PERCENTILE)) {
            percentile = d.percentile();
        }
        return new AnalysisWindowPrefs(x, y, width, height, selectedTab, scope, log, clip, percentile);
    }

    /** Write every field to {@code node} — the inverse of {@link #load}. */
    public void save(Preferences node) {
        node.putDouble("x", x);
        node.putDouble("y", y);
        node.putDouble("width", width);
        node.putDouble("height", height);
        node.putInt("selectedTab", selectedTab);
        node.put("scope", scope == null ? defaults().scope() : scope);
        node.putBoolean("log", log);
        node.putBoolean("clip", clip);
        node.putDouble("percentile", percentile);
    }

    /**
     * This record with its geometry pulled inside {@code screenBounds} — what makes a window
     * saved on a monitor that is no longer attached still appear, rather than opening off every
     * currently-connected display where nothing can ever click it. {@code x}/{@code y} of
     * {@link Double#NaN} (the never-saved default) pass through untouched: there is no saved
     * position to rescue, and clamping {@code NaN} arithmetically would just produce another
     * {@code NaN} anyway, so this says so explicitly rather than relying on that coincidence.
     * <p>
     * Width and height are capped to the screen's own size first — a window saved larger than
     * the current screen has nowhere valid to sit no matter where {@code x}/{@code y} land — and
     * {@code x}/{@code y} are then clamped so the (possibly-shrunk) window's far edge never
     * passes the screen's own far edge either.
     */
    public AnalysisWindowPrefs clampToScreen(Rectangle2D screenBounds) {
        double w = Math.min(width, screenBounds.getWidth());
        double h = Math.min(height, screenBounds.getHeight());
        double cx = Double.isNaN(x) ? x : clamp(x, screenBounds.getMinX(), screenBounds.getMaxX() - w);
        double cy = Double.isNaN(y) ? y : clamp(y, screenBounds.getMinY(), screenBounds.getMaxY() - h);
        return new AnalysisWindowPrefs(cx, cy, w, h, selectedTab, scope, log, clip, percentile);
    }

    private static double clamp(double v, double lo, double hi) {
        if (hi < lo) return lo; // the window (already capped to screen size) still doesn't fit
        return Math.max(lo, Math.min(v, hi));
    }
}
