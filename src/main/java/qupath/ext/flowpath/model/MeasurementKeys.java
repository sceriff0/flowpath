package qupath.ext.flowpath.model;

/**
 * Builds and parses the QuPath-native per-compartment measurement keys
 * {@code "<marker>: <Compartment>: <Stat>"} emitted by the MIRAGE pipeline.
 * <p>
 * Parsing tolerates an optional layer prefix (e.g. {@code "[Layer0] CD3: Nucleus: Mean"});
 * the layer prefix is stripped so the returned marker matches the channel names
 * discovered from image metadata.
 */
public final class MeasurementKeys {

    private MeasurementKeys() {}

    /** The separator between marker, compartment and statistic tokens. */
    private static final String SEP = ": ";

    /** Parsed components of a per-compartment measurement key. */
    public record Parsed(String marker, Compartment compartment, Statistic statistic) {}

    /** Build a measurement key, e.g. {@code build("CD3", NUCLEAR, MEAN) -> "CD3: Nucleus: Mean"}. */
    public static String build(String marker, Compartment compartment, Statistic statistic) {
        return marker + SEP + compartment.token() + SEP + statistic.token();
    }

    /**
     * Parse a measurement key of the form {@code "<marker>: <Compartment>: <Stat>"}.
     * <p>
     * <b>Anchored on the compartment, not on the pair.</b> This used to test every
     * {@code Compartment × Statistic} product as a suffix, which meant an export could
     * carry a statistic FlowPath had no name for and the whole key would fail to parse —
     * invisible to gating and to UMAP feature selection, and then re-absorbed by
     * {@link #collapseToBaseMarkers} as a phantom marker literally named
     * {@code "CD3: Cell: REDSEA"}. The two trailing slots look symmetric and are not:
     * {@link Compartment} is a genuinely closed set of anatomical regions, while the
     * statistic is whatever MIRAGE can compute. So the compartment is the anchor, and the
     * statistic slot accepts any non-blank token.
     * <p>
     * The split runs right-to-left, so a marker name that itself contains {@code ": "}
     * (QuPath's own {@code "ROI: 0.50 µm per pixel: CD3"} shape) still parses correctly.
     * A key with fewer than two separators, or whose second-from-right token is not a
     * compartment, is not a per-compartment key.
     *
     * @return the parsed components, or {@code null} if the key is not a recognised
     *         per-compartment key (e.g. a bare marker name or a morphology field).
     */
    public static Parsed parse(String key) {
        if (key == null) return null;
        int statSep = key.lastIndexOf(SEP);
        if (statSep < 0) return null;
        int compSep = key.lastIndexOf(SEP, statSep - 1);
        if (compSep < 0) return null;

        // The compartment token is the anchor: reject anything that is not one, which is
        // what keeps morphology fields and QuPath's own measurements out of the panel.
        Compartment compartment = Compartment.fromToken(key.substring(compSep + SEP.length(), statSep));
        if (compartment == null) return null;

        Statistic statistic = Statistic.fromToken(key.substring(statSep + SEP.length()));
        if (statistic == null) return null;

        String marker = stripLayerPrefix(key.substring(0, compSep)).trim();
        if (marker.isEmpty()) return null;
        return new Parsed(marker, compartment, statistic);
    }

    /**
     * Collapse measurement keys or channel names to their base marker, preserving order
     * and de-duplicating. {@code "CD3: Nucleus: Mean"} (optionally {@code "[Layer0] "}-
     * prefixed) collapses to {@code "CD3"}; a bare name keeps its own text with any layer
     * prefix stripped. This is what makes a marker panel show one row per marker rather
     * than one row per compartment/statistic combination.
     * <p>
     * The single implementation shared by every discovery path — it lived in
     * {@code UmapSession} while the gating half open-coded a different collapse, which is
     * one of the ways the two halves disagreed about the panel.
     */
    public static java.util.List<String> collapseToBaseMarkers(java.util.List<String> names) {
        var seen = new java.util.LinkedHashSet<String>();
        if (names == null) return new java.util.ArrayList<>(seen);
        for (String name : names) {
            if (name == null) continue;
            Parsed parsed = parse(name);
            String base = parsed != null ? parsed.marker() : stripLayerPrefix(name);
            if (base != null && !base.isBlank()) seen.add(base);
        }
        return new java.util.ArrayList<>(seen);
    }

    /** Remove a leading {@code "[...] "} layer prefix if present. */
    public static String stripLayerPrefix(String name) {
        if (name != null && name.startsWith("[")) {
            int idx = name.indexOf("] ");
            if (idx >= 0) return name.substring(idx + 2);
        }
        return name;
    }
}
