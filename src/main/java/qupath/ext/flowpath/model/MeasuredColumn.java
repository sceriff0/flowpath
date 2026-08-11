package qupath.ext.flowpath.model;

/**
 * One resolved measurement column, with its statistics guaranteed to exist.
 * <p>
 * This is the handle every consumer should hold instead of a {@code String} key.
 * Reading a per-compartment statistic used to be a four-step protocol — resolve the
 * key, materialise the column, register it with {@link MarkerStats}, then read the
 * statistic under that key — and <b>skipping the registration step did not throw</b>:
 * {@link MarkerStats#toZScore} falls back to {@code getOrDefault(key, 0.0)}, so an
 * unregistered column silently reports every cell as sitting exactly at the mean, and
 * {@link MarkerStats#getPercentileValue} silently returns NaN so outlier clipping
 * no-ops. Both are plausible-looking wrong answers that feed straight into gate
 * comparisons; release 2.0.1 shipped five separate fixes for that one class of defect.
 * <p>
 * A {@code MeasuredColumn} can only be obtained from
 * {@link CellIndex#column(String, Compartment, Statistic, MarkerStats)} (or the gate-axis
 * overload), which performs all four steps. Holding one is therefore proof that the
 * statistics below describe the column the values came from.
 * <p>
 * <b>Not a copy.</b> {@link #values()} returns {@code CellIndex}'s backing array, in
 * keeping with {@link CellIndex#getMarkerValues(int)} — a defensive copy here would
 * allocate the whole dataset on the gating hot path. Do not mutate it.
 * <p>
 * <b>Hot-path shape.</b> {@code mean}/{@code std}/{@code min}/{@code max} are snapshotted
 * at construction, so {@link #zScoreAt(int)} is arithmetic on a {@code double[]} with no
 * map lookup, no boxing and no allocation. That is safe because a registered column is
 * write-once for a given {@link MarkerStats} instance: {@link MarkerStats#ensureColumn}
 * skips keys already present, and {@link MarkerStats#compute} returns a fresh instance
 * rather than mutating an existing one. Resolve the column once outside a per-cell loop
 * and index it inside.
 * <p>
 * <b>Threading.</b> Construction is the only interaction with {@code MarkerStats}, and it
 * goes through {@code ensureColumn}, whose benign check-then-act race (two threads may
 * redundantly compute the same column, producing identical numbers) is deliberate and
 * preserved. Instances are immutable and safe to publish across threads.
 */
public final class MeasuredColumn {

    private final String key;
    private final double[] values;
    private final MarkerStats stats;

    private final double mean;
    private final double std;
    private final double min;
    private final double max;

    /**
     * Package-private: only {@link CellIndex} may mint a handle, and only after it has
     * registered {@code key} with {@code stats}. That is the whole point of the type.
     */
    MeasuredColumn(String key, double[] values, MarkerStats stats) {
        this.key = key;
        this.values = values;
        this.stats = stats;
        // Snapshot after registration — see the class javadoc on write-once columns.
        this.mean = stats.getMean(key);
        this.std = stats.getStd(key);
        this.min = stats.getMin(key);
        this.max = stats.getMax(key);
    }

    /**
     * The resolved measurement key this column is stored under — {@code "CD3"} for a
     * whole-cell mean, {@code "CD3: Nucleus: Median"} otherwise. Exposed for headers,
     * logging and equality between axes; prefer the methods below for reading numbers.
     */
    public String key() {
        return key;
    }

    /** The backing per-cell value array — <b>read-only, do not mutate, not a copy</b>. */
    public double[] values() {
        return values;
    }

    /** Number of cells in this column. */
    public int size() {
        return values.length;
    }

    /** Raw value for one cell. */
    public double valueAt(int cell) {
        return values[cell];
    }

    /** Standardised value for one cell — the number a z-score gate compares. */
    public double zScoreAt(int cell) {
        return toZScore(values[cell]);
    }

    /**
     * Standardise a raw value against this column's own mean and standard deviation.
     * A degenerate column (std below 1e-10) yields 0.0, matching
     * {@link MarkerStats#toZScore}.
     */
    public double toZScore(double raw) {
        if (std < 1e-10) return 0.0;
        return (raw - mean) / std;
    }

    /** Inverse of {@link #toZScore}. */
    public double fromZScore(double zScore) {
        return zScore * std + mean;
    }

    /** Value at the given percentile (0-100) of this column, or NaN if it is empty. */
    public double percentile(double percentile) {
        return stats.getPercentileValue(key, percentile);
    }

    /** Percentile rank (0-100) of a raw value within this column; the inverse of {@link #percentile}. */
    public double percentileRankOf(double value) {
        return stats.percentileRankOf(key, value);
    }

    public double mean() {
        return mean;
    }

    public double std() {
        return std;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /**
     * True when the column has enough spread to standardise against. Callers used to
     * spell this {@code stats.getStd(key) > 1e-10} at every site that decides whether a
     * z-score transform is meaningful.
     */
    public boolean hasSpread() {
        return std > 1e-10;
    }

    /** Histogram bin edges for this column (length {@code bins + 1}). */
    public double[] histogramBins() {
        return stats.getHistogramBins(key);
    }

    /** Histogram counts for this column. */
    public double[] histogramCounts() {
        return stats.getHistogramCounts(key);
    }

    @Override
    public String toString() {
        return "MeasuredColumn[" + key + ", n=" + values.length + "]";
    }
}
