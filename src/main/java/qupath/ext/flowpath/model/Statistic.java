package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-compartment summary statistic stored by the MIRAGE pipeline.
 * <p>
 * Maps to the statistic token in the measurement key
 * {@code "<marker>: <Compartment>: <Stat>"}. <b>{@link #MEDIAN} is the one MIRAGE
 * always emits</b> — {@code params.quantify_statistics} defaults to {@code ['Median']}.
 * Every other name appears only when that list asks for it. Which of
 * them a given export actually carries must be read from
 * {@link CompartmentCapability}, never assumed — a default-quantification export
 * has no Mean column, and pinning a gate axis to one resolves it to a measurement
 * key that is not in the file, so every cell reads NaN.
 *
 * <h2>Open vocabulary</h2>
 * This is deliberately <b>not</b> an enum. MIRAGE's statistic list is extensible
 * and — since MIRAGE composed its vocabulary — no longer even enumerable by hand. A closed
 * enum here made FlowPath unable to see a column it had no name for: the key failed to
 * parse, then {@code MeasurementKeys.collapseToBaseMarkers} re-absorbed the whole unparsed
 * string as a marker, so the panel grew a phantom row spelled {@code "CD3: Cell: REDSEA"}
 * next to the real {@code CD3}.
 *
 * <h2>The vocabulary is composed, not listed</h2>
 * MIRAGE builds its statistic names as {@code base × normalisation}: bases
 * {@code Median}, {@code Mean}, {@code Sum} and {@code REDSEA}, each crossed with
 * {@code ""}, {@code " Z"} and {@code " RobustZ"} — twelve names, several containing a
 * space. Enumerating those on this side would be a list to hand-sync forever, which is
 * the thing this type exists to stop; FlowPath discovers them from the data and only
 * understands their <em>shape</em>, via {@link #baseToken} and {@link #normalisation}.
 * <p>
 * {@code REDSEA} is whole-cell only — a membrane correction has no nucleus/cytoplasm
 * decomposition — which is why {@link CompartmentCapability} must store pairs rather than
 * two independent axes. FlowPath now discovers the
 * statistic vocabulary from the data and only {@link Compartment} stays closed — it is a
 * genuinely fixed set of anatomical regions, which is what makes it a reliable parsing
 * anchor. {@link #MEAN}, {@link #MEDIAN} and {@link #SUM} survive as constants because
 * FlowPath still has opinions about them: display ordering, and the bare-column rule.
 *
 * <h2>Interned</h2>
 * Instances are canonical per case-insensitive token, so {@code ==} and
 * {@link #equals} agree. That is load-bearing: {@code CellIndex.isDefault} spelled the
 * bare-column rule as {@code statistic == Statistic.MEAN}, and a non-interned value type
 * would have made {@code of("Mean") == MEAN} false — resolving every default gate to
 * {@code "CD3: Cell: Mean"} instead of the bare {@code "CD3"} column, and reading NaN for
 * every cell without throwing. Those call sites now use {@link #equals} as well, so
 * neither mechanism is the only thing standing between the rule and a silent wrong
 * answer.
 * <p>
 * Orthogonal to FlowPath's live z-score toggle, which is applied on top of
 * whichever statistic is selected.
 */
public final class Statistic {

    /** Canonical instances, keyed by lower-cased token. Declared first: the constants below intern through it. */
    private static final Map<String, Statistic> INTERNED = new ConcurrentHashMap<>();

    /** The whole-cell mean — also what the <em>bare</em> marker column holds. */
    public static final Statistic MEAN = of("Mean");

    /** MIRAGE's default statistic, always emitted for every compartment it quantifies. */
    public static final Statistic MEDIAN = of("Median");

    /** Integrated intensity over the compartment. */
    public static final Statistic SUM = of("Sum");

    /**
     * The statistics FlowPath has an opinion about, in display order. Anything else a file
     * carries is offered after these, in the order it was discovered.
     */
    private static final List<Statistic> KNOWN = List.of(MEAN, MEDIAN, SUM);

    private final String token;

    private Statistic(String token) {
        this.token = token;
    }

    /**
     * The canonical statistic for {@code token}, creating one if this is the first time
     * FlowPath has seen it. Case-insensitive: the first spelling encountered is the one
     * displayed, so {@code of("median")} is {@link #MEDIAN} and renders as {@code "Median"}.
     *
     * @throws IllegalArgumentException if {@code token} is null or blank — an unnamed
     *         statistic is a malformed key, not a new kind of measurement.
     */
    public static Statistic of(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Statistic token must not be null or blank");
        }
        String trimmed = token.trim();
        return INTERNED.computeIfAbsent(trimmed.toLowerCase(Locale.ROOT), k -> new Statistic(trimmed));
    }

    /**
     * Parse a statistic token (case-insensitive), or {@code null} if there is no token at
     * all.
     * <p>
     * <b>An unrecognised token is not a parse failure.</b> This used to return {@code null}
     * for anything outside {@code Mean}/{@code Median}/{@code Sum}, which is what made a
     * new MIRAGE statistic invisible to gating and to UMAP feature selection. It now
     * returns {@code null} only for a null or blank token, so callers keep their existing
     * "skip the malformed line" behaviour without also discarding valid new statistics.
     */
    public static Statistic fromToken(String token) {
        if (token == null || token.isBlank()) return null;
        return of(token);
    }

    /**
     * MIRAGE's normalisation suffixes, in display order (plain, then z, then robust z).
     * <p>
     * Splitting must try the <b>longest first</b>: {@code "Median RobustZ"} ends with
     * {@code " Z"} as well, so a shorter match would split it into base
     * {@code "Median Robust"}. MIRAGE's own {@code split_statistic} carries the same
     * caveat, and this is the mirror of it.
     */
    private static final List<String> NORMALISATIONS = List.of("", " Z", " RobustZ");

    /**
     * The measured quantity, with any normalisation suffix removed:
     * {@code "Median RobustZ"} -> {@code "Median"}, {@code "REDSEA"} -> {@code "REDSEA"}.
     */
    public String baseToken() {
        return token.substring(0, token.length() - normalisation().length());
    }

    /**
     * The normalisation suffix this statistic carries: {@code ""}, {@code " Z"} or
     * {@code " RobustZ"}.
     */
    public String normalisation() {
        String best = "";
        for (String norm : NORMALISATIONS) {
            // Longest wins: " RobustZ" also ends with " Z".
            if (!norm.isEmpty() && token.length() > norm.length()
                    && token.regionMatches(true, token.length() - norm.length(), norm, 0, norm.length())
                    && norm.length() > best.length()) {
                best = norm;
            }
        }
        return best;
    }

    /**
     * MIRAGE's standardising normalisation suffixes, in display order.
     * <p>
     * Exposed so a consumer can ask "does a standardised sibling of this column exist?"
     * without re-spelling the suffixes. FlowPath composes candidate keys from these and
     * then checks them against {@link CompartmentCapability} -- it never assumes one is
     * present, because which of them an export carries is decided by MIRAGE's
     * {@code params.quantify_statistics}, and the default list is {@code ['Median']}
     * alone.
     */
    public static List<String> standardisingNormalisations() {
        return List.of(" Z", " RobustZ");
    }

    /**
     * This statistic's sibling carrying {@code normalisation} instead of its own:
     * {@code of("Median").withNormalisation(" Z")} is {@code "Median Z"}, and
     * {@code of("Median RobustZ").withNormalisation("")} is back to {@code "Median"}.
     * <p>
     * Composing rather than enumerating is the point -- MIRAGE builds its names the same
     * way, as {@code base x normalisation}, so a name FlowPath has never seen still
     * composes correctly. Whether the composed column actually <em>exists</em> is a
     * separate question, and only {@link CompartmentCapability} can answer it.
     */
    public Statistic withNormalisation(String normalisation) {
        String norm = normalisation == null ? "" : normalisation;
        return of(baseToken() + norm);
    }

    /**
     * True when MIRAGE has <b>already standardised</b> this column across the cells of one
     * patient — the {@code " Z"} and {@code " RobustZ"} variants.
     * <p>
     * The reason FlowPath cares: its own z-score toggle standardises whatever column is
     * selected, so turning it on over an already-standardised statistic z-scores a
     * z-score. Nothing would throw, and the second pass is close to a no-op on a
     * well-behaved column, which is exactly what makes it hard to notice — the axis would
     * simply be wrong by a rescaling that varies with the filtered population.
     * <p>
     * Note the two are not the same number even in principle: MIRAGE standardises across
     * every cell of a patient, FlowPath across the cells currently loaded and filtered.
     */
    public boolean isStandardised() {
        return !normalisation().isEmpty();
    }

    /** True if this is one of the three statistics FlowPath ships an opinion about. */
    public boolean isKnown() {
        return KNOWN.contains(this);
    }

    /**
     * The statistics FlowPath has an opinion about, in display order (Mean, Median, Sum) —
     * the order the closed enum declared, kept so the selectors do not silently reorder.
     */
    public static List<Statistic> known() {
        return KNOWN;
    }

    /**
     * Order a discovered set for display: the {@linkplain #known() known} statistics first
     * in their canonical order, then anything else in the order the set iterates.
     * <p>
     * One implementation, because there were three — the gate axis, the feature picker's
     * list and the feature picker's default each looped {@code Statistic.values()} and
     * would each have needed the same "and then the unknown ones" clause bolted on.
     */
    public static List<Statistic> orderKnownFirst(Collection<Statistic> available) {
        List<Statistic> out = new ArrayList<>();
        if (available == null) return out;

        // Bases in display order: the ones FlowPath has an opinion about, then whatever
        // else turned up, in the order the export presented it.
        List<String> bases = new ArrayList<>();
        for (Statistic s : KNOWN) bases.add(s.baseToken());
        for (Statistic s : available) {
            if (s == null) continue;
            String base = s.baseToken();
            if (!containsIgnoreCase(bases, base)) bases.add(base);
        }

        // A base statistic's variants stay adjacent, matching how MIRAGE groups them.
        for (String base : bases) {
            for (String norm : NORMALISATIONS) {
                for (Statistic s : available) {
                    if (s != null && s.token.equalsIgnoreCase(base + norm) && !out.contains(s)) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> haystack, String needle) {
        for (String s : haystack) {
            if (s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    /** Token used inside the measurement key, e.g. {@code "Mean"}. */
    public String token() {
        return token;
    }

    /** Human-readable name for UI (same as the token). */
    public String displayName() {
        return token;
    }

    /**
     * The statistic the <em>bare</em> marker column holds — not the statistic a new gate
     * should default to.
     * <p>
     * {@code CellIndex} treats {@code (WHOLE_CELL, MEAN)} as the one selection that
     * resolves to the bare {@code "CD3"} column, which MIRAGE defines as the whole-cell
     * mean. That is why this is {@link #MEAN} while the gate model's own field default is
     * {@link #MEDIAN}: the two answer different questions. Gates pick their statistic from
     * {@link CompartmentCapability} via {@code GateEditorPane.chooseStatistic}.
     */
    public static Statistic defaultStatistic() {
        return MEAN;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Statistic other)) return false;
        return token.equalsIgnoreCase(other.token);
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
