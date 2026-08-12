package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-marker feature selection that drives which measurement key feeds the UMAP
 * matrix: the chosen {@link Compartment} and {@link Statistic}, plus whether the
 * marker is included in the embedding at all.
 * <p>
 * The whole selection is persisted in an {@code ImageData} property as a compact
 * one-entry-per-line string (see {@link #serialize}/{@link #deserialize}). Legacy
 * images (no rich keys, no stored property) load with whole-cell / mean defaults
 * via {@link #defaultFor}.
 */
public final class MarkerSelection {

    /** Selection for a single marker. */
    public record Entry(Compartment compartment, Statistic statistic, boolean included) {
        public Entry {
            if (compartment == null) compartment = Compartment.defaultCompartment();
            if (statistic == null) statistic = Statistic.defaultStatistic();
        }

        public Entry withCompartment(Compartment c) { return new Entry(c, statistic, included); }
        public Entry withStatistic(Statistic s) { return new Entry(compartment, s, included); }
        public Entry withIncluded(boolean inc) { return new Entry(compartment, statistic, inc); }
    }

    /** The default entry: whole-cell mean, included. */
    public static Entry defaultEntry() {
        return new Entry(Compartment.defaultCompartment(), Statistic.defaultStatistic(), true);
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public MarkerSelection() {}

    /** Get the entry for a marker, or the whole-cell/mean default if unset. */
    public Entry entryFor(String marker) {
        Entry e = entries.get(marker);
        return e != null ? e : defaultEntry();
    }

    /** Set the entry for a marker. */
    public void put(String marker, Entry entry) {
        entries.put(marker, entry == null ? defaultEntry() : entry);
    }

    /** True if this marker is included in the UMAP matrix (default: true). */
    public boolean isIncluded(String marker) {
        return entryFor(marker).included();
    }

    /** Compartment chosen for a marker (default: whole-cell). */
    public Compartment compartmentFor(String marker) {
        return entryFor(marker).compartment();
    }

    /** Statistic chosen for a marker (default: mean). */
    public Statistic statisticFor(String marker) {
        return entryFor(marker).statistic();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Build a default selection (whole-cell mean, all included) for the given
     * markers. Used for legacy images so every marker has a stable, included entry.
     */
    public static MarkerSelection defaultFor(List<String> markers) {
        MarkerSelection sel = new MarkerSelection();
        for (String m : markers) sel.put(m, defaultEntry());
        return sel;
    }

    // --- Persistence ---------------------------------------------------------
    //
    // Format: one line per marker, fields tab-separated:
    //   <marker>\t<compartmentToken>\t<statisticToken>\t<0|1 included>
    // Tabs/newlines never appear in marker names or tokens, so no escaping is
    // needed. The leading "qumap-markersel:v1" tag versions the payload so a
    // future format change can be detected; unknown/garbled lines are skipped,
    // which is how a v1/legacy property degrades to whole-cell/mean defaults.

    private static final String VERSION_TAG = "qumap-markersel:v1";

    /** Serialize to the compact persistence string. */
    public String serialize() {
        StringBuilder sb = new StringBuilder(VERSION_TAG);
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry v = e.getValue();
            sb.append('\n')
                    .append(e.getKey()).append('\t')
                    .append(v.compartment().token()).append('\t')
                    .append(v.statistic().token()).append('\t')
                    .append(v.included() ? '1' : '0');
        }
        return sb.toString();
    }

    /**
     * Parse a persistence string back into a selection. Returns an empty
     * selection for null/blank/unrecognised payloads (legacy fallback). Lines
     * that fail to parse are skipped rather than aborting the whole load.
     */
    public static MarkerSelection deserialize(String payload) {
        MarkerSelection sel = new MarkerSelection();
        if (payload == null || payload.isBlank()) return sel;
        String[] lines = payload.split("\n");
        if (lines.length == 0 || !VERSION_TAG.equals(lines[0].trim())) return sel;
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split("\t", -1);
            if (parts.length != 4) continue;
            String marker = parts[0];
            Compartment c = Compartment.fromToken(parts[1]);
            Statistic s = Statistic.fromToken(parts[2]);
            if (marker.isEmpty() || c == null || s == null) continue;
            boolean included = !"0".equals(parts[3]);
            sel.put(marker, new Entry(c, s, included));
        }
        return sel;
    }

    /**
     * An independent selection carrying the same entries.
     * <p>
     * {@link Entry} is an immutable record, so this is one map copy and nothing deeper.
     * It is what lets {@code UmapSession.selection()} hand out a value rather than a
     * handle on its own state: a thin map of records is not a hot-path allocation, and no
     * reader of it wants to write.
     */
    public MarkerSelection copy() {
        MarkerSelection out = new MarkerSelection();
        out.entries.putAll(entries);
        return out;
    }

    /** Marker names that currently have an explicit entry (insertion order). */
    public List<String> markers() {
        return new ArrayList<>(entries.keySet());
    }
}
