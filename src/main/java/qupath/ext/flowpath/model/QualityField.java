package qupath.ext.flowpath.model;

import java.util.Objects;

/**
 * One per-cell quality column a quality filter can act on: a shape ({@code MORPH: …}) or a
 * cell-level QC metric ({@code QC: Total intensity}). Round-level QC is not a field — failing a
 * round does not remove the cell, it makes that round's markers Unmeasured — see
 * {@code RoundQc}.
 * <p>
 * A field exists because a key for it is in the data; {@link CellIndex#qualityFields()}
 * discovers them from the export, through {@link MeasurementName#classify}.
 * <p>
 * {@link #values} is the backing array, in keeping with {@link CellIndex#getMarkerValues}:
 * a defensive copy of every quality column on a multi-million-cell slide would duplicate the
 * dataset to read it. Callers must not write to it.
 *
 * @param slug   namespaced, unit-free identifier and the key of saved filter ranges
 *               ({@code "morph/area"}, {@code "qc/total_intensity"})
 * @param key    the measurement key as exported
 * @param label  display name, unit removed
 * @param kind   {@link MeasurementName.Kind#MORPHOLOGY} or {@link MeasurementName.Kind#QC_CELL}
 * @param values one value per cell, positional against {@code CellIndex.getObjects()}; NaN
 *               where the cell does not carry it
 */
public record QualityField(String slug, String key, String label, MeasurementName.Kind kind, double[] values) {

    public QualityField {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(values, "values");
        if (kind != MeasurementName.Kind.MORPHOLOGY && kind != MeasurementName.Kind.QC_CELL) {
            throw new IllegalArgumentException("A quality field is a shape or a cell-level QC metric, not " + kind);
        }
    }

    /** This field's value for cell {@code i}, or NaN if the cell did not carry it. */
    public double valueAt(int i) {
        return i >= 0 && i < values.length ? values[i] : Double.NaN;
    }

    /** True when at least one cell carries a real number for this field. */
    public boolean hasAnyValue() {
        for (double v : values) {
            if (!Double.isNaN(v)) return true;
        }
        return false;
    }

    /**
     * The CSV column name: the unit-free metric slug, {@code qc_}-prefixed for a QC metric
     * ({@code "area"}, {@code "qc_total_intensity"}). Morphology keeps the names MIRAGE's own
     * {@code merged_quant.csv} uses, so the two tables line up.
     */
    public String csvName() {
        String bare = slug.substring(slug.indexOf('/') + 1);
        return kind == MeasurementName.Kind.QC_CELL ? "qc_" + bare : bare;
    }
}
