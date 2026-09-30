package qupath.ext.flowpath.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MeasurementName;
import qupath.ext.flowpath.model.QualityField;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.RoundMask;
import qupath.ext.flowpath.model.RoundQc;
import qupath.ext.flowpath.ui.widgets.SliderUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <b>Pre-gating quality control, built from what the export carries</b>, as two collapsible
 * sections:
 * <ul>
 *   <li><b>Morphology</b> — every {@code MORPH: …} field. A cell outside a range is excluded.</li>
 *   <li><b>QC</b> — <em>Cell QC</em>, every cell-level {@code QC: …} metric (a cell outside is
 *       excluded), then <em>Round QC</em>, one row per round-level metric that applies to every
 *       imaging round: a cell outside it in round r becomes Unmeasured for gates on round r's
 *       markers only. An expander under it lists each round and how many cells fail it.</li>
 * </ul>
 * Every slider spans its own column's observed range; a field the file does not carry has no
 * row. At either end a slider means "do not constrain this side", so the range is stored open.
 */
public class QualityFilterPane extends VBox {

    private QualityFilter filter;
    private CellIndex index;
    private boolean suppressEvents = false;

    private final TitledPane morphologySection = new TitledPane();
    private final TitledPane qcSection = new TitledPane();
    private final GridPane morphologyGrid = grid();
    private final GridPane cellQcGrid = grid();
    private final GridPane roundQcGrid = grid();
    private final Label morphologyEmpty = hint("No MORPH: measurements in this export.");
    private final Label qcEmpty = hint("No QC: measurements in this export.");
    private final Label cellQcHeader = subheader("Cell QC");
    private final Label roundQcHeader = subheader("Round QC — each metric applies to every round");
    private final TitledPane roundsExpander = new TitledPane();
    private final VBox roundsList = new VBox(2);

    /** One row per slug currently shown, in display order. */
    private final Map<String, Row> rows = new LinkedHashMap<>();

    private Consumer<QualityFilter> onFilterChanged;

    /**
     * What kind of user change is about to be written: a slider tick, which arrives in bursts
     * and is recorded coalesced, or a Reset, which is one discrete click.
     */
    public enum ChangeKind { DRAG, RESET }

    /** Runs before the filter is written, so an undo snapshot taken there holds the old value. */
    private Consumer<ChangeKind> onBeforeFilterChange;

    /** The controls for one filterable column (a field, or a round metric across all rounds). */
    private record Row(String slug, String label, Slider min, Slider max, Label minLabel, Label maxLabel) {}

    public QualityFilterPane(QualityFilter filter) {
        super(4);
        this.filter = filter != null ? filter : new QualityFilter();

        morphologySection.setText("Morphology");
        morphologySection.setContent(new VBox(2, morphologyEmpty, morphologyGrid));
        morphologySection.setExpanded(true);

        roundsExpander.setContent(roundsList);
        roundsExpander.setExpanded(false);
        roundsExpander.expandedProperty().addListener((o, was, now) -> {
            if (now) refreshRoundFailures();
        });
        roundsList.setPadding(new Insets(2, 2, 2, 8));

        Button reset = new Button("Reset");
        reset.setOnAction(e -> resetToDefaults());
        reset.setTooltip(new Tooltip("Clear every morphology and QC range"));

        qcSection.setText("QC");
        qcSection.setContent(new VBox(2, qcEmpty, cellQcHeader, cellQcGrid, roundQcHeader, roundQcGrid, roundsExpander));
        qcSection.setExpanded(true);

        getChildren().addAll(morphologySection, qcSection, reset);
        rebuild();
    }

    /**
     * Rebuild the panel for {@code index}: what there is to filter on, and every slider's travel,
     * both come from here. {@code null} clears the panel — no cells, nothing to filter.
     */
    public void setCellIndex(CellIndex index) {
        this.index = index;
        rebuild();
    }

    /** Adopt a different filter and redraw against the same cells. */
    public void setFilter(QualityFilter newFilter) {
        this.filter = newFilter != null ? newFilter : new QualityFilter();
        suppressEvents = true;
        try {
            rebuild();
        } finally {
            suppressEvents = false;
        }
    }

