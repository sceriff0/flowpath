package qupath.ext.flowpath.io;

import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RegionMask;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports cell phenotype assignments to a CSV file.
 * <p>
 * Each row represents a single cell. Columns include identity, spatial coordinates,
 * geometry measurements, and one triplet (raw intensity, z-score, gating sign) per
 * <em>resolved measurement column</em>.
 * <p>
 * A resolved column is {@code CellIndex.resolvedKey(channel, compartment, statistic)} —
 * the exact column {@code GatingEngine} gated on. Whole-cell mean resolves to the bare
 * marker name, so default gates and ungated markers keep the historical
 * {@code CD3_raw} / {@code CD3_zscore} / {@code CD3_sign} headers; a nuclear-median gate
 * additionally emits {@code CD3_Nucleus_Median_*}. Reporting per resolved column rather
 * than per marker is what lets two gates on different compartments of the same marker
 * stay distinguishable, and is what keeps {@code _sign} consistent with {@code phenotype}.
 * <p>
 * The {@code _sign} column reports independent positivity for a column: a cell is
 * {@code "+"} if it passes <em>at least one</em> threshold imposed on that column
 * anywhere in the gate tree (1D cuts from threshold gates and quadrant gate axes, plus
 * 2D region containment from polygon/rectangle/ellipse gates). Columns with no
 * threshold and no region gate anywhere in the tree get a blank sign.
 */
public class PhenotypeCsvExporter {

    private PhenotypeCsvExporter() {
        // static utility class
    }

    /**
     * A 1D cut imposed on a resolved column by a ThresholdGate or one QuadrantGate axis,
     * kept as {@code (gate, axis)} rather than as a copied threshold value so that
     * deciding positivity goes through the gate's own
     * {@link GateNode#isPositiveAt(int, double)} — the same geometry the engine and the
     * histogram use.
     */
    private record AxisCut(GateNode gate, int axis) {}

    /**
     * A region gate with both of its axis columns already resolved, so the per-cell sign
     * pass reads {@code double[]} by index instead of re-resolving two columns per cell.
     */
    private record ResolvedRegion(Region2DGate gate, MeasuredColumn x, MeasuredColumn y,
                                  boolean evaluable) {}

    /** {@code "CD3: Nucleus: Mean"} -> {@code "CD3_Nucleus_Mean"}; a bare marker is unchanged. */
    private static String header(MeasuredColumn column) {
        return column.key().replace(": ", "_");
    }

    /**
     * Export phenotype assignments to CSV with raw intensities, z-scores, and signs.
     *
     * @param stats statistics for the same population the gating used; required, because
     *              every {@code _zscore} and every z-score-mode {@code _sign} is reported
     *              against it
     */
    public static void export(File file, CellIndex index, GatingEngine.AssignmentResult result,
                              GateTree tree, MarkerStats stats) throws IOException {
        export(file, index, result, tree, stats, null);
    }

