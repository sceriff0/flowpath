package qupath.ext.flowpath.io;

import qupath.ext.flowpath.engine.GateReadout;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RegionMask;
import qupath.ext.flowpath.model.MeasuredColumn;
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
 * geometry measurements, and one pair (raw intensity, gating sign) per
 * <em>resolved measurement column</em>.
 * <p>
 * A resolved column is {@code CellIndex.resolvedKey(channel, compartment, statistic)} —
 * the exact column {@code GatingEngine} gated on. Whole-cell mean resolves to the bare
 * marker name, so default gates and ungated markers keep the historical
 * {@code CD3_raw} / {@code CD3_sign} headers; a nuclear-median gate
 * additionally emits {@code CD3_Nucleus_Median_*}. Reporting per resolved column rather
 * than per marker is what lets two gates on different compartments of the same marker
 * stay distinguishable, and is what keeps {@code _sign} consistent with {@code phenotype}.
 * <p>
 * The {@code _sign} column reports independent positivity for a column: a cell is
 * {@code "+"} if it passes <em>at least one</em> threshold imposed on that column
 * anywhere in the gate tree (1D cuts from threshold gates and quadrant gate axes, plus
 * 2D region containment from polygon/rectangle/ellipse gates), each judged on the column
 * as measured. It is blank when the column has no threshold and no region gate anywhere in
 * the tree, and blank when no gate imposed on it could judge this cell (the value, or the
 * other axis of a 2D gate, was not measured) -- unmeasured is not negative.
 * <p>
 * There used to be a {@code _zscore} column between the two. It reported FlowPath's own
 * standardisation against the cells loaded and filtered at export time -- the computed
 * z-score gates no longer use -- so it was a number no gate compared against and no re-run
 * could reproduce, and it went with the mode.
 */
public class PhenotypeCsvExporter {

    private PhenotypeCsvExporter() {
        // static utility class
    }

    /**
     * One axis of one enabled gate, imposed on a resolved column: a threshold gate's only
     * axis, either axis of a quadrant, or either axis of a region gate. Kept as
     * {@code (gate, axis)} so positivity is read off the branch the engine's own predicate
     * returns ({@link GateNode#branchIsPositiveOn}) rather than compared here.
     */
    private record AxisCut(GateNode gate, int axis) {}

    /** {@code "CD3: Nucleus: Mean"} -> {@code "CD3_Nucleus_Mean"}; a bare marker is unchanged. */
    private static String header(MeasuredColumn column) {
        return column.key().replace(": ", "_");
    }

    /**
     * Export phenotype assignments to CSV with raw intensities and signs.
     *
     * @param stats statistics for the same population the gating used; required, because
     *              every {@code _sign} is judged through the same compiled gates the gating
     *              pass used, including their percentile clip bounds
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

        // Cut inventory keyed by resolved column, so a nuclear cut and a cytoplasmic cut on
        // the same marker never pool into one sign.
        Map<String, List<AxisCut>> cutsByColumn = new LinkedHashMap<>();
        for (GateNode root : tree.getRoots()) {
            collectCutsRecursive(root, index, stats, cutsByColumn);
        }
        // The one gate predicate, for every gate of this tree. _sign reads its answers
        // rather than re-typing the comparison.
        GateReadout readout = GateReadout.compile(tree, index, stats);

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
                    String sign = computeSign(i, readout, cutsByColumn.get(col.key()));

                    writer.write(',' + CellTable.fmt(col.valueAt(i)));
                    writer.write(',' + CellTable.escape(sign));
                }
                writer.newLine();
            }
        }
    }

    /**
     * DFS the tree (enabled gates only), collecting every gate axis per resolved column.
     */
    private static void collectCutsRecursive(GateNode node, CellIndex index, MarkerStats stats,
                                             Map<String, List<AxisCut>> cuts) {
        if (!node.isEnabled()) return;
        // A half-configured 2D gate still registers under the axis it does have; the engine
        // cannot judge any cell on it, so that column reads blank rather than "-".
        for (int axis = 0; axis < node.getChannels().size(); axis++) {
            MeasuredColumn column = index.column(node, axis, stats);
            if (column == null) continue;
            cuts.computeIfAbsent(column.key(), k -> new ArrayList<>()).add(new AxisCut(node, axis));
        }
        for (Branch b : node.getBranches()) {
            for (GateNode child : b.getChildren()) {
                collectCutsRecursive(child, index, stats, cuts);
            }
        }
    }

    /**
     * Decide positivity for a cell on one resolved column by OR-combining every gate axis
     * imposed on it: {@code "+"} if the cell lands on the positive side of at least one
     * (above a threshold gate's or a quadrant axis's cut, or inside a region gate),
     * {@code "-"} if at least one gate could judge it and none put it there, and blank when
     * the column has no cut at all or no gate imposed on it could judge the cell.
     * <p>
     * <b>No comparison happens here.</b> Which branch a cell lands in comes from
     * {@link GateReadout#branchIgnoringClip} — {@code ResolvedGate}'s predicate, the one
     * {@code GatingEngine} classifies with — and only the branch-to-axis reading is the
     * gate's ({@link GateNode#branchIsPositiveOn}). This method used to re-implement the
     * comparison, with its own handling of a flat column, beside a {@code phenotype} column
     * computed by different code. Clipping is ignored because a clipped cell has a real
     * value and the walk labels it with this same branch.
     */
    private static String computeSign(int cellIdx, GateReadout readout, List<AxisCut> cuts) {
        if (cuts == null || cuts.isEmpty()) return "";
        boolean judged = false;
        for (AxisCut cut : cuts) {
            int branch = readout.branchIgnoringClip(cut.gate(), cellIdx);
            if (branch == GateReadout.UNMEASURED) continue;
            judged = true;
            if (cut.gate().branchIsPositiveOn(branch, cut.axis())) return "+";
        }
        return judged ? "-" : "";
    }

    /**
     * Collect every column to export: the resolved columns each gate axis uses
     * (depth-first order), then the bare column for any marker in the cell index not
     * already covered. Whole-cell mean resolves to the bare marker key, so a default
     * gate and its marker share one pair exactly as before.
     */
    private static List<MeasuredColumn> collectColumns(GateTree tree, CellIndex index,
                                                       MarkerStats stats) {
        Map<String, MeasuredColumn> byKey = new LinkedHashMap<>();
        for (GateNode root : tree.getRoots()) {
            collectColumnsRecursive(root, index, stats, byKey);
        }
        // Every marker also gets its default column. Which key that lands under is
        // CellIndex's rule to state, not ours — asking for the whole-cell mean and letting
        // it resolve is what keeps an ungated marker sharing one pair with a default
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
        // agreeing with collectCutsRecursive, which has always had this check: the
        // two disagreeing meant a disabled gate still emitted _raw/_sign columns,
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
