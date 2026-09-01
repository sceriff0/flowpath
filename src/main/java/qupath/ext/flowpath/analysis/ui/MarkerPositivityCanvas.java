package qupath.ext.flowpath.analysis.ui;

import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.model.PopulationStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <b>One bar per root gate, not per channel.</b> Two independent root gates on the same
 * channel — "compare two thresholds side by side" — ask the same question of the same cells
 * twice, so their answers are reported as two bars ({@code "CD45 (root 1)"},
 * {@code "CD45 (root 2)"}) rather than added together, which would claim more positives and
 * negatives than the slide has cells. Within one root a marker gated under several branches
 * still pools, but only across branches that <em>partition</em> the cells rather than
 * repeat them — see below.
 * <p>
 * <b>Sibling gates pool; nested ones do not.</b> The same marker gated under {@code CD45+}
 * and under {@code CD45-} measures two disjoint sets of cells, so adding the two answers is
 * exactly right. The same marker gated at the root <em>and again</em> beneath its own
 * positive branch is a different shape entirely: the deeper gate re-measures a subset of
 * the cells the shallower one already judged, so pooling counts them twice and
 * {@code positive + negative} exceeds the population. Nothing caught that — {@code
 * ungatedCount} clamps at 0 and the bar's segments clamp at full height, so the chart
 * showed a fully measured marker with no hint that its numbers summed past the total.
 * A group whose parent path is a strict descendant of another group's, for the same root
 * and channel, is therefore dropped in favour of the shallower one, which already covers
 * every cell the deeper one saw.
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
     * The three segments of every bar, in stacking order from the axis up. One list, read by
     * both the drawing loop and the legend, so a legend row can never name a segment the bar
     * does not draw — and {@code LEGEND_ROWS} is its size rather than a literal 3, so the
     * strip {@code plotTop} reserves cannot fall out of step with what fills it.
     */
    private static final List<String> SEGMENTS = List.of("Positive", "Negative", "Ungated");

    private static final int LEGEND_ROWS = SEGMENTS.size();

    /**
     * The identity of one gate node, as far as this reduction can see it: the root gate it
     * descends from, the path of the branch it hangs off, and the channel it gates on. A
     * record, not a string concatenation — string-joining arbitrary paths risks collision
     * (or, as this class once did, a control character used as a delimiter), where a
     * compound key is collision-safe by construction and costs nothing.
     * <p>
     * <b>{@code rootIndex} is part of it.</b> Two independent root gates on the identical
     * channel — the classic "compare two thresholds side by side" workflow — emit
     * byte-identical paths, so a {@code (parentPath, channel)} key collected all four of
     * their rows into one group. This class then diagnosed that as a malformed sibling gate
     * group, logged a WARN and dropped every one of those cells: the workflow rendered an
     * empty bar plus a spurious warning. With the root in the key each root is its own
     * node group again, and the malformed-group branch below is back to meaning what it
     * says.
     */
    private record MarkerKey(int rootIndex, String parentPath, String channel) {}

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
        // One bar per (root, marker), not per marker: two root gates on the same channel are
        // two independent questions asked of the SAME cells, so pooling them would report
        // more positives and negatives than the slide has cells. Within one root a marker
        // gated in several places still pools, which is the "summed over every place it is
        // gated" the accessors below describe.
        boolean multiRoot = wholeSlideRows.stream()
                .mapToInt(PopulationStats.Row::rootIndex).distinct().count() > 1;
        this.byMarker.clear();
        this.byMarker.putAll(tallyMarkers(wholeSlideRows, multiRoot));
        repaint();
    }

    /**
     * How a marker's bar is labelled: its channel, plus which root gate asked the question
     * when the report holds more than one — otherwise two roots on one channel would draw
     * two bars a reader cannot tell apart.
     */
    private static String markerLabel(MarkerKey key, boolean multiRoot) {
        return multiRoot ? key.channel() + " (root " + (key.rootIndex() + 1) + ")" : key.channel();
    }

    private static Map<String, Tally> tallyMarkers(List<PopulationStats.Row> rows, boolean multiRoot) {
        // Group single-axis rows by the node they came from: (parent path, channel). Two
        // rows share a node iff they share both -- a node's own two rows keep their
        // relative emission order (positive first) even when interleaved with a subtree.
        Map<MarkerKey, List<PopulationStats.Row>> byNode = new LinkedHashMap<>();
        for (PopulationStats.Row row : rows) {
            String channel = row.gateChannel();
            if (channel == null || channel.isEmpty() || channel.contains(" / ")) continue; // 2-axis gate
            MarkerKey key = new MarkerKey(row.rootIndex(), parentPathOf(row), channel);
            byNode.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // Drop any group that sits below another group for the same root and channel. Those
        // two measure overlapping cells rather than disjoint ones, and the shallower group
        // already covers everything the deeper one saw -- see the class javadoc.
        Set<MarkerKey> nested = new LinkedHashSet<>();
        for (MarkerKey candidate : byNode.keySet()) {
            for (MarkerKey other : byNode.keySet()) {
                if (candidate.equals(other)) continue;
                if (candidate.rootIndex() != other.rootIndex()) continue;
                if (!candidate.channel().equals(other.channel())) continue;
                if (isStrictDescendant(candidate.parentPath(), other.parentPath())) {
                    logger.warn("MarkerPositivityCanvas: channel '{}' is gated at '{}' and again "
                                    + "below it at '{}' within root {}. The deeper gate re-measures "
                                    + "cells the shallower one already judged, so it is not added in "
                                    + "-- pooling both would report more cells than the population "
                                    + "holds.",
                            candidate.channel(),
                            other.parentPath().isEmpty() ? "(root)" : other.parentPath(),
                            candidate.parentPath(), candidate.rootIndex() + 1);
                    nested.add(candidate);
                    break;
                }
            }
        }

        Map<String, Tally> out = new LinkedHashMap<>();
        for (Map.Entry<MarkerKey, List<PopulationStats.Row>> entry : byNode.entrySet()) {
            MarkerKey key = entry.getKey();
            if (nested.contains(key)) {
                // Still make sure the marker has an entry, so it keeps its bar.
                out.computeIfAbsent(markerLabel(key, multiRoot), k -> new Tally());
                continue;
            }
            List<PopulationStats.Row> nodeRows = entry.getValue();
            // Always create the marker's entry, valid group or not, so a marker touched
            // only by a malformed group still appears (with zero measured) rather than
            // silently vanishing from the chart -- see the class javadoc.
            Tally tally = out.computeIfAbsent(markerLabel(key, multiRoot), k -> new Tally());
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

    /**
     * Is {@code candidate} a gating path strictly below {@code ancestor}?
     * <p>
     * The root's parent path is the empty string, so every non-empty path is strictly below
     * it. Otherwise the boundary must fall on a {@code "/"} separator: {@code "CD4+"} is not
     * below {@code "CD"} merely because the string starts with it.
     */
    private static boolean isStrictDescendant(String candidate, String ancestor) {
        if (candidate.equals(ancestor)) return false;
        if (ancestor.isEmpty()) return !candidate.isEmpty();
        return candidate.startsWith(ancestor + "/");
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

    /**
     * The three segments take {@code theme.positive()}, {@code theme.negative()} and
     * {@code theme.ungated()}, and the legend takes the same three values from the same
     * accessors rather than from a parallel list of literals.
     * <p>
     * That parallel list is what this canvas got wrong before: the bars drew ungated in
     * {@code rgb(80, 80, 90)} on an {@code rgb(30, 30, 30)} background — a contrast ratio low
     * enough that the one segment this plot exists to make visible was the least visible thing
     * on it — while the legend swatch beside it was {@code 0x505059}, close but not equal. Two
     * lists of colours for one set of segments is a divergence waiting to happen; there is now
     * only one.
     * <p>
     * The two empty states name different problems: a panel with no single-marker gates has
     * nothing to report, whereas a scope holding no cells has nothing to report it about.
     */
    @Override
    protected void draw(PlotSurface s, PlotTheme theme) {
        List<String> markerList = markers();
        if (markerList.isEmpty()) {
            drawEmptyState(s, theme, "No single-marker gates to report");
            return;
        }
        if (scopeTotal <= 0) {
            drawEmptyState(s, theme, "No cells in this scope");
            return;
        }

        List<Color> segmentColors = List.of(theme.positive(), theme.negative(), theme.ungated());
        int n = markerList.size();
        // The value that matters for the axis is each bar's own total, not the shared
        // scopeTotal a bar draws against -- the two agree whenever every marker's cells are
        // fully accounted for (the common case), but a marker with a malformed sibling-gate
        // group (see the class javadoc) excludes some cells from itself entirely, so its bar's
        // own total falls short of scopeTotal. Scaling from the real per-bar tops is what a
        // percentile clip needs to be meaningful rather than accidentally correct only because
        // every bar happens to share one height.
        double[] values = markerList.stream()
                .mapToDouble(m -> positiveCount(m) + negativeCount(m) + ungatedCount(m))
                .toArray();
        AxisScale scale = scaleFor(values);
        LabelLayout labels = layoutLabels(s, markerList);
        double barW = categoryWidth(n) * 0.6;
        double baseY = fractionToY(0, labels, LEGEND_ROWS);

        drawValueTicks(s, theme, scale, 4, labels, LEGEND_ROWS);
        for (int i = 0; i < n; i++) {
            String marker = markerList.get(i);
            double cx = categoryToX(i, n);
            double x = cx - barW / 2;

            // Read back from values[i] rather than recomputing -- that array is this bar's
            // total already, computed once above to build the axis. A second
            // positiveCount+negativeCount+ungatedCount here would be the same "two expressions
            // computing one number" this task exists to remove one level up, at the value->Y
            // mapping itself.
            double total = values[i];

            // Cumulative tops, so each segment is drawn as the gap between two heights on the
            // one axis mapping -- a stack built from per-segment heights instead would drift
            // by a pixel per segment against the ticks beside it.
            double yAfterPositive = fractionToY(scale.toFraction(positiveCount(marker)), labels, LEGEND_ROWS);
            double yAfterNegative = fractionToY(
                    scale.toFraction(positiveCount(marker) + negativeCount(marker)), labels, LEGEND_ROWS);
            double yAfterUngated = fractionToY(scale.toFraction(total), labels, LEGEND_ROWS);

            s.setFill(segmentColors.get(0));
            s.fillRect(x, yAfterPositive, barW, baseY - yAfterPositive);
            s.setFill(segmentColors.get(1));
            s.fillRect(x, yAfterNegative, barW, yAfterPositive - yAfterNegative);
            s.setFill(segmentColors.get(2));
            s.fillRect(x, yAfterUngated, barW, yAfterNegative - yAfterUngated);
            if (scale.isClipped(total)) {
                drawClipMarker(s, theme, cx, barW, yAfterUngated);
            }
        }
        drawAxes(s, theme, labels, LEGEND_ROWS, "Marker", "Count");
        drawCategoryLabels(s, theme, labels, LEGEND_ROWS);
        drawLegend(s, theme, LEGEND_ROWS, SEGMENTS, segmentColors);
    }
}
