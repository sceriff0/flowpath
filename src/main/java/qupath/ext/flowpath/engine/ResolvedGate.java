package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Region2DGate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A gate node with its axes already resolved: the {@link MeasuredColumn} per axis and the
 * percentile clip bounds, worked out once for a whole gating pass.
 * <p>
 * This exists for one reason. The per-cell walk runs over ~200k cells x every gate in the
 * tree, and it used to re-derive each axis inside that loop — building the resolved key
 * string, hashing it into {@code CellIndex}'s column cache, then hashing it again (twice)
 * into {@code MarkerStats} for each percentile bound. All of it produced the same answer
 * every time. {@link #compile} does that work once per gate; the walk then reads
 * {@code double[]} by index.
 * <p>
 * Compiling is also what registers every column the tree touches with {@link MarkerStats},
 * which is what {@code prepareResolvedColumns} used to do as a separate pre-pass that
 * could be — and repeatedly was — forgotten. Here it is not a step you can skip: the walk
 * has nothing to read from unless the gate was compiled.
 * <p>
 * Gates whose channels are absent from the index compile to {@code usable == false}; the
 * walk skips them exactly as it used to skip a negative marker index.
 */
final class ResolvedGate {

    /** The gate itself. Live properties (threshold, enabled, z-score flag) are read from here. */
    final GateNode node;

    /** Axis 0 column (the only axis of a threshold gate, X of a 2D gate); null if unusable. */
    final MeasuredColumn x;

    /** Axis 1 column (Y of a 2D gate); null for 1D gates and unusable gates. */
    final MeasuredColumn y;

    /** False when a channel is missing from the index — the walk contributes nothing. */
    final boolean usable;

    /** True for gates with a Y axis (quadrant and 2D region gates). */
    final boolean twoAxis;

    /**
     * The gate's z-score flag, snapshotted with the clip bounds. Reading it once per pass
     * rather than once per cell also means a toggle landing mid-walk cannot split one pass
     * across two coordinate spaces.
     */
    final boolean zScore;

    /** Percentile clip bounds per axis, NaN when outlier exclusion is off or unavailable. */
    final double clipLoX;
    final double clipHiX;
    final double clipLoY;
    final double clipHiY;

    /** {@code node.getBranches()} materialised once; the walk indexes it per cell. */
    final Branch[] branches;

    /** Compiled children, parallel to {@link #branches}. */
    final ResolvedGate[][] children;

    private ResolvedGate(GateNode node, MeasuredColumn x, MeasuredColumn y, boolean usable,
                         boolean twoAxis,
                         double clipLoX, double clipHiX, double clipLoY, double clipHiY,
                         Branch[] branches, ResolvedGate[][] children) {
        this.node = node;
        this.x = x;
        this.y = y;
        this.usable = usable;
        this.twoAxis = twoAxis;
        this.zScore = node.isThresholdIsZScore();
        this.clipLoX = clipLoX;
        this.clipHiX = clipHiX;
        this.clipLoY = clipLoY;
        this.clipHiY = clipHiY;
        this.branches = branches;
        this.children = children;
    }

    /**
     * Compile a forest of gates, registering every column they read with {@code stats}.
     * <p>
     * Disabled gates are compiled too: the walk decides enablement per cell, and the
     * columns of a temporarily disabled gate stay registered so the editor can still draw
     * its histogram.
     *
     * @param byNode optional identity map to fill with {@code node -> compiled}, for
     *               callers that need to look a specific gate back up (see
     *               {@code GatingEngine.computeAncestorMask}); may be {@code null}
     */
    static List<ResolvedGate> compile(List<GateNode> nodes, CellIndex index, MarkerStats stats,
                                      Map<GateNode, ResolvedGate> byNode) {
        List<ResolvedGate> out = new ArrayList<>();
        if (nodes == null) return out;
        for (GateNode node : nodes) {
            out.add(compileOne(node, index, stats, byNode));
        }
        return out;
    }

    /** Compile a single gate and, recursively, everything under it. */
    static ResolvedGate compileOne(GateNode node, CellIndex index, MarkerStats stats,
                                   Map<GateNode, ResolvedGate> byNode) {
        boolean twoAxis = node instanceof QuadrantGate || node instanceof Region2DGate;
        int arity = twoAxis ? 2 : 1;

        List<String> channels = node.getChannels();
        boolean usable = channels.size() >= arity;
        for (int k = 0; usable && k < arity; k++) {
            usable = index.getMarkerIndex(channels.get(k)) >= 0;
        }

        MeasuredColumn x = null;
        MeasuredColumn y = null;
        if (usable) {
            x = index.column(node, 0, stats);
            if (twoAxis) y = index.column(node, 1, stats);
            usable = x != null && (!twoAxis || y != null);
        }

        // Clip bounds are constant for the whole pass: they depend only on the column and
        // the gate's clip percentiles, both fixed while the walk runs.
        double loX = Double.NaN, hiX = Double.NaN, loY = Double.NaN, hiY = Double.NaN;
        if (usable && node.isExcludeOutliers()) {
            double pctLo = node.getClipPercentileLow();
            double pctHi = node.getClipPercentileHigh();
            loX = x.percentile(pctLo);
            hiX = x.percentile(pctHi);
            if (y != null) {
                loY = y.percentile(pctLo);
                hiY = y.percentile(pctHi);
            }
        }

        List<Branch> branchList = node.getBranches();
        Branch[] branches = branchList.toArray(new Branch[0]);
        ResolvedGate[][] children = new ResolvedGate[branches.length][];
        for (int b = 0; b < branches.length; b++) {
            children[b] = compile(branches[b].getChildren(), index, stats, byNode)
                    .toArray(new ResolvedGate[0]);
        }

        ResolvedGate compiled = new ResolvedGate(node, x, y, usable, twoAxis,
                loX, hiX, loY, hiY, branches, children);
        if (byNode != null) byNode.put(node, compiled);
        return compiled;
    }

    /**
     * <b>The</b> predicate: which branch does cell {@code cellIdx} fall into?
     * <p>
     * Two steps, and only two. <i>Sample resolution</i> lives here — read the axis columns
     * this gate compiled to, drop the cell if it is outside the percentile clip, and put
     * the values in the gate's own coordinate space (raw or z-scored). <i>Geometry</i>
     * lives on the gate: {@link GateNode#branchFor}, the same method
     * {@code ScatterPlotCanvas} colours its dots with. Nothing else in the codebase
     * decides which branch a cell lands in.
     * <p>
     * Everything expensive — key resolution, column lookup, clip bounds — happened in
     * {@link #compileOne}. This reads {@code double[]} by index.
     *
     * @return the branch index, or {@code -1} when the gate is unusable or the cell is
     *         outlier-clipped by it
     */
    int branchOf(int cellIdx) {
        return branchOf(cellIdx, true);
    }

    /**
     * The branch a cell would land in if its outlier clipping were ignored.
     * <p>
     * The walk needs this for the one case where "clipped" and "which branch" are both
     * true at once: a clipped cell is flagged as an outlier <em>and</em> still labelled,
     * so the CSV has a phenotype for every row. Called only on that rare path.
     */
    int branchIgnoringClip(int cellIdx) {
        return branchOf(cellIdx, false);
    }

    private int branchOf(int cellIdx, boolean honourClip) {
        if (!usable) return -1;
        double rawX = x.valueAt(cellIdx);
        if (honourClip && clipsX(rawX)) return -1;
        double vx = zScore ? x.toZScore(rawX) : rawX;
        if (!twoAxis) return node.branchFor(vx, 0.0);
        double rawY = y.valueAt(cellIdx);
        if (honourClip && clipsY(rawY)) return -1;
        double vy = zScore ? y.toZScore(rawY) : rawY;
        return node.branchFor(vx, vy);
    }

    /** True when {@code raw} falls outside this gate's X clip bounds. */
    boolean clipsX(double raw) {
        return !Double.isNaN(clipLoX) && !Double.isNaN(clipHiX) && (raw < clipLoX || raw > clipHiX);
    }

    /** True when {@code raw} falls outside this gate's Y clip bounds. */
    boolean clipsY(double raw) {
        return !Double.isNaN(clipLoY) && !Double.isNaN(clipHiY) && (raw < clipLoY || raw > clipHiY);
    }
}
