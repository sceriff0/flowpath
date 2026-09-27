package qupath.ext.flowpath.ui.editor;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.ui.widgets.SliderUtils;

import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

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

    /**
     * Both axes are pinned before this runs (see {@link AbstractGateTypeEditor#build}). The
     * slider span below is the pinned column's clip window; ranged before the pin, a gate whose
     * stored statistic the file lacks resolved to no column and opened on [-5, 5].
     */
    @Override
    Node buildControls(List<HBox> channelRows, List<ComboBox<String>> channelCombos) {
        sliderX.setPrefWidth(300);
        sliderY.setPrefWidth(300);
        SliderUtils.enableScrollControl(sliderX);
        SliderUtils.enableScrollControl(sliderY);

        rerange();

        sliderX.valueProperty().addListener((obs, old, val) -> {
            if (!accepting()) return;
            gate.setThresholdX(val.doubleValue());
            valX.setText(format(val.doubleValue()));
            context.gateChanged();
            syncCut();
        });
        sliderY.valueProperty().addListener((obs, old, val) -> {
            if (!accepting()) return;
            gate.setThresholdY(val.doubleValue());
            valY.setText(format(val.doubleValue()));
            context.gateChanged();
            syncCut();
        });

        // Typed entry, for a threshold the slider's resolution cannot land on exactly.
        // A value outside the visible window is honoured and the slider widens to show it.
        wireField(valX, true);
        wireField(valY, false);

        VBox root = new VBox(4,
                channelRows.get(0), channelRows.get(1),
                modeRow,
                sectionHeader("Threshold X"), growRow(sliderX, valX),
                sectionHeader("Threshold Y"), growRow(sliderY, valY));

        if (hasPlottableAxes()) {
            root.getChildren().addAll(sectionHeader("Scatter Plot"), newScatter());
        }
        syncCut();
        return root;
    }

    /** New data: re-read the plot and re-range the sliders to the window it now shows. */
    @Override
    public void refresh() {
        if (isDisposed()) return;
        redrawScatter();
        rerange();
        syncCut();
    }

    @Override
    public void slideSettingChanged() {
        rerange();
        syncCut();
    }

    /**
     * Draw the crosshair the pass applies on the open slide ({@link #cutGate}): the scatter
     * colours through that gate, and the thumbs and fields show its numbers — a Manual's mapped
     * into reference units, the gate's own otherwise. Under a Skip there is no crosshair and the
     * dots are grey; the thumbs keep the gate's reference numbers.
     */
    private void syncCut() {
        if (isDisposed()) return;
        GateNode cut = cutGate();
        if (scatter != null) {
            scatter.setUnjudged(cut == null);
            scatter.setGateOverlay(cut == null ? gate : cut);
        }
        double x = shownThreshold(true);
        double y = shownThreshold(false);
        context.withSuppressedEvents(() -> {
            sliderX.setValue(x);
            sliderY.setValue(y);
            valX.setText(format(x));
            valY.setText(format(y));
        });
    }

    /** An axis threshold as the thumb and field show it: {@link #cutGate}'s, or the gate's own under a Skip. */
    private double shownThreshold(boolean xAxis) {
        GateNode cut = cutGate();
        QuadrantGate q = cut instanceof QuadrantGate c ? c : gate;
        return xAxis ? q.getThresholdX() : q.getThresholdY();
    }

    /**
     * Range both sliders to their axis' visible window, widened to hold the threshold.
     * Widen before narrowing so setMin never crosses the current max, and re-pin the value
     * afterwards: Slider clamps silently on a range move.
     */
    private void rerange() {
        context.withSuppressedEvents(() -> {
            rerange(sliderX, 0, shownThreshold(true));
            rerange(sliderY, 1, shownThreshold(false));
        });
    }

    private void rerange(Slider slider, int slot, double threshold) {
        double[] span = AxisMath.quadrantSliderSpan(clipSpan(slot), threshold);
        slider.setMin(Math.min(slider.getMin(), span[0]));
        slider.setMax(span[1]);
        slider.setMin(span[0]);
        slider.setValue(threshold);
        SliderUtils.applyRangeStep(slider);
    }

    @Override
    Runnable captureForRemap() {
        DoubleUnaryOperator fx = remapAcrossColumns(0);
        DoubleUnaryOperator fy = remapAcrossColumns(1);
        return () -> {
            gate.setThresholdX(fx.applyAsDouble(gate.getThresholdX()));
            gate.setThresholdY(fy.applyAsDouble(gate.getThresholdY()));
        };
    }

    /** Commit a typed threshold on Enter or focus loss; revert on a bad number. */
    private void wireField(TextField field, boolean xAxis) {
        Runnable commit = () -> {
            if (!accepting()) return;
            // The number the field shows, not the gate's own: under a Manual they differ, and a
            // focus loss must not write the shown number back as a reference edit.
            double current = shownThreshold(xAxis);
            // Still the gate's own value in the field's rendering: not an edit. Parsed, it
            // is the rounded value, which a dragged threshold is not equal to.
            if (format(current).equals(field.getText())) return;
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
            field.setText(format(val));
            context.gateChanged();
            rerange();
            syncCut();
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
