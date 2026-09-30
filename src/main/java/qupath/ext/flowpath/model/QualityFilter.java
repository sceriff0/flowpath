package qupath.ext.flowpath.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>Per-field acceptance ranges for pre-gating quality control</b>, keyed by namespaced slug
 * ({@link MeasurementName#slug()}): {@code morph/<name>} for a shape, {@code qc/<metric>} for a
 * cell-level QC metric — both remove a failing cell — and {@code qcround/<metric>} for a
 * round-level QC metric, which applies to every round and makes a failing round's markers
 * Unmeasured for that cell rather than removing it.
 * <p>
 * {@link CellIndex#qualityFields()} (and the index's round QC) decide what there is to
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

        /**
         * True when {@code v} is inside, or is NaN — see the class note on NaN.
         * <p>
         * Compared at {@code float} precision, not {@code double}. QuPath backs a detection's
         * measurement list with {@code float} storage, and {@code CellIndex} widens each value
         * to {@code double} to compute with, which cannot recover precision the value never
         * had. A bound typed as an exact-looking number like {@code 0.7} is a {@code double}
         * literal that rounds to a different bit pattern than the {@code float} {@code 0.7f} a
         * stored measurement of "0.7" actually widens to — so comparing at {@code double}
         * precision could reject a value one ulp short of a bound the user meant it to meet
         * exactly, or accept one one ulp past it, depending on which way the two roundings
         * fell. Casting both sides to {@code float} compares them at the precision the
         * underlying data actually carries, which loses at most the last bit of a bound typed
         * with more precision than a {@code float} measurement could ever match anyway.
         * <p>
         * Lossless for {@code v}: every field this class filters is a stored measurement.
         */
        public boolean accepts(double v) {
            return Double.isNaN(v) || ((float) v >= (float) min && (float) v <= (float) max);
        }

        /** True when this range excludes nothing and so need not be stored or shown as set. */
        public boolean isOpen() {
            return min <= Double.NEGATIVE_INFINITY && max >= Double.POSITIVE_INFINITY;
        }
    }

    // The slugs of MIRAGE's own fields, for code and tests that name one.
    public static final String AREA = "morph/area";
    public static final String ECCENTRICITY = "morph/eccentricity";
    public static final String SOLIDITY = "morph/solidity";
    public static final String PERIMETER = "morph/perimeter";
    public static final String TOTAL_INTENSITY = "qc/total_intensity";

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
     * Driven by {@link CellIndex#qualityFields()}, so a range over a field the file does not
     * have is not consulted — it cannot exclude a cell on the strength of a column that
     * is not there.
     */
    public boolean passes(CellIndex index, int i) {
        if (index == null || ranges.isEmpty()) return true;
        for (QualityField field : index.qualityFields()) {
            Range r = ranges.get(field.slug());
            if (r != null && !r.accepts(field.valueAt(i))) return false;
        }
        return true;
    }

    /** Close the lower bound of {@code slug}, keeping its upper bound. */
    public void setMin(String slug, double v) {
        setRange(slug, new Range(v, range(slug).max()));
    }

    /** Close the upper bound of {@code slug}, keeping its lower bound. */
    public void setMax(String slug, double v) {
        setRange(slug, new Range(range(slug).min(), v));
    }

    /** A deep copy, carrying every range including those for fields FlowPath does not name. */
    public QualityFilter deepCopy() {
        QualityFilter copy = new QualityFilter();
        copy.ranges.putAll(this.ranges);
        return copy;
    }
}
