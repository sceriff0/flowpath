package qupath.ext.flowpath.model;

import java.util.Locale;
import java.util.Objects;

/**
 * <b>One shape or size measurement the export actually carries</b>, discovered rather than
 * declared.
 * <p>
 * FlowPath used to know about exactly four: area, perimeter, eccentricity and solidity,
 * hard-coded as fields on {@link CellIndex} and again as five sliders on the quality-filter
 * panel. That was wrong in both directions at once.
 * <ul>
 *   <li><b>It offered what was not there.</b> A whole-cell-only mask, or a run with
 *       {@code quantify_compartments} off, carries no solidity — but the slider was drawn
 *       anyway, and moving it filtered on NaN.</li>
 *   <li><b>It hid what was there.</b> MIRAGE emits seven morphology measurements. FlowPath
 *       resolved four. {@code Major Axis Length µm} and {@code Minor Axis Length µm} sat in
 *       the file unread, so elongation — one of the more useful signals for rejecting a
 *       segmentation artefact — could not be filtered on at all, and nothing said so.</li>
 * </ul>
 * A field exists here because a key for it is in the data. Nothing else.
 *
 * <h2>Three names, because three things need naming</h2>
 * <ul>
 *   <li>{@link #key} — the measurement name as exported, {@code "Major Axis Length µm"}.
 *       This is what {@code PathObject.getMeasurements()} is addressed by.</li>
 *   <li>{@link #slug} — a stable identifier with the units stripped,
 *       {@code "major_axis_length"}. Quality-filter ranges are stored under this and it is
 *       what reaches the saved JSON, so a filter survives a pipeline renaming
 *       {@code "Area µm²"} to {@code "Area um2"}. It also matches MIRAGE's own
 *       {@code MORPHOLOGY_COLS} spelling.</li>
 *   <li>{@link #label} — what a human reads, {@code "Major Axis Length"}.</li>
 * </ul>
 *
 * <h2>Not a copy</h2>
 * {@link #values} is the backing array, in keeping with {@link CellIndex#getMarkerValues}:
 * a defensive copy of every morphology column on a multi-million-cell slide would
 * duplicate the dataset to read it. Callers must not write to it.
 *
 * @param slug   stable, unit-free identifier; the key for saved filter ranges
 * @param key    the measurement name as the export spells it
 * @param label  display name
 * @param values one value per cell, positional against {@code CellIndex.getObjects()}
 */
public record MorphologyField(String slug, String key, String label, double[] values) {

    public MorphologyField {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(values, "values");
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
     * A stable identifier for a measurement name, with trailing units removed and
     * everything else folded to {@code lower_snake_case}.
     * <p>
     * {@code "Area µm²"} becomes {@code "area"}, {@code "Major Axis Length µm"} becomes
     * {@code "major_axis_length"}, {@code "Eccentricity"} stays {@code "eccentricity"}.
     * <p>
     * Only a <em>trailing</em> unit is stripped. Stripping {@code "um"} anywhere would
     * quietly maul any name containing those letters — {@code "Sum"} being the one that
     * matters here, since it is also a statistic token.
     */
    public static String slugOf(String key) {
        if (key == null) return "";
        String s = key.toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("\\s*(µm\u00b2|µm2|µm\\^2|µm|um\u00b2|um2|um\\^2|um|px|pixels?)$", "");
        s = s.replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        return s;
    }

    /**
     * A display label for a measurement name: the exported name with a trailing unit
     * removed, so a panel reads "Area" rather than "Area µm²" beside a slider that already
     * says what it is measuring.
     */
    public static String labelOf(String key) {
        if (key == null) return "";
        String s = key.trim();
        s = s.replaceAll("\\s*(µm\u00b2|µm2|µm\\^2|µm|um\u00b2|um2|um\\^2|um|px|pixels?)$", "");
        return s.isBlank() ? key.trim() : s;
    }
}
