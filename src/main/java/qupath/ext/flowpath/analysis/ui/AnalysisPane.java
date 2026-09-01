package qupath.ext.flowpath.analysis.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.session.AnalysisState;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.PopulationStats;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * The Analysis window's body: a scope/denominator picker over a population table.
 * <p>
 * This class is a Humble Object over {@link AnalysisSession} — the same split
 * {@code UiStateController}/{@code UmapSession} make for the UMAP panel (see that class's
 * javadoc). The session decides what scopes and denominators exist and what the numbers
 * are; this pane only applies those answers to widgets. It never re-derives "is there
 * data", "which scopes are legal", or "what does an empty percentage mean" — those
 * questions already have single answers in {@link AnalysisSession} and
 * {@link PopulationStats.Row}, and a second answer here is exactly the kind of divergence
 * {@code CLAUDE.md} keeps a list of.
 */
public final class AnalysisPane extends BorderPane {

    private final AnalysisSession session;

    private final ChoiceBox<PopulationStats.Scope> scopeChoice = new ChoiceBox<>();
    private final ComboBox<Branch> denominatorCombo = new ComboBox<>();
    private final ComboBox<String> rootCombo = new ComboBox<>();
    private final ComboBox<String> populationCombo = new ComboBox<>();
    private final TableView<PopulationStats.Row> table = new TableView<>();
    private final Label placeholderLabel = new Label();

    private final CompositionCanvas compositionCanvas = new CompositionCanvas();
    private final RegionComparisonCanvas regionComparisonCanvas = new RegionComparisonCanvas();
    private final ScopeComparisonCanvas scopeComparisonCanvas = new ScopeComparisonCanvas();
    private final MarkerPositivityCanvas markerPositivityCanvas = new MarkerPositivityCanvas();

    private PopulationStats.Scope selectedScope;
    private Branch selectedDenominator;
    private String selectedRoot;
    private String selectedPopulation;

