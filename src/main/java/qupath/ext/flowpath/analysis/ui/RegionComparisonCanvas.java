package qupath.ext.flowpath.analysis.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
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

    /** Region names the selected population has a row in — the categories on the X axis. */
    List<String> regionLabels() {
        if (selected == null) return List.of();
        return regionRows.stream()
                .filter(selected::matches)
                .map(r -> r.regionName() == null ? "" : r.regionName())
                .toList();
    }

    /** The selected population's count in one region; 0 when that region has no row for it. */
    int valueForRegion(String regionName) {
        if (selected == null) return 0;
        return regionRows.stream()
                .filter(selected::matches)
                .filter(r -> Objects.equals(regionName, r.regionName()))
                .mapToInt(PopulationStats.Row::count)
                .findFirst()
                .orElse(0);
    }

    @Override
    protected void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, getWidth(), getHeight());

        List<String> regions = regionLabels();
        if (regions.isEmpty()) {
            gc.setFill(Color.gray(0.5));
            gc.fillText("No data", getWidth() / 2 - 20, getHeight() / 2);
            return;
        }

        int maxCount = regions.stream().mapToInt(this::valueForRegion).max().orElse(1);
        int n = regions.size();
        double barW = categoryWidth(n) * 0.6;
        double baseY = valueToY(0, 0, maxCount);

        for (int i = 0; i < n; i++) {
            String region = regions.get(i);
            double cx = categoryToX(i, n);
            double topY = valueToY(valueForRegion(region), 0, maxCount);
            gc.setFill(Color.rgb(76, 154, 255, 0.85));
            gc.fillRect(cx - barW / 2, topY, barW, baseY - topY);
        }
        drawAxes(gc, selected == null ? "Region" : selected.path(), "Count");
        drawCategoryLabels(gc, regions);
        drawValueTicks(gc, 0, maxCount, 4);
    }
}
