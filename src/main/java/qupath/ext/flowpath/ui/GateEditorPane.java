package qupath.ext.flowpath.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.collections.FXCollections;
import javafx.util.StringConverter;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.ValueMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Right-side editor panel for configuring a single gate node.
 * Swaps controls based on gate type: threshold shows histogram + slider,
 * quadrant shows 2-channel controls, boolean shows operation picker, etc.
 */
public class GateEditorPane extends VBox {

    // --- Shared controls ---
    private final Label gateTypeLabel;
    private final Spinner<Double> clipLowSpinner;
    private final Spinner<Double> clipHighSpinner;
    private final CheckBox excludeOutliersBox;
    private final VBox gateSpecificArea;
    private final VBox branchNamesArea;
    private final VBox actionButtonArea;

    // --- Shared threshold/quadrant controls (reused across gate types) ---
    private final ComboBox<String> channelCombo;
    /**
     * The accent for a number FlowPath computes rather than reads. Distinct from the
     * gate-type blue and from the warning orange, both of which already mean something
     * else in this pane.
     */
    private static final String COMPUTED_HERE_COLOR = "#7fc4a8";

    /** Muted, matching the pane's other unavailable controls. */
    private static final String UNAVAILABLE_COLOR = "#666666";

    private static final String ZSCORE_AVAILABLE_TOOLTIP =
            "Standardise against this column's own distribution.\n"
            + "Computed by FlowPath over the cells currently loaded and filtered \u2014 "
            + "not a value read from the export.";

    private final ToggleGroup modeGroup;
    /**
     * The "Values" selector. One row, rebuilt from {@link ValueMode#availableFor} whenever
     * the gate or its resolved columns change, and reused by all three editors because only
     * one is on screen at a time.
     */
    private final HBox modeRow;
    /** What the row currently offers, in the order it offers them. */
    private List<ValueMode> currentModes = List.of();
    /** The mode the gate is in, so a selection change knows what it is changing <em>from</em>. */
    private ValueMode currentMode;

    private GateNode currentNode;
    private CellIndex cellIndex;
    private MarkerStats markerStats;
    private CompartmentCapability compartmentCapability;
    private boolean[] roiMask;
    private boolean[] ancestorMask;
    private boolean suppressEvents = false;
    // Non-null only when a 2D gate editor (quadrant/polygon/rect/ellipse) is active.
    // Used by shared clip controls to update axis range. Cleared in setGateNode().
    private ScatterPlotCanvas currentScatter;
    // Non-null only when a threshold gate editor is active.
    // Created fresh in buildThresholdEditor(), cleared in other editor builders.
    private HistogramCanvas currentHistogram;
    private Slider currentThresholdSlider;
    private TextField currentThresholdField;
    private Label currentPopulationLabel;
    private Label clipInfoLabel;

    private Consumer<GateNode> onNodeChanged;
    private Runnable onAddToPositive;
    private Runnable onAddToNegative;
    private IntConsumer onAddToBranch;
    private Runnable onRemoveGate;
    private java.util.function.BiConsumer<GateNode, GateNode> onReplaceGate;

