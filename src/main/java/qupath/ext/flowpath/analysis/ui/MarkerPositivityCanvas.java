package qupath.ext.flowpath.analysis.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
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
 */
public final class MarkerPositivityCanvas extends PlotCanvas {

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
        for (List<PopulationStats.Row> nodeRows : byNode.values()) {
            if (nodeRows.size() != 2) continue; // not a plain two-branch threshold gate
            String channel = nodeRows.get(0).gateChannel();
            Tally tally = out.computeIfAbsent(channel, k -> new Tally());
            tally.positive += nodeRows.get(0).count();
            tally.negative += nodeRows.get(1).count();
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
     * Cells never evaluated against {@code marker} at all — the whole-slide total minus
     * every cell this canvas found in a positive or negative branch for it.
     */
    int ungatedCount(String marker) {
        Tally t = byMarker.get(marker);
        int measured = t == null ? 0 : t.positive + t.negative;
        return Math.max(0, scopeTotal - measured);
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
