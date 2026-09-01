package qupath.ext.flowpath.analysis.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import qupath.ext.flowpath.model.PopulationStats;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
 */
public final class ScopeComparisonCanvas extends PlotCanvas {

    private List<PopulationStats.Row> rows = List.of();
    private String selectedPath;

    public ScopeComparisonCanvas() {
        super(380, 220);
    }

    public void setRows(List<PopulationStats.Row> rows) {
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        if (selectedPath == null || this.rows.stream().noneMatch(r -> r.path().equals(selectedPath))) {
            selectedPath = this.rows.stream().map(PopulationStats.Row::path).findFirst().orElse(null);
        }
        repaint();
    }

    /** Choose which population is compared across scopes. */
    public void setSelectedPopulation(String path) {
        this.selectedPath = path;
        repaint();
    }

    /** Every distinct population path available to compare, in first-seen order. */
    List<String> availablePopulations() {
        Set<String> seen = new LinkedHashSet<>();
        for (PopulationStats.Row row : rows) seen.add(row.path());
        return List.copyOf(seen);
    }

    /**
     * The scopes the selected population actually has a row in, in nesting order —
     * {@code [WHOLE_SLIDE]} alone for an unannotated image, all three once regions exist.
     */
    List<PopulationStats.Scope> scopesPresent() {
        return Arrays.stream(PopulationStats.Scope.values())
                .filter(scope -> rows.stream()
                        .anyMatch(r -> r.scope() == scope && Objects.equals(r.path(), selectedPath)))
                .toList();
    }

    /**
     * The selected population's count at one scope. Summed rather than a single lookup, so
     * that {@code ANNOTATION_K}'s one row per region collapses to the one number this bar
     * needs, the same total {@code ANNOTATION_ALL} already reports.
     */
    int valueForScope(PopulationStats.Scope scope) {
        return rows.stream()
                .filter(r -> r.scope() == scope && Objects.equals(r.path(), selectedPath))
                .mapToInt(PopulationStats.Row::count)
                .sum();
    }

    @Override
    protected void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, getWidth(), getHeight());

        List<PopulationStats.Scope> scopes = scopesPresent();
        if (scopes.isEmpty()) {
            gc.setFill(Color.gray(0.5));
            gc.fillText("No data", getWidth() / 2 - 20, getHeight() / 2);
            return;
        }

        int maxCount = scopes.stream().mapToInt(this::valueForScope).max().orElse(1);
        int n = scopes.size();
        double barW = categoryWidth(n) * 0.6;
        double baseY = valueToY(0, 0, maxCount);

        for (int i = 0; i < n; i++) {
            double cx = categoryToX(i, n);
            double topY = valueToY(valueForScope(scopes.get(i)), 0, maxCount);
            gc.setFill(Color.rgb(87, 217, 163, 0.85));
            gc.fillRect(cx - barW / 2, topY, barW, baseY - topY);
        }
        drawAxes(gc, selectedPath == null ? "Scope" : selectedPath, "Count");
        drawCategoryLabels(gc, scopes.stream().map(Enum::name).toList());
        drawValueTicks(gc, 0, maxCount, 4);
    }
}
