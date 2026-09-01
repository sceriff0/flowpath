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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private final AnalysisSession session;

    private final ChoiceBox<PopulationStats.Scope> scopeChoice = new ChoiceBox<>();
    private final ComboBox<DenominatorRef> denominatorCombo = new ComboBox<>();
    private final ComboBox<Integer> rootCombo = new ComboBox<>();
    private final ComboBox<PopulationRef> populationCombo = new ComboBox<>();
    private final TextField filterField = new TextField();
    private final Button exportButton = new Button("Export CSV...");
    private final TableView<PopulationStats.Row> table = new TableView<>();
    private final Label placeholderLabel = new Label();

    // The table's items are this fixed FilteredList-over-SortedList pipeline, built once and
    // never replaced by updateTable() -- only backingRows' CONTENT changes on a push. Rebuilding
    // table.setItems() on every push is what would strand a mid-scroll, mid-sort user: a fresh
    // list reference resets the TableView's virtual flow and its sort comparator binding. See
    // effectiveComparator() for why a plain bind to table.comparatorProperty() is not enough on
    // its own to keep NaN rows pinned to the end in both sort directions.
    private final ObservableList<PopulationStats.Row> backingRows = FXCollections.observableArrayList();
    private final FilteredList<PopulationStats.Row> filteredRows = new FilteredList<>(backingRows, r -> true);
    private final SortedList<PopulationStats.Row> sortedRows = new SortedList<>(filteredRows);

    // Parallel to table.getColumns(): renderer(i) produces exactly the text column i shows for
    // a given row, reused by copySelectionAsTsv() so a copied cell can never read something the
    // screen does not -- a second "how does this cell render" would be the divergence this
    // codebase already keeps a list of (see ResolvedGate.branchOf's javadoc for the general
    // shape of that failure).
    private List<Function<PopulationStats.Row, String>> rowRenderers = List.of();

    private final CompositionCanvas compositionCanvas = new CompositionCanvas();
    private final RegionComparisonCanvas regionComparisonCanvas = new RegionComparisonCanvas();
    private final ScopeComparisonCanvas scopeComparisonCanvas = new ScopeComparisonCanvas();
    private final MarkerPositivityCanvas markerPositivityCanvas = new MarkerPositivityCanvas();

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
        populationCombo.valueProperty().addListener((obs, old, value) -> {
            selectedPopulation = value;
            regionComparisonCanvas.setSelectedPopulation(value);
            scopeComparisonCanvas.setSelectedPopulation(value);
        });

        exportButton.setOnAction(e -> exportCsv());

        filterField.setPromptText("Filter populations…");
        filterField.setPrefWidth(180);
        filterField.textProperty().addListener((obs, old, text) -> applyFilter(text));

        HBox controls = new HBox(10,
                new Label("Scope:"), scopeChoice,
                new Label("Denominator:"), denominatorCombo,
                new Label("Root:"), rootCombo,
                new Label("Population:"), populationCombo,
                filterField,
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
     * comes from {@link #rowRenderers}, the exact functions the table's own cell factories
     * render with, so a copied value can never read something the screen does not.
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

        List<PopulationRef> populations = scopeComparisonCanvas.availablePopulations();
        populationCombo.getItems().setAll(populations);
        if (selectedPopulation == null || !populations.contains(selectedPopulation)) {
            selectedPopulation = populations.isEmpty() ? null : populations.get(0);
        }
        populationCombo.setValue(selectedPopulation);
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
                spec(rootColumn(), row -> String.valueOf(row.rootIndex() + 1)),
                spec(column("Population", PopulationStats.Row::path), PopulationStats.Row::path),
                spec(column("Region", row -> row.regionName() == null ? "" : row.regionName()),
                        row -> row.regionName() == null ? "" : row.regionName()),
                // What Count includes depends on the scope, and saying otherwise was wrong
                // rather than merely vague: at the two annotation scopes the number comes
                // from BranchTally's per-region arrays, which are only incremented for a
                // cell with a region, and RegionMask gives every ROI-excluded cell a region
                // of -1. So ROI-excluded cells are in Count at WHOLE_SLIDE and absent from
                // it per region -- which also changes what the Count/Clean gap means.
                spec(countColumn("Count", PopulationStats.Row::count,
                        "Every cell that landed in this population, including cells the "
                                + "quality filter excluded from the view.\n"
                                + "At Whole slide this also includes cells outside the "
                                + "annotation ROI filter; at the per-region scopes those "
                                + "cells belong to no region and are not counted at all, so "
                                + "there the gap to Clean is quality filtering alone."),
                        row -> String.valueOf(row.count())),
                spec(countColumn("Clean", PopulationStats.Row::cleanCount,
                        "Cells in this population that were not excluded: not quality-filtered, "
                                + "not outlier-clipped and, when the annotation ROI filter is on, "
                                + "inside the annotations being filtered by. This is the number "
                                + "the gate tree shows."),
                        row -> String.valueOf(row.cleanCount())),
                spec(numberColumn("% Parent", PopulationStats.Row::percentOfParent, AnalysisPane::formatPercent,
                        "This branch's share of the branch directly above it (or, for a root "
                                + "branch, of the scope's whole population)."),
                        row -> formatPercent(row.percentOfParent())),
                spec(numberColumn("% Parent (clean)", PopulationStats.Row::percentOfCleanParent, AnalysisPane::formatPercent,
                        "The clean counterpart of % Parent: the clean count over the clean "
                                + "parent count, so quality-filtered, outlier-clipped and (when the "
                                + "ROI filter is on) out-of-annotation cells are excluded from both "
                                + "the numerator and the denominator, not just the numerator."),
                        row -> formatPercent(row.percentOfCleanParent())),
                spec(numberColumn("% Total", PopulationStats.Row::percentOfTotal, AnalysisPane::formatPercent,
                        "This branch's share of every cell at the current scope."),
                        row -> formatPercent(row.percentOfTotal())),
                spec(numberColumn("% Total (clean)", PopulationStats.Row::percentOfCleanTotal, AnalysisPane::formatPercent,
                        "The clean counterpart of % Total: the clean count over the scope's "
                                + "clean total, the same clean/raw split % Parent (clean) makes."),
                        row -> formatPercent(row.percentOfCleanTotal())),
                spec(numberColumn("% of Denominator", PopulationStats.Row::percentOfDenominator, AnalysisPane::formatPercent,
                        "This branch's share of the denominator chosen in the picker above. "
                                + "Blank when no denominator is chosen, and equally blank when the "
                                + "chosen denominator holds no cells -- see AnalysisPane.formatPercent."),
                        row -> formatPercent(row.percentOfDenominator())),
                spec(numberColumn("Density", PopulationStats.Row::densityPerMm2, AnalysisPane::formatDensity,
                        "Cells per mm² of the region this row reports over. Blank without a "
                                + "known area -- see the Area column."),
                        row -> formatDensity(row.densityPerMm2())),
                spec(numberColumn("Area (mm²)", PopulationStats.Row::areaMm2, AnalysisPane::formatDensity,
                        "The annotated region's area. Blank when the image has no pixel "
                                + "calibration, or for the implicit whole-image region, which no "
                                + "single ROI describes."),
                        row -> formatDensity(row.areaMm2())));

        table.getColumns().setAll(specs.stream().map(ColumnSpec::column).toList());
        rowRenderers = specs.stream().map(ColumnSpec::renderer).toList();
    }

    /** One column and the function that renders one row's value for it, kept paired so they can never drift apart. */
    private record ColumnSpec(TableColumn<PopulationStats.Row, ?> column,
                               Function<PopulationStats.Row, String> renderer) {}

    private static ColumnSpec spec(TableColumn<PopulationStats.Row, ?> column,
                                    Function<PopulationStats.Row, String> renderer) {
        return new ColumnSpec(column, renderer);
    }

    /**
     * A text column, deliberately left unsortable — {@code Population} and {@code Region}
     * carry no numeric meaning to rank by. {@link #numberColumn} is what makes sorting
     * correct for the percentage/density/area columns below it: splitting the raw
     * {@code double} (the cell <em>value</em>) from its formatted rendering (the cell
     * <em>factory</em>) is what lets JavaFX sort the number while a reader still sees the
     * formatted string, which a single {@code String}-typed column could never do — sorting
     * "100.0" against "20.0" lexicographically is exactly the bug this task fixes.
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
     */
    private static TableColumn<PopulationStats.Row, Number> numberColumn(
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