    private void rebuild() {
        rows.clear();
        morphologyGrid.getChildren().clear();
        cellQcGrid.getChildren().clear();
        roundQcGrid.getChildren().clear();

        List<QualityField> fields = index == null ? List.of() : index.qualityFields();
        RoundQc rounds = index == null ? RoundQc.NONE : index.roundQc();
        int morph = 0;
        int cell = 0;
        for (QualityField f : fields) {
            double[] bounds = observedRange(f.values());
            if (bounds == null) continue;   // nothing measured or constant; nothing to threshold
            String tip = f.key() + "\nObserved " + fmt(bounds[0]) + " to " + fmt(bounds[1]);
            if (f.kind() == MeasurementName.Kind.MORPHOLOGY) {
                morph = addRow(morphologyGrid, morph, f.slug(), f.label(), tip, bounds);
            } else {
                cell = addRow(cellQcGrid, cell, f.slug(), f.label(), tip, bounds);
            }
        }
        int round = 0;
        for (int m = 0; m < rounds.metrics().size(); m++) {
            RoundQc.Metric metric = rounds.metrics().get(m);
            double[] bounds = observedRange(rounds, m);
            if (bounds == null) continue;
            String tip = "QC: " + metric.keyName() + ": [...] — one range for every round; a cell outside it in"
                    + " a round is Unmeasured for that round's markers only.\nObserved " + fmt(bounds[0]) + " to "
                    + fmt(bounds[1]) + retentionNote(metric);
            round = addRow(roundQcGrid, round, metric.slug(), metric.label(), tip, bounds);
        }

        show(morphologyEmpty, morph == 0);
        show(morphologyGrid, morph > 0);
        boolean anyQc = cell > 0 || round > 0;
        show(qcEmpty, !anyQc);
        show(cellQcHeader, cell > 0);
        show(cellQcGrid, cell > 0);
        show(roundQcHeader, round > 0);
        show(roundQcGrid, round > 0);
        show(roundsExpander, round > 0);
        refreshRoundFailures();
    }

    private static String retentionNote(RoundQc.Metric metric) {
        return metric.slug().equals("qcround/nuclear_retention")
                ? "\nAssumes each round re-stains the same section (cyclic imaging); meaningless on serial sections."
                : "";
    }

    /** Add a label row and a slider row for {@code slug}; returns the next free grid row. */
    private int addRow(GridPane grid, int row, String slug, String label, String tooltip, double[] bounds) {
        QualityFilter.Range current = filter.range(slug);
        double lo = Double.isFinite(current.min()) ? clamp(current.min(), bounds) : bounds[0];
        double hi = Double.isFinite(current.max()) ? clamp(current.max(), bounds) : bounds[1];

        // No text fill set: the theme decides, so the label reads on light and dark styles alike.
        Label name = new Label(label);
        name.setStyle("-fx-font-size: 10; -fx-font-weight: bold;");
        name.setMinWidth(Region.USE_PREF_SIZE);
        name.setTooltip(new Tooltip(tooltip));

        Slider minSlider = slider(bounds, lo);
        Slider maxSlider = slider(bounds, hi);
        Label minLabel = valueLabel(Double.isFinite(current.min()) ? fmt(lo) : "off");
        Label maxLabel = valueLabel(Double.isFinite(current.max()) ? fmt(hi) : "off");
        minSlider.setTooltip(new Tooltip(label + " minimum"));
        maxSlider.setTooltip(new Tooltip(label + " maximum"));

        Row r = new Row(slug, label, minSlider, maxSlider, minLabel, maxLabel);
        rows.put(slug, r);
        minSlider.valueProperty().addListener((o, a, b) -> onSliderMoved(r));
        maxSlider.valueProperty().addListener((o, a, b) -> onSliderMoved(r));

        grid.add(name, 0, row, 4, 1);
        grid.add(minSlider, 0, row + 1);
        grid.add(minLabel, 1, row + 1);
        grid.add(maxSlider, 2, row + 1);
        grid.add(maxLabel, 3, row + 1);
        GridPane.setHgrow(minSlider, Priority.ALWAYS);
        GridPane.setHgrow(maxSlider, Priority.ALWAYS);
        return row + 2;
    }

    /**
     * The per-round failure list, "[CD3, CD8] — 412 cells fail (2.1%)". Computed only while the
     * expander is open: it is a scan of every round's metrics, not worth paying per slider tick
     * for a list nobody is looking at. The expander's own title always says how many rounds.
     */
    private void refreshRoundFailures() {
        RoundQc rounds = index == null ? RoundQc.NONE : index.roundQc();
        int n = rounds.rounds().size();
        roundsList.getChildren().clear();
        if (n == 0) {
            roundsExpander.setText("");
            return;
        }
        if (!roundsExpander.isExpanded()) {
            roundsExpander.setText(n + (n == 1 ? " round" : " rounds"));
            return;
        }
        RoundMask mask = rounds.mask(filter);
        int cells = index.size();
        int anyFailing = 0;
        for (RoundQc.Round round : rounds.rounds()) {
            int failing = mask.failedCount(round.index());
            anyFailing += failing;
            Label line = new Label(round.label() + " — " + String.format(Locale.US, "%,d", failing) + " cells fail ("
                    + String.format(Locale.US, "%.1f", cells == 0 ? 0.0 : 100.0 * failing / cells) + "%)");
            line.getStyleClass().add("fp-muted");
            line.setStyle("-fx-font-size: 10;");
            line.setWrapText(true);
            roundsList.getChildren().add(line);
        }
        roundsExpander.setText(n + (n == 1 ? " round" : " rounds") + " · "
                + String.format(Locale.US, "%,d", anyFailing) + " cell-round failures");
    }

