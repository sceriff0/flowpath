package qupath.ext.flowpath.ui.editor;

import qupath.ext.flowpath.model.MeasuredColumn;

import java.util.Arrays;

/**
 * The arithmetic behind the gate editors' axes, sliders and typed thresholds, with no JavaFX in
 * it: which window a slider travels, which window a plot shows, where a threshold lands when
 * its axis moves to a different column, and how a typed number is read.
 * <p>
 * The editors only put these answers on screen. Keeping them here means each rule has one
 * spelling and is table-tested without a toolkit.
 */
public final class AxisMath {

    private AxisMath() {}

    /**
     * The default axis window a threshold or quadrant slider starts from before real column
     * statistics (clip percentiles) narrow it — a holdover from the retired z-score space,
     * where {@code [-5, 5]} covered essentially every cell. {@link #quadrantSliderSpan} falls
     * back to this same window when there is no clip-derived one to widen instead.
     */
    public static final double DEFAULT_AXIS_LO = -5;
    public static final double DEFAULT_AXIS_HI = 5;

    /**
     * One axis' visible window: {@code col}'s clip percentiles, or {@code null} when they do
     * not make a usable range. The scatter plot's axes and the quadrant editor's slider travel
     * both come from here, so what the slider spans is what the plot shows.
     *
     * @param lowPct  the gate's low clip percentile, in {@code [0, 100]}
     * @param highPct the gate's high clip percentile, in {@code [0, 100]}
     */
    public static double[] clipSpan(MeasuredColumn col, double lowPct, double highPct) {
        if (col == null) return null;
        double lo = col.percentile(lowPct);
        double hi = col.percentile(highPct);
        if (Double.isNaN(lo) || Double.isNaN(hi) || !(hi > lo)) return null;
        return new double[]{lo, hi};
    }

    /**
     * The travel for a quadrant threshold slider: the axis' visible window, widened just
     * enough to contain the gate's current threshold so the thumb never lies about it by
     * pinning to an end. Falls back to {@code [-5, 5]} (the default window the threshold
     * editor also starts from) when there is no window.
     */
    public static double[] quadrantSliderSpan(double[] window, double threshold) {
        double lo = window != null ? window[0] : DEFAULT_AXIS_LO;
        double hi = window != null ? window[1] : DEFAULT_AXIS_HI;
        if (Double.isFinite(threshold)) {
            lo = Math.min(lo, threshold);
            hi = Math.max(hi, threshold);
        }
        if (!(hi > lo)) hi = lo + 1;
        return new double[]{lo, hi};
    }

    /**
     * The threshold editor's window — the histogram's clip range and the threshold slider's
     * travel, which are the same range.
     * <p>
     * Anchored on the column's global clip percentiles ({@code clipLo}/{@code clipHi}), so one
     * channel+compartment+statistic uses one axis everywhere it appears in the gate tree.
     * Only when those are unusable (NaN, or a column constant over the whole population) does
     * it fall back to the extremes of the values actually on display, and to {@code [0, 1]}
     * when there are none.
     *
     * @param displayValues the values the histogram shows (already masked, NaN-free)
     */
    public static double[] thresholdWindow(double clipLo, double clipHi, double[] displayValues) {
        boolean badGlobal = Double.isNaN(clipLo) || Double.isNaN(clipHi) || !(clipHi > clipLo);
        if (!badGlobal) return new double[]{clipLo, clipHi};
        if (displayValues != null && displayValues.length > 0) {
            double dataMin = percentileOf(displayValues, 0);
            double dataMax = percentileOf(displayValues, 100);
            return new double[]{dataMin, dataMax > dataMin ? dataMax : dataMin + 1};
        }
        return new double[]{0, 1};
    }