    /**
     * As above, additionally naming the annotated region each cell fell in.
     *
     * @param regions which annotated region each cell belongs to, or {@code null} when the
     *                annotation filter is off. When present a {@code region} column is
     *                written, holding the region's name or blank for a cell in none --
     *                which is what makes "does this population differ between tumour core
     *                and invasive margin?" answerable from one export instead of one
     *                export per region.
     */
    public static void export(File file, CellIndex index, GatingEngine.AssignmentResult result,
                              GateTree tree, MarkerStats stats, RegionMask regions)
            throws IOException {

        // Each column arrives already registered with MarkerStats — that is what holding a
        // MeasuredColumn means — so there is no separate ensure pass to forget.
        List<MeasuredColumn> columns = collectColumns(tree, index, stats);

        // Threshold inventory keyed by resolved column, so a nuclear cut and a
        // cytoplasmic cut on the same marker never pool into one sign.
        Map<String, List<AxisCut>> thresholdsByColumn = new LinkedHashMap<>();
        Map<String, List<ResolvedRegion>> regionGatesByColumn = new LinkedHashMap<>();
        for (GateNode root : tree.getRoots()) {
            collectThresholdsRecursive(root, index, stats, thresholdsByColumn, regionGatesByColumn);
        }

        String[] phenotypes = result.getPhenotypes();
        boolean[] outOfAnnotation = result.getOutOfAnnotation();
        boolean[] outlier = result.getOutlier();
        boolean[] unmeasured = result.getUnmeasured();
        boolean withRegion = regions != null && !regions.isEmpty();

        // Emitted only when the export actually carries labels. An all-blank column would
        // be worse than no column: join_flowpath.py branches on the column's *presence*,
        // so a blank one sends it down the exact-join path to match nothing, where its
        // absence correctly selects the centroid fallback.
        boolean withLabel = index.hasLabels();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            // Header — Out_of_annotation and Outlier flag cells excluded from QuPath
            // visual classification but still written as CSV rows.
            //
            // cell_id, phenotype, centroid_x and centroid_y are a cross-repo contract:
            // mirage/bin/join_flowpath.py hard-fails without the last three and inverts
            // centroid_x/centroid_y as `/ pixel_size - 0.5`. They must keep these names
            // and centroid_* must be micrometres. Additional columns are safe.
            CellTable.writeIdentityHeader(writer, index, withLabel);
            // Capitalised, and written as Python-style True/False below, because
            // join_flowpath.py maps these two names verbatim -- ("Outlier", "fp_outlier")
            // and ("Out_of_annotation", "fp_out_of_annotation") -- and then does
            // .fillna(False).astype(bool). pandas infers real booleans from True/False;
            // lower-cased true/false would be read as *strings*, and astype(bool) on any
            // non-empty string is True, which would silently mark every cell an outlier.
            // The odd casing is load-bearing. Do not tidy it.
            writer.write(",Out_of_annotation,Outlier");
            // Unmeasured: this cell reached a gate that had no measurement for it, so no
            // branch was assigned there and its phenotype stops at the last gate that
            // could judge it. Distinct from Outlier, which means "measured, but extreme".
            // Not part of the join_flowpath.py contract -- extra columns are ignored by
            // it -- but written in the same True/False casing as its neighbours so pandas
            // infers a real boolean rather than a always-truthy string.
            writer.write(",Unmeasured");
            if (withRegion) writer.write(",region");
            for (MeasuredColumn col : columns) {
                // Escape the *whole* field, suffix included: a channel name containing a
                // comma would otherwise emit `"CD3, clone"_raw`, which is text after a
                // closing quote and not valid CSV (lenient parsers recover; strict ones
                // do not).
                String base = header(col);
                writer.write("," + CellTable.escape(base + "_raw"));
                writer.write("," + CellTable.escape(base + "_zscore"));
                writer.write("," + CellTable.escape(base + "_sign"));
            }
            writer.newLine();

            int n = index.getSize();
            for (int i = 0; i < n; i++) {
                String phenotype = phenotypes[i] != null ? phenotypes[i] : "";

                CellTable.writeIdentityRow(writer, index, i, withLabel, phenotype);
                writer.write(',');
                writer.write(outOfAnnotation[i] ? "True" : "False");
                writer.write(',');
                writer.write(outlier[i] ? "True" : "False");
                writer.write(',');
                writer.write(unmeasured[i] ? "True" : "False");
                if (withRegion) {
                    String region = regions.regionNameOf(i);
                    writer.write(',' + CellTable.escape(region == null ? "" : region));
                }

                for (int c = 0; c < columns.size(); c++) {
                    MeasuredColumn col = columns.get(c);
                    double raw = col.valueAt(i);

                    double zscore = (Double.isNaN(raw) || !col.hasSpread())
                            ? Double.NaN
                            : col.toZScore(raw);

                    String sign = computeSign(i, col, raw,
                                              thresholdsByColumn.get(col.key()),
                                              regionGatesByColumn.get(col.key()));

                    writer.write(',' + CellTable.fmt(raw));
                    writer.write(',' + CellTable.fmt(zscore));
                    writer.write(',' + CellTable.escape(sign));
                }
                writer.newLine();
            }
        }
    }

    /**
     * DFS the tree (enabled gates only), collecting 1D thresholds per resolved column
     * and 2D region gates per resolved axis column.
     */
    private static void collectThresholdsRecursive(
            GateNode node, CellIndex index, MarkerStats stats,
            Map<String, List<AxisCut>> thresholds,
            Map<String, List<ResolvedRegion>> regionGates) {
        if (!node.isEnabled()) return;
        if (node instanceof QuadrantGate qg) {
            addThreshold(thresholds, index.column(qg, 0, stats), qg, 0);
            addThreshold(thresholds, index.column(qg, 1, stats), qg, 1);
        } else if (node instanceof Region2DGate region) {
            // Resolve both axes once here; computeSign then reads them by cell index.
            // A half-configured region still registers under the axis it does have, so
            // that column reports "-" rather than blank — the gate exists, this cell just
            // is not in it.
            MeasuredColumn colX = index.column(region, 0, stats);
            MeasuredColumn colY = index.column(region, 1, stats);
            boolean evaluable = colX != null && colY != null
                    && index.getMarkerIndex(region.getChannelX()) >= 0
                    && index.getMarkerIndex(region.getChannelY()) >= 0;
            ResolvedRegion resolved = new ResolvedRegion(region, colX, colY, evaluable);
            if (colX != null) regionGates.computeIfAbsent(colX.key(), k -> new ArrayList<>()).add(resolved);
            if (colY != null) regionGates.computeIfAbsent(colY.key(), k -> new ArrayList<>()).add(resolved);
        } else {
            // ThresholdGate: 1D cut on a single resolved column
            addThreshold(thresholds, index.column(node, 0, stats), node, 0);
        }
        for (Branch b : node.getBranches()) {
            for (GateNode child : b.getChildren()) {
                collectThresholdsRecursive(child, index, stats, thresholds, regionGates);
            }
        }
    }

    private static void addThreshold(Map<String, List<AxisCut>> thresholds,
                                     MeasuredColumn column, GateNode gate, int axis) {
        if (column == null) return;
        thresholds.computeIfAbsent(column.key(), k -> new ArrayList<>())
                .add(new AxisCut(gate, axis));
    }

    /**
     * Decide positivity for a cell on one resolved column by OR-combining every imposed
     * threshold: 1D cuts from ThresholdGate / QuadrantGate axes (compare-mode honors each
     * gate's z-score flag), plus 2D containment from region gates (cell inside region →
     * "+" on both of that gate's axis columns).
     * <p>
     * Returns blank if the column has no threshold or region anywhere in the tree.
     * <p>
     * The <em>geometry</em> — where the cut is, what counts as inside — comes from the
     * gates themselves ({@link GateNode#isPositiveAt}, {@link GateNode#branchFor}), the
     * same methods {@code GatingEngine} classifies with and the plots colour with, so this
     * column cannot drift away from the {@code phenotype} column beside it. Only the
     * <em>sample resolution</em> is local, and deliberately so: a degenerate column is
     * skipped here (the gate contributes no opinion) where the engine standardises it to
     * zero, because a sign of "+" earned by a column with no spread would be noise.
     */
    private static String computeSign(int cellIdx, MeasuredColumn column, double raw,
                                       List<AxisCut> thresholds,
                                       List<ResolvedRegion> regionGates) {
        boolean hasThresholds = thresholds != null && !thresholds.isEmpty();
        boolean hasRegions = regionGates != null && !regionGates.isEmpty();
        if (!hasThresholds && !hasRegions) return "";
        if (Double.isNaN(raw)) return "";

        if (hasThresholds) {
            for (AxisCut cut : thresholds) {
                double cmp;
                if (cut.gate().isThresholdIsZScore()) {
                    if (!column.hasSpread()) continue;
                    cmp = column.toZScore(raw);
                } else {
                    cmp = raw;
                }
                if (cut.gate().isPositiveAt(cut.axis(), cmp)) return "+";
            }
        }

        if (hasRegions) {
            for (ResolvedRegion region : regionGates) {
                if (!region.evaluable()) continue;
                // Each axis is evaluated on its own resolved column, matching the engine.
                double rawX = region.x().valueAt(cellIdx);
                double rawY = region.y().valueAt(cellIdx);
                if (Double.isNaN(rawX) || Double.isNaN(rawY)) continue;
                double vx;
                double vy;
                if (region.gate().isThresholdIsZScore()) {
                    if (!region.x().hasSpread() || !region.y().hasSpread()) continue;
                    vx = region.x().toZScore(rawX);
                    vy = region.y().toZScore(rawY);
                } else {
                    vx = rawX;
                    vy = rawY;
                }
                // Branch 0 of a region gate is "inside" — see Region2DGate.branchFor.
                if (region.gate().branchFor(vx, vy) == 0) return "+";
            }
        }

        return "-";
    }

    /**
     * Collect every column to export: the resolved columns each gate axis uses
     * (depth-first order), then the bare column for any marker in the cell index not
     * already covered. Whole-cell mean resolves to the bare marker key, so a default
     * gate and its marker share one triplet exactly as before.
     */
    private static List<MeasuredColumn> collectColumns(GateTree tree, CellIndex index,
                                                       MarkerStats stats) {
        Map<String, MeasuredColumn> byKey = new LinkedHashMap<>();
        for (GateNode root : tree.getRoots()) {
            collectColumnsRecursive(root, index, stats, byKey);
        }
        // Every marker also gets its default column. Which key that lands under is
        // CellIndex's rule to state, not ours — asking for the whole-cell mean and letting
        // it resolve is what keeps an ungated marker sharing one triplet with a default
        // gate on the same marker.
        for (String m : index.getMarkerNames()) {
            MeasuredColumn col = index.column(m, Compartment.WHOLE_CELL, Statistic.MEAN, stats);
            byKey.putIfAbsent(col.key(), col);
        }
        return new ArrayList<>(byKey.values());
    }

    private static void collectColumnsRecursive(GateNode node, CellIndex index, MarkerStats stats,
                                                Map<String, MeasuredColumn> byKey) {
        // A disabled gate is a hard stop for its whole subtree in GatingEngine.walkNode, so
        // it contributes no phenotype and no sign. Skipping it here keeps this traversal
        // agreeing with collectThresholdsRecursive, which has always had this check: the
        // two disagreeing meant a disabled gate still emitted _raw/_zscore/_sign columns,
        // with _sign permanently blank because the threshold inventory had skipped it.
        if (!node.isEnabled()) return;
        List<String> channels = node.getChannels();
        for (int k = 0; k < channels.size(); k++) {
            MeasuredColumn col = index.column(node, k, stats);
            if (col == null) continue;
            byKey.putIfAbsent(col.key(), col);
        }
        for (Branch branch : node.getBranches()) {
            for (GateNode child : branch.getChildren()) {
                collectColumnsRecursive(child, index, stats, byKey);
            }
        }
    }
}
