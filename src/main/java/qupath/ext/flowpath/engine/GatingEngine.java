package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core gating logic that walks a {@link GateTree} and assigns phenotype labels
 * and colors to every cell in a {@link CellIndex}.
 */
public final class GatingEngine {

    private static final Logger logger = LoggerFactory.getLogger(GatingEngine.class);

    private GatingEngine() {
        // static utility class
    }

    /**
     * Result of running the gating engine over all cells.
     */
    public static final class AssignmentResult {
        private final String[] phenotypes;
        private final boolean[] excluded;
        private final boolean[] outOfAnnotation;
        private final boolean[] outlier;
        private final boolean[] unmeasured;
        private final int[] colors;
        private final List<int[]> perRootColors;
        private final List<String> rootLabels;
        private final BranchTally tally;

        AssignmentResult(String[] phenotypes, boolean[] excluded,
                         boolean[] outOfAnnotation, boolean[] outlier,
                         boolean[] unmeasured, int[] colors,
                         List<int[]> perRootColors, List<String> rootLabels,
                         BranchTally tally) {
            this.phenotypes = phenotypes;
            this.excluded = excluded;
            this.outOfAnnotation = outOfAnnotation;
            this.outlier = outlier;
            this.unmeasured = unmeasured;
            this.colors = colors;
            this.perRootColors = perRootColors;
            this.rootLabels = rootLabels;
            this.tally = tally;
        }

        /**
         * Phenotype label per cell. Always populated (never {@code null}) — excluded cells
         * still receive the phenotype they would have been assigned if not excluded.
         * Visual filtering in QuPath is driven by {@link #getExcluded()}.
         */
        public String[] getPhenotypes() {
            return phenotypes;
        }

        /** {@code true} for cells removed by ROI mask, quality filter, or outlier exclusion. */
        public boolean[] getExcluded() {
            return excluded;
        }

        /** {@code true} for cells that fell outside the ROI annotation mask. */
        public boolean[] getOutOfAnnotation() {
            return outOfAnnotation;
        }

        /**
         * {@code true} for cells rejected by the quality filter or by a gate's
         * per-channel percentile clipping.
         */
        public boolean[] getOutlier() {
            return outlier;
        }

        /**
         * {@code true} for cells that reached a gate which had no measurement for them --
         * the marker is absent from that cell's GeoJSON feature, so the column reads
         * {@code NaN}.
         * <p>
         * Such a cell keeps whatever phenotype its ancestors gave it and is <em>not</em>
         * counted in either branch of the gate that could not judge it. It is not
         * {@link #getExcluded() excluded}: the ancestor classification is real information
         * and the cell stays visible under it. Before this flag existed the cell was
         * silently assigned the negative branch and counted there, so a marker missing on
         * 5% of cells inflated that marker's negative population by 5%.
         *
         * @see #getPhenotypes()
         */
        public boolean[] getUnmeasured() {
            return unmeasured;
        }

        /** Packed RGB color per cell (default: last root's color), 0 for excluded cells. */
        public int[] getColors() {
            return colors;
        }

        /**
         * Per-root color arrays for multi-root trees.
         * Each entry is a {@code int[cellCount]} with that root's leaf branch colors.
         * {@code null} when only a single enabled root exists.
         */
        public List<int[]> getPerRootColors() {
            return perRootColors;
        }

        /**
         * Names of enabled root gates, matching the order of {@link #getPerRootColors()}.
         * {@code null} when only a single enabled root exists.
         */
        public List<String> getRootLabels() {
            return rootLabels;
        }

        /**
         * Per-region breakdown of the same counts {@link Branch#getCount()} carries,
         * built alongside them during the same walk rather than recomputed from it.
         */
        public BranchTally getTally() {
            return tally;
        }
    }

    /**
     * Assign phenotypes to every cell by walking the gate tree.
     * Delegates to {@link #assignAll(GateTree, CellIndex, MarkerStats, boolean[])}
     * with no ROI mask.
     */
    public static AssignmentResult assignAll(GateTree tree, CellIndex index, MarkerStats stats) {
        return assignAll(tree, index, stats, null);
    }