    public GateEditorPane() {
        setSpacing(8);
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #2a2a2a;");

        // Gate type indicator
        gateTypeLabel = new Label("No gate selected");
        gateTypeLabel.setStyle("-fx-text-fill: #80b0d0; -fx-font-size: 11; -fx-font-weight: bold;");

        // --- Threshold-specific controls (always created, shown/hidden as needed) ---
        channelCombo = new ComboBox<>();
        channelCombo.setPrefWidth(200);
        channelCombo.setTooltip(new Tooltip("Select the marker channel for this gate"));

        modeGroup = new ToggleGroup();
        // Built empty; syncModeSelection fills it from what the file turns out to carry.
        modeRow = new HBox(12, new Label("Values:") {{ setStyle("-fx-text-fill: white;"); }});
        modeRow.setAlignment(Pos.CENTER_LEFT);
        modeGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (suppressEvents || currentNode == null || val == null) return;
            if (val.getUserData() instanceof ValueMode selected) onModeSelected(selected);
        });

        // --- Shared: Outlier Clipping ---
        clipLowSpinner = new Spinner<>(0.0, 50.0, 1.0, 0.5);
        clipLowSpinner.setPrefWidth(75);
        clipLowSpinner.setEditable(true);
        clipHighSpinner = new Spinner<>(50.0, 100.0, 99.0, 0.5);
        clipHighSpinner.setPrefWidth(75);
        clipHighSpinner.setEditable(true);
        excludeOutliersBox = new CheckBox("Exclude outliers");
        excludeOutliersBox.setStyle("-fx-text-fill: white;");
        excludeOutliersBox.setTooltip(new Tooltip(
            "When enabled, cells with marker values outside the clip percentile range\n" +
            "are classified as 'Excluded' in QuPath and flagged Outlier=True in the CSV.\n" +
            "Their would-have-been phenotype is still written to the CSV but they don't\n" +
            "contribute to branch counts.\n" +
            "Percentiles are computed from all quality-passing cells, not per gate population."));

        clipLowSpinner.valueProperty().addListener((obs, old, val) -> {
            if (!suppressEvents && currentNode != null) {
                double clamped = Math.min(val, clipHighSpinner.getValue() - 0.5);
                if (clamped != val) { clipLowSpinner.getValueFactory().setValue(clamped); return; }
                currentNode.setClipPercentileLow(val);
                updateHistogram();
                if (currentScatter != null && markerStats != null) {
                    applyAxisRangeFor(currentScatter, currentNode);
                }
                fireNodeChanged();
            }
        });
        clipHighSpinner.valueProperty().addListener((obs, old, val) -> {
            if (!suppressEvents && currentNode != null) {
                double clamped = Math.max(val, clipLowSpinner.getValue() + 0.5);
                if (clamped != val) { clipHighSpinner.getValueFactory().setValue(clamped); return; }
                currentNode.setClipPercentileHigh(val);
                updateHistogram();
                if (currentScatter != null && markerStats != null) {
                    applyAxisRangeFor(currentScatter, currentNode);
                }
                fireNodeChanged();
            }
        });
        excludeOutliersBox.selectedProperty().addListener((obs, old, val) -> {
            if (!suppressEvents && currentNode != null) {
                currentNode.setExcludeOutliers(val);
                fireNodeChanged();
            }
        });

        Label clipInfoLabel = new Label("Percentiles based on all cells, not this gate's population");
        clipInfoLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 9; -fx-font-style: italic;");
        clipInfoLabel.setVisible(false);
        clipInfoLabel.managedProperty().bind(clipInfoLabel.visibleProperty());
        this.clipInfoLabel = clipInfoLabel;

        HBox clipRow = new HBox(6,
            new Label("Clip:") {{ setStyle("-fx-text-fill: white;"); }},
            clipLowSpinner, new Label("% to") {{ setStyle("-fx-text-fill: white;"); }},
            clipHighSpinner, new Label("%") {{ setStyle("-fx-text-fill: white;"); }},
            excludeOutliersBox);

        // Swappable areas
        gateSpecificArea = new VBox(4);
        branchNamesArea = new VBox(4);
        actionButtonArea = new VBox(4);

        // The channel pickers are wired per gate, in the builders, through
        // wireChannelCombo — a channel change is a decision about one axis, and each
        // builder knows only which slot its combo drives and what to redraw afterwards.

        // Assemble
        getChildren().addAll(
            gateTypeLabel,
            gateSpecificArea,
            createSectionHeader("Outlier Clipping"), clipRow, clipInfoLabel,
            new Separator(),
            branchNamesArea,
            new Separator(),
            actionButtonArea
        );

        setDisabled(true);
    }

    /**
     * Populate the editor with a gate node's current values.
     * Rebuilds the gate-specific UI section based on gate type.
     */

    /**
     * Move the current gate into {@code selected}.
     * <p>
     * Two different transitions hide behind one selector, and telling them apart is the
     * whole job:
     * <ul>
     *   <li><b>Same column, different space</b> — Raw &harr; the computed z-score. The gate
     *       keeps reading the same measurement; only the units it is written in change, so
     *       thresholds and shapes are <em>converted</em> through that column's own mean and
     *       standard deviation.</li>
     *   <li><b>A different column</b> — anything that changes the normalisation suffix, such
     *       as Raw to MIRAGE's {@code " Z"}. A bare threshold does not carry across columns
     *       (a Sum is ~100x the corresponding Mean), which is exactly what
     *       {@link #applySignalChange} exists for: it re-maps the threshold to the same
     *       percentile of the new column, so the gate lands on the same cells rather than
     *       collapsing to all-positive or all-negative.</li>
     * </ul>
     * Conflating the two was the old defect in miniature. The previous radio could only
     * express the first, so reaching MIRAGE's already-standardised column meant using the
     * Statistic dropdown instead — a different control, which did not know a mode had been
     * chosen at all and silently disabled this one.
     */
    private void onModeSelected(ValueMode selected) {
        GateNode node = currentNode;
        if (node == null || selected == null) return;
        ValueMode previous = currentMode;
        currentMode = selected;

        boolean sameColumn = previous != null
                && previous.normalisation().equals(selected.normalisation());
        if (!sameColumn) {
            // A different measurement column. applySignalChange re-maps by percentile and
            // re-syncs this row afterwards, so nothing more is needed here.
            applySignalChange(() -> selected.applyTo(node));
            return;
        }
        if (previous != null && previous.computed() == selected.computed()) return;

        selected.applyTo(node);
        convertGateSpace(node, selected.computed());
    }

    /**
     * Rewrite {@code node}'s thresholds and shapes between raw and z-score space, against
     * the same resolved columns the engine compares on.
     * <p>
     * Only valid when the column itself is unchanged — see {@link #onModeSelected}. A
     * column with no spread is left alone rather than converted through a zero standard
     * deviation.
     */
    private void convertGateSpace(GateNode node, boolean toZScore) {
        if (node == null) return;
            if (isThresholdGate(node)) {
                // Transform threshold value between coordinate spaces, against the
                // same resolved column the engine compares on. updateHistogram then
                // re-ranges the axis and re-pins the slider/field to the new value,
                // so the gate lands on the same cells and stays freely draggable.
                MeasuredColumn col = thresholdColumn(node);
                if (col != null && col.hasSpread()) {
                    double oldVal = node.getThreshold();
                    node.setThreshold(toZScore
                            ? col.toZScore(oldVal)
                            : col.fromZScore(oldVal));
                }
                updateHistogram();
            } else if (node instanceof QuadrantGate qg) {
                // Transform quadrant thresholds between coordinate spaces
                MeasuredColumn colX = columnX(qg);
                MeasuredColumn colY = columnY(qg);
                if (colX != null && colY != null && colX.hasSpread() && colY.hasSpread()) {
                    if (toZScore) {
                        qg.setThresholdX(colX.toZScore(qg.getThresholdX()));
                        qg.setThresholdY(colY.toZScore(qg.getThresholdY()));
                    } else {
                        qg.setThresholdX(colX.fromZScore(qg.getThresholdX()));
                        qg.setThresholdY(colY.fromZScore(qg.getThresholdY()));
                    }
                }
                fireNodeChanged();
                Platform.runLater(() -> setGateNode(node));
                return;
            } else if (node instanceof Region2DGate) {
                // Transform shape coordinates between raw and z-score space
                MeasuredColumn colX = columnX(node);
                MeasuredColumn colY = columnY(node);
                if (colX != null && colY != null && colX.hasSpread() && colY.hasSpread()) {
                    if (node instanceof PolygonGate pg && !pg.getVertices().isEmpty()) {
                        List<double[]> transformed = new ArrayList<>();
                        for (double[] v : pg.getVertices()) {
                            transformed.add(new double[]{
                                    toZScore ? colX.toZScore(v[0]) : colX.fromZScore(v[0]),
                                    toZScore ? colY.toZScore(v[1]) : colY.fromZScore(v[1])
                            });
                        }
                        pg.setVertices(transformed);
                    } else if (node instanceof RectangleGate rg
                            && rg.getMaxX() - rg.getMinX() > 1e-10) {
                        if (toZScore) {
                            rg.setMinX(colX.toZScore(rg.getMinX()));
                            rg.setMaxX(colX.toZScore(rg.getMaxX()));
                            rg.setMinY(colY.toZScore(rg.getMinY()));
                            rg.setMaxY(colY.toZScore(rg.getMaxY()));
                        } else {
                            rg.setMinX(colX.fromZScore(rg.getMinX()));
                            rg.setMaxX(colX.fromZScore(rg.getMaxX()));
                            rg.setMinY(colY.fromZScore(rg.getMinY()));
                            rg.setMaxY(colY.fromZScore(rg.getMaxY()));
                        }
                    } else if (node instanceof EllipseGate eg && eg.getRadiusX() > 1e-10) {
                        double stdX = colX.std();
                        double stdY = colY.std();
                        if (toZScore) {
                            eg.setCenterX(colX.toZScore(eg.getCenterX()));
                            eg.setCenterY(colY.toZScore(eg.getCenterY()));
                            eg.setRadiusX(eg.getRadiusX() / stdX);
                            eg.setRadiusY(eg.getRadiusY() / stdY);
                        } else {
                            eg.setCenterX(colX.fromZScore(eg.getCenterX()));
                            eg.setCenterY(colY.fromZScore(eg.getCenterY()));
                            eg.setRadiusX(eg.getRadiusX() * stdX);
                            eg.setRadiusY(eg.getRadiusY() * stdY);
                        }
                    }
                } else {
                    // Can't transform — clear shape as fallback
                    if (node instanceof PolygonGate pg) pg.setVertices(List.of());
                    else if (node instanceof RectangleGate rg) { rg.setMinX(0); rg.setMaxX(0); rg.setMinY(0); rg.setMaxY(0); }
                    else if (node instanceof EllipseGate eg) { eg.setCenterX(0); eg.setCenterY(0); eg.setRadiusX(0); eg.setRadiusY(0); }
                }
                fireNodeChanged();
                Platform.runLater(() -> setGateNode(node));
                return;
            }
            fireNodeChanged();
    }

    public void setGateNode(GateNode node) {
        this.currentNode = node;
        this.currentScatter = null;
        this.currentHistogram = null;
        this.currentThresholdSlider = null;
        this.currentThresholdField = null;
        this.currentPopulationLabel = null;
        if (node == null) {
            withSuppressedEvents(() -> setDisabled(true));
            gateTypeLabel.setText("No gate selected");
            gateSpecificArea.getChildren().clear();
            Label hint = new Label("Select a gate from the tree to edit it,\nor click '+ Add Root Gate' to create one.");
            hint.setStyle("-fx-text-fill: #888888; -fx-font-size: 11;");
            hint.setWrapText(true);
            gateSpecificArea.getChildren().add(hint);
            branchNamesArea.getChildren().clear();
            actionButtonArea.getChildren().clear();
            return;
        }
        withSuppressedEvents(() -> {
            setDisabled(false);

            // Shared controls
            clipLowSpinner.getValueFactory().setValue(node.getClipPercentileLow());
            clipHighSpinner.getValueFactory().setValue(node.getClipPercentileHigh());
            excludeOutliersBox.setSelected(node.isExcludeOutliers());

            // Gate type label
            String typeDisplay = switch (node.getGateType()) {
                case "threshold" -> "Threshold Gate";
                case "quadrant" -> "Quadrant Gate";
                case "polygon" -> "Polygon Gate";
                case "rectangle" -> "Rectangle Gate";
                case "ellipse" -> "Ellipse Gate";
                default -> "Gate";
            };
            gateTypeLabel.setText(typeDisplay);

            // Rebuild gate-specific area
            gateSpecificArea.getChildren().clear();
            if (node instanceof QuadrantGate qg) {
                buildQuadrantEditor(qg);
            } else if (node instanceof Region2DGate region2d) {
                build2DEditor(region2d);
            } else {
                buildThresholdEditor(node);
            }

            // Rebuild branch names/colors
            buildBranchNamesEditor(node);

            // Rebuild action buttons
            buildActionButtons(node);
        });

        if (isThresholdGate(node)) {
            updateHistogram();
        }
    }

    // ---- Gate-type-specific editor builders ----

    private void buildThresholdEditor(GateNode node) {
        Label chLabel = new Label("Channel:");
        chLabel.setStyle("-fx-text-fill: white;");
        HBox channelRow = new HBox(8, chLabel, channelCombo);
        channelCombo.setValue(node.getChannel());
        addSignalControls(channelRow, GateAxis.of(node, 0));
        wireChannelCombo(channelCombo, node, 0);

        syncModeSelection(node);

        // Create fresh controls for this gate (local-creation pattern, like quadrant editor)
        HistogramCanvas histogram = new HistogramCanvas();
        Label hoverLabel = new Label(" ");
        hoverLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 9;");
        histogram.setOnMouseHover(val -> hoverLabel.setText(String.format(Locale.US, "Value: %.4f", val)));

        Slider slider = new Slider(-5, 5, node.getThreshold());
        slider.setPrefWidth(300);
        SliderUtils.makeRangeFriendly(slider);
        TextField valueField = new TextField(String.format(Locale.US, "%.4f", node.getThreshold()));
        valueField.setPrefWidth(80);
        valueField.setStyle("-fx-text-fill: white; -fx-font-family: monospace; -fx-background-color: #3a3a3a;");

        Label populationLabel = new Label("Positive: -- | Negative: --");
        populationLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");

        histogram.setGate(node);
        histogram.setPosColor(ColorUtils.intToColor(node.getPositiveColor()));
        histogram.setNegColor(ColorUtils.intToColor(node.getNegativeColor()));

        // Store references for external updates (updateHistogram, etc.)
        currentHistogram = histogram;
        currentThresholdSlider = slider;
        currentThresholdField = valueField;
        currentPopulationLabel = populationLabel;

        // Wire slider listener
        slider.valueProperty().addListener((obs, old, val) -> {
            if (!suppressEvents && currentNode != null) {
                currentNode.setThreshold(val.doubleValue());
                valueField.setText(String.format(Locale.US, "%.4f", val.doubleValue()));
                histogram.setThreshold(val.doubleValue());
                fireNodeChanged();
                updatePopulationCounts();
            }
        });

        // Wire text field
        valueField.setOnAction(e -> applyThresholdFromField());
        valueField.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) applyThresholdFromField();
        });

        // Wire histogram drag-threshold
        histogram.setOnThresholdChanged(val -> {
            if (!suppressEvents && currentNode != null) {
                currentNode.setThreshold(val);
                slider.setValue(val);
                valueField.setText(String.format(Locale.US, "%.4f", val));
                fireNodeChanged();
                updatePopulationCounts();
            }
        });

        HBox threshRow = new HBox(8,
            new Label("Threshold:") {{ setStyle("-fx-text-fill: white;"); }},
            slider, valueField);
        HBox.setHgrow(slider, Priority.ALWAYS);

        gateSpecificArea.getChildren().addAll(
            channelRow, modeRow,
            createSectionHeader("Histogram"), histogram, hoverLabel,
            createSectionHeader("Threshold"), threshRow, populationLabel
        );
    }

    private void buildQuadrantEditor(QuadrantGate gate) {
        currentHistogram = null;
        currentThresholdSlider = null;
        currentThresholdField = null;
        currentPopulationLabel = null;
        Label chXLabel = new Label("Channel X:");
        chXLabel.setStyle("-fx-text-fill: white;");
        ComboBox<String> chXCombo = new ComboBox<>(channelCombo.getItems());
        chXCombo.setValue(gate.getChannelX());
        chXCombo.setPrefWidth(150);

        Label chYLabel = new Label("Channel Y:");
        chYLabel.setStyle("-fx-text-fill: white;");
        ComboBox<String> chYCombo = new ComboBox<>(channelCombo.getItems());
        chYCombo.setValue(gate.getChannelY());
        chYCombo.setPrefWidth(150);

        // One handler per axis, wired before the plot is built — this method used to wire
        // each combo twice, early and again after the scatter existed, with a longer body
        // the second time. Which one you got depended on whether the gate's channels were
        // in the index, and only one of the two re-resolved the axis.
        wireChannelCombo(chXCombo, gate, 0);
        wireChannelCombo(chYCombo, gate, 1);

        // Compute slider ranges from data (z-score or raw).
        // For child gates with ancestor mask, use the filtered data range for proper centering.
        double sliderMinX = -5, sliderMaxX = 5, sliderMinY = -5, sliderMaxY = 5;
        if (hasPlottableAxes(gate)) {
            double[][] fData = plotData(gate);
            if (fData[0].length > 0) {
                double dMinX = Double.MAX_VALUE, dMaxX = -Double.MAX_VALUE;
                double dMinY = Double.MAX_VALUE, dMaxY = -Double.MAX_VALUE;
                for (int i = 0; i < fData[0].length; i++) {
                    if (!Double.isNaN(fData[0][i]) && !Double.isNaN(fData[1][i])) {
                        dMinX = Math.min(dMinX, fData[0][i]);
                        dMaxX = Math.max(dMaxX, fData[0][i]);
                        dMinY = Math.min(dMinY, fData[1][i]);
                        dMaxY = Math.max(dMaxY, fData[1][i]);
                    }
                }
                if (dMaxX > dMinX) { sliderMinX = dMinX; sliderMaxX = dMaxX; }
                if (dMaxY > dMinY) { sliderMinY = dMinY; sliderMaxY = dMaxY; }
            }
        }
        if (sliderMinX >= sliderMaxX) { sliderMinX = -5; sliderMaxX = 5; }
        if (sliderMinY >= sliderMaxY) { sliderMinY = -5; sliderMaxY = 5; }

        Slider sliderX = new Slider(sliderMinX, sliderMaxX, Math.max(sliderMinX, Math.min(sliderMaxX, gate.getThresholdX())));
        SliderUtils.makeRangeFriendly(sliderX);
        Label valX = new Label(String.format(Locale.US, "%.3f", gate.getThresholdX()));
        valX.setStyle("-fx-text-fill: white; -fx-font-family: monospace;");

        Slider sliderY = new Slider(sliderMinY, sliderMaxY, Math.max(sliderMinY, Math.min(sliderMaxY, gate.getThresholdY())));
        SliderUtils.makeRangeFriendly(sliderY);
        Label valY = new Label(String.format(Locale.US, "%.3f", gate.getThresholdY()));
        valY.setStyle("-fx-text-fill: white; -fx-font-family: monospace;");

        final ScatterPlotCanvas[] scatterRef = {null};

        sliderX.valueProperty().addListener((obs, old, val) -> {
            if (!suppressEvents) {
                gate.setThresholdX(val.doubleValue());
                valX.setText(String.format(Locale.US, "%.3f", val.doubleValue()));
                if (scatterRef[0] != null) scatterRef[0].setGateOverlay(gate);
                fireNodeChanged();
            }
        });

        sliderY.valueProperty().addListener((obs, old, val) -> {
            if (!suppressEvents) {
                gate.setThresholdY(val.doubleValue());
                valY.setText(String.format(Locale.US, "%.3f", val.doubleValue()));
                if (scatterRef[0] != null) scatterRef[0].setGateOverlay(gate);
                fireNodeChanged();
            }
        });

        syncModeSelection(gate);

        HBox rowX = new HBox(8, chXLabel, chXCombo);
        addSignalControls(rowX, GateAxis.of(gate, 0));
        HBox rowY = new HBox(8, chYLabel, chYCombo);
        addSignalControls(rowY, GateAxis.of(gate, 1));

        gateSpecificArea.getChildren().addAll(
            rowX, rowY,
            modeRow,
            createSectionHeader("Threshold X"), new HBox(8, sliderX, valX),
            createSectionHeader("Threshold Y"), new HBox(8, sliderY, valY)
        );

        // Add scatter plot if data is available
        if (hasPlottableAxes(gate)) {
            double[][] filtered = plotData(gate);
            ScatterPlotCanvas scatter = new ScatterPlotCanvas();
            scatter.setData(filtered[0], filtered[1], gate.getChannelX(), gate.getChannelY());
            scatter.setGateOverlay(gate);
            if (markerStats != null) {
                applyAxisRangeFor(scatter, gate);
            }
            applyBranchColorsToScatter(scatter, gate);
            scatterRef[0] = scatter;
            this.currentScatter = scatter;
            gateSpecificArea.getChildren().addAll(createSectionHeader("Scatter Plot"), scatter);
        }
    }

    private void build2DEditor(Region2DGate node) {
        currentHistogram = null;
        currentThresholdSlider = null;
        currentThresholdField = null;
        currentPopulationLabel = null;
        // Channel pickers
        Label chXLabel = new Label("Channel X:");
        chXLabel.setStyle("-fx-text-fill: white;");
        ComboBox<String> chXCombo = new ComboBox<>(channelCombo.getItems());
        chXCombo.setPrefWidth(150);
        Label chYLabel = new Label("Channel Y:");
        chYLabel.setStyle("-fx-text-fill: white;");
        ComboBox<String> chYCombo = new ComboBox<>(channelCombo.getItems());
        chYCombo.setPrefWidth(150);

        chXCombo.setValue(node.getChannelX());
        chYCombo.setValue(node.getChannelY());

        // Drawing toolbar — shape picker
        ToggleGroup toolGroup = new ToggleGroup();
        ToggleButton polygonBtn = new ToggleButton("Polygon");
        polygonBtn.setToggleGroup(toolGroup);
        ToggleButton rectBtn = new ToggleButton("Rectangle");
        rectBtn.setToggleGroup(toolGroup);
        ToggleButton ellipseBtn = new ToggleButton("Ellipse");
        ellipseBtn.setToggleGroup(toolGroup);
        Button clearShapeBtn = new Button("Clear Shape");
        clearShapeBtn.setOnAction(e -> {
            if (node instanceof PolygonGate pg) {
                pg.setVertices(List.of());
            } else if (node instanceof RectangleGate rg) {
                rg.setMinX(0); rg.setMaxX(0); rg.setMinY(0); rg.setMaxY(0);
            } else if (node instanceof EllipseGate eg) {
                eg.setCenterX(0); eg.setCenterY(0); eg.setRadiusX(0); eg.setRadiusY(0);
            }
            // Rebuild the editor to show fresh scatter (no overlay)
            setGateNode(node);
            fireNodeChanged();
        });
        HBox drawToolbar = new HBox(4, polygonBtn, rectBtn, ellipseBtn, clearShapeBtn);

        if (node instanceof PolygonGate) polygonBtn.setSelected(true);
        else if (node instanceof RectangleGate) rectBtn.setSelected(true);
        else if (node instanceof EllipseGate) ellipseBtn.setSelected(true);

        syncModeSelection(node);

        HBox rowX = new HBox(8, chXLabel, chXCombo);
        addSignalControls(rowX, GateAxis.of(node, 0));
        HBox rowY = new HBox(8, chYLabel, chYCombo);
        addSignalControls(rowY, GateAxis.of(node, 1));

        // Wired before the plot is built: a gate whose channels this image does not carry
        // still has to accept a channel change — that is the only way to point it at one
        // the image does carry. The old handler bailed out when either combo was blank.
        wireChannelCombo(chXCombo, node, 0);
        wireChannelCombo(chYCombo, node, 1);

        gateSpecificArea.getChildren().addAll(
            rowX, rowY,
            modeRow,
            createSectionHeader("Shape"), drawToolbar
        );

        // Scatter plot if data available
        if (hasPlottableAxes(node)) {
            {
                ScatterPlotCanvas scatter = new ScatterPlotCanvas();
                double[][] filtered = plotData(node);
                scatter.setData(filtered[0], filtered[1], node.getChannelX(), node.getChannelY());
                if (markerStats != null) {
                    applyAxisRangeFor(scatter, node);
                }
                this.currentScatter = scatter;

                // Apply branch colors to scatter plot
                applyBranchColorsToScatter(scatter, node);

                // The gate itself is the overlay — including a gate whose shape is not
                // drawable yet. It classifies every cell as outside, and the plot now says
                // so instead of painting the whole population as if it were selected.
                scatter.setGateOverlay(node);

                // Wire toolbar to drawing mode
                toolGroup.selectedToggleProperty().addListener((obs, old, val) -> {
                    if (val == polygonBtn) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.POLYGON);
                    else if (val == rectBtn) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.RECTANGLE);
                    else if (val == ellipseBtn) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.ELLIPSE);
                    else scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.NONE);
                });

                // Wire callbacks — convert gate type if needed, then update model
                scatter.setOnPolygonDrawn(vertices -> {
                    GateNode target = currentNode;
                    boolean replaced = false;
                    if (!(target instanceof PolygonGate)) {
                        PolygonGate pg = new PolygonGate(chXCombo.getValue(), chYCombo.getValue());
                        pg.setEnabled(target.isEnabled());
                        copySharedSettings(target, pg);
                        if (onReplaceGate != null) onReplaceGate.accept(target, pg);
                        currentNode = pg;
                        target = pg;
                        replaced = true;
                    }
                    ((PolygonGate) target).setVertices(new ArrayList<>(vertices));
                    scatter.setGateOverlay(target);
                    fireNodeChanged();
                    if (replaced) {
                        Platform.runLater(() -> setGateNode(currentNode));
                    }
                });
                scatter.setOnRectangleDrawn(bounds -> {
                    GateNode target = currentNode;
                    boolean replaced = false;
                    if (!(target instanceof RectangleGate)) {
                        RectangleGate rg = new RectangleGate(chXCombo.getValue(), chYCombo.getValue(),
                            bounds[0], bounds[1], bounds[2], bounds[3]);
                        rg.setEnabled(target.isEnabled());
                        copySharedSettings(target, rg);
                        if (onReplaceGate != null) onReplaceGate.accept(target, rg);
                        currentNode = rg;
                        target = rg;
                        replaced = true;
                    } else {
                        RectangleGate rg = (RectangleGate) target;
                        rg.setMinX(bounds[0]); rg.setMaxX(bounds[1]);
                        rg.setMinY(bounds[2]); rg.setMaxY(bounds[3]);
                    }
                    scatter.setGateOverlay(target);
                    fireNodeChanged();
                    if (replaced) {
                        Platform.runLater(() -> setGateNode(currentNode));
                    }
                });
                scatter.setOnEllipseDrawn(params -> {
                    GateNode target = currentNode;
                    boolean replaced = false;
                    if (!(target instanceof EllipseGate)) {
                        EllipseGate eg = new EllipseGate(chXCombo.getValue(), chYCombo.getValue(),
                            params[0], params[1], params[2], params[3]);
                        eg.setEnabled(target.isEnabled());
                        copySharedSettings(target, eg);
                        if (onReplaceGate != null) onReplaceGate.accept(target, eg);
                        currentNode = eg;
                        target = eg;
                        replaced = true;
                    } else {
                        EllipseGate eg = (EllipseGate) target;
                        eg.setCenterX(params[0]); eg.setCenterY(params[1]);
                        eg.setRadiusX(params[2]); eg.setRadiusY(params[3]);
                    }
                    scatter.setGateOverlay(target);
                    fireNodeChanged();
                    if (replaced) {
                        Platform.runLater(() -> setGateNode(currentNode));
                    }
                });

                if (node instanceof PolygonGate) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.POLYGON);
                else if (node instanceof RectangleGate) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.RECTANGLE);
                else if (node instanceof EllipseGate) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.ELLIPSE);

                gateSpecificArea.getChildren().add(scatter);

                return;
            }
        }

        Label noData = new Label("Load an image to see the scatter plot");
        noData.setStyle("-fx-text-fill: #888888;");
        gateSpecificArea.getChildren().add(noData);
    }

    // ---- Branch names/colors editor (generic for any gate type) ----

    private void buildBranchNamesEditor(GateNode node) {
        branchNamesArea.getChildren().clear();
        if (node == null) return;
        branchNamesArea.getChildren().add(createSectionHeader("Branch Names & Colors"));

        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(4);

        List<Branch> branches = node.getBranches();
        for (int i = 0; i < branches.size(); i++) {
            Branch branch = branches.get(i);
            int idx = i;

            // Contextual branch label based on gate type and index
            String labelText;
            if (node instanceof QuadrantGate) {
                labelText = new String[]{"Q1 (++):", "Q2 (-+):", "Q3 (+-):", "Q4 (--):"} [Math.min(i, 3)];
            } else if (node instanceof Region2DGate) {
                labelText = i == 0 ? "Inside:" : "Outside:";
            } else {
                labelText = i == 0 ? "Positive:" : "Negative:";
            }
            Color labelColor = ColorUtils.intToColor(branch.getColor());
            Label label = new Label(labelText);
            label.setStyle("-fx-text-fill: " + toWebColor(labelColor) + ";");

            TextField nameField = new TextField(branch.getName());
            nameField.setPrefWidth(120);
            nameField.textProperty().addListener((obs, old, val) -> {
                if (!suppressEvents && val != null && !val.isBlank()) {
                    if (currentNode != null && idx < currentNode.getBranches().size()) {
                        currentNode.getBranches().get(idx).setName(val);
                    }
                }
            });
            nameField.setOnAction(e -> { fireNodeChanged(); buildActionButtons(currentNode); });
            nameField.focusedProperty().addListener((obs, old, focused) -> {
                if (!focused) { fireNodeChanged(); buildActionButtons(currentNode); }
            });

            ColorPicker colorPicker = new ColorPicker(ColorUtils.intToColor(branch.getColor()));
            colorPicker.setPrefWidth(80);
            colorPicker.valueProperty().addListener((obs, old, val) -> {
                if (!suppressEvents) {
                    // Use currentNode's branches to avoid stale references after gate replacement
                    if (currentNode != null && idx < currentNode.getBranches().size()) {
                        currentNode.getBranches().get(idx).setColor(ColorUtils.colorToInt(val));
                    }
                    if (currentScatter != null && currentNode != null) {
                        applyBranchColorsToScatter(currentScatter, currentNode);
                    }
                    if (currentHistogram != null && currentNode != null
                            && !(currentNode instanceof QuadrantGate)
                            && !(currentNode instanceof Region2DGate)) {
                        currentHistogram.setPosColor(ColorUtils.intToColor(currentNode.getPositiveColor()));
                        currentHistogram.setNegColor(ColorUtils.intToColor(currentNode.getNegativeColor()));
                    }
                    fireNodeChanged();
                }
            });

            Label countLabel = new Label(String.format("%,d", branch.getCount()));
            countLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10;");

            grid.add(label, 0, i);
            grid.add(nameField, 1, i);
            grid.add(colorPicker, 2, i);
            grid.add(countLabel, 3, i);
        }

        branchNamesArea.getChildren().add(grid);
    }

    // ---- Action buttons (generic for any gate type) ----

    private void buildActionButtons(GateNode node) {
        actionButtonArea.getChildren().clear();
        // Reached with a null node when the editor is cleared while a branch-name
        // field holds focus: clearing the container moves focus, and the focus-lost
        // handler fires after setGateNode(null) has already nulled currentNode.
        if (node == null) return;

        List<Branch> branches = node.getBranches();
        HBox buttonRow = new HBox(8);

        // Add "Add child gate to [branch]" button for each branch
        for (int i = 0; i < branches.size(); i++) {
            Branch branch = branches.get(i);
            int branchIdx = i;
            Button addBtn = new Button("+ " + branch.getName());
            addBtn.setStyle("-fx-base: #003300;");
            addBtn.setTooltip(new Tooltip("Add a child gate to '" + branch.getName() + "'"));
            addBtn.setOnAction(e -> {
                if (onAddToBranch != null) onAddToBranch.accept(branchIdx);
            });
            buttonRow.getChildren().add(addBtn);
        }

        Button removeBtn = new Button("Remove Gate");
        removeBtn.setStyle("-fx-base: #440000;");
        removeBtn.setTooltip(new Tooltip("Remove this gate and all its children (Del)"));
        removeBtn.setOnAction(e -> { if (onRemoveGate != null) onRemoveGate.run(); });
        buttonRow.getChildren().add(removeBtn);

        actionButtonArea.getChildren().add(buttonRow);
    }

    // ---- Public API ----

    public void setChannelNames(List<String> names) { channelCombo.getItems().setAll(names); }

    /** Per-compartment availability for the loaded image (drives the signal-type selectors). */
    public void setCompartmentCapability(CompartmentCapability capability) {
        this.compartmentCapability = capability;
    }

    /**
     * Append a "Signal:" compartment selector (and, when the export carries more than one
     * statistic, a statistic selector) to {@code row} for one gate axis.
     * <p>
     * The layout is this pane's; the decision is {@link GateAxis}'. {@link
     * GateAxis#choicesFrom} answers both what may be offered and what the axis must be
     * read as, and the axis is pinned to that signal <em>whether or not</em> a selector
     * appears. Skipping the pin because there was nothing to show is how a gate ended up
     * on {@code "<marker>: <Compartment>: Mean"} — MIRAGE's default quantification emits
     * Median only, so that column is not in the file, and the axis read NaN for every
     * cell: an empty histogram and a gate classifying nothing.
     */
    private void addSignalControls(HBox row, GateAxis axis) {
        GateAxis.Choices choices = axis.choicesFrom(compartmentCapability);
        axis.apply(choices.signal());
        if (!choices.offersCompartment()) return;

        String channel = axis.channel();
        ComboBox<Compartment> compCombo =
                new ComboBox<>(FXCollections.observableArrayList(choices.compartments()));
        compCombo.setValue(choices.signal().compartment());
        compCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Compartment c) { return c == null ? "" : c.displayName(); }
            @Override public Compartment fromString(String s) { return null; }
        });
        compCombo.setTooltip(new Tooltip("Signal compartment for " + channel));
        compCombo.setOnAction(e -> {
            if (!suppressEvents && currentNode != null) {
                applySignalChange(() ->
                        axis.apply(new GateAxis.Signal(compCombo.getValue(), axis.statistic())));
            }
        });
        Label sigLabel = new Label("Signal:");
        sigLabel.setStyle("-fx-text-fill: white;");
        row.getChildren().addAll(sigLabel, compCombo);

        if (!choices.offersStatistic()) return;
        ComboBox<Statistic> statCombo =
                new ComboBox<>(FXCollections.observableArrayList(choices.statistics()));
        statCombo.setValue(choices.signal().statistic());
        statCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Statistic s) { return s == null ? "" : s.displayName(); }
            @Override public Statistic fromString(String s) { return null; }
        });
        statCombo.setTooltip(new Tooltip("Summary statistic for " + channel));
        statCombo.setOnAction(e -> {
            if (!suppressEvents && currentNode != null) {
                applySignalChange(() ->
                        axis.apply(new GateAxis.Signal(axis.compartment(), statCombo.getValue())));
            }
        });
        row.getChildren().add(statCombo);
    }

    /**
     * Point {@code combo} at slot {@code slot} of {@code gate}: everything a channel
     * change implies is {@link GateAxis#retarget}'s, and everything it leaves behind on
     * screen is this pane's.
     * <p>
     * {@code retarget} repoints the axis, re-pins its compartment and statistic to a
     * column the <em>new</em> channel is quantified with, and moves the branch labels the
     * user has not claimed. The pane then refreshes the branch-name editor and queues a
     * rebuild, which is what re-derives the signal selectors — a legacy channel offers no
     * compartment choice, and one that replaces it must stop showing one.
     * <p>
     * There is deliberately no immediate plot refresh here. Each builder used to run one,
     * because the re-pin arrived only with the rebuild and the plot would otherwise have
     * shown the old channel's compartment until then. Now that {@code retarget} pins
     * before returning, the rebuild on the next pulse draws the right thing the first
     * time, and a second drawing path is one more place for the two to disagree.
     */
    private void wireChannelCombo(ComboBox<String> combo, GateNode gate, int slot) {
        combo.setOnAction(e -> {
            // currentNode is the gate the editor is showing. A combo left over from a
            // superseded build (a gate-type conversion queues its own rebuild) must not
            // write to a gate that is no longer in the tree.
            if (suppressEvents || currentNode != gate) return;
            if (!GateAxis.of(gate, slot).retarget(combo.getValue(), compartmentCapability)) return;
            buildBranchNamesEditor(gate);
            fireNodeChanged();
            rebuildForChannelChange();
        });
    }


    /**
     * Carry a drawn region across a raw-mode compartment/statistic switch by remapping
     * each coordinate to the same percentile of its axis' new column, so the shape keeps
     * enclosing a comparable population instead of landing off-plot.
     * <p>
     * Degenerate (cleared) shapes are left alone — remapping a placeholder zero would
     * plant a real region at the new column's minimum. The ellipse is remapped through
     * its bounding box: percentile mapping is not linear, so an exact ellipse cannot be
     * preserved, and the box is the sensible approximation.
     */
    private void remapRegionShape(Region2DGate gate, MeasuredColumn oldX, MeasuredColumn newX,
                                  MeasuredColumn oldY, MeasuredColumn newY) {
        if (gate instanceof PolygonGate pg) {
            if (pg.getVertices().isEmpty()) return;
            List<double[]> out = new ArrayList<>();
            for (double[] v : pg.getVertices()) {
                out.add(new double[]{
                        remapRawThreshold(oldX, newX, v[0]),
                        remapRawThreshold(oldY, newY, v[1])});
            }
            pg.setVertices(out);
        } else if (gate instanceof RectangleGate rg) {
            if (rg.getMaxX() - rg.getMinX() <= 1e-10) return;
            rg.setMinX(remapRawThreshold(oldX, newX, rg.getMinX()));
            rg.setMaxX(remapRawThreshold(oldX, newX, rg.getMaxX()));
            rg.setMinY(remapRawThreshold(oldY, newY, rg.getMinY()));
            rg.setMaxY(remapRawThreshold(oldY, newY, rg.getMaxY()));
        } else if (gate instanceof EllipseGate eg) {
            if (eg.getRadiusX() <= 1e-10) return;
            double loX = remapRawThreshold(oldX, newX, eg.getCenterX() - eg.getRadiusX());
            double hiX = remapRawThreshold(oldX, newX, eg.getCenterX() + eg.getRadiusX());
            double loY = remapRawThreshold(oldY, newY, eg.getCenterY() - eg.getRadiusY());
            double hiY = remapRawThreshold(oldY, newY, eg.getCenterY() + eg.getRadiusY());
            eg.setCenterX((loX + hiX) / 2);
            eg.setRadiusX(Math.abs(hiX - loX) / 2);
            eg.setCenterY((loY + hiY) / 2);
            eg.setRadiusY(Math.abs(hiY - loY) / 2);
        }
    }

    /**
     * Apply a compartment/statistic selection and bring the rest of the editor with it.
     * <p>
     * In <b>raw</b> mode the threshold is remapped to the same percentile of the newly
     * selected column: a bare number does not carry across columns (a Sum is ~100x the
     * corresponding Mean, a nuclear intensity nothing like a whole-cell one), so
     * without this the gate silently collapses to "everything positive" or "everything
     * negative". In <b>z-score</b> mode no remap is needed — a z-score means the same
     * thing in either column — and the axis simply re-standardises.
     * <p>
     * Threshold gates refresh in place via {@link #updateHistogram()}. Quadrant gates
     * build their axis sliders from the column's own data range, so they are rebuilt
     * rather than patched.
     */
    private void applySignalChange(Runnable mutation) {
        GateNode node = currentNode;
        if (node == null) return;
        boolean threshold = isThresholdGate(node);
        boolean raw = !node.isThresholdIsZScore();
        MeasuredColumn oldCol = threshold ? thresholdColumn(node) : null;
        MeasuredColumn oldColX = threshold ? null : columnX(node);
        MeasuredColumn oldColY = threshold ? null : columnY(node);
        double oldThreshold = node.getThreshold();

        mutation.run();

        if (raw) {
            if (threshold) {
                node.setThreshold(remapRawThreshold(oldCol, thresholdColumn(node), oldThreshold));
            } else if (node instanceof QuadrantGate qg) {
                qg.setThresholdX(remapRawThreshold(oldColX, columnX(node), qg.getThresholdX()));
                qg.setThresholdY(remapRawThreshold(oldColY, columnY(node), qg.getThresholdY()));
            } else if (node instanceof Region2DGate region) {
                remapRegionShape(region, oldColX, columnX(node), oldColY, columnY(node));
            }
        }

        // The axis now resolves to a different column, so both z-score questions have to
        // be asked again: a Median column with spread and a "Median Z" column are not the
        // same offer.
        syncModeSelection(node);

        if (threshold) {
            updateHistogram();
            fireNodeChanged();
        } else {
            fireNodeChanged();
            Platform.runLater(() -> setGateNode(node));
        }
    }
    public void setCellIndex(CellIndex index) { this.cellIndex = index; }
    public void setMarkerStats(MarkerStats stats) {
        this.markerStats = stats;
        if (currentNode != null) {
            updateHistogram();
            refreshScatterPlot();
        }
    }
    public void setRoiMask(boolean[] mask) {
        this.roiMask = mask;
        if (currentNode != null) {
            updateHistogram();
            refreshScatterPlot();
        }
    }
    public void setAncestorMask(boolean[] mask) {
        this.ancestorMask = mask;
        if (clipInfoLabel != null) clipInfoLabel.setVisible(mask != null);
        if (currentNode != null) {
            updateHistogram();
            refreshScatterPlot();
        }
    }
    public void setOnNodeChanged(Consumer<GateNode> callback) { this.onNodeChanged = callback; }
    public void setOnAddToPositive(Runnable callback) { this.onAddToPositive = callback; }
    public void setOnAddToNegative(Runnable callback) { this.onAddToNegative = callback; }
    public void setOnAddToBranch(IntConsumer callback) { this.onAddToBranch = callback; }
    public void setOnRemoveGate(Runnable callback) { this.onRemoveGate = callback; }
    public void setOnReplaceGate(java.util.function.BiConsumer<GateNode, GateNode> callback) { this.onReplaceGate = callback; }

    /** True when the selected mode is FlowPath's own standardisation. */
    public boolean isUseZScore() {
        return currentMode != null && currentMode.computed();
    }

    public void updatePopulationCounts() {
        if (currentPopulationLabel == null) return;
        if (currentNode == null) {
            currentPopulationLabel.setText("Positive: -- | Negative: --");
            return;
        }
        List<Branch> branches = currentNode.getBranches();
        if (branches.size() == 2) {
            int pos = branches.get(0).getCount();
            int neg = branches.get(1).getCount();
            int total = pos + neg;
            if (total > 0) {
                currentPopulationLabel.setText(String.format(
                    "%s: %,d (%.1f%%) | %s: %,d (%.1f%%)",
                    branches.get(0).getName(), pos, 100.0 * pos / total,
                    branches.get(1).getName(), neg, 100.0 * neg / total));
            } else {
                currentPopulationLabel.setText(branches.get(0).getName() + ": 0 | " + branches.get(1).getName() + ": 0");
            }
        }
    }

    // ---- Internal ----

    /**
     * Carry a gate's settings onto its replacement when the user converts one gate
     * type into another by drawing a different shape.
     * <p>
     * The drawn coordinates are read straight off the scatter plot, which renders in
     * the <em>source</em> gate's coordinate space: z-scored or raw per its z-score
     * flag, and standardised against each axis' resolved column (channel +
     * compartment + statistic). Both therefore have to travel with the shape. If they
     * do not, {@code GatingEngine} evaluates the boundary in a different space than
     * it was drawn in — the overlay still renders over the points, so nothing looks
     * wrong while every cell is misclassified.
     * <p>
     * Package-private and static so the conversion contract is testable without a
     * JavaFX toolkit.
     */
    static void copySharedSettings(GateNode from, GateNode to) {
        to.setClipPercentileLow(from.getClipPercentileLow());
        to.setClipPercentileHigh(from.getClipPercentileHigh());
        to.setExcludeOutliers(from.isExcludeOutliers());
        to.setThresholdIsZScore(from.isThresholdIsZScore());
        GateAxis.copySignals(from, to);
        // Copy branch children, colors, and names from old gate to new gate
        for (int i = 0; i < Math.min(from.getBranches().size(), to.getBranches().size()); i++) {
            Branch srcBranch = from.getBranches().get(i);
            Branch dstBranch = to.getBranches().get(i);
            dstBranch.setChildren(new ArrayList<>(srcBranch.getChildren()));
            dstBranch.setColor(srcBranch.getColor());
            dstBranch.setName(srcBranch.getName());
        }
    }

    // ---- resolved measurement columns (must match GatingEngine) ----

    /**
     * The measurement column for a channel + compartment + statistic, statistics included.
     * <p>
     * {@code GatingEngine} z-scores and percentile-clips against the column
     * {@code CellIndex} resolves — {@code "CD3: Nucleus: Median"} rather than the bare
     * {@code "CD3"}. The editor must read the same column, or the histogram axis, the
     * threshold line and the actual classification all describe different data.
     * {@link CellIndex#column} is that one resolution, shared with the engine.
     * <p>
     * {@link MarkerStats#compute} only summarises the bare markers, and a fresh
     * {@code MarkerStats} arrives on image load, QC change and ROI change, so the
     * compartment columns this editor needs may not be registered yet.
     * {@code CellIndex.column} registers them; that is the point of holding a
     * {@link MeasuredColumn} rather than a key.
     *
     * @return the column, or {@code null} when there is nothing to resolve against yet
     *         (no channel, no index, or no statistics)
     */
    private MeasuredColumn column(String channel, Compartment comp, Statistic stat) {
        if (channel == null || cellIndex == null || markerStats == null) return null;
        return cellIndex.column(channel, comp, stat, markerStats);
    }

    /**
     * Resolved column for one axis <em>slot</em> of {@code node}, or null when the gate
     * type has no such axis.
     * <p>
     * Addressed by slot rather than by position in {@code getChannels()}, which omits an
     * unset channel: on a gate whose X channel is null, {@code compartmentAt(0)} answers
     * with the <em>Y</em> axis' compartment, and {@code compartmentAt(1)} with an
     * out-of-range whole-cell Mean. {@link GateAxis} reads and writes the same slot by
     * construction, so that skew is not expressible.
     */
    private MeasuredColumn axisColumn(GateNode node, int slot) {
        if (node == null || slot >= GateAxis.axisCount(node)) return null;
        return GateAxis.of(node, slot).columnIn(cellIndex, markerStats);
    }

    /** Resolved column for a threshold gate's single channel. */
    private MeasuredColumn thresholdColumn(GateNode node) {
        return axisColumn(node, 0);
    }

    /** Resolved column for a 2D gate's X axis. */
    private MeasuredColumn columnX(GateNode node) {
        return axisColumn(node, 0);
    }

    /** Resolved column for a 2D gate's Y axis. */
    private MeasuredColumn columnY(GateNode node) {
        return axisColumn(node, 1);
    }

    /**
     * A raw threshold remapped to the same percentile of {@code newCol}, so a
     * compartment/statistic switch keeps the gate splitting the population the same
     * way instead of leaving a number that means nothing in the new column. Returns
     * {@code value} unchanged when the column is unchanged or the remap is not possible.
     */
    private double remapRawThreshold(MeasuredColumn oldCol, MeasuredColumn newCol, double value) {
        if (oldCol == null || newCol == null || newCol.key().equals(oldCol.key())) return value;
        double pct = oldCol.percentileRankOf(value);
        if (Double.isNaN(pct)) return value;
        double mapped = newCol.percentile(pct);
        return Double.isNaN(mapped) ? value : mapped;
    }

    /**
     * True when both of {@code node}'s axes name a channel the loaded index carries, so
     * there is something to plot.
     */
    private boolean hasPlottableAxes(GateNode node) {
        if (node == null || cellIndex == null || GateAxis.axisCount(node) < 2) return false;
        for (GateAxis axis : GateAxis.axesOf(node)) {
            String channel = axis.channel();
            if (channel == null || cellIndex.getMarkerIndex(channel) < 0) return false;
        }
        return true;
    }

    /**
     * The points to plot for a 2D gate: each axis read through its <em>own</em> resolved
     * column, and standardised against its own distribution when the gate is in z-score
     * space.
     * <p>
     * One spelling. This block existed four times — the quadrant slider range, the
     * quadrant scatter, its channel-change refresh, and the region scatter — and a fix
     * landing in one of them was the whole shape of commit {@code 6b66868}: three copies
     * plotted the bare whole-cell mean while the gate classified on a nuclear median, so
     * the overlay sat over points that were not the ones being gated.
     */
    private double[][] plotData(GateNode node) {
        GateAxis x = GateAxis.of(node, 0);
        GateAxis y = GateAxis.of(node, 1);
        if (node.isThresholdIsZScore() && markerStats != null) {
            return getFilteredXYWithZScore(x.channel(), x.compartment(), x.statistic(),
                    y.channel(), y.compartment(), y.statistic());
        }
        return getFilteredXY(x.channel(), x.compartment(), x.statistic(),
                y.channel(), y.compartment(), y.statistic());
    }

    /** Re-read {@code node}'s points onto {@code scatter} and re-anchor its axes. */
    private void redrawScatter(ScatterPlotCanvas scatter, GateNode node) {
        if (scatter == null || !hasPlottableAxes(node)) return;
        double[][] data = plotData(node);
        scatter.setData(data[0], data[1],
                GateAxis.of(node, 0).channel(), GateAxis.of(node, 1).channel());
        if (markerStats != null) {
            applyAxisRangeFor(scatter, node);
        }
    }

    private double[][] getFilteredXY(String chX, Compartment compX, Statistic statX,
                                     String chY, Compartment compY, Statistic statY) {
        double[] allX = cellIndex.getResolvedColumn(chX, compX, statX);
        double[] allY = cellIndex.getResolvedColumn(chY, compY, statY);
        boolean hasMask = roiMask != null || ancestorMask != null;
        if (!hasMask) return new double[][]{allX, allY};
        int count = 0;
        for (int i = 0; i < allX.length; i++) {
            if (passesMasks(i)) count++;
        }
        double[] fx = new double[count], fy = new double[count];
        int j = 0;
        for (int i = 0; i < allX.length; i++) {
            if (passesMasks(i)) { fx[j] = allX[i]; fy[j] = allY[i]; j++; }
        }
        return new double[][]{fx, fy};
    }

    /**
     * Like getFilteredXY but transforms values to z-score space.
     * Used for 2D gate scatter plots where thresholds/shapes are in z-score space.
     * <p>
     * Standardises each axis against its own <em>resolved</em> column: z-scoring a
     * nuclear-median value with the whole-cell-mean mean/std would place every point
     * somewhere the gate boundaries do not mean anything.
     */
    private double[][] getFilteredXYWithZScore(String chX, Compartment compX, Statistic statX,
                                               String chY, Compartment compY, Statistic statY) {
        double[][] raw = getFilteredXY(chX, compX, statX, chY, compY, statY);
        MeasuredColumn colX = column(chX, compX, statX);
        MeasuredColumn colY = column(chY, compY, statY);
        if (colX == null || colY == null) return raw;
        double[] fx = raw[0];
        double[] fy = raw[1];
        double[] zx = new double[fx.length];
        double[] zy = new double[fy.length];
        for (int i = 0; i < fx.length; i++) {
            zx[i] = colX.toZScore(fx[i]);
            zy[i] = colY.toZScore(fy[i]);
        }
        return new double[][]{zx, zy};
    }

    /** Check if a cell index passes both ROI mask and ancestor mask. */
    private boolean passesMasks(int i) {
        if (roiMask != null && !roiMask[i]) return false;
        if (ancestorMask != null && !ancestorMask[i]) return false;
        return true;
    }

    private void updateHistogram() {
        if (currentNode == null || cellIndex == null || markerStats == null) return;
        if (currentHistogram == null || currentThresholdSlider == null) return;
        String channel = currentNode.getChannel();
        if (channel == null) return;
        int markerIdx = cellIndex.getMarkerIndex(channel);
        if (markerIdx < 0) return;

        // The column the engine will actually gate on. Everything below — values, z-score
        // transform, clip percentiles, slider range — comes off this one handle, so the
        // histogram shows exactly what GatingEngine compares against.
        MeasuredColumn col = thresholdColumn(currentNode);
        if (col == null) return;
        double[] allValues = col.values();
        // Filter by ROI mask and ancestor mask, excluding NaN channel values
        // so downstream percentile/clip logic cannot produce NaN bounds.
        boolean hasMask = roiMask != null || ancestorMask != null;
        double[] rawValues;
        if (hasMask) {
            int count = 0;
            for (int i = 0; i < allValues.length; i++) {
                if (passesMasks(i) && !Double.isNaN(allValues[i])) count++;
            }
            rawValues = new double[count];
            int j = 0;
            for (int i = 0; i < allValues.length; i++) {
                if (passesMasks(i) && !Double.isNaN(allValues[i])) rawValues[j++] = allValues[i];
            }
        } else {
            int count = 0;
            for (double v : allValues) if (!Double.isNaN(v)) count++;
            if (count == allValues.length) {
                rawValues = allValues;
            } else {
                rawValues = new double[count];
                int j = 0;
                for (double v : allValues) if (!Double.isNaN(v)) rawValues[j++] = v;
            }
        }
        boolean useZ = currentNode.isThresholdIsZScore() && col.hasSpread();

        double[] displayValues;
        if (useZ) {
            displayValues = new double[rawValues.length];
            double mean = col.mean();
            double std = col.std();
            for (int i = 0; i < rawValues.length; i++) {
                displayValues[i] = (rawValues[i] - mean) / std;
            }
        } else {
            displayValues = rawValues;
        }

        // Anchor the histogram clip range on global per-marker percentiles so
        // the same axis is used for this channel everywhere it appears in the
        // gate tree. When the parent-filtered cells sit outside this range
        // (e.g. a 0.5% tail population on a correlated child marker), the
        // histogram's "X cells outside clip range" message at
        // HistogramCanvas:184-198 informs the user — they can widen the clip
        // percentiles if they want to gate inside the tail.
        double pctLo = currentNode.getClipPercentileLow();
        double pctHi = currentNode.getClipPercentileHigh();
        // Global per-column percentiles, so the same channel+compartment+statistic
        // uses one axis everywhere it appears in the gate tree.
        double clipLo = col.percentile(pctLo);
        double clipHi = col.percentile(pctHi);
        if (useZ) {
            clipLo = col.toZScore(clipLo);
            clipHi = col.toZScore(clipHi);
        }

        // Defensive fallback only when the global percentile is unusable
        // (column constant in the full population, or absent from markerStats).
        boolean badGlobal = Double.isNaN(clipLo) || Double.isNaN(clipHi) || !(clipHi > clipLo);
        if (badGlobal && displayValues.length > 0) {
            double dataMin = percentileOf(displayValues, 0);
            double dataMax = percentileOf(displayValues, 100);
            clipLo = dataMin;
            clipHi = dataMax > dataMin ? dataMax : dataMin + 1;
        } else if (badGlobal) {
            clipLo = 0;
            clipHi = 1;
        }
        final double clipMin = clipLo;
        final double clipMax = clipHi;

        currentHistogram.setData(displayValues, clipMin, clipMax);
        currentHistogram.setThreshold(currentNode.getThreshold());
        // Suppress events when updating slider range to prevent clamping from
        // writing a corrupted value back to the node
        withSuppressedEvents(() -> {
            currentThresholdSlider.setMin(clipMin);
            currentThresholdSlider.setMax(clipMax);
            // Re-pin the step to the new range so threshold "speed" matches the
            // QC sliders whether the axis is z-score (~10 wide) or raw (~10000s wide).
            SliderUtils.applyRangeStep(currentThresholdSlider);
            // Re-pin the thumb and the text field AFTER the range move. Slider.setMin
            // and setMax silently clamp the current value, so a mode/compartment switch
            // would otherwise leave the node, the thumb and the field holding three
            // different numbers — and the next drag would start from the clamped edge.
            currentThresholdSlider.setValue(currentNode.getThreshold());
            if (currentThresholdField != null) {
                currentThresholdField.setText(String.format(Locale.US, "%.4f", currentNode.getThreshold()));
            }
        });
        updatePopulationCounts();
    }

    /**
     * Linear-interpolated percentile of an array (NaNs ignored). Returns NaN for
     * an empty/all-NaN input so the caller's badGlobal fallback engages.
     * Package-private so PercentileOfTest (same package) can call it directly.
     * @param pct percentile in [0,100]
     */
    static double percentileOf(double[] values, double pct) {
        if (values == null || values.length == 0) return Double.NaN;
        double[] sorted = new double[values.length];
        int n = 0;
        for (double v : values) if (!Double.isNaN(v)) sorted[n++] = v;
        if (n == 0) return Double.NaN;
        sorted = java.util.Arrays.copyOf(sorted, n);
        java.util.Arrays.sort(sorted);
        if (n == 1) return sorted[0];
        double rank = (pct / 100.0) * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double frac = rank - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    private boolean isThresholdGate(GateNode node) {
        return !(node instanceof QuadrantGate) && !(node instanceof Region2DGate);
    }

    /**
     * Rebuild the editor after a gate's channel changed, so the per-axis signal
     * <em>selectors</em> are re-derived for the new channel.
     * <p>
     * Selectors, not selection. The selection itself is already correct by the time this
     * runs: {@link GateAxis#retarget} re-pins the axis to a column the new channel is
     * actually quantified with before it returns, which is what stopped a retarget
     * leaving {@code Nucleus} on a whole-cell-only channel and reading NaN for every
     * cell. What {@code retarget} cannot do is change what is on screen, and the combos
     * are built from {@link GateAxis#choicesFrom}: whether a compartment combo exists at
     * all, whether a statistic combo does, and which options each offers are all answers
     * about <em>this</em> channel's {@link CompartmentCapability}. A legacy channel
     * offers no compartment choice and one that replaces it must stop showing one; a
     * Median-only channel must stop offering Mean. Only {@link #setGateNode} builds
     * those, so only a rebuild re-derives them.
     * <p>
     * Deferred so the rebuild does not tear down the combo whose action is running.
     */
    private void rebuildForChannelChange() {
        GateNode node = currentNode;
        if (node == null) return;
        Platform.runLater(() -> {
            if (currentNode == node) setGateNode(node);
        });
    }

    /**
     * Point the Raw/Z-score toggle at {@code node}, and offer z-score only when this gate's
     * axes can actually deliver one.
     * <p>
     * The button used to be created selected and never disabled, while the drawing code
     * asked a second question the button had not: {@code updateHistogram} computes
     * {@code isThresholdIsZScore() && col.hasSpread()} and draws raw values on a flat
     * column. So the editor rendered raw under a button reading "Z-score", and flipping the
     * toggle there moved the label and the gate's flag without converting the threshold —
     * the conversion is guarded on the same {@code hasSpread()} the button was not.
     * <p>
     * Two separate reasons to withdraw the offer, and they become answerable at different
     * times. A statistic MIRAGE already standardised is knowable from the gate alone, so it
     * is checked first and holds even before an index is attached. Whether the column is
     * flat needs data; until there is some, the question is left open and the node's own
     * preference stands, so an editor that has not seen cells cannot discard a saved gate's
     * setting.
     * <p>
     * Re-run from {@link #applySignalChange} as well as on rebuild, because changing the
     * compartment or the statistic changes which column the axis resolves to — and
     * therefore both answers.
     */
    /**
     * Rebuild the "Values" row from what this gate can actually offer, and select the mode
     * it is in.
     * <p>
     * The row is <b>derived, never set</b>: {@link ValueMode#availableFor} is the only
     * thing that decides which buttons exist, from the gate, the capability scanned at
     * ingest and (when loaded) the data. This method renders that answer and decides
     * nothing — the same division {@code UiStateController} keeps on the UMAP side, and for
     * the same reason. The control it replaces was a fixed pair whose disabled state was
     * computed here, in the editor, from three separate predicates; a second caller
     * answering "can this be z-scored?" slightly differently is how a display and a
     * classification path drift apart.
     * <p>
     * When the gate's own combination is not on offer — a saved gate pinned to a column
     * this file does not carry — {@link ValueMode#selectedIn} falls back to raw, and that
     * fallback is <b>written back onto the gate</b>. Selecting a button while events are
     * suppressed changes no model state, so without this the engine would keep reading a
     * column the editor has stopped drawing.
     */
    private void syncModeSelection(GateNode node) {
        if (node == null) return;
        List<ValueMode> modes =
                ValueMode.availableFor(node, compartmentCapability, cellIndex, markerStats);
        ValueMode selected = ValueMode.selectedIn(modes, node);
        currentModes = modes;

        withSuppressedEvents(() -> {
            modeGroup.getToggles().clear();
            // Keep the "Values:" label, replace the buttons after it.
            modeRow.getChildren().remove(1, modeRow.getChildren().size());
            for (ValueMode mode : modes) {
                RadioButton button = new RadioButton(mode.label());
                button.setToggleGroup(modeGroup);
                button.setUserData(mode);
                // Coloured by who computed the number: FlowPath's own standardisation is
                // marked as derived, everything read from the export is plain, and a mode
                // that cannot be used right now is muted and says why in its tooltip
                // rather than quietly disappearing.
                button.setDisable(!mode.available());
                String colour = !mode.available() ? UNAVAILABLE_COLOR
                        : mode.computed() ? COMPUTED_HERE_COLOR : "white";
                button.setStyle("-fx-text-fill: " + colour + ";");
                button.setTooltip(new Tooltip(mode.tooltip()));
                if (mode.equals(selected)) button.setSelected(true);
                modeRow.getChildren().add(button);
            }
        });

        // Bring the model across by hand: the selection above was made with events
        // suppressed, so nothing has written it to the gate yet. Skipped when the gate is
        // already in the selected mode, so a plain refresh does not rewrite the tree.
        currentMode = selected;
        if (selected != null && !alreadyIn(node, selected)) {
            selected.applyTo(node);
        }
    }

    /** Whether {@code node} already reads the way {@code mode} says it should. */
    private static boolean alreadyIn(GateNode node, ValueMode mode) {
        if (node.isThresholdIsZScore() != mode.computed()) return false;
        for (GateAxis axis : GateAxis.axesOf(node)) {
            Statistic statistic = axis.statistic();
            if (statistic == null) continue;
            if (!statistic.normalisation().equals(mode.normalisation())) return false;
        }
        return true;
    }

    /** Why the computed z-score is not on offer, in the user's terms. */
    private static String unavailableZScoreReason(boolean alreadyStandardised) {
        if (alreadyStandardised) {
            return "This statistic is already standardised by MIRAGE, across every cell of "
                    + "the patient.\nStandardising it again would rescale the axis by "
                    + "whatever is currently filtered.";
        }
        return "This channel's values are constant, so there is no spread to standardise "
                + "against.";
    }

    private void withSuppressedEvents(Runnable action) {
        suppressEvents = true;
        try { action.run(); } finally { suppressEvents = false; }
    }

    private void fireNodeChanged() {
        if (onNodeChanged != null && currentNode != null) onNodeChanged.accept(currentNode);
    }

    private void applyThresholdFromField() {
        if (suppressEvents || currentNode == null || currentThresholdField == null) return;
        try {
            double val = parseThreshold(currentThresholdField.getText());
            withSuppressedEvents(() -> {
                currentNode.setThreshold(val);
                if (currentThresholdSlider != null) currentThresholdSlider.setValue(val);
                if (currentHistogram != null) currentHistogram.setThreshold(val);
            });
            fireNodeChanged();
            updatePopulationCounts();
        } catch (NumberFormatException ex) {
            currentThresholdField.setText(String.format(Locale.US, "%.4f", currentNode.getThreshold()));
        }
    }

    /**
     * Parse a typed threshold. The field is rendered with {@link Locale#US} so that
     * {@link Double#parseDouble} — which only accepts {@code '.'} — can read it back,
     * but a user on a comma-decimal locale will naturally type {@code "0,33"}, so
     * accept that too. No thousands separator is ever emitted, making the swap safe.
     * Package-private so tests in the same package can exercise it directly.
     */
    static double parseThreshold(String text) {
        if (text == null) throw new NumberFormatException("null");
        return Double.parseDouble(text.trim().replace(',', '.'));
    }

    private static String toWebColor(Color c) {
        return String.format("#%02x%02x%02x",
            (int)(c.getRed() * 255), (int)(c.getGreen() * 255), (int)(c.getBlue() * 255));
    }

    private static Label createSectionHeader(String text) {
        Label header = new Label(text);
        header.setStyle("-fx-text-fill: #888888; -fx-font-size: 10; -fx-font-weight: bold;");
        header.setPadding(new Insets(4, 0, 0, 0));
        return header;
    }

    /**
     * Anchor the scatter axes on the clip percentiles of each axis' own resolved
     * column, matching whichever coordinate space the gate is in. {@code node} is
     * only read for its clip percentiles; the columns come from {@link #columnX}/
     * {@link #columnY} so a nuclear or median axis anchors on its own distribution.
     */
    private void applyClipAxisRange(ScatterPlotCanvas scatter, MeasuredColumn colX, MeasuredColumn colY,
                                    GateNode node, boolean zScore) {
        if (colX == null || colY == null) {
            scatter.clearAxisRange();
            return;
        }
        double loX = colX.percentile(node.getClipPercentileLow());
        double hiX = colX.percentile(node.getClipPercentileHigh());
        double loY = colY.percentile(node.getClipPercentileLow());
        double hiY = colY.percentile(node.getClipPercentileHigh());
        if (zScore) {
            loX = colX.toZScore(loX);
            hiX = colX.toZScore(hiX);
            loY = colY.toZScore(loY);
            hiY = colY.toZScore(hiY);
        }
        if (Double.isNaN(loX) || Double.isNaN(hiX) || Double.isNaN(loY) || Double.isNaN(hiY)
                || !(hiX > loX) || !(hiY > loY)) {
            scatter.clearAxisRange();
            return;
        }
        scatter.setAxisRange(loX, hiX, loY, hiY);
    }

    /** Re-anchor {@code scatter}'s axes for {@code node}'s current axis selection. */
    private void applyAxisRangeFor(ScatterPlotCanvas scatter, GateNode node) {
        applyClipAxisRange(scatter, columnX(node), columnY(node), node, node.isThresholdIsZScore());
    }

    /** Re-read the scatter currently on screen, for whichever gate it is showing. */
    private void refreshScatterPlot() {
        redrawScatter(currentScatter, currentNode);
    }

    private void applyBranchColorsToScatter(ScatterPlotCanvas scatter, GateNode node) {
        List<Branch> branches = node.getBranches();
        if (node instanceof QuadrantGate && branches.size() == 4) {
            scatter.setQuadrantColors(
                ColorUtils.intToColor(branches.get(0).getColor()).deriveColor(0, 1, 1, 0.6),
                ColorUtils.intToColor(branches.get(1).getColor()).deriveColor(0, 1, 1, 0.6),
                ColorUtils.intToColor(branches.get(2).getColor()).deriveColor(0, 1, 1, 0.6),
                ColorUtils.intToColor(branches.get(3).getColor()).deriveColor(0, 1, 1, 0.6));
        } else if (branches.size() >= 2) {
            scatter.setInsideColor(ColorUtils.intToColor(branches.get(0).getColor()).deriveColor(0, 1, 1, 0.6));
            scatter.setOutsideColor(ColorUtils.intToColor(branches.get(1).getColor()).deriveColor(0, 1, 1, 0.3));
        }
    }

}
