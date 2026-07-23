package qupath.ext.flowpath.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
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
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

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
    private final ToggleGroup modeGroup;
    private final RadioButton rawModeBtn;
    private final RadioButton zscoreModeBtn;

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
        rawModeBtn = new RadioButton("Raw");
        rawModeBtn.setToggleGroup(modeGroup);
        rawModeBtn.setStyle("-fx-text-fill: white;");
        rawModeBtn.setTooltip(new Tooltip("Compare marker raw intensity values against threshold"));
        zscoreModeBtn = new RadioButton("Z-score");
        zscoreModeBtn.setToggleGroup(modeGroup);
        zscoreModeBtn.setSelected(true);
        zscoreModeBtn.setStyle("-fx-text-fill: white;");
        zscoreModeBtn.setTooltip(new Tooltip("Compare z-score normalized values against threshold (recommended)"));
        modeGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (!suppressEvents && currentNode != null) {
                boolean toZScore = zscoreModeBtn.isSelected();
                currentNode.setThresholdIsZScore(toZScore);
                if (isThresholdGate(currentNode)) {
                    // Transform threshold value between coordinate spaces, against the
                    // same resolved column the engine compares on. updateHistogram then
                    // re-ranges the axis and re-pins the slider/field to the new value,
                    // so the gate lands on the same cells and stays freely draggable.
                    String key = thresholdStatsKey(currentNode);
                    if (markerStats != null && key != null && markerStats.getStd(key) > 1e-10) {
                        double oldVal = currentNode.getThreshold();
                        currentNode.setThreshold(toZScore
                                ? markerStats.toZScore(key, oldVal)
                                : markerStats.fromZScore(key, oldVal));
                    }
                    updateHistogram();
                } else if (currentNode instanceof QuadrantGate qg) {
                    // Transform quadrant thresholds between coordinate spaces
                    String keyX = statsKeyX(qg);
                    String keyY = statsKeyY(qg);
                    if (markerStats != null && keyX != null && keyY != null
                            && markerStats.getStd(keyX) > 1e-10
                            && markerStats.getStd(keyY) > 1e-10) {
                        if (toZScore) {
                            qg.setThresholdX(markerStats.toZScore(keyX, qg.getThresholdX()));
                            qg.setThresholdY(markerStats.toZScore(keyY, qg.getThresholdY()));
                        } else {
                            qg.setThresholdX(markerStats.fromZScore(keyX, qg.getThresholdX()));
                            qg.setThresholdY(markerStats.fromZScore(keyY, qg.getThresholdY()));
                        }
                    }
                    fireNodeChanged();
                    Platform.runLater(() -> setGateNode(currentNode));
                    return;
                } else if (currentNode instanceof Region2DGate) {
                    // Transform shape coordinates between raw and z-score space
                    String chX = statsKeyX(currentNode);
                    String chY = statsKeyY(currentNode);
                    if (markerStats != null && chX != null && chY != null
                            && markerStats.getStd(chX) > 1e-10 && markerStats.getStd(chY) > 1e-10) {
                        if (currentNode instanceof PolygonGate pg && !pg.getVertices().isEmpty()) {
                            List<double[]> transformed = new ArrayList<>();
                            for (double[] v : pg.getVertices()) {
                                transformed.add(new double[]{
                                        toZScore ? markerStats.toZScore(chX, v[0]) : markerStats.fromZScore(chX, v[0]),
                                        toZScore ? markerStats.toZScore(chY, v[1]) : markerStats.fromZScore(chY, v[1])
                                });
                            }
                            pg.setVertices(transformed);
                        } else if (currentNode instanceof RectangleGate rg
                                && rg.getMaxX() - rg.getMinX() > 1e-10) {
                            if (toZScore) {
                                rg.setMinX(markerStats.toZScore(chX, rg.getMinX()));
                                rg.setMaxX(markerStats.toZScore(chX, rg.getMaxX()));
                                rg.setMinY(markerStats.toZScore(chY, rg.getMinY()));
                                rg.setMaxY(markerStats.toZScore(chY, rg.getMaxY()));
                            } else {
                                rg.setMinX(markerStats.fromZScore(chX, rg.getMinX()));
                                rg.setMaxX(markerStats.fromZScore(chX, rg.getMaxX()));
                                rg.setMinY(markerStats.fromZScore(chY, rg.getMinY()));
                                rg.setMaxY(markerStats.fromZScore(chY, rg.getMaxY()));
                            }
                        } else if (currentNode instanceof EllipseGate eg && eg.getRadiusX() > 1e-10) {
                            double stdX = markerStats.getStd(chX);
                            double stdY = markerStats.getStd(chY);
                            if (toZScore) {
                                eg.setCenterX(markerStats.toZScore(chX, eg.getCenterX()));
                                eg.setCenterY(markerStats.toZScore(chY, eg.getCenterY()));
                                eg.setRadiusX(eg.getRadiusX() / stdX);
                                eg.setRadiusY(eg.getRadiusY() / stdY);
                            } else {
                                eg.setCenterX(markerStats.fromZScore(chX, eg.getCenterX()));
                                eg.setCenterY(markerStats.fromZScore(chY, eg.getCenterY()));
                                eg.setRadiusX(eg.getRadiusX() * stdX);
                                eg.setRadiusY(eg.getRadiusY() * stdY);
                            }
                        }
                    } else {
                        // Can't transform — clear shape as fallback
                        if (currentNode instanceof PolygonGate pg) pg.setVertices(List.of());
                        else if (currentNode instanceof RectangleGate rg) { rg.setMinX(0); rg.setMaxX(0); rg.setMinY(0); rg.setMaxY(0); }
                        else if (currentNode instanceof EllipseGate eg) { eg.setCenterX(0); eg.setCenterY(0); eg.setRadiusX(0); eg.setRadiusY(0); }
                    }
                    fireNodeChanged();
                    Platform.runLater(() -> setGateNode(currentNode));
                    return;
                }
                fireNodeChanged();
            }
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

        // Wire deferred callbacks
        channelCombo.setOnAction(e -> {
            if (!suppressEvents && currentNode != null) {
                String oldChannel = currentNode.getChannel();
                String newChannel = channelCombo.getValue();
                currentNode.setChannel(newChannel);
                // Only auto-rename branches if they still match the old default pattern
                if (newChannel != null && currentNode.getBranches().size() >= 2) {
                    Branch pos = currentNode.getBranches().get(0);
                    Branch neg = currentNode.getBranches().get(1);
                    if (oldChannel != null && pos.getName().equals(oldChannel + "+")) {
                        pos.setName(newChannel + "+");
                    }
                    if (oldChannel != null && neg.getName().equals(oldChannel + "-")) {
                        neg.setName(newChannel + "-");
                    }
                }
                updateHistogram();
                fireNodeChanged();
                // Rebuild the editor so the signal-type selector reflects the new channel's
                // available compartments (and a now-unavailable compartment falls back safely).
                setGateNode(currentNode);
            }
        });
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
            } else if (node instanceof Region2DGate) {
                build2DEditor(node);
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
        addCompartmentControls(channelRow, node.getChannel(),
                node::getCompartment, node::setCompartment,
                node::getStatistic, node::setStatistic);

        HBox modeRow = new HBox(12, new Label("Mode:") {{ setStyle("-fx-text-fill: white;"); }}, rawModeBtn, zscoreModeBtn);
        if (node.isThresholdIsZScore()) zscoreModeBtn.setSelected(true);
        else rawModeBtn.setSelected(true);

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
        chXCombo.setOnAction(e -> { if (!suppressEvents) {
            String oldX = gate.getChannelX(); String newX = chXCombo.getValue();
            gate.setChannelX(newX);
            if (oldX != null && newX != null) {
                for (Branch b : gate.getBranches()) {
                    b.setName(b.getName().replace(oldX + "+", newX + "+").replace(oldX + "-", newX + "-"));
                }
                buildBranchNamesEditor(gate);
            }
            fireNodeChanged();
        }});

        Label chYLabel = new Label("Channel Y:");
        chYLabel.setStyle("-fx-text-fill: white;");
        ComboBox<String> chYCombo = new ComboBox<>(channelCombo.getItems());
        chYCombo.setValue(gate.getChannelY());
        chYCombo.setPrefWidth(150);
        chYCombo.setOnAction(e -> { if (!suppressEvents) {
            String oldY = gate.getChannelY(); String newY = chYCombo.getValue();
            gate.setChannelY(newY);
            if (oldY != null && newY != null) {
                for (Branch b : gate.getBranches()) {
                    b.setName(b.getName().replace(oldY + "+", newY + "+").replace(oldY + "-", newY + "-"));
                }
                buildBranchNamesEditor(gate);
            }
            fireNodeChanged();
        }});

        // Compute slider ranges from data (z-score or raw).
        // For child gates with ancestor mask, use the filtered data range for proper centering.
        double sliderMinX = -5, sliderMaxX = 5, sliderMinY = -5, sliderMaxY = 5;
        if (cellIndex != null && gate.getChannelX() != null && gate.getChannelY() != null) {
            int mxI = cellIndex.getMarkerIndex(gate.getChannelX());
            int myI = cellIndex.getMarkerIndex(gate.getChannelY());
            if (mxI >= 0 && myI >= 0) {
                double[][] fData;
                if (gate.isThresholdIsZScore() && markerStats != null) {
                    fData = getFilteredXYWithZScore(gate.getChannelX(), gate.getCompartmentX(), gate.getStatisticX(),
                            gate.getChannelY(), gate.getCompartmentY(), gate.getStatisticY());
                } else {
                    fData = getFilteredXY(gate.getChannelX(), gate.getCompartmentX(), gate.getStatisticX(),
                            gate.getChannelY(), gate.getCompartmentY(), gate.getStatisticY());
                }
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
                if (scatterRef[0] != null) scatterRef[0].setCrosshairOverlay(gate.getThresholdX(), gate.getThresholdY());
                fireNodeChanged();
            }
        });

        sliderY.valueProperty().addListener((obs, old, val) -> {
            if (!suppressEvents) {
                gate.setThresholdY(val.doubleValue());
                valY.setText(String.format(Locale.US, "%.3f", val.doubleValue()));
                if (scatterRef[0] != null) scatterRef[0].setCrosshairOverlay(gate.getThresholdX(), gate.getThresholdY());
                fireNodeChanged();
            }
        });

        HBox modeRow = new HBox(12, new Label("Mode:") {{ setStyle("-fx-text-fill: white;"); }}, rawModeBtn, zscoreModeBtn);
        if (gate.isThresholdIsZScore()) zscoreModeBtn.setSelected(true);
        else rawModeBtn.setSelected(true);

        HBox rowX = new HBox(8, chXLabel, chXCombo);
        addCompartmentControls(rowX, gate.getChannelX(),
                gate::getCompartmentX, gate::setCompartmentX, gate::getStatisticX, gate::setStatisticX);
        HBox rowY = new HBox(8, chYLabel, chYCombo);
        addCompartmentControls(rowY, gate.getChannelY(),
                gate::getCompartmentY, gate::setCompartmentY, gate::getStatisticY, gate::setStatisticY);

        gateSpecificArea.getChildren().addAll(
            rowX, rowY,
            modeRow,
            createSectionHeader("Threshold X"), new HBox(8, sliderX, valX),
            createSectionHeader("Threshold Y"), new HBox(8, sliderY, valY)
        );

        // Add scatter plot if data is available
        if (cellIndex != null && gate.getChannelX() != null && gate.getChannelY() != null) {
            int mxIdx = cellIndex.getMarkerIndex(gate.getChannelX());
            int myIdx = cellIndex.getMarkerIndex(gate.getChannelY());
            if (mxIdx >= 0 && myIdx >= 0) {
                // Transform data to z-score space when thresholds are z-score based
                double[][] filtered;
                if (gate.isThresholdIsZScore() && markerStats != null) {
                    filtered = getFilteredXYWithZScore(gate.getChannelX(), gate.getCompartmentX(), gate.getStatisticX(),
                            gate.getChannelY(), gate.getCompartmentY(), gate.getStatisticY());
                } else {
                    filtered = getFilteredXY(gate.getChannelX(), gate.getCompartmentX(), gate.getStatisticX(),
                            gate.getChannelY(), gate.getCompartmentY(), gate.getStatisticY());
                }
                ScatterPlotCanvas scatter = new ScatterPlotCanvas();
                scatter.setData(filtered[0], filtered[1], gate.getChannelX(), gate.getChannelY());
                scatter.setCrosshairOverlay(gate.getThresholdX(), gate.getThresholdY());
                if (markerStats != null) {
                    applyAxisRangeFor(scatter, gate);
                }
                applyBranchColorsToScatter(scatter, gate);
                scatterRef[0] = scatter;
                this.currentScatter = scatter;
                gateSpecificArea.getChildren().addAll(createSectionHeader("Scatter Plot"), scatter);

                // Refresh scatter when channels change
                Runnable refreshScatter = () -> {
                    int mx = cellIndex.getMarkerIndex(gate.getChannelX());
                    int my = cellIndex.getMarkerIndex(gate.getChannelY());
                    if (mx >= 0 && my >= 0) {
                        double[][] f;
                        if (gate.isThresholdIsZScore() && markerStats != null) {
                            f = getFilteredXYWithZScore(gate.getChannelX(), gate.getCompartmentX(), gate.getStatisticX(),
                                    gate.getChannelY(), gate.getCompartmentY(), gate.getStatisticY());
                        } else {
                            f = getFilteredXY(gate.getChannelX(), gate.getCompartmentX(), gate.getStatisticX(),
                                    gate.getChannelY(), gate.getCompartmentY(), gate.getStatisticY());
                        }
                        scatter.setData(f[0], f[1], gate.getChannelX(), gate.getChannelY());
                        if (markerStats != null) {
                            applyAxisRangeFor(scatter, gate);
                        }
                    }
                };
                chXCombo.setOnAction(e -> { if (!suppressEvents) {
                    String oldX = gate.getChannelX(); String newX = chXCombo.getValue();
                    gate.setChannelX(newX);
                    if (oldX != null && newX != null) {
                        for (Branch b : gate.getBranches()) {
                            b.setName(b.getName().replace(oldX + "+", newX + "+").replace(oldX + "-", newX + "-"));
                        }
                        buildBranchNamesEditor(gate);
                    }
                    refreshScatter.run(); fireNodeChanged(); rebuildForChannelChange();
                }});
                chYCombo.setOnAction(e -> { if (!suppressEvents) {
                    String oldY = gate.getChannelY(); String newY = chYCombo.getValue();
                    gate.setChannelY(newY);
                    if (oldY != null && newY != null) {
                        for (Branch b : gate.getBranches()) {
                            b.setName(b.getName().replace(oldY + "+", newY + "+").replace(oldY + "-", newY + "-"));
                        }
                        buildBranchNamesEditor(gate);
                    }
                    refreshScatter.run(); fireNodeChanged(); rebuildForChannelChange();
                }});
            }
        }
    }

    private void build2DEditor(GateNode node) {
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

        // Read current channels from the node
        if (node instanceof Region2DGate region) {
            chXCombo.setValue(region.getChannelX());
            chYCombo.setValue(region.getChannelY());
        }

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

        HBox modeRow = new HBox(12, new Label("Mode:") {{ setStyle("-fx-text-fill: white;"); }}, rawModeBtn, zscoreModeBtn);
        if (node.isThresholdIsZScore()) zscoreModeBtn.setSelected(true);
        else rawModeBtn.setSelected(true);

        HBox rowX = new HBox(8, chXLabel, chXCombo);
        HBox rowY = new HBox(8, chYLabel, chYCombo);
        if (node instanceof Region2DGate region) {
            addCompartmentControls(rowX, region.getChannelX(),
                    region::getCompartmentX, region::setCompartmentX,
                    region::getStatisticX, region::setStatisticX);
            addCompartmentControls(rowY, region.getChannelY(),
                    region::getCompartmentY, region::setCompartmentY,
                    region::getStatisticY, region::setStatisticY);
        }

        gateSpecificArea.getChildren().addAll(
            rowX, rowY,
            modeRow,
            createSectionHeader("Shape"), drawToolbar
        );

        // Scatter plot if data available
        String chX = chXCombo.getValue();
        String chY = chYCombo.getValue();
        if (cellIndex != null && chX != null && chY != null) {
            int mxIdx = cellIndex.getMarkerIndex(chX);
            int myIdx = cellIndex.getMarkerIndex(chY);
            if (mxIdx >= 0 && myIdx >= 0) {
                ScatterPlotCanvas scatter = new ScatterPlotCanvas();
                // Transform data to z-score space when z-score mode is active
                double[][] filtered;
                if (node.isThresholdIsZScore() && markerStats != null) {
                    filtered = getFilteredXYWithZScore(chX, get2DCompartmentX(node), get2DStatisticX(node),
                            chY, get2DCompartmentY(node), get2DStatisticY(node));
                } else {
                    filtered = getFilteredXY(chX, get2DCompartmentX(node), get2DStatisticX(node),
                            chY, get2DCompartmentY(node), get2DStatisticY(node));
                }
                scatter.setData(filtered[0], filtered[1], chX, chY);
                if (markerStats != null) {
                    applyAxisRangeFor(scatter, node);
                }
                this.currentScatter = scatter;

                // Apply branch colors to scatter plot
                applyBranchColorsToScatter(scatter, node);

                if (node instanceof PolygonGate pg && pg.getVertices().size() >= 3) {
                    scatter.setPolygonOverlay(pg.getVertices());
                } else if (node instanceof RectangleGate rg && rg.getMaxX() - rg.getMinX() > 1e-10) {
                    scatter.setRectangleOverlay(rg.getMinX(), rg.getMaxX(), rg.getMinY(), rg.getMaxY());
                } else if (node instanceof EllipseGate eg && eg.getRadiusX() > 1e-10) {
                    scatter.setEllipseOverlay(eg.getCenterX(), eg.getCenterY(), eg.getRadiusX(), eg.getRadiusY());
                }

                // Wire toolbar to drawing mode
                toolGroup.selectedToggleProperty().addListener((obs, old, val) -> {
                    if (val == polygonBtn) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.POLYGON);
                    else if (val == rectBtn) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.RECTANGLE);
                    else if (val == ellipseBtn) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.ELLIPSE);
                    else scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.NONE);
                });

                // Wire callbacks — convert gate type if needed, then update model
                String chXVal = chXCombo.getValue();
                String chYVal = chYCombo.getValue();
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
                    scatter.setPolygonOverlay(((PolygonGate) target).getVertices());
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
                    scatter.setRectangleOverlay(bounds[0], bounds[1], bounds[2], bounds[3]);
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
                    scatter.setEllipseOverlay(params[0], params[1], params[2], params[3]);
                    fireNodeChanged();
                    if (replaced) {
                        Platform.runLater(() -> setGateNode(currentNode));
                    }
                });

                if (node instanceof PolygonGate) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.POLYGON);
                else if (node instanceof RectangleGate) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.RECTANGLE);
                else if (node instanceof EllipseGate) scatter.setDrawingMode(ScatterPlotCanvas.DrawingMode.ELLIPSE);

                gateSpecificArea.getChildren().add(scatter);

                // Channel change: update scatter data and rebuild editor to re-wire channels on the gate
                Runnable refreshScatter = () -> {
                    String cx = chXCombo.getValue(), cy = chYCombo.getValue();
                    if (cx == null || cy == null) return;
                    if (node instanceof Region2DGate region) { region.setChannelX(cx); region.setChannelY(cy); }
                    int mx = cellIndex.getMarkerIndex(cx), my = cellIndex.getMarkerIndex(cy);
                    if (mx >= 0 && my >= 0) {
                        double[][] f;
                        if (node.isThresholdIsZScore() && markerStats != null) {
                            f = getFilteredXYWithZScore(cx, get2DCompartmentX(node), get2DStatisticX(node),
                                    cy, get2DCompartmentY(node), get2DStatisticY(node));
                        } else {
                            f = getFilteredXY(cx, get2DCompartmentX(node), get2DStatisticX(node),
                                    cy, get2DCompartmentY(node), get2DStatisticY(node));
                        }
                        scatter.setData(f[0], f[1], cx, cy);
                        if (markerStats != null) {
                            applyAxisRangeFor(scatter, node);
                        }
                    }
                    fireNodeChanged();
                    rebuildForChannelChange();
                };
                chXCombo.setOnAction(e -> { if (!suppressEvents) refreshScatter.run(); });
                chYCombo.setOnAction(e -> { if (!suppressEvents) refreshScatter.run(); });
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
     * Append a "Signal:" compartment selector (and, when expanded statistics exist, a
     * statistic selector) to {@code row} for the given channel, wired to the gate via the
     * supplied getters/setters. No-op when the GeoJSON is legacy or the channel has no
     * per-compartment measurements — the gate then stays on whole-cell mean.
     */
    private void addCompartmentControls(HBox row, String channel,
                                        Supplier<Compartment> getComp, Consumer<Compartment> setComp,
                                        Supplier<Statistic> getStat, Consumer<Statistic> setStat) {
        if (compartmentCapability == null || channel == null
                || !compartmentCapability.hasCompartments(channel)) {
            // Legacy / no rich data: pin to whole-cell mean, show nothing.
            setComp.accept(Compartment.WHOLE_CELL);
            setStat.accept(Statistic.MEAN);
            return;
        }

        // Compartment selector (enum order: Nuclear, Cytoplasmic, Whole-cell).
        List<Compartment> comps = new ArrayList<>();
        for (Compartment c : Compartment.values()) {
            if (compartmentCapability.compartmentsFor(channel).contains(c)) comps.add(c);
        }
        Compartment selComp = comps.contains(getComp.get()) ? getComp.get() : Compartment.WHOLE_CELL;
        if (!comps.contains(selComp)) selComp = comps.get(comps.size() - 1);
        setComp.accept(selComp);

        ComboBox<Compartment> compCombo = new ComboBox<>(FXCollections.observableArrayList(comps));
        compCombo.setValue(selComp);
        compCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Compartment c) { return c == null ? "" : c.displayName(); }
            @Override public Compartment fromString(String s) { return null; }
        });
        compCombo.setTooltip(new Tooltip("Signal compartment for " + channel));
        compCombo.setOnAction(e -> {
            if (!suppressEvents && currentNode != null) {
                applySignalChange(() -> setComp.accept(compCombo.getValue()));
            }
        });
        Label sigLabel = new Label("Signal:");
        sigLabel.setStyle("-fx-text-fill: white;");
        row.getChildren().addAll(sigLabel, compCombo);

        // Statistic selector — only when expanded quantification gave more than one stat.
        List<Statistic> stats = new ArrayList<>();
        for (Statistic s : Statistic.values()) {
            if (compartmentCapability.statisticsFor(channel).contains(s)) stats.add(s);
        }
        if (stats.size() > 1) {
            Statistic selStat = stats.contains(getStat.get()) ? getStat.get() : Statistic.MEAN;
            if (!stats.contains(selStat)) selStat = stats.get(0);
            setStat.accept(selStat);
            ComboBox<Statistic> statCombo = new ComboBox<>(FXCollections.observableArrayList(stats));
            statCombo.setValue(selStat);
            statCombo.setConverter(new StringConverter<>() {
                @Override public String toString(Statistic s) { return s == null ? "" : s.displayName(); }
                @Override public Statistic fromString(String s) { return null; }
            });
            statCombo.setTooltip(new Tooltip("Summary statistic for " + channel));
            statCombo.setOnAction(e -> {
                if (!suppressEvents && currentNode != null) {
                    applySignalChange(() -> setStat.accept(statCombo.getValue()));
                }
            });
            row.getChildren().add(statCombo);
        } else {
            setStat.accept(Statistic.MEAN);
        }
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
    private void remapRegionShape(Region2DGate gate, String oldKeyX, String newKeyX,
                                  String oldKeyY, String newKeyY) {
        if (gate instanceof PolygonGate pg) {
            if (pg.getVertices().isEmpty()) return;
            List<double[]> out = new ArrayList<>();
            for (double[] v : pg.getVertices()) {
                out.add(new double[]{
                        remapRawThreshold(oldKeyX, newKeyX, v[0]),
                        remapRawThreshold(oldKeyY, newKeyY, v[1])});
            }
            pg.setVertices(out);
        } else if (gate instanceof RectangleGate rg) {
            if (rg.getMaxX() - rg.getMinX() <= 1e-10) return;
            rg.setMinX(remapRawThreshold(oldKeyX, newKeyX, rg.getMinX()));
            rg.setMaxX(remapRawThreshold(oldKeyX, newKeyX, rg.getMaxX()));
            rg.setMinY(remapRawThreshold(oldKeyY, newKeyY, rg.getMinY()));
            rg.setMaxY(remapRawThreshold(oldKeyY, newKeyY, rg.getMaxY()));
        } else if (gate instanceof EllipseGate eg) {
            if (eg.getRadiusX() <= 1e-10) return;
            double loX = remapRawThreshold(oldKeyX, newKeyX, eg.getCenterX() - eg.getRadiusX());
            double hiX = remapRawThreshold(oldKeyX, newKeyX, eg.getCenterX() + eg.getRadiusX());
            double loY = remapRawThreshold(oldKeyY, newKeyY, eg.getCenterY() - eg.getRadiusY());
            double hiY = remapRawThreshold(oldKeyY, newKeyY, eg.getCenterY() + eg.getRadiusY());
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
        String oldKey = threshold ? thresholdStatsKey(node) : null;
        String oldKeyX = threshold ? null : statsKeyX(node);
        String oldKeyY = threshold ? null : statsKeyY(node);
        double oldThreshold = node.getThreshold();

        mutation.run();

        if (raw) {
            if (threshold) {
                node.setThreshold(remapRawThreshold(oldKey, thresholdStatsKey(node), oldThreshold));
            } else if (node instanceof QuadrantGate qg) {
                qg.setThresholdX(remapRawThreshold(oldKeyX, statsKeyX(node), qg.getThresholdX()));
                qg.setThresholdY(remapRawThreshold(oldKeyY, statsKeyY(node), qg.getThresholdY()));
            } else if (node instanceof Region2DGate region) {
                remapRegionShape(region, oldKeyX, statsKeyX(node), oldKeyY, statsKeyY(node));
            }
        }

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

    public boolean isUseZScore() { return zscoreModeBtn.isSelected(); }

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
        copyAxisSelection(from, to);
        // Copy branch children, colors, and names from old gate to new gate
        for (int i = 0; i < Math.min(from.getBranches().size(), to.getBranches().size()); i++) {
            Branch srcBranch = from.getBranches().get(i);
            Branch dstBranch = to.getBranches().get(i);
            dstBranch.setChildren(new ArrayList<>(srcBranch.getChildren()));
            dstBranch.setColor(srcBranch.getColor());
            dstBranch.setName(srcBranch.getName());
        }
    }

    /**
     * Copy each axis' compartment + statistic across a gate-type change, reading the
     * source through the same parallel {@code getChannels()} / {@code compartmentAt(k)}
     * / {@code statisticAt(k)} contract {@code GatingEngine} resolves columns with.
     * Axes the source does not have (a threshold gate converted to a 2D gate) fall
     * back to whole-cell mean, which is what {@code compartmentAt} returns out of range.
     */
    private static void copyAxisSelection(GateNode from, GateNode to) {
        if (to instanceof Region2DGate region) {
            region.setCompartmentX(from.compartmentAt(0));
            region.setStatisticX(from.statisticAt(0));
            region.setCompartmentY(from.compartmentAt(1));
            region.setStatisticY(from.statisticAt(1));
        } else if (to instanceof QuadrantGate quad) {
            quad.setCompartmentX(from.compartmentAt(0));
            quad.setStatisticX(from.statisticAt(0));
            quad.setCompartmentY(from.compartmentAt(1));
            quad.setStatisticY(from.statisticAt(1));
        } else {
            to.setCompartment(from.compartmentAt(0));
            to.setStatistic(from.statisticAt(0));
        }
    }

    // ---- resolved measurement keys (must match GatingEngine) ----

    /**
     * The {@link MarkerStats} key for a channel + compartment + statistic, registering
     * the column on first use.
     * <p>
     * {@code GatingEngine.evaluateGate} z-scores and percentile-clips against
     * {@code CellIndex.resolvedKey(...)} — {@code "CD3: Nucleus: Median"} rather than
     * the bare {@code "CD3"}. The editor must use the same key or the histogram axis,
     * the threshold line and the actual classification all describe different columns.
     * <p>
     * {@link MarkerStats#compute} only summarises the bare markers, and the engine's
     * {@code prepareResolvedColumns} pre-pass has not necessarily run by the time the
     * editor draws (a fresh {@code MarkerStats} arrives on image load, QC change and
     * ROI change), so register here instead of assuming. {@code ensureColumn} is
     * idempotent and re-uses the quality mask captured at compute time.
     */
    private String statsKey(String channel, Compartment comp, Statistic stat) {
        if (channel == null || cellIndex == null) return channel;
        String key = cellIndex.resolvedKey(channel, comp, stat);
        if (markerStats != null && !markerStats.hasColumn(key)) {
            markerStats.ensureColumn(key, cellIndex.getResolvedColumn(channel, comp, stat));
        }
        return key;
    }

    /** Resolved stats key for a threshold gate's single channel. */
    private String thresholdStatsKey(GateNode node) {
        return statsKey(node.getChannel(), node.getCompartment(), node.getStatistic());
    }

    /** Resolved stats key for a 2D gate's X axis. */
    private String statsKeyX(GateNode node) {
        return statsKey(get2DChannelX(node), get2DCompartmentX(node), get2DStatisticX(node));
    }

    /** Resolved stats key for a 2D gate's Y axis. */
    private String statsKeyY(GateNode node) {
        return statsKey(get2DChannelY(node), get2DCompartmentY(node), get2DStatisticY(node));
    }

    /**
     * A raw threshold remapped to the same percentile of {@code newKey}, so a
     * compartment/statistic switch keeps the gate splitting the population the same
     * way instead of leaving a number that means nothing in the new column. Returns
     * {@code value} unchanged when the key is unchanged or the remap is not possible.
     */
    private double remapRawThreshold(String oldKey, String newKey, double value) {
        if (markerStats == null || oldKey == null || newKey == null || newKey.equals(oldKey)) return value;
        double pct = markerStats.percentileRankOf(oldKey, value);
        if (Double.isNaN(pct)) return value;
        double mapped = markerStats.getPercentileValue(newKey, pct);
        return Double.isNaN(mapped) ? value : mapped;
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
        if (markerStats == null) return raw;
        String keyX = statsKey(chX, compX, statX);
        String keyY = statsKey(chY, compY, statY);
        double[] fx = raw[0];
        double[] fy = raw[1];
        double[] zx = new double[fx.length];
        double[] zy = new double[fy.length];
        for (int i = 0; i < fx.length; i++) {
            zx[i] = markerStats.toZScore(keyX, fx[i]);
            zy[i] = markerStats.toZScore(keyY, fy[i]);
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

        Compartment comp = currentNode.getCompartment();
        Statistic stat = currentNode.getStatistic();
        double[] allValues = cellIndex.getResolvedColumn(channel, comp, stat);
        // The column the engine will actually gate on. Everything below — z-score
        // transform, clip percentiles, slider range — is derived from this key so the
        // histogram shows exactly what GatingEngine.evaluateGate compares against.
        String key = thresholdStatsKey(currentNode);
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
        boolean useZ = currentNode.isThresholdIsZScore() && markerStats.getStd(key) > 1e-10;

        double[] displayValues;
        if (useZ) {
            displayValues = new double[rawValues.length];
            double mean = markerStats.getMean(key);
            double std = markerStats.getStd(key);
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
        double clipLo = markerStats.getPercentileValue(key, pctLo);
        double clipHi = markerStats.getPercentileValue(key, pctHi);
        if (useZ) {
            clipLo = markerStats.toZScore(key, clipLo);
            clipHi = markerStats.toZScore(key, clipHi);
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
     * Rebuild the editor after a 2D gate's channel changed, so the per-axis
     * compartment and statistic selectors are re-derived for the new channel.
     * <p>
     * Without this the gate keeps the previous channel's selection. When the new
     * channel has no measurement for that compartment, every resolved value is NaN
     * and the plot goes empty with no explanation. {@code addCompartmentControls} is
     * the only place the selection is validated against {@link CompartmentCapability},
     * and it only runs while the editor is being built — which is why the threshold
     * editor already rebuilds itself on a channel change.
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
     * only read for its clip percentiles; the keys come from {@link #statsKeyX}/
     * {@link #statsKeyY} so a nuclear or median axis anchors on its own distribution.
     */
    private void applyClipAxisRange(ScatterPlotCanvas scatter, String keyX, String keyY,
                                    GateNode node, boolean zScore) {
        if (markerStats == null || keyX == null || keyY == null) {
            scatter.clearAxisRange();
            return;
        }
        double loX = markerStats.getPercentileValue(keyX, node.getClipPercentileLow());
        double hiX = markerStats.getPercentileValue(keyX, node.getClipPercentileHigh());
        double loY = markerStats.getPercentileValue(keyY, node.getClipPercentileLow());
        double hiY = markerStats.getPercentileValue(keyY, node.getClipPercentileHigh());
        if (zScore) {
            loX = markerStats.toZScore(keyX, loX);
            hiX = markerStats.toZScore(keyX, hiX);
            loY = markerStats.toZScore(keyY, loY);
            hiY = markerStats.toZScore(keyY, hiY);
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
        applyClipAxisRange(scatter, statsKeyX(node), statsKeyY(node), node, node.isThresholdIsZScore());
    }

    private void refreshScatterPlot() {
        if (currentScatter == null || cellIndex == null || currentNode == null) return;
        String chX = get2DChannelX(currentNode);
        String chY = get2DChannelY(currentNode);
        if (chX == null || chY == null) return;
        int mxIdx = cellIndex.getMarkerIndex(chX);
        int myIdx = cellIndex.getMarkerIndex(chY);
        if (mxIdx < 0 || myIdx < 0) return;
        Compartment compX = get2DCompartmentX(currentNode);
        Compartment compY = get2DCompartmentY(currentNode);
        Statistic statX = get2DStatisticX(currentNode);
        Statistic statY = get2DStatisticY(currentNode);
        // All 2D gate types (quadrant, polygon, rectangle, ellipse) use per-gate z-score flag
        double[][] filtered;
        if (currentNode.isThresholdIsZScore() && markerStats != null) {
            filtered = getFilteredXYWithZScore(chX, compX, statX, chY, compY, statY);
        } else {
            filtered = getFilteredXY(chX, compX, statX, chY, compY, statY);
        }
        currentScatter.setData(filtered[0], filtered[1], chX, chY);
        if (markerStats != null) {
            applyAxisRangeFor(currentScatter, currentNode);
        }
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

    private String get2DChannelX(GateNode node) {
        if (node instanceof Region2DGate region) return region.getChannelX();
        if (node instanceof QuadrantGate qg) return qg.getChannelX();
        return null;
    }

    private String get2DChannelY(GateNode node) {
        if (node instanceof Region2DGate region) return region.getChannelY();
        if (node instanceof QuadrantGate qg) return qg.getChannelY();
        return null;
    }

    // Per-axis compartment/statistic, read through the gate's parallel
    // getCompartments()/getStatistics() lists exactly as GatingEngine.compAt and
    // statAt do — quadrant gates carry one per axis; shape gates carry none yet and
    // fall back to whole-cell mean in both layers.

    private Compartment get2DCompartmentX(GateNode node) { return node.compartmentAt(0); }

    private Compartment get2DCompartmentY(GateNode node) { return node.compartmentAt(1); }

    private Statistic get2DStatisticX(GateNode node) { return node.statisticAt(0); }

    private Statistic get2DStatisticY(GateNode node) { return node.statisticAt(1); }
}
