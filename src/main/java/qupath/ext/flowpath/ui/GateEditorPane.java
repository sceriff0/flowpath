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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import qupath.ext.flowpath.cohort.CohortCurves;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.ui.editor.EditorAlignment;
import qupath.ext.flowpath.ui.editor.EditorContext;
import qupath.ext.flowpath.ui.editor.EditorLabels;
import qupath.ext.flowpath.ui.editor.GateTypeEditor;
import qupath.ext.flowpath.ui.editor.GateTypeEditors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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

    // --- Cohort gating ---
    /** Per-gate "Correct staining" (U2); shown only while a cohort is available. */
    private final CheckBox correctStainingBox;
    /** Per-gate "Lineage marker" tick (marker rules, flag type 5); threshold gates in a cohort only. */
    private final CheckBox lineageMarkerBox;
    /** A slide Manual/Skip, shown as a banner rather than drawn: its number is this slide's own raw value. */
    static final String CUT_LOCKED_HINT =
            "This slide has its own threshold — open it from the Cohort window, or use the cohort value";

    private final Label slideSettingLabel;
    /** Which slide is the reference and what that means here ({@link EditorLabels#referenceLine}); shown with a cohort. */
    private final Label referenceLineLabel = new Label();
    /** Shown while the cut is locked; see {@link #setCutEditable}. */
    private final Label cutLockedLabel = new Label(CUT_LOCKED_HINT);
    private boolean cutEditable = true;
    /** The shown gate's setting on the open slide, as last handed to {@link #setSlideSetting}. */
    private SlideSetting slideSetting;
    private final Button clearSlideSettingButton;
    private final HBox slideSettingRow;
    private EditorAlignment editorAlignment = EditorAlignment.IDENTITY;
    private Runnable onClearSlideSetting;

    /** "This slide" / "All slides" (U1); shown only while a cohort is available. */
    final ToggleGroup viewModeGroup = new ToggleGroup();
    final ToggleButton thisSlideButton = new ToggleButton("This slide");
    final ToggleButton allSlidesButton = new ToggleButton("All slides");
    private final HBox viewModeRow;
    private boolean cohortAvailable;
    private CohortSession.ViewMode viewMode = CohortSession.ViewMode.THIS_SLIDE;
    private Function<GateNode, List<CohortCurves.SlideValues>> cohortValues = g -> List.of();
    /** Which slides the review flagged on a gate (by the host, by value); asked only in All slides. */
    private Function<GateNode, Set<String>> flaggedSlides = g -> Set.of();
    private Consumer<CohortSession.ViewMode> onViewModeChanged;

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
    private Consumer<GateNode> onNodeNormalised;
    private Consumer<GateNode> onDiscreteEdit;
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

        correctStainingBox = new CheckBox("Correct staining");
        correctStainingBox.getStyleClass().add("fp-primary-text");
        correctStainingBox.setTooltip(new Tooltip(
            "Carry this gate's numbers to every other slide through that slide's staining alignment.\n" +
            "The numbers you edit are on the reference slide.\n" +
            "Ellipse gates are carried by their bounding box and polygon edges between vertices\n" +
            "bend slightly — both are approximate near their outline."));
        correctStainingBox.setVisible(false);
        correctStainingBox.managedProperty().bind(correctStainingBox.visibleProperty());
        correctStainingBox.selectedProperty().addListener((obs, old, val) -> {
            if (suppressEvents || currentNode == null) return;
            // Written before it is reported, like every other editor write; a discrete edit, so
            // the host records it as its own undo step, never coalesced with a drag before it.
            currentNode.setCorrectStaining(val);
            // The seam answers from the flag, so the plot moves between raw and aligned units.
            if (typeEditor != null) typeEditor.refresh();
            fireDiscreteEdit();
        });

        lineageMarkerBox = new CheckBox("Lineage marker");
        lineageMarkerBox.getStyleClass().add("fp-primary-text");
        lineageMarkerBox.setTooltip(new Tooltip(
            "A lineage marker should not be positive together with another lineage marker.\n" +
            "FlowPath flags slides where such double positives are unusually common."));
        lineageMarkerBox.setVisible(false);
        lineageMarkerBox.managedProperty().bind(lineageMarkerBox.visibleProperty());
        lineageMarkerBox.selectedProperty().addListener((obs, old, val) -> {
            if (suppressEvents || currentNode == null) return;
            // Written before it is reported, as a discrete edit: its own undo step, then a rescore.
            currentNode.setLineageMarker(val);
            fireDiscreteEdit();
        });

        referenceLineLabel.getStyleClass().add("fp-hint");
        referenceLineLabel.setWrapText(true);
        // Slide names carry underscores; a mnemonic would swallow the first one.
        referenceLineLabel.setMnemonicParsing(false);
        referenceLineLabel.setVisible(false);
        referenceLineLabel.managedProperty().bind(referenceLineLabel.visibleProperty());

        slideSettingLabel = new Label();
        slideSettingLabel.getStyleClass().add("fp-hint");
        slideSettingLabel.setWrapText(true);
        clearSlideSettingButton = new Button("Use the cohort value");
        clearSlideSettingButton.setTooltip(new Tooltip("Drop this slide's own setting and use the gate's cohort value here"));
        clearSlideSettingButton.setOnAction(e -> { if (onClearSlideSetting != null) onClearSlideSetting.run(); });
        slideSettingRow = new HBox(8, slideSettingLabel, clearSlideSettingButton);
        slideSettingRow.setVisible(false);
        slideSettingRow.managedProperty().bind(slideSettingRow.visibleProperty());
        HBox.setHgrow(slideSettingLabel, Priority.ALWAYS);
        cutLockedLabel.getStyleClass().add("fp-hint");
        cutLockedLabel.setWrapText(true);
        cutLockedLabel.setVisible(false);
        cutLockedLabel.managedProperty().bind(cutLockedLabel.visibleProperty());

        thisSlideButton.setToggleGroup(viewModeGroup);
        allSlidesButton.setToggleGroup(viewModeGroup);
        thisSlideButton.setSelected(true);
        thisSlideButton.setTooltip(new Tooltip("Plot the open slide's cells only"));
        allSlidesButton.setTooltip(new Tooltip(
            "Also plot every sampled slide's cells for this gate, in the reference slide's units.\n" +
            "A child gate shows each slide's own parent population. You still edit the reference values."));
        viewModeGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val == null) {
                // Clicking the selected toggle deselects it; one of the two is always on.
                if (old != null) viewModeGroup.selectToggle(old);
                return;
            }
            if (suppressEvents) return;
            CohortSession.ViewMode mode = val == allSlidesButton
                ? CohortSession.ViewMode.ALL_SLIDES : CohortSession.ViewMode.THIS_SLIDE;
            if (mode == viewMode) return;
            viewMode = mode;
            refreshForNewData();
            if (onViewModeChanged != null) onViewModeChanged.accept(mode);
        });
        viewModeRow = new HBox(0, thisSlideButton, allSlidesButton);
        viewModeRow.setVisible(false);
        viewModeRow.managedProperty().bind(viewModeRow.visibleProperty());

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

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, gateTypeLabel, spacer, lineageMarkerBox, correctStainingBox);

        getChildren().addAll(
            header,
            referenceLineLabel,
            viewModeRow,
            slideSettingRow,
            cutLockedLabel,
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
        boolean anotherGate = node != this.currentNode;
        this.currentNode = node;
        updateLineageMarkerVisibility();
        refreshReferenceLine();
        // The last gate's setting on the open slide is not this one's: the host hands the new
        // gate's in after showing it, and the editor built below must not draw the old cut. The
        // same gate rebuilt (a channel or column switch) keeps its setting: it still applies.
        if (anotherGate) {
            setSlideSetting(null);
            setCutEditable(true);
        }
        if (node == null) {
            withSuppressedEvents(() -> setDisabled(true));
            gateTypeLabel.setText("No gate selected");
            setSlideSetting(null);
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
        // Building pins each axis to a signal the export carries, which can write to the gate.
        List<GateAxis.Signal> storedSignals = signalsOf(node);
        withSuppressedEvents(() -> {
            setDisabled(false);

            clipLowSpinner.getValueFactory().setValue(node.getClipPercentileLow());
            clipHighSpinner.getValueFactory().setValue(node.getClipPercentileHigh());
            excludeOutliersBox.setSelected(node.isExcludeOutliers());
            correctStainingBox.setSelected(node.isCorrectStaining());
            lineageMarkerBox.setSelected(node.isLineageMarker());

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
        // Reported as its own change, after the build and outside suppression, and only when
        // something was actually written — reopening a gate already pinned reports nothing.
        if (currentNode == node && !signalsOf(node).equals(storedSignals) && onNodeNormalised != null) {
            onNodeNormalised.accept(node);
        }
    }

    private static List<GateAxis.Signal> signalsOf(GateNode node) {
        return GateAxis.axesOf(node).stream().map(GateAxis::signal).toList();
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
            // Typing writes the name as it goes; Enter or focus loss reports it, once, and
            // only if it differs from the name last reported. An unchanged commit used to
            // report anyway: a no-op undo step that also cleared the redo stack.
            //
            // committed[0] is captured once, when this row is built, and never re-read from
            // elsewhere -- it does not need to be. The one thing that could otherwise move the
            // ground from under it is a gate replacement (Context#replaceGate, a shape drawn
            // over this one converting its type mid-edit): copySharedSettings carries the
            // OLD branch's current name onto the NEW one unchanged, and replaceGate rebuilds
            // this very row for the replacement immediately (see its javadoc), which replaces
            // this closure -- and its committed[0] -- wholesale before anything could observe
            // a stale one.
            String[] committed = {branch.getName()};
            Runnable commitName = () -> {
                if (currentNode == null || idx >= currentNode.getBranches().size()) return;
                String name = currentNode.getBranches().get(idx).getName();
                if (java.util.Objects.equals(name, committed[0])) return;
                committed[0] = name;
                fireNodeChanged();
                buildActionButtons(currentNode);
            };
            nameField.setOnAction(e -> commitName.run());
            nameField.focusedProperty().addListener((obs, old, focused) -> {
                if (!focused) commitName.run();
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

    /**
     * How the open slide's values map into reference units, per gate axis. A data change, so the
     * editor on screen is refreshed, never rebuilt (a rebuild discards a half-drawn polygon).
     */
    public void setEditorAlignment(EditorAlignment alignment) {
        this.editorAlignment = alignment == null ? EditorAlignment.IDENTITY : alignment;
        refreshReferenceLine();
        refreshForNewData();
    }

    /**
     * Re-read the reference line from the alignment seam: the tree's reference, the open slide and
     * the shown gate's first-axis correction there. The host calls it whenever the open slide, the
     * shown gate or the cohort's model may have changed.
     */
    public void refreshReferenceLine() {
        referenceLineLabel.setVisible(cohortAvailable);
        if (!cohortAvailable) return;
        Alignment axis0 = currentNode == null ? null : editorAlignment.forAxis(currentNode, 0);
        referenceLineLabel.setText(EditorLabels.referenceLine(editorAlignment.referenceName(),
                editorAlignment.currentSlideName(), editorAlignment.isReferenceSlide(), axis0));
    }

    /** The reference line's text as shown, or null while it is hidden. For tests. */
    String referenceLineText() {
        return referenceLineLabel.isVisible() ? referenceLineLabel.getText() : null;
    }

    /**
     * Whether a cohort is available; the "Correct staining" switch, the "Lineage marker" tick
     * (threshold gates only) and the This slide / All slides toggle are offered only then. Losing the cohort while in All slides drops the
     * other slides' values from the plot at once (a refresh, not a rebuild).
     */
    public void setCohortAvailable(boolean available) {
        correctStainingBox.setVisible(available);
        viewModeRow.setVisible(available);
        boolean changed = available != cohortAvailable;
        cohortAvailable = available;
        updateLineageMarkerVisibility();
        refreshReferenceLine();
        if (changed && viewMode == CohortSession.ViewMode.ALL_SLIDES) refreshForNewData();
    }

    /**
     * Where the All slides view gets a gate's per-slide values (the host answers from
     * {@code CohortCurves.of}); asked only while in All slides with a cohort available.
     */
    public void setCohortValues(Function<GateNode, List<CohortCurves.SlideValues>> provider) {
        this.cohortValues = provider == null ? g -> List.of() : provider;
        refreshForNewData();
    }

    /**
     * Where the All slides view learns which slides the review flagged on a gate, to mark their
     * ridges and name them (spec §6 "Reviewing by gate"). A view only: a refresh, not a rebuild.
     */
    public void setFlaggedSlides(Function<GateNode, Set<String>> provider) {
        this.flaggedSlides = provider == null ? g -> Set.of() : provider;
        refreshForNewData();
    }

    /** Show {@code mode}. Programmatic, so not reported to {@link #setOnViewModeChanged}. */
    public void setViewMode(CohortSession.ViewMode mode) {
        CohortSession.ViewMode m = mode == null ? CohortSession.ViewMode.THIS_SLIDE : mode;
        withSuppressedEvents(() ->
            viewModeGroup.selectToggle(m == CohortSession.ViewMode.ALL_SLIDES ? allSlidesButton : thisSlideButton));
        if (m == viewMode) return;
        viewMode = m;
        refreshForNewData();
    }

    public CohortSession.ViewMode viewMode() { return viewMode; }

    /** Called when the user switches between This slide and All slides. */
    public void setOnViewModeChanged(Consumer<CohortSession.ViewMode> callback) { this.onViewModeChanged = callback; }

    /** Re-read the shown gate's data (the cohort's values included) without a rebuild. */
    public void refreshEditor() {
        refreshForNewData();
    }

    /**
     * The shown gate's setting on the open slide. A {@code Manual} or {@code Skip} is shown as a
     * banner with a way back to the cohort value; {@code null} or {@code Reviewed} hides it.
     */
    public void setSlideSetting(SlideSetting setting) {
        boolean changed = !java.util.Objects.equals(this.slideSetting, setting);
        this.slideSetting = setting;
        if (changed && typeEditor != null) typeEditor.slideSettingChanged();
        String text = null;
        if (setting instanceof SlideSetting.Manual manual) {
            List<String> values = new ArrayList<>();
            for (int k = 0; k < manual.values().axisCount(); k++) {
                for (double v : manual.values().axis(k)) values.add(String.format(Locale.US, "%.4f", v));
            }
            text = "Adjusted on this slide: " + String.join(", ", values) + " (raw)";
        } else if (setting instanceof SlideSetting.Skip) {
            text = "Skipped on this slide — its cells are unmeasured";
        }
        slideSettingLabel.setText(text == null ? "" : text);
        slideSettingRow.setVisible(text != null);
    }

    /**
     * Whether the shown gate's cut may be moved (see {@code EditorContext.cutEditable}). Locked,
     * the type editor disables its cut controls and a hint says why and what to do instead.
     */
    public void setCutEditable(boolean editable) {
        cutLockedLabel.setVisible(!editable);
        if (editable == cutEditable) return;
        cutEditable = editable;
        if (typeEditor != null) typeEditor.slideSettingChanged();
    }

    public boolean isCutEditable() { return cutEditable; }

    /** Called by "Use the cohort value": drop the open slide's setting for the shown gate. */
    public void setOnClearSlideSetting(Runnable callback) { this.onClearSlideSetting = callback; }

    public void setOnNodeChanged(Consumer<GateNode> callback) { this.onNodeChanged = callback; }
    /**
     * Called when <em>opening</em> a gate wrote to it: the gate's stored signal is not one the
     * export carries, so the editor pinned it to one that is (see
     * {@code AbstractGateTypeEditor.syncModeSelection}). Not a user edit, so not an undo step —
     * but the tree did change, so the pane settles it and re-gates. Reported separately from
     * {@link #setOnNodeChanged} so the write is never folded into the next user edit's undo step.
     */
    public void setOnNodeNormalised(Consumer<GateNode> callback) { this.onNodeNormalised = callback; }
    /**
     * Called after a discrete, already-written switch on the gate ("Correct staining", "Lineage
     * marker"). Reported apart from {@link #setOnNodeChanged}, whose edits coalesce into a drag's
     * undo step, so a tick right after a drag stays its own step — like the tree's enabled
     * checkbox.
     */
    public void setOnDiscreteEdit(Consumer<GateNode> callback) { this.onDiscreteEdit = callback; }
    public void setOnAddToBranch(IntConsumer callback) { this.onAddToBranch = callback; }
    public void setOnRemoveGate(Runnable callback) { this.onRemoveGate = callback; }
    public void setOnReplaceGate(BiConsumer<GateNode, GateNode> callback) { this.onReplaceGate = callback; }

    public void updatePopulationCounts() {
        if (typeEditor != null) typeEditor.updatePopulationCounts();
    }

    // ---- Internal ----

    /** The Lineage marker tick is offered on a shown threshold gate while a cohort is available. */
    private void updateLineageMarkerVisibility() {
        lineageMarkerBox.setVisible(cohortAvailable && currentNode != null
                && "threshold".equals(currentNode.getGateType()));
    }

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
        to.setCorrectStaining(from.isCorrectStaining());
        to.setLineageMarker(from.isLineageMarker());
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

    private void fireDiscreteEdit() {
        if (onDiscreteEdit != null && currentNode != null) onDiscreteEdit.accept(currentNode);
    }

    private static String toWebColor(Color c) {
        return String.format("#%02x%02x%02x",
            (int)(c.getRed() * 255), (int)(c.getGreen() * 255), (int)(c.getBlue() * 255));
    }

    // primaryLabel/createSectionHeader used to keep their own copy of these two builders;
    // both now delegate to EditorLabels, the one copy every ui.editor gate-type editor
    // already shares (ui already depends one-way on ui.editor -- see
    // UiPackageDependencyDirectionTest -- so this pane can too).
    private static Label primaryLabel(String text) {
        return EditorLabels.styledLabel(text, "fp-primary-text");
    }

    private static Label createSectionHeader(String text) {
        return EditorLabels.sectionHeader(text);
    }

    /** What the type editor sees of this pane. */
    private final class Context implements EditorContext {
        @Override public CellIndex cellIndex() { return cellIndex; }
        @Override public MarkerStats markerStats() { return markerStats; }
        @Override public CompartmentCapability capability() { return compartmentCapability; }
        @Override public boolean[] roiMask() { return roiMask; }
        @Override public boolean[] ancestorMask() { return ancestorMask; }
        @Override public ObservableList<String> channelNames() { return channelNames; }
        @Override public Alignment displayAlignment(GateNode gate, int axis) { return editorAlignment.forAxis(gate, axis); }
        @Override public String referenceName() { return editorAlignment.referenceName(); }
        @Override public SlideSetting slideSetting() { return slideSetting; }
        @Override public boolean cutEditable() { return cutEditable; }
        @Override public List<CohortCurves.SlideValues> cohortValues(GateNode gate) {
            return viewMode == CohortSession.ViewMode.ALL_SLIDES && cohortAvailable
                ? cohortValues.apply(gate) : List.of();
        }
        @Override public Set<String> flaggedSlides(GateNode gate) {
            return viewMode == CohortSession.ViewMode.ALL_SLIDES ? flaggedSlides.apply(gate) : Set.of();
        }
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
            updateLineageMarkerVisibility();
            // Rebuilt for the replacement AT ONCE, unlike showLater's full rebuild (deferred
            // to the next pulse so it does not tear down the scatter canvas a drag may still
            // be in progress on): neither of these two areas holds an input gesture of its
            // own, so nothing is lost by rebuilding them now, and it closes the window in
            // which the branch-name row's commit baseline would otherwise still belong to the
            // gate this row was built for rather than the one now shown.
            GateEditorPane.this.withSuppressedEvents(() -> {
                buildBranchNamesEditor(replacement);
                buildActionButtons(replacement);
            });
        }
    }
}
