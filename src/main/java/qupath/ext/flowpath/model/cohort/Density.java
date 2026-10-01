package qupath.ext.flowpath.model.cohort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A Gaussian kernel density on a fixed grid spanning the data, in log units. Binned (grid ×
 * kernel reach, not cells × grid) so "every cell" on a million-cell slide stays cheap, and
 * reflected at both data edges so a pile-up at the lowest value is not mistaken for a peak.
 */
public final class Density {

    public static final int GRID = 512;
    public static final int MIN_VALUES = 50;
    /** A peak counts when its topographic prominence is at least this fraction of the tallest density. */
    public static final double MIN_PROMINENCE = 0.05;

    public record Peak(double position, double height, double prominence) {}

    public static final Density EMPTY = new Density(0, 0, 1, new double[0], List.of(), 0);

    private final double lo;
    private final double step;
    private final double bandwidth;
    private final double[] density;
    private final List<Peak> peaks;
    private final double max;

    private Density(double lo, double step, double bandwidth, double[] density, List<Peak> peaks, double max) {
        this.lo = lo;
        this.step = step;
        this.bandwidth = bandwidth;
        this.density = density;
        this.peaks = peaks;
        this.max = max;
    }

    public static Density of(double[] u) {
        double[] finite = Arrays.stream(u).filter(Double::isFinite).toArray();
        int n = finite.length;
        if (n < MIN_VALUES) return EMPTY;
        Arrays.sort(finite);
        double lo = finite[0];
        double hi = finite[n - 1];
        if (!(hi > lo)) return EMPTY;

        double mean = 0;
        for (double v : finite) mean += v;
        mean /= n;
        double var = 0;
        for (double v : finite) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / (n - 1));
        double iqr = finite[(int) (0.75 * (n - 1))] - finite[(int) (0.25 * (n - 1))];
        double spread = iqr > 0 ? Math.min(sd, iqr / 1.34) : sd;
        double h = 0.9 * spread * Math.pow(n, -0.2);          // Silverman
        if (!(h > 0)) return EMPTY;

        double step = (hi - lo) / (GRID - 1);
        double[] counts = new double[GRID];
        for (double v : finite) counts[(int) Math.round((v - lo) / step)]++;

        int reach = (int) Math.ceil(4 * h / step);
        int last = GRID - 1;
        double[] d = new double[GRID];
        for (int j = 0; j < GRID; j++) {
            double c = counts[j];
            if (c == 0) continue;
            for (int i = Math.max(0, j - reach); i <= Math.min(last, j + reach); i++) d[i] += c * kernel((i - j) * step / h);
            for (int i = 0; i <= Math.min(last, reach - j); i++) d[i] += c * kernel((i + j) * step / h);
            for (int i = Math.max(0, 2 * last - j - reach); i <= last; i++) d[i] += c * kernel((2 * last - j - i) * step / h);
        }
        double norm = n * h * Math.sqrt(2 * Math.PI);
        double max = 0;
        for (int i = 0; i < GRID; i++) {
            d[i] /= norm;
            max = Math.max(max, d[i]);
        }
        return new Density(lo, step, h, d, findPeaks(d, lo, step, max), max);
    }

    private static double kernel(double z) {
        return Math.exp(-0.5 * z * z);
    }

    private static List<Peak> findPeaks(double[] d, double lo, double step, double max) {
        List<Peak> out = new ArrayList<>();
        for (int i = 1; i < d.length - 1; i++) {
            if (!(d[i] > d[i - 1] && d[i] >= d[i + 1])) continue;
            double leftMin = d[i];
            for (int k = i - 1; k >= 0 && d[k] <= d[i]; k--) leftMin = Math.min(leftMin, d[k]);
            double rightMin = d[i];
            for (int k = i + 1; k < d.length && d[k] <= d[i]; k++) rightMin = Math.min(rightMin, d[k]);
            double prominence = d[i] - Math.max(leftMin, rightMin);
            if (prominence >= MIN_PROMINENCE * max) out.add(new Peak(lo + i * step, d[i], prominence));
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isEmpty() {
        return density.length == 0;
    }

    /** Linearly interpolated density at {@code u}; 0 outside the data's range or when empty. */
    public double at(double u) {
        if (isEmpty() || !Double.isFinite(u)) return 0;
        double pos = (u - lo) / step;
        if (pos < 0 || pos > GRID - 1) return 0;
        int i = Math.min(GRID - 2, (int) Math.floor(pos));
        double f = pos - i;
        return density[i] * (1 - f) + density[i + 1] * f;
    }

    public double bandwidth() {
        return bandwidth;
    }

    public double max() {
        return max;
    }

    public List<Peak> peaks() {
        return peaks;
    }

    public Peak nearestPeak(double u) {
        Peak best = null;
        for (Peak p : peaks) {
            if (best == null || Math.abs(p.position() - u) < Math.abs(best.position() - u)) best = p;
        }
        return best;
    }
}
