package qupath.ext.flowpath.umap.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.session.UmapSession;
import qupath.ext.flowpath.umap.model.PopulationTag;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The phenotype / population legend beside the UMAP plot.
 * <p>
 * Deliberately more than a colour key. On a 20-population embedding the legend is the
 * only affordance that makes the plot readable, so each row is also a control:
 * <ul>
 *   <li><b>Click</b> to hide or show that population — the fastest way to dig a rare
 *       population out from under a dominant one.</li>
 *   <li><b>Hover</b> to highlight it in place, without committing to a filter.</li>
 *   <li>Counts carry a share-of-total percentage, because "12,481 cells" only becomes
 *       meaningful next to "31%".</li>
 * </ul>
 * Rows are sized so the colour swatch, the name and the count each have a stable
 * column; a long phenotype name truncates with the full name available on hover rather
 * than reflowing the row and shifting every count out of alignment.
 */
public class PhenotypeLegend extends ScrollPane {

    private static final String HEADER_STYLE =
            "-fx-text-fill: #9aa0a6; -fx-font-weight: bold; -fx-font-size: 10;";
    private static final String ROW_STYLE =
            "-fx-background-color: transparent; -fx-background-radius: 3;";
    private static final String ROW_HOVER_STYLE =
            "-fx-background-color: #3a3a3a; -fx-background-radius: 3;";

    private final VBox content;
    private Consumer<String> onPopulationRemove;
    private Consumer<String> onPhenotypeToggled;
    private Consumer<String> onPhenotypeHover;
    private Runnable onShowAll;

    public PhenotypeLegend() {
        content = new VBox(2);
        content.setPadding(new Insets(6));
        content.setStyle("-fx-background-color: #2a2a2a;");
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setPrefWidth(200);
        setMinWidth(160);
        setStyle("-fx-background: #2a2a2a; -fx-background-color: #2a2a2a;");
    }

    public void setOnPopulationRemove(Consumer<String> cb) { this.onPopulationRemove = cb; }

    /** Called with a phenotype name when the user clicks its row to hide or show it. */
    public void setOnPhenotypeToggled(Consumer<String> cb) { this.onPhenotypeToggled = cb; }

    /**
     * Called with a phenotype name when the pointer enters its row, and with
     * {@code null} when it leaves — so the canvas can highlight and un-highlight.
     */
    public void setOnPhenotypeHover(Consumer<String> cb) { this.onPhenotypeHover = cb; }

    /** Called when the user clicks "show all" to clear every hidden phenotype. */
    public void setOnShowAll(Runnable cb) { this.onShowAll = cb; }

    /**
     * Update the legend from a gating snapshot's populations.
     *
     * @param populations phenotypes with their gate-tree colour and cell count
     * @param tags        population tags drawn as rings, or {@code null}
     * @param totalCells  denominator for the share-of-total percentages
     * @param hidden      phenotype names currently hidden from the plot
     */
    public void update(List<PhenotypeSnapshot.Population> populations,
                       List<PopulationTag> tags,
                       int totalCells,
                       Set<String> hidden) {
        content.getChildren().clear();
        if (populations == null || populations.isEmpty()) return;

        addPhenotypeHeader(populations.size(), hidden);
        for (var p : populations) {
            content.getChildren().add(
                    phenotypeRow(p.name(), p.color(), p.count(), totalCells,
                            hidden != null && hidden.contains(p.name())));
        }
        addTagSection(tags);
    }

    /**
     * Legacy path: derive the legend by scanning each cell's {@link PathClass}.
     * <p>
     * Used when the pane runs without a gating snapshot — cells classified by something
     * other than FlowPath still deserve a legend.
     */
    public void update(PathObject[] objects, List<PopulationTag> tags) {
        content.getChildren().clear();
        if (objects == null || objects.length == 0) return;

        var sorted = new ArrayList<>(UmapSession.classCounts(objects).entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));

