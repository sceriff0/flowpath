package qupath.ext.flowpath.analysis.ui;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.session.AnalysisState;
import qupath.ext.flowpath.analysis.session.DenominatorRef;
import qupath.ext.flowpath.io.PopulationStatsExporter;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.lib.gui.dialogs.Dialogs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

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
    private final ComboBox<DenominatorRef> denominatorCombo = new ComboBox<>();
    private final ComboBox<Integer> rootCombo = new ComboBox<>();
    private final ComboBox<PopulationRef> populationCombo = new ComboBox<>();
    private final Button exportButton = new Button("Export CSV...");
    private final TableView<PopulationStats.Row> table = new TableView<>();
    private final Label placeholderLabel = new Label();

    private final CompositionCanvas compositionCanvas = new CompositionCanvas();
    private final RegionComparisonCanvas regionComparisonCanvas = new RegionComparisonCanvas();
    private final ScopeComparisonCanvas scopeComparisonCanvas = new ScopeComparisonCanvas();
    private final MarkerPositivityCanvas markerPositivityCanvas = new MarkerPositivityCanvas();

    private PopulationStats.Scope selectedScope;
    private DenominatorRef selectedDenominatorRef;
    private Integer selectedRoot;
    private PopulationRef selectedPopulation;

    public AnalysisPane(AnalysisSession session) {
        this.session = Objects.requireNonNull(session, "session");

        table.setPlaceholder(placeholderLabel);
        buildColumns();

        scopeChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(PopulationStats.Scope scope) {
                return scope == null ? "" : scope.displayName();
            }

            @Override
            public PopulationStats.Scope fromString(String s) {
                return null;
            }
        });
        // "(root N)" is appended only when more than one enabled root is currently on offer
        // -- self-referencing denominatorCombo.getItems(), the same pattern rootCombo's own
        // converter below uses, rather than cross-referencing rootCombo (whose items are not
        // rebuilt until later in refresh()/updateTable(), so reading them here could render
        // against a stale root count).
        denominatorCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(DenominatorRef ref) {
                if (ref == null) return "(none)";
                long distinctRoots = denominatorCombo.getItems().stream()
                        .filter(Objects::nonNull)
                        .map(DenominatorRef::rootIndex)
                        .distinct()
                        .count();
                return ref.label(distinctRoots > 1);
            }

            @Override
            public DenominatorRef fromString(String s) {
                return null;
            }
        });
        // A display label only -- two roots can share the identical channel (that is
        // exactly the case this picker exists to make selectable), so the label is not
        // unique and is never what setSelectedRoot matches on; rootIndex is. The "(root N)"
        // suffix is therefore for the reader, not for the lookup: once the region, scope and
        // marker plots are all keyed on rootIndex too, two entries reading "CD45" would leave
        // the user unable to tell which of them they are currently looking at.
        rootCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer rootIndex) {
                if (rootIndex == null) return "";
                String channel = compositionCanvas.rootLabel(rootIndex);
                return rootCombo.getItems().size() > 1
                        ? channel + " (root " + (rootIndex + 1) + ")" : channel;
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        populationCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(PopulationRef population) {
                return population == null ? "" : population.label(rootCombo.getItems().size() > 1);
            }

            @Override
            public PopulationRef fromString(String s) {
                return null;
            }
        });

        scopeChoice.valueProperty().addListener((obs, old, value) -> {
            selectedScope = value;
            updateTable();
        });
        denominatorCombo.valueProperty().addListener((obs, old, value) -> {
            selectedDenominatorRef = value;
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

        exportButton.setOnAction(e -> exportCsv());

        HBox controls = new HBox(10,
                new Label("Scope:"), scopeChoice,
                new Label("Denominator:"), denominatorCombo,
                new Label("Root:"), rootCombo,
                new Label("Population:"), populationCombo,
                exportButton);
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

    /**
     * One tab: its canvas centred, its scale controls docked below. This is the simple form —
     * Task 11 later replaces the bottom region with a wrapping {@code FlowPane} that also
     * carries each plot's own Root/Population picker; that restructuring is deliberately not
     * built here, only the controls this task owns.
     */
    private static Tab plotTab(String title, PlotCanvas canvas) {
        BorderPane body = new BorderPane(canvas);
        body.setBottom(new PlotControls(canvas));
        Tab tab = new Tab(title, body);
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
     * Choose the denominator every row's {@code percentOfDenominator} is reported against,
     * by {@link DenominatorRef} rather than by {@link qupath.ext.flowpath.model.Branch} —
     * see {@link DenominatorRef}'s javadoc for why a {@code Branch} cannot survive the next
     * gating pass. Package-private: exercised directly by the pane's own FX test, the same
     * way {@code HistogramCanvas.isPositiveAt} is — see that method's comment.
     */
    void setDenominator(DenominatorRef denominator) {
        selectedDenominatorRef = denominator;
        denominatorCombo.setValue(denominator);
        updateTable();
    }

    /**
     * Choose which root gate's leaves {@link #compositionCanvas()} shows, by
     * {@link PopulationStats.Row#rootIndex()}. Package-private, exercised directly by the
     * pane's own FX test, the same way {@link #setDenominator} is.
     */
    void selectRoot(Integer rootIndex) {
        selectedRoot = rootIndex;
        rootCombo.setValue(rootIndex);
    }

    /** Choose which population {@link #regionComparisonCanvas()} and {@link #scopeComparisonCanvas()} compare. */
    void selectPopulation(PopulationRef population) {
        selectedPopulation = population;
        populationCombo.setValue(population);
    }

    /** The root gates currently offered by the picker — {@link CompositionCanvas#availableRoots()}. */
    List<Integer> rootChoices() {
        return List.copyOf(rootCombo.getItems());
    }

    /** The populations currently offered by the picker. */
    List<PopulationRef> populationChoices() {
        return List.copyOf(populationCombo.getItems());
    }

    /**
     * The denominators currently offered by the picker, leading {@code null} ("all cells")
     * included — {@link List#copyOf} would reject that null, hence the plain copy.
     */
    List<DenominatorRef> denominatorRefChoices() {
        return new ArrayList<>(denominatorCombo.getItems());
    }

    /**
     * As {@link #denominatorRefChoices()}, but rendered — the exact strings the combo shows,
     * via its own converter. Exists so a test can pin "two same-channel roots read
     * distinguishably" against what a user actually sees rather than against the refs alone.
     */
    List<String> denominatorLabels() {
        return denominatorCombo.getItems().stream()
                .map(item -> denominatorCombo.getConverter().toString(item))
                .toList();
    }

    /** The denominator currently chosen, or {@code null} for "(none)" — {@link #setDenominator}. */
    DenominatorRef selectedDenominatorRef() {
        return selectedDenominatorRef;
    }

    /** The raw {@code percentOfDenominator} behind {@link #formattedPercentOfDenominatorAt}. */
    double percentOfDenominatorAt(int rowIndex) {
        return table.getItems().get(rowIndex).percentOfDenominator();
    }

    /** The "Export CSV..." button's enabled state — {@code AnalysisState.canExport()} applied. */
    boolean exportEnabled() {
        return !exportButton.isDisabled();
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
     * The table's column titles, left to right, as a user reads them. A column built by
     * {@link #countColumn}/{@link #rootColumn} carries its title on a {@link Label} graphic
     * (so the header can hold a tooltip) rather than in {@code getText()}, so both are
     * checked here — a test asserting on {@code getText()} alone would silently see "".
     */
    List<String> columnTitles() {
        return table.getColumns().stream()
                .map(c -> {
                    if (c.getText() != null && !c.getText().isEmpty()) return c.getText();
                    return c.getGraphic() instanceof Label label ? label.getText() : "";
                })
                .toList();
    }

    /**
     * What one row shows in the named column — the rendered text a user would read, taken
     * from the column's own cell-value factory rather than recomputed here, so a test
     * cannot pass while the table shows something else.
     */
    String cellTextAt(int rowIndex, String columnTitle) {
        List<String> titles = columnTitles();
        int col = titles.indexOf(columnTitle);
        if (col < 0) throw new IllegalArgumentException(
                "no column titled '" + columnTitle + "'; have " + titles);
        Object value = table.getColumns().get(col).getCellObservableValue(rowIndex).getValue();
        return value == null ? "" : value.toString();
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

        // The one place canExport() is applied. It is a derived fact about the session
        // (AnalysisState), not a judgement this pane re-makes -- the same rule the scope and
        // denominator lists follow above.
        exportButton.setDisable(!state.canExport());

        List<PopulationStats.Scope> scopes = state.availableScopes();
        scopeChoice.getItems().setAll(scopes);
        if (selectedScope == null || !scopes.contains(selectedScope)) {
            selectedScope = scopes.isEmpty() ? null : scopes.get(0);
        }
        scopeChoice.setValue(selectedScope);

        // A leading null is the "(none)" the converter above renders, i.e. "report every
        // population against the whole scope". Without it the converter's null branch was
        // unreachable and the choice was one-way: once a user picked a denominator there
        // was no item in the list that could take them back off it.
        List<AnalysisSession.DenominatorOption> options = session.denominatorOptions();
        List<DenominatorRef> items = new ArrayList<>();
        items.add(null);
        for (AnalysisSession.DenominatorOption option : options) {
            items.add(option.ref());
        }
        denominatorCombo.getItems().setAll(items);
        // THE FIX. FlowPathPane.buildAnalysisInput() deep-copies the gate tree on every
        // push, so the tree behind session.denominatorOptions() above is never the one
        // selectedDenominatorRef was chosen from -- but the ref is a VALUE
        // ((rootIndex, path)), and session.resolveDenominator re-finds the live Branch that
        // value still names in the new tree. Clearing the selection is therefore reserved
        // for the one case that actually means "gone": resolveDenominator returns null only
        // when no branch in the CURRENT tree carries that (rootIndex, path) any more --
        // its gate was disabled, deleted, or renamed since the last accepted pass. A plain
        // "was it deep-copied" test (the bug this replaces) cleared the selection on every
        // single pass, since a deep copy always mints fresh Branch objects; this clears it
        // only when the population itself is gone.
        if (selectedDenominatorRef != null && session.resolveDenominator(selectedDenominatorRef) == null) {
            selectedDenominatorRef = null;
        }
        denominatorCombo.setValue(selectedDenominatorRef);

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
        PopulationStats stats = session.stats(session.resolveDenominator(selectedDenominatorRef));
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
        List<Integer> roots = compositionCanvas.availableRoots();
        rootCombo.getItems().setAll(roots);
        if (selectedRoot == null || !roots.contains(selectedRoot)) {
            selectedRoot = roots.isEmpty() ? null : roots.get(0);
        }
        rootCombo.setValue(selectedRoot);

        List<PopulationRef> populations = scopeComparisonCanvas.availablePopulations();
        populationCombo.getItems().setAll(populations);
        if (selectedPopulation == null || !populations.contains(selectedPopulation)) {
            selectedPopulation = populations.isEmpty() ? null : populations.get(0);
        }
        populationCombo.setValue(selectedPopulation);
    }

    private void buildColumns() {
        table.getColumns().setAll(List.of(
                // Root, then Population. Two un-renamed roots on one channel emit
                // byte-identical paths (GateNode names its branches from the channel
                // alone), so a Population column on its own showed four rows all reading
                // "CD45+"/"CD45-" with no way to tell which root each belonged to -- the
                // same collision PopulationRef exists to resolve, and which the root and
                // population *pickers* already spell out with a "(root N)" suffix. The
                // one-based number matches those pickers exactly; root_index in the CSV is
                // zero-based, as PopulationStatsExporter documents.
                rootColumn(),
                column("Population", PopulationStats.Row::path),
                column("Region", row -> row.regionName() == null ? "" : row.regionName()),
                // What Count includes depends on the scope, and saying otherwise was wrong
                // rather than merely vague: at the two annotation scopes the number comes
                // from BranchTally's per-region arrays, which are only incremented for a
                // cell with a region, and RegionMask gives every ROI-excluded cell a region
                // of -1. So ROI-excluded cells are in Count at WHOLE_SLIDE and absent from
                // it per region -- which also changes what the Count/Clean gap means.
                countColumn("Count", PopulationStats.Row::count,
                        "Every cell that landed in this population, including cells the "
                                + "quality filter excluded from the view.\n"
                                + "At Whole slide this also includes cells outside the "
                                + "annotation ROI filter; at the per-region scopes those "
                                + "cells belong to no region and are not counted at all, so "
                                + "there the gap to Clean is quality filtering alone."),
                countColumn("Clean", PopulationStats.Row::cleanCount,
                        "Cells in this population that were not excluded: not quality-filtered, "
                                + "not outlier-clipped and, when the annotation ROI filter is on, "
                                + "inside the annotations being filtered by. This is the number "
                                + "the gate tree shows."),
                column("% Parent", row -> formatPercent(row.percentOfParent())),
                column("% Total", row -> formatPercent(row.percentOfTotal())),
                column("% of Denominator", row -> formatPercent(row.percentOfDenominator())),
                column("Density", row -> formatDensity(row.densityPerMm2()))));
    }

    /**
     * A text column.
     * <p>
     * Explicitly <b>not sortable</b>. A JavaFX column sorts by its cell value type, so a
     * String column of numbers orders them lexicographically — one click on "% Parent"
     * used to put 100.0 above 20.0 above 9.5. {@link #countColumn} exists precisely to
     * avoid that for the counts; the percentage and density columns are formatted strings
     * and cannot be fixed the same way without carrying the raw double alongside, so they
     * simply do not offer a sort rather than offering a wrong one.
     */
    private static TableColumn<PopulationStats.Row, String> column(
            String title, Function<PopulationStats.Row, String> extractor) {
        TableColumn<PopulationStats.Row, String> col = new TableColumn<>(title);
        col.setCellValueFactory(data -> new SimpleStringProperty(extractor.apply(data.getValue())));
        col.setSortable(false);
        return col;
    }

    /**
     * Which root gate the row descends from, one-based to match the root and population
     * pickers. Typed {@link Number} so it sorts numerically and so it reads as an index
     * rather than a label.
     */
    private static TableColumn<PopulationStats.Row, Number> rootColumn() {
        TableColumn<PopulationStats.Row, Number> col = new TableColumn<>();
        Label header = new Label("Root");
        header.setTooltip(new Tooltip(
                "Which enabled root gate this population descends from, numbered from 1 in "
                + "tree order.\nTwo roots on the same channel produce identically named "
                + "populations, and this is the only thing that tells them apart."));
        col.setGraphic(header);
        col.setCellValueFactory(data ->
                new SimpleIntegerProperty(data.getValue().rootIndex() + 1));
        return col;
    }

    /**
     * A cell-count column. Typed {@link Number}, not {@link String}: a string column sorts
     * lexicographically, so one click on the header of a table of counts puts 100 above 20.
     * <p>
     * The header carries a tooltip because neither "Count" nor "Clean" says what it excludes,
     * and the two differ by exactly that — see {@link PopulationStats.Row#cleanCount()},
     * whose definition also folds in annotation membership when the ROI filter is on.
     */
    private static TableColumn<PopulationStats.Row, Number> countColumn(
            String title, ToIntFunction<PopulationStats.Row> extractor, String tooltip) {
        TableColumn<PopulationStats.Row, Number> col = new TableColumn<>();
        Label header = new Label(title);
        header.setTooltip(new Tooltip(tooltip));
        col.setGraphic(header);
        col.setCellValueFactory(data ->
                new SimpleIntegerProperty(extractor.applyAsInt(data.getValue())));
        return col;
    }

    /**
     * Write the table exactly as shown — every scope, every region, against the denominator
     * currently chosen — to a CSV the user picks.
     * <p>
     * Mirrors {@code FlowPathPane.exportCsv()}: prompt, write, notify, and report a failure
     * as a dialog rather than only to the log. The file holds
     * {@link PopulationStats#rows()} in full rather than the one scope the table happens to
     * be showing, because a report that silently dropped two of its three scopes on the way
     * to disk would be the more surprising of the two behaviours.
     */
    private void exportCsv() {
        File file = Dialogs.promptToSaveFile("Export Population Statistics", null,
                "population_stats.csv", "CSV", ".csv");
        if (file == null) return;
        try {
            PopulationStatsExporter.export(file, session.stats(session.resolveDenominator(selectedDenominatorRef)));
            Dialogs.showInfoNotification("FlowPath", "Exported " + file.getName());
        } catch (IOException | RuntimeException ex) {
            Dialogs.showErrorMessage("Export Error", ex.getMessage());
        }
    }

    /**
     * {@code NaN} renders as an empty cell, every real value as a one-decimal percentage.
     * <p>
     * Note which questions that leaves indistinguishable in the {@code % of Denominator}
     * column, because an earlier version of this comment claimed otherwise: <em>both</em>
     * "no denominator was chosen" and "the chosen denominator holds no cells" are
     * {@code NaN} by {@link PopulationStats.Row#percentOfDenominator()}'s own definition,
     * and both therefore render blank here. That is deliberate — neither is a question with
     * a numeric answer, and rendering the second as {@code 0.0} would state a share of
     * nothing as though it were measured. A reader who needs to tell them apart reads
     * {@link PopulationStats.Row#denominatorCount()}, which is 0 only in the second case.
     * {@code % Parent} and {@code % Total} are different: {@link PopulationStats#percent}
     * returns a real {@code 0} for an empty whole, so those columns show {@code 0.0}.
     */
    private static String formatPercent(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.1f", value);
    }

    /** {@code NaN} (no area known for this row) renders as an empty cell, not "NaN". */
    private static String formatDensity(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.1f", value);
    }
}
