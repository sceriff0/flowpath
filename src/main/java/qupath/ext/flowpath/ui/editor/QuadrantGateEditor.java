package qupath.ext.flowpath.ui.editor;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.ui.SliderUtils;

import java.util.Locale;

/** A quadrant gate: two channels, a threshold slider and typed threshold per axis, a scatter plot. */
final class QuadrantGateEditor extends TwoAxisGateEditor<QuadrantGate> {

    // Slider travel is the SAME window the scatter plot's axes show: each axis' clip
    // percentiles. It used to be the raw data min to max, every outlier included, so on a
    // skewed marker the visible population was a few pixels of slider.
    private final Slider sliderX = new Slider(AxisMath.DEFAULT_AXIS_LO, AxisMath.DEFAULT_AXIS_HI, 0);
    private final Slider sliderY = new Slider(AxisMath.DEFAULT_AXIS_LO, AxisMath.DEFAULT_AXIS_HI, 0);
    private final TextField valX;
    private final TextField valY;

    QuadrantGateEditor(QuadrantGate gate, EditorContext context) {
        super(gate, context);
        valX = thresholdField(gate.getThresholdX());
        valY = thresholdField(gate.getThresholdY());
    }

    @Override
    public Node build() {
        ComboBox<String> chXCombo = channelCombo(gate.getChannelX(), 150);
        ComboBox<String> chYCombo = channelCombo(gate.getChannelY(), 150);
        // One handler per axis, wired before the plot is built.
        wireChannelCombo(chXCombo, 0);
        wireChannelCombo(chYCombo, 1);

        // Pin both axes to a signal the export carries BEFORE anything reads a column. The
        // slider span below is that column's clip window; ranged first, a gate whose stored
        // statistic the file lacks (a default MEDIAN gate over bare mean columns) resolved to
        // no column at all and opened on the [-5, 5] default until the next data change.
        HBox rowX = channelRow("Channel X:", chXCombo, 0);
        HBox rowY = channelRow("Channel Y:", chYCombo, 1);
        syncModeSelection();

        sliderX.setPrefWidth(300);
        sliderY.setPrefWidth(300);
        SliderUtils.enableScrollControl(sliderX);
        SliderUtils.enableScrollControl(sliderY);

        rerange();

        sliderX.valueProperty().addListener((obs, old, val) -> {
            if (!accepting()) return;
            gate.setThresholdX(val.doubleValue());
            valX.setText(format(val.doubleValue()));
            if (scatter != null) scatter.setGateOverlay(gate);
            context.gateChanged();
        });
        sliderY.valueProperty().addListener((obs, old, val) -> {
            if (!accepting()) return;
            gate.setThresholdY(val.doubleValue());
            valY.setText(format(val.doubleValue()));
            if (scatter != null) scatter.setGateOverlay(gate);
            context.gateChanged();
        });

        // Typed entry, for a threshold the slider's resolution cannot land on exactly.
        // A value outside the visible window is honoured and the slider widens to show it.
        wireField(valX, true);
        wireField(valY, false);

        VBox root = new VBox(4,
                rowX, rowY,
                modeRow,
                sectionHeader("Threshold X"), growRow(sliderX, valX),
                sectionHeader("Threshold Y"), growRow(sliderY, valY));

        if (hasPlottableAxes()) {
            root.getChildren().addAll(sectionHeader("Scatter Plot"), newScatter());
        }
        return root;
    }

    /** New data: re-read the plot and re-range the sliders to the window it now shows. */
    @Override
    public void refresh() {
        if (isDisposed()) return;
        redrawScatter();
        rerange();
    }

    /**
     * Range both sliders to their axis' visible window, widened to hold the threshold.
     * Widen before narrowing so setMin never crosses the current max, and re-pin the value
     * afterwards: Slider clamps silently on a range move.
     */
    private void rerange() {
        context.withSuppressedEvents(() -> {
            rerange(sliderX, axisColumn(0), gate.getThresholdX());
            rerange(sliderY, axisColumn(1), gate.getThresholdY());
        });
    }

    private void rerange(Slider slider, MeasuredColumn column, double threshold) {
        double[] span = AxisMath.quadrantSliderSpan(clipSpan(column), threshold);
        slider.setMin(Math.min(slider.getMin(), span[0]));
        slider.setMax(span[1]);
        slider.setMin(span[0]);
        slider.setValue(threshold);
        SliderUtils.applyRangeStep(slider);
    }

    @Override
    Runnable captureForRemap() {
        MeasuredColumn oldX = axisColumn(0);
        MeasuredColumn oldY = axisColumn(1);
        return () -> {
            gate.setThresholdX(AxisMath.remapRawThreshold(oldX, axisColumn(0), gate.getThresholdX()));
            gate.setThresholdY(AxisMath.remapRawThreshold(oldY, axisColumn(1), gate.getThresholdY()));
        };
    }

    /** Commit a typed threshold on Enter or focus loss; revert on a bad number. */
    private void wireField(TextField field, boolean xAxis) {
        Runnable commit = () -> {
            if (!accepting()) return;
            double current = xAxis ? gate.getThresholdX() : gate.getThresholdY();
            double val;
            try {
                val = AxisMath.parseThreshold(field.getText());
            } catch (NumberFormatException ex) {
                field.setText(format(current));
                return;
            }
            if (!Double.isFinite(val)) {
                field.setText(format(current));
                return;
            }
            if (val == current) return;
            if (xAxis) gate.setThresholdX(val); else gate.setThresholdY(val);
            rerange();
            field.setText(format(val));
            if (scatter != null) scatter.setGateOverlay(gate);
            context.gateChanged();
        };
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) commit.run();
        });
    }

    private static TextField thresholdField(double value) {
        TextField field = new TextField(format(value));
        field.setPrefWidth(80);
        field.setMinWidth(Region.USE_PREF_SIZE);
        field.getStyleClass().add("fp-mono-field");
        return field;
    }

    private static HBox growRow(Slider slider, TextField field) {
        HBox row = new HBox(8, slider, field);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.3f", value);
    }
}
