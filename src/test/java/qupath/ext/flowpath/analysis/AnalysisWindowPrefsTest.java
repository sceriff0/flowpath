package qupath.ext.flowpath.analysis;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** Preferences only — no Stage, no toolkit. */
class AnalysisWindowPrefsTest {

    private static Preferences scratch() {
        return Preferences.userRoot().node("flowpath-test/" + java.util.UUID.randomUUID());
    }

    @Test
    void roundTripsEverySetting() throws Exception {
        Preferences node = scratch();
        try {
            new AnalysisWindowPrefs(100, 200, 1200, 800, 2, "ANNOTATION_K", true, true, 90).save(node);
            AnalysisWindowPrefs read = AnalysisWindowPrefs.load(node);
            assertEquals(1200, read.width(), 1e-9);
            assertEquals(2, read.selectedTab());
            assertEquals("ANNOTATION_K", read.scope());
            assertTrue(read.log());
            assertTrue(read.clip());
            assertEquals(90, read.percentile(), 1e-9);
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
        node.put("percentile", "999");
        AnalysisWindowPrefs read = assertDoesNotThrow(() -> AnalysisWindowPrefs.load(node));
        assertEquals(AnalysisWindowPrefs.defaults().width(), read.width(), 1e-9);
        assertTrue(read.percentile() >= 50 && read.percentile() <= 100,
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
        node.putDouble("percentile", -12.0);
        AnalysisWindowPrefs read = assertDoesNotThrow(() -> AnalysisWindowPrefs.load(node));
        assertTrue(read.percentile() >= 50 && read.percentile() <= 100);
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
                new AnalysisWindowPrefs(5000, 5000, 960, 640, 0, "WHOLE_SLIDE", false, false, 95);
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
                new AnalysisWindowPrefs(100, 150, 960, 640, 1, "WHOLE_SLIDE", true, false, 95);
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1080);

        AnalysisWindowPrefs clamped = prefs.clampToScreen(primary);

        assertEquals(100, clamped.x(), 1e-9);
        assertEquals(150, clamped.y(), 1e-9);
        assertEquals(960, clamped.width(), 1e-9);
        assertEquals(640, clamped.height(), 1e-9);
    }

    /** A saved window larger than the screen itself is shrunk to fit, not merely repositioned. */
    @Test
    void clampToScreenShrinksAWindowLargerThanTheScreen() {
        AnalysisWindowPrefs prefs =
                new AnalysisWindowPrefs(0, 0, 4000, 3000, 0, "WHOLE_SLIDE", false, false, 95);
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
        assertFalse(d.log());
        assertFalse(d.clip());
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