    /**
     * Assign phenotypes to every cell by walking the gate tree.
     * Delegates to {@link #assignAll(GateTree, CellIndex, MarkerStats, boolean[], int[], int)}
     * with no region breakdown ({@code regionOf = null, regionCount = 0}).
     *
     * @param tree      the gate tree (roots + quality filter)
     * @param index     columnar cell data
     * @param stats     per-marker statistics (mean, std, percentiles)
     * @param roiMask   optional boolean mask where {@code true} means the cell is inside the ROI;
     *                  {@code null} means no ROI filtering
     * @return assignment result with phenotypes, exclusion flags, and colors
     */
    public static AssignmentResult assignAll(GateTree tree, CellIndex index, MarkerStats stats,
                                              boolean[] roiMask) {
        return assignAll(tree, index, stats, roiMask, null, 0);
    }

    /**
     * Assign phenotypes to every cell by walking the gate tree, additionally tallying every
     * branch's cell count broken down by annotated region and by whether the cell was
     * cleanly judged.
     *
     * @param tree        the gate tree (roots + quality filter)
     * @param index       columnar cell data
     * @param stats       per-marker statistics (mean, std, percentiles)
     * @param roiMask     optional boolean mask where {@code true} means the cell is inside
     *                    the ROI; {@code null} means no ROI filtering
     * @param regionOf    per-cell region index from {@code RegionMask.regionOf()}, or a
     *                    negative value for a cell in no region; {@code null} means no
     *                    region breakdown
     * @param regionCount number of named regions, matching {@code RegionMask.regionNames()};
     *                    ignored when {@code regionOf} is {@code null}
     * @return assignment result with phenotypes, exclusion flags, colors, and the region/
     *         cleanliness tally
     */
    public static AssignmentResult assignAll(GateTree tree, CellIndex index, MarkerStats stats,
                                              boolean[] roiMask, int[] regionOf, int regionCount) {
        int n = index.size();
        if (regionOf != null && regionOf.length != n) {
            throw new IllegalArgumentException(
                    "regionOf describes a different population than the index: "
                            + regionOf.length + " vs " + n + " cells");
        }
        String[] phenotypes = new String[n];
        boolean[] excluded = new boolean[n];
        boolean[] outOfAnnotation = new boolean[n];
        boolean[] outlier = new boolean[n];
        boolean[] unmeasured = new boolean[n];
        int[] colors = new int[n];

        // 0. Resolve every gate axis to a MeasuredColumn (which registers its stats),
        //    once, before the per-cell walk. This replaces the old prepareResolvedColumns
        //    pre-pass: registration is no longer a separate step that could be skipped.
        List<ResolvedGate> plan = ResolvedGate.compile(tree.getRoots(), index, stats, null);

        // 1. Initialize all as Unclassified
        for (int i = 0; i < n; i++) {
            phenotypes[i] = "Unclassified";
        }

        // 2. Apply quality filter — flag as outlier but keep phenotype computation going
        QualityFilter qf = tree.getQualityFilter();
        if (qf != null) {
            for (int i = 0; i < n; i++) {
                // Every morphology field the export carries, not the five FlowPath used
                // to know about -- see QualityFilter.passes(CellIndex, int).
                if (!qf.passes(index, i)) {
                    outlier[i] = true;
                    excluded[i] = true;
                }
            }
        }

        // 2b. Apply ROI mask — cells outside the annotation are flagged but still walked
        if (roiMask != null) {
            for (int i = 0; i < n; i++) {
                if (!roiMask[i]) {
                    outOfAnnotation[i] = true;
                    excluded[i] = true;
                }
            }
        }

        // 3. Walk gate tree for non-excluded cells
        // Reset transient counts on all nodes before walking
        List<GateNode> roots = tree.getRoots();
        resetCounts(roots);

        // Count enabled roots to decide single vs. multi-root mode
        List<ResolvedGate> enabledRoots = new ArrayList<>();
        for (ResolvedGate root : plan) {
            if (root.node.isEnabled()) enabledRoots.add(root);
        }
        boolean multiRoot = enabledRoots.size() > 1;

        // Detect duplicate leaf names only when multiple roots exist
        if (multiRoot) {
            Map<String, List<Integer>> duplicates = tree.findDuplicateLeafNames();
            if (!duplicates.isEmpty()) {
                logger.warn("Duplicate leaf branch names across roots: {}", duplicates.keySet());
            }
        }

        // Allocate per-root color arrays for multi-root mode
        List<int[]> perRootColors = null;
        List<String> rootLabels = null;
        if (multiRoot) {
            perRootColors = new ArrayList<>();
            rootLabels = new ArrayList<>();
            for (ResolvedGate root : enabledRoots) {
                perRootColors.add(new int[n]);
                rootLabels.add(root.node.getChannels().isEmpty()
                        ? "Root" : root.node.getChannels().get(0));
            }
        }

        // Exclusion as it stands before any gate has run: quality filter and ROI mask only.
        // A root's own outlier clipping is layered on top of this per root, never carried
        // from one root into the next -- see walkRoots.
        boolean[] baseExcluded = excluded.clone();

        BranchTally tally = new BranchTally(regionCount);
        WalkContext ctx = new WalkContext(phenotypes, excluded, baseExcluded, outlier,
                unmeasured, colors, perRootColors, regionOf, tally);

        // Walk every cell — excluded cells still get their would-have-been phenotype
        // for CSV export; branch counts skip increments when excluded[i] is true so the
        // visible counts in the UI continue to reflect non-excluded cells only.
        for (int i = 0; i < n; i++) {
            walkRoots(plan, i, ctx);
        }

        // Cell-level tallying runs after the walk, not folded into it: baseExcluded is
        // already final at this point, but doing it in the same pass as the walk would be
        // an easy place to accidentally read excluded[]/unmeasured[] instead, which are
        // still changing mid-walk for multi-root cells (re-walked per root above).
        for (int i = 0; i < n; i++) {
            int region = regionOf == null ? -1 : regionOf[i];
            // "Clean" here is the denominator clean(branch) is checked against, so it must
            // use the same exclusion baseExcluded[] already means: quality filter + ROI
            // mask only. Not excluded[i] (which also carries a gate's own clipping) and not
            // unmeasured[i] -- a per-branch clean count can only ever be <= this denominator
            // because a cell can only land in a branch when it was not base-excluded in the
            // first place. Using excluded[]/unmeasured[] here instead would let a gate's own
            // clipping shrink the denominator differently for cells that never reached that
            // gate, breaking that bound.
            tally.recordCell(region, !baseExcluded[i]);
        }

        return new AssignmentResult(phenotypes, excluded, outOfAnnotation, outlier,
                unmeasured, colors, perRootColors, rootLabels, tally);
    }

