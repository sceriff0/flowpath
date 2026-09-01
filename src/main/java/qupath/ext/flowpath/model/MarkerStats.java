package qupath.ext.flowpath.model;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MarkerStats {

    private static final int HISTOGRAM_BINS = 200;

    // Keyed by the *resolved* measurement key: a base marker name ("CD3") for
    // whole-cell/mean, or a compartment key ("CD3: Nucleus: Mean") otherwise.
    // ConcurrentHashMap so columns can be registered lazily from the background
    // live-preview thread (see ensureColumn).
    private final Map<String, double[]> sortedValues = new ConcurrentHashMap<>();
    private final Map<String, Double> means = new ConcurrentHashMap<>();
    private final Map<String, Double> stds = new ConcurrentHashMap<>();
    private final Map<String, Double> mins = new ConcurrentHashMap<>();
    private final Map<String, Double> maxs = new ConcurrentHashMap<>();
    private final Map<String, double[]> histogramBins = new ConcurrentHashMap<>();
    private final Map<String, double[]> histogramCounts = new ConcurrentHashMap<>();

    // Quality mask captured at compute time, so lazily-registered compartment
    // columns are summarised over the same cell population as the base markers.
    private boolean[] qualityMask;

    private MarkerStats() {}

    /**
     * Compute statistics over every cell, with no quality mask applied.
     * Equivalent to {@link #compute(CellIndex, boolean[])} with a null mask — used by
     * the UMAP view, which scales features over the whole indexed population.
     */
    public static MarkerStats compute(CellIndex index) {
        return compute(index, null);
    }

    public static MarkerStats compute(CellIndex index, boolean[] qualityMask) {
        MarkerStats s = new MarkerStats();
        s.qualityMask = qualityMask;

        String[] markers = index.getMarkerNames();
        for (int m = 0; m < markers.length; m++) {
            s.putColumnStats(markers[m], index.getMarkerValues(m), qualityMask);
        }
        return s;
    }

    /**
     * Compute and store summary statistics (sorted values, mean, std, min/max,
     * histogram) for a single column under {@code name}.
     * <p>
     * Everything is computed before anything is published, and {@link #means} — the
     * map {@link #hasColumn} tests — is written <em>last</em>. Columns are registered
     * lazily from both the FX thread (the gate editor, drawing a histogram for a
     * compartment selection) and the background gating thread, so a reader that sees
     * {@code hasColumn(name)} must see a fully populated column, not a half-written one.
     */
    private void putColumnStats(String name, double[] raw, boolean[] mask) {
        int n = raw.length;

        int actualCount = 0;
        for (int i = 0; i < n; i++) {
            if ((mask == null || mask[i]) && !Double.isNaN(raw[i])) actualCount++;
        }
        double[] passing = new double[actualCount];
        int idx = 0;
        for (int i = 0; i < n; i++) {
            if ((mask == null || mask[i]) && !Double.isNaN(raw[i])) {
                passing[idx++] = raw[i];
            }
        }

        Arrays.sort(passing);

        if (actualCount == 0) {
            sortedValues.put(name, passing);
            stds.put(name, 0.0);
            mins.put(name, 0.0);
            maxs.put(name, 0.0);
            histogramBins.put(name, new double[HISTOGRAM_BINS + 1]);
            histogramCounts.put(name, new double[HISTOGRAM_BINS]);
            means.put(name, 0.0);   // published last: see javadoc
            return;
        }

        double min = passing[0];
        double max = passing[actualCount - 1];

        double sum = 0;
        for (double v : passing) sum += v;
        double mean = sum / actualCount;

        double sumSq = 0;
        for (double v : passing) sumSq += (v - mean) * (v - mean);
        double std = Math.sqrt(sumSq / actualCount);

        // Histogram
        double[] bins = new double[HISTOGRAM_BINS + 1];
        double[] counts = new double[HISTOGRAM_BINS];
        double range = max - min;
        if (range < 1e-10) range = 1.0;
        double binWidth = range / HISTOGRAM_BINS;

        for (int b = 0; b <= HISTOGRAM_BINS; b++) {
            bins[b] = min + b * binWidth;
        }
        for (double v : passing) {
            int bin = (int) ((v - min) / binWidth);
            if (bin >= HISTOGRAM_BINS) bin = HISTOGRAM_BINS - 1;
            if (bin < 0) bin = 0;
            counts[bin]++;
        }

        sortedValues.put(name, passing);
        mins.put(name, min);
        maxs.put(name, max);
        stds.put(name, std);
        histogramBins.put(name, bins);
        histogramCounts.put(name, counts);
        means.put(name, mean);      // published last: see javadoc
    }

    /**
     * Ensure statistics exist for a resolved compartment column, computed with the
     * same quality mask used at {@link #compute}. Idempotent and safe to call from
     * the gating pre-pass on any thread. No-op for keys already present (including
     * the base markers).
     */
    public void ensureColumn(String key, double[] rawColumn) {
        if (key == null || rawColumn == null) return;
        if (means.containsKey(key)) return;
        putColumnStats(key, rawColumn, qualityMask);
    }

    /** True if statistics for the given resolved key are available. */
    public boolean hasColumn(String key) {
        return means.containsKey(key);
    }

    public double toZScore(String channel, double rawValue) {
        double mean = means.getOrDefault(channel, 0.0);
        double std = stds.getOrDefault(channel, 0.0);
        if (std < 1e-10) return 0.0;
        return (rawValue - mean) / std;
    }

    public double fromZScore(String channel, double zScore) {
        double mean = means.getOrDefault(channel, 0.0);
        double std = stds.getOrDefault(channel, 0.0);
        return zScore * std + mean;
    }

    public double getPercentileValue(String channel, double percentile) {
        double[] sorted = sortedValues.get(channel);
        if (sorted == null || sorted.length == 0) return Double.NaN;
        // Clamp the *percentile*, not the two indices it produces. The old code clamped
        // `lo` only from below and `hi` only from above, which covers the two ends that
        // cannot go out of range and neither of the two that can: a percentile above 100
        // leaves `lo` past the end of the array, and one below 0 leaves `hi` negative.
        // The UI spinners are bounded, but FlowPathSerializer reads clipPercentileLow/High
        // straight out of the JSON with no range check, so a hand-edited or third-party
        // .flowpath file reached this with, say, 150 and threw inside ResolvedGate's
        // compile step -- where LivePreviewService swallows it and the view just stops
        // updating. Clamping once, up front, makes every downstream index in range by
        // construction.
        double p = Double.isNaN(percentile) ? 0.0 : Math.clamp(percentile, 0.0, 100.0);
        double idx = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        double frac = idx - lo;
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }

    /**
     * Percentile rank (0-100) of {@code value} within the column stored under
     * {@code channel}, by linear interpolation over the sorted values. The inverse
     * of {@link #getPercentileValue}: {@code percentileRankOf(k, getPercentileValue(k, p)) == p}.
     * <p>
     * Used to carry a raw threshold across a compartment/statistic switch: a raw
     * number means nothing in the new column (a Sum is ~100x a Mean), but its
     * percentile does, so the gate keeps splitting the population the same way.
     *
     * @return the rank in [0,100], or NaN if the column is unknown or empty
     */
    public double percentileRankOf(String channel, double value) {
        double[] sorted = sortedValues.get(channel);
        if (sorted == null || sorted.length == 0 || Double.isNaN(value)) return Double.NaN;
        if (sorted.length == 1) return 50.0;
        if (value <= sorted[0]) return 0.0;
        if (value >= sorted[sorted.length - 1]) return 100.0;
        // Last index whose value is <= the query.
        int lo = 0, hi = sorted.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (sorted[mid] <= value) lo = mid; else hi = mid - 1;
        }
        double idx = lo;
        if (lo < sorted.length - 1 && sorted[lo + 1] > sorted[lo]) {
            idx += (value - sorted[lo]) / (sorted[lo + 1] - sorted[lo]);
        }
        return 100.0 * idx / (sorted.length - 1);
    }

    public double[] getHistogramBins(String channel) {
        return histogramBins.get(channel);
    }

    public double[] getHistogramCounts(String channel) {
        return histogramCounts.get(channel);
    }

    public double getMean(String channel) {
        return means.getOrDefault(channel, 0.0);
    }

    public double getStd(String channel) {
        return stds.getOrDefault(channel, 0.0);
    }

    public double getMin(String channel) {
        return mins.getOrDefault(channel, 0.0);
    }

    public double getMax(String channel) {
        return maxs.getOrDefault(channel, 0.0);
    }

    public Set<String> getMarkerNames() {
        return means.keySet();
    }
}
