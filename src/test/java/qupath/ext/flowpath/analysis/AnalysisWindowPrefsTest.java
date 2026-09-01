package qupath.ext.flowpath.analysis;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.analysis.ui.ScaleOptions;

import java.util.List;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** Preferences only — no Stage, no toolkit. */
class AnalysisWindowPrefsTest {

    private static Preferences scratch() {
        return Preferences.userRoot().node("flowpath-test/" + java.util.UUID.randomUUID());
    }

    /** Four distinct {@link ScaleOptions}, one per tab, so a test can catch index-swapping bugs
     * that four identical entries would hide. */
    private static List<ScaleOptions> distinctPerTab() {
        return List.of(
                new ScaleOptions(true, true, 90),
                new ScaleOptions(false, false, 95),
                new ScaleOptions(true, false, 70),
                new ScaleOptions(false, true, 60));
    }

    private static List<ScaleOptions> uniform(ScaleOptions options) {
        return List.of(options, options, options, options);
    }

    @Test
    void roundTripsEverySetting() throws Exception {
        Preferences node = scratch();
        try {
            new AnalysisWindowPrefs(100, 200, 1200, 800, 2, "ANNOTATION_K", distinctPerTab())
                    .save(node);
            AnalysisWindowPrefs read = AnalysisWindowPrefs.load(node);
            assertEquals(1200, read.width(), 1e-9);
            assertEquals(2, read.selectedTab());
            assertEquals("ANNOTATION_K", read.scope());
            assertEquals(distinctPerTab(), read.scaleOptionsByTab(),
                    "all four tabs' scale options round-trip, in order, not only one of them");
        } finally {
            node.removeNode();
        }
    }

    @Test
    void anEmptyNodeYieldsTheDefaultsRatherThanZeroes() {
        AnalysisWindowPrefs read = AnalysisWindowPrefs.load(scratch());
        assertEquals(AnalysisWindowPrefs.defaults().width(), read.width(), 1e-9);
        assertTrue(read.width() > 0 && read.height() > 0,
                "a zero-sized window would open invisible");
    }

    @Test
    void nonsenseValuesFallBackRatherThanThrow() {
        Preferences node = scratch();
        node.put("width", "not-a-number");
        node.put("percentile0", "999");
        AnalysisWindowPrefs read = assertDoesNotThrow(() -> AnalysisWindowPrefs.load(node));
        assertEquals(AnalysisWindowPrefs.defaults().width(), read.width(), 1e-9);
        double tab0Percentile = read.scaleOptionsByTab().get(0).percentile();
        assertTrue(tab0Percentile >= 50 && tab0Percentile <= 100,
                "a percentile outside [50,100] would throw inside ScaleOptions");
    }

    /**
     * A negative or wildly out-of-range percentile is exactly as dangerous as one above 100 --
     * {@code ScaleOptions}'s compact constructor rejects both ends of the range identically --
     * so {@link AnalysisWindowPrefs#load} must repair a value below 50 the same way it repairs
     * one above 100, not merely clamp the high end because that is the only case the brief's
     * own sample data happens to exercise.
     */
    @Test
    void aPercentileBelowFiftyAlsoFallsBackRatherThanThrow() {
        Preferences node = scratch();
        node.putDouble("percentile0", -12.0);
        AnalysisWindowPrefs read = assertDoesNotThrow(() -> AnalysisWindowPrefs.load(node));
        double tab0Percentile = read.scaleOptionsByTab().get(0).percentile();
        assertTrue(tab0Percentile >= 50 && tab0Percentile <= 100);
    }

    /**
     * The assertion that catches a loop bailing out on its first error: a corrupt value in ONE
     * tab's stored percentile must repair only that tab, leaving the other three exactly as
     * they were saved. {@link AnalysisWindowPrefs#load} reads and repairs each tab's
     * {@code logN}/{@code clipN}/{@code percentileN} keys in an independent loop iteration --
     * this pins that tab 1's corruption cannot reach tabs 0, 2 or 3, which a naive
     * "parse everything, then validate the whole record at the end and fall back entirely on
     * any failure" implementation would get wrong.
     */
    @Test
    void aBadPercentileInOneTabLeavesTheOtherThreeIntact() throws Exception {
        Preferences node = scratch();
        try {
            List<ScaleOptions> saved = distinctPerTab();
            new AnalysisWindowPrefs(0, 0, 960, 640, 0, "WHOLE_SLIDE", saved).save(node);
            // Corrupt ONLY tab 1's percentile after a legitimate save of all four.
            node.putDouble("percentile1", 250.0);

            AnalysisWindowPrefs read = AnalysisWindowPrefs.load(node);
            List<ScaleOptions> perTab = read.scaleOptionsByTab();

            assertEquals(saved.get(0), perTab.get(0), "tab 0 untouched");
            assertEquals(saved.get(2), perTab.get(2), "tab 2 untouched");
            assertEquals(saved.get(3), perTab.get(3), "tab 3 untouched");

            ScaleOptions tab1 = perTab.get(1);
            assertTrue(tab1.percentile() >= 50 && tab1.percentile() <= 100,
                    "tab 1's own corrupted percentile is repaired rather than thrown");
            assertEquals(saved.get(1).log(), tab1.log(), "tab 1's log/clip are unaffected");
            assertEquals(saved.get(1).clip(), tab1.clip());
        } finally {
            node.removeNode();
        }
    }

