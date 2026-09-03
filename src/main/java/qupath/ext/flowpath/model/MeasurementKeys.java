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
        return parse(key, null);
    }

    /**
     * Parse a key, additionally accepting any compartment in {@code recognised}.
     * <p>
     * {@link Compartment} became an open vocabulary for the same reason {@link Statistic}
     * did — a closed set makes FlowPath unable to see a column it has no name for — but the
     * compartment slot cannot simply be opened, because it is what this method anchors on.
     * Accepting any token here would make {@code "ROI: 0.50 µm per pixel: CD3"}, a name
     * QuPath writes itself, parse as marker {@code "ROI"}, compartment
     * {@code "0.50 µm per pixel"} and statistic {@code "CD3"}: a phantom compartment, a
     * phantom marker, and a real channel lost.
     * <p>
     * So an unknown compartment is accepted only when something has established it as one.
     * {@link #discoverCompartments} is what does that, and the evidence it requires is
     * structural rather than a guess — see there.
     *
     * @param recognised compartments beyond the known three to accept, or {@code null}
     */
    public static Parsed parse(String key, java.util.Set<Compartment> recognised) {
        if (key == null) return null;
        int statSep = key.lastIndexOf(SEP);
        if (statSep < 0) return null;
        int compSep = key.lastIndexOf(SEP, statSep - 1);
        if (compSep < 0) return null;

        // The compartment token is the anchor: reject anything that is neither a known
        // compartment nor one discovery has vouched for, which is what keeps morphology
        // fields and QuPath's own measurements out of the panel.
        String compToken = key.substring(compSep + SEP.length(), statSep);
        Compartment compartment = Compartment.known(compToken);
        if (compartment == null && recognised != null && !recognised.isEmpty()) {
            for (Compartment c : recognised) {
                if (c != null && c.token().equalsIgnoreCase(compToken.trim())) {
                    compartment = c;
                    break;
                }
            }
        }
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
        return collapseToBaseMarkers(names, null);
    }

    /**
     * Collapse to base markers, accepting any compartment in {@code recognised}.
     * <p>
     * Anyone who has a {@link CompartmentCapability} in hand <b>must</b> use this form and
     * pass {@code capability.compartments()}. The one-argument form recognises only the
     * known three, so on data carrying a discovered fourth compartment it fails to parse
     * {@code "CD3: Membrane: Mean"}, falls back to {@link #stripLayerPrefix} and adds the
     * whole key as a phantom marker of that literal name — while the very same capability
     * is simultaneously offering {@code Membrane} in the gate editor's compartment picker.
     * The two readers of one key set then disagree about what a compartment is, which is
     * exactly what {@link CompartmentCapability#compartments()} tells its caller to
     * prevent.
     *
     * @param recognised compartments beyond the known three to accept, or {@code null} for
     *                   the closed set
     */
    public static java.util.List<String> collapseToBaseMarkers(
            java.util.List<String> names, java.util.Set<Compartment> recognised) {
        var seen = new java.util.LinkedHashSet<String>();
        if (names == null) return new java.util.ArrayList<>(seen);
        for (String name : names) {
            if (name == null) continue;
            Parsed parsed = parse(name, recognised);
            String base = parsed != null ? parsed.marker() : stripLayerPrefix(name);
            if (base != null && !base.isBlank()) seen.add(base);
        }
        return new java.util.ArrayList<>(seen);
    }

    /**
     * The compartments a set of measurement keys actually contains: the known three, plus
     * any token that <b>two or more distinct markers</b> use in the compartment slot.
     * <p>
     * The threshold is the whole point, and it is a structural fact rather than a
     * heuristic. A real compartment is emitted for every marker in the panel, so on any
     * export worth gating it appears dozens of times with dozens of different markers.
     * QuPath's own three-part names have a <em>constant</em> token in the marker slot —
     * {@code "ROI: 0.50 µm per pixel: CD3"}, {@code "ROI: 0.50 µm per pixel: CD8"} — so
     * however many of them a file carries, the marker slot never reaches two distinct
     * values and the token is never promoted.
     * <p>
     * A single-marker panel is unaffected: the known three are always recognised, and the
     * two-marker rule gates only tokens FlowPath has never seen.
     */
    public static java.util.Set<Compartment> discoverCompartments(java.util.Collection<String> keys) {
        java.util.LinkedHashSet<Compartment> out = new java.util.LinkedHashSet<>(Compartment.known());
        if (keys == null) return out;

        java.util.Map<String, java.util.Set<String>> markersByToken = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            if (key == null) continue;
            int statSep = key.lastIndexOf(SEP);
            if (statSep < 0) continue;
            int compSep = key.lastIndexOf(SEP, statSep - 1);
            if (compSep < 0) continue;
            String token = key.substring(compSep + SEP.length(), statSep).trim();
            if (token.isEmpty() || Compartment.known(token) != null) continue;
            String marker = stripLayerPrefix(key.substring(0, compSep)).trim();
            if (marker.isEmpty()) continue;
            markersByToken.computeIfAbsent(token, k -> new java.util.LinkedHashSet<>()).add(marker);
        }
        markersByToken.forEach((token, markers) -> {
            if (markers.size() >= 2) out.add(Compartment.of(token));
        });
        return out;
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