    public AnalysisPane(AnalysisSession session) {
        this.session = Objects.requireNonNull(session, "session");

        table.setPlaceholder(placeholderLabel);
        buildColumns();

        scopeChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(PopulationStats.Scope scope) {
                return scope == null ? "" : scope.name();
            }

            @Override
            public PopulationStats.Scope fromString(String s) {
                return null;
            }
        });
        denominatorCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Branch branch) {
                return branch == null ? "(none)" : branch.getName();
            }

            @Override
            public Branch fromString(String s) {
                return null;
            }
        });

        scopeChoice.valueProperty().addListener((obs, old, value) -> {
            selectedScope = value;
            updateTable();
        });
        denominatorCombo.valueProperty().addListener((obs, old, value) -> {
            selectedDenominator = value;
            updateTable();
        });
        // The root/population pickers only forward the user's choice to the one canvas
        // each drives -- deciding which root or population is "interesting" is not this
        // pane's job, so it never re-derives a default here beyond the same "fall back to
        // the first item" a ChoiceBox already does for scope/denominator above.
        rootCombo.valueProperty().addListener((obs, old, value) -> {
            selectedRoot = value;
            compositionCanvas.setSelectedRoot(value);
        });
        populationCombo.valueProperty().addListener((obs, old, value) -> {
            selectedPopulation = value;
            regionComparisonCanvas.setSelectedPopulation(value);
            scopeComparisonCanvas.setSelectedPopulation(value);
        });

        HBox controls = new HBox(10,
                new Label("Scope:"), scopeChoice,
                new Label("Denominator:"), denominatorCombo,
                new Label("Root:"), rootCombo,
                new Label("Population:"), populationCombo);
        controls.setPadding(new Insets(8));
        controls.setAlignment(Pos.CENTER_LEFT);

        TabPane plotTabs = new TabPane(
                plotTab("Composition", compositionCanvas),
                plotTab("By Region", regionComparisonCanvas),
                plotTab("By Scope", scopeComparisonCanvas),
                plotTab("Marker Positivity", markerPositivityCanvas));
        plotTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        SplitPane body = new SplitPane(table, plotTabs);
        body.setOrientation(Orientation.VERTICAL);
        body.setDividerPositions(0.45);

        setTop(controls);
        setCenter(body);

        refresh();
    }

    private static Tab plotTab(String title, PlotCanvas canvas) {
        Tab tab = new Tab(title, canvas);
        tab.setClosable(false);
        return tab;
    }

    /**
     * Adopt a freshly gated pass and redraw. Forwards straight to
     * {@link AnalysisSession#accept}; the pane decides nothing about what the pass means.
     */
    public void accept(AnalysisSession.AnalysisInput input) {
        session.accept(input);
        refresh();
    }

    /**
     * Choose the denominator every row's {@code percentOfDenominator} is reported against.
     * Package-private: exercised directly by the pane's own FX test, the same way
     * {@code HistogramCanvas.isPositiveAt} is — see that method's comment.
     */
    void setDenominator(Branch denominator) {
        selectedDenominator = denominator;
        denominatorCombo.setValue(denominator);
        updateTable();
    }

    /**
     * Choose which root gate's leaves {@link #compositionCanvas()} shows. Package-private,
     * exercised directly by the pane's own FX test, the same way {@link #setDenominator}
     * is.
     */
    void selectRoot(String rootName) {
        selectedRoot = rootName;
        rootCombo.setValue(rootName);
    }

    /** Choose which population {@link #regionComparisonCanvas()} and {@link #scopeComparisonCanvas()} compare. */
    void selectPopulation(String path) {
        selectedPopulation = path;
        populationCombo.setValue(path);
    }

    /** The root gates currently offered by the picker — {@link CompositionCanvas#availableRoots()}. */
    List<String> rootChoices() {
        return List.copyOf(rootCombo.getItems());
    }

    /** The populations currently offered by the picker. */
    List<String> populationChoices() {
        return List.copyOf(populationCombo.getItems());
    }

    /**
     * The embedded composition plot, for the pane's own FX test to confirm the root picker
     * actually reaches it rather than only updating this pane's bookkeeping.
     */
    CompositionCanvas compositionCanvas() {
        return compositionCanvas;
    }

    /** As {@link #compositionCanvas()}, for the region comparison plot. */
    RegionComparisonCanvas regionComparisonCanvas() {
        return regionComparisonCanvas;
    }

    /** As {@link #compositionCanvas()}, for the scope comparison plot. */
    ScopeComparisonCanvas scopeComparisonCanvas() {
        return scopeComparisonCanvas;
    }

    /** The table's current placeholder text — non-null exactly when there is no data. */
    String placeholderText() {
        return placeholderLabel.getText();
    }

    /** Rows currently shown, for the scope and denominator in effect. */
    int rowCount() {
        return table.getItems().size();
    }

    /**
     * The "% of Denominator" column's rendered text for one currently-shown row —
     * the same string {@link #formatPercent} produced for the table cell, not a
     * second computation of it. Exists so the NaN-renders-blank /
     * zero-renders-"0.0" distinction (see {@link #formatPercent}) is pinned by a
     * test rather than only ever eyeballed.
     */
    String formattedPercentOfDenominatorAt(int rowIndex) {
        return formatPercent(table.getItems().get(rowIndex).percentOfDenominator());
    }

    private void refresh() {
        AnalysisState state = session.state();

        List<PopulationStats.Scope> scopes = state.availableScopes();
        scopeChoice.getItems().setAll(scopes);
        if (selectedScope == null || !scopes.contains(selectedScope)) {
            selectedScope = scopes.isEmpty() ? null : scopes.get(0);
        }
        scopeChoice.setValue(selectedScope);

        List<Branch> denominators = session.denominatorChoices();
        denominatorCombo.getItems().setAll(denominators);
        if (selectedDenominator != null && !denominators.contains(selectedDenominator)) {
            selectedDenominator = null;
        }
        denominatorCombo.setValue(selectedDenominator);

        // The state guarantees emptyMessage() is non-null exactly when there is no data
        // (AnalysisState's compact constructor enforces it), so this is the only string
        // this pane ever shows for "nothing to report" -- never a message invented here.
        placeholderLabel.setText(state.emptyMessage() != null ? state.emptyMessage() : "");

        updateTable();
    }

    private void updateTable() {
        AnalysisState state = session.state();
        if (!state.hasData() || selectedScope == null) {
            table.getItems().clear();
            setAllPlotRows(List.of());
            return;
        }
        PopulationStats stats = session.stats(selectedDenominator);
        table.getItems().setAll(stats.rows(selectedScope));
        // Every plot canvas is handed the full, unfiltered row set (every scope, every
        // region) and narrows to what it means on its own -- CompositionCanvas and
        // MarkerPositivityCanvas to WHOLE_SLIDE, RegionComparisonCanvas to ANNOTATION_K,
        // ScopeComparisonCanvas to none of the above, since scope is the axis it compares.
        // See each canvas's own class javadoc.
        setAllPlotRows(stats.rows());
    }

    private void setAllPlotRows(List<PopulationStats.Row> rows) {
        compositionCanvas.setRows(rows);
        regionComparisonCanvas.setRows(rows);
        scopeComparisonCanvas.setRows(rows);
        markerPositivityCanvas.setRows(rows);

        // Each canvas already fell back to its own first-available choice inside setRows
        // above when the previous selection no longer applies; this pane mirrors that same
        // choice into the pickers rather than computing a different one of its own.
        List<String> roots = compositionCanvas.availableRoots();
        rootCombo.getItems().setAll(roots);
        if (selectedRoot == null || !roots.contains(selectedRoot)) {
            selectedRoot = roots.isEmpty() ? null : roots.get(0);
        }
        rootCombo.setValue(selectedRoot);

        List<String> populations = scopeComparisonCanvas.availablePopulations();
        populationCombo.getItems().setAll(populations);
        if (selectedPopulation == null || !populations.contains(selectedPopulation)) {
            selectedPopulation = populations.isEmpty() ? null : populations.get(0);
        }
        populationCombo.setValue(selectedPopulation);
    }

    private void buildColumns() {
        table.getColumns().setAll(List.of(
                column("Population", PopulationStats.Row::path),
                column("Region", row -> row.regionName() == null ? "" : row.regionName()),
                column("Count", row -> String.valueOf(row.count())),
                column("Clean", row -> String.valueOf(row.cleanCount())),
                column("% Parent", row -> formatPercent(row.percentOfParent())),
                column("% Total", row -> formatPercent(row.percentOfTotal())),
                column("% of Denominator", row -> formatPercent(row.percentOfDenominator())),
                column("Density", row -> formatDensity(row.densityPerMm2()))));
    }

    private static TableColumn<PopulationStats.Row, String> column(
            String title, Function<PopulationStats.Row, String> extractor) {
        TableColumn<PopulationStats.Row, String> col = new TableColumn<>(title);
        col.setCellValueFactory(data -> new SimpleStringProperty(extractor.apply(data.getValue())));
        return col;
    }

    /**
     * {@code NaN} (no denominator chosen) renders as an empty cell; a real zero — a chosen
     * denominator that happens to hold no cells — renders as {@code "0.0"}. These are
     * different answers ({@link PopulationStats.Row#percentOfDenominator()}) and must not
     * collapse to the same text.
     */
    private static String formatPercent(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.1f", value);
    }

    /** {@code NaN} (no area known for this row) renders as an empty cell, not "NaN". */
    private static String formatDensity(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.1f", value);
    }
}
