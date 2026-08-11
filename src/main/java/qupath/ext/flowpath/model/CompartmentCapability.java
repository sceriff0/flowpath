package qupath.ext.flowpath.model;

import qupath.lib.objects.PathObject;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Detects which per-compartment measurements a loaded set of cells actually
 * carries, by scanning measurement keys for the
 * {@code "<marker>: <Compartment>: <Stat>"} pattern.
 * <p>
 * Drives the UI: when {@link #isRich()} is false the GeoJSON is "legacy" and the
 * compartment/statistic selectors are disabled and pinned to whole-cell / mean.
 * Per-marker queries let the editor offer only the compartments/statistics that
 * exist for a given channel.
 */
public final class CompartmentCapability {

    private final Map<String, EnumSet<Compartment>> compartments = new HashMap<>();
    private final Map<String, EnumSet<Statistic>> statistics = new HashMap<>();
    private boolean rich = false;

    private CompartmentCapability() {}

    /** An empty capability (legacy: nothing rich). */
    public static CompartmentCapability empty() {
        return new CompartmentCapability();
    }

    /** Build from a collection of measurement keys. */
    public static CompartmentCapability fromKeys(Collection<String> keys) {
        CompartmentCapability cap = new CompartmentCapability();
        for (String key : keys) {
            MeasurementKeys.Parsed parsed = MeasurementKeys.parse(key);
            if (parsed == null) continue;
            cap.rich = true;
            cap.compartments
                    .computeIfAbsent(parsed.marker(), k -> EnumSet.noneOf(Compartment.class))
                    .add(parsed.compartment());
            cap.statistics
                    .computeIfAbsent(parsed.marker(), k -> EnumSet.noneOf(Statistic.class))
                    .add(parsed.statistic());
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
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
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
        Set<Compartment> set = compartments.get(MeasurementKeys.stripLayerPrefix(marker));
        return set != null && !set.isEmpty();
    }

    /** Available compartments for a marker (empty if none / legacy). */
    public Set<Compartment> compartmentsFor(String marker) {
        EnumSet<Compartment> set = compartments.get(MeasurementKeys.stripLayerPrefix(marker));
        return set == null ? EnumSet.noneOf(Compartment.class) : EnumSet.copyOf(set);
    }

    /** Available statistics for a marker (empty if none / legacy). */
    public Set<Statistic> statisticsFor(String marker) {
        EnumSet<Statistic> set = statistics.get(MeasurementKeys.stripLayerPrefix(marker));
        return set == null ? EnumSet.noneOf(Statistic.class) : EnumSet.copyOf(set);
    }

    /**
     * The compartment to read {@code marker} in, given what this export actually carries.
     * <p>
     * Keeps {@code preferred} when the export has it, so a configured gate is never
     * silently moved. Otherwise prefers whole-cell, then whatever exists. A channel
     * quantified without a nuclear mask has only {@code Cell} and must land there.
     */
    public Compartment resolveCompartment(String marker, Compartment preferred) {
        java.util.List<Compartment> available =
                new java.util.ArrayList<>(compartmentsFor(marker));
        if (available.isEmpty()) return Compartment.WHOLE_CELL;
        if (preferred != null && available.contains(preferred)) return preferred;
        if (available.contains(Compartment.WHOLE_CELL)) return Compartment.WHOLE_CELL;
        return available.get(available.size() - 1);
    }

    /**
     * The statistic to read {@code marker} with, given what this export actually carries.
     * <p>
     * The one rule that matters: never return a statistic this export lacks. MIRAGE's
     * default compartment quantification emits {@code Median} only — {@code Mean} and
     * {@code Sum} are {@code --expanded_quantification} — so falling back to a hardcoded
     * Mean resolves the column to a measurement key that is not in the file, and every
     * cell reads NaN. Prefers the current selection, then Median, then Mean, then
     * whatever exists; Mean is the answer only for a legacy channel with no structured
     * statistics at all, whose bare column <em>is</em> the whole-cell mean.
     */
    public Statistic resolveStatistic(String marker, Statistic preferred) {
        java.util.List<Statistic> available = new java.util.ArrayList<>(statisticsFor(marker));
        if (available.isEmpty()) return Statistic.MEAN;
        if (preferred != null && available.contains(preferred)) return preferred;
        if (available.contains(Statistic.MEDIAN)) return Statistic.MEDIAN;
        if (available.contains(Statistic.MEAN)) return Statistic.MEAN;
        return available.get(0);
    }
}
