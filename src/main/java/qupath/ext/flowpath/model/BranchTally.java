package qupath.ext.flowpath.model;

import java.util.IdentityHashMap;
import java.util.List;
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
 * <p>
 * <b>What "clean" means, and why the two clean fields use different flags.</b>
 * {@link #clean(Branch)} is judged with the exact exclusion flag a cell had <em>at the
 * moment it landed in that branch</em> — the same flag {@code Branch.getCount()} was just
 * incremented under — so {@code clean(branch) == branch.getCount()} by construction; that
 * parity is the whole reason the tree view and the Analysis window's <em>Clean</em> column
 * can never disagree about the same population. {@link #cellsClean()} is judged with a
 * coarser flag: excluded by the quality filter or ROI mask only, before any individual
 * gate's own outlier clipping is applied. A cell can only land in a branch at all if it
 * passed that coarser exclusion, so {@code clean(branch) <= cellsClean()} holds for every
 * branch — the bound percentage displays depend on — which would not hold if the two fields
 * were judged by the same, finer-grained flag.
 * <p>
 * <b>"Clean" also means "inside the annotation", and the column says so.</b>
 * {@code GatingEngine} folds the annotation ROI mask into the same exclusion flag as the
 * quality filter, so a cell outside the annotations being filtered by is not clean here —
 * even though annotation membership is not a data-quality property. That is deliberate and
 * must stay: it is what makes {@code clean(branch) <= cellsClean()} hold structurally for
 * every branch, the bound every percentage display depends on. The cost is that at whole-slide
 * scope on an annotated slide, part of the raw/clean gap is annotation coverage rather than
 * data quality — so the meaning is spelled out in {@code PopulationStats.Row.cleanCount()}
 * and in the Analysis window's own column tooltip rather than left to be inferred from the
 * word "clean".
 * <p>
 * <b>Unmeasured cells play no part in either clean field.</b> A cell a gate could not
 * measure never reaches {@code assignBranch} at all — the walk returns before recording
 * anything for it at that gate — so it is already absent from every branch count without
 * this class needing to check for it, and it is reported separately through
 * {@code AssignmentResult.getUnmeasured()}. Do not reintroduce an unmeasured check into
 * either clean flag; it would be redundant at best, and at worst — if a stale unmeasured
 * flag ever survived across a multi-root re-walk — it would corrupt a count this class was
 * never told to exclude that cell from.
 */
public final class BranchTally {

    /** One branch's counts: a raw and a clean total, plus a raw and a clean total per region. */
    private static final class Counts {
        int total;
        int clean;
        final int[] perRegion;
        final int[] perRegionClean;

        Counts(int regionCount) {
            perRegion = new int[regionCount];
            perRegionClean = new int[regionCount];
        }

        Counts copy() {
            Counts c = new Counts(perRegion.length);
            c.total = total;
            c.clean = clean;
            System.arraycopy(perRegion, 0, c.perRegion, 0, perRegion.length);
            System.arraycopy(perRegionClean, 0, c.perRegionClean, 0, perRegionClean.length);
            return c;
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
     * @param clean  {@code true} unless the cell was excluded at the moment it landed in
     *               this branch — pass the negation of the same flag
     *               {@code branch.getCount()} was just guarded by, so {@link #clean(Branch)}
     *               tracks {@code Branch.getCount()} exactly. That flag covers the quality
     *               filter, this gate's own outlier clipping <em>and</em> the annotation ROI
     *               mask; see the class javadoc for why the last of those belongs in it.
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
     *
     * @param region the cell's region index, or negative when it is in none
     * @param clean  {@code true} unless the cell was excluded by the quality filter or ROI
     *               mask — <em>not</em> by an individual gate's own outlier clipping, and
     *               not by whether a gate could measure it. See the class javadoc for why
     *               this denominator is deliberately coarser than {@link #clean(Branch)}.
     */
    public void recordCell(int region, boolean clean) {
        cellsTotal++;
        if (clean) cellsClean++;
        if (region >= 0 && region < regionCount) {
            cellsPerRegion[region]++;
            if (clean) cellsPerRegionClean[region]++;
        }
    }

    /**
     * Re-key this tally from the branches the gating walk actually saw onto the corresponding
     * branches of the live tree the caller holds.
     * <p>
     * <b>Why this is needed at all.</b> {@code LivePreviewService} deep-copies the gate tree
     * before walking it on a background thread, and {@code GateNode.deepCopy()} constructs
     * fresh {@link Branch} objects. Because this class is identity-keyed (see the class
     * javadoc — deliberately, so two same-named branches stay separate), a tally filled
     * during that walk is keyed on the copy's branches while every consumer holds the live
     * tree's. {@code GateTree.transferCounts} reconciles only {@link Branch#getCount()}, so
     * without this rebind every per-branch lookup missed and this class answered 0 by design:
     * the whole Analysis window read zero.
     * <p>
     * <b>It throws rather than migrate half-way</b>, the same rule {@code
     * PhenotypeSnapshot.rebindTo} follows: a tally re-keyed onto a tree that is not the one it
     * was filled from would attribute one branch's cells to another, which is a wrong answer
     * rather than a missing one. The cell-level denominators ({@link #cellsTotal()} and
     * friends) carry over unchanged — they were never keyed on a branch.
     * <p>
     * A branch the walk never reached simply has no entry to move, and still answers 0
     * afterwards; that is ordinary input, not a mismatch.
     *
     * @param walkedRoots roots of the tree the walk classified against — the tree whose
     *                    branches are this tally's current keys
     * @param liveRoots   roots of the tree the caller will look counts up in
     * @return a tally with the same numbers, keyed on {@code liveRoots}' branches
     * @throws IllegalArgumentException when the two trees are not structurally identical
     */
    public BranchTally rebindTo(List<GateNode> walkedRoots, List<GateNode> liveRoots) {
        Map<Branch, Branch> walkedToLive = GateTree.pairBranches(liveRoots, walkedRoots);
        BranchTally out = new BranchTally(regionCount);
        out.cellsTotal = cellsTotal;
        out.cellsClean = cellsClean;
        System.arraycopy(cellsPerRegion, 0, out.cellsPerRegion, 0, regionCount);
        System.arraycopy(cellsPerRegionClean, 0, out.cellsPerRegionClean, 0, regionCount);
        for (Map.Entry<Branch, Counts> entry : counts.entrySet()) {
            Branch live = walkedToLive.get(entry.getKey());
            if (live == null) {
                throw new IllegalArgumentException(
                        "this tally counted cells for branch '" + entry.getKey().getName()
                                + "', which has no counterpart in the tree it is being rebound onto");
            }
            out.counts.put(live, entry.getValue().copy());
        }
        return out;
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

    /**
     * Cells that were cleanly judged — the clean denominator. Excluded by the quality filter
     * or the annotation ROI mask only; see the class javadoc.
     */
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
