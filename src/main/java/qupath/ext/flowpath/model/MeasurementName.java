package qupath.ext.flowpath.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * <b>What a measurement key is</b>, decided once from its name alone — the one classifier every
 * discovery path goes through (marker panel, compartment scan, quality fields, round QC).
 * <p>
 * The vocabulary is MIRAGE's ({@code docs/outputs.md}, "Per-cell QC"), and the prefix is the
 * contract, not a list of metric names:
 * <pre>
 * QC: &lt;Metric&gt;                              cell-level  — failing it removes the whole cell
 * QC: &lt;Metric&gt;: [&lt;marker&gt;, &lt;marker&gt;, ...]   round-level — failing it makes those markers Unmeasured
 * MORPH: &lt;Name&gt;[ &lt;unit&gt;]                    the cell's shape
 * label, Centroid X µm, Centroid Y µm         identity
 * anything else                              a marker key ("CD3", "CD3: Cell: Median")
 * </pre>
 * The prefixes are exact and case-sensitive, with the {@code ": "} separator of the marker
 * grammar. An unknown QC metric is still a QC field: it is shown, never dropped.
 * <p>
 * There is deliberately no fallback for names without a prefix. FlowPath used to guess shape
 * columns from their spelling ({@code "Area µm²"}, {@code "circularity"}, a trailing unit), and
 * a guess wrong in either direction is silent: a marker filtered as a shape, or a shape gated
 * as a marker. Since MIRAGE's {@code MORPH:}/{@code QC:} vocabulary, nothing needs guessing.
 *
 * @param key          the measurement key, verbatim
 * @param kind         what it is
 * @param metric       for QC and morphology, the name after the prefix (and before a round's
 *                     marker list): {@code "Area µm²"}, {@code "Nuclear retention"}; else
 *                     {@code null}
 * @param roundMarkers for a round-level QC key, the round's markers in the order the key
 *                     lists them; else empty
 */
public record MeasurementName(String key, Kind kind, String metric, List<String> roundMarkers) {

    public static final String QC_PREFIX = "QC: ";
    public static final String MORPH_PREFIX = "MORPH: ";
    /** The one identity column MIRAGE writes besides the centroids. */
    public static final String LABEL = "label";
    public static final String CENTROID_X = "Centroid X µm";
    public static final String CENTROID_Y = "Centroid Y µm";

    public enum Kind { MARKER, IDENTITY, QC_CELL, QC_ROUND, MORPHOLOGY }

    public MeasurementName {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        roundMarkers = roundMarkers == null ? List.of() : List.copyOf(roundMarkers);
    }

    /** Classify {@code key}. Never {@code null}; a {@code null} key is not a measurement. */
    public static MeasurementName classify(String key) {
        Objects.requireNonNull(key, "key");
        if (key.startsWith(QC_PREFIX)) {
            String rest = key.substring(QC_PREFIX.length());
            int open = rest.lastIndexOf(": [");
            if (open > 0 && rest.endsWith("]")) {
                String list = rest.substring(open + 3, rest.length() - 1);
                List<String> markers = Arrays.stream(list.split(", "))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (!markers.isEmpty()) {
                    return new MeasurementName(key, Kind.QC_ROUND, rest.substring(0, open), markers);
                }
            }
            return new MeasurementName(key, Kind.QC_CELL, rest, List.of());
        }
        if (key.startsWith(MORPH_PREFIX)) {
            return new MeasurementName(key, Kind.MORPHOLOGY, key.substring(MORPH_PREFIX.length()), List.of());
        }
        if (key.equals(LABEL) || key.equals(CENTROID_X) || key.equals(CENTROID_Y)) {
            return new MeasurementName(key, Kind.IDENTITY, null, List.of());
        }
        return new MeasurementName(key, Kind.MARKER, null, List.of());
    }

    /** Whether {@code key} is a marker key — the question marker discovery asks. */
    public static boolean isMarkerKey(String key) {
        return key != null && classify(key).kind() == Kind.MARKER;
    }

    /**
     * The quality-filter slug for a cell-level field: {@code "morph/area"},
     * {@code "qc/total_intensity"}; for a round metric {@code "qcround/nuclear_retention"}.
     * Namespaced so a QC metric and a shape can never share a saved range, and unit-free so a
     * range survives the export renaming {@code "µm²"} to {@code "um2"}. {@code null} for a
     * marker or identity key.
     */
    public String slug() {
        return switch (kind) {
            case MORPHOLOGY -> "morph/" + slugOf(metric);
            case QC_CELL -> "qc/" + slugOf(metric);
            case QC_ROUND -> "qcround/" + slugOf(metric);
            case MARKER, IDENTITY -> null;
        };
    }

    /** What a person reads: the metric with a trailing unit removed ("Area", "Nuclear retention"). */
    public String label() {
        if (metric == null) return key;
        String s = metric.trim().replaceAll(UNIT_SUFFIX, "");
        return s.isBlank() ? metric.trim() : s;
    }

    private static final String UNIT_SUFFIX = "\\s+(µm\u00b2|µm2|µm\\^2|µm|um\u00b2|um2|um\\^2|um|px|pixels?)$";

    /**
     * {@code lower_snake_case} with a trailing unit removed: {@code "Major Axis Length µm"} →
     * {@code "major_axis_length"}. Only a trailing unit — {@code "Sum"} ends in "um".
     */
    public static String slugOf(String name) {
        if (name == null) return "";
        String s = name.toLowerCase(java.util.Locale.ROOT).trim();
        s = s.replaceAll(UNIT_SUFFIX, "");
        s = s.replaceAll("[^a-z0-9]+", "_");
        return s.replaceAll("^_+|_+$", "");
    }
}
