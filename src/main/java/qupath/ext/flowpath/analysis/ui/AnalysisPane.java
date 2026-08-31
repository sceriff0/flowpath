package qupath.ext.flowpath.analysis.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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
    private final TableView<PopulationStats.Row> table = new TableView<>();
    private final Label placeholderLabel = new Label();

    private PopulationStats.Scope selectedScope;
    private Branch selectedDenominator;

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

        HBox controls = new HBox(10,
                new Label("Scope:"), scopeChoice,
                new Label("Denominator:"), denominatorCombo);
        controls.setPadding(new Insets(8));
        controls.setAlignment(Pos.CENTER_LEFT);

        setTop(controls);
        setCenter(table);

        refresh();
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

    /** The table's current placeholder text — non-null exactly when there is no data. */
    String placeholderText() {
        return placeholderLabel.getText();
    }

    /** Rows currently shown, for the scope and denominator in effect. */
    int rowCount() {
        return table.getItems().size();
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
            return;
        }
        PopulationStats stats = session.stats(selectedDenominator);
        table.getItems().setAll(stats.rows(selectedScope));
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
