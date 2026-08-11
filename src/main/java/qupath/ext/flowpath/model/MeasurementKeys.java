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
     *
     * @return the parsed components, or {@code null} if the key is not a recognised
     *         per-compartment key (e.g. a bare marker name or a morphology field).
     */
    public static Parsed parse(String key) {
        if (key == null) return null;
        // Match the last two ": <token>" segments against known compartment/statistic tokens.
        for (Compartment c : Compartment.values()) {
            for (Statistic s : Statistic.values()) {
                String suffix = SEP + c.token() + SEP + s.token();
                if (key.endsWith(suffix)) {
                    String marker = key.substring(0, key.length() - suffix.length());
                    marker = stripLayerPrefix(marker).trim();
                    if (marker.isEmpty()) return null;
                    return new Parsed(marker, c, s);
                }
            }
        }
        return null;
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
