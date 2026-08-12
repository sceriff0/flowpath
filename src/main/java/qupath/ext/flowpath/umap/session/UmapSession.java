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
import qupath.ext.flowpath.umap.engine.UmapOutcome;
import qupath.ext.flowpath.umap.model.PopulationTag;
import qupath.ext.flowpath.umap.model.UmapResult;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.interfaces.ROI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
 *
 * <h2>The session pushes; nothing pulls</h2>
 * Every mutator here ends by handing {@link #viewState()} to whoever
 * {@link #observe(Consumer) subscribed} — {@code UiStateController} does, in its
 * constructor. There is therefore no {@code sync()} to call and no {@code assertSynced()}
 * to catch the day someone did not, which is what the eight call sites of the latter
 * existed for. The corollary is that state must never leave this class in a form a caller
 * can edit: {@link #tags()} and {@link #hiddenPhenotypes()} are unmodifiable views and the
 * gate mask is not handed out at all, because a caller mutating one of those changes the
 * derived state without the session — and therefore without the panel — ever hearing.
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

    /**
     * The {@link PathClass} name the gating half writes onto cells its quality filter
     * removed. They are never analysis input, whichever half discovered them.
     */
    public static final String EXCLUDED_CLASS = "Excluded";

    // --- Data ---
    private CellIndex cellIndex;
    private MarkerStats markerStats;
    private PhenotypeSnapshot snapshot;

    /**
     * The embedding on screen, or {@code null}. Held here rather than in {@code UmapPane}
     * because every rule that asks "may the user do this?" asks about it, and while it was
     * a pane field those rules could only be answered through a live JavaFX toolkit.
     */
    private UmapResult embedding;

    // --- Run lifecycle ---
    private boolean running;
    private String failure;
    private boolean exporting;

    /**
     * The cell set is stale and a replacement is expected from the gating tree.
     * <p>
     * Set by {@link #detachSnapshot()}, which the pane calls when the active image changes
     * while the gating tree owns the cells. Nulling the snapshot alone does not make the
     * session empty — {@code cellIndex} still holds the <em>previous</em> image's
     * {@link PathObject}s until the gating pane finishes re-indexing, which on a real slide
     * is seconds of background ingest. Without this fact the derivation reads
     * {@code hasCells == true} and offers a Run button that would embed the old image's
     * cells, a viewer push of objects the new hierarchy does not contain, and an annotation
     * filter that would re-index the new image behind the gating pane's back.
     * <p>
     * This is the fact the imperative {@code setState(NO_IMAGE)} used to carry and the
     * session could not express.
     */
    private boolean awaitingSnapshot;

    /**
     * How many feature rebuilds have been started and not yet landed.
     * <p>
     * A counter rather than a flag because rebuilds supersede each other: a superseded one
     * still has to balance its own start, and the newest must keep the panel un-runnable
     * until it lands. Symmetric with {@link #running} — a run in flight forbids editing the
     * inputs, and an edit in flight forbids running, because
     * {@link #installRebuiltIndex} would otherwise seat a new {@link CellIndex} under a
     * compute thread still reading the old one. Locking the controls closes that in one
     * direction only: {@code runUmap} does not bump the build generation, so ticking a
     * marker and immediately clicking Run reaches the same place by the other route.
     */
    private int pendingRebuilds;

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
    // Observation
    // ------------------------------------------------------------------

    /**
     * Who is told when the session changes. Plain {@link Consumer}s of {@link ViewState},
     * so this class still compiles and tests without a JavaFX toolkit.
     */
    private final List<Consumer<ViewState>> observers = new ArrayList<>();

    /**
     * How deep the current mutation is. Observers are told once, when the outermost
     * mutator returns.
     * <p>
     * Not an optimisation. {@link #adopt} reaches {@link #retireCellSet()} at a moment
     * when {@code snapshot} names the incoming cells and {@code cellIndex} still holds
     * the outgoing ones — the one instant in the class's life at which its own
     * index/snapshot invariant is false. Publishing there would make that instant
     * <em>observable</em>, which is exactly what the invariant promises never happens.
     */
    private int mutationDepth;

    /**
     * True while a notification is being delivered.
     * <p>
     * {@link #mutationDepth} is back to zero by then, so without this a subscriber that
     * mutates starts a full nested publish and the outer loop afterwards resumes handing
     * the <em>older</em> state to the subscribers it had not reached yet — the exact
     * staleness this design replaced, reintroduced from the inside. Unreachable with
     * today's two subscribers; a third is precisely the edit this design invites.
     */
    private boolean publishing;

    /**
     * Subscribe to every settled change, and receive the current state immediately.
     * <p>
     * This is what replaced {@code UiStateController.sync()} being <em>called</em>.
     * {@code sync()} was already unable to express a wrong state — it took no argument —
     * but it could still be forgotten, and {@code UmapPane.onUmapResultReady} was the
     * standing proof: it retired the gate and the stale tags <em>after</em>
     * {@code ComputeController} had synced, so a second sync had to be remembered at the
     * end of the handler, and an {@code assertSynced()} had to be sprinkled at the tail
     * of eight others to catch the day someone did not. Now no caller syncs, so no caller
     * can fail to.
     */
    public void observe(Consumer<ViewState> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
        observer.accept(viewState());
    }

    /**
     * Run {@code body} as one mutation and tell the observers once it has settled. A
     * mutator that throws publishes nothing: the depth unwinds, and observers never see
     * a half-applied change.
     */
    private void mutate(Runnable body) {
        mutationDepth++;
        try {
            body.run();
        } finally {
            mutationDepth--;
        }
        publish();
    }

    /**
     * Tell every observer, unless an enclosing mutation is still running.
     * <p>
     * Every subscriber is told even if an earlier one threw — a panel half-updated because
     * a legend blew up is worse than the exception, and the first failure is rethrown once
     * the round is complete so it is still loud.
     */
    private void publish() {
        if (mutationDepth > 0 || observers.isEmpty()) return;
        if (publishing) {
            throw new IllegalStateException(
                    "A subscriber mutated the session while its own notification was still "
                            + "being delivered. The subscribers not yet reached would then "
                            + "be handed the state from BEFORE that mutation. Defer the "
                            + "mutation (Platform.runLater) instead of making it inline.");
        }
        ViewState now = viewState();
        publishing = true;
        RuntimeException firstFailure = null;
        try {
            // Indexed rather than iterated: an observer that subscribes another during its
            // own notification would otherwise take the whole panel down with a
            // ConcurrentModificationException.
            for (int i = 0; i < observers.size(); i++) {
                try {
                    observers.get(i).accept(now);
                } catch (RuntimeException e) {
                    if (firstFailure == null) firstFailure = e;
                }
            }
        } finally {
            publishing = false;
        }
        if (firstFailure != null) throw firstFailure;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public CellIndex index() { return cellIndex; }

    public MarkerStats stats() { return markerStats; }

    public PhenotypeSnapshot snapshot() { return snapshot; }

    /**
     * {@code true} when the gating tree — not this panel's own read of the hierarchy —
     * owns the cell set.
     * <p>
     * Waiting for a snapshot is still snapshot mode. {@code snapshot != null} alone was a
     * second, unrepaired reading of "who owns the cells": during the window between an
     * image change and the gating tree's next push, {@link #detachSnapshot()} has nulled
     * the snapshot but the gating pane is still the owner. Answering "standalone" there
     * let the panel offer its own annotation filter, re-index the new image behind the
     * gating pane's back, and — through {@link #detectionsForRebuild()} — rebuild features
     * from a hierarchy the gating half had not finished reading.
     */
    public boolean isSnapshotMode() { return snapshot != null || awaitingSnapshot; }

    public List<String> markers() { return markers; }

    public CompartmentCapability capability() { return capability; }

    /**
     * The feature picker's state, as a <b>copy</b>. Writes go through
     * {@link #editSelection(String, MarkerSelection.Entry)}.
     * <p>
     * A copy, not a view, because {@link MarkerSelection} has no unmodifiable form and the
     * include flag decides whether Run UMAP is clickable at all —
     * {@code FeatureSelectionPane} used to {@code put} straight into this object, so
     * ticking a marker changed that answer without the session, and therefore the panel,
     * ever finding out. Every selection this session holds is one it built or was handed
     * ({@link #seedSelection}, {@link #loadSelection}, {@code new MarkerSelection()}), so
     * nothing outside is aliased to it and the copy costs one put per marker on no hot
     * path.
     * <p>
     * It also closes a live aliasing hazard: {@code onFeatureSelectionChanged} hands this
     * to a background rebuild thread while the FX thread can still be editing rows into it.
     */
    public MarkerSelection selection() { return selection.copy(); }

    /**
     * One marker's current entry — an immutable record, so this allocates nothing and is
     * what the feature picker reads per row rather than copying the whole selection.
     */
    public MarkerSelection.Entry selectionEntry(String marker) {
        return selection.entryFor(marker);
    }

    /**
     * The population tags, in the order they were applied — an unmodifiable <em>view</em>,
     * not a copy.
     * <p>
     * It used to be the live list, and {@code UmapPane} called {@code tags().clear()} on
     * it directly. That is a change of {@link ViewState} (TAGGED to COMPUTED) made without
     * the session's knowledge, which is precisely what {@link #observe} cannot survive:
     * the observers would never be told. {@link #clearTags()} is the same edit, spelled so
     * the session hears it.
     */
    public List<PopulationTag> tags() { return Collections.unmodifiableList(populationTags); }

    /** The hidden phenotype names — an unmodifiable view; see {@link #tags()}. */
    public Set<String> hiddenPhenotypes() { return Collections.unmodifiableSet(hiddenPhenotypes); }

    /** {@code true} when a polygon gate is closed over the embedding. */
    public boolean hasGate() { return gateMask != null; }

    /** The embedding currently on screen, or {@code null} when there is none. */
    public UmapResult embedding() { return embedding; }

    /** {@code true} while a UMAP run is in flight. */
    public boolean isRunning() { return running; }

    /** Why the last run failed, or {@code null}. */
    public String failure() { return failure; }

    /** {@code true} while the cell set is stale and the gating tree owes this session one. */
    public boolean isAwaitingSnapshot() { return awaitingSnapshot; }

    /** {@code true} while a feature rebuild is in flight and has not been installed. */
    public boolean isRebuildPending() { return pendingRebuilds > 0; }

    /**
     * {@code true} when this session holds cells that describe the image on screen.
     * <p>
     * One rule, two readers: the derivation and the rail's own summary. A stale cell set —
     * held while the gating tree re-indexes after an image change — is deliberately not
     * cells, and the two would otherwise disagree about the same window.
     */
    public boolean hasCells() {
        return !awaitingSnapshot && cellIndex != null && cellIndex.size() > 0;
    }

    /**
     * How many markers the feature picker leaves ticked — asked of the same filter the
     * embedding runs through, so the count the rail promises, the count the empty state
     * quotes and the count that decides whether Run UMAP is clickable are one number.
     */
    public int includedMarkerCount() {
        return EmbeddingFeatures.includedMarkers(markers, selection).size();
    }

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
        /**
         * {@code null} came in: the gating tree has no phenotyping to give, so the session
         * leaves snapshot mode and waits for one — see {@link #detachSnapshot()}. It does
         * <em>not</em> fall back to the cells it happens to be holding; those came from the
         * gating pane and describe whatever it was showing before.
         */
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
        Adoption adoption;
        mutationDepth++;
        try {
            adoption = adoptInternal(incoming);
        } finally {
            mutationDepth--;
        }
        publish();
        return adoption;
    }

    private Adoption adoptInternal(PhenotypeSnapshot incoming) {
        if (incoming == null) {
            // Delegated, not reimplemented. This branch used to null the snapshot and stop,
            // which was identical to detachSnapshot() until detachSnapshot() learnt that the
            // surviving CellIndex belongs to a cell set nobody is looking at any more. Left
            // alone it would be a second, public route back into exactly the state that fix
            // was written to make inexpressible: READY, Run clickable over the previous
            // image's cells, and an annotation filter offering to re-index behind the gating
            // pane's back. One detach, one meaning.
            detachSnapshot();
            return Adoption.DETACHED;
        }
        // Whatever else adoption decides, a snapshot has arrived: the session is no longer
        // waiting for one.
        awaitingSnapshot = false;

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
        mutate(() -> {
            snapshot = null;
            // The index survives on purpose — the pane still needs the old PathObjects until
            // a replacement arrives — so say out loud that it no longer describes anything
            // the user is looking at. Everything derived from it is withheld until then.
            awaitingSnapshot = true;
            clearDerivedState();
        });
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
        mutate(this::retireCellSetNow);
    }

    private void retireCellSetNow() {
        gateMask = null;
        baseColors = null;
        populationTags.clear();
        // The embedding is positional against the cell set too — its coordinate arrays and
        // its PathObject array are indexed the same way the gate mask and the tag masks
        // are. It used to be dropped by three separate `umapResult = null` lines in the
        // pane, one beside each caller of this method; a fourth caller would simply not
        // have had one.
        embedding = null;
        // A failure describes a run over the cells being retired, so it stops meaning
        // anything about the ones arriving.
        failure = null;
    }

    /**
     * {@link #retireCellSet()} plus the hidden set — used when the <em>image</em> changes,
     * where the phenotype names themselves stop meaning anything.
     */
    public void clearDerivedState() {
        mutate(() -> {
            retireCellSetNow();
            hiddenPhenotypes.clear();
        });
    }

    /**
     * Drop the polygon gate, and invalidate any gate computation still in flight so it
     * cannot re-apply the mask this just removed.
     * <p>
     * One method because it was three: {@code onUmapResultReady}, {@code clearPolygon} and
     * {@code applyPopulationTag} each spelled out {@code beginGateComputation()} followed
     * by {@code setGateMask(null)}, and a fourth caller would have had to know that the
     * generation bump is not optional. {@link #setGateMask} now refuses {@code null}, so
     * this is the only way to say it.
     */
    public void retireGate() {
        mutate(() -> {
            gateGeneration.incrementAndGet();
            gateMask = null;
        });
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
        mutate(() -> {
            snapshot = null;
            awaitingSnapshot = false;
            cellIndex = null;
            markerStats = null;
            markers = new ArrayList<>();
            capability = CompartmentCapability.empty();
            selection = new MarkerSelection();
            retireCellSetNow();
        });
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
        Objects.requireNonNull(index, "index");
        mutate(() -> {
            this.cellIndex = index;
            this.awaitingSnapshot = false;
            this.markerStats = stats;
            this.markers = List.copyOf(markers);
            this.capability = capability;
            // Copied on the way in for the same reason selection() copies on the way out:
            // the caller keeps no handle on what this session is deriving Run UMAP from.
            this.selection = selection.copy();
            this.baseColors = null;
        });
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
        mutate(() -> {
            this.snapshot = reseated;
            this.cellIndex = rebuilt;
            this.markerStats = rebuiltStats;
            this.baseColors = null;
        });
    }

    /**
     * The detections a feature rebuild must re-index.
     * <p>
     * In snapshot mode the cell set is the gating pane's, already narrowed by its quality
     * and annotation filters; re-querying the hierarchy would widen it back to the whole
     * slide and break the positional alignment every snapshot array depends on. Returns
     * {@code null} when the caller should collect detections itself.
     * <p>
     * Empty — <em>not</em> null — while {@link #isAwaitingSnapshot() awaiting one}. The
     * gating pane owns the cell set in that window too, and it has not yet said what it
     * is; collecting detections ourselves would index the new image's whole hierarchy
     * behind its back. Nothing to rebuild is the honest answer, and the caller's existing
     * "no detections, nothing to do" branch handles it.
     */
    public List<PathObject> detectionsForRebuild() {
        if (snapshot != null) return List.of(snapshot.index().getObjects());
        return awaitingSnapshot ? List.of() : null;
    }

    /**
     * The cells an analysis may read: everything not classified {@link #EXCLUDED_CLASS},
     * narrowed to the annotation ROIs when there are any.
     * <p>
     * The predicate as data — a list of detections and a list of ROIs — rather than an
     * {@link qupath.lib.images.ImageData} and a checkbox. It was the pane's
     * {@code collectDetections}, unreachable without a {@code QuPathGUI}, even though the
     * rule it carries is the reason the feature-rebuild path exists at all: rebuilding
     * used to re-query the hierarchy <em>without</em> the annotation filter, silently
     * widening the analysis back to the whole slide and misaligning every population tag
     * mask, which is indexed positionally against this list.
     *
     * @param detections every detection on the image, in hierarchy order
     * @param rois       the annotation ROIs to narrow to; empty means "no narrowing",
     *                   which is what an annotation filter with nothing drawn must mean
     */
    public static List<PathObject> selectDetections(Collection<PathObject> detections,
                                                    List<ROI> rois) {
        List<PathObject> kept = new ArrayList<>();
        for (PathObject d : detections) {
            PathClass pc = d.getPathClass();
            // getName(), deliberately, and it is the leaf: an "Excluded" cell that was
            // later tagged reads "Excluded: Rim", whose getName() is "Rim" and whose
            // toString() is "Excluded: Rim" — so it escapes this filter under EITHER
            // spelling. The gating half writes Excluded and this half writes tags, and a
            // cell cannot currently be given both; changing the spelling here would only
            // move which unreachable case is wrong.
            if (pc != null && EXCLUDED_CLASS.equals(pc.getName())) continue;
            if (!rois.isEmpty()) {
                ROI cellRoi = d.getROI();
                // No ROI means no centroid to test, so it cannot be shown to be inside.
                if (cellRoi == null) continue;
                double cx = cellRoi.getCentroidX();
                double cy = cellRoi.getCentroidY();
                boolean inside = false;
                for (ROI roi : rois) {
                    if (roi.contains(cx, cy)) { inside = true; break; }
                }
                if (!inside) continue;
            }
            kept.add(d);
        }
        return kept;
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
    // Run lifecycle and the derived view state
    // ------------------------------------------------------------------

    /**
     * A UMAP run has been submitted. Clears any previous failure — the panel is no longer
     * describing that run — and puts the session into the one phase in which Cancel exists.
     */
    public void beginRun() {
        mutate(() -> {
            running = true;
            failure = null;
        });
    }

    /**
     * The user pressed Cancel. Leaves the running phase immediately so the click feels
     * responsive; the run's own {@link UmapOutcome.Cancelled} arrives later and is
     * idempotent with this.
     */
    public void cancelRun() {
        mutate(() -> running = false);
    }

    /**
     * Fold a run's single terminal outcome into the session, and return the state the view
     * must now show.
     * <p>
     * This is the whole outcome-to-UI mapping, in one toolkit-free place, and it is why
     * {@code Superseded} is safe. That branch does not "do nothing" by convention any more:
     * it leaves {@code running} set, so the state it returns is still
     * {@link ViewState.Stage#COMPUTING} and the newer run keeps the busy UI it owns. An
     * edit that made this branch clear the phase would show up as a changed return value in
     * a test that needs no JavaFX toolkit — where previously the only symptom was a live
     * run whose progress bar had vanished.
     * <p>
     * {@code Failed} is the reason {@link ViewState.Stage#FAILED} exists. The reason string
     * outlives the modal alert and the five-second status line, so the overlay over the
     * empty plot can go on saying that the last attempt broke rather than reverting to
     * "Ready to embed" — which is what the panel said after a crash before.
     *
     * @param outcome the run's one ending
     * @return {@link #viewState()} after the fold, for the caller to apply
     */
    public ViewState record(UmapOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        mutate(() -> {
            switch (outcome) {
                case UmapOutcome.Succeeded succeeded -> {
                    embedding = succeeded.result();
                    failure = null;
                    running = false;
                }
                case UmapOutcome.Failed failed -> {
                    failure = failed.describe();
                    running = false;
                }
                case UmapOutcome.Cancelled ignored -> {
                    running = false;
                }
                case UmapOutcome.Superseded ignored -> {
                    // A newer run owns the busy state. Touching `running` here would drive
                    // the panel out of a COMPUTING that is still true.
                }
            }
        });
        return viewState();
    }

    /**
     * A feature rebuild has been started. Must be balanced by {@link #endRebuild()} on every
     * exit of the background build, superseded ones included.
     */
    public void beginRebuild() {
        mutate(() -> pendingRebuilds++);
    }

    /** A feature rebuild has landed, been superseded, or failed. */
    public void endRebuild() {
        mutate(() -> {
            if (pendingRebuilds > 0) pendingRebuilds--;
        });
    }

    /** A CSV export is writing; Export must not be clickable again until it finishes. */
    public void beginExport() {
        mutate(() -> exporting = true);
    }

    /** The CSV export finished, successfully or not. */
    public void endExport() {
        mutate(() -> exporting = false);
    }

    /**
     * Everything the panel may currently offer, derived from the facts above and from
     * nothing else.
     * <p>
     * The rules, stated once:
     * <ul>
     *   <li><b>Stage</b> — a run in flight wins; then a failure the user has not moved past;
     *       then the presence of an embedding, and within that whether a polygon is closed
     *       (GATING) or a tag has been applied (TAGGED).</li>
     *   <li><b>Compute</b> — needs cells that describe the image on screen, needs
     *       {@link EmbeddingFeatures#MINIMUM_FEATURES} markers ticked, and needs nothing
     *       already running <em>or rebuilding</em>. The marker count is the carry-forward
     *       from the feature picker: the toolbar button used to ignore it and invite a
     *       click whose only possible ending was a failure dialog.</li>
     *   <li><b>Inputs</b> — the feature picker and every embedding parameter are locked
     *       while a run is in flight. {@code onFeatureSelectionChanged} installs a rebuilt
     *       {@link CellIndex} on this session; doing that under a compute thread still
     *       reading the old one is the mid-run-edit hole, and a control the user cannot
     *       reach is the only fix that does not depend on a cancel arriving in time.</li>
     * </ul>
     */
    public ViewState viewState() {
        // A stale cell set is not a cell set. Everything below reads through this, so the
        // window between an image change and the gating tree's next push offers nothing.
        boolean hasCells = hasCells();
        boolean hasEmbedding = embedding != null;
        boolean gateOpen = hasEmbedding && gateMask != null;

        ViewState.Stage stage;
        if (running) {
            stage = ViewState.Stage.COMPUTING;
        } else if (!hasEmbedding) {
            // A failure with nothing on the plot is the case the panel used to misreport as
            // READY. A failure over a surviving embedding leaves the stage alone — the plot
            // still shows something true — and travels in `failure` instead.
            stage = failure != null ? ViewState.Stage.FAILED
                    : hasCells ? ViewState.Stage.READY : ViewState.Stage.NO_IMAGE;
        } else if (gateOpen) {
            stage = ViewState.Stage.GATING;
        } else if (!populationTags.isEmpty()) {
            stage = ViewState.Stage.TAGGED;
        } else {
            stage = ViewState.Stage.COMPUTED;
        }

        boolean idle = !running;
        boolean rebuilding = isRebuildPending();
        return new ViewState(
                stage,
                idle && !rebuilding && hasCells
                        && includedMarkerCount() >= EmbeddingFeatures.MINIMUM_FEATURES,
                running,
                idle && hasEmbedding,
                idle && stage == ViewState.Stage.GATING,
                idle && hasEmbedding && !exporting,
                // The feature picker is still populated with the PREVIOUS image's markers
                // while the gating tree re-indexes, and ticking one there re-indexed the
                // new image's whole hierarchy behind the gating pane's back. The awaiting
                // window withholds the inputs for the same reason it withholds Run.
                idle && !awaitingSnapshot,
                rebuilding,
                !hasEmbedding,
                !hasEmbedding && hasCells,
                // Waiting for a snapshot is still snapshot mode: the gating pane owns the
                // cell set and this panel must not offer its own annotation filter over it.
                !isSnapshotMode(),
                running ? null : failure);
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
        if (seedableCount(incoming) < EmbeddingFeatures.MINIMUM_FEATURES) {
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
     * Record one row of the feature picker: which compartment, which statistic, and
     * whether this marker feeds the embedding at all.
     */
    public void editSelection(String marker, MarkerSelection.Entry entry) {
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(entry, "entry");
        mutate(() -> selection.put(marker, entry));
    }

    /**
     * How many of the gate tree's markers this image actually carries — the count
     * {@link #seedSelection} decides on, so {@link #featuresPreSeeded()} cannot answer a
     * different question from the one that was asked.
     */
    private static int seedableCount(PhenotypeSnapshot incoming) {
        List<String> gated = incoming.gatedMarkers();
        int seedable = 0;
        for (String marker : incoming.markerNames()) {
            if (gated.contains(marker)) seedable++;
        }
        return seedable;
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

    /** What {@link #firstColourMode} decided a finished embedding should be painted by. */
    public enum ColourMode {
        /** Paint the gate tree's phenotypes — the thing the user came to look at. */
        PHENOTYPE,
        /** Paint one marker's expression, at {@link #preferredMarker()}. */
        MARKER,
        /** The user has already chosen; leave the plot alone. */
        UNCHANGED
    }

    /**
     * What a finished embedding should be coloured by.
     * <p>
     * Standalone, the answer is "some marker" — without one the plot is a uniform grey
     * blob and the user has to go hunting through a dropdown to see anything. Opened from
     * a gate tree the answer is the opposite: the phenotypes <em>are</em> the thing they
     * came to look at, and overriding them with an arbitrary channel would throw away the
     * whole reason the two halves were joined. So marker colouring is the fallback for
     * having no phenotypes to show, not the default.
     * <p>
     * A product decision that carried thirteen lines of justifying comment inside a
     * {@code UmapPane} handler and no test at all, because reaching it needed a live
     * toolkit and a {@code QuPathGUI}.
     *
     * @param currentMarker what the marker dropdown holds; anything other than
     *                      {@code null} or {@link #NO_MARKER} is a choice the user has
     *                      already made, and is left alone
     */
    public ColourMode firstColourMode(String currentMarker) {
        if (snapshot != null && snapshot.hasPhenotypes()) return ColourMode.PHENOTYPE;
        if (markers.isEmpty()) return ColourMode.UNCHANGED;
        boolean unchosen = currentMarker == null || NO_MARKER.equals(currentMarker);
        return unchosen ? ColourMode.MARKER : ColourMode.UNCHANGED;
    }

    /**
     * The per-cell numbers the marker overlay paints, already on the requested scale.
     *
     * @param marker    a marker name from {@link #markers()}
     * @param asZScore  {@code true} for the standardized scale, {@code false} for the raw
     *                  measurement
     * @return the values, positional against {@link CellIndex#getObjects()}, or
     *         {@code null} when this session cannot resolve the marker. Raw values are the
     *         index's <b>backing column</b> — read-only, like every other array accessor
     *         on the hot path; the z-scored form is necessarily a new array
     */
    public double[] colourValues(String marker, boolean asZScore) {
        if (cellIndex == null || markerStats == null || marker == null) return null;
        int idx = cellIndex.getMarkerIndex(marker);
        if (idx < 0) return null;
        double[] raw = cellIndex.getMarkerValues(idx);
        if (!asZScore) return raw;
        double[] z = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            z[i] = markerStats.toZScore(marker, raw[i]);
        }
        return z;
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

    /**
     * Close a polygon gate over the embedding.
     *
     * @throws NullPointerException on {@code null} — dropping a gate is
     *         {@link #retireGate()}, which also invalidates the computation still in
     *         flight. {@code setGateMask(null)} said only half of that, in three places
     */
    public void setGateMask(boolean[] mask) {
        Objects.requireNonNull(mask, "mask — to drop the gate call retireGate()");
        mutate(() -> this.gateMask = mask);
    }

    /**
     * The gated cells and whether the cap dropped any.
     *
     * @param objects   at most {@code limit} of them, in embedding order
     * @param truncated {@code true} only when a cell was actually left out. Reading this
     *                  off {@code objects.size() == limit} at the call site warned about a
     *                  gate covering exactly the cap, where nothing was dropped
     */
    public record GateSelection(List<PathObject> objects, boolean truncated) {}

    /**
     * The gated cells, in embedding order, capped at {@code limit}.
     * <p>
     * The last thing outside this class that iterated the gate mask, which is why there is
     * no {@code gateMask()} accessor any more: the mask is state, and handing it out is
     * handing out the ability to change it without the session hearing. The cap and the
     * fact that it bit are decided in the same pass, so they cannot disagree.
     *
     * @param objects the embedding's object array; read, never retained
     * @param limit   the most objects to return — a gate can cover millions of cells and
     *                QuPath's selection model is not built for that
     */
    public GateSelection gatedObjects(PathObject[] objects, int limit) {
        List<PathObject> selected = new ArrayList<>();
        if (gateMask == null || objects == null) return new GateSelection(selected, false);
        int end = Math.min(objects.length, gateMask.length);
        for (int i = 0; i < end; i++) {
            if (!gateMask[i]) continue;
            if (selected.size() == limit) return new GateSelection(selected, true);
            selected.add(objects[i]);
        }
        return new GateSelection(selected, false);
    }

    /** Hide or show one phenotype in the plot. */
    public void togglePhenotype(String name) {
        if (name == null) return;
        mutate(() -> {
            if (!hiddenPhenotypes.remove(name)) hiddenPhenotypes.add(name);
        });
    }

    /**
     * Un-hide every phenotype the legend pushed out of the way.
     *
     * @return {@code true} when something was actually hidden, so the caller can skip a
     *         repaint. The pane used to answer that by reading {@code hiddenPhenotypes()}
     *         and then clearing the live set itself
     */
    public boolean showAllPhenotypes() {
        if (hiddenPhenotypes.isEmpty()) return false;
        mutate(hiddenPhenotypes::clear);
        return true;
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
        mutate(() -> populationTags.add(tag));
    }

    /**
     * Drop every population tag — the overlays only, never the classifications on the
     * cells.
     * <p>
     * Called when a recompute produces a different cell count, at which point the masks
     * are positional against nothing. {@code UmapPane} used to do this with
     * {@code session.tags().clear()} on the live list, which changed the session's
     * {@link ViewState} from TAGGED to COMPUTED without the session ever finding out.
     */
    public void clearTags() {
        if (populationTags.isEmpty()) return;
        mutate(populationTags::clear);
    }

    /**
     * Name the gated cells as a population: write the derived {@link PathClass} onto each
     * one, register the tag, and retire the gate that selected them.
     * <p>
     * This is the one place FlowPath's UMAP half writes classifications, and it happens
     * only because the user pressed Tag Selection. Cells outside the gate are never
     * touched — which is why there is no restore pass here, unlike the version that
     * reclassified every outside cell just to grey it out.
     *
     * @param name        the population name
     * @param packedColor packed RGB for the ring overlay
     * @param objects     the embedding's object array, positional against the gate mask
     * @return the tag that was applied, or {@code null} when no gate is closed
     */
    public PopulationTag applyTag(String name, int packedColor, PathObject[] objects) {
        if (gateMask == null || objects == null) return null;
        boolean[] insideMask = gateMask;
        int end = Math.min(objects.length, insideMask.length);
        for (int i = 0; i < end; i++) {
            if (!insideMask[i]) continue;
            PathClass current = objects[i].getPathClass();
            // Carry the phenotype's colour onto the derived class, so a tagged cell still
            // renders as its population in QuPath rather than in the default grey.
            int originalColor = current != null ? current.getColor() : 0xFF808080;
            // toString(), not getName(). QuPath treats "T cell: Rim" as a DERIVED class
            // whose getName() is the leaf "Rim" — so reading the name back tagged an
            // already-tagged cell as "Rim: Core" instead of "T cell: Core", and left
            // removeTag unable to recognise its own suffix at all. toString() is the full
            // path, and is what fromString() parses.
            String derivedName = tagClassName(current != null ? current.toString() : null, name);
            objects[i].setPathClass(PathClass.fromString(derivedName, originalColor));
        }
        PopulationTag tag = new PopulationTag(name, packedColor, insideMask);
        mutate(() -> {
            populationTags.add(tag);
            gateGeneration.incrementAndGet();
            gateMask = null;
        });
        return tag;
    }

    /**
     * Remove a population tag and put its cells back to the class they carried before it
     * was applied.
     * <p>
     * The colour is carried across deliberately. {@link #applyTag} preserved the phenotype
     * colour on the derived class, so dropping it here left every untagged cell rendered
     * in QuPath's default instead of its own phenotype.
     *
     * @param objects the embedding's object array, positional against the tag's mask
     * @return the tag that was removed, or {@code null} when there was no such tag
     */
    public PopulationTag removeTag(String name, PathObject[] objects) {
        PopulationTag tagToRemove = tag(name);
        if (tagToRemove == null) return null;
        if (objects != null) {
            boolean[] mask = tagToRemove.mask();
            int end = Math.min(objects.length, mask.length);
            for (int i = 0; i < end; i++) {
                if (!mask[i]) continue;
                PathClass current = objects[i].getPathClass();
                // The full derived path — see applyTag. current.getName() is "Rim", which
                // never ends with ": Rim", so nothing was ever restored.
                String baseName = untagClassName(current == null ? null : current.toString(), name);
                if (baseName != null) {
                    objects[i].setPathClass(PathClass.fromString(baseName, current.getColor()));
                }
            }
        }
        mutate(() -> populationTags.remove(tagToRemove));
        return tagToRemove;
    }

    /** One single-entry colour array per tag, for the canvas's ring overlay. */
    public List<int[]> ringColors() {
        List<int[]> colors = new ArrayList<>(populationTags.size());
        for (PopulationTag tag : populationTags) colors.add(new int[]{tag.color()});
        return colors;
    }

    /** One mask per tag, in the same order as {@link #ringColors()}. */
    public List<boolean[]> ringMasks() {
        List<boolean[]> masks = new ArrayList<>(populationTags.size());
        for (PopulationTag tag : populationTags) masks.add(tag.mask());
        return masks;
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
     * Count cells by classification for the standalone legend:
     * {@code full class path -> [count, ARGB]}, in first-seen order.
     * <p>
     * <b>The full path, not {@link PathClass#getName()}.</b> QuPath treats
     * {@code "T cell: Rim"} as a class <em>derived</em> from {@code "T cell"} and returns
     * the leaf {@code "Rim"} from {@code getName()} — so once two phenotypes carried the
     * same population tag, the legend collapsed them into one row wearing whichever colour
     * it saw first and quoting the sum of both counts. Same defect as the tag write loops,
     * one screen away.
     * <p>
     * Here rather than on {@code PhenotypeLegend} because that class cannot be loaded
     * without a JavaFX toolkit, and because the legend's other source — a snapshot's
     * {@code populations()} — is already the session's answer. One legend, two sources,
     * both answered here.
     */
    public static java.util.Map<String, int[]> classCounts(PathObject[] objects) {
        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        if (objects == null) return counts;
        for (PathObject obj : objects) {
            PathClass pc = obj.getPathClass();
            String name = pc != null ? pc.toString() : PhenotypeSnapshot.UNCLASSIFIED;
            int color = pc != null ? pc.getColor() : 0x808080;
            counts.computeIfAbsent(name, k -> new int[]{0, color})[0]++;
        }
        return counts;
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

    /**
     * What this session is holding, as ordered fragments: the cell count, what the gating
     * tree made of them, and how much of the panel the next run would read.
     * <p>
     * <b>One composition, two renderings.</b> The rail's cell summary and the status
     * bar's snapshot line were two implementations of the same paragraph with different
     * wording and different arithmetic — the rail counted ticked markers, the status line
     * counted gated ones, and the two appeared within an inch of each other saying
     * different numbers about the same slide. {@link #overviewLine()} joins these with
     * commas for the status bar; the rail joins them with newlines. Neither composes
     * anything of its own.
     */
    public List<String> overviewLines() {
        if (!hasCells()) return List.of("No cells loaded");

        List<String> lines = new ArrayList<>(3);
        int total = snapshot != null ? snapshot.includedCount() : cellIndex.size();
        int dropped = snapshot != null ? snapshot.cellCount() - snapshot.includedCount() : 0;
        lines.add(dropped > 0
                ? String.format(Locale.US, "%,d cells \u00b7 %,d filtered out", total, dropped)
                : String.format(Locale.US, "%,d cells", total));

        if (snapshot != null) {
            int populations = snapshot.populations().size();
            lines.add(snapshot.hasPhenotypes()
                    ? String.format(Locale.US, "%d phenotype%s from %d gate%s",
                            populations, populations == 1 ? "" : "s",
                            snapshot.gateCount(), snapshot.gateCount() == 1 ? "" : "s")
                    : "no gates applied yet");
        }

        lines.add(String.format(Locale.US, "%d of %d markers selected",
                includedMarkerCount(), markers.size()));
        return lines;
    }

    /** {@link #overviewLines()} on one line, for the status bar: a list of clauses. */
    public String overviewLine() {
        return String.join(", ", overviewLines());
    }

    /**
     * {@link #overviewLines()} as the rail prints them: one sentence per line.
     * <p>
     * Two renderings of one composition, and the case is the rendering's business. The
     * fragments are clauses because that is what the status line needs; a clause that
     * starts its own line in the rail — "no gates applied yet" — reads as a typo, and the
     * empty case is a whole sentence and wants its full stop.
     */
    public String railSummary() {
        if (!hasCells()) return "No cells loaded.";
        StringBuilder sb = new StringBuilder();
        for (String line : overviewLines()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(Character.toUpperCase(line.charAt(0))).append(line, 1, line.length());
        }
        return sb.toString();
    }

    /**
     * {@code true} when the feature picker arrived pre-ticked from the gate tree.
     * <p>
     * Not the same question as "did the gate tree name any markers". {@link #seedSelection}
     * declines to seed below {@link EmbeddingFeatures#MINIMUM_FEATURES} — the ordinary
     * first gate anyone draws names one marker, and seeding it faithfully would offer a Run
     * button that cannot succeed — so the empty state promised "features are pre-selected
     * from your gates" over a selection that had been left at everything-ticked.
     */
    public boolean featuresPreSeeded() {
        return snapshot != null && seedableCount(snapshot) >= EmbeddingFeatures.MINIMUM_FEATURES;
    }
}
