package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.LegacyZScoreMigration;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.RegionMask;
import qupath.ext.flowpath.model.UndoHistory;
import qupath.lib.objects.PathObject;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the gating pane runs against — the gate tree, the cells, and everything derived from
 * the two — plus the undo history over the tree, with one {@link #resync} that brings the
 * derived state in line after anything changes it.
 * <p>
 * <b>Why one resync.</b> {@code FlowPathPane} used to have one list of steps per entry point,
 * and they had drifted: loading a tree recomputed the masks, statistics and tree view; undo
 * recomputed none of them, so the filter pane, the cached masks and the statistics described
 * three different trees; opening an image computed the statistics but never requested a
 * gating pass, so the new cells kept no phenotype and the tree kept the previous image's
 * counts until some unrelated edit. Every entry point that replaces the tree, the index or a
 * filter now ends in {@link #resync}, and nothing else decides what to recompute.
 * <p>
 * <b>The order is the point.</b> The ROI mask and quality mask come from the <em>current</em>
 * tree's own filter state, the statistics from those masks, and only then is a legacy
 * z-score tree migrated — through statistics that describe the cells this tree actually
 * filters, not the ones the previous tree filtered — and only then is a gating pass
 * requested, so a pass never sees a flagged gate or stale statistics.
 * <p>
 * Toolkit-free, like {@code UmapSession} and {@code AnalysisSession}: the pane renders what
 * this holds and decides nothing, and every sequence is table-tested without JavaFX.
 */
final class GatingSession {

    /** Everything a gating pass needs, as {@link #resync} left it. */
    record PassInput(GateTree tree, CellIndex index, MarkerStats stats, boolean[] roiMask,
                     RegionMask regions) {}

    /** Where a resync hands its result for gating. The pane's is the live-preview service. */
    @FunctionalInterface
    interface GatingPass {
        void request(PassInput input);
    }

    /** One notification about a legacy z-score migration. */
    record MigrationNotice(String message, boolean warning) {}

    /**
     * What a coalesced undo step belongs to. A burst coalesces only with itself, so a
     * quality-filter drag straight after a gate edit is a step of its own.
     */
    enum EditSource { GATE, QUALITY_FILTER }

    private final UndoHistory<GateTree> undoHistory;
    private final GatingPass gatingPass;

    private GateTree tree = new GateTree();
    /** A copy of {@link #tree} as of the last {@link #settle()}; see {@link #recordAppliedEdit}. */
    private GateTree settled = tree.deepCopy();
    private CellIndex index;
    private boolean[] roiMask;
    private RegionMask regions;
    private boolean[] qualityMask;
    private MarkerStats stats;
    /**
     * The index and combined mask {@link #stats} were computed over, or {@code null} when that
     * is not known (statistics a background recompute handed in). A resync whose masks come
     * out equal to these keeps {@link #stats} instead of re-sorting every column.
     */
    private CellIndex statsIndex;
    private boolean[] statsMask;

    /**
     * The last migration notice shown while the tree changed nothing, so the same "these gates
     * read a channel this image does not carry" warning is not repeated on every undo, redo
     * and load. Cleared when a new index is adopted.
     */
    private String lastUnchangedMigrationNotice;

    GatingSession(LongSupplier clock, GatingPass gatingPass) {
        this.undoHistory = new UndoHistory<>(UndoHistory.DEFAULT_MAX_DEPTH, GateTree::deepCopy, clock);
        this.gatingPass = Objects.requireNonNull(gatingPass, "gatingPass");
    }

    // ---- state -------------------------------------------------------------------------

    GateTree tree() { return tree; }
    CellIndex index() { return index; }
    MarkerStats stats() { return stats; }
    /** The annotation filter's inclusion mask, or {@code null} when it filters nothing. */
    boolean[] roiMask() { return roiMask; }
    /** Which annotated region each cell fell in; {@code null} whenever {@link #roiMask()} is. */
    RegionMask regions() { return regions; }
    boolean[] qualityMask() { return qualityMask; }

    /** The quality and ROI masks combined: the population statistics are computed over. */
    boolean[] combinedMask() {
        if (qualityMask == null) return roiMask;
        if (roiMask == null) return qualityMask;
        return GatingEngine.combineMasks(qualityMask, roiMask);
    }

    // ---- the resync --------------------------------------------------------------------

    /**
     * Bring every derived piece in line with the current tree and index, then request a
     * gating pass: ROI mask, quality mask, statistics, legacy migration, pass — in that order.
     * Computes the heavy part on the calling thread; see {@link #resync(Derived, Supplier)} for
     * adopting one computed elsewhere.
     * <p>
     * The masks are always recomputed, but the statistics — which sort every marker column
     * over every cell, seconds on a large slide — only when the index or the combined mask
     * they were computed over changed, and a gate-only undo changes neither. Every path that
     * can change a mask (undo, redo, load, the ROI toggle, an image read) derives off the FX
     * thread and adopts through {@link #resync(Derived, Supplier)}; this overload is the
     * fallback for a derivation the session has moved past, and for the cheap cases — no
     * cells at all — where there is nothing heavy to defer.
     *
     * @param annotations the annotations the ROI filter should use; only asked for when the
     *                    tree's filter is on and there are cells to filter
     * @return the migration notice to show, if any
     */
    Optional<MigrationNotice> resync(Supplier<List<PathObject>> annotations) {
        if (index == null) return adopt(Derived.NONE);
        return adopt(derive(derivationInputs(index, annotations), reusableStats()));
    }

    /**
     * The same resync, adopting masks and statistics {@linkplain #derive derived} on another
     * thread — but only while they still describe this session: the same index, the same ROI
     * flag and a quality filter that selects the same cells. If the tree or index moved on
     * while the derivation ran (an undo, a filter drag, a toggle, a new image), it is derived
     * again here, synchronously, from the current state. A stale derivation is never adopted.
     * <p>
     * The annotations are the one input not re-checked: a derivation carries the annotations
     * captured when it was requested, and any later annotation change requests its own.
     */
    Optional<MigrationNotice> resync(Derived derived, Supplier<List<PathObject>> annotations) {
        Objects.requireNonNull(derived, "derived");
        if (index == null) return adopt(Derived.NONE);
        if (!stillDescribes(derived)) return resync(annotations);
        return adopt(derived);
    }

    /**
     * Whether {@code derived} still describes this session — the same index, the same ROI flag
     * and a quality filter that selects the same cells — so that
     * {@link #resync(Derived, Supplier)} would adopt it rather than derive again.
     * <p>
     * Cheap: a mask comparison and one pass over the filtered columns, never a sort. A caller
     * that can re-derive off the FX thread asks this first, so the synchronous fallback inside
     * {@code resync} stays the safety net it is rather than the way a slide gets re-sorted on
     * the FX thread after all.
     */
    boolean stillDescribes(Derived derived) {
        Objects.requireNonNull(derived, "derived");
        return index != null
                && derived.index() == index
                && derived.roiFilterEnabled() == tree.isRoiFilterEnabled()
                && Arrays.equals(derived.qualityMask(), qualityMaskOf(index, tree.getQualityFilter()));
    }

    private Optional<MigrationNotice> adopt(Derived derived) {
        Optional<MigrationNotice> notice = Optional.empty();
        regions = derived.regions();
        roiMask = regions != null ? regions.included() : null;
        qualityMask = derived.qualityMask();
        stats = derived.stats();
        statsIndex = derived.index();
        statsMask = combinedMask();
        if (index != null) notice = migrateLegacyZScores();
        settle();
        gatingPass.request(new PassInput(tree, index, stats, roiMask, regions));
        return notice;
    }

    // ---- the heavy part, on any thread -------------------------------------------------

    /**
     * What a derivation reads, captured on the thread that owns the session so it can be
     * handed to another: the cells, a <em>copy</em> of the tree's quality filter (the panel
     * writes into the live one while the user drags), the ROI flag and the annotations.
     */
    record DerivationInputs(CellIndex index, QualityFilter qualityFilter, boolean roiFilterEnabled,
                            List<PathObject> annotations) {
        DerivationInputs {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }

        /** The same inputs against another index — a read of the image that has not landed yet. */
        DerivationInputs withIndex(CellIndex newIndex) {
            return new DerivationInputs(newIndex, qualityFilter, roiFilterEnabled, annotations);
        }
    }

    /** Masks and statistics derived from {@link DerivationInputs}; adopted by {@link #resync(Derived, Supplier)}. */
    record Derived(CellIndex index, boolean roiFilterEnabled, RegionMask regions, boolean[] qualityMask,
                   MarkerStats stats) {
        static final Derived NONE = new Derived(null, false, null, null, null);
    }

    /**
     * Capture what {@link #derive} needs for {@code forIndex} — the current index, or one being
     * read, whose cells are not known yet. Annotations are asked for only when the tree's ROI
     * filter is on.
     */
    DerivationInputs derivationInputs(CellIndex forIndex, Supplier<List<PathObject>> annotations) {
        QualityFilter filter = tree.getQualityFilter();
        boolean roi = tree.isRoiFilterEnabled();
        return new DerivationInputs(forIndex, filter == null ? null : filter.deepCopy(), roi,
                roi ? annotations.get() : List.of());
    }

    /**
     * The statistics a derivation may keep instead of sorting every column again, captured on
     * the thread that owns the session beside its {@link DerivationInputs}: the index and
     * combined mask {@link #stats} describes, or {@code null} for both when that is not known
     * (statistics {@link #adoptStats} handed in, which are never reused).
     */
    record ReusableStats(CellIndex index, boolean[] mask, MarkerStats stats) {
        static final ReusableStats NONE = new ReusableStats(null, null, null);
    }

    /** What {@link #stats} may be reused for; see {@link ReusableStats}. */
    ReusableStats reusableStats() {
        return statsIndex == null ? ReusableStats.NONE : new ReusableStats(statsIndex, statsMask, stats);
    }

    /**
     * The expensive half of a resync — region mask, quality mask, statistics — as a pure
     * function of captured inputs, so it can run off the FX thread. Touches no session state.
     */
    static Derived derive(DerivationInputs in) {
        return derive(in, null, null);
    }

    /**
     * {@link #derive(DerivationInputs)}, keeping {@code reusable}'s statistics when they
     * describe the same index and the same combined mask this derivation computes — the
     * background path's version of the reuse {@link #resync(Supplier)} does, so moving a
     * gate-only undo off the FX thread does not turn it into a full re-sort.
     */
    static Derived derive(DerivationInputs in, ReusableStats reusable) {
        return reusable != null && reusable.index() != null && reusable.index() == in.index()
                ? derive(in, reusable.mask(), reusable.stats())
                : derive(in, null, null);
    }

    /**
     * {@link #derive(DerivationInputs)}, keeping {@code reusable} when its combined mask
     * {@code reusableMask} equals the one derived here. Both must describe {@code in.index()}.
     */
    private static Derived derive(DerivationInputs in, boolean[] reusableMask, MarkerStats reusable) {
        CellIndex idx = in.index();
        if (idx == null) return Derived.NONE;
        RegionMask regions = in.roiFilterEnabled() ? usableRegions(idx, in.annotations()) : null;
        boolean[] roi = regions != null ? regions.included() : null;
        boolean[] quality = qualityMaskOf(idx, in.qualityFilter());
        boolean[] combined = quality == null ? roi
                : roi == null ? quality
                : GatingEngine.combineMasks(quality, roi);
        MarkerStats stats = reusable != null && Arrays.equals(combined, reusableMask)
                ? reusable
                : MarkerStats.compute(idx, combined);
        return new Derived(idx, in.roiFilterEnabled(), regions, quality, stats);
    }

    private static boolean[] qualityMaskOf(CellIndex idx, QualityFilter filter) {
        return filter == null ? null : GatingEngine.computeQualityMask(idx, filter);
    }

    /**
     * The regions to filter by, or {@code null} when there is nothing usable. Treated as "no
     * filter" rather than "exclude everything": annotations that enclose no area answer
     * {@code contains()} false everywhere, so the old behaviour emptied the entire view
     * whenever the only annotation on the image was a point or a line.
     */
    private static RegionMask usableRegions(CellIndex index, List<PathObject> annotations) {
        RegionMask computed = RegionMask.compute(index, annotations);
        return computed.isEmpty() ? null : computed;
    }

    // ---- what changes the inputs -------------------------------------------------------

    /**
     * A new image's cells, or {@code null} when there are none. Follow with {@link #resync}.
     * A new image may show the same missing-channel notice again.
     */
    void adoptIndex(CellIndex newIndex) {
        this.index = newIndex;
        this.lastUnchangedMigrationNotice = null;
    }

    /**
     * The same image's cells, read again because its detection set changed. Unlike
     * {@link #adoptIndex} the missing-channel notice is not shown again: the panel is the
     * image's, and repeating it on every detection edit would train the user to ignore it.
     * Follow with {@link #resync}.
     */
    void rereadIndex(CellIndex newIndex) {
        this.index = newIndex;
    }

    /** Replace the tree (a load), as one undo step. Follow with {@link #resync}. */
    void replaceTree(GateTree loaded) {
        undoHistory.record(tree);
        this.tree = Objects.requireNonNull(loaded, "loaded");
    }

    /** Step back one edit; {@code true} if there was one. Follow with {@link #resync}. */
    boolean undo() {
        return undoHistory.undo(tree).map(previous -> { tree = previous; return true; }).orElse(false);
    }

    /** Step forward one undone edit; {@code true} if there was one. Follow with {@link #resync}. */
    boolean redo() {
        return undoHistory.redo(tree).map(next -> { tree = next; return true; }).orElse(false);
    }

    /**
     * Record the tree as it is now, before a discrete edit, as one undo step. The caller
     * {@linkplain #settle settles} once the edit is written.
     */
    void recordEdit() {
        undoHistory.record(tree);
    }

    /**
     * Record the tree before a gate is replaced by one of another type (a shape of another
     * kind drawn over it), as one undo step. The editor writes the drawn shape and reports the
     * change straight after, through {@link #recordAppliedEdit}{@code (GATE)}; that report is
     * folded into this step. Recorded as a plain {@link #recordEdit} and then settled by the
     * pane's pass request, the report recorded the settled replacement a second time — a
     * no-op first undo, or an "empty polygon" step before the original gate.
     */
    void recordReplacement() {
        undoHistory.recordStartingBurst(tree, EditSource.GATE);
    }

    /**
     * Record an edit that has <em>already been written</em> into the tree, as the gate
     * editor reports its edits: it writes into the gate, then notifies. Recording the tree
     * at that point snapshots the edited value, so undo restored nothing. What is recorded
     * instead is the tree as it was when the previous edit {@linkplain #settle settled},
     * which is the tree just before this one. Coalesced by source, so a drag is one step.
     */
    void recordAppliedEdit(EditSource source) {
        undoHistory.recordCoalesced(settled, source);
        settle();
    }

    /**
     * Record a discrete edit that has already been written (a gate's enabled checkbox), from
     * the settled tree, as one uncoalesced step.
     */
    void recordAppliedDiscreteEdit() {
        undoHistory.record(settled);
        settle();
    }

    /**
     * The tree's current state is complete: the pre-state for the next
     * {@link #recordAppliedEdit}. Every {@link #resync} settles; the pane settles whenever
     * it requests a gating pass after an edit, and on every quality-filter tick. An edit
     * that changes the tree without ever settling would be folded into the next applied
     * edit's undo step.
     */
    void settle() {
        settled = tree.deepCopy();
    }

    /**
     * Record the tree <em>before</em> an edit that arrives in bursts and is announced before
     * its write (the quality-filter panel's before-change hook).
     */
    void recordEditCoalesced(EditSource source) {
        undoHistory.recordCoalesced(tree, source);
    }

    /**
     * Turn the annotation filter on or off, as one undo step recorded before the change.
     * Discrete, so never coalesced: two quick toggles are two steps. Follow with
     * {@link #resync}.
     */
    void setRoiFilterEnabled(boolean enabled) {
        if (tree.isRoiFilterEnabled() == enabled) return;
        undoHistory.record(tree);
        tree.setRoiFilterEnabled(enabled);
    }

    // ---- the incremental path of a quality-filter drag ----------------------------------

    /**
     * Recompute the quality mask from the tree's filter. A drag uses this on every tick and
     * leaves the statistics to a background recompute, which it hands back through
     * {@link #adoptStats}; a full {@link #resync} per tick would recompute them on the FX
     * thread.
     */
    void recomputeQualityMask() {
        qualityMask = index == null ? null : qualityMaskOf(index, tree.getQualityFilter());
    }

    /** Statistics a background recompute produced for the current masks. */
    void adoptStats(MarkerStats recomputed) {
        this.stats = recomputed;
        // Computed from the filter as some earlier tick left it; which mask that was is not
        // known here, so the next resync recomputes rather than reuse them.
        this.statsIndex = null;
        this.statsMask = null;
    }

    // ---- migration ---------------------------------------------------------------------

    /**
     * Convert a tree saved under the retired computed z-score onto raw values, through the
     * current statistics. Called by {@link #resync} after the statistics are recomputed, and
     * directly when an edit re-points a flagged gate onto a channel this image carries.
     * <p>
     * A no-op without an index (the conversion waits for one) or when no gate carries the
     * flag. Gates whose channel this image lacks keep the flag and are found again on every
     * call; their notice is returned once per image unless the set of such gates changes.
     */
    Optional<MigrationNotice> migrateLegacyZScores() {
        if (index == null || stats == null || !LegacyZScoreMigration.needsMigration(tree)) {
            return Optional.empty();
        }
        LegacyZScoreMigration.Result result = LegacyZScoreMigration.migrate(tree, index, stats);
        if (result.isEmpty()) return Optional.empty();
        String message = result.message();
        if (!result.changedTree()) {
            if (message.equals(lastUnchangedMigrationNotice)) return Optional.empty();
            lastUnchangedMigrationNotice = message;
        }
        boolean warning = !result.unconvertible().isEmpty() || !result.missingChannel().isEmpty();
        return Optional.of(new MigrationNotice(message, warning));
    }
}
