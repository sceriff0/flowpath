package qupath.ext.flowpath.analysis.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.Objects;

/**
 * A control strip bound to exactly one {@link PlotCanvas}: the two axis remedies — log scale
 * and percentile clipping — a user can turn on independently or together, plus the "top values
 * clipped" note that appears only when the current axis actually cut something off.
 * <p>
 * <b>The canvas is the single owner of {@link ScaleOptions}; this class stores no copy of its
 * own.</b> Every widget change reads the canvas's current {@link PlotCanvas#scaleOptions()},
 * derives one field's new value with {@link ScaleOptions#withLog}/{@link
 * ScaleOptions#withClip}/{@link ScaleOptions#withPercentile}, and writes the result straight
 * back with {@link PlotCanvas#setScaleOptions}. Keeping a second field here — say, a cached
 * {@code boolean log} updated alongside the checkbox — would let this strip and its canvas
 * disagree about what is actually plotted, which is exactly the kind of two-owners-of-one-fact
 * bug {@code CLAUDE.md} keeps a list of. It is also what makes the setting per plot: each tab
 * constructs its own {@code PlotControls} over its own canvas (see {@link AnalysisPane}), so
 * ticking "Log scale" on the Composition tab has nowhere to leak into Marker Positivity's own
 * canvas and its own {@link ScaleOptions}.
 * <p>
 * <b>The "clipped" label reflects the last paint, not the toggle.</b> {@link ScaleOptions#clip}
 * being on only says the user asked for a cap; whether that cap actually cut a bar off is a
 * fact about the data, resolved by {@link AxisScale#of} — a percentile candidate that lands on
 * zero or on the data's own maximum changes nothing (see that method's javadoc), and the label
 * would be lying if it lit up anyway. This binds straight to {@link
 * PlotCanvas#anyClippedProperty()}, which the canvas recomputes on every paint, so the label
 * tracks the canvas's actual output rather than the checkbox's own state.
 */
public final class PlotControls extends HBox {

    private static final double SPACING = 8;
    private static final double PADDING = 6;
    private static final double SPINNER_WIDTH = 70;

    private final PlotCanvas canvas;

    private final CheckBox logToggle = new CheckBox("Log scale");
    private final CheckBox clipToggle = new CheckBox("Clip outliers");
    private final Spinner<Double> percentileSpinner = new Spinner<>(50.0, 100.0, 95.0, 0.5);
    private final Label pctLabel = new Label("pct");
    private final Label clippedLabel = new Label("— top values clipped");

    public PlotControls(PlotCanvas canvas) {
        super(SPACING);
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        setPadding(new Insets(PADDING));
        setAlignment(Pos.CENTER_LEFT);

        ScaleOptions initial = canvas.scaleOptions();
        logToggle.setSelected(initial.log());
        clipToggle.setSelected(initial.clip());
        percentileSpinner.getValueFactory().setValue(initial.percentile());
        percentileSpinner.setPrefWidth(SPINNER_WIDTH);
        // Meaningless until clipping is on -- ScaleOptions itself still validates the range
        // regardless (see its own javadoc), but there is nothing for a percentile to do here
        // until the toggle beside it is ticked, so the spinner starts disabled to match.
        percentileSpinner.setDisable(!initial.clip());

        logToggle.setTooltip(new Tooltip(
                "Plot counts on a log10 axis, so small populations stay visible beside a large one."));
        clipToggle.setTooltip(new Tooltip(
                "Cap the axis at a percentile of the bar values, so one huge population does not "
                        + "squash the rest. Clipped bars are drawn to the top and marked."));

        logToggle.selectedProperty().addListener((obs, was, log) ->
                canvas.setScaleOptions(canvas.scaleOptions().withLog(log)));
        clipToggle.selectedProperty().addListener((obs, was, clip) -> {
            percentileSpinner.setDisable(!clip);
            canvas.setScaleOptions(canvas.scaleOptions().withClip(clip));
        });
        percentileSpinner.valueProperty().addListener((obs, was, percentile) -> {
            if (percentile != null) {
                canvas.setScaleOptions(canvas.scaleOptions().withPercentile(percentile));
            }
        });

        // Bound, not set once: the fact this names ("did the last paint actually clip
        // something") changes on every repaint -- new rows, a new percentile, the clip toggle
        // itself -- and PlotCanvas.anyClippedProperty() is recomputed on every one of them. A
        // one-time read here would go stale the moment the data did.
        clippedLabel.visibleProperty().bind(canvas.anyClippedProperty());
        clippedLabel.managedProperty().bind(clippedLabel.visibleProperty());

        getChildren().addAll(logToggle, clipToggle, percentileSpinner, pctLabel, clippedLabel);
    }

    /** Package-private: exercised directly by this class's own FX test. */
    CheckBox logToggle() {
        return logToggle;
    }

    /** As {@link #logToggle()}. */
    CheckBox clipToggle() {
        return clipToggle;
    }

    /** As {@link #logToggle()}. */
    Spinner<Double> percentileSpinner() {
        return percentileSpinner;
    }
}
