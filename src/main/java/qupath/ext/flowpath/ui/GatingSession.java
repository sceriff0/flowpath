package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.LegacyZScoreMigration;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RegionMask;
import qupath.ext.flowpath.model.UndoHistory;
import qupath.lib.objects.PathObject;

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
    private CellIndex index;
    private boolean[] roiMask;
    private RegionMask regions;
    private boolean[] qualityMask;
    private MarkerStats stats;

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
     *
     * @param annotations the annotations the ROI filter should use; only asked for when the
     *                    tree's filter is on and there are cells to filter
     * @return the migration notice to show, if any
     */
    Optional<MigrationNotice> resync(Supplier<List<PathObject>> annotations) {
        Optional<MigrationNotice> notice = Optional.empty();
        if (index == null) {
            roiMask = null;
            regions = null;
            qualityMask = null;
            stats = null;
        } else {
            regions = tree.isRoiFilterEnabled() ? usableRegions(index, annotations.get()) : null;
            roiMask = regions != null ? regions.included() : null;
            recomputeQualityMask();
            stats = MarkerStats.compute(index, combinedMask());
            notice = migrateLegacyZScores();
        }
        gatingPass.request(new PassInput(tree, index, stats, roiMask, regions));
        return notice;
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

    /** Record the tree as it is now, before a discrete edit, as one undo step. */
    void recordEdit() {
        undoHistory.record(tree);
    }

    /** Record the tree before an edit that arrives in bursts (a slider drag). */
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
        qualityMask = index == null ? null : GatingEngine.computeQualityMask(index, tree.getQualityFilter());
    }

    /** Statistics a background recompute produced for the current masks. */
    void adoptStats(MarkerStats recomputed) {
        this.stats = recomputed;
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
