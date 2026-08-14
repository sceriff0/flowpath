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
 * always emits</b> for every compartment it quantifies; {@link #MEAN} and
 * {@link #SUM} appear only with {@code --expanded_quantification}. Which of
 * them a given export actually carries must be read from
 * {@link CompartmentCapability}, never assumed — a default-quantification export
 * has no Mean column, and pinning a gate axis to one resolves it to a measurement
 * key that is not in the file, so every cell reads NaN.
 *
 * <h2>Open vocabulary</h2>
 * This is deliberately <b>not</b> an enum. MIRAGE's statistic list is extensible
 * (MIRAGE's {@code feat/redsea-compensation} adds two at once, {@code "REDSEA Sum"} and
 * {@code "REDSEA Mean"}), and a closed enum here made FlowPath unable to see a column it
 * had no name for: the key failed to parse, then
 * {@code MeasurementKeys.collapseToBaseMarkers} re-absorbed the whole unparsed string as
 * a marker, so the panel grew a phantom row spelled {@code "CD3: Cell: REDSEA Sum"} next
 * to the real {@code CD3}. Note the statistic token contains a space — one more reason
 * the old {@code Compartment × Statistic} suffix loop could never have been extended by
 * adding names to it. FlowPath now discovers the
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
        for (Statistic s : KNOWN) {
            if (available.contains(s)) out.add(s);
        }
        for (Statistic s : available) {
            if (s != null && !KNOWN.contains(s)) out.add(s);
        }
        return out;
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
