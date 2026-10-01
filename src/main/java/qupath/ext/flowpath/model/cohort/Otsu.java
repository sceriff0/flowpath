package qupath.ext.flowpath.model.cohort;

import java.util.Arrays;

/**
 * scikit-image's threshold_otsu (nbins = 256 over the data's own range, bin centres) and the Otsu
 * discordance of Harris et al. 2022 (Bioinformatics, PMC8896603 [FULL]): the fraction of one
 * slide's cells on which its own Otsu threshold and the pooled cohort's disagree. FlowPath computes
 * both on the corrected log values (a FlowPath choice; Harris's scale per method is not restated).
 */
public final class Otsu {

    public static final int BINS = 256;

    private Otsu() {}

    /** NaN when there are no finite values; the value itself when they are all equal. */
    public static double threshold(double[] values) {
        double[] v = Arrays.stream(values).filter(Double::isFinite).toArray();
        if (v.length == 0) return Double.NaN;
        double min = Arrays.stream(v).min().getAsDouble(), max = Arrays.stream(v).max().getAsDouble();
        if (!(max > min)) return min;
        double step = (max - min) / BINS;
        long[] hist = new long[BINS];
        for (double x : v) {
            int i = (int) ((x - min) / step);
            hist[Math.min(BINS - 1, Math.max(0, i))]++;
        }
        double[] centre = new double[BINS];
        for (int i = 0; i < BINS; i++) centre[i] = min + (i + 0.5) * step;
        double[] w1 = new double[BINS], m1 = new double[BINS], w2 = new double[BINS], m2 = new double[BINS];
        double cw = 0, cm = 0;
        for (int i = 0; i < BINS; i++) { cw += hist[i]; cm += hist[i] * centre[i]; w1[i] = cw; m1[i] = cm / cw; }
        cw = 0; cm = 0;
        for (int i = BINS - 1; i >= 0; i--) { cw += hist[i]; cm += hist[i] * centre[i]; w2[i] = cw; m2[i] = cm / cw; }
        double best = Double.NEGATIVE_INFINITY;
        int idx = 0;
        for (int i = 0; i < BINS - 1; i++) {
            if (w1[i] == 0 || w2[i + 1] == 0) continue;     // empty side: m is NaN, variance undefined
            double var = w1[i] * w2[i + 1] * Math.pow(m1[i] - m2[i + 1], 2);
            if (var > best) { best = var; idx = i; }       // first maximum, as np.argmax
        }
        return centre[idx];
    }

    public static double discordance(double[] slide, double slideThreshold, double pooledThreshold) {
        int n = 0, differ = 0;
        for (double x : slide) {
            if (!Double.isFinite(x)) continue;
            n++;
            if ((x >= slideThreshold) != (x >= pooledThreshold)) differ++;
        }
        return n == 0 ? 0.0 : differ / (double) n;
    }
}
