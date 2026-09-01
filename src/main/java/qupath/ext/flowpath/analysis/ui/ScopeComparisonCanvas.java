package qupath.ext.flowpath.analysis.ui;

import qupath.ext.flowpath.model.PopulationStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One population, compared at all three nested scopes — {@code ANNOTATION_K ⊆
 * ANNOTATION_ALL ⊆ WHOLE_SLIDE}.
 * <p>
 * Unlike {@link CompositionCanvas} and {@link RegionComparisonCanvas}, this canvas does not
 * narrow the rows it is handed to one scope — the scope <em>is</em> the axis being compared,
 * so it reads whatever {@link PopulationStats#rows()} the caller passes as-is.
 * {@link PopulationStats.Scope#ANNOTATION_K} may hold one row per region; its bar is their
 * sum, which by construction equals the {@code ANNOTATION_ALL} row for the same population
 * when the annotations cover every cell.
 * <p>
 * <b>A population is identified by {@link PopulationRef}, never by path alone.</b> Two
 * un-renamed root gates on one channel emit byte-identical paths, so a path-keyed sum here
 * added both roots' cells into one bar — 2x the true count, the same defect
 * {@link CompositionCanvas} was fixed for — and the path-keyed
 * {@link #availablePopulations()} hid the second root's populations from the picker
 * entirely. See {@link PopulationRef}.
 */
public final class ScopeComparisonCanvas extends PlotCanvas {

    /** No legend: one series, already named by the X-axis title. See {@code CompositionCanvas}. */
    private static final int LEGEND_ROWS = 0;

    private List<PopulationStats.Row> rows = List.of();
    private PopulationRef selected;

    public ScopeComparisonCanvas() {
        super(380, 220);
    }

    public void setRows(List<PopulationStats.Row> rows) {
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        if (selected == null || this.rows.stream().noneMatch(selected::matches)) {
            selected = this.rows.stream().map(PopulationRef::of).findFirst().orElse(null);
        }
        repaint();
    }

    /** Choose which population is compared across scopes. */
    public void setSelectedPopulation(PopulationRef population) {
        this.selected = population;
        repaint();
    }

    /** Every distinct population available to compare, in first-seen order. */
    List<PopulationRef> availablePopulations() {
        Set<PopulationRef> seen = new LinkedHashSet<>();
        for (PopulationStats.Row row : rows) seen.add(PopulationRef.of(row));
        return List.copyOf(seen);
    }

    /**
     * The population this canvas is currently comparing. Package-private, exercised directly
     * by {@code AnalysisPaneFxTest} to pin that the By Region and By Scope tabs' own combos
     * (Task 11) drive one shared selection rather than two independent ones.
     */
    PopulationRef selectedPopulation() {
        return selected;
    }

    /**
     * The scopes the selected population actually has a row in, in nesting order —
     * {@code [WHOLE_SLIDE]} alone for an unannotated image, all three once regions exist.
     */
    List<PopulationStats.Scope> scopesPresent() {
        return Arrays.stream(PopulationStats.Scope.values())
                .filter(scope -> rows.stream()
                        .anyMatch(r -> r.scope() == scope && selected != null && selected.matches(r)))
                .toList();
    }

    /**
     * The selected population's count at one scope. Summed rather than a single lookup, so
     * that {@code ANNOTATION_K}'s one row per region collapses to the one number this bar
     * needs, the same total {@code ANNOTATION_ALL} already reports.
     */
    int valueForScope(PopulationStats.Scope scope) {
        if (selected == null) return 0;
        return rows.stream()
                .filter(r -> r.scope() == scope && selected.matches(r))
                .mapToInt(PopulationStats.Row::count)
                .sum();
    }

    /**
     * One datum per bar — {@link #scopesPresent()} and {@link #valueForScope}, the exact
     * methods {@link #draw} calls to build its own axis and bars, not a fresh scan over
     * {@link #rows}.
     */
    @Override
    public List<PlotDatum> plotData() {
        if (selected == null) return List.of();
        String series = selected.path();
        List<PlotDatum> data = new ArrayList<>();
        for (PopulationStats.Scope scope : scopesPresent()) {
            data.add(new PlotDatum(scope.displayName(), series, valueForScope(scope)));
        }
        return data;
    }

    /**
     * {@code theme.series(1)} for every bar — deliberately the <em>second</em> palette entry,
     * not the first, so this plot and {@code RegionComparisonCanvas} do not read as the same
     * chart when a user flips between their two tabs.
     * <p>
     * The two empty states are different facts and say so. No rows at all means nothing has
     * been gated yet; rows with nothing selected means the user has a choice to make. The
     * single "No data" both used to show answered neither question.
     */
    @Override
    protected void draw(PlotSurface s, PlotTheme theme) {
        if (rows.isEmpty()) {
            drawEmptyState(s, theme, "No gated populations yet");
            return;
        }
        // Empty exactly when no population is selected -- scopesPresent() filters on the
        // selection, so a selection that matches nothing lands here too, which is the same
        // "pick something" state from the reader's point of view.
        List<PopulationStats.Scope> scopes = scopesPresent();
        if (scopes.isEmpty()) {
            drawEmptyState(s, theme, "Select a population to compare");
            return;
        }

        int n = scopes.size();
        double[] values = scopes.stream().mapToDouble(this::valueForScope).toArray();
        AxisScale scale = scaleFor(values);
        LabelLayout labels = layoutLabels(s,
                scopes.stream().map(PopulationStats.Scope::displayName).toList());
        double barW = categoryWidth(n) * 0.6;
        double baseY = fractionToY(0, labels, LEGEND_ROWS);

        drawValueTicks(s, theme, scale, 4, labels, LEGEND_ROWS);
        for (int i = 0; i < n; i++) {
            double cx = categoryToX(i, n);
            double value = valueForScope(scopes.get(i));
            double topY = fractionToY(scale.toFraction(value), labels, LEGEND_ROWS);
            s.setFill(theme.series(1));
            s.fillRect(cx - barW / 2, topY, barW, baseY - topY);
            if (scale.isClipped(value)) {
                drawClipMarker(s, theme, cx, barW, topY);
            }
        }
        drawAxes(s, theme, labels, LEGEND_ROWS,
                selected == null ? "Scope" : selected.path(), "Count");
        drawCategoryLabels(s, theme, labels, LEGEND_ROWS);
    }
}
