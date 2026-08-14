package qupath.ext.flowpath.model;

import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects which per-compartment measurements a loaded set of cells actually
 * carries, by scanning measurement keys for the
 * {@code "<marker>: <Compartment>: <Stat>"} pattern.
 * <p>
 * Drives the UI: when {@link #isRich()} is false the GeoJSON is "legacy" and the
 * compartment/statistic selectors are disabled and pinned to whole-cell / mean.
 * Per-marker queries let the editor offer only the combinations that exist for a
 * given channel.
 *
 * <h2>Pairs, not axes (critical invariant)</h2>
 * A marker's capability is stored as a set of <b>{@link Pair (compartment, statistic)}
 * pairs</b>, never as two independent sets. It used to be two —
 * {@code Map<marker, EnumSet<Compartment>>} and {@code Map<marker, EnumSet<Statistic>>} —
 * built from the same parsed key and then queried independently by
 * {@code resolveCompartment} and {@code resolveStatistic}. That made the set of offerable
 * signals the <b>Cartesian product of the two projections</b>, which is a superset of the
 * pairs the file actually holds: a marker carrying {@code Cell: REDSEA} and
 * {@code Nucleus: Median} advertised {@code Nucleus × REDSEA}, which resolves to a key
 * that is not in the file, so every cell reads NaN.
 * <p>
 * The bug was unobservable only because MIRAGE emitted Median/Mean/Sum for every
 * compartment together, making product equal to original. A Cell-only statistic breaks
 * that coincidence permanently: MIRAGE's REDSEA compensation is whole-cell only by
 * nature — it is a membrane correction whose algebra subtracts a fraction of a
 * neighbour's integrated boundary counts, so it has no nucleus/cytoplasm decomposition
 * to emit. {@link #resolvePair} is now the single
 * resolution entry point and can only ever return a combination the scan actually saw;
 * {@link #compartmentsFor} and {@link #statisticsFor} are <em>derived</em> projections
 * kept for display, not for resolution.
 */
public final class CompartmentCapability {

    /**
     * One (compartment, statistic) combination a marker actually carries. The unit the
     * capability is stored in — see the class javadoc on why the two axes cannot be
     * stored separately.
     */
    public record Pair(Compartment compartment, Statistic statistic) {}

    /** Insertion-ordered so discovered statistics keep the order the file presented them. */
    private final Map<String, LinkedHashSet<Pair>> pairs = new LinkedHashMap<>();
    private boolean rich = false;

    private CompartmentCapability() {}

    /** An empty capability (legacy: nothing rich). */
    public static CompartmentCapability empty() {
        return new CompartmentCapability();
    }

    /** Build from a collection of measurement keys. */
    public static CompartmentCapability fromKeys(Collection<String> keys) {
        CompartmentCapability cap = new CompartmentCapability();
        if (keys == null) return cap;
        for (String key : keys) {
            MeasurementKeys.Parsed parsed = MeasurementKeys.parse(key);
            if (parsed == null) continue;
            cap.rich = true;
            cap.pairs
                    .computeIfAbsent(parsed.marker(), k -> new LinkedHashSet<>())
                    .add(new Pair(parsed.compartment(), parsed.statistic()));
        }
        return cap;
    }

    /**
     * How many detections to inspect by default. Both halves of FlowPath must use the
     * same depth: gating scanned 100 cells and the UMAP 20, so a marker whose compartment
     * keys first appeared past cell 20 was offered in the gate editor and silently
     * downgraded to whole-cell in the UMAP's feature selection.
     */
    public static final int DEFAULT_SAMPLE_SIZE = 100;

    /** Scan the default number of detections. */
    public static CompartmentCapability scan(Collection<PathObject> detections) {
        return scan(detections, DEFAULT_SAMPLE_SIZE);
    }

    /** Scan up to {@code sampleLimit} detections' measurement keys. */
    public static CompartmentCapability scan(Collection<PathObject> detections, int sampleLimit) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        int sampled = 0;
        for (PathObject obj : detections) {
            try {
                var m = obj.getMeasurements();
                if (m != null) keys.addAll(m.keySet());
            } catch (Exception ignored) {
            }
            if (++sampled >= sampleLimit) break;
        }
        return fromKeys(keys);
    }

    /** True if any per-compartment measurement key was found (rich GeoJSON). */
    public boolean isRich() {
        return rich;
    }

    /** True if the given marker has per-compartment keys. */
    public boolean hasCompartments(String marker) {
        return !pairsFor(marker).isEmpty();
    }

    /**
     * Every (compartment, statistic) combination this export carries for {@code marker},
     * in discovery order. The authoritative answer — the two projections below are
     * derived from this.
     */
    public Set<Pair> pairsFor(String marker) {
        LinkedHashSet<Pair> set = pairs.get(MeasurementKeys.stripLayerPrefix(marker));
        return set == null ? Set.of() : new LinkedHashSet<>(set);
    }

    /**
     * True if this export carries exactly this combination for this marker.
     * <p>
     * The question the UI should ask before offering a signal. Asking it of the two
     * projections separately is what advertised combinations that were not in the file.
     */
    public boolean offers(String marker, Compartment compartment, Statistic statistic) {
        if (compartment == null || statistic == null) return false;
        return pairsFor(marker).contains(new Pair(compartment, statistic));
    }

    /**
     * Available compartments for a marker, in canonical order (empty if none / legacy).
     * <p>
     * <b>A projection, for populating a selector.</b> Not every compartment here is valid
     * with every statistic from {@link #statisticsFor} — ask {@link #offers} or resolve
     * through {@link #resolvePair}.
     */
    public Set<Compartment> compartmentsFor(String marker) {
        Set<Pair> ps = pairsFor(marker);
        LinkedHashSet<Compartment> out = new LinkedHashSet<>();
        for (Compartment c : Compartment.values()) {
            for (Pair p : ps) {
                if (p.compartment() == c) { out.add(c); break; }
            }
        }
        return out;
    }

    /**
     * Available statistics for a marker, known ones first (empty if none / legacy).
     * <p>
     * <b>A projection</b> — see {@link #compartmentsFor}.
     */
    public Set<Statistic> statisticsFor(String marker) {
        LinkedHashSet<Statistic> seen = new LinkedHashSet<>();
        for (Pair p : pairsFor(marker)) seen.add(p.statistic());
        return new LinkedHashSet<>(Statistic.orderKnownFirst(seen));
    }

    /** The statistics this export carries for {@code marker} <em>in</em> {@code compartment}. */
    public Set<Statistic> statisticsFor(String marker, Compartment compartment) {
        LinkedHashSet<Statistic> seen = new LinkedHashSet<>();
        for (Pair p : pairsFor(marker)) {
            if (p.compartment() == compartment) seen.add(p.statistic());
        }
        return new LinkedHashSet<>(Statistic.orderKnownFirst(seen));
    }

    /**
     * The (compartment, statistic) to read {@code marker} with, given what this export
     * actually carries — <b>the single resolution entry point</b>.
     * <p>
     * Guarantees the returned pair is one the scan saw, or the legacy
     * {@code (WHOLE_CELL, MEAN)} bare-column signal when the marker has no structured
     * keys at all. Keeps both preferences when the export has that exact combination, so
     * a configured gate is never silently moved.
     * <p>
     * When the exact pair is absent it keeps the <b>compartment</b> and re-picks the
     * statistic, because the compartment is the more structural choice and is usually the
     * one the user just changed. Statistic preference within a compartment is: the
     * requested one, then Median (MIRAGE's default, always present), then Mean, then
     * whatever exists.
     */
    public Pair resolvePair(String marker, Compartment preferredCompartment, Statistic preferredStatistic) {
        List<Pair> available = new ArrayList<>(pairsFor(marker));
        if (available.isEmpty()) {
            // Legacy channel with no structured keys: the bare column IS the whole-cell mean.
            return new Pair(Compartment.WHOLE_CELL, Statistic.MEAN);
        }
        if (preferredCompartment != null && preferredStatistic != null) {
            Pair exact = new Pair(preferredCompartment, preferredStatistic);
            if (available.contains(exact)) return exact;
        }
        Compartment compartment = chooseCompartment(available, preferredCompartment);
        return new Pair(compartment, chooseStatistic(available, compartment, preferredStatistic));
    }

    private static Compartment chooseCompartment(List<Pair> available, Compartment preferred) {
        if (preferred != null) {
            for (Pair p : available) if (p.compartment() == preferred) return preferred;
        }
        for (Pair p : available) if (p.compartment() == Compartment.WHOLE_CELL) return Compartment.WHOLE_CELL;
        return available.get(available.size() - 1).compartment();
    }

    private static Statistic chooseStatistic(List<Pair> available, Compartment compartment, Statistic preferred) {
        List<Statistic> inCompartment = new ArrayList<>();
        for (Pair p : available) {
            if (p.compartment() == compartment) inCompartment.add(p.statistic());
        }
        if (preferred != null && inCompartment.contains(preferred)) return preferred;
        if (inCompartment.contains(Statistic.MEDIAN)) return Statistic.MEDIAN;
        if (inCompartment.contains(Statistic.MEAN)) return Statistic.MEAN;
        return inCompartment.get(0);
    }

    /**
     * The compartment to read {@code marker} in. Convenience projection of
     * {@link #resolvePair}; prefer the pair form, which cannot return a combination the
     * file lacks.
     */
    public Compartment resolveCompartment(String marker, Compartment preferred) {
        return resolvePair(marker, preferred, null).compartment();
    }

    /**
     * The statistic to read {@code marker} with. Convenience projection of
     * {@link #resolvePair}.
     * <p>
     * The one rule that matters: never return a statistic this export lacks. MIRAGE's
     * default compartment quantification emits {@code Median} only — {@code Mean} and
     * {@code Sum} appear only when {@code --quantify_statistics} names them — so falling
     * back to a hardcoded Mean resolves the column to a measurement key that is not in
     * the file, and every cell reads NaN. Mean is the answer only for a legacy channel
     * with no structured statistics at all, whose bare column <em>is</em> the whole-cell
     * mean.
     */
    public Statistic resolveStatistic(String marker, Statistic preferred) {
        return resolvePair(marker, null, preferred).statistic();
    }
}
