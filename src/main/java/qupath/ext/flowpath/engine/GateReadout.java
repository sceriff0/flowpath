package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Read-only access, from outside the engine, to {@link ResolvedGate#branchOf} — the one
 * predicate — for any gate of a compiled tree.
 * <p>
 * Exists so a consumer that needs a per-gate answer rather than a phenotype (the CSV
 * {@code _sign} column) asks the same code the walk asks instead of re-typing the comparison.
 * {@code PhenotypeCsvExporter} used to carry its own copy — a second implementation of "which
 * side of this gate is the cell on", with its own sample resolution and its own handling of a
 * flat column — which is exactly the drift the one-gate-predicate invariant forbids.
 */
public final class GateReadout {

    /** The gate has no value to judge this cell on — see {@link ResolvedGate#UNMEASURED}. */
    public static final int UNMEASURED = ResolvedGate.UNMEASURED;

    private final Map<GateNode, ResolvedGate> byNode;

    private GateReadout(Map<GateNode, ResolvedGate> byNode) {
        this.byNode = byNode;
    }

    /**
     * Compile every gate of {@code tree} (disabled ones too) against {@code index}, registering
     * each axis column with {@code stats} exactly as a gating pass does.
     */
    public static GateReadout compile(GateTree tree, CellIndex index, MarkerStats stats) {
        Map<GateNode, ResolvedGate> byNode = new IdentityHashMap<>();
        ResolvedGate.compile(tree.getRoots(), index, stats, byNode);
        return new GateReadout(byNode);
    }

    /**
     * The branch of {@code gate} that cell {@code cellIdx} falls into, ignoring the gate's
     * outlier clipping — a clipped cell has a real value and the walk labels it with this
     * same branch — or {@link #UNMEASURED}.
     *
     * @throws IllegalArgumentException if {@code gate} is not part of the compiled tree
     */
    public int branchIgnoringClip(GateNode gate, int cellIdx) {
        ResolvedGate resolved = byNode.get(gate);
        if (resolved == null) {
            throw new IllegalArgumentException("gate is not part of the compiled tree: " + gate);
        }
        return resolved.branchIgnoringClip(cellIdx);
    }
}
