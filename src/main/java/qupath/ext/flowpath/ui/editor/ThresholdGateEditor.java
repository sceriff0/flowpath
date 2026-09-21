package qupath.ext.flowpath.ui.editor;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.ui.widgets.HistogramCanvas;
import qupath.ext.flowpath.ui.widgets.SliderUtils;

import java.util.List;
import java.util.Locale;

/** A threshold gate: one channel, a histogram, a threshold slider and a typed threshold. */
final class ThresholdGateEditor extends AbstractGateTypeEditor<GateNode> {

    private final HistogramCanvas histogram = new HistogramCanvas();
    private final Slider slider;
    private final TextField valueField;
    private final Label populationLabel;

    ThresholdGateEditor(GateNode gate, EditorContext context) {
        super(gate, context);
        slider = new Slider(AxisMath.DEFAULT_AXIS_LO, AxisMath.DEFAULT_AXIS_HI, gate.getThreshold());
        valueField = new TextField(format(gate.getThreshold()));
        populationLabel = new Label("Positive: -- | Negative: --");
    }

    @Override
    Node buildControls(List<HBox> channelRows, List<ComboBox<String>> channelCombos) {
        Label hoverLabel = new Label(" ");
        hoverLabel.getStyleClass().add("fp-muted");
        hoverLabel.setStyle("-fx-font-size: 9;");
        histogram.setOnMouseHover(val -> hoverLabel.setText(String.format(Locale.US, "Value: %.4f", val)));

        slider.setPrefWidth(300);
        SliderUtils.makeRangeFriendly(slider);
        valueField.setPrefWidth(80);
        valueField.getStyleClass().add("fp-mono-field");

        populationLabel.getStyleClass().add("fp-muted");
        populationLabel.setStyle("-fx-font-size: 10;");

        histogram.setGate(gate);
        branchColorsChanged();

        slider.valueProperty().addListener((obs, old, val) -> {
            if (!accepting()) return;
            gate.setThreshold(val.doubleValue());
            valueField.setText(format(val.doubleValue()));
            histogram.setThreshold(val.doubleValue());
            context.gateChanged();
            updatePopulationCounts();
        });

        valueField.setOnAction(e -> applyThresholdFromField());
        valueField.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) applyThresholdFromField();
        });

        histogram.setOnThresholdChanged(val -> {
            if (!accepting()) return;
            gate.setThreshold(val);
            slider.setValue(val);
            valueField.setText(format(val));
            context.gateChanged();
            updatePopulationCounts();
        });

        HBox threshRow = new HBox(8, styledLabel("Threshold:", "fp-primary-text"), slider, valueField);
        HBox.setHgrow(slider, Priority.ALWAYS);

        VBox root = new VBox(4,
                channelRows.get(0), modeRow,
                sectionHeader("Histogram"), histogram, hoverLabel,
                sectionHeader("Threshold"), threshRow, populationLabel);
        refresh();
        return root;
    }

    /**
     * Re-read the histogram and re-range the slider from the column the engine gates on.
     * Everything — values, clip percentiles, slider range — comes off one {@link MeasuredColumn}
     * handle, so the histogram shows exactly what {@code GatingEngine} compares against.
     */
    @Override
    public void refresh() {
        if (isDisposed()) return;
        CellIndex index = context.cellIndex();
        if (index == null || context.markerStats() == null) return;
        String channel = gate.getChannel();
        if (channel == null || index.getMarkerIndex(channel) < 0) return;
        MeasuredColumn col = axisColumn(0);
        if (col == null) return;

        double[] displayValues = AxisMath.measuredValues(col.values(), context.roiMask(), context.ancestorMask());
        // Global per-column clip percentiles, so the same channel+compartment+statistic uses
        // one axis everywhere it appears in the gate tree. When the parent-filtered cells sit
        // outside it, the histogram's "X cells outside clip range" message says so.
        double[] window = AxisMath.thresholdWindow(
                col.percentile(gate.getClipPercentileLow()),
                col.percentile(gate.getClipPercentileHigh()),
                displayValues);

        histogram.setData(displayValues, window[0], window[1]);
        histogram.setThreshold(gate.getThreshold());
        // Suppressed, so a clamping range move cannot write a corrupted value back to the gate.
        context.withSuppressedEvents(() -> {
            slider.setMin(window[0]);
            slider.setMax(window[1]);
            // Re-pin the step to the new range so threshold "speed" matches the QC sliders
            // whether the column is ~10 wide or ~10000s wide.
            SliderUtils.applyRangeStep(slider);
            // Re-pin the thumb and the field AFTER the range move: Slider.setMin/setMax
            // silently clamp the value, which would leave the gate, the thumb and the field
            // holding three different numbers.
            slider.setValue(gate.getThreshold());
            valueField.setText(format(gate.getThreshold()));
        });
        updatePopulationCounts();
    }

    @Override
    public void branchColorsChanged() {
        histogram.setPosColor(ColorUtils.intToColor(gate.getPositiveColor()));
        histogram.setNegColor(ColorUtils.intToColor(gate.getNegativeColor()));
    }

    @Override
    public void updatePopulationCounts() {
        if (isDisposed()) return;
        List<Branch> branches = gate.getBranches();
        if (branches.size() != 2) return;
        int pos = branches.get(0).getCount();
        int neg = branches.get(1).getCount();
        int total = pos + neg;
        if (total > 0) {
            populationLabel.setText(String.format(
                    "%s: %,d (%.1f%%) | %s: %,d (%.1f%%)",
                    branches.get(0).getName(), pos, 100.0 * pos / total,
                    branches.get(1).getName(), neg, 100.0 * neg / total));
        } else {
            populationLabel.setText(branches.get(0).getName() + ": 0 | " + branches.get(1).getName() + ": 0");
        }
    }

    @Override
    Runnable captureForRemap() {
        MeasuredColumn oldCol = axisColumn(0);
        double oldThreshold = gate.getThreshold();
        return () -> gate.setThreshold(AxisMath.remapRawThreshold(oldCol, axisColumn(0), oldThreshold));
    }

    /** Threshold gates refresh in place: the histogram and slider re-read the new column. */
    @Override
    void afterSignalChange() {
        refresh();
        context.gateChanged();
    }

    /**
     * Commit the typed threshold, on Enter or focus loss. Only a real change is written and
     * reported: a field still showing the gate's own value (in its four-decimal rendering) is
     * not an edit, and reporting it recorded a no-op undo step that cleared the redo stack —
     * and rewrote a dragged threshold with its rounded rendering. A non-finite value is
     * rejected like unparseable text, as the quadrant fields do.
     */
    private void applyThresholdFromField() {
        if (!accepting()) return;
        double current = gate.getThreshold();
        String text = valueField.getText();
        if (format(current).equals(text)) return;
        double val;
        try {
            val = AxisMath.parseThreshold(text);
        } catch (NumberFormatException ex) {
            valueField.setText(format(current));
            return;
        }
        if (!Double.isFinite(val)) {
            valueField.setText(format(current));
            return;
        }
        if (val == current) return;
        context.withSuppressedEvents(() -> {
            gate.setThreshold(val);
            slider.setValue(val);
            histogram.setThreshold(val);
        });
        context.gateChanged();
        updatePopulationCounts();
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.4f", value);
    }
}