    /**
     * Compute a boolean mask indicating which cells pass the quality filter.
     *
     * @param index  columnar cell data
     * @param filter quality filter criteria
     * @return boolean array where {@code true} means the cell passes
     */
    public static boolean[] computeQualityMask(CellIndex index, QualityFilter filter) {
        int n = index.size();
        boolean[] mask = new boolean[n];
        for (int i = 0; i < n; i++) {
            mask[i] = filter.passes(index, i);
        }
        return mask;
    }

    /**
     * Compute a boolean mask indicating which cells fall inside the given ROI.
     * If {@code roi} is {@code null}, all cells pass.
     * <p>
     * A cell is tested by its <b>centroid</b>, in level-0 pixels, against the annotation's
     * own pixel geometry — see the coordinate-space invariant in {@code CellGeometry}. A
     * cell straddling the boundary therefore falls on the side its centre does.
     * <p>
     * Disjoint and holed annotations need no special handling here: a QuPath {@code ROI}
     * backed by a multi-part or holed geometry answers {@link ROI#contains} correctly for
     * every part and every hole, so one annotation drawn as several separate islands
     * behaves exactly like the same islands drawn as separate annotations.
     */
    public static boolean[] computeRoiMask(CellIndex index, ROI roi) {
        if (roi == null) {
            boolean[] mask = new boolean[index.size()];
            Arrays.fill(mask, true);
            return mask;
        }
        return computeRoiMask(index, List.of(roi));
    }

