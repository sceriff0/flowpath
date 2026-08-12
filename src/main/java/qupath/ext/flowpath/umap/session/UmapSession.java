package qupath.ext.flowpath.umap.session;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestOptions;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasurementKeys;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.engine.EmbeddingFeatures;
import qupath.ext.flowpath.umap.model.PopulationTag;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything the UMAP view <em>knows</em>, separated from everything it <em>shows</em>.
 * <p>
 * {@code UmapPane} used to hold the cell index, the gating snapshot, the feature
 * selection, the phenotype colours, the gate mask and the population tags as private
 * fields interleaved with 1900 lines of widget construction, which made every rule in
 * the list below reachable only through a live JavaFX toolkit. This class owns that
 * derived state and the rules that govern it; the pane keeps the widgets, the layout
 * and the event wiring. {@code UiStateController} is the precedent — it owns what a
 * state <em>means</em> for widget enablement while the pane decides which state to
 * enter, and this owns what the data <em>means</em> while the pane decides when to ask.
 *
 * <h2>The index/snapshot invariant</h2>
 * A session is either standalone ({@link #snapshot()} {@code == null}, colours read
 * back from {@link PathClass}) or driven by the gating tree. While it is driven by the
 * gating tree the following must hold at every observable point:
 * <pre>{@code
 *   snapshot() == null || snapshot().index() == index()
 * }</pre>
 * i.e. the session never holds a {@link CellIndex} its snapshot does not name. This is
 * what makes the recolour-vs-invalidate decision in {@link #adopt} trustworthy: it
 * compares the incoming snapshot against the index the session is <em>actually</em>
 * working with rather than against a remembered one that may since have been replaced.
 * {@link #assertIndexInvariant()} states it; the tests assert it after every mutator.
 *
 * <h2>Threading</h2>
 * Not thread-safe, and deliberately so: every mutator is called from the FX thread,
 * with the expensive work ({@link CellIndex#build}, {@link MarkerStats#compute}) done
 * on a background thread by the caller and handed back through
 * {@link #installIndex}/{@link #installRebuiltIndex}. {@link #beginIndexBuild()} and
 * {@link #isCurrentBuild(int)} are the generation guard those background threads use,
 * and they are the only members touched off the FX thread.
 */
public final class UmapSession {

    /** Packed RGB for cells with no phenotype. */
    public static final int UNCLASSIFIED_RGB = 0x808080;
    /** Packed RGB for cells outside the current polygon gate. */
    public static final int UNFOCUSED_RGB = 0x505050;
    /** Packed RGB for cells the quality/ROI filters removed, when they are still drawn. */
    public static final int FILTERED_RGB = 0x3A3A3A;

    /** Placeholder entry in the marker dropdown meaning "colour by phenotype, not expression". */
    public static final String NO_MARKER = "-- none --";

    /** ImageData property key under which the per-marker selection is persisted. */
    public static final String SELECTION_PROPERTY = "qumap.markerSelection";

    // --- Data ---
    private CellIndex cellIndex;
    private MarkerStats markerStats;
    private PhenotypeSnapshot snapshot;

    // --- Feature selection ---
    private List<String> markers = new ArrayList<>();
    private CompartmentCapability capability = CompartmentCapability.empty();
    private MarkerSelection selection = new MarkerSelection();

    // --- Derived view state ---
    private final Set<String> hiddenPhenotypes = new LinkedHashSet<>();
    private final List<PopulationTag> populationTags = new ArrayList<>();
    private boolean[] gateMask;
    private int[] baseColors;

    /**
     * Guards the index-building paths against each other. Both run off the FX thread;
     * without this, rapid edits (toggling the ROI filter, or editing several feature
     * rows) leave whichever thread finishes last as the winner rather than whichever
     * request the user made last.
     */
    private final AtomicInteger indexGeneration = new AtomicInteger();

    /** Generation guard so a superseded gate computation cannot apply its result. */
    private final AtomicInteger gateGeneration = new AtomicInteger();

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public CellIndex index() { return cellIndex; }

    public MarkerStats stats() { return markerStats; }

    public PhenotypeSnapshot snapshot() { return snapshot; }

    /** {@code true} when this session is driven by the gating tree rather than the hierarchy. */
    public boolean isSnapshotMode() { return snapshot != null; }

    public List<String> markers() { return markers; }

    public CompartmentCapability capability() { return capability; }

    public MarkerSelection selection() { return selection; }

    public List<PopulationTag> tags() { return populationTags; }

    public Set<String> hiddenPhenotypes() { return hiddenPhenotypes; }

    public boolean[] gateMask() { return gateMask; }

    // ------------------------------------------------------------------
    // Generation guards
    // ------------------------------------------------------------------

    /** Claim the newest index build; the returned token is passed back to {@link #isCurrentBuild}. */
    public int beginIndexBuild() {
        gateGeneration.incrementAndGet();
        return indexGeneration.incrementAndGet();
    }

    /** {@code false} when a newer build superseded the one that owns {@code token}. */
    public boolean isCurrentBuild(int token) {
        return token == indexGeneration.get();
    }

    /** Claim the newest gate computation; the returned token is passed back to {@link #isCurrentGate}. */
    public int beginGateComputation() {
        return gateGeneration.incrementAndGet();
    }

    /** {@code false} when a newer gate computation superseded the one that owns {@code token}. */
    public boolean isCurrentGate(int token) {
        return token == gateGeneration.get();
    }

    // ------------------------------------------------------------------
    // Snapshot handoff
    // ------------------------------------------------------------------

    /** What {@link #adopt} decided the incoming snapshot means for the embedding. */
    public enum Adoption {
        /** {@code null} came in: the session leaves snapshot mode and runs off the hierarchy. */
        DETACHED,
        /** Same cells, new labels: keep the embedding, re-derive colours. */
        RECOLOUR,
        /** Different cells: everything derived from the old index is stale. */
        REBUILD
    }

    /**
     * Adopt a phenotyping produced by the gating tree.
     * <p>
     * Editing a gate does not rebuild the cell index — the same {@link CellIndex} is
     * re-walked with new thresholds — so when the incoming snapshot covers the cells the
     * session is already holding, the UMAP coordinates remain valid and only the colours
     * have changed. Recomputing a multi-minute embedding because the user nudged a
     * threshold would make the two halves unusable together.
     *
     * @param incoming the new phenotyping; {@code null} detaches snapshot mode
     * @return what the caller must do to the view
     */
    public Adoption adopt(PhenotypeSnapshot incoming) {
        if (incoming == null) {
            snapshot = null;
            return Adoption.DETACHED;
        }

        // Ask the incoming snapshot whether it covers the cells this session is ACTUALLY
        // holding. Comparing against the snapshot we happen to remember (the old
        // `snapshot.index() == incoming.index()`) is falsifiable: a feature rebuild
        // replaces `cellIndex` without the gating pane's index changing, so the
        // remembered pointer keeps answering "same" about an index no longer in use.
        boolean sameCells = snapshot != null && cellIndex != null
                && incoming.describesSameCells(cellIndex);

        if (sameCells) {
            // Re-seat the incoming labels onto the live index so the session cannot end up
            // naming two indices at once. The arrays are unchanged — the gating is what
            // moved, not the cells.
            this.snapshot = incoming.rebindTo(cellIndex, markerStats);
            baseColors = null;   // force a re-derive from the new labels
            return Adoption.RECOLOUR;
        }

        this.snapshot = incoming;

        beginIndexBuild();
        retireCellSet();
        cellIndex = incoming.index();
        markerStats = incoming.stats();
        markers = incoming.markerNames();
        capability = incoming.capability();
        selection = seedSelection(incoming);
        return Adoption.REBUILD;
    }

    /**
     * Leave snapshot mode without adopting a replacement — the active image changed and
     * the gating pane has not re-indexed yet.
     */
    public void detachSnapshot() {
        snapshot = null;
        clearDerivedState();
    }

    /**
     * Drop everything the <em>cells</em> implied: the gate, the cached colours and the
     * population tags, all of which are positional against the cell set being replaced.
     * <p>
     * The hidden-phenotype set deliberately survives. It is keyed by phenotype
     * <em>name</em>, not by index, so re-gating or re-resolving the same slide should not
     * silently un-hide the population the user pushed out of the way — whereas a mask or
     * a colour array from the previous cell set would land on the wrong points.
     */
    public void retireCellSet() {
        gateMask = null;
        baseColors = null;
        populationTags.clear();
    }

    /**
     * {@link #retireCellSet()} plus the hidden set — used when the <em>image</em> changes,
     * where the phenotype names themselves stop meaning anything.
     */
    public void clearDerivedState() {
        retireCellSet();
        hiddenPhenotypes.clear();
    }

    // ------------------------------------------------------------------
    // Index lifecycle
    // ------------------------------------------------------------------

    /**
     * Forget the index and everything derived from it (standalone reload, or no image).
     * <p>
     * The snapshot goes with it. A snapshot names a cell set, and this discards the cell
     * set — keeping it would leave the session holding labels positional against an index
     * that no longer exists, which is the drift the whole class is built to prevent.
     */
    public void clearIndex() {
        snapshot = null;
        cellIndex = null;
        markerStats = null;
        markers = new ArrayList<>();
        capability = CompartmentCapability.empty();
        selection = new MarkerSelection();
        retireCellSet();
    }

    /**
     * Install a freshly built index for the standalone (non-snapshot) path.
     */
    public void installIndex(CellIndex index, MarkerStats stats, List<String> markers,
                             CompartmentCapability capability, MarkerSelection selection) {
        // The standalone path discovers its own cell set from the hierarchy, which is
        // precisely what a snapshot forbids. Reaching here in snapshot mode would seat
        // the session on an index the snapshot does not describe.
        if (snapshot != null) {
            throw new IllegalStateException(
                    "installIndex is the standalone path and must not run while a gating "
                            + "snapshot owns the cell set — use installRebuiltIndex, which "
                            + "reconciles the snapshot, or detach first.");
        }
        this.cellIndex = Objects.requireNonNull(index, "index");
        this.markerStats = stats;
        this.markers = List.copyOf(markers);
        this.capability = capability;
        this.selection = selection;
        this.baseColors = null;
    }

    /**
     * Install an index rebuilt from the <em>same</em> cells at a different feature
     * resolution — what the feature picker produces.
     */
    public void installRebuiltIndex(CellIndex rebuilt, MarkerStats rebuiltStats) {
        Objects.requireNonNull(rebuilt, "rebuilt");
        // Reconcile FIRST, so a rebuild that quietly changed the cell set throws before
        // anything is mutated and the session is never left half-migrated. In snapshot
        // mode this is also the only thing standing between a same-size-different-cells
        // rebuild and painting the old phenotypes onto the new cells: the snapshot's own
        // length check cannot see that, because the lengths still agree.
        PhenotypeSnapshot reseated = snapshot == null ? null : snapshot.rebindTo(rebuilt, rebuiltStats);
        this.snapshot = reseated;
        this.cellIndex = rebuilt;
        this.markerStats = rebuiltStats;
        this.baseColors = null;
    }

    /**
     * The detections a feature rebuild must re-index.
     * <p>
     * In snapshot mode the cell set is the gating pane's, already narrowed by its quality
     * and annotation filters; re-querying the hierarchy would widen it back to the whole
     * slide and break the positional alignment every snapshot array depends on. Returns
     * {@code null} when the caller should collect detections itself.
     */
    public List<PathObject> detectionsForRebuild() {
        return snapshot == null ? null : List.of(snapshot.index().getObjects());
    }

    /**
     * The invariant this class exists to make statable: the session never holds a
     * {@link CellIndex} its snapshot does not name.
     *
     * @throws IllegalStateException when the two have drifted apart
     */
    public void assertIndexInvariant() {
        if (snapshot != null && snapshot.index() != cellIndex) {
            throw new IllegalStateException(
                    "Session index and snapshot index have diverged: the snapshot's per-cell "
                            + "arrays are positional against an index this session no longer holds.");
        }
    }

    // ------------------------------------------------------------------
    // Feature selection
    // ------------------------------------------------------------------

    /**
     * The initial feature selection for a snapshot: the markers the user actually gated
     * on, in the compartment and statistic they gated them in.
     * <p>
     * Defaulting to the gated panel rather than to every channel on the slide is the
     * single biggest usability difference between the fused view and the old standalone
     * one. A 40-plex image opened cold offers 40 checkboxes and no guidance; opened from
     * a gate tree it offers the 8 markers that define the phenotypes on screen, already
     * ticked. Ungated markers stay available in the picker, just unticked.
     * <p>
     * <b>Below {@link EmbeddingFeatures#MINIMUM_FEATURES} the seeding does not happen at
     * all.</b> A single {@code ThresholdGate} on CD45 — the ordinary first gate anyone
     * draws — gates exactly one marker, and seeding it faithfully would produce a
     * selection the embedding refuses: the pane would offer "Ready to embed, 1 marker" and
     * a Run button that cannot succeed. Pre-selection is a convenience, so it yields to
     * the run being possible at all; the user still sees every marker ticked and can
     * narrow them by hand.
     * <p>
     * The count is taken against the <em>panel</em> rather than against the tree. A gate
     * on a marker this image does not carry ticks nothing, so it cannot make up the
     * shortfall either, and counting it would reintroduce the same dead Run button by a
     * longer route.
     */
    public static MarkerSelection seedSelection(PhenotypeSnapshot incoming) {
        MarkerSelection sel = MarkerSelection.defaultFor(incoming.markerNames());
        List<String> gated = incoming.gatedMarkers();
        int seedable = 0;
        for (String marker : incoming.markerNames()) {
            if (gated.contains(marker)) seedable++;
        }
        if (seedable < EmbeddingFeatures.MINIMUM_FEATURES) {
            // Nothing gated yet, or too little to embed — fall back to "everything included".
            return sel;
        }
        MarkerSelection gateSel = incoming.gateSelection();
        for (String marker : incoming.markerNames()) {
            if (gated.contains(marker)) {
                var e = gateSel.entryFor(marker);
                // Only honour a compartment/statistic the image actually carries; otherwise
                // the column resolves to NaN for every cell. The fallback has to come from
                // the capability too — hardcoding Statistic.defaultStatistic() here landed
                // on Mean, which a default (Median-only) MIRAGE export does not contain, so
                // the guard against a NaN column produced one.
                Compartment c = incoming.capability().resolveCompartment(marker, e.compartment());
                Statistic st = incoming.capability().resolveStatistic(marker, e.statistic());
                sel.put(marker, new MarkerSelection.Entry(c, st, true));
            } else {
                sel.put(marker, sel.entryFor(marker).withIncluded(false));
            }
        }
        return sel;
    }

    /**
     * Rehydrate a persisted {@link MarkerSelection} payload. Falls back to whole-cell/mean
     * defaults for every marker when nothing is stored or the payload is legacy or
     * unrecognised; for legacy (non-rich) data the selection is forced to whole-cell mean.
     *
     * @param stored the serialized payload from the image's properties, or {@code null}
     */
    public static MarkerSelection loadSelection(String stored, List<String> markers,
                                                CompartmentCapability cap) {
        MarkerSelection sel = MarkerSelection.defaultFor(markers);
        if (!cap.isRich() || stored == null) return sel;
        MarkerSelection parsed = MarkerSelection.deserialize(stored);
        // Overlay stored entries onto the defaults, but only for markers that still
        // exist and only with compartments/statistics the data actually carries
        // (otherwise keep the default).
        for (String marker : markers) {
            if (!parsed.markers().contains(marker)) continue;
            var e = parsed.entryFor(marker);
            Compartment c = cap.compartmentsFor(marker).contains(e.compartment())
                    ? e.compartment() : Compartment.defaultCompartment();
            Statistic st = cap.statisticsFor(marker).contains(e.statistic())
                    ? e.statistic() : Statistic.defaultStatistic();
            sel.put(marker, new MarkerSelection.Entry(c, st, e.included()));
        }
        return sel;
    }

    /**
     * Work out the marker panel for an image.
     * <p>
     * A delegate. This was a second, independent implementation of marker discovery: it
     * sampled 20 detections where the gating half sampled 100, and it filtered morphology
     * columns with a different list. {@link DetectionIngest} now owns the one rule, and the
     * one sample depth. Kept so callers that only want the panel — and the tests that pin
     * the panel's contents — do not have to build an index to get it.
     *
     * @param channelNames the image server's channel names, in panel order
     * @param detections   the cells to validate against
     */
    public static List<String> discoverMarkerNames(List<String> channelNames,
                                                   Collection<PathObject> detections) {
        return DetectionIngest.read(detections,
                IngestOptions.none().withChannelNames(channelNames)).markerNames();
    }

    /**
     * Collapse structured measurement keys to their base marker, preserving order and
     * de-duplicating. A key like {@code "CD3: Nucleus: Mean"} (optionally with a
     * {@code "[Layer0] "} prefix) collapses to {@code "CD3"}; a bare name like
     * {@code "DAPI"} is kept verbatim (after stripping any layer prefix). This is what
     * makes the marker list show one row per marker rather than one per
     * compartment/statistic combination.
     */
    public static List<String> collapseToBaseMarkers(List<String> names) {
        return MeasurementKeys.collapseToBaseMarkers(names);
    }

    /**
     * The marker to colour by when the first embedding lands and the user has not chosen
     * one: the first gated marker if the gating tree named any, otherwise the first
     * marker on the panel.
     */
    public String preferredMarker() {
        if (markers.isEmpty()) return null;
        if (snapshot != null) {
            for (String m : snapshot.gatedMarkers()) {
                if (markers.contains(m)) return m;
            }
        }
        return markers.get(0);
    }

    // ------------------------------------------------------------------
    // Phenotype colours
    // ------------------------------------------------------------------

    /** The cached, un-shaded per-cell colours, or {@code null} when none have been derived. */
    public int[] baseColors() {
        return baseColors;
    }

    /**
     * Derive the un-shaded per-point colour for every embedded cell and cache it.
     * <p>
     * In snapshot mode the colours come straight from the gate tree, so the UMAP and the
     * gating tree cannot disagree — the same branch colour paints the tree row, the tissue
     * overlay and the embedding. Standalone, this falls back to reading each cell's
     * {@link PathClass}, which is how the view behaved as a separate extension and is
     * still correct for cells classified by something other than FlowPath.
     * <p>
     * The snapshot's arrays are positional against its {@link CellIndex}. The embedding
     * covers every indexed cell (subsampled runs project the remainder rather than
     * dropping it), so the two line up 1:1 — but only while the index is the one the
     * snapshot was taken from. A length mismatch means they have drifted apart, and
     * painting through it would mislabel cells; fall back rather than lie.
     */
    public int[] derivePointColors(PathObject[] objects) {
        int n = objects.length;
        int[] colors = new int[n];
        if (usesGatingColors(n)) {
            String[] labels = snapshot.phenotypes();
            int[] gateColors = snapshot.colors();
            boolean[] excluded = snapshot.excluded();
            for (int i = 0; i < n; i++) {
                if (excluded[i]) {
                    colors[i] = FILTERED_RGB;
                } else if (PhenotypeSnapshot.UNCLASSIFIED.equals(labels[i])) {
                    colors[i] = UNCLASSIFIED_RGB;
                } else {
                    colors[i] = gateColors[i] & 0xFFFFFF;
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                PathClass pc = objects[i].getPathClass();
                // QuPath uses ARGB; keep only the RGB.
                colors[i] = pc != null ? pc.getColor() & 0xFFFFFF : UNCLASSIFIED_RGB;
            }
        }
        baseColors = colors;
        return colors;
    }

    /**
     * {@code true} when the gate tree — rather than {@link PathClass} — is the authority
     * for the colour of {@code pointCount} embedded points.
     */
    public boolean usesGatingColors(int pointCount) {
        return snapshot != null && snapshot.cellCount() == pointCount;
    }

    /**
     * Grey out points outside the gate. Returns {@code colors} unchanged when no gate is
     * active, so the common path allocates nothing.
     * <p>
     * This is what replaced writing a "qUMAP: Unfocused" PathClass onto every outside
     * cell: the same visual result, achieved without touching the user's data.
     */
    public int[] applyGateShading(int[] colors) {
        if (gateMask == null || colors == null) return colors;
        int[] shaded = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            shaded[i] = (i < gateMask.length && gateMask[i]) ? colors[i] : UNFOCUSED_RGB;
        }
        return shaded;
    }

    public void setGateMask(boolean[] mask) {
        this.gateMask = mask;
    }

    /** Hide or show one phenotype in the plot. */
    public void togglePhenotype(String name) {
        if (name == null) return;
        if (!hiddenPhenotypes.remove(name)) hiddenPhenotypes.add(name);
    }

    /**
     * A per-point visibility mask for the hidden phenotype set, or {@code null} when
     * everything is visible — the overwhelmingly common case, which the canvas reads as
     * "draw everything" and which therefore allocates nothing.
     */
    public boolean[] visibilityMask(int pointCount) {
        if (hiddenPhenotypes.isEmpty() || !usesGatingColors(pointCount)) return null;
        String[] labels = snapshot.phenotypes();
        boolean[] visible = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            visible[i] = !hiddenPhenotypes.contains(labels[i]);
        }
        return visible;
    }

    /**
     * A per-point mask for the cells belonging to one phenotype, used to highlight a
     * legend row under the pointer. {@code null} when the phenotype cannot be resolved
     * against the current embedding.
     */
    public boolean[] highlightMask(String phenotype, int pointCount) {
        if (phenotype == null || !usesGatingColors(pointCount)) return null;
        String[] labels = snapshot.phenotypes();
        boolean[] excluded = snapshot.excluded();
        boolean[] mask = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            mask[i] = !excluded[i] && phenotype.equals(labels[i]);
        }
        return mask;
    }

    // ------------------------------------------------------------------
    // Population tagging
    // ------------------------------------------------------------------

    public void addTag(PopulationTag tag) {
        populationTags.add(tag);
    }

    /** Remove the tag with this name, returning it, or {@code null} when there was none. */
    public PopulationTag removeTag(String name) {
        for (PopulationTag tag : populationTags) {
            if (tag.name().equals(name)) {
                populationTags.remove(tag);
                return tag;
            }
        }
        return null;
    }

    /** The tag with this name, or {@code null}. */
    public PopulationTag tag(String name) {
        for (PopulationTag tag : populationTags) {
            if (tag.name().equals(name)) return tag;
        }
        return null;
    }

    /**
     * The class name a cell should carry once tagged: its phenotype plus {@code ": tag"},
     * with any <em>previously applied</em> tag suffix stripped first.
     * <p>
     * The strip uses {@code lastIndexOf} and only fires for a suffix matching a tag this
     * session actually applied, so a phenotype whose own name contains {@code ": "}
     * ("CD3+: CD8+", say) is not silently truncated into a different population.
     */
    public String tagClassName(String currentName, String tagName) {
        String baseName = currentName != null ? currentName : PhenotypeSnapshot.UNCLASSIFIED;
        int tagSep = baseName.lastIndexOf(": ");
        if (tagSep >= 0) {
            String possibleTag = baseName.substring(tagSep + 2);
            if (tag(possibleTag) != null) {
                baseName = baseName.substring(0, tagSep);
            }
        }
        return baseName + ": " + tagName;
    }

    /**
     * The class name a cell should return to once a tag is removed, or {@code null} when
     * it does not carry that tag and must be left alone.
     */
    public static String untagClassName(String currentName, String tagName) {
        if (currentName == null) return null;
        String suffix = ": " + tagName;
        if (!currentName.endsWith(suffix)) return null;
        return currentName.substring(0, currentName.length() - suffix.length());
    }

    /**
     * {@code true} when the population tag masks no longer line up with an embedding of
     * {@code pointCount} points, so the overlays refer to nothing meaningful and must be
     * dropped rather than drawn against mismatched indices.
     */
    public boolean tagsAreStaleFor(int pointCount) {
        return !populationTags.isEmpty() && populationTags.get(0).mask().length != pointCount;
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    /** One-line summary of a snapshot for the status bar. */
    public static String describe(PhenotypeSnapshot s) {
        int populations = s.populations().size();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%,d cells", s.includedCount()));
        int dropped = s.cellCount() - s.includedCount();
        if (dropped > 0) {
            sb.append(String.format(" (%,d filtered out)", dropped));
        }
        if (s.hasPhenotypes()) {
            sb.append(String.format(", %d phenotype%s from %d gate%s",
                    populations, populations == 1 ? "" : "s",
                    s.gateCount(), s.gateCount() == 1 ? "" : "s"));
        } else {
            sb.append(", no gates applied yet");
        }
        if (!s.gatedMarkers().isEmpty()) {
            sb.append(String.format(", %d gated marker%s pre-selected",
                    s.gatedMarkers().size(), s.gatedMarkers().size() == 1 ? "" : "s"));
        }
        return sb.toString();
    }
}
