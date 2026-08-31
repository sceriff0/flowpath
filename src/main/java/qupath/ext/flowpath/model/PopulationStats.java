package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The gate tree as a frequency table: every branch with its count, its share of the branch
 * it hangs from, its share of the population, and its share of whatever branch the user
 * chose as a denominator — at each of three nested scopes.
 * <p>
 * <b>Both denominators, always.</b> Getting the denominator wrong is the classic way a
 * gating table misleads: "20% CD8+" reads very differently when it is 40% of the T cells it
 * was gated from. Percent-of-parent is also the one number a downstream tool cannot
 * reconstruct from a per-cell export, because it never sees the tree.
 * <p>
 * <b>Raw and clean side by side.</b> The clean count drops cells that were outlier-clipped,
 * quality-filtered, or that a gate could not measure. The difference between the two is the
 * data-quality cost of this panel, and it belongs in front of the reader rather than being a
 * choice made silently inside an exporter.
 * <p>
 * <b>Three nested scopes</b> — {@code ANNOTATION_K ⊆ ANNOTATION_ALL ⊆ WHOLE_SLIDE}. The
 * difference between them is not noise; it is the effect of the annotation.
 * <p>
 * Pure: no I/O, no JavaFX, and no gating. It reads a {@link BranchTally} the walk already
 * filled, so nothing here re-classifies a cell.
 */
public final class PopulationStats {

    /** The three nested populations a quantity can be reported over. */
    public enum Scope {
        /** Every indexed cell; no polygon consulted. The only scope an unannotated slide has. */
        WHOLE_SLIDE,
        /** Cells inside the union of the annotations in use. */
        ANNOTATION_ALL,
        /** Cells inside one annotated region; several values per image. */
        ANNOTATION_K
    }

    /**
     * One branch's line in the table.
     *
     * @param regionName            the region, for {@link Scope#ANNOTATION_K}; {@code null} otherwise
     * @param path                  gating route, e.g. {@code "CD45+/CD8+"}
     * @param depth                 0 for a root branch
     * @param count                 cells in this branch at this scope
     * @param cleanCount            of those, the cleanly judged ones
     * @param parentCount           cells in the branch above, or the scope's population for a root
     * @param denominatorCount      cells in the user-chosen denominator branch; 0 when none chosen
     * @param percentOfDenominator  {@code NaN} when no denominator was chosen — not zero, which
     *                              would read as a real answer
     * @param areaMm2               the region's area, or {@code NaN} when unknown
     * @param densityPerMm2         {@code count / areaMm2}, or {@code NaN} without an area
     */
    public record Row(Scope scope, String regionName, String path, String branchName,
                      String gateChannel, int depth,
                      int count, int cleanCount, int parentCount, int cleanParentCount,
                      int denominatorCount,
                      double percentOfParent, double percentOfTotal, double percentOfDenominator,
                      double areaMm2, double densityPerMm2) {}

    private final List<Row> rows;

    private PopulationStats(List<Row> rows) {
        this.rows = Collections.unmodifiableList(rows);
    }

    /**
     * Build the table.
     *
     * @param tree           the gate tree; disabled gates and their subtrees contribute no rows
     * @param tally          counts recorded during the gating walk
     * @param regionNames    region names, parallel to the tally's region indices
     * @param regionAreasMm2 per-region area, or {@code null} when unknown
     * @param denominator    the branch to report every population against, or {@code null}
     *                       when the user has not chosen one
     */
    public static PopulationStats of(GateTree tree, BranchTally tally, List<String> regionNames,
                                     double[] regionAreasMm2, Branch denominator) {
        List<Row> out = new ArrayList<>();
        boolean hasDenominator = denominator != null;

        collect(tree.getRoots(), Scope.WHOLE_SLIDE, null, -1, "", 0,
                tally.cellsTotal(), tally.cellsTotal(), tally.cellsClean(),
                hasDenominator, hasDenominator ? tally.total(denominator) : 0,
                Double.NaN, tally, out);

        if (tally.regionCount() > 0) {
            int unionTotal = 0, unionClean = 0, unionDenominator = 0;
            for (int r = 0; r < tally.regionCount(); r++) {
                unionTotal += tally.cellsInRegion(r);
                unionClean += tally.cleanCellsInRegion(r);
                if (hasDenominator) unionDenominator += tally.inRegion(denominator, r);
            }
            collect(tree.getRoots(), Scope.ANNOTATION_ALL, null, -1, "", 0,
                    unionTotal, unionTotal, unionClean,
                    hasDenominator, unionDenominator,
                    sumAreas(regionAreasMm2), tally, out);
        }

        for (int r = 0; r < tally.regionCount(); r++) {
            String name = r < regionNames.size() ? regionNames.get(r) : "Region " + (r + 1);
            double area = (regionAreasMm2 != null && r < regionAreasMm2.length)
                    ? regionAreasMm2[r] : Double.NaN;
            int regionTotal = tally.cellsInRegion(r);
            collect(tree.getRoots(), Scope.ANNOTATION_K, name, r, "", 0,
                    regionTotal, regionTotal, tally.cleanCellsInRegion(r),
                    hasDenominator, hasDenominator ? tally.inRegion(denominator, r) : 0,
                    area, tally, out);
        }

        return new PopulationStats(out);
    }

