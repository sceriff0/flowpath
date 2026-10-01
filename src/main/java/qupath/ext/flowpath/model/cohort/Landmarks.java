package qupath.ext.flowpath.model.cohort;

import java.util.List;

/**
 * The detector's landmarks on one slide x column, in {@link LogScale} units: L1 the lowest-intensity
 * prominent density peak (the negative population — not the mode, which on a tumour-rich slide is
 * the positive peak), L2 the highest prominent peak at least two bandwidths above L1. NaN when
 * absent. The detector belongs to the problem layer (spec U5): it never moves a threshold, except
 * as the reference's landmark in UniFORM's landmark mode when no peak was picked (spec departure 4).
 */
public record Landmarks(LogScale scale, double l1, double l2) {

    /** Minimum L1 to L2 distance, in density bandwidths, for L2 to count as a separate population. */
    public static final double MIN_SEPARATION_BANDWIDTHS = 2.0;

    public boolean hasL1() { return Double.isFinite(l1); }

    public boolean hasL2() { return hasL1() && Double.isFinite(l2); }

    /** 0, 1 or 2: what reference eligibility compares with the cohort's modal count. */
    public int count() { return hasL2() ? 2 : hasL1() ? 1 : 0; }

    public static Landmarks none(LogScale scale) { return new Landmarks(scale, Double.NaN, Double.NaN); }

    public static Landmarks find(double[] raw, boolean[] mask, LogScale scale) {
        Density density = Density.of(toLog(raw, mask, scale));
        List<Density.Peak> peaks = density.peaks();
        if (peaks.isEmpty()) return none(scale);
        double l1 = peaks.get(0).position();
        double l2 = Double.NaN;
        for (int i = peaks.size() - 1; i > 0; i--) {
            double p = peaks.get(i).position();
            if (p - l1 >= MIN_SEPARATION_BANDWIDTHS * density.bandwidth()) { l2 = p; break; }
        }
        return new Landmarks(scale, l1, l2);
    }

    /** scale.toLog(raw) for every cell in {@code mask} (all when null); NaN elsewhere. */
    public static double[] toLog(double[] raw, boolean[] mask, LogScale scale) {
        double[] u = new double[raw.length];
        for (int i = 0; i < raw.length; i++) u[i] = (mask == null || mask[i]) ? scale.toLog(raw[i]) : Double.NaN;
        return u;
    }
}
