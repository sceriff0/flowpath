package qupath.ext.flowpath.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>Per-field acceptance ranges for pre-gating quality control</b>, keyed by
 * {@link MorphologyField#slug()}.
 * <p>
 * This used to be ten fixed fields — a min and a max each for area, eccentricity,
 * solidity, total intensity and perimeter — which meant the filter could only ever
 * express what FlowPath had been told about in advance. A MIRAGE export carries seven
 * morphology measurements; five could be filtered, and {@code Major Axis Length µm} and
 * {@code Minor Axis Length µm} could not, though they are among the more useful signals
 * for rejecting a segmentation artefact. In the other direction a file with no solidity
 * still got a solidity range, applied to a column of NaN.
 * <p>
 * Ranges are now a map, and {@link CellIndex#morphology()} decides what there is to
 * filter. A slug with no entry here is unconstrained; an entry whose field the file does
 * not carry is simply never consulted, so a filter saved against a richer export loads
 * against a leaner one without either erroring or silently dropping cells.
 *
 * <h2>NaN passes</h2>
 * A cell missing a measurement is not excluded by a range over it. Excluding would mean a
 * marker the pipeline could not compute for one cell silently removes that cell from every
 * population, which is a data-dependent bias rather than quality control.
 */
public class QualityFilter {

    /** An inclusive acceptance range. {@code min}/{@code max} may be infinite. */
    public record Range(double min, double max) {

        /** Accepts everything. */
        public static final Range OPEN = new Range(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        /** True when {@code v} is inside, or is NaN — see the class note on NaN. */
        public boolean accepts(double v) {
            return Double.isNaN(v) || (v >= min && v <= max);
        }

        /** True when this range excludes nothing and so need not be stored or shown as set. */
        public boolean isOpen() {
            return min <= Double.NEGATIVE_INFINITY && max >= Double.POSITIVE_INFINITY;
        }
    }

    // Slugs FlowPath has always filtered on. Named constants because the legacy accessors
    // and the v1..v3 JSON both address them by these exact spellings.
    public static final String AREA = "area";
    public static final String ECCENTRICITY = "eccentricity";
    public static final String SOLIDITY = "solidity";
    public static final String PERIMETER = "perimeter";
    public static final String TOTAL_INTENSITY = "total_intensity";

    private final Map<String, Range> ranges = new LinkedHashMap<>();

    public QualityFilter() {
    }

    // ---- generic access ---------------------------------------------------------

    /** The range for {@code slug}, or {@link Range#OPEN} if unconstrained. */
    public Range range(String slug) {
        Range r = ranges.get(slug);
        return r != null ? r : Range.OPEN;
    }

    /** Constrain {@code slug}; an open range removes the entry rather than storing a no-op. */
    public void setRange(String slug, Range range) {
        if (slug == null) return;
        if (range == null || range.isOpen()) ranges.remove(slug);
        else ranges.put(slug, range);
    }

    /** Every constrained slug, in the order it was first set. Never null. */
    public Map<String, Range> ranges() {
        return Map.copyOf(ranges);
    }

    /** True when this filter would exclude nothing. */
    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /**
     * Whether cell {@code i} passes every constrained field this export actually carries.
     * <p>
     * Driven by {@link CellIndex#morphology()}, so a range over a field the file does not
     * have is not consulted — it cannot exclude a cell on the strength of a column that
     * is not there.
     */
    public boolean passes(CellIndex index, int i) {
        if (index == null || ranges.isEmpty()) return true;
        for (MorphologyField field : index.morphology()) {
            Range r = ranges.get(field.slug());
            if (r != null && !r.accepts(field.valueAt(i))) return false;
        }
        return true;
    }

    /**
     * The legacy positional form, kept for the two call sites that already hold these five
     * numbers and for the tests that pin them.
     *
     * @deprecated prefer {@link #passes(CellIndex, int)}, which consults every field the
     *             export carries rather than the five FlowPath used to know about.
     */
    @Deprecated
    public boolean passes(double area, double eccentricity, double solidity,
                          double totalIntensity, double perimeter) {
        return range(AREA).accepts(area)
                && range(ECCENTRICITY).accepts(eccentricity)
                && range(SOLIDITY).accepts(solidity)
                && range(TOTAL_INTENSITY).accepts(totalIntensity)
                && range(PERIMETER).accepts(perimeter);
    }

    // ---- legacy named accessors -------------------------------------------------
    //
    // The serializer and the older tests address these five by name. They are thin views
    // onto the map, so there is one representation rather than two that can disagree.

    private double min(String slug, double fallback) {
        double v = range(slug).min();
        return v <= Double.NEGATIVE_INFINITY ? fallback : v;
    }

    private double max(String slug, double fallback) {
        double v = range(slug).max();
        return v >= Double.POSITIVE_INFINITY ? fallback : v;
    }

    private void withMin(String slug, double v) {
        setRange(slug, new Range(v, range(slug).max()));
    }

    private void withMax(String slug, double v) {
        setRange(slug, new Range(range(slug).min(), v));
    }

    public double getMinArea() { return min(AREA, 0); }
    public void setMinArea(double v) { withMin(AREA, v); }
    public double getMaxArea() { return max(AREA, Double.MAX_VALUE); }
    public void setMaxArea(double v) { withMax(AREA, v); }

    public double getMinEccentricity() { return min(ECCENTRICITY, 0.0); }
    public void setMinEccentricity(double v) { withMin(ECCENTRICITY, v); }
    public double getMaxEccentricity() { return max(ECCENTRICITY, 1.0); }
    public void setMaxEccentricity(double v) { withMax(ECCENTRICITY, v); }

    public double getMinSolidity() { return min(SOLIDITY, 0.0); }
    public void setMinSolidity(double v) { withMin(SOLIDITY, v); }
    public double getMaxSolidity() { return max(SOLIDITY, 1.0); }
    public void setMaxSolidity(double v) { withMax(SOLIDITY, v); }

    public double getMinPerimeter() { return min(PERIMETER, 0); }
    public void setMinPerimeter(double v) { withMin(PERIMETER, v); }
    public double getMaxPerimeter() { return max(PERIMETER, Double.MAX_VALUE); }
    public void setMaxPerimeter(double v) { withMax(PERIMETER, v); }

    public double getMinTotalIntensity() { return min(TOTAL_INTENSITY, 0); }
    public void setMinTotalIntensity(double v) { withMin(TOTAL_INTENSITY, v); }
    public double getMaxTotalIntensity() { return max(TOTAL_INTENSITY, Double.MAX_VALUE); }
    public void setMaxTotalIntensity(double v) { withMax(TOTAL_INTENSITY, v); }

    /** A deep copy, carrying every range including those for fields FlowPath does not name. */
    public QualityFilter deepCopy() {
        QualityFilter copy = new QualityFilter();
        copy.ranges.putAll(this.ranges);
        return copy;
    }
}
