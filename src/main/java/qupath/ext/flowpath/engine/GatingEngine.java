package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.Branch;
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
        private final int[] colors;
        private final List<int[]> perRootColors;
        private final List<String> rootLabels;

        AssignmentResult(String[] phenotypes, boolean[] excluded,
                         boolean[] outOfAnnotation, boolean[] outlier,
                         int[] colors,
                         List<int[]> perRootColors, List<String> rootLabels) {
            this.phenotypes = phenotypes;
            this.excluded = excluded;
            this.outOfAnnotation = outOfAnnotation;
            this.outlier = outlier;
            this.colors = colors;
            this.perRootColors = perRootColors;
            this.rootLabels = rootLabels;
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
        int n = index.size();
        String[] phenotypes = new String[n];
        boolean[] excluded = new boolean[n];
        boolean[] outOfAnnotation = new boolean[n];
        boolean[] outlier = new boolean[n];
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

        // Walk every cell — excluded cells still get their would-have-been phenotype
        // for CSV export; branch counts skip increments when excluded[i] is true so the
        // visible counts in the UI continue to reflect non-excluded cells only.
        for (int i = 0; i < n; i++) {
            walkRoots(plan, i, phenotypes, excluded, outlier, colors, perRootColors);
        }

        return new AssignmentResult(phenotypes, excluded, outOfAnnotation, outlier,
                colors, perRootColors, rootLabels);
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
     */
    public static boolean[] computeRoiMask(CellIndex index, ROI roi) {
        int n = index.size();
        boolean[] mask = new boolean[n];
        if (roi == null) {
            Arrays.fill(mask, true);
            return mask;
        }
        for (int i = 0; i < n; i++) {
            PathObject obj = index.getObject(i);
            ROI cellRoi = (obj != null) ? obj.getROI() : null;
            mask[i] = (cellRoi != null) && roi.contains(cellRoi.getCentroidX(), cellRoi.getCentroidY());
        }
        return mask;
    }

    /**
     * Compute a boolean mask indicating which cells fall inside any of the given ROIs.
     * If the collection is empty, all cells are excluded.
     */
    public static boolean[] computeRoiMask(CellIndex index, Collection<ROI> rois) {
        int n = index.size();
        boolean[] mask = new boolean[n];
        if (rois.isEmpty()) return mask;
        for (int i = 0; i < n; i++) {
            PathObject obj = index.getObject(i);
            ROI cellRoi = (obj != null) ? obj.getROI() : null;
            if (cellRoi == null) continue;
            double cx = cellRoi.getCentroidX();
            double cy = cellRoi.getCentroidY();
            for (ROI roi : rois) {
                if (roi.contains(cx, cy)) {
                    mask[i] = true;
                    break;
                }
            }
        }
        return mask;
    }

    /**
     * Combine two boolean masks with logical AND. Both arrays must have the same length.
     */
    public static boolean[] combineMasks(boolean[] a, boolean[] b) {
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
            if (!gate.isEnabled()) continue;

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

    private static void walkRoots(List<ResolvedGate> roots, int cellIdx,
                                  String[] phenotypes, boolean[] excluded, boolean[] outlier,
                                  int[] colors, List<int[]> perRootColors) {
        if (perRootColors == null) {
            // Single-root fast path — walk all roots regardless of exclusion so excluded
            // cells still get a phenotype for CSV. Count increments inside walkNode are
            // guarded by excluded[] to keep UI counts consistent.
            for (ResolvedGate root : roots) {
                walkNode(root, cellIdx, phenotypes, excluded, outlier, colors);
            }
            return;
        }

        // Multi-root: collect per-root phenotypes, then build composite
        List<String> contributions = new ArrayList<>();
        List<Integer> contributedColors = new ArrayList<>();
        int enabledIdx = 0;

        for (ResolvedGate root : roots) {
            if (!root.node.isEnabled()) continue;

            // Clean slate for this root's walk
            phenotypes[cellIdx] = "Unclassified";
            colors[cellIdx] = 0;

            walkNode(root, cellIdx, phenotypes, excluded, outlier, colors);

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
    private static void walkNode(ResolvedGate rg, int cellIdx,
                                 String[] phenotypes, boolean[] excluded, boolean[] outlier, int[] colors) {
        if (!rg.node.isEnabled()) return;
        if (!rg.usable) return;

        int branchIdx = rg.branchOf(cellIdx);
        if (branchIdx < 0) {
            // Outlier exclusion based on this gate's percentile clip bounds. Flag the cell
            // but keep walking, so the CSV still receives a phenotype for it; the branch
            // counts skip it because assignBranch checks excluded[].
            outlier[cellIdx] = true;
            excluded[cellIdx] = true;
            branchIdx = rg.branchIgnoringClip(cellIdx);
        }
        assignBranch(rg, branchIdx, cellIdx, phenotypes, excluded, outlier, colors);
    }

    /**
     * Land a cell in one of the gate's branches: count it (unless excluded), label it,
     * then descend into that branch's children.
     */
    private static void assignBranch(ResolvedGate rg, int branchIdx, int cellIdx,
                                      String[] phenotypes,
                                      boolean[] excluded, boolean[] outlier, int[] colors) {
        Branch branch = rg.branches[branchIdx];
        if (!excluded[cellIdx]) {
            branch.setCount(branch.getCount() + 1);
        }
        phenotypes[cellIdx] = branch.getName();
        colors[cellIdx] = branch.getColor();
        for (ResolvedGate child : rg.children[branchIdx]) {
            walkNode(child, cellIdx, phenotypes, excluded, outlier, colors);
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
