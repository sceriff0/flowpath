package qupath.ext.flowpath.model;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-branch cell counts, broken down by annotated region and by whether the cell was
 * cleanly judged — filled by {@code GatingEngine} during the walk it already performs.
 * <p>
 * <b>Why this is filled in the walk rather than computed afterwards.</b> The Analysis window
 * needs each branch's count per region, which means knowing which branch every cell landed
 * in. That answer already exists exactly once, inside {@code ResolvedGate.branchOf}, and
 * {@code CLAUDE.md} forbids a second implementation of it — five divergent copies is the
 * defect that invariant was written to end. Recomputing counts outside the walk would be a
 * sixth. So the walk records what it already decided, and this class is a passive receiver.
 * <p>
 * <b>Identity-keyed.</b> Branches are mutable and two branches can share a name
 * ({@code GateTree.findDuplicateLeafNames} exists precisely because they do), so the map is
 * an {@link IdentityHashMap}: a tally belongs to <em>this</em> branch object, not to any
 * branch that happens to be called the same thing.
 * <p>
 * A branch the walk never reached reports zero rather than throwing — a gate whose channel
 * is missing from the panel is ordinary input, not an error.
 * <p>
 * {@link #record(Branch, int, boolean)} and {@link #recordCell(int, boolean)} are {@code
 * public} because {@code GatingEngine} (package {@code qupath.ext.flowpath.engine}) is the
 * writer, but the intent is the same as package-private: the gating walk is the only
 * intended caller. Nothing outside that walk has the branch decision to record.
 */
public final class BranchTally {

    /** Counts for one branch: [0] raw total, [1] clean total, then per-region pairs. */
    private static final class Counts {
        int total;
        int clean;
        final int[] perRegion;
        final int[] perRegionClean;

        Counts(int regionCount) {
            perRegion = new int[regionCount];
            perRegionClean = new int[regionCount];
        }
    }

    private final Map<Branch, Counts> counts = new IdentityHashMap<>();
    private final int regionCount;

    private int cellsTotal;
    private int cellsClean;
    private final int[] cellsPerRegion;
    private final int[] cellsPerRegionClean;

    public BranchTally(int regionCount) {
        this.regionCount = Math.max(0, regionCount);
        this.cellsPerRegion = new int[this.regionCount];
        this.cellsPerRegionClean = new int[this.regionCount];
    }

    /**
     * Record one cell landing in one branch. The only intended caller is the gating walk in
     * {@code GatingEngine}, which has already decided the branch via
     * {@code ResolvedGate.branchOf} — this method does not re-derive it.
     *
     * @param region the cell's region index, or negative when it is in none
     * @param clean  false when the cell was outlier-clipped, quality-filtered, or a gate
     *               could not measure it
     */
    public void record(Branch branch, int region, boolean clean) {
        Counts c = counts.computeIfAbsent(branch, b -> new Counts(regionCount));
        c.total++;
        if (clean) c.clean++;
        if (region >= 0 && region < regionCount) {
            c.perRegion[region]++;
            if (clean) c.perRegionClean[region]++;
        }
    }

    /**
     * Record one cell's existence, independent of any branch — the denominators. The only
     * intended caller is the gating walk in {@code GatingEngine}.
     */
    public void recordCell(int region, boolean clean) {
        cellsTotal++;
        if (clean) cellsClean++;
        if (region >= 0 && region < regionCount) {
            cellsPerRegion[region]++;
            if (clean) cellsPerRegionClean[region]++;
        }
    }

    public int total(Branch branch) {
        Counts c = counts.get(branch);
        return c == null ? 0 : c.total;
    }

    public int clean(Branch branch) {
        Counts c = counts.get(branch);
        return c == null ? 0 : c.clean;
    }

    public int inRegion(Branch branch, int region) {
        Counts c = counts.get(branch);
        if (c == null || region < 0 || region >= regionCount) return 0;
        return c.perRegion[region];
    }

    public int cleanInRegion(Branch branch, int region) {
        Counts c = counts.get(branch);
        if (c == null || region < 0 || region >= regionCount) return 0;
        return c.perRegionClean[region];
    }

    public int regionCount() {
        return regionCount;
    }

    /** Every cell the walk saw. */
    public int cellsTotal() {
        return cellsTotal;
    }

    /** Cells that were cleanly judged — the clean denominator. */
    public int cellsClean() {
        return cellsClean;
    }

    public int cellsInRegion(int region) {
        return (region < 0 || region >= regionCount) ? 0 : cellsPerRegion[region];
    }

    public int cleanCellsInRegion(int region) {
        return (region < 0 || region >= regionCount) ? 0 : cellsPerRegionClean[region];
    }
}
