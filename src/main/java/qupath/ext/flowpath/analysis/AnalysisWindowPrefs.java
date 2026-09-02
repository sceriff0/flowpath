package qupath.ext.flowpath.analysis;

import javafx.geometry.Rectangle2D;
import qupath.ext.flowpath.analysis.ui.ScaleOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * What {@link AnalysisWindow} remembers about itself across a JVM restart: window geometry,
 * the active plot tab, the chosen scope, and the Y-axis remedies (log/clip/percentile) of
 * <b>every</b> plot tab, not only whichever one happened to be selected when the window closed.
 * <p>
 * <b>Why all four, and not just the selected tab.</b> The four plot tabs scale independently —
 * a user can turn log scale on for Composition and, separately, for Marker Positivity, while
 * leaving By Region linear. A single remembered triple could only ever restore whichever ONE
 * tab was on screen at save time, silently discarding the other three. Partial restore is worse
 * than none: no restore reads as "the feature doesn't exist yet", but partial restore reads as
 * the feature being broken, because the user cannot tell which of their settings survived
 * without opening all four tabs to check. {@link #scaleOptionsByTab()} is a {@link List} of
 * exactly {@link #TAB_COUNT} entries, index-matched to
 * {@code AnalysisPane}'s own tab order (Composition, By Region, By Scope, Marker Positivity).
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
 * (uniquely dangerous here) a percentile outside {@code [50, 100]} all fall back to that one
 * field's own default rather than propagating — the last case matters because {@link
 * ScaleOptions}'s compact constructor rejects an out-of-range percentile outright, so a
 * hand-edited or corrupted preferences entry would otherwise throw the moment the Analysis
 * window tried to open, rather than merely losing one remembered setting. That repair is
 * applied <b>per tab</b>: a corrupt {@code percentile1} falls back to that one tab's default and
 * leaves tabs 0, 2 and 3 exactly as saved — {@link #load} reads and repairs each tab's three
 * keys independently in its own loop iteration, so a bad value in one iteration cannot short
 * a later one out of running at all.
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
 * @param x                 window X, {@link Double#NaN} when never saved — see {@link
 *                          #defaults()}. Left as {@code NaN} rather than some on-screen
 *                          coordinate deliberately: a window that has never been positioned by
 *                          the user should get the platform's own default placement on its
 *                          first-ever open, not a hard-coded corner that may not even suit their
 *                          monitor layout. {@code AnalysisWindow} treats {@code NaN} as "do not
 *                          call setX/setY at all" — do not "simplify" that into {@code 0}, or
 *                          every fresh install opens pinned to the top-left of the primary
 *                          display instead of wherever the windowing system would have put it.
 * @param y                 window Y, as {@link #x}
 * @param width             window width
 * @param height            window height
 * @param selectedTab       which plot tab was active (Composition=0 .. Marker Positivity=3)
 * @param scope             {@link qupath.ext.flowpath.model.PopulationStats.Scope#name()} of the
 *                          scope that was chosen — a plain string, not the enum itself, so this
 *                          class never has to import the model package. A {@code null} passed in
 *                          is repaired to {@link #DEFAULT_SCOPE} by the compact constructor, so
 *                          every getter of this field is guaranteed non-null once construction
 *                          has succeeded
 * @param scaleOptionsByTab the Y-axis remedy of every plot tab, in tab order — exactly {@link
 *                          #TAB_COUNT} entries, each already guaranteed to hold a percentile in
 *                          {@code [50, 100]} once it has passed through {@link #load}
 */
public record AnalysisWindowPrefs(double x, double y, double width, double height,
                                   int selectedTab, String scope,
                                   List<ScaleOptions> scaleOptionsByTab) {

    /** Composition, By Region, By Scope, Marker Positivity — {@code AnalysisPane}'s own order. */
    public static final int TAB_COUNT = 4;

    private static final double DEFAULT_WIDTH = 960;
    private static final double DEFAULT_HEIGHT = 640;
    private static final double MIN_PERCENTILE = 50;
    private static final double MAX_PERCENTILE = 100;
    /** {@link qupath.ext.flowpath.model.PopulationStats.Scope#WHOLE_SLIDE}'s {@code name()}. */
    private static final String DEFAULT_SCOPE = "WHOLE_SLIDE";

    /**
     * Defends the "exactly one entry per tab" invariant every reader of {@link
     * #scaleOptionsByTab} depends on ({@link AnalysisWindow#open} indexes it positionally
     * against {@code AnalysisPane}'s own tab list) — the same "throw rather than migrate
     * half-way" rule {@code GateTree}/{@code PhenotypeSnapshot} apply elsewhere in this codebase
     * to a length mismatch between two things that are supposed to describe the same set.
     * {@link #load} and {@link #defaults()} are the only two places this record is ever built in
     * production, and both always pass exactly {@link #TAB_COUNT} entries, so this only ever
     * fires against a hand-built record — almost always a test's own mistake, worth failing loud
     * for. {@link List#copyOf} on the way in also closes off a caller mutating the list this
     * record hands back out from under a later reader.
     * <p>
     * Also the one place {@code scope == null} is repaired to {@link #DEFAULT_SCOPE}. This used
     * to be a ternary duplicated at both of this record's two constructors — {@link #save} and
     * {@link AnalysisWindow#saveWindowPrefs()} — one guard apiece, each trusting the other one
     * did not already exist. Folding it in here means every caller that ever constructs this
     * record, present or future, gets the repair for free, and {@link #scope} itself can be
     * documented as never {@code null} once construction succeeds.
     */
    public AnalysisWindowPrefs {
        if (scaleOptionsByTab == null || scaleOptionsByTab.size() != TAB_COUNT) {
            throw new IllegalArgumentException(
                    "scaleOptionsByTab must have exactly " + TAB_COUNT + " entries (one per plot "
                            + "tab), got "
                            + (scaleOptionsByTab == null ? "null" : scaleOptionsByTab.size()));
        }
        scaleOptionsByTab = List.copyOf(scaleOptionsByTab);
        if (scope == null) {
            scope = DEFAULT_SCOPE;
        }
    }

    /**
     * The window as it has never been saved: 960×640, geometry unset (so a first-ever open
     * gets the platform's own default placement — see this record's own {@code x} javadoc),
     * the Composition tab, whole-slide scope, and every tab at a plain linear axis.
     */
    public static AnalysisWindowPrefs defaults() {
        return new AnalysisWindowPrefs(Double.NaN, Double.NaN, DEFAULT_WIDTH, DEFAULT_HEIGHT,
                0, DEFAULT_SCOPE, List.of(ScaleOptions.LINEAR, ScaleOptions.LINEAR,
                        ScaleOptions.LINEAR, ScaleOptions.LINEAR));
    }

    /**
     * Read every field from {@code node}, falling back to {@link #defaults()}'s own value
     * field-by-field (and, for {@link #scaleOptionsByTab}, tab-by-tab) for anything missing or
     * that will not parse — never for the whole record at once, so a node with one corrupted key
     * still returns every other field the user actually saved. {@link Preferences#getDouble}/
     * {@code getInt}/{@code getBoolean} already implement exactly that per-key fallback for a
     * value that fails to parse (per their own javadoc), which is what makes this method a plain
     * read rather than a hand-written parser with its own failure modes to get wrong.
     * <p>
     * The one thing those built-in fallbacks cannot catch is a syntactically valid double that
     * is still out of range — {@code "999"} parses fine as a percentile and would sail straight
     * into {@link ScaleOptions}'s compact constructor, which throws outside {@code [50, 100]}.
     * That repair happens here, explicitly, once per tab inside the loop below — each iteration
     * reads and repairs its own {@code logN}/{@code clipN}/{@code percentileN} keys independently
     * of the other three, so a corrupt {@code percentile1} degrades tab 1 alone rather than
     * throwing out of the loop and leaving tabs 2 and 3 unread.
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

        List<ScaleOptions> perTab = new ArrayList<>(TAB_COUNT);
        for (int i = 0; i < TAB_COUNT; i++) {
            ScaleOptions tabDefault = d.scaleOptionsByTab().get(i);
            boolean log = node.getBoolean("log" + i, tabDefault.log());
            boolean clip = node.getBoolean("clip" + i, tabDefault.clip());
            double percentile = node.getDouble("percentile" + i, tabDefault.percentile());
            if (!(percentile >= MIN_PERCENTILE && percentile <= MAX_PERCENTILE)) {
                percentile = tabDefault.percentile();
            }
            perTab.add(new ScaleOptions(log, clip, percentile));
        }
        return new AnalysisWindowPrefs(x, y, width, height, selectedTab, scope, perTab);
    }

    /** Write every field to {@code node} — the inverse of {@link #load}. */
    public void save(Preferences node) {
        node.putDouble("x", x);
        node.putDouble("y", y);
        node.putDouble("width", width);
        node.putDouble("height", height);
        node.putInt("selectedTab", selectedTab);
        node.put("scope", scope); // never null -- the compact constructor already repaired it
        for (int i = 0; i < TAB_COUNT; i++) {
            ScaleOptions options = scaleOptionsByTab.get(i);
            node.putBoolean("log" + i, options.log());
            node.putBoolean("clip" + i, options.clip());
            node.putDouble("percentile" + i, options.percentile());
        }
    }

    /**
     * This record with its geometry pulled inside {@code screenBounds} — what makes a window
     * saved on a monitor that is no longer attached still appear, rather than opening off every
     * currently-connected display where nothing can ever click it. {@code x}/{@code y} of
     * {@link Double#NaN} (the never-saved default) pass through untouched: there is no saved
     * position to rescue, and clamping {@code NaN} arithmetically would just produce another
     * {@code NaN} anyway, so this says so explicitly rather than relying on that coincidence.
     * {@link #scaleOptionsByTab} is untouched — it describes plot axes, not screen geometry.
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
        return new AnalysisWindowPrefs(cx, cy, w, h, selectedTab, scope, scaleOptionsByTab);
    }

    private static double clamp(double v, double lo, double hi) {
        if (hi < lo) return lo; // the window (already capped to screen size) still doesn't fit
        return Math.max(lo, Math.min(v, hi));
    }
}
