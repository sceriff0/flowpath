package qupath.ext.flowpath.umap;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.lib.objects.PathObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable picture of "what the gating tree currently says about every cell",
 * handed from the gating pane to the UMAP view.
 * <p>
 * Before FlowPath was a single extension, the UMAP half recovered this information by
 * reading each cell's {@link qupath.lib.objects.classes.PathClass} back out of QuPath's
 * hierarchy: it rebuilt its own {@link CellIndex} from the image, re-derived colours by
 * string-parsing class names, and had no way to know <em>which</em> markers had actually
 * been gated on. That round-trip through global mutable state was both slow (a second
 * full index build over hundreds of thousands of cells) and lossy.
 * <p>
 * A snapshot carries the real thing instead:
 * <ul>
 *   <li>the <em>same</em> {@link CellIndex} instance the gating walk used, so cell
 *       <em>i</em> means the same cell on both sides and no rebuild is needed;</li>
 *   <li>per-cell phenotype labels and packed-RGB colours straight from the gate tree,
 *       so the UMAP legend matches the gating tree exactly;</li>
 *   <li>the exclusion mask, so quality-filtered cells can be dimmed or dropped rather
 *       than silently embedded;</li>
 *   <li>{@link #gatedMarkers()} and {@link #gateSelection()} — the markers the user
 *       actually gated on and the compartment/statistic each was measured in, which
 *       makes "embed on my gating panel" a one-click default.</li>
 * </ul>
 *
 * <p><b>Cell identity.</b> A snapshot is only meaningful for the image it was taken
 * from: the arrays are positional against {@code index.getObjects()}. Consumers must
 * discard it when the active image changes — see {@link #imageKey()}.
 *
 * @param index          the columnar index the gating ran over; shared, never copied
 * @param stats          marker statistics computed under the same masks as the gating
 * @param markerNames    every marker discovered on the image, in panel order
 * @param capability     which compartment/statistic combinations this image supports
 * @param phenotypes     per-cell phenotype label; never {@code null}, {@code "Unclassified"}
 *                       for cells no gate claimed
 * @param colors         per-cell packed RGB colour assigned by the gate tree
 * @param excluded       per-cell {@code true} when removed by ROI, quality filter or outlier clipping
 * @param gatedMarkers   markers used as an axis by at least one enabled gate, in tree order
 * @param gateSelection  compartment + statistic per gated marker, mirroring the gate axes
 * @param gateCount      number of gates in the tree, for display
 * @param imageKey       identity of the image the snapshot was taken from
 */
public record PhenotypeSnapshot(
        CellIndex index,
        MarkerStats stats,
        List<String> markerNames,
        CompartmentCapability capability,
        String[] phenotypes,
        int[] colors,
        boolean[] excluded,
        List<String> gatedMarkers,
        MarkerSelection gateSelection,
        int gateCount,
        String imageKey
) {

    /** Label used for cells that no gate claimed. */
    public static final String UNCLASSIFIED = "Unclassified";

    public PhenotypeSnapshot {
        Objects.requireNonNull(index, "index");
        markerNames = List.copyOf(markerNames);
        gatedMarkers = List.copyOf(gatedMarkers);
        // The three per-cell arrays are positional against index.getObjects(); a length
        // mismatch means the caller mixed results from two different builds, which would
        // silently mislabel cells rather than fail. Catch it at construction.
        int n = index.size();
        if (phenotypes.length != n || colors.length != n || excluded.length != n) {
            throw new IllegalArgumentException(
                    "Per-cell arrays must match the index size (" + n + "): phenotypes="
                            + phenotypes.length + ", colors=" + colors.length
                            + ", excluded=" + excluded.length);
        }
    }

    /** Number of cells the snapshot covers, including excluded ones. */
    public int cellCount() {
        return index.size();
    }

    /**
     * {@code true} when {@code other} indexes exactly the cells this snapshot describes:
     * the same {@link qupath.lib.objects.PathObject} instances, in the same order.
     * <p>
     * <b>Why this is not {@code other == index}.</b> The constructor's length check turns
     * a positional misalignment into a loud failure, but it can only see the arrays it is
     * handed — it cannot see a consumer that keeps the snapshot while replacing the index
     * underneath it. The UMAP view does exactly that: changing the feature picker rebuilds
     * the index from the snapshot's own cells at a different compartment/statistic, which
     * is a <em>new instance describing the same cells</em>. Answering "same cells?" by
     * instance identity gets that case wrong in both directions — it invalidates a still
     * valid embedding, and, once a stale snapshot is compared instead of the live index,
     * it green-lights recolouring against cells the labels were never computed for.
     * <p>
     * Comparing the object arrays inspects the data instead of trusting a remembered
     * pointer, so it cannot be falsified by a field going stale. Cost is O(1) for the
     * overwhelmingly common cases — the same instance, or a different cell count — and a
     * single pass of reference comparisons only when two distinct indices happen to agree
     * on size, which is the derived case this exists to recognise.
     */
    public boolean describesSameCells(CellIndex other) {
        if (other == index) return true;
        if (other == null || other.size() != index.size()) return false;
        PathObject[] mine = index.getObjects();
        PathObject[] theirs = other.getObjects();
        for (int i = 0; i < mine.length; i++) {
            if (mine[i] != theirs[i]) return false;
        }
        return true;
    }

    /**
     * This snapshot's labels, re-seated onto an index that covers the same cells.
     * <p>
     * The reconciliation step a consumer owes the handoff when it rebuilds the index: it
     * keeps the phenotypes, colours and exclusion mask — which are properties of the
     * gating, not of the feature resolution — while making the snapshot name the index
     * that is actually in use. Holding both at once is the drift this prevents.
     *
     * @param other      an index over the same cells, in the same order
     * @param otherStats statistics computed over {@code other}; {@code null} keeps the current ones
     * @return {@code this} when {@code other} is already the named index, else a re-seated copy
     * @throws IllegalArgumentException when {@code other} does not cover the same cells,
     *         because re-seating onto a different cell set would mislabel every one of them
     */
    public PhenotypeSnapshot rebindTo(CellIndex other, MarkerStats otherStats) {
        if (other == index) return this;
        if (!describesSameCells(other)) {
            throw new IllegalArgumentException(
                    "Cannot rebind a phenotype snapshot onto an index covering different cells: "
                            + "this snapshot describes " + index.size() + " cell(s), the candidate "
                            + "index has " + (other == null ? "none" : String.valueOf(other.size()))
                            + " and its objects are not the same instances in the same order.");
        }
        return new PhenotypeSnapshot(other, otherStats != null ? otherStats : stats,
                markerNames, capability, phenotypes, colors, excluded,
                gatedMarkers, gateSelection, gateCount, imageKey);
    }

    /** Number of cells that survived the ROI, quality and outlier filters. */
    public int includedCount() {
        int c = 0;
        for (boolean e : excluded) {
            if (!e) c++;
        }
        return c;
    }

    /** {@code true} when at least one gate produced a phenotype other than Unclassified. */
    public boolean hasPhenotypes() {
        for (int i = 0; i < phenotypes.length; i++) {
            if (!excluded[i] && !UNCLASSIFIED.equals(phenotypes[i])) return true;
        }
        return false;
    }

    /**
     * Distinct phenotypes among the non-excluded cells, largest population first,
     * each with its gate-tree colour and cell count.
     * <p>
     * Ordering by size rather than by tree order is deliberate: the legend is read as a
     * "what is this tissue made of?" summary, and the populations that dominate the
     * embedding are the ones worth reading first. {@link #UNCLASSIFIED} is always sorted
     * last regardless of size, because it is a residual rather than a finding.
     */
    public List<Population> populations() {
        Map<String, int[]> counts = new HashMap<>();   // name -> {count, color}
        for (int i = 0; i < phenotypes.length; i++) {
            if (excluded[i]) continue;
            String name = phenotypes[i] == null ? UNCLASSIFIED : phenotypes[i];
            int[] entry = counts.get(name);
            if (entry == null) {
                counts.put(name, new int[]{1, colors[i]});
            } else {
                entry[0]++;
            }
        }
        List<Population> out = new ArrayList<>(counts.size());
        counts.forEach((name, entry) -> out.add(new Population(name, entry[1], entry[0])));
        out.sort(Comparator
                .comparing((Population p) -> UNCLASSIFIED.equals(p.name()))
                .thenComparing(Comparator.comparingInt(Population::count).reversed())
                .thenComparing(Population::name));
        return List.copyOf(out);
    }

    /** One phenotype in the legend: its name, gate-tree colour and cell count. */
    public record Population(String name, int color, int count) {}

    /**
     * Walk an assigned gate tree and collect, in tree order, every marker used as a gate
     * axis together with the compartment and statistic it was measured in.
     * <p>
     * This is what lets the UMAP view open pre-configured on the user's panel instead of
     * on all ~40 channels the image happens to carry. Disabled gates are skipped — a gate
     * the user switched off is not part of the phenotyping.
     *
     * @return the gated markers in tree order, and a matching {@link MarkerSelection}
     */
    public static GatedPanel collectGatedPanel(GateTree tree) {
        Set<String> ordered = new LinkedHashSet<>();
        Map<String, Compartment> comps = new LinkedHashMap<>();
        Map<String, Statistic> stats = new LinkedHashMap<>();

        if (tree != null) {
            Deque<GateNode> queue = new ArrayDeque<>(tree.getRoots());
            while (!queue.isEmpty()) {
                GateNode node = queue.poll();
                if (node == null || !node.isEnabled()) continue;
                List<String> channels = node.getChannels();
                for (int k = 0; k < channels.size(); k++) {
                    String channel = channels.get(k);
                    if (channel == null || channel.isBlank()) continue;
                    // First gate to use a marker wins its compartment/statistic. Gating the
                    // same marker twice in different compartments is legal but rare, and the
                    // shallower gate is the one that defines the population structure.
                    if (ordered.add(channel)) {
                        comps.put(channel, node.compartmentAt(k));
                        stats.put(channel, node.statisticAt(k));
                    }
                }
                node.getBranches().forEach(b -> queue.addAll(b.getChildren()));
            }
        }

        MarkerSelection selection = new MarkerSelection();
        for (String marker : ordered) {
            selection.put(marker, new MarkerSelection.Entry(
                    comps.get(marker), stats.get(marker), true));
        }
        return new GatedPanel(List.copyOf(ordered), selection);
    }

    /** The markers a gate tree actually gates on, with their compartment/statistic. */
    public record GatedPanel(List<String> markers, MarkerSelection selection) {}
}
