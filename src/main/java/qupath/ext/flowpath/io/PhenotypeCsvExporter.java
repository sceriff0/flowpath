package qupath.ext.flowpath.io;

import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PhenotypeTree;
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

    /** A 1D threshold imposed on a resolved column by a ThresholdGate or one QuadrantGate axis. */
    private record MarkerThreshold(double threshold, boolean isZScore) {}

    /**
     * One exported column group: the measurement column a gate axis resolves to, plus
     * the header prefix used for its {@code _raw} / {@code _zscore} / {@code _sign} triplet.
     */
    private record ResolvedColumn(String channel, Compartment compartment, Statistic statistic, String key) {
        /** {@code "CD3: Nucleus: Mean"} -> {@code "CD3_Nucleus_Mean"}; a bare marker is unchanged. */
        String header() {
            return key.replace(": ", "_");
        }
    }

    /**
     * Export phenotype assignments to CSV with raw intensities, z-scores, and signs.
     */
    public static void export(File file, CellIndex index, GatingEngine.AssignmentResult result,
                              GateTree tree, MarkerStats stats) throws IOException {

        List<ResolvedColumn> columns = collectColumns(tree, index);

        // Threshold inventory keyed by resolved column, so a nuclear cut and a
        // cytoplasmic cut on the same marker never pool into one sign.
        Map<String, List<MarkerThreshold>> thresholdsByColumn = new LinkedHashMap<>();
        Map<String, List<Region2DGate>> regionGatesByColumn = new LinkedHashMap<>();
        for (GateNode root : tree.getRoots()) {
            collectThresholdsRecursive(root, index, thresholdsByColumn, regionGatesByColumn);
        }

        // Materialise each column once rather than per cell, and make sure MarkerStats
        // knows it — the engine's pre-pass registers gated columns, but export() is also
        // called directly (tests, scripting) where that has not necessarily happened.
        double[][] columnValues = new double[columns.size()][];
        for (int c = 0; c < columns.size(); c++) {
            ResolvedColumn col = columns.get(c);
            columnValues[c] = index.getResolvedColumn(col.channel(), col.compartment(), col.statistic());
            if (stats != null && !stats.hasColumn(col.key())) {
                stats.ensureColumn(col.key(), columnValues[c]);
            }
        }

        String[] phenotypes = result.getPhenotypes();
        boolean[] outOfAnnotation = result.getOutOfAnnotation();
        boolean[] outlier = result.getOutlier();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            // Header — Out_of_annotation and Outlier flag cells excluded from QuPath
            // visual classification but still written as CSV rows.
            writer.write("cell_id,phenotype,Out_of_annotation,Outlier,centroid_x,centroid_y,area,perimeter,eccentricity,solidity");
            for (ResolvedColumn col : columns) {
                // Escape the *whole* field, suffix included: a channel name containing a
                // comma would otherwise emit `"CD3, clone"_raw`, which is text after a
                // closing quote and not valid CSV (lenient parsers recover; strict ones
                // do not).
                String base = col.header();
                writer.write("," + escapeCsv(base + "_raw"));
                writer.write("," + escapeCsv(base + "_zscore"));
                writer.write("," + escapeCsv(base + "_sign"));
            }
            writer.newLine();

            int n = index.getSize();
            for (int i = 0; i < n; i++) {
                String phenotype = phenotypes[i] != null ? phenotypes[i] : "";

                writer.write(String.valueOf(i));
                writer.write(',');
                writer.write(escapeCsv(phenotype));
                writer.write(',');
                writer.write(outOfAnnotation[i] ? "True" : "False");
                writer.write(',');
                writer.write(outlier[i] ? "True" : "False");

                writer.write(',' + fmt(index.getCentroidX(i)));
                writer.write(',' + fmt(index.getCentroidY(i)));
                writer.write(',' + fmt(index.getArea(i)));
                writer.write(',' + fmt(index.getPerimeter(i)));
                writer.write(',' + fmt(index.getEccentricity(i)));
                writer.write(',' + fmt(index.getSolidity(i)));

                for (int c = 0; c < columns.size(); c++) {
                    ResolvedColumn col = columns.get(c);
                    String key = col.key();
                    double raw = columnValues[c][i];

                    double zscore;
                    if (Double.isNaN(raw) || stats == null || stats.getStd(key) <= 1e-10) {
                        zscore = Double.NaN;
                    } else {
                        zscore = stats.toZScore(key, raw);
                    }

                    String sign = computeSign(i, key, raw, index, stats,
                                              thresholdsByColumn.get(key),
                                              regionGatesByColumn.get(key));

                    writer.write(',' + fmt(raw));
                    writer.write(',' + fmt(zscore));
                    writer.write(',' + escapeCsv(sign));
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
            GateNode node, CellIndex index,
            Map<String, List<MarkerThreshold>> thresholds,
            Map<String, List<Region2DGate>> regionGates) {
        if (!node.isEnabled()) return;
        if (node instanceof QuadrantGate qg) {
            boolean z = qg.isThresholdIsZScore();
            addThreshold(thresholds, index, qg.getChannelX(), qg.getCompartmentX(), qg.getStatisticX(),
                    qg.getThresholdX(), z);
            addThreshold(thresholds, index, qg.getChannelY(), qg.getCompartmentY(), qg.getStatisticY(),
                    qg.getThresholdY(), z);
        } else if (node instanceof Region2DGate region) {
            String keyX = keyOf(index, region.getChannelX(), region.getCompartmentX(), region.getStatisticX());
            String keyY = keyOf(index, region.getChannelY(), region.getCompartmentY(), region.getStatisticY());
            if (keyX != null) regionGates.computeIfAbsent(keyX, k -> new ArrayList<>()).add(region);
            if (keyY != null) regionGates.computeIfAbsent(keyY, k -> new ArrayList<>()).add(region);
        } else {
            // ThresholdGate: 1D cut on a single resolved column
            addThreshold(thresholds, index, node.getChannel(), node.getCompartment(), node.getStatistic(),
                    node.getThreshold(), node.isThresholdIsZScore());
        }
        for (Branch b : node.getBranches()) {
            for (GateNode child : b.getChildren()) {
                collectThresholdsRecursive(child, index, thresholds, regionGates);
            }
        }
    }

    private static void addThreshold(Map<String, List<MarkerThreshold>> thresholds, CellIndex index,
                                     String channel, Compartment comp, Statistic stat,
                                     double threshold, boolean isZScore) {
        String key = keyOf(index, channel, comp, stat);
        if (key == null) return;
        thresholds.computeIfAbsent(key, k -> new ArrayList<>()).add(new MarkerThreshold(threshold, isZScore));
    }

    /** Resolved column key, or null when the gate has no usable channel on this axis. */
    private static String keyOf(CellIndex index, String channel, Compartment comp, Statistic stat) {
        if (channel == null || channel.isEmpty()) return null;
        return index.resolvedKey(channel, comp, stat);
    }

    /**
     * Decide positivity for a cell on one resolved column by OR-combining every imposed
     * threshold: 1D cuts from ThresholdGate / QuadrantGate axes (compare-mode honors each
     * gate's z-score flag), plus 2D containment from region gates (cell inside region →
     * "+" on both of that gate's axis columns).
     * <p>
     * Returns blank if the column has no threshold or region anywhere in the tree.
     * Mirrors {@code GatingEngine.evaluateGate}.
     */
    private static String computeSign(int cellIdx, String key, double raw,
                                       CellIndex index, MarkerStats stats,
                                       List<MarkerThreshold> thresholds,
                                       List<Region2DGate> regionGates) {
        boolean hasThresholds = thresholds != null && !thresholds.isEmpty();
        boolean hasRegions = regionGates != null && !regionGates.isEmpty();
        if (!hasThresholds && !hasRegions) return "";
        if (Double.isNaN(raw)) return "";

        if (hasThresholds) {
            for (MarkerThreshold t : thresholds) {
                double cmp;
                if (t.isZScore()) {
                    if (stats == null || stats.getStd(key) <= 1e-10) continue;
                    cmp = stats.toZScore(key, raw);
                } else {
                    cmp = raw;
                }
                if (cmp >= t.threshold()) return "+";
            }
        }

        if (hasRegions) {
            for (Region2DGate gate : regionGates) {
                String chX = gate.getChannelX();
                String chY = gate.getChannelY();
                if (chX == null || chY == null) continue;
                if (index.getMarkerIndex(chX) < 0 || index.getMarkerIndex(chY) < 0) continue;
                // Each axis is evaluated on its own resolved column, matching the engine.
                String keyX = index.resolvedKey(chX, gate.getCompartmentX(), gate.getStatisticX());
                String keyY = index.resolvedKey(chY, gate.getCompartmentY(), gate.getStatisticY());
                double rawX = index.getResolvedColumn(chX, gate.getCompartmentX(), gate.getStatisticX())[cellIdx];
                double rawY = index.getResolvedColumn(chY, gate.getCompartmentY(), gate.getStatisticY())[cellIdx];
                if (Double.isNaN(rawX) || Double.isNaN(rawY)) continue;
                double vx;
                double vy;
                if (gate.isThresholdIsZScore()) {
                    if (stats == null
                            || stats.getStd(keyX) <= 1e-10
                            || stats.getStd(keyY) <= 1e-10) continue;
                    vx = stats.toZScore(keyX, rawX);
                    vy = stats.toZScore(keyY, rawY);
                } else {
                    vx = rawX;
                    vy = rawY;
                }
                if (gate.contains(vx, vy)) return "+";
            }
        }

        return "-";
    }

    /** Format a double for CSV; NaN → empty string. Uses US locale to ensure dot decimal separator. */
    private static String fmt(double val) {
        return Double.isNaN(val) ? "" : String.format(java.util.Locale.US, "%.4f", val);
    }

    /**
     * Collect every column to export: the resolved columns each gate axis uses
     * (depth-first order), then the bare column for any marker in the cell index not
     * already covered. Whole-cell mean resolves to the bare marker key, so a default
     * gate and its marker share one triplet exactly as before.
     */
    private static List<ResolvedColumn> collectColumns(GateTree tree, CellIndex index) {
        Map<String, ResolvedColumn> byKey = new LinkedHashMap<>();
        for (GateNode root : tree.getRoots()) {
            collectColumnsRecursive(root, index, byKey);
        }
        for (String m : index.getMarkerNames()) {
            byKey.putIfAbsent(m, new ResolvedColumn(m, Compartment.WHOLE_CELL, Statistic.MEAN, m));
        }
        return new ArrayList<>(byKey.values());
    }

    private static void collectColumnsRecursive(GateNode node, CellIndex index,
                                                Map<String, ResolvedColumn> byKey) {
        List<String> channels = node.getChannels();
        for (int k = 0; k < channels.size(); k++) {
            String ch = channels.get(k);
            if (ch == null || ch.isEmpty()) continue;
            Compartment comp = node.compartmentAt(k);
            Statistic stat = node.statisticAt(k);
            byKey.putIfAbsent(index.resolvedKey(ch, comp, stat),
                    new ResolvedColumn(ch, comp, stat, index.resolvedKey(ch, comp, stat)));
        }
        for (Branch branch : node.getBranches()) {
            for (GateNode child : branch.getChildren()) {
                collectColumnsRecursive(child, index, byKey);
            }
        }
    }

    /**
     * Escape a value for CSV output. Wraps in double quotes if it contains a
     * comma, double quote, or newline.
     */
    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Rich phenotype export (spec §7.5) — the terminal deliverable. One row per cell:
     * committed class, outcome, provenance, and the derived candidate set.
     */
    public static void exportPhenotypes(File file, List<CellPhenotype> cells,
                                        PhenotypeTree tree) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write("cell_label,committed_phenotype,outcome,provenance,candidates");
            writer.newLine();
            for (var c : cells) {
                StringBuilder cands = new StringBuilder();
                for (var cand : c.candidates()) {
                    if (cands.length() > 0) cands.append(';');
                    cands.append(cand.name()).append(':').append(fmt(cand.score()));
                }
                writer.write(String.valueOf(c.getLabel()));
                writer.write(',' + escapeCsv(c.getCommitted() != null ? c.getCommitted() : ""));
                writer.write(',' + escapeCsv(c.getOutcome().name()));
                writer.write(',' + escapeCsv(c.getProvenance().name()));
                writer.write(',' + escapeCsv(cands.toString()));
                writer.newLine();
            }
        }
    }
}
