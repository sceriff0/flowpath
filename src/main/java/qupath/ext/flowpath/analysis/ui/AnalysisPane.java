package qupath.ext.flowpath.analysis.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.util.StringConverter;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.session.AnalysisState;
import qupath.ext.flowpath.analysis.session.DenominatorRef;
import qupath.ext.flowpath.io.PlotDataCsvExporter;
import qupath.ext.flowpath.io.PlotImageExporter;
import qupath.ext.flowpath.io.PopulationStatsExporter;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.lib.gui.dialogs.Dialogs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
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

    private static final KeyCombination COPY_COMBO =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);

    /**
     * The snapshot scale every plot image export (clipboard and PNG) renders at, so a pasted
     * or written figure is not soft. A snapshot parameter only — see
     * {@link PlotImageExporter}'s own class javadoc for why this never touches a canvas's
     * layout.
     */
    private static final double EXPORT_SCALE = 2.0;

    private final AnalysisSession session;

    private final ChoiceBox<PopulationStats.Scope> scopeChoice = new ChoiceBox<>();
    private final ComboBox<DenominatorRef> denominatorCombo = new ComboBox<>();
    private final ComboBox<Integer> rootCombo = new ComboBox<>();
    // Two ComboBox INSTANCES, one selection. The By Region and By Scope tabs each need their
    // own picker -- Task 11 moves Population off the table's control row and onto the tabs it
    // actually drives, and a shared row above two tabs is not an option once each tab has its
    // own bottom FlowPane -- but "which population" is one fact, not two: see
    // applyPopulationSelection() for how the pair stays in lockstep without looping.
    private final ComboBox<PopulationRef> populationCombo = new ComboBox<>();
    private final ComboBox<PopulationRef> scopePopulationCombo = new ComboBox<>();
    private final TextField filterField = new TextField();
    // The "Export ▾" control (Task 12), replacing the old plain "Export CSV..." button.
    // Items 1-3 act on the plot in the tab plotTabs currently has selected; item 5 is the
    // pre-existing full-table export, unchanged in behaviour -- see exportCsv() below. A
    // CustomMenuItem carries item 5 rather than a plain MenuItem because MenuItem itself has
    // no tooltip property in this JavaFX version; wrapping a Label (which does) as its content
    // is the same Label+Tooltip pattern the table's own column headers already use below.
    private final MenuItem copyPlotItem = new MenuItem("Copy plot to clipboard");
    private final MenuItem plotImageItem = new MenuItem("Plot as image…");
    private final MenuItem plotDataCsvItem = new MenuItem("Plot data as CSV…");
    private final CustomMenuItem exportTableItem = buildExportTableItem();
    private final MenuButton exportMenu = new MenuButton("Export ▾");
    private final TableView<PopulationStats.Row> table = new TableView<>();
    private final Label placeholderLabel = new Label();
    private final Label summaryLabel = new Label();
    // The table's own control row -- Scope, Denominator, the filter field, and (Task 12) the
    // export control. Kept as a field, not a local built once, so tableControlLabels() can read
    // it back; a FlowPane rather than the old HBox so it wraps instead of clipping at the 720px
    // minimum stage width.
    private final FlowPane tableControls = new FlowPane();

    // The table's items are this fixed FilteredList-over-SortedList pipeline, built once and
    // never replaced by updateTable() -- only backingRows' CONTENT changes on a push. Rebuilding
    // table.setItems() on every push is what would strand a mid-scroll, mid-sort user: a fresh
    // list reference resets the TableView's virtual flow and its sort comparator binding. See
    // effectiveComparator() for why a plain bind to table.comparatorProperty() is not enough on
    // its own to keep NaN rows pinned to the end in both sort directions.
    private final ObservableList<PopulationStats.Row> backingRows = FXCollections.observableArrayList();
    private final FilteredList<PopulationStats.Row> filteredRows = new FilteredList<>(backingRows, r -> true);
    private final SortedList<PopulationStats.Row> sortedRows = new SortedList<>(filteredRows);

    // Parallel to table.getColumns(): renderer(i) is what copySelectionAsTsv() calls for
    // column i. Each renderer is produced by the SAME column-building call (column()/
    // countColumn()/rootColumn()/numberColumn(), see ColumnSpec) that builds the column's own
    // cell-value factory and cell factory, closed over the identical extractor/formatter
    // arguments -- so a copied cell reading something the screen does not would require
    // editing one of those factories without editing the call that builds both, not merely
    // forgetting to update a second call site.
    private List<Function<PopulationStats.Row, String>> rowRenderers = List.of();

    private final CompositionCanvas compositionCanvas = new CompositionCanvas();
    private final RegionComparisonCanvas regionComparisonCanvas = new RegionComparisonCanvas();
    private final ScopeComparisonCanvas scopeComparisonCanvas = new ScopeComparisonCanvas();
    private final MarkerPositivityCanvas markerPositivityCanvas = new MarkerPositivityCanvas();

    // Parallel to plotTabs.getTabs() -- tab i's own picker/canvas is plotCanvases.get(i).
    // Built from the four fields just above, in the same order plotTab() adds their tabs
    // below, so "the plot in the currently selected tab" (see currentPlotCanvas()) is always
    // this list indexed by plotTabs' own selected index, never a second lookup that could
    // drift out of step with tab order.
    private final List<PlotCanvas> plotCanvases = List.of(
            compositionCanvas, regionComparisonCanvas, scopeComparisonCanvas, markerPositivityCanvas);

    // A field rather than a constructor-local, so the Export menu's tab-selection listener
    // and currentPlotCanvas()/currentPlotTitle() can all reach it after construction.
    private final TabPane plotTabs;

    private PopulationStats.Scope selectedScope;
    private DenominatorRef selectedDenominatorRef;
    private Integer selectedRoot;
    private PopulationRef selectedPopulation;

    // Set for the duration of refresh()'s own scopeChoice/denominatorCombo assignments, so the
    // listeners those assignments fire do not each trigger their own updateTable() -- refresh()
    // calls it exactly once itself, after the guard is lifted. Without this, one accepted pass
    // rebuilt PopulationStats three times (scope listener, denominator listener, then refresh()'s
    // own call), and did so on every live-preview push.
    private boolean refreshing;

    // Set for the duration of applyPopulationSelection()'s own setValue calls on BOTH population
    // combos, so writing the second combo does not re-enter this method through ITS OWN listener
    // -- the same guard shape `refreshing` uses above, applied to the two-combos-one-selection
    // problem instead of the scope/denominator one. Without it, populationCombo's listener would
    // call applyPopulationSelection(), which sets scopePopulationCombo's value, which fires that
    // combo's own listener, which calls applyPopulationSelection() again -- reading the correct
    // value each time, so it would not diverge, but it would run the canvas updates and the
    // opposite combo's setValue twice per user click, and a future edit to this method that is
    // NOT idempotent (e.g. one that mutates something rather than just re-asserting the same
    // value) would then double-apply silently.
    private boolean updatingPopulationSelection;

    public AnalysisPane(AnalysisSession session) {
        this.session = Objects.requireNonNull(session, "session");

        table.setPlaceholder(placeholderLabel);
        buildColumns();
        configureTableItems();
        configureSelectionAndCopy();

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
        // Both Population combos render identically -- they are two views of one selection
        // (see applyPopulationSelection()), so they share this converter's logic rather than
        // each carrying its own copy that could drift out of sync with the other's rendering.
        populationCombo.setConverter(populationConverter());
        scopePopulationCombo.setConverter(populationConverter());

        scopeChoice.valueProperty().addListener((obs, old, value) -> {
            selectedScope = value;
            if (!refreshing) updateTable();
        });
        denominatorCombo.valueProperty().addListener((obs, old, value) -> {
            selectedDenominatorRef = value;
            if (!refreshing) updateTable();
        });
        // The root/population pickers only forward the user's choice to the one canvas
        // each drives -- deciding which root or population is "interesting" is not this
        // pane's job, so it never re-derives a default here beyond the same "fall back to
        // the first item" a ChoiceBox already does for scope/denominator above.
        rootCombo.valueProperty().addListener((obs, old, value) -> {
            selectedRoot = value;
            compositionCanvas.setSelectedRoot(value);
        });
        // Both Population combos share ONE listener shape: whichever fires forwards straight to
        // applyPopulationSelection(), which is also what selectPopulation() calls. That is
        // deliberate -- a user driving populationCombo directly, a user driving
        // scopePopulationCombo directly, and a caller going through selectPopulation() are the
        // same event as far as this pane is concerned, and routing all three through one method
        // is what keeps "reach both canvases and the other combo" a single fact instead of two
        // (or three) hand-synchronised copies of it.
        populationCombo.valueProperty().addListener((obs, old, value) -> {
            if (!updatingPopulationSelection) applyPopulationSelection(value);
        });
        scopePopulationCombo.valueProperty().addListener((obs, old, value) -> {
            if (!updatingPopulationSelection) applyPopulationSelection(value);
        });

        // The Export menu's action wiring; the actual writing logic lives in
        // PlotDataCsvExporter/PlotImageExporter/PopulationStatsExporter, never here -- see
        // each method's own javadoc.
        copyPlotItem.setOnAction(e -> copyCurrentPlotToClipboard());
        plotImageItem.setOnAction(e -> exportCurrentPlotAsImage());
        plotDataCsvItem.setOnAction(e -> exportCurrentPlotDataAsCsv());
        exportTableItem.setOnAction(e -> exportCsv());
        exportMenu.getItems().addAll(
                copyPlotItem, plotImageItem, plotDataCsvItem, new SeparatorMenuItem(), exportTableItem);

        filterField.setPromptText("Filter populations…");
        filterField.setPrefWidth(180);
        filterField.textProperty().addListener((obs, old, text) -> applyFilter(text));

        summaryLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11; -fx-padding: 4 8 4 8;");

        // Only the two controls that drive the TABLE live here, plus the filter -- Root and
        // Population each drive exactly one plot tab and live there instead (see plotTab()
        // below), which is the whole point of this task: a picker that visibly does nothing on
        // the tab a user is looking at is the single worst intuitiveness problem in this window.
        // A FlowPane, not the old HBox, so this row wraps rather than clipping off the right
        // edge at the 720px minimum stage width; hgap/vgap/padding match the brief exactly so a
        // reflow looks intentional rather than merely "whatever wrapped".
        tableControls.setHgap(10);
        tableControls.setVgap(6);
        tableControls.setPadding(new Insets(8));
        tableControls.getChildren().addAll(
                new Label("Scope:"), scopeChoice,
                new Label("Denominator:"), denominatorCombo,
                filterField,
                exportMenu);

        plotTabs = new TabPane(
                plotTab("Composition", compositionCanvas, new Label("Root:"), rootCombo),
                plotTab("By Region", regionComparisonCanvas, new Label("Population:"), populationCombo),
                plotTab("By Scope", scopeComparisonCanvas, new Label("Population:"), scopePopulationCombo),
                plotTab("Marker Positivity", markerPositivityCanvas,
                        new Label("All single-marker gates, whole slide")));
        plotTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // Items 1-3 act on whichever tab is selected -- see currentPlotCanvas() -- so a tab
        // switch alone (no new data) must also re-evaluate whether they have anything to act
        // on. The push path handles the other half, in setAllPlotRows().
        plotTabs.getSelectionModel().selectedIndexProperty()
                .addListener((obs, old, index) -> updateExportMenuState());

        SplitPane body = new SplitPane(table, plotTabs);
        body.setOrientation(Orientation.VERTICAL);
        body.setDividerPositions(0.45);

        BorderPane top = new BorderPane();
        top.setTop(tableControls);
        top.setCenter(summaryLabel);

        setTop(top);
        setCenter(body);

        refresh();
    }

    /**
     * One tab: its canvas centred, and at the bottom a wrapping {@code FlowPane} holding this
     * plot's own picker(s) — {@code leadingControls}, shown before everything else — followed
     * by the Task 6 {@link PlotControls} every tab still carries. Marker Positivity passes a
     * bare {@link Label} instead of a picker: it obeys no picker at all, and saying so in the
     * tab itself is what stops it reading as unresponsive the way Root and Population used to
     * on the tabs they did not drive.
     * <p>
     * An instance method, not the static helper this used to be, because {@code leadingControls}
     * is built from this pane's own combo fields (whose converters close over {@code
     * compositionCanvas} and {@code rootCombo}) rather than from anything the method could be
     * handed as plain arguments alone.
     */
    private Tab plotTab(String title, PlotCanvas canvas, Node... leadingControls) {
        BorderPane body = new BorderPane(canvas);
        FlowPane bottom = new FlowPane(10, 6, leadingControls);
        bottom.setPadding(new Insets(8));
        bottom.getChildren().add(new PlotControls(canvas));
        body.setBottom(bottom);
        Tab tab = new Tab(title, body);
        tab.setClosable(false);
        return tab;
    }

    /**
     * The one {@link StringConverter} both Population combos render with — see the field
     * comments on {@link #populationCombo}/{@link #scopePopulationCombo} for why there are two
     * combos sharing one selection; this is what keeps them rendering it identically too.
     */
    private StringConverter<PopulationRef> populationConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(PopulationRef population) {
                return population == null ? "" : population.label(rootCombo.getItems().size() > 1);
            }

            @Override
            public PopulationRef fromString(String s) {
                return null;
            }
        };
    }

    /**
     * The one place a new Population selection is applied — reached from
     * {@link #selectPopulation}, from either combo's own listener, and from
     * {@link #setAllPlotRows}'s post-push reconciliation. Updates {@link #selectedPopulation},
     * pushes it into both comparison canvases, and mirrors it onto whichever combo did not
     * just fire, guarded by {@link #updatingPopulationSelection} so that mirrored {@code
     * setValue} call cannot re-enter this method through the other combo's own listener — the
     * same shape {@link #refresh}'s {@code refreshing} guard uses for scope/denominator.
     * <p>
     * A user driving {@code populationCombo} directly, a user driving {@code
     * scopePopulationCombo} directly, and a caller going through {@link #selectPopulation} all
     * end up here, so "switching tabs never changes which population is being compared" holds
     * regardless of which of the three a caller used to make the choice.
     */
    private void applyPopulationSelection(PopulationRef population) {
        selectedPopulation = population;
        updatingPopulationSelection = true;
        try {
            populationCombo.setValue(population);
            scopePopulationCombo.setValue(population);
        } finally {
            updatingPopulationSelection = false;
        }
        regionComparisonCanvas.setSelectedPopulation(population);
        scopeComparisonCanvas.setSelectedPopulation(population);
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

    /**
     * Choose which population {@link #regionComparisonCanvas()} and {@link #scopeComparisonCanvas()}
     * compare. Goes through {@link #applyPopulationSelection}, the same path either combo's own
     * listener uses, so a test driving this method and a user driving a combo directly are
     * exercising identical behaviour.
     */
    void selectPopulation(PopulationRef population) {
        applyPopulationSelection(population);
    }

    /**
     * Choose which {@link PopulationStats.Scope} the table reports. Package-private, exercised
     * directly by the pane's own FX test, the same way {@link #setDenominator} is — needed to
     * reach {@link PopulationStats.Scope#ANNOTATION_K} without a real annotation selection in
     * the viewer, since {@code scopeChoice} otherwise only ever offers what
     * {@link AnalysisState#availableScopes()} lists and defaults to its first entry.
     */
    void selectScope(PopulationStats.Scope scope) {
        selectedScope = scope;
        scopeChoice.setValue(scope);
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
     * The {@link Label#getText()} of every {@link Label} in the table's own control row, in
     * row order — what pins Task 11's fix: "Scope:" and "Denominator:" (and the filter field,
     * which carries no label) belong here because they drive the table; "Root:" and
     * "Population:" must never appear here, because each drives exactly one plot tab and lives
     * on that tab's own {@link FlowPane} instead (see {@link #plotTab}).
     */
    List<String> tableControlLabels() {
        return tableControls.getChildren().stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .toList();
    }

    /**
     * The Export menu's item labels, in order — {@code ""} for the separator. Pins Task 12's
     * exact menu order and wording: "Copy plot to clipboard", "Plot as image…", "Plot data as
     * CSV…", the separator, "Population table as CSV…".
     */
    List<String> exportMenuLabels() {
        return exportMenu.getItems().stream().map(AnalysisPane::exportMenuItemLabel).toList();
    }

    private static String exportMenuItemLabel(MenuItem item) {
        if (item instanceof SeparatorMenuItem) return "";
        // exportTableItem is a CustomMenuItem whose text lives on its Label content (see
        // buildExportTableItem()), never on MenuItem.getText() itself -- reading that instead
        // would silently see "" for item 5 rather than throw, which is exactly the kind of
        // test that passes without testing anything CLAUDE.md warns this task against.
        if (item instanceof CustomMenuItem custom && custom.getContent() instanceof Label label) {
            return label.getText();
        }
        return item.getText();
    }

    /**
     * Whether Export menu item {@code i} (0-based, {@link #exportMenuLabels()} order) is
     * currently disabled — what pins "items 1-3 disable when the selected tab's plot has no
     * data" and "item 5 stays bound to {@code AnalysisState.canExport()}".
     */
    boolean exportMenuItemDisabled(int i) {
        return exportMenu.getItems().get(i).isDisable();
    }

    /**
     * Select the plot tab at {@code index} (Composition=0, By Region=1, By Scope=2, Marker
     * Positivity=3) — lets a test drive "items 1-3 act on the plot in the CURRENTLY SELECTED
     * tab" without a real click, the same reason {@link #selectRoot}/{@link
     * #selectPopulation}/{@link #selectScope} exist above.
     */
    void selectPlotTab(int index) {
        plotTabs.getSelectionModel().select(index);
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

    /**
     * Item 5's ("Population table as CSV…") enabled state — {@code AnalysisState.canExport()}
     * applied.
     */
    boolean exportEnabled() {
        return !exportTableItem.isDisable();
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

    /**
     * The summary line's current text — what {@link #summaryLabel} shows above the table.
     * Package-private, exercised directly by the pane's own FX test, the same way
     * {@link #placeholderText()} is.
     */
    String summaryText() {
        return summaryLabel.getText();
    }

    /** Rows currently shown, for the scope, denominator and filter in effect. */
    int rowCount() {
        return table.getItems().size();
    }

    /**
     * The {@code path} of every row currently shown, in table order — i.e. after the filter
     * and any sort have been applied. What {@link #setFilter} narrows.
     */
    List<String> visibleRowPaths() {
        return table.getItems().stream().map(PopulationStats.Row::path).toList();
    }

    /** {@code percentOfParent} of every row currently shown, in table order. */
    List<Double> visiblePercentOfParent() {
        return table.getItems().stream().map(PopulationStats.Row::percentOfParent).toList();
    }

    /** {@code percentOfDenominator} of every row currently shown, in table order. */
    List<Double> visiblePercentOfDenominator() {
        return table.getItems().stream().map(PopulationStats.Row::percentOfDenominator).toList();
    }

    /**
     * {@code areaMm2} of every row currently shown, in table order. Unlike
     * {@link #visiblePercentOfDenominator()}, this column can hold a genuine mix of real and
     * {@code NaN} values within one scope — see
     * {@code AnalysisFixtures.partiallyKnownRegionAreasInput()} — which is why it is what pins
     * the NaN-sorts-last behaviour rather than a column that is all-or-nothing.
     */
    List<Double> visibleAreaMm2() {
        return table.getItems().stream().map(PopulationStats.Row::areaMm2).toList();
    }

    /**
     * Case-insensitive filter on {@code path} and {@code regionName}, applied to a
     * {@link FilteredList} over the backing row list — never by asking
     * {@link AnalysisSession} for a narrower {@link PopulationStats}. Filtering which
     * populations are visible is a view concern; recomputing the model to hide rows would be
     * a second answer to "what are the populations", which is {@code AnalysisSession}'s alone
     * to give.
     */
    void setFilter(String text) {
        filterField.setText(text == null ? "" : text);
    }

    /**
     * Whether the named column offers a numeric sort. {@code Population} and {@code Region}
     * never do; every percentage, density and area column does, as of this task.
     */
    boolean isColumnSortable(String title) {
        return columnAt(title).isSortable();
    }

    /**
     * Sort the table by the named column, exactly as clicking its header would — used by
     * tests, which cannot click a header that may not be laid out. {@code ascending} maps to
     * {@link TableColumn.SortType#ASCENDING}/{@code DESCENDING}.
     * <p>
     * {@code table.sort()} is what actually rebuilds {@link TableView#comparatorProperty()}
     * from {@link TableView#getSortOrder()} and fires the {@code SortEvent} — mutating
     * {@code getSortOrder()} alone does not, so this calls it explicitly rather than relying on
     * a listener that does not exist. See {@link #configureTableItems()} for why the sort
     * policy is overridden so that call cannot revert what it just computed.
     */
    void sortBy(String title, boolean ascending) {
        TableColumn<PopulationStats.Row, ?> column = columnAt(title);
        column.setSortType(ascending ? TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
        table.getSortOrder().setAll(column);
        table.sort();
    }

    /** Select the row at {@code index} in the table's current (filtered, sorted) order. */
    void selectRow(int index) {
        table.getSelectionModel().clearSelection();
        table.getSelectionModel().select(index);
    }

    /** The population currently selected in the table, or {@code null} with no selection. */
    PopulationRef selectedRowRef() {
        PopulationStats.Row row = table.getSelectionModel().getSelectedItem();
        return row == null ? null : PopulationRef.of(row);
    }

    /**
     * The selected rows as tab-separated text with a header line, in {@link Locale#US} — the
     * same locale every other export in this codebase uses for decimal formatting. Every cell
     * comes from {@link #rowRenderers}, built alongside the table's own cell-value/cell
     * factories by the same {@link ColumnSpec}-returning call (see {@link #numberColumn}) —
     * that closure, not a separately-written one, is what a copied value reads.
     */
    String copySelectionAsTsv() {
        List<String> titles = columnTitles();
        StringBuilder sb = new StringBuilder(String.join("\t", titles));
        for (PopulationStats.Row row : table.getSelectionModel().getSelectedItems()) {
            List<String> cells = new ArrayList<>(rowRenderers.size());
            for (Function<PopulationStats.Row, String> renderer : rowRenderers) {
                cells.add(renderer.apply(row));
            }
            sb.append('\n').append(String.join("\t", cells));
        }
        return sb.toString();
    }

    /**
     * The table's column titles, left to right, as a user reads them. A column built by
     * {@link #countColumn}/{@link #rootColumn}/{@link #numberColumn} carries its title on a
     * {@link Label} graphic (so the header can hold a tooltip) rather than in {@code
     * getText()}, so both are checked here — a test asserting on {@code getText()} alone would
     * silently see "".
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
     * <p>
     * For a numeric column this is the raw cell <em>value</em>'s {@code toString()}, not its
     * formatted rendering — {@code Integer}-valued columns (Root, Count, Clean) render
     * identically either way, but a percentage/density/area column's formatted text is only
     * available through {@link #copySelectionAsTsv} or a column-specific accessor such as
     * {@link #formattedPercentOfDenominatorAt}, which read the same {@link #rowRenderers} the
     * cell factory itself uses.
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

    private TableColumn<PopulationStats.Row, ?> columnAt(String title) {
        List<String> titles = columnTitles();
        int idx = titles.indexOf(title);
        if (idx < 0) throw new IllegalArgumentException("no column titled '" + title + "'; have " + titles);
        return table.getColumns().get(idx);
    }

    /**
     * Wire the table's items to the fixed filter/sort pipeline described on
     * {@link #backingRows}, plus the resize policy every column benefits from.
     */
    private void configureTableItems() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // A plain `sortedRows.comparatorProperty().bind(table.comparatorProperty())` is the
        // textbook TableView/SortedList wiring, but it hands the NaN-ordering question to
        // whatever table.getComparator() produces -- which, for a DESCENDING sort, is built by
        // reversing the column's own comparator, so a NaN placed last by an ascending compare
        // would move to the FRONT under descending. effectiveComparator() reads the same
        // dependencies but forces the primary sorted column's NaN rows to the end regardless
        // of direction, by comparing that one column's raw value directly rather than trusting
        // the reversed comparator to already do it.
        sortedRows.comparatorProperty().bind(Bindings.createObjectBinding(
                this::effectiveComparator, table.comparatorProperty(), table.getSortOrder()));
        table.setItems(sortedRows);
        // TableView.DEFAULT_SORT_POLICY, when items is a SortedList, only succeeds if that
        // list's OWN comparator is the exact same object as table.getComparator() -- the
        // "sortedList.comparatorProperty().bind(tableView.comparatorProperty())" idiom its own
        // javadoc recommends. effectiveComparator() deliberately returns a WRAPPING comparator
        // instead (the one that pins NaN to the end), so that identity check fails, the policy
        // returns false, and table.sort() silently reverts table.comparatorProperty() back to
        // whatever it was -- undoing every click before it visibly did anything. A policy that
        // always succeeds is correct here because the actual reordering already happens
        // reactively, purely from sortedRows' own bound comparatorProperty; there is nothing
        // left for a sort policy to do.
        table.setSortPolicy(tv -> true);
    }

    private Comparator<PopulationStats.Row> effectiveComparator() {
        Comparator<PopulationStats.Row> base = table.getComparator();
        List<TableColumn<PopulationStats.Row, ?>> order = table.getSortOrder();
        if (base == null || order.isEmpty()) return base;
        TableColumn<PopulationStats.Row, ?> primary = order.get(0);
        return (r1, r2) -> {
            boolean n1 = isNaNValue(primary.getCellObservableValue(r1).getValue());
            boolean n2 = isNaNValue(primary.getCellObservableValue(r2).getValue());
            if (n1 && n2) return 0;
            if (n1) return 1;
            if (n2) return -1;
            return base.compare(r1, r2);
        };
    }

    private static boolean isNaNValue(Object value) {
        return value instanceof Number number && Double.isNaN(number.doubleValue());
    }

    private void configureSelectionAndCopy() {
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        table.setOnKeyPressed(event -> {
            if (COPY_COMBO.match(event)) {
                copySelectionToClipboard();
                event.consume();
            }
        });
        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(e -> copySelectionToClipboard());
        table.setContextMenu(new ContextMenu(copyItem));
    }

    private void copySelectionToClipboard() {
        if (table.getSelectionModel().getSelectedItems().isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(copySelectionAsTsv());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void applyFilter(String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.US);
        filteredRows.setPredicate(needle.isEmpty() ? row -> true : row ->
                row.path().toLowerCase(Locale.US).contains(needle)
                        || (row.regionName() != null && row.regionName().toLowerCase(Locale.US).contains(needle)));
        updatePlaceholder();
        updateSummary();
    }

    private void refresh() {
        Double scrollPosition = captureScrollPosition();
        PopulationRef previousSelection = selectedRowRef();

        refreshing = true;
        try {
            AnalysisState state = session.state();

            // The one place canExport() is applied. It is a derived fact about the session
            // (AnalysisState), not a judgement this pane re-makes -- the same rule the scope and
            // denominator lists follow above.
            exportTableItem.setDisable(!state.canExport());

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
        } finally {
            refreshing = false;
        }

        // Exactly one rebuild per push -- the scopeChoice/denominatorCombo assignments above
        // fired their listeners while refreshing was true, so this is the only updateTable()
        // call this method makes, however many of those values actually changed.
        updateTable();

        restoreSelection(previousSelection);
        restoreScrollPosition(scrollPosition);
    }

    private void updateTable() {
        AnalysisState state = session.state();
        if (!state.hasData() || selectedScope == null) {
            backingRows.clear();
            setAllPlotRows(List.of());
            updatePlaceholder();
            updateSummary();
            return;
        }
        PopulationStats stats = session.stats(session.resolveDenominator(selectedDenominatorRef));
        // backingRows is mutated in place, never replaced -- table.setItems() is called exactly
        // once, in configureTableItems(). Replacing the items list here would drop the
        // TableView's sort comparator binding and reset its scroll position on every push,
        // which is the instability this task exists to close.
        backingRows.setAll(stats.rows(selectedScope));
        // Every plot canvas is handed the full, unfiltered row set (every scope, every
        // region) and narrows to what it means on its own -- CompositionCanvas and
        // MarkerPositivityCanvas to WHOLE_SLIDE, RegionComparisonCanvas to ANNOTATION_K,
        // ScopeComparisonCanvas to none of the above, since scope is the axis it compares.
        // See each canvas's own class javadoc.
        setAllPlotRows(stats.rows());
        updatePlaceholder();
        updateSummary();
    }

    /**
     * Redraw {@link #summaryLabel} from the session's current state and {@link #backingRows} —
     * called from every place that can change what either holds: the end of
     * {@link #updateTable()} (scope, denominator, or a freshly accepted pass) and the end of
     * {@link #applyFilter} (the row count the line reports is the same
     * filtered-and-sorted count {@link #rowCount()} exposes, so a search that hides rows must
     * move the summary's "populations" number too). Deliberately not folded into
     * {@link #refresh()} alone — {@code refresh()} only calls {@code updateTable()} once per
     * push, but the filter box changes {@code table.getItems()} without going through
     * {@code updateTable()} at all, and a summary line that only refreshed on the not-taken
     * path would silently drift from the table it sits above.
     */
    private void updateSummary() {
        summaryLabel.setText(summaryText(session.state(), backingRows, rowCount()));
    }

    /**
     * Build the summary line's text from already-computed facts — never re-derives "is there
     * data" or "what scope total applies", both of which {@link AnalysisSession} and
     * {@link PopulationStats} already answer once.
     * <p>
     * {@code scopeTotal} is read off {@code scopedRows}' own depth-0 {@code parentCount}
     * rather than {@link AnalysisState#cellCount()} — {@code cellCount} is the whole pass's
     * total, fixed regardless of scope, and would silently overstate the denominator at
     * {@link PopulationStats.Scope#ANNOTATION_K}, where the true total is one region's cells.
     * Every depth-0 row of a given scope carries the same {@code parentCount} (root branches
     * are all handed the same scope total in {@link PopulationStats#of}), so the first one
     * found is exact, not a guess among roots.
     * <p>
     * {@code scopeTotal} and the populations count are deliberately asymmetric under a
     * filter, and that is not an inconsistency: {@code scopeTotal} describes the population
     * the gating pass covered, which does not change while a user types, so it stays fixed
     * at {@code scopedRows.size()}'s full set; the populations count describes what the
     * table is showing right now, so it must move with the filter — reporting the
     * unfiltered count there would have the panel claim more rows than the list beneath it
     * actually holds. Rendering it as "{@code N of M}" rather than switching silently to
     * {@code N} keeps both facts on screen: a user who has forgotten there is text in the
     * filter box can see it in the summary, not only by noticing the box itself.
     *
     * @param state       the session's current derived state
     * @param scopedRows  the rows for the scope currently selected, i.e. {@link #backingRows}
     * @param visibleRows how many of those rows the table is currently showing, i.e. after the
     *                    user's filter — {@link #rowCount()}
     */
    private static String summaryText(AnalysisState state, List<PopulationStats.Row> scopedRows,
                                      int visibleRows) {
        if (!state.hasData()) return "";
        List<String> parts = new ArrayList<>();
        if (state.imageName() != null && !state.imageName().isBlank()) {
            parts.add(state.imageName());
        }
        parts.add(String.format(Locale.US, "%,d cells", state.cellCount()));
        if (state.regionCount() > 0) {
            parts.add(String.format(Locale.US, "%,d regions", state.regionCount()));
        }
        scopedRows.stream()
                .filter(r -> r.depth() == 0)
                .mapToInt(PopulationStats.Row::parentCount)
                .findFirst()
                .ifPresent(scopeTotal ->
                        parts.add(String.format(Locale.US, "%,d in scope", scopeTotal)));
        int scopedCount = scopedRows.size();
        parts.add(visibleRows == scopedCount
                ? String.format(Locale.US, "%,d populations", visibleRows)
                : String.format(Locale.US, "%,d of %,d populations", visibleRows, scopedCount));
        return String.join(" · ", parts);
    }

    /**
     * The empty-grid placeholder, distinguishing the two reasons {@code table.getItems()} can
     * be empty while {@code AnalysisState.hasData()} is true — a case that state's own compact
     * constructor deliberately cannot express (it only knows "data" vs. "no data", not "data
     * but nothing at this scope" vs. "data but the filter hid it"), so the pane resolves it
     * instead of inventing a third {@code AnalysisState} shape for one screen's own filter box.
     */
    private void updatePlaceholder() {
        AnalysisState state = session.state();
        if (!state.hasData()) {
            placeholderLabel.setText(state.emptyMessage());
            return;
        }
        if (!filteredRows.isEmpty()) {
            return;
        }
        placeholderLabel.setText(backingRows.isEmpty()
                ? "No populations at this scope."
                : "No populations match \"" + filterField.getText() + "\".");
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

        // Both combos' item lists are rebuilt from the same availablePopulations() call, so By
        // Region and By Scope always offer the identical set of choices -- neither tab's combo
        // can drift to offering a population the other does not, which would make the "one
        // shared selection" promise meaningless for whichever population went missing from one
        // side. Both combos stay live in the scene graph regardless of which tab is currently
        // selected (TabPane does not lazily build tab content), so this keeps every tab's picker
        // current even while a user is looking at a different one.
        List<PopulationRef> populations = scopeComparisonCanvas.availablePopulations();
        populationCombo.getItems().setAll(populations);
        scopePopulationCombo.getItems().setAll(populations);
        if (selectedPopulation == null || !populations.contains(selectedPopulation)) {
            selectedPopulation = populations.isEmpty() ? null : populations.get(0);
        }
        // Routed through applyPopulationSelection(), not a bare setValue() on each combo, so
        // the reconciled choice reaches both canvases unconditionally -- applyPopulationSelection
        // pushes to regionComparisonCanvas/scopeComparisonCanvas OUTSIDE the
        // updatingPopulationSelection guard (see its own javadoc), whereas a plain setValue()
        // here would silently do nothing when the value already matched what the combo held,
        // leaving whichever canvas's own setRows() fallback (above) had picked a different
        // population unreconciled with the pane's own choice for the rest of this push.
        applyPopulationSelection(selectedPopulation);

        // Every canvas above just adopted this push's rows (or the empty list, on the no-data
        // path), so whichever plot is currently selected may have gone from "has data" to
        // empty or back -- re-evaluate items 1-3 here rather than only on a tab switch.
        updateExportMenuState();
    }

    /**
     * Re-select the row naming {@code ref}, if the current (post-rebuild) row set still has
     * one — a live push mints fresh {@link PopulationStats.Row} instances every time, so the
     * old selected object is never {@code .equals} to anything in the new list; matching on
     * {@link PopulationRef} (root + path, not row identity) is what lets the selection survive
     * a push the way the denominator choice already does (see {@link #refresh}'s own comment).
     */
    private void restoreSelection(PopulationRef ref) {
        if (ref == null) return;
        for (PopulationStats.Row row : table.getItems()) {
            if (ref.matches(row)) {
                table.getSelectionModel().select(row);
                return;
            }
        }
    }

    private Double captureScrollPosition() {
        ScrollBar bar = verticalScrollBar();
        return bar == null ? null : bar.getValue();
    }

    private void restoreScrollPosition(Double value) {
        if (value == null) return;
        ScrollBar bar = verticalScrollBar();
        if (bar != null) bar.setValue(value);
    }

    /**
     * {@code null} until the table has a skin -- i.e. until it is in a live scene and CSS has
     * been applied at least once. Every caller guards for that, since a pane built for a test
     * with no {@code Stage} never reaches that point.
     */
    private ScrollBar verticalScrollBar() {
        Node node = table.lookup(".scroll-bar:vertical");
        return node instanceof ScrollBar bar ? bar : null;
    }

    private void buildColumns() {
        List<ColumnSpec> specs = List.of(
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
                numberColumn("% Parent", PopulationStats.Row::percentOfParent, AnalysisPane::formatPercent,
                        "This branch's share of the branch directly above it (or, for a root "
                                + "branch, of the scope's whole population)."),
                numberColumn("% Parent (clean)", PopulationStats.Row::percentOfCleanParent, AnalysisPane::formatPercent,
                        "The clean counterpart of % Parent: the clean count over the clean "
                                + "parent count, so quality-filtered, outlier-clipped and (when the "
                                + "ROI filter is on) out-of-annotation cells are excluded from both "
                                + "the numerator and the denominator, not just the numerator."),
                numberColumn("% Total", PopulationStats.Row::percentOfTotal, AnalysisPane::formatPercent,
                        "This branch's share of every cell at the current scope."),
                numberColumn("% Total (clean)", PopulationStats.Row::percentOfCleanTotal, AnalysisPane::formatPercent,
                        "The clean counterpart of % Total: the clean count over the scope's "
                                + "clean total, the same clean/raw split % Parent (clean) makes."),
                numberColumn("% of Denominator", PopulationStats.Row::percentOfDenominator, AnalysisPane::formatPercent,
                        "This branch's share of the denominator chosen in the picker above. "
                                + "Blank when no denominator is chosen, and equally blank when the "
                                + "chosen denominator holds no cells -- see AnalysisPane.formatPercent."),
                numberColumn("Density", PopulationStats.Row::densityPerMm2, AnalysisPane::formatDensity,
                        "Cells per mm² of the region this row reports over. Blank without a "
                                + "known area -- see the Area column."),
                numberColumn("Area (mm²)", PopulationStats.Row::areaMm2, AnalysisPane::formatDensity,
                        "The annotated region's area. Blank when the image has no pixel "
                                + "calibration, or for the implicit whole-image region, which no "
                                + "single ROI describes."));

        table.getColumns().setAll(specs.stream().map(ColumnSpec::column).toList());
        rowRenderers = specs.stream().map(ColumnSpec::renderer).toList();
    }

    /**
     * One column and the function that renders one row's value for it, produced together by
     * {@link #column}/{@link #countColumn}/{@link #rootColumn}/{@link #numberColumn} rather
     * than assembled by a caller. Each of those factories closes {@code renderer} over the
     * <em>same</em> {@code extractor}/{@code formatter} arguments it gives the column's own
     * cell-value factory and cell factory, so there is exactly one place that says "how does
     * this column's value become text" — not two hand-written copies a caller could edit out
     * of step. An earlier version of {@link #buildColumns} paired each column with a
     * separately-written lambda at the call site; the two happened to agree only because both
     * literally re-typed the same logic, which is precisely the "second implementation kept in
     * sync by a comment" shape {@code CLAUDE.md} catalogues.
     */
    private record ColumnSpec(TableColumn<PopulationStats.Row, ?> column,
                               Function<PopulationStats.Row, String> renderer) {}

    /**
     * A text column, deliberately left unsortable — {@code Population} and {@code Region}
     * carry no numeric meaning to rank by. {@link #numberColumn} is what makes sorting
     * correct for the percentage/density/area columns below it: splitting the raw
     * {@code double} (the cell <em>value</em>) from its formatted rendering (the cell
     * <em>factory</em>) is what lets JavaFX sort the number while a reader still sees the
     * formatted string, which a single {@code String}-typed column could never do — sorting
     * "100.0" against "20.0" lexicographically is exactly the bug this task fixes.
     */
    private static ColumnSpec column(String title, Function<PopulationStats.Row, String> extractor) {
        TableColumn<PopulationStats.Row, String> col = new TableColumn<>(title);
        col.setCellValueFactory(data -> new SimpleStringProperty(extractor.apply(data.getValue())));
        col.setSortable(false);
        return new ColumnSpec(col, extractor);
    }

    /**
     * Which root gate the row descends from, one-based to match the root and population
     * pickers. Typed {@link Number} so it sorts numerically and so it reads as an index
     * rather than a label.
     */
    private static ColumnSpec rootColumn() {
        TableColumn<PopulationStats.Row, Number> col = new TableColumn<>();
        Label header = new Label("Root");
        header.setTooltip(new Tooltip(
                "Which enabled root gate this population descends from, numbered from 1 in "
                + "tree order.\nTwo roots on the same channel produce identically named "
                + "populations, and this is the only thing that tells them apart."));
        col.setGraphic(header);
        ToIntFunction<PopulationStats.Row> extractor = row -> row.rootIndex() + 1;
        col.setCellValueFactory(data -> new SimpleIntegerProperty(extractor.applyAsInt(data.getValue())));
        return new ColumnSpec(col, row -> String.valueOf(extractor.applyAsInt(row)));
    }

    /**
     * A cell-count column. Typed {@link Number}, not {@link String}: a string column sorts
     * lexicographically, so one click on the header of a table of counts puts 100 above 20.
     * <p>
     * The header carries a tooltip because neither "Count" nor "Clean" says what it excludes,
     * and the two differ by exactly that — see {@link PopulationStats.Row#cleanCount()},
     * whose definition also folds in annotation membership when the ROI filter is on.
     */
    private static ColumnSpec countColumn(
            String title, ToIntFunction<PopulationStats.Row> extractor, String tooltip) {
        TableColumn<PopulationStats.Row, Number> col = new TableColumn<>();
        Label header = new Label(title);
        header.setTooltip(new Tooltip(tooltip));
        col.setGraphic(header);
        col.setCellValueFactory(data ->
                new SimpleIntegerProperty(extractor.applyAsInt(data.getValue())));
        return new ColumnSpec(col, row -> String.valueOf(extractor.applyAsInt(row)));
    }

    /**
     * A numeric column whose cell <em>value</em> is the raw {@code double} and whose cell
     * <em>factory</em> only controls how that value is drawn — the split that makes sorting
     * correct. Every percentage, {@code Density} and {@code Area} column is built with this
     * rather than the {@link String}-typed {@link #column}, which is what those columns used
     * to be: sortable only lexicographically, i.e. not usably sortable at all, which is why
     * they previously shipped with {@code setSortable(false)}.
     * <p>
     * {@code NaN} rows are not handled here — a per-column comparator cannot see which
     * direction the table is currently sorting without also duplicating that state, so
     * "NaN always last, in both directions" is handled once, centrally, by
     * {@link #effectiveComparator()}. This column only supplies the ordinary ascending
     * numeric comparator {@link #effectiveComparator()} falls back to for non-NaN pairs.
     * <p>
     * The returned {@link ColumnSpec#renderer} calls {@code formatter.apply(extractor.applyAsDouble(row))}
     * — the exact two calls the cell factory below makes, in the exact same order, closed over
     * the exact same {@code extractor}/{@code formatter} references — so a copied cell and a
     * displayed cell cannot diverge without changing both at once.
     */
    private static ColumnSpec numberColumn(
            String title, ToDoubleFunction<PopulationStats.Row> extractor,
            DoubleFunction<String> formatter, String tooltip) {
        TableColumn<PopulationStats.Row, Number> col = new TableColumn<>();
        Label header = new Label(title);
        header.setTooltip(new Tooltip(tooltip));
        col.setGraphic(header);
        col.setCellValueFactory(data -> new SimpleDoubleProperty(extractor.applyAsDouble(data.getValue())));
        col.setComparator(Comparator.comparingDouble(Number::doubleValue));
        col.setSortable(true);
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                setStyle("-fx-alignment: CENTER-RIGHT;");
                setText(empty || value == null ? null : formatter.apply(value.doubleValue()));
            }
        });
        return new ColumnSpec(col, row -> formatter.apply(extractor.applyAsDouble(row)));
    }

    /**
     * Build item 5's {@link CustomMenuItem} — a {@link Label} carrying both the item's text
     * and its tooltip, since {@link MenuItem} itself has no tooltip property to set one on
     * directly. {@code static} and called from a field initializer, so it runs before
     * {@code this} is otherwise touched, the same as every other field built inline above it.
     */
    private static CustomMenuItem buildExportTableItem() {
        Label label = new Label("Population table as CSV…");
        label.setTooltip(new Tooltip(
                "Writes every scope and region, not only the rows currently shown."));
        return new CustomMenuItem(label, true);
    }

    /**
     * Which plot {@code copyPlotItem}/{@code plotImageItem}/{@code plotDataCsvItem} act on —
     * whichever {@link PlotCanvas} sits behind {@link #plotTabs}' currently selected tab. See
     * {@link #plotCanvases}' own comment for why the two stay in lockstep by construction
     * rather than by a second, hand-kept mapping.
     */
    private PlotCanvas currentPlotCanvas() {
        return plotCanvases.get(Math.max(0, plotTabs.getSelectionModel().getSelectedIndex()));
    }

    /** The currently selected tab's own title — {@code Tab} is the one place that name lives. */
    private String currentPlotTitle() {
        return plotTabs.getTabs().get(Math.max(0, plotTabs.getSelectionModel().getSelectedIndex()))
                .getText();
    }

    /**
     * Enable or disable {@code copyPlotItem}/{@code plotImageItem}/{@code plotDataCsvItem} for
     * whichever plot is currently selected — called on every tab change and after every push
     * of fresh rows (see {@link #setAllPlotRows}), so the three items track both "which plot"
     * and "does it currently have anything to export".
     * <p>
     * {@link PlotCanvas#plotData()} empty is the same "nothing to show" signal {@link
     * PlotCanvas#draw} itself falls back to an empty-state message on — see that method's own
     * javadoc — so a plot reading "No gated populations yet" on screen can never offer an
     * export that would just write an empty file.
     */
    private void updateExportMenuState() {
        boolean hasData = !currentPlotCanvas().plotData().isEmpty();
        copyPlotItem.setDisable(!hasData);
        plotImageItem.setDisable(!hasData);
        plotDataCsvItem.setDisable(!hasData);
    }

    /**
     * "Copy plot to clipboard" — the plot in the currently selected tab, rendered at
     * {@link #EXPORT_SCALE} so a pasted figure is not soft (see
     * {@link PlotImageExporter#copyToClipboard}). Wrapped the same way the two dialog-driven
     * exports below are, even though there is no file I/O to fail on here, so all three read
     * as one family of action rather than singling this one out as exempt from error reporting.
     */
    private void copyCurrentPlotToClipboard() {
        try {
            PlotImageExporter.copyToClipboard(currentPlotCanvas(), EXPORT_SCALE);
        } catch (RuntimeException ex) {
            Dialogs.showErrorMessage("Copy Error", ex.getMessage());
        }
    }

    /**
     * "Plot as image…" — <b>one</b> {@link Dialogs#promptToSaveFile} call offering both SVG
     * and PNG extension filters, SVG first, rather than two separate menu items; this is the
     * "user-choosable format" the brief asks for. The chosen file's own extension decides which
     * writer runs — see {@link #isPngFile} for why that also makes SVG the default when the
     * user types no extension at all.
     */
    private void exportCurrentPlotAsImage() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("SVG (*.svg)", ".svg");
        filters.put("PNG (*.png)", ".png");
        File file = Dialogs.promptToSaveFile(
                "Export Plot", null, plotFileBaseName() + ".svg", filters);
        if (file == null) return;
        try {
            if (isPngFile(file)) {
                PlotImageExporter.writePng(file, currentPlotCanvas(), EXPORT_SCALE);
            } else {
                PlotImageExporter.writeSvg(file, currentPlotCanvas().toSvg());
            }
            Dialogs.showInfoNotification("FlowPath", "Exported " + file.getName());
        } catch (IOException | RuntimeException ex) {
            Dialogs.showErrorMessage("Export Error", ex.getMessage());
        }
    }

    /** "Plot data as CSV…" — the numbers behind the plot in the currently selected tab. */
    private void exportCurrentPlotDataAsCsv() {
        File file = Dialogs.promptToSaveFile("Export Plot Data", null,
                plotFileBaseName() + "_data.csv", "CSV", ".csv");
        if (file == null) return;
        try {
            PlotDataCsvExporter.export(file, currentPlotTitle(), currentPlotCanvas().plotData());
            Dialogs.showInfoNotification("FlowPath", "Exported " + file.getName());
        } catch (IOException | RuntimeException ex) {
            Dialogs.showErrorMessage("Export Error", ex.getMessage());
        }
    }

    /**
     * {@code true} for a filename ending {@code .png} (case-insensitive); every other case —
     * including no extension at all — defaults to SVG, matching SVG being both the first
     * filter offered in {@link #exportCurrentPlotAsImage}'s dialog and, by
     * {@code Dialogs.promptToSaveFile}'s own rule for a name with no recognised extension, the
     * filter it defaults the picker to.
     */
    private static boolean isPngFile(File file) {
        return file.getName().toLowerCase(Locale.US).endsWith(".png");
    }

    /** A filesystem-friendly stem for the currently selected tab's default export filename. */
    private String plotFileBaseName() {
        return currentPlotTitle().toLowerCase(Locale.US).replace(' ', '_');
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
     * {@code % Parent (clean)} and {@code % Total (clean)} are never {@code NaN} at all under
     * the current model — see {@link PopulationStats.Row#percentOfCleanParent()} — so for
     * those two columns this method's blank branch is defensive, not a case a reader will
     * actually meet.
     */
    private static String formatPercent(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.1f", value);
    }

    /** {@code NaN} (no area known for this row) renders as an empty cell, not "NaN". */
    private static String formatDensity(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.1f", value);
    }
}
