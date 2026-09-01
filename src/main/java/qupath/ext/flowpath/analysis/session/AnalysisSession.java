package qupath.ext.flowpath.analysis.session;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PopulationStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything the Analysis window <em>knows</em>, separated from everything it <em>shows</em>.
 * <p>
 * This is the same Humble Object split {@code UmapSession}/{@code ViewState} make for the
 * UMAP panel: this class holds the last accepted gating pass and answers every question the
 * window asks about it — what to offer, and what the numbers are — so the window itself only
 * applies the answer. It is constructible and testable with <b>no JavaFX toolkit and no
 * QuPath objects</b>; that is the whole point of the layer.
 * <p>
 * A session holds at most one {@link AnalysisInput} at a time. {@link #accept} replaces it
 * wholesale — a new image must not leave the previous image's regions, tree or tally behind
 * — and {@link #clear} drops it entirely, returning {@link #state()} to the empty state.
 */
public final class AnalysisSession {

    /**
     * One accepted gating pass: the tree that produced it, the cells and their statistics,
     * the per-branch tally the walk filled, and enough about the image's annotated regions
     * to label and area-normalise a per-region row.
     * <p>
     * Deliberately {@link List}{@code <String>} region names rather than a
     * {@code RegionMask} — a {@code RegionMask} is keyed to {@link qupath.lib.objects.PathObject}
     * and {@link qupath.lib.roi.interfaces.ROI}, and taking one here would mean this session,
     * and therefore this whole layer, could only be exercised by building QuPath annotations.
     * The caller (the gating pane) already holds the {@code RegionMask} it built the tally's
     * region indices from; it derives {@code regionNames} from
     * {@code RegionMask.regionNames()} and {@code regionAreasMm2} from each region's ROI.
     * <p>
     * {@code regionNames} (and {@code regionAreasMm2}, when given) and {@code tally} are two
     * views of the <em>same</em> region set — the tally's region indices are what
     * {@code regionNames}/{@code regionAreasMm2} label. A length mismatch means the caller
     * paired a tally from one image with region metadata from another; the constructor
     * rejects that rather than let {@link PopulationStats} paper over it with a
     * {@code "Region N"} fallback name, the same way {@code PhenotypeSnapshot} rejects a
     * length mismatch between its cells and its per-cell arrays, and
     * {@code GatingEngine.combineMasks} rejects one between two masks.
     *
     * @param tree           the gate tree the walk classified against; disabled gates and
     *                       their subtrees contribute no rows, matching {@link PopulationStats}
     * @param index          the cells the walk covered
     * @param stats          per-marker statistics for {@code index}
     * @param tally          per-branch counts the walk recorded, by region and by cleanliness
     * @param regionNames    region names, parallel to the tally's region indices; empty when
     *                       there are no annotated regions; must have exactly
     *                       {@code tally.regionCount()} entries
     * @param regionAreasMm2 per-region area in mm², parallel to {@code regionNames}, or
     *                       {@code null} when unknown; when given, must have exactly
     *                       {@code tally.regionCount()} entries
     * @param imageName      the image the pass describes, for the window's own display
     */
    public record AnalysisInput(GateTree tree, CellIndex index, MarkerStats stats,
                                BranchTally tally, List<String> regionNames,
                                double[] regionAreasMm2, String imageName) {

        public AnalysisInput {
            Objects.requireNonNull(tree, "tree");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(stats, "stats");
            Objects.requireNonNull(tally, "tally");
            regionNames = regionNames == null ? List.of() : List.copyOf(regionNames);
            if (regionNames.size() != tally.regionCount()) {
                throw new IllegalArgumentException(
                        "regionNames describes a different region set than tally: "
                                + regionNames.size() + " vs " + tally.regionCount() + " regions");
            }
            if (regionAreasMm2 != null && regionAreasMm2.length != tally.regionCount()) {
                throw new IllegalArgumentException(
                        "regionAreasMm2 describes a different region set than tally: "
                                + regionAreasMm2.length + " vs " + tally.regionCount() + " regions");
            }
        }
    }

    /** The last accepted pass, or {@code null} when nothing has been accepted, or it was cleared. */
    private AnalysisInput input;

    /**
     * Adopt a freshly gated pass, replacing whatever this session held before in full.
     * <p>
     * Wholesale, not merged: a new image's regions, tree and tally do not blend with the
     * previous image's — the previous pass is simply gone once this returns.
     */
    public void accept(AnalysisInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /** Forget the accepted pass; {@link #state()} returns to the empty state. */
    public void clear() {
        this.input = null;
    }

    /**
     * Everything the window may currently offer, derived from the accepted pass and from
     * nothing else.
     * <p>
     * {@link AnalysisState#availableScopes()} is {@code [WHOLE_SLIDE]} when the pass has no
     * annotated regions, and all three {@link PopulationStats.Scope} values when it does —
     * {@code ANNOTATION_ALL} and {@code ANNOTATION_K} are meaningless without an annotation,
     * and offering an empty scope is how a panel looks broken.
     */
    public AnalysisState state() {
        if (input == null) {
            return new AnalysisState(false, false, false, 0, 0, List.of(),
                    "No gating pass to report on yet — gate some cells to see population statistics.",
                    null);
        }
        int regionCount = input.tally().regionCount();
        boolean hasRegions = regionCount > 0;
        List<PopulationStats.Scope> scopes = hasRegions
                ? List.of(PopulationStats.Scope.WHOLE_SLIDE,
                        PopulationStats.Scope.ANNOTATION_ALL,
                        PopulationStats.Scope.ANNOTATION_K)
                : List.of(PopulationStats.Scope.WHOLE_SLIDE);
        return new AnalysisState(true, hasRegions, true,
                input.tally().cellsTotal(), regionCount, scopes, null, input.imageName());
    }

    /**
     * The population table for the accepted pass, reported against {@code denominator}.
     *
     * @param denominator the branch to report every population's share against, or
     *                     {@code null} when the user has not chosen one — every row's
     *                     {@code percentOfDenominator()} is then {@link Double#NaN}, per
     *                     {@link PopulationStats}
     * @return an empty table ({@link PopulationStats#rows()} empty) when nothing has been
     *         accepted, or since {@link #clear()}
     */
    public PopulationStats stats(Branch denominator) {
        if (input == null) {
            return PopulationStats.of(new GateTree(), new BranchTally(0), List.of(), null, null);
        }
        return PopulationStats.of(input.tree(), input.tally(), input.regionNames(),
                input.regionAreasMm2(), denominator);
    }

    /**
     * One branch a user may choose as the report's denominator, offered under a value that
     * survives the next gating pass, paired with the live {@link Branch} it currently names.
     * <p>
     * The pane stores {@link #ref}, never {@link #branch} — see {@link DenominatorRef}'s own
     * javadoc for why holding the {@code Branch} itself is exactly the bug this task fixes.
     */
    public record DenominatorOption(DenominatorRef ref, Branch branch) {}

    /**
     * Every branch a user may choose as the report's denominator, as a
     * {@link DenominatorRef}/{@link Branch} pair: every branch of every <b>enabled</b> gate
     * in the accepted tree, depth-first.
     * <p>
     * Matches {@link PopulationStats#collectFromRoots}'s own walk exactly — same
     * {@code rootIndex} assignment (incremented only for an enabled root, so disabled roots
     * do not consume a number), same {@code path} construction
     * ({@code prefix.isEmpty() ? branch.getName() : prefix + "/" + branch.getName()}), same
     * depth-first descent, same skip of a disabled gate's whole subtree. Diverging from that
     * walk in any of those details would build a {@link DenominatorRef} that names a
     * population {@link PopulationStats} never reports a row for — the same failure mode
     * {@link qupath.ext.flowpath.analysis.ui.PopulationRef} exists to prevent one layer up,
     * for the exact same reason.
     *
     * @return empty when nothing has been accepted, or since {@link #clear()}
     */
    public List<DenominatorOption> denominatorOptions() {
        List<DenominatorOption> out = new ArrayList<>();
        if (input != null) {
            collectOptionsFromRoots(input.tree().getRoots(), out);
        }
        return out;
    }

    /**
     * Re-find the live {@link Branch} a {@link DenominatorRef} names in the currently
     * accepted tree.
     * <p>
     * This is the one place the fix actually happens: a ref built against a previous pass's
     * tree still resolves here, because {@code (rootIndex, path)} is reproduced
     * byte-for-byte by {@code GateNode.deepCopy()}, unlike the fresh {@link Branch} objects
     * the copy mints. A {@code null} result is not an error — it is the honest answer when
     * the branch the user chose has genuinely gone (its gate was disabled, or deleted, or
     * renamed between passes), and the caller must clear the selection in exactly that case,
     * never in the "same population, new copy" case a non-null result reports.
     *
     * @return the live branch, or {@code null} when no currently offered option matches
     *         {@code ref}
     */
    public Branch resolveDenominator(DenominatorRef ref) {
        if (ref == null) return null;
        for (DenominatorOption option : denominatorOptions()) {
            if (option.ref().equals(ref)) return option.branch();
        }
        return null;
    }

    /**
     * Assigns each enabled root gate its {@code rootIndex}, in tree order, then walks its
     * whole subtree before moving to the next root — mirrors
     * {@link PopulationStats#collectFromRoots} exactly; see {@link #denominatorOptions()}.
     */
    private static void collectOptionsFromRoots(List<GateNode> roots, List<DenominatorOption> out) {
        if (roots == null) return;
        int rootIndex = 0;
        for (GateNode root : roots) {
            if (!root.isEnabled()) continue;
            collectOptions(List.of(root), "", rootIndex, out);
            rootIndex++;
        }
    }

    private static void collectOptions(List<GateNode> nodes, String prefix, int rootIndex,
                                       List<DenominatorOption> out) {
        if (nodes == null) return;
        for (GateNode node : nodes) {
            // Mirrors PopulationStats.collect: a disabled gate is a hard stop for its whole
            // subtree in GatingEngine.walkNode, so offering its branches as a denominator
            // would let the user pick one that produces no row at all.
            if (!node.isEnabled()) continue;
            for (Branch branch : node.getBranches()) {
                String path = prefix.isEmpty() ? branch.getName() : prefix + "/" + branch.getName();
                out.add(new DenominatorOption(new DenominatorRef(rootIndex, path), branch));
                collectOptions(branch.getChildren(), path, rootIndex, out);
            }
        }
    }
}