    /**
     * The values of {@code all} a plot of the current population shows: cells passing both
     * masks (a {@code null} mask passes everything) with a measured, non-NaN value, so no
     * percentile or clip computed from them can come out NaN. Returns {@code all} itself when
     * nothing is filtered out.
     */
    public static double[] measuredValues(double[] all, boolean[] roiMask, boolean[] ancestorMask) {
        int count = 0;
        for (int i = 0; i < all.length; i++) {
            if (passes(i, roiMask, ancestorMask) && !Double.isNaN(all[i])) count++;
        }
        if (count == all.length) return all;
        double[] out = new double[count];
        int j = 0;
        for (int i = 0; i < all.length; i++) {
            if (passes(i, roiMask, ancestorMask) && !Double.isNaN(all[i])) out[j++] = all[i];
        }
        return out;
    }

    /** Whether cell {@code i} passes both masks; a {@code null} mask passes everything. */
    public static boolean passes(int i, boolean[] roiMask, boolean[] ancestorMask) {
        if (roiMask != null && !roiMask[i]) return false;
        return ancestorMask == null || ancestorMask[i];
    }

    /**
     * {@code allX}/{@code allY} filtered together by both masks, in lockstep -- a 2D
     * scatter's two axes, which must keep the same cell at the same position in each output
     * array. Unlike {@link #measuredValues}, NaN is not dropped: a scatter draws (or skips)
     * a NaN point on its own, and dropping it independently per axis here would desynchronise
     * the pair. Returns {@code {allX, allY}} themselves, unfiltered, when both masks are
     * {@code null} -- the common case, and one allocation avoided on every redraw.
     */
    public static double[][] pairedMaskedValues(double[] allX, double[] allY,
                                                boolean[] roiMask, boolean[] ancestorMask) {
        if (roiMask == null && ancestorMask == null) return new double[][]{allX, allY};
        int count = 0;
        for (int i = 0; i < allX.length; i++) if (passes(i, roiMask, ancestorMask)) count++;
        double[] fx = new double[count];
        double[] fy = new double[count];
        int j = 0;
        for (int i = 0; i < allX.length; i++) {
            if (passes(i, roiMask, ancestorMask)) {
                fx[j] = allX[i];
                fy[j] = allY[i];
                j++;
            }
        }
        return new double[][]{fx, fy};
    }

    /**
     * A raw threshold remapped to the same percentile of {@code newCol}, so a
     * compartment/statistic switch keeps the gate splitting the population the same way
     * instead of leaving a number that means nothing in the new column (a Sum is ~100x the
     * corresponding Mean). Returns {@code value} unchanged when the column is unchanged or the
     * remap is not possible.
     */
    public static double remapRawThreshold(MeasuredColumn oldCol, MeasuredColumn newCol, double value) {
        if (oldCol == null || newCol == null || newCol.key().equals(oldCol.key())) return value;
        double pct = oldCol.percentileRankOf(value);
        if (Double.isNaN(pct)) return value;
        double mapped = newCol.percentile(pct);
        return Double.isNaN(mapped) ? value : mapped;
    }

    /**
     * Parse a typed threshold. The fields render with {@link java.util.Locale#US} so that
     * {@link Double#parseDouble} — which only accepts {@code '.'} — can read it back, but a
     * user on a comma-decimal locale will naturally type {@code "0,33"}, so accept that too. No
     * thousands separator is ever emitted, making the swap safe.
     *
     * @throws NumberFormatException when the text is not a number
     */
    public static double parseThreshold(String text) {
        if (text == null) throw new NumberFormatException("null");
        return Double.parseDouble(text.trim().replace(',', '.'));
    }

    /**
     * Linear-interpolated percentile of an array (NaNs ignored). Returns NaN for an
     * empty/all-NaN input so a caller's fallback engages.
     *
     * @param pct percentile in [0,100]
     */
    static double percentileOf(double[] values, double pct) {
        if (values == null || values.length == 0) return Double.NaN;
        double[] sorted = new double[values.length];
        int n = 0;
        for (double v : values) if (!Double.isNaN(v)) sorted[n++] = v;
        if (n == 0) return Double.NaN;
        sorted = Arrays.copyOf(sorted, n);
        Arrays.sort(sorted);
        if (n == 1) return sorted[0];
        double rank = (pct / 100.0) * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double frac = rank - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }
}
