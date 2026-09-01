package qupath.ext.flowpath.analysis.ui;

import qupath.ext.flowpath.model.PopulationStats;

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

        int maxCount = scopes.stream().mapToInt(this::valueForScope).max().orElse(1);
        int n = scopes.size();
        LabelLayout labels = layoutLabels(s,
                scopes.stream().map(PopulationStats.Scope::displayName).toList());
        double barW = categoryWidth(n) * 0.6;
        double baseY = valueToY(0, 0, maxCount, labels, LEGEND_ROWS);

        drawValueTicks(s, theme, 0, maxCount, 4, labels, LEGEND_ROWS);
        s.setFill(theme.series(1));
        for (int i = 0; i < n; i++) {
            double cx = categoryToX(i, n);
            double topY = valueToY(valueForScope(scopes.get(i)), 0, maxCount, labels, LEGEND_ROWS);
            s.fillRect(cx - barW / 2, topY, barW, baseY - topY);
        }
        drawAxes(s, theme, labels, LEGEND_ROWS,
                selected == null ? "Scope" : selected.path(), "Count");
        drawCategoryLabels(s, theme, labels);
    }
}
