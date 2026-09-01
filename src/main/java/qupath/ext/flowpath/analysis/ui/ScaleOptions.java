package qupath.ext.flowpath.analysis.ui;

/**
 * The two axis remedies the user asked for a population-count plot's Y axis, kept as
 * independent toggles rather than a single "scale mode" enum.
 * <p>
 * A gated slide routinely has one enormous population and several tiny ones — 214,000 cells
 * in one bar and 3 in another — and on a linear axis scaled to the maximum the small bars are
 * invisible. The user asked for two remedies and was explicit that they have to compose:
 * {@link #log} lifts small bars into view without touching any data, and {@link #clip} caps
 * the axis at a percentile of the bar values so one outlier does not set the scale. An enum of
 * {@code LINEAR}/{@code LOG}/{@code CLIPPED}/{@code LOG_CLIPPED} would have made "both" a
 * fourth case a future edit could forget to add; two independent booleans make it the only
 * thing four states can mean, with nothing to keep in sync.
 * <p>
 * {@link #percentile} travels with {@link #clip} rather than being a separate control, because
 * it is meaningless without it — there is nothing to validate about a percentile that never
 * gets read. The compact constructor still enforces its range regardless of whether
 * {@code clip} is set, so a caller cannot stash an out-of-range value now and have it surface
 * only once someone flips the checkbox later.
 *
 * @param log        draw the Y axis logarithmically (see {@link AxisScale} for the mapping)
 * @param clip        cap the axis at the {@link #percentile}-th percentile of the plotted values
 * @param percentile the percentile to clip at, nearest-rank, in {@code [50, 100]}. The floor of
 *                   50 rules out a clip that would put the median or lower at the top of the
 *                   axis, which would hide more than half the bars rather than merely rescale
 *                   them.
 */
public record ScaleOptions(boolean log, boolean clip, double percentile) {

    /** The default: a plain linear axis, nothing lifted and nothing clipped. */
    public static final ScaleOptions LINEAR = new ScaleOptions(false, false, 95.0);

    public ScaleOptions {
        if (percentile < 50 || percentile > 100) {
            throw new IllegalArgumentException(
                    "percentile must be in [50, 100], got " + percentile);
        }
    }

    /** This options set with only {@link #log} changed. */
    public ScaleOptions withLog(boolean log) {
        return new ScaleOptions(log, clip, percentile);
    }

    /** This options set with only {@link #clip} changed. */
    public ScaleOptions withClip(boolean clip) {
        return new ScaleOptions(log, clip, percentile);
    }

    /** This options set with only {@link #percentile} changed. */
    public ScaleOptions withPercentile(double percentile) {
        return new ScaleOptions(log, clip, percentile);
    }
}
