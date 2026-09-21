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
import qupath.ext.flowpath.model.MorphologyField;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.ui.widgets.SliderUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <b>Pre-gating quality control, built from the morphology the export actually carries.</b>
 * <p>
 * This was five hard-coded pairs of sliders — area, eccentricity, solidity, total
 * intensity, perimeter — which was wrong in both directions at once. A whole-cell-only
 * mask carries no solidity, and the slider was drawn anyway over a column of NaN; a MIRAGE
 * export carries {@code Major Axis Length µm} and {@code Minor Axis Length µm}, and there
 * was no way to filter on either, nor anything to say the columns were there unread.
 * <p>
 * The rows are now one per {@link CellIndex#morphology()} entry, and each slider's travel
 * is the observed range of that column rather than a guessed constant. A field the file
 * does not carry has no row, because there is nothing to filter; a field FlowPath has
 * never heard of gets a row like any other, because the panel does not need to recognise a
 * measurement to let you threshold it.
 *
 * <h2>Ranges come from the data</h2>
 * The old panel guessed: area spanned 0..50 000, eccentricity 0..1, and total intensity
 * was re-ranged by a separate {@code updateRanges} call that knew about three of the five.
 * A slider whose travel does not cover the data cannot express the filter you want, and
 * one that covers far more than the data is unusable at the scale that matters. Each
 * slider now spans its own column's observed minimum to maximum.
 */
public class QualityFilterPane extends TitledPane {

    private QualityFilter filter;
    private boolean suppressEvents = false;

    private final VBox content = new VBox(4);
    private final GridPane grid = new GridPane();
    private final Label emptyLabel = new Label("No morphology measurements in this export.");

    /** One row per field currently shown, keyed by slug. */
    private final Map<String, Row> rows = new LinkedHashMap<>();

    private Consumer<QualityFilter> onFilterChanged;
    /**
     * What kind of user change is about to be written: a slider tick, which arrives in bursts
     * and is recorded coalesced, or a Reset, which is one discrete click.
     */
    public enum ChangeKind { DRAG, RESET }

    /** Runs before the filter is written, so an undo snapshot taken there holds the old value. */
    private Consumer<ChangeKind> onBeforeFilterChange;

    /** The controls for one morphology field. */
    private record Row(MorphologyField field, Slider min, Slider max,
                       Label minLabel, Label maxLabel) {}

    public QualityFilterPane(QualityFilter filter) {
        this.filter = filter != null ? filter : new QualityFilter();
        setText("Quality Filter");
        setCollapsible(true);
        setExpanded(true);

        grid.setHgap(4);
        grid.setVgap(4);
        grid.setPadding(new Insets(6));

        emptyLabel.getStyleClass().add("fp-hint");
        emptyLabel.setStyle("-fx-font-size: 10;");
        emptyLabel.setWrapText(true);

        Button reset = new Button("Reset");
        reset.setOnAction(e -> resetToDefaults());

        content.setPadding(new Insets(2));
        content.getChildren().addAll(emptyLabel, grid, reset);
        setContent(content);

        showFields(List.of());
    }

    /**
     * Rebuild the panel for {@code index}'s morphology.
     * <p>
     * The single entry point: what there is to filter on, and the travel of every slider,
     * both come from here. Passing {@code null} clears the panel, which is the honest
     * rendering of "no cells loaded" — an empty panel rather than sliders over nothing.
     */
    public void setCellIndex(CellIndex index) {
        showFields(index == null ? List.of() : index.morphology());
    }

    private void showFields(List<MorphologyField> fields) {
        rows.clear();
        grid.getChildren().clear();

        boolean any = fields != null && !fields.isEmpty();
        emptyLabel.setVisible(!any);
        emptyLabel.setManaged(!any);
        grid.setVisible(any);
        grid.setManaged(any);
        if (!any) return;

        int row = 0;
        for (MorphologyField field : fields) {
            double[] bounds = observedRange(field);
            if (bounds == null) continue;   // nothing measured; nothing to threshold

            QualityFilter.Range current = filter.range(field.slug());
            double lo = Double.isFinite(current.min()) ? clamp(current.min(), bounds) : bounds[0];
            double hi = Double.isFinite(current.max()) ? clamp(current.max(), bounds) : bounds[1];

            // The name gets a row of its own. It used to sit in a column beside two
            // 110px sliders inside a 280px pane, hard-coded white on that pane's light
            // background — so what the user saw was the bars and nothing saying what they
            // filtered. No text fill is set anywhere here: the theme decides, so the label
            // reads on QuPath's light and dark styles alike.
            Label name = new Label(field.label());
            name.setStyle("-fx-font-size: 10; -fx-font-weight: bold;");
            name.setMinWidth(Region.USE_PREF_SIZE);
            name.setTooltip(new Tooltip(field.key() + "\nObserved " + fmt(bounds[0]) + " to " + fmt(bounds[1])));

            Slider minSlider = slider(bounds, lo);
            Slider maxSlider = slider(bounds, hi);
            Label minLabel = new Label(fmt(lo));
            Label maxLabel = new Label(Double.isFinite(current.max()) ? fmt(hi) : "off");
            minLabel.getStyleClass().add("fp-muted");
            maxLabel.getStyleClass().add("fp-muted");
            minLabel.setStyle("-fx-font-size: 9;");
            maxLabel.setStyle("-fx-font-size: 9;");
            // Fixed width, so a value growing a digit does not shove the sliders mid-drag.
            minLabel.setMinWidth(36);
            maxLabel.setMinWidth(36);
            minSlider.setTooltip(new Tooltip(field.label() + " minimum"));
            maxSlider.setTooltip(new Tooltip(field.label() + " maximum"));

            Row r = new Row(field, minSlider, maxSlider, minLabel, maxLabel);
            rows.put(field.slug(), r);

            minSlider.valueProperty().addListener((o, a, b) -> onSliderMoved(r));
            maxSlider.valueProperty().addListener((o, a, b) -> onSliderMoved(r));

            grid.add(name, 0, row, 4, 1);
            row++;
            grid.add(minSlider, 0, row);
            grid.add(minLabel, 1, row);
            grid.add(maxSlider, 2, row);
            grid.add(maxLabel, 3, row);
            GridPane.setHgrow(minSlider, Priority.ALWAYS);
            GridPane.setHgrow(maxSlider, Priority.ALWAYS);
            row++;
        }
    }

    /**
     * The column's observed span, or {@code null} when it has none.
     * <p>
     * A column that is entirely NaN, or entirely one value, cannot be thresholded into two
     * non-empty groups — a slider over it would move without ever changing the result,
     * which is the same "control that cannot do anything" the row hiding exists to avoid.
     */
    private static double[] observedRange(MorphologyField field) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : field.values()) {
            if (Double.isNaN(v)) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
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
        r.minLabel().setText(fmt(lo));
        r.maxLabel().setText(hi >= r.max().getMax() ? "off" : fmt(hi));

        // At the very ends the control means "do not constrain this side", so the range is
        // stored open rather than pinned to the observed extreme. Otherwise a filter saved
        // against one slide would silently exclude cells on a slide whose values run wider.
        double min = lo <= r.min().getMin() ? Double.NEGATIVE_INFINITY : lo;
        double max = hi >= r.max().getMax() ? Double.POSITIVE_INFINITY : hi;
        fireBeforeChange(ChangeKind.DRAG);
        filter.setRange(r.field().slug(), new QualityFilter.Range(min, max));
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

    /** Adopt a different filter and redraw against the fields currently shown. */
    public void setFilter(QualityFilter newFilter) {
        this.filter = newFilter != null ? newFilter : new QualityFilter();
        List<MorphologyField> shown = new ArrayList<>();
        for (Row r : rows.values()) shown.add(r.field());
        suppressEvents = true;
        try {
            showFields(shown);
        } finally {
            suppressEvents = false;
        }
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

    /** The slugs currently offered, in display order. */
    public List<String> shownFields() {
        return List.copyOf(rows.keySet());
    }

    /**
     * Clear every constraint and return the sliders to their columns' full span. A discrete
     * change ({@link ChangeKind#RESET}): announced as a slider tick it was folded into a drag
     * made within the coalescing window, and one undo reverted both. A reset that would clear
     * nothing announces nothing, so it records no empty undo step.
     */
    public void resetToDefaults() {
        if (rows.keySet().stream().allMatch(slug -> filter.range(slug).isOpen())) return;
        fireBeforeChange(ChangeKind.RESET);
        for (Row r : rows.values()) filter.setRange(r.field().slug(), null);
        List<MorphologyField> shown = new ArrayList<>();
        for (Row r : rows.values()) shown.add(r.field());
        suppressEvents = true;
        try {
            showFields(shown);
        } finally {
            suppressEvents = false;
        }
        fireChanged();
    }
}
