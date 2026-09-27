package qupath.ext.flowpath.ui.editor;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.cohort.CohortCurves;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.ui.widgets.HistogramCanvas;
import qupath.ext.flowpath.ui.widgets.SliderUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/** A threshold gate: one channel, a histogram, a threshold slider and a typed threshold. */
final class ThresholdGateEditor extends AbstractGateTypeEditor<GateNode> {

    private final HistogramCanvas histogram = new HistogramCanvas();
    private final Slider slider;
    private final TextField valueField;
    private final Label populationLabel;
    /** "CD8 · Cell · Median (aligned to slide_01)" under the histogram, shown only while the axis is corrected. */
    private final Label alignmentLabel = new Label();

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

        alignmentLabel.getStyleClass().add("fp-muted");
        alignmentLabel.setStyle("-fx-font-size: 9;");
        alignmentLabel.setVisible(false);
        alignmentLabel.setManaged(false);
        // Worked out when shown, so a threshold dragged since the last refresh is not stale.
        Tooltip rawThreshold = new Tooltip();
        rawThreshold.setOnShowing(e -> rawThreshold.setText(
                rawThresholdText(context.slideSetting(), alignment(0), gate.getThreshold())));
        alignmentLabel.setTooltip(rawThreshold);

        branchColorsChanged();

        slider.valueProperty().addListener((obs, old, val) -> {
            if (!accepting()) return;
            gate.setThreshold(val.doubleValue());
            valueField.setText(format(val.doubleValue()));
            histogram.setThreshold(val.doubleValue());
            context.gateChanged();
            syncCut();
            updatePopulationCounts();
        });

