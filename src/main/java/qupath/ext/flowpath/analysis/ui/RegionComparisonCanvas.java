package qupath.ext.flowpath.analysis.ui;

import qupath.ext.flowpath.model.PopulationStats;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One population, compared across every annotated region on one shared axis.
 * <p>
 * Always reads {@link PopulationStats.Scope#ANNOTATION_K} — a caller may hand this canvas
 * the full, unfiltered {@link PopulationStats#rows()} and it filters to the per-region rows
 * itself, the same way {@link CompositionCanvas} filters to whole-slide.
 * <p>
 * <b>A population is identified by {@link PopulationRef}, never by path alone.</b> Two
 * un-renamed root gates on one channel emit byte-identical paths, so a path-keyed
 * {@link #regionLabels()} emitted every region twice and {@link #valueForRegion} then
 * reported the first root's number under both labels. See {@link PopulationRef}.
 */
public final class RegionComparisonCanvas extends PlotCanvas {

    /** No legend: one series, already named by the X-axis title. See {@code CompositionCanvas}. */
    private static final int LEGEND_ROWS = 0;

    private List<PopulationStats.Row> regionRows = List.of();
    private PopulationRef selected;

    public RegionComparisonCanvas() {
        super(380, 220);
    }

    public void setRows(List<PopulationStats.Row> rows) {
        this.regionRows = rows == null ? List.of() : rows.stream()
                .filter(r -> r.scope() == PopulationStats.Scope.ANNOTATION_K)
                .toList();
        if (selected == null || regionRows.stream().noneMatch(selected::matches)) {
            selected = regionRows.stream().map(PopulationRef::of).findFirst().orElse(null);
        }
        repaint();
    }

    /** Choose which population's count is compared across regions. */
    public void setSelectedPopulation(PopulationRef population) {
        this.selected = population;
        repaint();
    }

    /** Every distinct population available to compare, in first-seen order. */
    List<PopulationRef> availablePopulations() {
        Set<PopulationRef> seen = new LinkedHashSet<>();
        for (PopulationStats.Row row : regionRows) seen.add(PopulationRef.of(row));
        return List.copyOf(seen);
    }

    /**
     * The selected population's rows, one per region, in region order — the bars.
     * <p>
     * The row itself is the unit, not its name. Region names are not unique:
     * {@code RegionMask} falls back to an annotation's classification when it has none of
     * its own, so two annotations both classified {@code Tumor} are both called
     * {@code "Tumor"}, which is the ordinary way a slide gets annotated rather than an
     * edge case. Keying the bars on the name meant {@code valueForRegion} resolved it with
     * {@code findFirst()} and both bars drew the first Tumor region's count, with the
     * second region unreachable. {@link PopulationStats.Row#regionIndex()} is the identity.
     */
    List<PopulationStats.Row> regionBars() {
        if (selected == null) return List.of();
        return regionRows.stream()
                .filter(selected::matches)
                .sorted(java.util.Comparator.comparingInt(PopulationStats.Row::regionIndex))
                .toList();
    }

    /** Region labels for the X axis, parallel to {@link #regionBars()}. May contain repeats. */
    List<String> regionLabels() {
        return regionBars().stream()
                .map(r -> r.regionName() == null ? "" : r.regionName())
                .toList();
    }

    /**
     * The selected population's count in region {@code regionIndex}; 0 when that region has
     * no row for it.
     */
    int valueForRegion(int regionIndex) {
        if (selected == null) return 0;
        return regionRows.stream()
                .filter(selected::matches)
                .filter(r -> r.regionIndex() == regionIndex)
                .mapToInt(PopulationStats.Row::count)
                .findFirst()
                .orElse(0);
    }

    /**
     * One series, so one colour — {@code theme.series(0)}, at full opacity. The 0.85 alpha the
     * bars used to carry was there to soften a hardcoded blue against a hardcoded dark
     * background; the palette is now contrast-checked against its own background (see {@code
     * PlotTheme}), and thinning a checked colour with alpha would put it back below the floor
     * that check exists to hold.
     */
    @Override
    protected void draw(PlotSurface s, PlotTheme theme) {
        if (regionRows.isEmpty()) {
            drawEmptyState(s, theme, "No annotated regions to compare");
            return;
        }
        // One bar per row, not one bar per distinct name -- two regions may share a name.
        List<PopulationStats.Row> bars = regionBars();
        if (bars.isEmpty()) {
            drawEmptyState(s, theme, "Select a population to compare");
            return;
        }

        int maxCount = bars.stream().mapToInt(PopulationStats.Row::count).max().orElse(1);
        int n = bars.size();
        LabelLayout labels = layoutLabels(s, regionLabels());
        double barW = categoryWidth(n) * 0.6;
        double baseY = valueToY(0, 0, maxCount, labels, LEGEND_ROWS);

        drawValueTicks(s, theme, 0, maxCount, 4, labels, LEGEND_ROWS);
        s.setFill(theme.series(0));
        for (int i = 0; i < n; i++) {
            double cx = categoryToX(i, n);
            double topY = valueToY(bars.get(i).count(), 0, maxCount, labels, LEGEND_ROWS);
            s.fillRect(cx - barW / 2, topY, barW, baseY - topY);
        }
        drawAxes(s, theme, labels, LEGEND_ROWS,
                selected == null ? "Region" : selected.path(), "Count");
        drawCategoryLabels(s, theme, labels);
    }
}