    /**
     * Compute a boolean mask indicating which cells fall inside any of the given ROIs.
     * If the collection is empty, all cells are excluded.
     * <p>
     * The ROIs are combined by union: a cell passes if it is inside <em>any</em> of them.
     * <p>
     * <b>Bounding-box prefilter.</b> {@link ROI#contains} on a polygon annotation is a
     * full point-in-polygon test through JTS, and this mask is recomputed on every
     * annotation edit, so it sits in the interactive path. Annotations are typically small
     * regions on a large slide, which makes the overwhelmingly common answer "nowhere
     * near". Each ROI's envelope is read once outside the loop and rejects those cells
     * with four comparisons. Measured on 200k cells against four 200-vertex polygons: the
     * envelopes reject 97% of cells, taking the pass from ~286ms to ~23ms. The envelope is
     * by definition a superset of the geometry, so this rejects nothing
     * {@code contains} would have accepted — the mask is unchanged.
     */
    public static boolean[] computeRoiMask(CellIndex index, Collection<ROI> rois) {
        int n = index.size();
        boolean[] mask = new boolean[n];
        if (rois.isEmpty()) return mask;

        ROI[] roiArray = rois.toArray(new ROI[0]);
        int r = roiArray.length;
        double[] minX = new double[r], maxX = new double[r];
        double[] minY = new double[r], maxY = new double[r];
        for (int j = 0; j < r; j++) {
            ROI roi = roiArray[j];
            minX[j] = roi.getBoundsX();
            minY[j] = roi.getBoundsY();
            maxX[j] = minX[j] + roi.getBoundsWidth();
            maxY[j] = minY[j] + roi.getBoundsHeight();
        }

        for (int i = 0; i < n; i++) {
            PathObject obj = index.getObject(i);
            ROI cellRoi = (obj != null) ? obj.getROI() : null;
            if (cellRoi == null) continue;
            double cx = cellRoi.getCentroidX();
            double cy = cellRoi.getCentroidY();
            for (int j = 0; j < r; j++) {
                if (cx < minX[j] || cx > maxX[j] || cy < minY[j] || cy > maxY[j]) continue;
                if (roiArray[j].contains(cx, cy)) {
                    mask[i] = true;
                    break;
                }
            }
        }
        return mask;
    }