    /**
     * A record hand-built with the wrong number of tab entries is rejected outright, the same
     * "throw rather than migrate half-way" rule this codebase applies elsewhere to two things
     * that are supposed to describe the same set (e.g. {@code GateTree}/{@code PhenotypeSnapshot}
     * length mismatches) -- every reader of {@link AnalysisWindowPrefs#scaleOptionsByTab()}
     * indexes it positionally against a fixed four-tab list, so a shorter or longer one is not a
     * value this type can represent at all.
     */
    @Test
    void aWrongNumberOfTabEntriesIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new AnalysisWindowPrefs(0, 0, 960, 640, 0, "WHOLE_SLIDE",
                        List.of(ScaleOptions.LINEAR, ScaleOptions.LINEAR)));
    }

    /**
     * {@link AnalysisWindowPrefs#load} deliberately does NOT reach for {@code Screen.getPrimary()}
     * itself -- doing so would make this record's core parsing require a live JavaFX toolkit,
     * breaking the "testable with no toolkit" rule this whole class exists to satisfy. Instead
     * the screen check is a separate, pure function over a plain {@link Rectangle2D} the caller
     * supplies, so a monitor that is no longer attached is testable with a synthetic rectangle
     * exactly as it would be tested against a real one.
     */
    @Test
    void clampToScreenPullsAnOffScreenWindowBackOntoTheScreen() {
        AnalysisWindowPrefs savedOnDisconnectedMonitor =
                new AnalysisWindowPrefs(5000, 5000, 960, 640, 0, "WHOLE_SLIDE",
                        uniform(ScaleOptions.LINEAR));
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1080);

        AnalysisWindowPrefs clamped = savedOnDisconnectedMonitor.clampToScreen(primary);

        assertTrue(clamped.x() >= primary.getMinX() && clamped.x() + clamped.width() <= primary.getMaxX(),
                "x=" + clamped.x() + " width=" + clamped.width());
        assertTrue(clamped.y() >= primary.getMinY() && clamped.y() + clamped.height() <= primary.getMaxY(),
                "y=" + clamped.y() + " height=" + clamped.height());
    }

    /** A window already fully on screen is left alone -- clamping is a rescue, not a reset. */
    @Test
    void clampToScreenLeavesAnOnScreenWindowUntouched() {
        AnalysisWindowPrefs prefs =
                new AnalysisWindowPrefs(100, 150, 960, 640, 1, "WHOLE_SLIDE",
                        uniform(new ScaleOptions(true, false, 95)));
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1080);

        AnalysisWindowPrefs clamped = prefs.clampToScreen(primary);

        assertEquals(100, clamped.x(), 1e-9);
        assertEquals(150, clamped.y(), 1e-9);
        assertEquals(960, clamped.width(), 1e-9);
        assertEquals(640, clamped.height(), 1e-9);
        assertEquals(prefs.scaleOptionsByTab(), clamped.scaleOptionsByTab(),
                "clamping is geometry-only -- it must not touch the plot settings");
    }

    /** A saved window larger than the screen itself is shrunk to fit, not merely repositioned. */
    @Test
    void clampToScreenShrinksAWindowLargerThanTheScreen() {
        AnalysisWindowPrefs prefs =
                new AnalysisWindowPrefs(0, 0, 4000, 3000, 0, "WHOLE_SLIDE",
                        uniform(ScaleOptions.LINEAR));
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1080);

        AnalysisWindowPrefs clamped = prefs.clampToScreen(primary);

        assertTrue(clamped.width() <= primary.getWidth());
        assertTrue(clamped.height() <= primary.getHeight());
    }

    /**
     * {@link AnalysisWindowPrefs#defaults()} leaves x/y unset (NaN) rather than some arbitrary
     * on-screen coordinate: the very first run, before anything has ever been saved, should get
     * whatever placement the windowing system's own default gives a new {@code Stage}, not a
     * hard-coded corner that may not even be sensible on the user's monitor. {@code
     * AnalysisWindow} treats NaN as "do not call setX/setY at all" -- see its own javadoc.
     */
    @Test
    void defaultsLeaveGeometryUnsetForFirstRunPlacement() {
        AnalysisWindowPrefs d = AnalysisWindowPrefs.defaults();
        assertTrue(Double.isNaN(d.x()));
        assertTrue(Double.isNaN(d.y()));
        assertEquals(960, d.width(), 1e-9);
        assertEquals(640, d.height(), 1e-9);
        assertEquals(0, d.selectedTab());
        assertEquals("WHOLE_SLIDE", d.scope());
        assertEquals(AnalysisWindowPrefs.TAB_COUNT, d.scaleOptionsByTab().size());
        for (ScaleOptions options : d.scaleOptionsByTab()) {
            assertEquals(ScaleOptions.LINEAR, options, "every tab starts linear/unclipped");
        }
    }

    /** NaN geometry (the default, first-run case) must pass through clamping untouched. */
    @Test
    void clampToScreenLeavesUnsetGeometryAsNaN() {
        AnalysisWindowPrefs clamped =
                AnalysisWindowPrefs.defaults().clampToScreen(new Rectangle2D(0, 0, 1920, 1080));
        assertTrue(Double.isNaN(clamped.x()));
        assertTrue(Double.isNaN(clamped.y()));
    }
}