        valueField.setOnAction(e -> applyThresholdFromField());
        valueField.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) applyThresholdFromField();
        });

        histogram.setOnThresholdChanged(val -> {
            if (!accepting()) return;
            gate.setThreshold(val);
            // Suppressed: the slider's own listener would report this edit a second time, and on
            // an open review item the second report would read the reference numbers the first
            // one had already put back.
            context.withSuppressedEvents(() -> slider.setValue(val));
            valueField.setText(format(val));
            context.gateChanged();
            syncCut();
            updatePopulationCounts();
        });

        HBox threshRow = new HBox(8, styledLabel("Threshold:", "fp-primary-text"), slider, valueField);
        HBox.setHgrow(slider, Priority.ALWAYS);

        VBox root = new VBox(4,
                channelRows.get(0), modeRow,
                sectionHeader("Histogram"), histogram, flaggedLegend, alignmentLabel, hoverLabel,
                sectionHeader("Threshold"), threshRow, populationLabel);
        refresh();
        syncCut();
        return root;
    }

    /**
     * Re-read the histogram and re-range the slider from the column the engine gates on.
     * Everything — values, clip percentiles, slider range — comes off one {@link MeasuredColumn}
     * handle, so the histogram shows exactly what {@code GatingEngine} compares against —
     * mapped into reference units when this slide is corrected, as the gate's threshold is.
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

        double[] displayValues = inReference(0,
                AxisMath.measuredValues(col.values(), context.roiMask(), context.ancestorMask()));
        // Global per-column clip percentiles, so the same channel+compartment+statistic uses
        // one axis everywhere it appears in the gate tree. When the parent-filtered cells sit
        // outside it, the histogram's "X cells outside clip range" message says so.
        Alignment a = alignment(0);
        double[] window = AxisMath.thresholdWindow(
                a.inverse(col.percentile(gate.getClipPercentileLow())),
                a.inverse(col.percentile(gate.getClipPercentileHigh())),
                displayValues);
        showAlignment(a);

        histogram.setData(displayValues, window[0], window[1]);
        showCohortCurves();
        // Suppressed, so a clamping range move cannot write a corrupted value back to the gate.
        context.withSuppressedEvents(() -> {
            slider.setMin(window[0]);
            slider.setMax(window[1]);
            // Re-pin the step to the new range so threshold "speed" matches the QC sliders
            // whether the column is ~10 wide or ~10000s wide.
            SliderUtils.applyRangeStep(slider);
        });
        // Re-pin the thumb and the field AFTER the range move: Slider.setMin/setMax silently
        // clamp the value, which would leave the gate, the thumb and the field holding three
        // different numbers.
        syncCut();
        updatePopulationCounts();
    }

    /**
     * Draw the cut the pass applies on the open slide ({@link #cutGate}): the histogram colours
     * through that gate and draws its threshold, and the thumb and the field show it — a Manual's
     * number mapped into reference units, the gate's own number otherwise. Under a Skip there is
     * no cut: the bars are grey, and the thumb keeps the gate's reference number.
     */
    private void syncCut() {
        if (isDisposed()) return;
        GateNode cut = cutGate();
        boolean editable = context.cutEditable();
        slider.setDisable(!editable);
        valueField.setDisable(!editable);
        histogram.setDraggable(editable);
        histogram.setUnjudged(cut == null);
        histogram.setGate(cut == null ? gate : cut);
        double t = shownThreshold();
        histogram.setThreshold(t);
        context.withSuppressedEvents(() -> {
            slider.setValue(t);
            valueField.setText(format(t));
        });
    }

    /** The threshold the thumb and field show: {@link #cutGate}'s, or the gate's own under a Skip. */
    private double shownThreshold() {
        GateNode cut = cutGate();
        return (cut == null ? gate : cut).getThreshold();
    }

    @Override
    public void slideSettingChanged() {
        syncCut();
    }

    /**
     * The All slides ridges over the bars, or none; the open slide's entry is highlighted, and the
     * slides the review flagged on this gate are stroked amber and named under the canvas.
     */
    private void showCohortCurves() {
        List<CohortCurves.SlideValues> cohort = context.cohortValues(gate);
        if (cohort.isEmpty()) {
            histogram.clearCohortCurves();
            showFlagged(cohort);
            return;
        }
        int current = -1;
        List<double[]> curves = new ArrayList<>(cohort.size());
        for (int i = 0; i < cohort.size(); i++) {
            curves.add(cohort.get(i).x());
            if (cohort.get(i).current()) current = i;
        }
        histogram.setCohortCurves(curves, current);
        // After the curves, which clear the flags; marking a ridge never re-bins it.
        histogram.setFlaggedCurves(showFlagged(cohort));
    }

    /**
     * The number the pass applies on the open slide, in its raw units: a {@code Manual} there is
     * applied as-is (the banner shows the same number), a {@code Skip} applies none, and
     * otherwise it is the reference threshold through the slide's correction.
     */
    static String rawThresholdText(SlideSetting setting, Alignment alignment, double referenceThreshold) {
        if (setting instanceof SlideSetting.Manual manual && manual.values().axisCount() == 1) {
            return String.format(Locale.US, "On this slide the threshold is %.4f (raw, adjusted on this slide)",
                    manual.values().axis(0)[0]);
        }
        if (setting instanceof SlideSetting.Skip) {
            return "This gate is skipped on this slide — its cells are unmeasured";
        }
        return String.format(Locale.US, "On this slide the threshold is %.4f (raw)", alignment.apply(referenceThreshold));
    }

    /** The aligned-units caption (its tooltip gives this slide's raw threshold); hidden when uncorrected. */
    private void showAlignment(Alignment a) {
        boolean corrected = a.kind() != Alignment.Kind.IDENTITY && context.referenceName() != null;
        alignmentLabel.setVisible(corrected);
        alignmentLabel.setManaged(corrected);
        alignmentLabel.setText(corrected ? axisLabel(0) : "");
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
        DoubleUnaryOperator f = remapAcrossColumns(0);
        double oldThreshold = gate.getThreshold();
        return () -> gate.setThreshold(f.applyAsDouble(oldThreshold));
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
        // The number the field shows, not the gate's own: under a Manual they differ, and a
        // focus loss must not write the shown number back as a reference edit.
        double current = shownThreshold();
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
        syncCut();
        updatePopulationCounts();
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.4f", value);
    }
}