    /**
     * Combine two boolean masks with logical AND.
     *
     * @throws IllegalArgumentException if the masks describe different populations.
     *         Every mask here is positional against {@code CellIndex.getObjects()}, so a
     *         length mismatch means two different cell populations are being combined --
     *         which silently truncated to the shorter answer when {@code b} was longer,
     *         and threw an unexplained {@code ArrayIndexOutOfBoundsException} when it was
     *         shorter. Both are worse than saying so.
     */
    public static boolean[] combineMasks(boolean[] a, boolean[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Cannot combine masks over different populations: " + a.length
                            + " vs " + b.length + " cells");
        }
        boolean[] result = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] && b[i];
        }
        return result;
    }

    /**
     * Compute a boolean mask indicating which cells would reach a specific gate node
     * by passing through all ancestor gates/branches in the tree hierarchy.
     * Root gates get all non-excluded cells. Child gates only get cells that passed
     * through their parent gate's branch.
     *
     * @param tree      the gate tree
     * @param target    the gate node to compute the ancestor mask for
     * @param index     columnar cell data
     * @param stats     per-marker statistics
     * @param baseMask  optional base mask (ROI + quality); null means all cells pass
     * @return boolean array where {@code true} means the cell reaches this gate
     */
    public static boolean[] computeAncestorMask(GateTree tree, GateNode target,
                                                 CellIndex index, MarkerStats stats,
                                                 boolean[] baseMask) {
        int n = index.size();
        boolean[] mask = new boolean[n];

        // Resolve every gate axis once (which registers its stats), keeping an identity
        // map so each ancestor on the path can be looked up outside the per-cell loop.
        Map<GateNode, ResolvedGate> byNode = new IdentityHashMap<>();
        ResolvedGate.compile(tree.getRoots(), index, stats, byNode);

        // Find the path from root to the target node
        java.util.List<Object> path = new java.util.ArrayList<>();
        if (!findPath(tree.getRoots(), target, path)) {
            // findPath returns false for both root nodes and nodes not in the tree.
            // Only fill with true if the target is actually a root node.
            if (tree.getRoots().contains(target)) {
                if (baseMask != null) {
                    System.arraycopy(baseMask, 0, mask, 0, n);
                } else {
                    Arrays.fill(mask, true);
                }
            }
            // If target is not in the tree at all, mask stays all-false
            return mask;
        }

        // Start with base mask (all cells that pass QF + ROI)
        if (baseMask != null) {
            System.arraycopy(baseMask, 0, mask, 0, n);
        } else {
            Arrays.fill(mask, true);
        }

        // Walk path: each entry is alternating GateNode, Branch (the branch the child is under)
        // For each ancestor gate+branch pair, keep only cells that land in that branch
        for (int p = 0; p < path.size(); p += 2) {
            GateNode gate = (GateNode) path.get(p);
            Branch branch = (Branch) path.get(p + 1);

            // A disabled gate is a hard stop for its whole subtree: walkNode returns
            // before descending, so no descendant of a disabled gate is ever evaluated.
            // Treating it as transparent here (the old `continue`) made this mask claim
            // every cell reaches the target while the engine classified none of them --
            // the plot for such a gate drew the full population against a phenotype
            // column that never mentioned it.
            if (!gate.isEnabled()) {
                Arrays.fill(mask, false);
                return mask;
            }

            // Resolved once per ancestor, outside the cell loop.
            ResolvedGate resolved = byNode.get(gate);
            if (resolved == null) continue;
            int branchIdx = gate.getBranches().indexOf(branch);
            for (int i = 0; i < n; i++) {
                if (!mask[i]) continue;
                int result = resolved.branchOf(i);
                if (result < 0 || result != branchIdx) {
                    mask[i] = false;
                }
            }
        }

        return mask;
    }

    /**
     * Find the path of (GateNode, Branch) pairs from a root to the target node.
     * Returns true if target is found as a child (not a root).
     */
    private static boolean findPath(List<GateNode> nodes, GateNode target, List<Object> path) {
        for (GateNode node : nodes) {
            for (Branch branch : node.getBranches()) {
                if (branch.getChildren().contains(target)) {
                    path.add(node);
                    path.add(branch);
                    return true;
                }
                // Recurse deeper
                if (findPath(branch.getChildren(), target, path)) {
                    path.add(0, node);
                    path.add(1, branch);
                    return true;
                }
            }
        }
        return false;
    }

    // ---- private helpers ----

    /**
     * The arrays a per-cell walk reads and writes, gathered into one record so
     * {@code walkRoots}/{@code walkNode}/{@code assignBranch} take one parameter for them
     * instead of widening a positional parameter list every time a new one is needed —
     * {@code region} and {@code tally} are the ones this class added. Introduced as one
     * deliberate refactor rather than by growing the existing signatures further.
     */
    private record WalkContext(String[] phenotypes, boolean[] excluded, boolean[] baseExcluded,
                                boolean[] outlier, boolean[] unmeasured, int[] colors,
                                List<int[]> perRootColors, int[] regionOf, BranchTally tally) {

        /** This cell's region index, or -1 when no region breakdown was requested. */
        int regionOf(int cellIdx) {
            return regionOf == null ? -1 : regionOf[cellIdx];
        }
    }

    private static void walkRoots(List<ResolvedGate> roots, int cellIdx, WalkContext ctx) {
        if (ctx.perRootColors() == null) {
            // Single-root fast path — walk all roots regardless of exclusion so excluded
            // cells still get a phenotype for CSV. Count increments inside walkNode are
            // guarded by excluded[] to keep UI counts consistent.
            for (ResolvedGate root : roots) {
                walkNode(root, cellIdx, ctx);
            }
            return;
        }

        // Multi-root: collect per-root phenotypes, then build composite
        List<String> contributions = new ArrayList<>();
        List<Integer> contributedColors = new ArrayList<>();
        int enabledIdx = 0;

        String[] phenotypes = ctx.phenotypes();
        boolean[] excluded = ctx.excluded();
        boolean[] baseExcluded = ctx.baseExcluded();
        int[] colors = ctx.colors();
        List<int[]> perRootColors = ctx.perRootColors();

        // Roots are independent views of the same cells, so each one is walked against the
        // same starting exclusion (quality filter + ROI) and its own outlier clipping is
        // discarded before the next root runs. Carrying `excluded` straight through made
        // branch counts depend on the order roots happened to be added: a cell clipped by
        // root A stopped counting in root B, but a cell clipped by root B had already been
        // counted by root A. The union is restored below, because QuPath's visual filtering
        // still wants "excluded by anything".
        boolean anyExcluded = baseExcluded[cellIdx];

        for (ResolvedGate root : roots) {
            if (!root.node.isEnabled()) continue;

            // Clean slate for this root's walk
            phenotypes[cellIdx] = "Unclassified";
            colors[cellIdx] = 0;
            excluded[cellIdx] = baseExcluded[cellIdx];

            walkNode(root, cellIdx, ctx);

            anyExcluded |= excluded[cellIdx];

            // Capture this root's per-cell color
            perRootColors.get(enabledIdx)[cellIdx] = colors[cellIdx];

            // Collect non-Unclassified phenotypes for composite
            String rootPheno = phenotypes[cellIdx];
            if (rootPheno != null && !"Unclassified".equals(rootPheno)) {
                contributions.add(rootPheno);
                contributedColors.add(colors[cellIdx]);
            }
            enabledIdx++;
        }

        // Restore the union: a cell any root excluded is excluded overall.
        excluded[cellIdx] = anyExcluded;

        // Build composite phenotype using QuPath derived PathClass separator ": "
        if (contributions.isEmpty()) {
            phenotypes[cellIdx] = "Unclassified";
            colors[cellIdx] = 0;
        } else if (contributions.size() == 1) {
            phenotypes[cellIdx] = contributions.get(0);
            colors[cellIdx] = contributedColors.get(0);
        } else {
            phenotypes[cellIdx] = String.join(": ", contributions);
            // Default color: last contributing root
            colors[cellIdx] = contributedColors.get(contributedColors.size() - 1);
        }
    }

    /**
     * Walk one gate for one cell: ask the single predicate which branch the cell is in,
     * then do the side effects. There is deliberately no per-gate-type walk method — the
     * branch decision is {@link ResolvedGate#branchOf} for every gate type, and the only
     * thing left here is bookkeeping.
     */
    private static void walkNode(ResolvedGate rg, int cellIdx, WalkContext ctx) {
        if (!rg.node.isEnabled()) return;
        if (!rg.usable) return;

        boolean[] excluded = ctx.excluded();
        boolean[] unmeasured = ctx.unmeasured();
        boolean[] outlier = ctx.outlier();

        int branchIdx = rg.branchOf(cellIdx);

        if (branchIdx == ResolvedGate.UNMEASURED) {
            // This gate has no value for this cell, so it gets no opinion about it. The
            // cell keeps the phenotype its ancestors gave it, is counted in none of this
            // gate's branches, and the walk stops here rather than descending into a
            // subtree that would be judging it on the same absent data.
            unmeasured[cellIdx] = true;
            return;
        }

        if (branchIdx == ResolvedGate.CLIPPED) {
            // Outlier exclusion based on this gate's percentile clip bounds. Unlike an
            // unmeasured cell, a clipped one has a real value and so has a real branch --
            // it is merely extreme. Flag it but keep walking, so the CSV still receives a
            // phenotype for it; the branch counts skip it because assignBranch checks
            // excluded[].
            outlier[cellIdx] = true;
            excluded[cellIdx] = true;
            branchIdx = rg.branchIgnoringClip(cellIdx);
        }
        assignBranch(rg, branchIdx, cellIdx, ctx);
    }

    /**
     * Land a cell in one of the gate's branches: count it (unless excluded), label it,
     * then descend into that branch's children.
     */
    private static void assignBranch(ResolvedGate rg, int branchIdx, int cellIdx, WalkContext ctx) {
        boolean[] excluded = ctx.excluded();

        Branch branch = rg.branches[branchIdx];
        if (!excluded[cellIdx]) {
            branch.setCount(branch.getCount() + 1);
        }
        // Same decision, recorded with its region and cleanliness. Not a second predicate:
        // branchIdx was decided once, by ResolvedGate.branchOf, above. "Clean" here is
        // judged by !excluded[cellIdx] alone -- the exact condition branch.getCount() just
        // used above -- so tally.clean(branch) is identical to branch.getCount() by
        // construction. unmeasured plays no part: a cell that could not be measured never
        // reaches assignBranch at all (walkNode returns on UNMEASURED before calling this),
        // so it is already absent from every branch's count without this method saying so.
        ctx.tally().record(branch, ctx.regionOf(cellIdx), !excluded[cellIdx]);

        ctx.phenotypes()[cellIdx] = branch.getName();
        ctx.colors()[cellIdx] = branch.getColor();
        for (ResolvedGate child : rg.children[branchIdx]) {
            walkNode(child, cellIdx, ctx);
        }
    }

    private static void resetCounts(List<GateNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (GateNode node : nodes) {
            for (Branch branch : node.getBranches()) {
                branch.setCount(0);
                resetCounts(branch.getChildren());
            }
        }
    }
}
