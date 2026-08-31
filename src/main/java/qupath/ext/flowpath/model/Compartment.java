package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A subcellular compartment a marker measurement can refer to.
 * <p>
 * Maps to the compartment token in the QuPath-native measurement key
 * {@code "<marker>: <Compartment>: <Stat>"} that the MIRAGE pipeline produces
 * (e.g. {@code "CD3: Nucleus: Mean"}). {@link #WHOLE_CELL} is the default, and is what a
 * legacy GeoJSON's bare {@code "CD3"} key resolves to.
 *
 * <h2>Open vocabulary, guarded</h2>
 * This is deliberately <b>not</b> an enum, for the same reason {@link Statistic} stopped
 * being one: a closed set makes FlowPath unable to see a column it has no name for, and
 * the key then fails to parse and is re-absorbed by
 * {@link MeasurementKeys#collapseToBaseMarkers} as a phantom marker literally spelled
 * {@code "CD3: Membrane: Median"}. MIRAGE's {@code COMPARTMENTS} is
 * {@code ("Nucleus", "Cytoplasm", "Cell")} today; if it grows a fourth, FlowPath should
 * offer it rather than invent a marker.
 * <p>
 * <b>But the compartment slot is load-bearing in a way the statistic slot is not.</b> It
 * is what {@link MeasurementKeys#parse} anchors on, and dropping the anchor entirely would
 * make any three-part name parse as a measurement. QuPath writes such names itself:
 * {@code "ROI: 0.50 µm per pixel: CD3"} would become marker {@code "ROI"}, compartment
 * {@code "0.50 µm per pixel"}, statistic {@code "CD3"} — a phantom compartment, a phantom
 * marker, and a real channel lost.
 * <p>
 * So an unknown token is a compartment only on evidence, and
 * {@link MeasurementKeys#discoverCompartments} states what counts: a token used by
 * <b>two or more distinct markers</b>. That is the structural difference between the two
 * cases. A real compartment is emitted for every marker in the panel; QuPath's ROI shape
 * has a single constant token in the marker slot, so it never reaches two.
 *
 * <h2>Interned</h2>
 * Instances are canonical per case-insensitive token, so {@code ==} and {@link #equals}
 * agree and the three constants below are the same objects discovery hands back.
 */
public final class Compartment {

    /** Canonical instances, keyed by lower-cased token. Declared first: the constants intern through it. */
    private static final Map<String, Compartment> INTERNED = new ConcurrentHashMap<>();

    /** The nucleus. */
    public static final Compartment NUCLEAR = intern("Nucleus", "Nuclear", "N");

    /** The cell body outside the nucleus: whole cell minus nucleus. */
    public static final Compartment CYTOPLASMIC = intern("Cytoplasm", "Cytoplasmic", "C");

    /** The whole cell; the default, and what a bare marker key means. */
    public static final Compartment WHOLE_CELL = intern("Cell", "Whole-cell", "W");

    /**
     * The compartments FlowPath has an opinion about, in display order. Anything a file
     * carries beyond these is offered after them, in the order it was discovered.
     */
    private static final List<Compartment> KNOWN = List.of(NUCLEAR, CYTOPLASMIC, WHOLE_CELL);

    private final String token;
    private final String displayName;
    private final String abbreviation;

    private Compartment(String token, String displayName, String abbreviation) {
        this.token = token;
        this.displayName = displayName;
        this.abbreviation = abbreviation;
    }

    private static Compartment intern(String token, String displayName, String abbreviation) {
        return INTERNED.computeIfAbsent(token.toLowerCase(Locale.ROOT),
                k -> new Compartment(token, displayName, abbreviation));
    }

    /**
     * The canonical compartment for {@code token}, creating one if this is the first time
     * FlowPath has seen it. Case-insensitive; the first spelling encountered is displayed.
     * <p>
     * <b>This does not ask whether the token is plausible.</b> Call it once something has
     * established that the slot really is a compartment — which for a measurement key means
     * {@link MeasurementKeys#discoverCompartments}.
     *
     * @throws IllegalArgumentException if {@code token} is null or blank
     */
    public static Compartment of(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Compartment token must not be null or blank");
        }
        String trimmed = token.trim();
        return INTERNED.computeIfAbsent(trimmed.toLowerCase(Locale.ROOT),
                k -> new Compartment(trimmed, trimmed, initial(trimmed)));
    }

    /** A one-letter badge for a compartment FlowPath has no opinion about. */
    private static String initial(String token) {
        return token.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    /**
     * The compartment for {@code token} <b>only if FlowPath already knows it</b>, else
     * {@code null}. This is the conservative lookup {@link MeasurementKeys#parse} uses when
     * no discovered set is supplied, and it is what keeps an arbitrary three-part name from
     * parsing as a measurement.
     */
    public static Compartment known(String token) {
        if (token == null) return null;
        Compartment c = INTERNED.get(token.trim().toLowerCase(Locale.ROOT));
        return c != null && KNOWN.contains(c) ? c : null;
    }

    /** Backwards-compatible alias for {@link #known}. */
    public static Compartment fromToken(String token) {
        return known(token);
    }

    /** The three FlowPath has an opinion about, in display order. */
    public static List<Compartment> known() {
        return KNOWN;
    }

    /** True when this is one of the three FlowPath names itself. */
    public boolean isKnown() {
        return KNOWN.contains(this);
    }

    /**
     * {@code available} ordered with the known compartments first, in their display order,
     * then anything else in encounter order. The replacement for iterating
     * {@code values()} and filtering, which could only ever order a closed set.
     */
    public static List<Compartment> orderKnownFirst(Collection<Compartment> available) {
        List<Compartment> out = new ArrayList<>();
        if (available == null) return out;
        for (Compartment c : KNOWN) {
            if (available.contains(c)) out.add(c);
        }
        for (Compartment c : available) {
            if (c != null && !out.contains(c)) out.add(c);
        }
        return out;
    }

    /** Token used inside the measurement key, e.g. {@code "Nucleus"}. */
    public String token() {
        return token;
    }

    /** Human-readable name for UI, e.g. {@code "Nuclear"}. */
    public String displayName() {
        return displayName;
    }

    /** One-letter badge code for the gating tree (N / C / W). */
    public String abbreviation() {
        return abbreviation;
    }

    /** Default compartment used when none is selected or the GeoJSON is legacy. */
    public static Compartment defaultCompartment() {
        return WHOLE_CELL;
    }

    /**
     * The enum-style constant name, kept so saved files keep spelling compartments
     * {@code "NUCLEAR"} / {@code "WHOLE_CELL"} as they always have. A compartment FlowPath
     * does not name falls back to its token.
     */
    public String name() {
        if (this == NUCLEAR) return "NUCLEAR";
        if (this == CYTOPLASMIC) return "CYTOPLASMIC";
        if (this == WHOLE_CELL) return "WHOLE_CELL";
        return token;
    }

    @Override
    public boolean equals(Object o) {
        // Interned, so identity is equality; spelled out so a future non-interned path
        // cannot make == and equals disagree.
        return this == o;
    }

    @Override
    public int hashCode() {
        return token.toLowerCase(Locale.ROOT).hashCode();
    }

    @Override
    public String toString() {
        return token;
    }
}
