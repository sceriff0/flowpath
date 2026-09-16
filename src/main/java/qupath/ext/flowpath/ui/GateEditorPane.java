package qupath.ext.flowpath.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.ui.editor.EditorContext;
import qupath.ext.flowpath.ui.editor.GateTypeEditor;
import qupath.ext.flowpath.ui.editor.GateTypeEditors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Right-side editor panel for configuring a single gate node.
 * <p>
 * The pane owns the chrome every gate type shares — the type label, outlier clipping, branch
 * names and colours, and the action buttons — and exactly one {@link GateTypeEditor} for the
 * type-specific controls, chosen by {@link GateTypeEditors#forGate}. Opening another gate
 * disposes that editor and builds a new one, so no widget of the previous gate can survive
 * into the next; new data reaches it through {@link GateTypeEditor#refresh} without a rebuild.
 */
public class GateEditorPane extends VBox {

    // --- Shared chrome ---
    private final Label gateTypeLabel;
    private final Spinner<Double> clipLowSpinner;
    private final Spinner<Double> clipHighSpinner;
    private final CheckBox excludeOutliersBox;
    private final Label clipInfoLabel;
    private final VBox gateSpecificArea;
    private final VBox branchNamesArea;
    private final VBox actionButtonArea;

    private final ObservableList<String> channelNames = FXCollections.observableArrayList();

    /** The gate on screen, or {@code null}. */
    private GateNode currentNode;
    /** The controls for {@link #currentNode}'s type; {@code null} exactly when it is. */
    private GateTypeEditor typeEditor;

    private CellIndex cellIndex;
    private MarkerStats markerStats;
    private CompartmentCapability compartmentCapability;
    private boolean[] roiMask;
    private boolean[] ancestorMask;
    private boolean suppressEvents = false;

    private Consumer<GateNode> onNodeChanged;
    private IntConsumer onAddToBranch;
    private Runnable onRemoveGate;
    private BiConsumer<GateNode, GateNode> onReplaceGate;

    private final EditorContext context = new Context();

    public GateEditorPane() {
        setSpacing(8);
        setPadding(new Insets(10));
        getStyleClass().add("fp-panel");

        gateTypeLabel = new Label("No gate selected");
        gateTypeLabel.getStyleClass().add("fp-section-header");
        gateTypeLabel.setStyle("-fx-font-size: 11;");

        // --- Outlier clipping ---
        clipLowSpinner = new Spinner<>(0.0, 50.0, 1.0, 0.5);
        clipLowSpinner.setPrefWidth(75);
        clipLowSpinner.setEditable(true);
        clipHighSpinner = new Spinner<>(50.0, 100.0, 99.0, 0.5);
        clipHighSpinner.setPrefWidth(75);
        clipHighSpinner.setEditable(true);
        excludeOutliersBox = new CheckBox("Exclude outliers");
        excludeOutliersBox.getStyleClass().add("fp-primary-text");
        excludeOutliersBox.setTooltip(new Tooltip(
            "When enabled, cells with marker values outside the clip percentile range\n" +
            "are classified as 'Excluded' in QuPath and flagged Outlier=True in the CSV.\n" +
            "Their would-have-been phenotype is still written to the CSV but they don't\n" +
            "contribute to branch counts.\n" +
            "Percentiles are computed from all quality-passing cells, not per gate population."));

        clipLowSpinner.valueProperty().addListener((obs, old, val) -> {
            if (suppressEvents || currentNode == null) return;
            double clamped = Math.min(val, clipHighSpinner.getValue() - 0.5);
            if (clamped != val) { clipLowSpinner.getValueFactory().setValue(clamped); return; }
            currentNode.setClipPercentileLow(val);
            // The clip window is every type editor's axis window: histogram and slider range,
            // scatter axes, quadrant slider travel.
            if (typeEditor != null) typeEditor.refresh();
            fireNodeChanged();
        });
        clipHighSpinner.valueProperty().addListener((obs, old, val) -> {
            if (suppressEvents || currentNode == null) return;
            double clamped = Math.max(val, clipLowSpinner.getValue() + 0.5);
            if (clamped != val) { clipHighSpinner.getValueFactory().setValue(clamped); return; }
            currentNode.setClipPercentileHigh(val);
            if (typeEditor != null) typeEditor.refresh();
            fireNodeChanged();
        });
        excludeOutliersBox.selectedProperty().addListener((obs, old, val) -> {
            if (suppressEvents || currentNode == null) return;
            currentNode.setExcludeOutliers(val);
            fireNodeChanged();
        });

        clipInfoLabel = new Label("Percentiles based on all cells, not this gate's population");
        clipInfoLabel.getStyleClass().add("fp-hint");
        clipInfoLabel.setStyle("-fx-font-size: 9;");
        clipInfoLabel.setVisible(false);
        clipInfoLabel.managedProperty().bind(clipInfoLabel.visibleProperty());

        HBox clipRow = new HBox(6,
            primaryLabel("Clip:"), clipLowSpinner, primaryLabel("% to"),
            clipHighSpinner, primaryLabel("%"), excludeOutliersBox);

        gateSpecificArea = new VBox(4);
        branchNamesArea = new VBox(4);
        actionButtonArea = new VBox(4);

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

    /** The gate this editor currently shows, or {@code null}. */
    public GateNode getGateNode() {
        return currentNode;
    }

    /**
     * Show {@code node}, or nothing. The previous type editor is disposed first, so none of its
     * controls can write to {@code node} or to the gate it showed.
     */
    public void setGateNode(GateNode node) {
        if (typeEditor != null) {
            typeEditor.dispose();
            typeEditor = null;
        }
        this.currentNode = node;
        if (node == null) {
            withSuppressedEvents(() -> setDisabled(true));
            gateTypeLabel.setText("No gate selected");
            gateSpecificArea.getChildren().clear();
            Label hint = new Label("Select a gate from the tree to edit it,\nor click '+ Add Root Gate' to create one.");
            hint.getStyleClass().add("fp-hint");
            hint.setStyle("-fx-font-size: 11;");
            hint.setWrapText(true);
            gateSpecificArea.getChildren().add(hint);
            branchNamesArea.getChildren().clear();
            actionButtonArea.getChildren().clear();
            return;
        }
        withSuppressedEvents(() -> {
            setDisabled(false);

            clipLowSpinner.getValueFactory().setValue(node.getClipPercentileLow());
            clipHighSpinner.getValueFactory().setValue(node.getClipPercentileHigh());
            excludeOutliersBox.setSelected(node.isExcludeOutliers());

            String typeDisplay = switch (node.getGateType()) {
                case "threshold" -> "Threshold Gate";
                case "quadrant" -> "Quadrant Gate";
                case "polygon" -> "Polygon Gate";
                case "rectangle" -> "Rectangle Gate";
                case "ellipse" -> "Ellipse Gate";
                default -> "Gate";
            };
            gateTypeLabel.setText(typeDisplay);

            GateTypeEditor editor = GateTypeEditors.forGate(node, context);
            typeEditor = editor;
            gateSpecificArea.getChildren().setAll(editor.build());

            buildBranchNamesEditor(node);
            buildActionButtons(node);
        });
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
                    if (typeEditor != null) typeEditor.branchColorsChanged();
                    fireNodeChanged();
                }
            });

            Label countLabel = new Label(String.format("%,d", branch.getCount()));
            countLabel.getStyleClass().add("fp-muted");
            countLabel.setStyle("-fx-font-size: 10;");

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

    public void setChannelNames(List<String> names) { channelNames.setAll(names); }

    /** Per-compartment availability for the loaded image (drives the signal-type selectors). */
    public void setCompartmentCapability(CompartmentCapability capability) {
        this.compartmentCapability = capability;
    }

    public void setCellIndex(CellIndex index) { this.cellIndex = index; }

    public void setMarkerStats(MarkerStats stats) {
        this.markerStats = stats;
        refreshForNewData();
    }

    public void setRoiMask(boolean[] mask) {
        this.roiMask = mask;
        refreshForNewData();
    }

    public void setAncestorMask(boolean[] mask) {
        this.ancestorMask = mask;
        clipInfoLabel.setVisible(mask != null);
        refreshForNewData();
    }

    /**
     * Bring every data-driven control of the gate on screen in line with new statistics or
     * masks, without rebuilding the editor (a rebuild discards a half-drawn polygon, so the
     * pane skips it when only the data changed). Which controls those are is the type
     * editor's: the histogram and threshold slider; the scatter plot and, for a quadrant, the
     * sliders whose travel is the scatter's clip window.
     */
    private void refreshForNewData() {
        if (typeEditor != null) typeEditor.refresh();
    }

    public void setOnNodeChanged(Consumer<GateNode> callback) { this.onNodeChanged = callback; }
    public void setOnAddToBranch(IntConsumer callback) { this.onAddToBranch = callback; }
    public void setOnRemoveGate(Runnable callback) { this.onRemoveGate = callback; }
    public void setOnReplaceGate(BiConsumer<GateNode, GateNode> callback) { this.onReplaceGate = callback; }

    public void updatePopulationCounts() {
        if (typeEditor != null) typeEditor.updatePopulationCounts();
    }

    // ---- Internal ----

    /**
     * Carry a gate's settings onto its replacement when the user converts one gate
     * type into another by drawing a different shape.
     * <p>
     * The drawn coordinates are read straight off the scatter plot, which renders each
     * axis' resolved column (channel + compartment + statistic) as measured. The axis
     * signals therefore have to travel with the shape. If they do not,
     * {@code GatingEngine} evaluates the boundary against different columns than it was
     * drawn over — the overlay still renders over the points, so nothing looks wrong
     * while every cell is misclassified.
     * <p>
     * Package-private and static so the conversion contract is testable without a
     * JavaFX toolkit.
     */
    static void copySharedSettings(GateNode from, GateNode to) {
        to.setClipPercentileLow(from.getClipPercentileLow());
        to.setClipPercentileHigh(from.getClipPercentileHigh());
        to.setExcludeOutliers(from.isExcludeOutliers());
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

    /** Re-entrant: a nested call must not lift the outer call's suppression early. */
    private void withSuppressedEvents(Runnable action) {
        boolean previous = suppressEvents;
        suppressEvents = true;
        try { action.run(); } finally { suppressEvents = previous; }
    }

    private void fireNodeChanged() {
        if (onNodeChanged != null && currentNode != null) onNodeChanged.accept(currentNode);
    }

    private static String toWebColor(Color c) {
        return String.format("#%02x%02x%02x",
            (int)(c.getRed() * 255), (int)(c.getGreen() * 255), (int)(c.getBlue() * 255));
    }

    private static Label primaryLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("fp-primary-text");
        return label;
    }

    private static Label createSectionHeader(String text) {
        Label header = new Label(text);
        header.getStyleClass().add("fp-section-header");
        header.setStyle("-fx-font-size: 10;");
        header.setPadding(new Insets(4, 0, 0, 0));
        return header;
    }

    /** What the type editor sees of this pane. */
    private final class Context implements EditorContext {
        @Override public CellIndex cellIndex() { return cellIndex; }
        @Override public MarkerStats markerStats() { return markerStats; }
        @Override public CompartmentCapability capability() { return compartmentCapability; }
        @Override public boolean[] roiMask() { return roiMask; }
        @Override public boolean[] ancestorMask() { return ancestorMask; }
        @Override public ObservableList<String> channelNames() { return channelNames; }
        @Override public GateNode shownGate() { return currentNode; }
        @Override public boolean eventsSuppressed() { return suppressEvents; }
        @Override public void withSuppressedEvents(Runnable action) { GateEditorPane.this.withSuppressedEvents(action); }
        @Override public void gateChanged() { fireNodeChanged(); }
        @Override public void branchNamesChanged() { buildBranchNamesEditor(currentNode); }
        @Override public void show(GateNode gate) { setGateNode(gate); }

        @Override
        public void showLater(GateNode gate) {
            Platform.runLater(() -> {
                if (currentNode == gate) setGateNode(gate);
            });
        }

        @Override
        public void replaceGate(GateNode old, GateNode replacement) {
            replacement.setEnabled(old.isEnabled());
            copySharedSettings(old, replacement);
            if (onReplaceGate != null) onReplaceGate.accept(old, replacement);
            currentNode = replacement;
        }
    }
}
