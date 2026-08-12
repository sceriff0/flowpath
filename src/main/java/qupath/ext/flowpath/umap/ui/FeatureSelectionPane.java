package qupath.ext.flowpath.umap.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.umap.session.UmapSession;

import java.util.List;
import java.util.Set;

/**
 * Compact per-marker feature picker that drives which measurement key feeds the
 * UMAP matrix. One row per marker: an include checkbox, a compartment combo and
 * a statistic combo.
 * <p>
 * When the loaded GeoJSON is legacy (no rich per-compartment keys), the
 * compartment/statistic combos are disabled and pinned to whole-cell / mean; only
 * the include checkboxes stay live. Per-marker combos offer only the
 * compartments/statistics that actually exist for that marker (from
 * {@link CompartmentCapability}).
 * <p>
 * The pane owns no persistence and no model. It reads {@link UmapSession#selectionEntry}
 * for each row's initial value and reports every edit through
 * {@link UmapSession#editSelection}, then notifies its owner via {@code onChanged} so
 * {@code UmapPane} can persist and re-resolve.
 * <p>
 * <b>{@link #populate} takes the session itself, and nothing else.</b> It used to
 * {@code put} straight into a {@link MarkerSelection} it was handed, which meant the
 * include flag — an input to whether Run UMAP is clickable at all — changed without the
 * session hearing about it, and therefore without the panel re-deriving. Routing that
 * through a {@code BiConsumer} writer parameter fixed the behaviour but not the shape: the
 * call site could still name {@code selection()::put} and silently put the leak back, with
 * the whole suite green. There is no writer to name here. Reverting this needs a signature
 * change in this file and at both call sites, which is a compile error rather than a
 * one-word edit.
 */
final class FeatureSelectionPane extends VBox {

    /** Notified whenever the user changes any marker's selection. */
    @FunctionalInterface
    interface ChangeListener {
        void onChanged();
    }

    private final GridPane grid = new GridPane();
    private final Label header = new Label();
    private Runnable onChanged = () -> {};

    FeatureSelectionPane() {
        super(6);
        setPadding(new Insets(8));
        setPrefWidth(320);

        header.setStyle("-fx-font-weight: bold;");
        grid.setHgap(6);
        grid.setVgap(4);

        var scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(280);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    void setOnChanged(Runnable r) {
        this.onChanged = r == null ? () -> {} : r;
    }

    /**
     * Rebuild the grid from the session's panel, capability and selection — the only three
     * things a row needs, and the session is the only place all three agree.
     */
    void populate(UmapSession session) {
        grid.getChildren().clear();

        List<String> markers = session.markers();
        CompartmentCapability capability = session.capability();
        boolean rich = capability != null && capability.isRich();
        header.setText(rich
                ? "Features (per-marker compartment + statistic)"
                : "Features (legacy: whole-cell mean)");

        grid.add(new Label("Use"), 0, 0);
        grid.add(new Label("Marker"), 1, 0);
        grid.add(new Label("Compartment"), 2, 0);
        grid.add(new Label("Statistic"), 3, 0);

        int row = 1;
        for (String marker : markers) {
            MarkerSelection.Entry entry = session.selectionEntry(marker);

            CheckBox include = new CheckBox();
            include.setSelected(entry.included());
            include.selectedProperty().addListener((obs, o, n) -> {
                session.editSelection(marker, session.selectionEntry(marker).withIncluded(n));
                onChanged.run();
            });

            Label name = new Label(marker);
            name.setMaxWidth(110);

            ComboBox<Compartment> compCombo = new ComboBox<>();
            ComboBox<Statistic> statCombo = new ComboBox<>();

            Set<Compartment> comps = rich ? capability.compartmentsFor(marker) : Set.of();
            Set<Statistic> stats = rich ? capability.statisticsFor(marker) : Set.of();

            if (rich && !comps.isEmpty()) {
                // Order compartments/statistics by their enum declaration order.
                compCombo.setItems(FXCollections.observableArrayList(ordered(comps)));
                compCombo.setValue(comps.contains(entry.compartment())
                        ? entry.compartment() : firstOrDefault(comps, Compartment.defaultCompartment()));
                compCombo.setConverter(new CompartmentStringConverter());
                compCombo.setOnAction(e -> {
                    session.editSelection(marker,
                            session.selectionEntry(marker).withCompartment(compCombo.getValue()));
                    onChanged.run();
                });

                statCombo.setItems(FXCollections.observableArrayList(orderedStats(stats)));
                statCombo.setValue(stats.contains(entry.statistic())
                        ? entry.statistic() : firstOrDefaultStat(stats, Statistic.defaultStatistic()));
                statCombo.setConverter(new StatisticStringConverter());
                statCombo.setOnAction(e -> {
                    session.editSelection(marker,
                            session.selectionEntry(marker).withStatistic(statCombo.getValue()));
                    onChanged.run();
                });
            } else {
                // Legacy / no per-compartment keys for this marker: pin to whole-cell mean.
                compCombo.setItems(FXCollections.observableArrayList(Compartment.WHOLE_CELL));
                compCombo.setValue(Compartment.WHOLE_CELL);
                compCombo.setConverter(new CompartmentStringConverter());
                compCombo.setDisable(true);

                statCombo.setItems(FXCollections.observableArrayList(Statistic.MEAN));
                statCombo.setValue(Statistic.MEAN);
                statCombo.setConverter(new StatisticStringConverter());
                statCombo.setDisable(true);

                // Ensure the selection reflects the pinned default.
                session.editSelection(marker, session.selectionEntry(marker)
                        .withCompartment(Compartment.WHOLE_CELL).withStatistic(Statistic.MEAN));
            }

            compCombo.setPrefWidth(95);
            statCombo.setPrefWidth(80);

            grid.add(include, 0, row);
            GridPane.setHalignment(include, javafx.geometry.HPos.CENTER);
            grid.add(name, 1, row);
            grid.add(compCombo, 2, row);
            grid.add(statCombo, 3, row);
            row++;
        }
    }

    private static List<Compartment> ordered(Set<Compartment> set) {
        List<Compartment> out = new java.util.ArrayList<>();
        for (Compartment c : Compartment.values()) if (set.contains(c)) out.add(c);
        return out;
    }

    private static List<Statistic> orderedStats(Set<Statistic> set) {
        List<Statistic> out = new java.util.ArrayList<>();
        for (Statistic s : Statistic.values()) if (set.contains(s)) out.add(s);
        return out;
    }

    private static Compartment firstOrDefault(Set<Compartment> set, Compartment fallback) {
        for (Compartment c : Compartment.values()) if (set.contains(c)) return c;
        return fallback;
    }

    private static Statistic firstOrDefaultStat(Set<Statistic> set, Statistic fallback) {
        for (Statistic s : Statistic.values()) if (set.contains(s)) return s;
        return fallback;
    }

    private static final class CompartmentStringConverter extends javafx.util.StringConverter<Compartment> {
        @Override public String toString(Compartment c) { return c == null ? "" : c.displayName(); }
        @Override public Compartment fromString(String s) { return Compartment.fromToken(s); }
    }

    private static final class StatisticStringConverter extends javafx.util.StringConverter<Statistic> {
        @Override public String toString(Statistic s) { return s == null ? "" : s.displayName(); }
        @Override public Statistic fromString(String s) { return Statistic.fromToken(s); }
    }
}