    /** The span of {@code values}, or {@code null} when it has none (all NaN, or one value). */
    static double[] observedRange(double[] values) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (Double.isNaN(v)) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return spanOrNull(lo, hi);
    }

    /** A round metric's span over every round it was exported for. */
    private static double[] observedRange(RoundQc rounds, int metric) {
        double[] span = rounds.span(metric);
        return span == null ? null : spanOrNull(span[0], span[1]);
    }

    private static double[] spanOrNull(double lo, double hi) {
        if (!Double.isFinite(lo) || !Double.isFinite(hi) || hi - lo < 1e-12) return null;
        return new double[]{lo, hi};
    }

    private static double clamp(double v, double[] bounds) {
        return Math.max(bounds[0], Math.min(bounds[1], v));
    }

    private static Slider slider(double[] bounds, double value) {
        Slider s = new Slider(bounds[0], bounds[1], clamp(value, bounds));
        s.setPrefWidth(90);
        s.setMinWidth(40);
        SliderUtils.makeRangeFriendly(s);
        return s;
    }

    private void onSliderMoved(Row r) {
        if (suppressEvents) return;
        double lo = r.min().getValue();
        double hi = r.max().getValue();
        r.minLabel().setText(lo <= r.min().getMin() ? "off" : fmt(lo));
        r.maxLabel().setText(hi >= r.max().getMax() ? "off" : fmt(hi));

        // At the very ends the control means "do not constrain this side", so the range is
        // stored open rather than pinned to the observed extreme. Otherwise a filter saved
        // against one slide would silently exclude cells on a slide whose values run wider.
        double min = lo <= r.min().getMin() ? Double.NEGATIVE_INFINITY : lo;
        double max = hi >= r.max().getMax() ? Double.POSITIVE_INFINITY : hi;
        fireBeforeChange(ChangeKind.DRAG);
        filter.setRange(r.slug(), new QualityFilter.Range(min, max));
        if (r.slug().startsWith("qcround/")) refreshRoundFailures();
        fireChanged();
    }

    private void fireBeforeChange(ChangeKind kind) {
        if (!suppressEvents && onBeforeFilterChange != null) onBeforeFilterChange.accept(kind);
    }

    private void fireChanged() {
        if (!suppressEvents && onFilterChanged != null) onFilterChanged.accept(filter);
    }

    private static String fmt(double v) {
        if (!Double.isFinite(v)) return "off";
        return Math.abs(v) >= 100 ? String.format(Locale.US, "%.0f", v)
                                  : String.format(Locale.US, "%.2f", v);
    }

    public void setOnFilterChanged(Consumer<QualityFilter> callback) {
        this.onFilterChanged = callback;
    }

    /**
     * Called just before a user change is written into the filter, with what kind of change
     * it is: a slider tick ({@link ChangeKind#DRAG}, coalesced by the caller) or a Reset
     * ({@link ChangeKind#RESET}, a step of its own).
     * <p>
     * The panel edits the filter object in place and fires {@link #setOnFilterChanged
     * onFilterChanged} afterwards, so an undo snapshot taken in that callback would already
     * hold the new value and undo would restore nothing. This is where to take it.
     */
    public void setOnBeforeFilterChange(Consumer<ChangeKind> callback) {
        this.onBeforeFilterChange = callback;
    }

    public QualityFilter getFilter() {
        return filter;
    }

    /** The slugs currently offered, in display order: morphology, cell QC, round QC. */
    public List<String> shownFields() {
        return List.copyOf(rows.keySet());
    }

    /** Every slider, min then max per row, in display order — for tests, which run with no skin. */
    List<Slider> sliders() {
        List<Slider> out = new ArrayList<>();
        for (Row r : rows.values()) {
            out.add(r.min());
            out.add(r.max());
        }
        return out;
    }

    /**
     * Clear every constraint and return the sliders to their columns' full span. A discrete
     * change ({@link ChangeKind#RESET}); a reset that would clear nothing announces nothing, so
     * it records no empty undo step.
     */
    public void resetToDefaults() {
        if (rows.keySet().stream().allMatch(slug -> filter.range(slug).isOpen())) return;
        fireBeforeChange(ChangeKind.RESET);
        for (String slug : rows.keySet()) filter.setRange(slug, null);
        suppressEvents = true;
        try {
            rebuild();
        } finally {
            suppressEvents = false;
        }
        fireChanged();
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(4);
        g.setVgap(4);
        g.setPadding(new Insets(4));
        return g;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("fp-hint");
        l.setStyle("-fx-font-size: 10;");
        l.setWrapText(true);
        return l;
    }

    private static Label subheader(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("fp-section-header");
        l.setStyle("-fx-font-size: 10;");
        return l;
    }

    private static Label valueLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("fp-muted");
        l.setStyle("-fx-font-size: 9;");
        // Fixed width, so a value growing a digit does not shove the sliders mid-drag.
        l.setMinWidth(36);
        return l;
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
