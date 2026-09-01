package qupath.ext.flowpath.analysis.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.model.PopulationStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per marker: how much of the whole-slide population is positive, how much negative, and —
 * its own segment, not folded into negative — how much was never evaluated against that
 * marker at all.
 * <p>
 * <b>Why ungated is not negative.</b> A marker gated only under one branch of the tree (say,
 * {@code CD3} hanging off {@code CD45+}) never has its threshold applied to the cells that
 * took the other branch. Those cells are not "CD3-negative" — nobody asked the question —
 * and reporting them that way is exactly the unmeasured-is-not-negative error the
 * 2026-08-31 audit removed from the gating path itself. This plot exists so a partially
 * quantified panel is visible at a glance rather than silently smoothed into "negative".
 * <p>
 * Always summarises {@link PopulationStats.Scope#WHOLE_SLIDE}, filtering it out of whatever
 * row list it is handed — see {@link CompositionCanvas} for why.
 * <p>
 * <b>Deriving positive/negative without trusting branch names.</b> A user may rename a
 * gate's branches to anything, so this canvas does not match on {@code "+"}/{@code "-"}
 * suffixes. Instead it relies on an invariant of {@link PopulationStats}: every row for one
 * gate node shares one {@code (parent path, gateChannel)} pair, and within that pair the
 * node's positive branch is always emitted before its negative branch — {@link
 * qupath.ext.flowpath.model.GateNode#getBranches()} returns {@code [positive, negative]}, and
 * {@code PopulationStats.collect} adds a branch's own row before recursing into its
 * children, so the two rows for one node keep that relative order even when the positive
 * branch's subtree is interleaved between them in the flattened list. Multi-axis gates
 * (a quadrant or region gate, whose {@code gateChannel} joins two markers with {@code " / "})
 * have no single positive/negative axis and are excluded from this reduction entirely.
 * <p>
 * <b>A malformed gate group fails visibly, not silently.</b> {@code Branch.setChildren}
 * accepts any list with no dedup check, so two sibling gates on the identical channel under
 * one parent branch is constructible (however unintended). That groups four rows under one
 * {@code (parent path, channel)} key instead of the expected two, and this class cannot
 * decide which pair is "the" positive/negative branch. The cells behind that group really
 * were gated — ambiguously, by two independent thresholds — so they must not be reported as
 * ungated (unmeasured is not the same claim as ambiguously measured); they are excluded from
 * both the measured count and the marker's effective denominator, and the exclusion is
 * logged at WARN so the condition is visible rather than a chart that simply looks fine.
 */
public final class MarkerPositivityCanvas extends PlotCanvas {

    private static final Logger logger = LoggerFactory.getLogger(MarkerPositivityCanvas.class);

    /**
     * The identity of one gate node, as far as this reduction can see it: the path of the
     * branch it hangs off, and the channel it gates on. A record, not a string
     * concatenation — string-joining two arbitrary paths risks collision (or, as this class
     * once did, a control character used as a delimiter), where a compound key is
     * collision-safe by construction and costs nothing.
     */
    private record MarkerKey(String parentPath, String channel) {}

    private static final class Tally {
        int positive;
        int negative;
        /**
         * Cells caught by a malformed group for this marker (not exactly two rows sharing
         * a node) — removed from both "measured" and the effective denominator, so they
         * never show up as ungated either. See the class javadoc.
         */
        int excluded;
    }

    private List<PopulationStats.Row> wholeSlideRows = List.of();
    private final Map<String, Tally> byMarker = new LinkedHashMap<>();
    private int scopeTotal;

    public MarkerPositivityCanvas() {
        super(380, 220);
    }

    public void setRows(List<PopulationStats.Row> rows) {
        this.wholeSlideRows = rows == null ? List.of() : rows.stream()
                .filter(r -> r.scope() == PopulationStats.Scope.WHOLE_SLIDE)
                .toList();
        this.scopeTotal = wholeSlideRows.stream()
                .filter(r -> r.depth() == 0)
                .mapToInt(PopulationStats.Row::parentCount)
                .findFirst()
                .orElse(0);
        this.byMarker.clear();
        this.byMarker.putAll(tallyMarkers(wholeSlideRows));
        repaint();
    }

    private static Map<String, Tally> tallyMarkers(List<PopulationStats.Row> rows) {
        // Group single-axis rows by the node they came from: (parent path, channel). Two
        // rows share a node iff they share both -- a node's own two rows keep their
        // relative emission order (positive first) even when interleaved with a subtree.
        Map<MarkerKey, List<PopulationStats.Row>> byNode = new LinkedHashMap<>();
        for (PopulationStats.Row row : rows) {
            String channel = row.gateChannel();
            if (channel == null || channel.isEmpty() || channel.contains(" / ")) continue; // 2-axis gate
            MarkerKey key = new MarkerKey(parentPathOf(row), channel);
            byNode.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        Map<String, Tally> out = new LinkedHashMap<>();
        for (Map.Entry<MarkerKey, List<PopulationStats.Row>> entry : byNode.entrySet()) {
            MarkerKey key = entry.getKey();
            List<PopulationStats.Row> nodeRows = entry.getValue();
            // Always create the marker's entry, valid group or not, so a marker touched
            // only by a malformed group still appears (with zero measured) rather than
            // silently vanishing from the chart -- see the class javadoc.
            Tally tally = out.computeIfAbsent(key.channel(), k -> new Tally());
            if (nodeRows.size() == 2) {
                tally.positive += nodeRows.get(0).count();
                tally.negative += nodeRows.get(1).count();
            } else {
                // Every row sharing this (parent path, channel) key stems from the SAME
                // parent branch, so they all carry that branch's own parentCount -- the
                // number of distinct cells actually exposed to this ambiguous gate group,
                // as opposed to summing the rows' own counts, which double- (or N-)counts
                // a cell once per sibling gate that independently classified it.
                int excludedCells = nodeRows.get(0).parentCount();
                tally.excluded += excludedCells;
                logger.warn("MarkerPositivityCanvas: {} rows found for channel '{}' under parent "
                                + "branch '{}', expected exactly 2 (one gate's positive/negative "
                                + "branches) -- likely two sibling gates on the same channel. "
                                + "Excluding these {} cells from both measured and ungated rather "
                                + "than guessing which rows are the positive/negative pair.",
                        nodeRows.size(), key.channel(), key.parentPath().isEmpty() ? "(root)" : key.parentPath(),
                        excludedCells);
            }
        }
        return out;
    }

    /** Reverses {@code PopulationStats.collect}'s {@code prefix + "/" + branchName}. */
    private static String parentPathOf(PopulationStats.Row row) {
        String path = row.path();
        String branchName = row.branchName();
        if (path.equals(branchName)) return "";
        return path.substring(0, path.length() - branchName.length() - 1);
    }

    /** Every single-axis-gated marker this canvas can show, in first-seen order. */
    List<String> markers() {
        return List.copyOf(byMarker.keySet());
    }

    /** Cells that landed in {@code marker}'s positive branch, summed over every place it is gated. */
    int positiveCount(String marker) {
        Tally t = byMarker.get(marker);
        return t == null ? 0 : t.positive;
    }

    /** Cells that landed in {@code marker}'s negative branch, summed over every place it is gated. */
    int negativeCount(String marker) {
        Tally t = byMarker.get(marker);
        return t == null ? 0 : t.negative;
    }

    /**
     * Cells never evaluated against {@code marker} at all — the marker's effective
     * denominator (whole-slide total, minus any cells a malformed gate group excluded)
     * minus every cell this canvas found in a positive or negative branch for it. A
     * malformed group's cells are excluded from the denominator too, so they are never
     * reported as ungated — they were gated, just ambiguously.
     */
    int ungatedCount(String marker) {
        Tally t = byMarker.get(marker);
        if (t == null) return scopeTotal;
        int measured = t.positive + t.negative;
        int denominator = scopeTotal - t.excluded;
        return Math.max(0, denominator - measured);
    }

    /**
     * Cells this canvas excluded from {@code marker}'s measured/ungated split entirely,
     * because they came from a malformed gate group (see the class javadoc). Package-private
     * so a test can pin the exclusion directly rather than only inferring it from
     * {@link #ungatedCount}.
     */
    int excludedCount(String marker) {
        Tally t = byMarker.get(marker);
        return t == null ? 0 : t.excluded;
    }

    /** The whole-slide cell count every marker's three segments must sum to. */
    int scopeTotal() {
        return scopeTotal;
    }

    @Override
    protected void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, getWidth(), getHeight());

        List<String> markerList = markers();
        if (markerList.isEmpty() || scopeTotal <= 0) {
            gc.setFill(Color.gray(0.5));
            gc.fillText("No data", getWidth() / 2 - 20, getHeight() / 2);
            return;
        }

        int n = markerList.size();
        double barW = categoryWidth(n) * 0.6;
        double baseY = valueToY(0, 0, scopeTotal);

        Color posColor = Color.rgb(0, 200, 0, 0.85);
        Color negColor = Color.rgb(160, 160, 160, 0.85);
        Color ungatedColor = Color.rgb(80, 80, 90, 0.85);

        for (int i = 0; i < n; i++) {
            String marker = markerList.get(i);
            double cx = categoryToX(i, n);

            int pos = positiveCount(marker);
            int neg = negativeCount(marker);
            int ungated = ungatedCount(marker);

            double yAfterPos = valueToY(pos, 0, scopeTotal);
            double yAfterNeg = valueToY(pos + neg, 0, scopeTotal);
            double yAfterUngated = valueToY(pos + neg + ungated, 0, scopeTotal);

            gc.setFill(posColor);
            gc.fillRect(cx - barW / 2, yAfterPos, barW, baseY - yAfterPos);
            gc.setFill(negColor);
            gc.fillRect(cx - barW / 2, yAfterNeg, barW, yAfterPos - yAfterNeg);
            gc.setFill(ungatedColor);
            gc.fillRect(cx - barW / 2, yAfterUngated, barW, yAfterNeg - yAfterUngated);
        }
        drawAxes(gc, "Marker", "Count");
        drawCategoryLabels(gc, markerList);
        drawValueTicks(gc, 0, scopeTotal, 4);
        drawLegend(gc, List.of("Positive", "Negative", "Ungated"),
                new int[] {0x00C800, 0xA0A0A0, 0x505059});
    }
}
