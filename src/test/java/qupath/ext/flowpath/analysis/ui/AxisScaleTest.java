package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure arithmetic — no toolkit, no rows, no gating. */
class AxisScaleTest {

    private static final double[] SQUISHED = { 214003, 4120, 2011, 880, 310, 44, 3 };

    @Test
    void linearWithoutClippingKeepsTheLargestValueAtTheTop() {
        AxisScale s = AxisScale.of(SQUISHED, ScaleOptions.LINEAR);
        assertEquals(0, s.min(), 1e-9);
        assertEquals(214003, s.max(), 1e-9);
        assertFalse(s.anyClipped());
        assertEquals(1.0, s.toFraction(214003), 1e-9);
        // This is the squish the user is complaining about, stated as a number:
        assertTrue(s.toFraction(3) < 0.0001, "the small bars are invisible on a linear axis");
    }

    @Test
    void logScaleLiftsTheSmallBarsIntoView() {
        AxisScale s = AxisScale.of(SQUISHED, ScaleOptions.LINEAR.withLog(true));
        assertTrue(s.log());
        assertEquals(1, s.min(), 1e-9);
        assertTrue(s.toFraction(3) > 0.05, "the smallest bar is now visible");
        assertTrue(s.toFraction(44) > s.toFraction(3));
        assertTrue(s.toFraction(214003) <= 1.0);
        assertEquals(0, s.toFraction(0), 1e-9, "a zero count sits on the floor, not off the axis");
        assertEquals(0, s.toFraction(1), 1e-9);
    }

    @Test
    void percentileClipCapsTheAxisAndSaysSo() {
        AxisScale s = AxisScale.of(SQUISHED, ScaleOptions.LINEAR.withClip(true).withPercentile(75));
        assertTrue(s.max() < 214003, "the outlier no longer sets the axis");
        assertTrue(s.anyClipped(), "and the plot is told to mark it");
        assertTrue(s.isClipped(214003));
        assertFalse(s.isClipped(44));
        assertEquals(1.0, s.toFraction(214003), 1e-9, "a clipped bar draws to the top, not past it");
    }

    @Test
    void bothTogglesComposeRatherThanOverride() {
        // The user asked for either or both. Both means: log axis, percentile top.
        AxisScale s = AxisScale.of(SQUISHED,
                new ScaleOptions(true, true, 75));
        assertTrue(s.log());
        assertTrue(s.anyClipped());
        assertTrue(s.max() < 214003);
        assertTrue(s.toFraction(3) > 0.05, "still logarithmic");
    }

    @Test
    void clippingThatChangesNothingDrawsNoBreakMarker() {
        double[] flat = { 100, 100, 100, 100 };
        AxisScale s = AxisScale.of(flat, ScaleOptions.LINEAR.withClip(true).withPercentile(95));
        assertFalse(s.anyClipped(), "a uniform population has no outlier to mark");
        assertEquals(100, s.max(), 1e-9);
    }

    @Test
    void degenerateInputsDoNotDivideByZero() {
        for (ScaleOptions o : new ScaleOptions[] {
                ScaleOptions.LINEAR, ScaleOptions.LINEAR.withLog(true),
                ScaleOptions.LINEAR.withClip(true) }) {
            AxisScale empty = AxisScale.of(new double[0], o);
            assertTrue(empty.max() > empty.min(), "an empty plot still has a drawable axis");
            assertEquals(0, empty.toFraction(0), 1e-9);

            AxisScale zeros = AxisScale.of(new double[] { 0, 0, 0 }, o);
            assertTrue(zeros.max() > zeros.min());
            assertTrue(Double.isFinite(zeros.toFraction(0)));
        }
    }

    @Test
    void fractionsAreClampedNeverNegativeAndNeverPastOne() {
        AxisScale s = AxisScale.of(SQUISHED, ScaleOptions.LINEAR);
        assertEquals(0, s.toFraction(-500), 1e-9);
        assertEquals(1, s.toFraction(999999), 1e-9);
    }

    @Test
    void logTicksAreDecadesAndLinearTicksAreRound() {
        AxisScale log = AxisScale.of(SQUISHED, ScaleOptions.LINEAR.withLog(true));
        double[] t = log.ticks(4);
        assertEquals(1, t[0], 1e-9);
        assertTrue(t.length >= 3, "a five-decade range gets decade ticks");

        AxisScale linear = AxisScale.of(new double[] { 41733 }, ScaleOptions.LINEAR);
        for (double v : linear.ticks(4)) {
            assertEquals(v, Math.rint(v), 1e-9, "count ticks are whole numbers: " + v);
        }
    }

    @Test
    void tickLabelsGroupThousandsInUsLocale() {
        AxisScale s = AxisScale.of(SQUISHED, ScaleOptions.LINEAR);
        assertEquals("214,003", s.formatTick(214003));
        assertEquals("0", s.formatTick(0));
    }

    @Test
    void percentileMustBeInRange() {
        assertThrows(IllegalArgumentException.class, () -> new ScaleOptions(false, true, 49));
        assertThrows(IllegalArgumentException.class, () -> new ScaleOptions(false, true, 101));
        assertDoesNotThrow(() -> new ScaleOptions(false, true, 50));
        assertDoesNotThrow(() -> new ScaleOptions(false, true, 100));
    }

    /**
     * Regression for a bug caught in review, never in the wild: {@code anyClipped} must be
     * decided against the FINAL (possibly log-floored) {@code max}, not against the raw
     * percentile candidate computed before the floor is applied. {1..9} at p50 lands the
     * percentile on 5 (index = ceil(4.5) - 1 = 4, sorted[4] = 5) — a genuine, non-fallback clip,
     * since 0 < 5 < 9. Log mode then floors max from 5 up to 10. An implementation that decides
     * anyClipped inside the percentile branch, before that floor, would flag 6/7/8/9 as clipped
     * against the unfloored top of 5, while isClipped (which reads the floored max of 10) would
     * say none of them are — two methods on the same axis disagreeing about the same values.
     * Computing anyClipped once, last, against the same final max isClipped reads is what rules
     * that out. Do not "simplify" AxisScale.of by moving this scan back inside the percentile
     * branch — that is exactly the regression this test exists to catch.
     */
    @Test
    void clipAndLogAgreeAboutWhatWasClipped() {
        double[] values = { 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        AxisScale s = AxisScale.of(values, new ScaleOptions(true, true, 50));
        assertEquals(10, s.max(), 1e-9, "log floor lifts the genuine percentile of 5 up to 10");
        assertFalse(s.anyClipped(), "nothing in 1..9 exceeds the floored max of 10");
        for (double v : values) {
            assertFalse(s.isClipped(v), "isClipped and anyClipped must agree: " + v);
        }
    }
}
