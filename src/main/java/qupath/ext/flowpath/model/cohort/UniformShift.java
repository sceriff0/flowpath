package qupath.ext.flowpath.model.cohort;

import java.util.List;

/**
 * UniFORM's feature-level automatic registration, reproduced exactly (Wang et al. 2025, Cell Rep
 * Methods, PMC12539234 [FULL: main text + STAR Methods]; code kunlunW/UniFORM @ c750a9a [FULL]):
 * a raw-count histogram of {@link #BINS} bins over the column's global log range across every
 * slide (preprocessing.py:314-358, numpy's np.histogram binning), the integer shift
 * {@code argmax(correlate(slide, ref, 'full')) - (BINS - 1)} (registration.py:150-180), and the
 * log shift {@code shift * (max - min) / (BINS - 1)} (normalization.py:258-266) — note UniFORM's
 * increment divides by BINS - 1 while its histogram's bins are (max - min) / BINS wide; both are
 * kept as UniFORM has them. Exact integer correlation; ties go to the first maximum, as numpy's
 * argmax (UniFORM's FFT path can differ only on an exact tie — spec departure 3).
 */
public final class UniformShift {

    public static final int BINS = 1024;

    public record Grid(double min, double max) {
        public boolean usable() {
            return Double.isFinite(min) && Double.isFinite(max) && max > min;
        }

        /** UniFORM's increment: one bin of shift in log units. */
        public double binWidth() {
            return (max - min) / (BINS - 1);
        }
    }

    private UniformShift() {}

    /** The global min and max of every finite value across {@code logValues}. */
    public static Grid grid(List<double[]> logValues) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double[] v : logValues) {
            for (double u : v) {
                if (!Double.isFinite(u)) continue;
                if (u < min) min = u;
                if (u > max) max = u;
            }
        }
        return new Grid(min, max);
    }

    /** numpy's np.histogram(values, bins=BINS, range=(min, max)) for finite values in range; NaN skipped. */
    public static long[] histogram(double[] logValues, Grid g) {
        long[] counts = new long[BINS];
        if (!g.usable()) return counts;
        double[] edges = edges(g);
        for (double u : logValues) {
            if (!Double.isFinite(u) || u < g.min() || u > g.max()) continue;
            counts[index(u, g, edges)]++;
        }
        return counts;
    }

    /** The histogram bin {@code u} falls in, clamped to [0, BINS - 1]: the bin a picked landmark names. */
    public static int binOf(double u, Grid g) {
        if (!g.usable() || !Double.isFinite(u)) return 0;
        if (u <= g.min()) return 0;
        if (u >= g.max()) return BINS - 1;
        return index(u, g, edges(g));
    }

    /** {@code argmax_k corr[k] - (BINS - 1)}, corr = scipy.signal.correlate(slide, ref, 'full'). */
    public static int shiftBins(long[] slide, long[] ref) {
        int n = BINS;
        long best = Long.MIN_VALUE;
        int bestK = 0;
        for (int k = 0; k < 2 * n - 1; k++) {
            int lag = k - (n - 1);                 // z[k] = sum_l slide[l] * ref[l - lag]
            long sum = 0;
            int lo = Math.max(0, lag), hi = Math.min(n - 1, n - 1 + lag);
            for (int l = lo; l <= hi; l++) sum += slide[l] * ref[l - lag];
            if (sum > best) { best = sum; bestK = k; }
        }
        return bestK - (n - 1);
    }

    public static double logShift(int shiftBins, Grid g) {
        return shiftBins * g.binWidth();
    }

    /** np.linspace(min, max, BINS + 1), last edge exactly max. */
    private static double[] edges(Grid g) {
        double[] e = new double[BINS + 1];
        double step = (g.max() - g.min()) / BINS;
        for (int i = 0; i <= BINS; i++) e[i] = g.min() + i * step;
        e[BINS] = g.max();
        return e;
    }

    /** numpy's uniform-bin index with its edge corrections (numpy/lib/histograms.py, _search_sorted path). */
    private static int index(double u, Grid g, double[] edges) {
        double norm = BINS / (g.max() - g.min());
        int i = (int) ((u - g.min()) * norm);
        if (i == BINS) i--;
        if (u < edges[i]) i--;
        else if (i != BINS - 1 && u >= edges[i + 1]) i++;
        return Math.max(0, Math.min(BINS - 1, i));
    }
}
