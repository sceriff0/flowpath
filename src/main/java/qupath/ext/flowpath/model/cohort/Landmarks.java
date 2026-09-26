package qupath.ext.flowpath.model.cohort;

import java.util.Arrays;
import java.util.List;

/**
 * One column's landmarks on one slide, in asinh(x / c) units: L1 the lowest-intensity prominent
 * peak (the negative population — NOT the mode, which on a tumour-rich slide is the positive
 * peak), L2 the highest prominent peak at least two bandwidths above L1. NaN when absent.
 */
public record Landmarks(double cofactor, double l1, double l2) {

    /** Minimum L1→L2 distance, in density bandwidths, for L2 to count as a separate population. */
    public static final double MIN_SEPARATION_BANDWIDTHS = 2.0;

    public boolean hasL1() {
        return Double.isFinite(l1);
    }

    public boolean hasL2() {
        return hasL1() && Double.isFinite(l2);
    }

    public static Landmarks none(double cofactor) {
        return new Landmarks(cofactor, Double.NaN, Double.NaN);
    }

    public static Landmarks find(double[] raw, boolean[] mask, double cofactor) {
        Density density = Density.of(toAsinh(raw, mask, cofactor));
        List<Density.Peak> peaks = density.peaks();
        if (peaks.isEmpty()) return none(cofactor);
        double l1 = peaks.get(0).position();
        double l2 = Double.NaN;
        for (int i = peaks.size() - 1; i > 0; i--) {
            double p = peaks.get(i).position();
            if (p - l1 >= MIN_SEPARATION_BANDWIDTHS * density.bandwidth()) {
                l2 = p;
                break;
            }
        }
        return new Landmarks(cofactor, l1, l2);
    }

    /** asinh(raw / c) for every cell in {@code mask} (all when null); NaN elsewhere and for NaN input. */
    public static double[] toAsinh(double[] raw, boolean[] mask, double cofactor) {
        double[] u = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            u[i] = (mask == null || mask[i]) ? asinh(raw[i], cofactor) : Double.NaN;
        }
        return u;
    }

    /** The pooled median of |x| over finite non-zero values; 1.0 when there are none. */
    public static double cofactor(List<double[]> pooledRaw) {
        double[] all = pooledRaw.stream().flatMapToDouble(Arrays::stream)
                .filter(v -> Double.isFinite(v) && v != 0).map(Math::abs).sorted().toArray();
        if (all.length == 0) return 1.0;
        int m = all.length / 2;
        return all.length % 2 == 1 ? all[m] : (all[m - 1] + all[m]) / 2;
    }

    public static double asinh(double raw, double cofactor) {
        double x = raw / cofactor;
        if (!Double.isFinite(x)) return x;
        return Math.signum(x) * Math.log(Math.abs(x) + Math.sqrt(x * x + 1));
    }

    public static double sinh(double u, double cofactor) {
        return cofactor * Math.sinh(u);
    }
}