    private static void collect(List<GateNode> nodes, Scope scope, String regionName, int region,
                                String prefix, int depth,
                                int parentCount, int scopeTotal, int cleanParentCount,
                                boolean hasDenominator, int denominatorCount,
                                double areaMm2, BranchTally tally, List<Row> out) {
        if (nodes == null) return;
        for (GateNode node : nodes) {
            // A disabled gate is a hard stop for its whole subtree in GatingEngine.walkNode,
            // so reporting its stale counts would show populations the phenotype column
            // never mentions.
            if (!node.isEnabled()) continue;
            List<String> channels = node.getChannels();
            String channel = channels.isEmpty() ? "" : channels.get(0);
            for (Branch branch : node.getBranches()) {
                String path = prefix.isEmpty() ? branch.getName() : prefix + "/" + branch.getName();
                int count = region < 0 ? scopeCount(scope, branch, tally)
                                       : tally.inRegion(branch, region);
                int clean = region < 0 ? scopeClean(scope, branch, tally)
                                       : tally.cleanInRegion(branch, region);
                out.add(new Row(scope, regionName, path, branch.getName(), channel, depth,
                        count, clean, parentCount, cleanParentCount, denominatorCount,
                        percent(count, parentCount),
                        percent(count, scopeTotal),
                        !hasDenominator ? Double.NaN : percent(count, denominatorCount),
                        areaMm2,
                        Double.isNaN(areaMm2) || areaMm2 <= 0 ? Double.NaN : count / areaMm2));
                collect(branch.getChildren(), scope, regionName, region, path, depth + 1,
                        count, scopeTotal, clean, hasDenominator, denominatorCount,
                        areaMm2, tally, out);
            }
        }
    }

    private static int scopeCount(Scope scope, Branch branch, BranchTally tally) {
        if (scope == Scope.WHOLE_SLIDE) return tally.total(branch);
        int sum = 0;
        for (int r = 0; r < tally.regionCount(); r++) sum += tally.inRegion(branch, r);
        return sum;
    }

    private static int scopeClean(Scope scope, Branch branch, BranchTally tally) {
        if (scope == Scope.WHOLE_SLIDE) return tally.clean(branch);
        int sum = 0;
        for (int r = 0; r < tally.regionCount(); r++) sum += tally.cleanInRegion(branch, r);
        return sum;
    }

    private static double sumAreas(double[] areas) {
        if (areas == null) return Double.NaN;
        double sum = 0;
        for (double a : areas) {
            if (Double.isNaN(a)) return Double.NaN;
            sum += a;
        }
        return sum;
    }

    /** Percentage, or 0 for an empty denominator — a report must not carry NaN from division. */
    private static double percent(int part, int whole) {
        return whole <= 0 ? 0.0 : 100.0 * part / whole;
    }

    /** Every row, all scopes, in depth-first tree order within each scope. */
    public List<Row> rows() {
        return rows;
    }

    /** Rows for one scope. */
    public List<Row> rows(Scope scope) {
        return rows.stream().filter(r -> r.scope() == scope).toList();
    }
}