        addPhenotypeHeader(sorted.size(), null);
        for (var entry : sorted) {
            content.getChildren().add(phenotypeRow(entry.getKey(), entry.getValue()[1],
                    entry.getValue()[0], objects.length, false));
        }
        addTagSection(tags);
    }

    /** Clear every row. */
    public void clear() {
        content.getChildren().clear();
    }

    // --- Row construction ---

    private void addPhenotypeHeader(int count, Set<String> hidden) {
        var header = new Label("Phenotypes (" + count + ")");
        header.setStyle(HEADER_STYLE);

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var headerRow = new HBox(4, header, spacer);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // The "show all" affordance only exists while something is hidden — an
        // always-present disabled link is noise in a 200px-wide rail.
        if (hidden != null && !hidden.isEmpty()) {
            var showAll = new Hyperlink("show all");
            showAll.setStyle("-fx-text-fill: #6ea8fe; -fx-font-size: 9; -fx-padding: 0;");
            showAll.setTooltip(new Tooltip(hidden.size() + " phenotype(s) hidden — click to restore"));
            showAll.setOnAction(e -> {
                if (onShowAll != null) onShowAll.run();
            });
            headerRow.getChildren().add(showAll);
        }
        content.getChildren().add(headerRow);
    }

    private HBox phenotypeRow(String name, int color, int count, int total, boolean hiddenRow) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        var swatch = new Circle(5, Color.rgb(r, g, b));
        // A hidden population keeps its colour as an outline so the row still reads as
        // "this one, switched off" rather than as a different, greyed-out population.
        if (hiddenRow) {
            swatch.setFill(Color.TRANSPARENT);
            swatch.setStroke(Color.rgb(r, g, b, 0.65));
            swatch.setStrokeWidth(1.5);
        }

        var label = new Label(truncate(name, 16));
        label.setStyle("-fx-text-fill: " + (hiddenRow ? "#6b6b6b" : "#d5d5d5") + "; -fx-font-size: 10;");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        String pct = total > 0 ? String.format("%.1f%%", 100.0 * count / total) : "—";
        var countLabel = new Label(pct);
        countLabel.setStyle("-fx-text-fill: " + (hiddenRow ? "#5a5a5a" : "#9aa0a6") + "; -fx-font-size: 9;");

        var row = new HBox(5, swatch, label, spacer, countLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 4, 2, 3));
        row.setStyle(ROW_STYLE);

        Tooltip.install(row, new Tooltip(String.format(
                "%s%n%,d cells (%s of shown)%n%nClick to %s", name, count, pct,
                hiddenRow ? "show" : "hide")));

        row.setOnMouseEntered(e -> {
            row.setStyle(ROW_HOVER_STYLE);
            if (onPhenotypeHover != null && !hiddenRow) onPhenotypeHover.accept(name);
        });
        row.setOnMouseExited(e -> {
            row.setStyle(ROW_STYLE);
            if (onPhenotypeHover != null) onPhenotypeHover.accept(null);
        });
        row.setOnMouseClicked(e -> {
            if (onPhenotypeToggled != null) onPhenotypeToggled.accept(name);
        });

        return row;
    }

    private void addTagSection(Collection<PopulationTag> tags) {
        if (tags == null || tags.isEmpty()) return;

        var header = new Label("Tagged populations");
        header.setStyle(HEADER_STYLE + " -fx-padding: 8 0 0 0;");
        content.getChildren().add(header);

        for (PopulationTag tag : tags) {
            int tc = tag.color();
            var ring = new Circle(5);
            ring.setFill(Color.TRANSPARENT);
            ring.setStroke(Color.rgb((tc >> 16) & 0xFF, (tc >> 8) & 0xFF, tc & 0xFF));
            ring.setStrokeWidth(2);

            var label = new Label(truncate(tag.name(), 14));
            label.setStyle("-fx-text-fill: #d5d5d5; -fx-font-size: 10;");

            var spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            var countLabel = new Label(String.format("%,d", tag.count()));
            countLabel.setStyle("-fx-text-fill: #9aa0a6; -fx-font-size: 9;");

            var row = new HBox(5, ring, label, spacer, countLabel);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(2, 4, 2, 3));
            row.setStyle(ROW_STYLE);
            Tooltip.install(row, new Tooltip(String.format(
                    "%s%n%,d cells%n%nRight-click to remove this tag", tag.name(), tag.count())));

            row.setOnMouseEntered(e -> row.setStyle(ROW_HOVER_STYLE));
            row.setOnMouseExited(e -> row.setStyle(ROW_STYLE));
            row.setOnContextMenuRequested(e -> {
                if (onPopulationRemove != null) onPopulationRemove.accept(tag.name());
            });

            content.getChildren().add(row);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
