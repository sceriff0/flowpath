package qupath.ext.flowpath.umap.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestOptions;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.umap.engine.EmbeddingFeatures;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.engine.UmapComputeService;
import qupath.ext.flowpath.umap.session.UmapSession;
import qupath.ext.flowpath.umap.session.ViewState;
import qupath.ext.flowpath.umap.model.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.util.*;

/**
 * Main panel for the qUMAP extension.
 * Orchestrates UMAP computation, visualization, polygon gating, and marker overlays.
 */
public class UmapPane extends BorderPane {

    private final QuPathGUI qupath;

    /**
     * Everything this view <em>knows</em>, as opposed to everything it <em>shows</em>:
     * the cell index, the gating snapshot, the feature selection, the phenotype colours,
     * the gate mask and the population tags, together with the rules that govern them.
     * The pane owns widgets, layout and event forwarding; every question about the data
     * is asked here. {@link UiStateController} is the same split one level up — it owns
     * what a UI state means, this owns what the data means.
     */
    private final UmapSession session = new UmapSession();

    /** Reentrancy guard so our own selection pushes don't echo back as viewer events. */
    private boolean syncingSelection = false;

    private PathObjectSelectionListener selectionListener;

    // Engine
    private final UmapComputeService computeService;

    // UI state machine (centralizes enable/disable/visibility transitions)
    private final UiStateController uiState;

    // Compute lifecycle (spinners, presets, runUmap/cancel/onComplete/onError, progressDialog)
    private final ComputeController computeController;

    // UI components
    private final UmapCanvas umapCanvas;
    private final MarkerOverlayCanvas markerOverlay;
    private final PhenotypeLegend legend;
    private final ColorScaleLegend colorScaleLegend;
    private final PolygonSelector polygonSelector;
    private final Label statusLabel;
    private final ProgressIndicator progressIndicator;

    // Controls
    private final ComboBox<String> markerDropdown;
    private final ComboBox<String> colorScaleDropdown;
    private final TextField tagNameField;
    private final ColorPicker tagColorPicker;
    private final ToggleButton drawButton;
    private final Button clearButton;
    private final Button applyTagButton;
    private final Button exportButton;
    private final Button featuresButton;
    private final CheckBox roiFilterCheckBox;
    private final CheckBox viewerSyncCheckBox;

    // Per-marker feature picker (compartment + statistic + include), shown in a popup
    private final FeatureSelectionPane featureSelectionPane;
    private final ContextMenu featuresPopup;

    private javafx.beans.value.ChangeListener<ImageData<?>> imageDataListener;

    // Marker overlay visibility
    private final SplitPane centerSplit;
    private boolean markerOverlayVisible = false;

    // --- Rail chrome ---
    private static final double RAIL_WIDTH = 236;
    private static final String RAIL_CONTROL_STYLE = "-fx-text-fill: #d5d5d5; -fx-font-size: 11;";
    private static final String RAIL_LABEL_STYLE = "-fx-text-fill: #9aa0a6; -fx-font-size: 10;";
    private static final String SECTION_HEADER_STYLE =
            "-fx-text-fill: #7f8a94; -fx-font-size: 10; -fx-font-weight: bold;";
    private static final String PRIMARY_BUTTON_STYLE =
            "-fx-base: #2563eb; -fx-text-fill: white; -fx-font-weight: bold;";

    private Label cellsSummary;
    private ProgressBar computeProgress;
    private Label computeStage;
    private Label failureBanner;
    private ToggleGroup colorModeGroup;
    private ToggleButton colorByPhenotype;
    private ToggleButton colorByMarker;
    private StackPane emptyState;
    private Label emptyHeadline;
    private Label emptySubline;
    private Button emptyAction;

    public UmapPane(QuPathGUI qupath) {
        this.qupath = qupath;

        // --- Initialize components ---
        computeService = new UmapComputeService();
        umapCanvas = new UmapCanvas();
        markerOverlay = new MarkerOverlayCanvas();
        legend = new PhenotypeLegend();
        colorScaleLegend = new ColorScaleLegend();
        polygonSelector = new PolygonSelector(umapCanvas);
        statusLabel = new Label("Load an image with cell detections");
        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(16, 16);

        // --- Non-compute controls (compute controls are built inside ComputeController) ---

        markerDropdown = new ComboBox<>();
        markerDropdown.setPromptText(UmapSession.NO_MARKER);
        markerDropdown.setPrefWidth(120);
        markerDropdown.setTooltip(new Tooltip("Select a marker to color cells by expression level."));
        markerDropdown.setOnAction(e -> onMarkerSelected());

        colorScaleDropdown = new ComboBox<>(javafx.collections.FXCollections.observableArrayList("Z-score", "Raw"));
        colorScaleDropdown.setValue("Z-score");
        colorScaleDropdown.setPrefWidth(75);
        colorScaleDropdown.setTooltip(new Tooltip(
                "Z-score: normalized (blue=low, red=high).\n" +
                "Raw: actual measurement values."));
        colorScaleDropdown.setOnAction(e -> onMarkerSelected());

        roiFilterCheckBox = new CheckBox("Filter by annotations");
        roiFilterCheckBox.setTooltip(new Tooltip(
                "Only include cells inside annotation ROIs.\nDraw annotations in QuPath first, then check this box."));
        // Changing the filter changes which cells are indexed, so the embedding and
        // any gate built on it no longer apply — but this is NOT an image switch, and
        // must not be routed through the image-change teardown (which used to ask
        // "Switching images will clear...", a question about something that hadn't
        // happened, and left the checkbox lying about the displayed data if declined).
        roiFilterCheckBox.selectedProperty().addListener((obs, o, n) ->
                reloadCells("Annotation filter changed"));

        viewerSyncCheckBox = new CheckBox("Link viewer");
        viewerSyncCheckBox.setSelected(true);
        viewerSyncCheckBox.setTooltip(new Tooltip(
                "Two-way link with the QuPath image viewer:\n"
                        + "• Click a UMAP point to select that cell and centre the viewer on it.\n"
                        + "• Closing a gate selects those cells, highlighting them on the tissue.\n"
                        + "• Selecting cells in the viewer highlights them in the UMAP.\n"
                        + "Selection only — this never changes cell classifications."));
        viewerSyncCheckBox.selectedProperty().addListener((obs, o, on) -> {
            if (!on) {
                clearViewerSelection();
                umapCanvas.setHighlightMask(null);
            } else if (session.hasGate()) {
                pushGateSelectionToViewer();
            }
        });

        drawButton = drawToggleFor(polygonSelector);

        clearButton = new Button("Clear Shape");
        clearButton.setTooltip(new Tooltip("Remove polygon gate and restore all cell classes. (Esc)"));
        clearButton.setOnAction(e -> clearPolygon());

        tagNameField = new TextField();
        tagNameField.setPromptText("e.g. CD4+ T cells");
        tagNameField.setPrefWidth(120);
        tagNameField.setTooltip(new Tooltip(
                "Name for the gated population.\nCells inside the polygon get this label in QuPath."));
        // Clear any error highlight as soon as the user starts typing a name.
        tagNameField.textProperty().addListener((obs, o, n) -> tagNameField.setStyle(""));

        tagColorPicker = new ColorPicker(Color.ORANGE);
        tagColorPicker.setPrefWidth(50);

        applyTagButton = new Button("Tag Selection");
        applyTagButton.setTooltip(new Tooltip(
                "Label polygon-selected cells as a named population.\nAdds a classification suffix in QuPath's hierarchy."));
        applyTagButton.setOnAction(e -> applyPopulationTag());

        exportButton = new Button("Export Data");
        exportButton.setTooltip(new Tooltip(
                "Export UMAP coordinates, markers, and population tags to CSV.\nIncludes all cells. (Ctrl+E)"));
        exportButton.setOnAction(e -> exportCsv());

        // Per-marker feature picker. Lives in a popup anchored to the button so it
        // doesn't crowd the toolbar. Editing a row mutates the session's selection in place;
        // onChanged persists the selection and rebuilds the cell index so the next
        // UMAP run uses the chosen keys.
        featureSelectionPane = new FeatureSelectionPane();
        featureSelectionPane.setOnChanged(this::onFeatureSelectionChanged);
        var featuresMenuItem = new CustomMenuItem(featureSelectionPane, false);
        featuresPopup = new ContextMenu(featuresMenuItem);
        featuresButton = new Button("Features...");
        featuresButton.setTooltip(new Tooltip(
                "Choose which markers feed the UMAP and, for rich data,\n"
                        + "the compartment + statistic used for each."));
        featuresButton.setOnAction(e ->
                featuresPopup.show(featuresButton, javafx.geometry.Side.BOTTOM, 0, 0));

        // ComputeController owns the compute / cancel buttons and the embedding
        // parameters, all of which the state machine needs; it is therefore built first,
        // the state machine second (once every widget exists), and injected back via
        // attachUiState. Lifecycle methods on ComputeController must not run before that.
        computeController = new ComputeController(
                computeService,
                session,
                this::onUmapResultReady,
                new StatusReporter() {
                    @Override public void report(String text, StatusLevel level) {
                        setStatus(text, level);
                    }
                    @Override public void detail(String text) {
                        setStatusDetail(text);
                    }
                },
                dotSize -> {
                    umapCanvas.setDotSize(dotSize);
                    markerOverlay.setDotSize(dotSize);
                });

        // --- Layout ---
        //
        // The standalone qUMAP put all eighteen controls in two dense toolbar rows above
        // the plot: compute parameters, display options and destructive actions side by
        // side, every one of them visible whether or not it could do anything yet. There
        // was no reading order and no sense of what to do first.
        //
        // The rail below replaces that with the actual workflow, top to bottom — check
        // your cells, embed them, colour them, select from them — with each step's
        // controls grouped under a heading and the advanced knobs folded away until
        // asked for. The plot gets the whole rest of the window.

        cellsSummary = new Label("No cells loaded");
        cellsSummary.setWrapText(true);
        cellsSummary.setStyle("-fx-text-fill: #d5d5d5; -fx-font-size: 11;");

        featuresButton.setMaxWidth(Double.MAX_VALUE);
        roiFilterCheckBox.setStyle(RAIL_CONTROL_STYLE);

        var cellsSection = section("1 · Cells", cellsSummary, featuresButton, roiFilterCheckBox);

        // --- Embedding ---
        var presetCombo = computeController.getQualityPreset();
        presetCombo.setMaxWidth(Double.MAX_VALUE);
        var scalingCombo = computeController.getScalingMode();
        scalingCombo.setMaxWidth(Double.MAX_VALUE);

        // Advanced knobs stay collapsed. They exist for the rare run that needs them,
        // and a user who does not know what "negative samples" means should never have
        // to decide whether it matters before they can see their data.
        var advancedGrid = new GridPane();
        advancedGrid.setHgap(6);
        advancedGrid.setVgap(4);
        advancedGrid.addRow(0, railLabel("Neighbours (k)"), computeController.getKSpinner());
        advancedGrid.addRow(1, railLabel("Epochs"), computeController.getEpochsSpinner());
        advancedGrid.addRow(2, railLabel("Subsample"), computeController.getSubsampleMode());
        advancedGrid.addRow(3, railLabel("Max cells"), computeController.getMaxCellsSpinner());
        for (var node : List.of(computeController.getKSpinner(), computeController.getEpochsSpinner(),
                computeController.getSubsampleMode(), computeController.getMaxCellsSpinner())) {
            ((Region) node).setPrefWidth(96);
        }

        var advancedPane = new TitledPane("Advanced", advancedGrid);
        advancedPane.setExpanded(false);
        advancedPane.setStyle("-fx-font-size: 10;");
        // Choosing "Custom" is an explicit request to tune, so open the drawer for them.
        presetCombo.valueProperty().addListener((obs, o, n) -> {
            if ("Custom".equals(n)) advancedPane.setExpanded(true);
        });

        var computeBtn = computeController.getComputeButton();
        computeBtn.setText("Run UMAP");
        computeBtn.setMaxWidth(Double.MAX_VALUE);
        computeBtn.setStyle(PRIMARY_BUTTON_STYLE);
        computeBtn.setTooltip(new Tooltip(
                "Compute the embedding from the selected features.\n"
                        + "Runs in the background — the gating window stays usable."));

        var cancelBtn = computeController.getCancelButton();
        cancelBtn.setMaxWidth(Double.MAX_VALUE);

        computeProgress = new ProgressBar();
        computeProgress.setMaxWidth(Double.MAX_VALUE);
        computeStage = new Label();
        computeStage.setWrapText(true);
        computeStage.setStyle("-fx-text-fill: #9aa0a6; -fx-font-size: 10;");

        // Where a failure goes when the plot is not empty. A re-run that dies over a
        // surviving embedding leaves the stage at COMPUTED, so the empty-state overlay —
        // the other place the reason is shown — never comes up to say so.
        failureBanner = new Label();
        failureBanner.setWrapText(true);
        failureBanner.setStyle("-fx-text-fill: #ff8080; -fx-font-size: 10;");
        failureBanner.setVisible(false);
        failureBanner.setManaged(false);

        var embedSection = section("2 · Embedding",
                railLabel("Quality"), presetCombo,
                railLabel("Feature scaling"), scalingCombo,
                advancedPane,
                computeBtn, cancelBtn, computeProgress, computeStage, failureBanner);

        // --- Colour ---
        // A segmented pair rather than two independent dropdowns: colouring by phenotype
        // and colouring by marker expression are alternatives, and the old layout — a
        // marker combo whose "-- none --" entry silently meant "phenotype mode" — made
        // that look like a setting rather than a choice.
        colorModeGroup = new ToggleGroup();
        colorByPhenotype = segmentedToggle("Phenotype", colorModeGroup);
        colorByMarker = segmentedToggle("Marker", colorModeGroup);
        colorByPhenotype.setSelected(true);
        colorByPhenotype.setTooltip(new Tooltip(
                "Colour every cell by the phenotype its gates assigned."));
        colorByMarker.setTooltip(new Tooltip(
                "Colour every cell by one marker's expression level."));

        var colorModeRow = new HBox(colorByPhenotype, colorByMarker);
        HBox.setHgrow(colorByPhenotype, Priority.ALWAYS);
        HBox.setHgrow(colorByMarker, Priority.ALWAYS);

        markerDropdown.setMaxWidth(Double.MAX_VALUE);
        colorScaleDropdown.setMaxWidth(Double.MAX_VALUE);
        var markerControls = new VBox(4,
                railLabel("Marker"), markerDropdown,
                railLabel("Scale"), colorScaleDropdown);
        markerControls.setVisible(false);
        markerControls.setManaged(false);

        colorModeGroup.selectedToggleProperty().addListener((obs, o, sel) -> {
            // A segmented control must always have exactly one segment down; clicking the
            // active one would otherwise deselect it and leave the plot in no mode at all.
            if (sel == null) {
                (o == colorByMarker ? colorByMarker : colorByPhenotype).setSelected(true);
                return;
            }
            boolean byMarker = sel == colorByMarker;
            markerControls.setVisible(byMarker);
            markerControls.setManaged(byMarker);
            if (byMarker) {
                // Land on a real marker rather than the placeholder, so switching modes
                // shows something immediately instead of an unchanged plot.
                if (UmapSession.NO_MARKER.equals(markerDropdown.getValue())
                        && !session.markers().isEmpty()) {
                    markerDropdown.setValue(session.preferredMarker());
                }
            } else {
                markerDropdown.setValue(UmapSession.NO_MARKER);
            }
            onMarkerSelected();
        });

        var dotSizeSpinner = computeController.getDotSizeSpinner();
        dotSizeSpinner.setPrefWidth(80);
        var resetViewButton = new Button("Reset view");
        resetViewButton.setMaxWidth(Double.MAX_VALUE);
        resetViewButton.setTooltip(new Tooltip("Undo zoom and pan, fitting all points in view."));
        resetViewButton.setOnAction(e -> umapCanvas.resetView());

        var dotRow = new HBox(6, railLabel("Dot size"), dotSizeSpinner);
        dotRow.setAlignment(Pos.CENTER_LEFT);

        var colorSection = section("3 · Colour", colorModeRow, markerControls, dotRow, resetViewButton);

        // --- Select ---
        drawButton.setMaxWidth(Double.MAX_VALUE);
        clearButton.setMaxWidth(Double.MAX_VALUE);
        tagNameField.setMaxWidth(Double.MAX_VALUE);
        applyTagButton.setMaxWidth(Double.MAX_VALUE);
        tagColorPicker.setMaxWidth(Double.MAX_VALUE);
        viewerSyncCheckBox.setStyle(RAIL_CONTROL_STYLE);

        var tagRow = new HBox(6, tagNameField, tagColorPicker);
        HBox.setHgrow(tagNameField, Priority.ALWAYS);
        tagColorPicker.setPrefWidth(52);

        var selectSection = section("4 · Select",
                drawButton, clearButton,
                railLabel("Name the selection"), tagRow, applyTagButton,
                viewerSyncCheckBox);

        var rail = new VBox(14, cellsSection, embedSection, colorSection, selectSection);
        rail.setPadding(new Insets(10));
        rail.setStyle("-fx-background-color: #2b2b2b;");
        rail.setPrefWidth(RAIL_WIDTH);
        rail.setMinWidth(RAIL_WIDTH);

        var railScroll = new ScrollPane(rail);
        railScroll.setFitToWidth(true);
        railScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        railScroll.setStyle("-fx-background: #2b2b2b; -fx-background-color: #2b2b2b;");
        railScroll.setPrefWidth(RAIL_WIDTH + 14);
        railScroll.setMinWidth(RAIL_WIDTH + 14);
        setLeft(railScroll);

        // --- Centre: plot + legend, with an empty state layered over it ---
        var legendBox = new VBox(legend, colorScaleLegend);
        VBox.setVgrow(legend, Priority.ALWAYS);

        centerSplit = new SplitPane(umapCanvas, legendBox);
        centerSplit.setDividerPositions(0.82);

        emptyState = buildEmptyState();
        var centerStack = new StackPane(centerSplit, emptyState);
        setCenter(centerStack);

        // --- Status bar ---
        statusLabel.setWrapText(true);
        var statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        var statusBar = new HBox(8, progressIndicator, statusLabel, statusSpacer, exportButton);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        statusBar.setStyle("-fx-background-color: #2a2a2a;");
        setBottom(statusBar);

        // Every widget now exists, so the state machine can be wired to all of them. It
        // takes no state from anyone: sync() re-derives the panel from the session, which
        // is why nothing below ever names a state.
        uiState = new UiStateController(session, new UiStateController.Controls(
                computeController.getComputeButton(), computeController.getCancelButton(),
                progressIndicator, computeProgress, computeStage, failureBanner,
                drawButton, clearButton,
                tagNameField, tagColorPicker, applyTagButton,
                exportButton,
                emptyState, emptyAction,
                roiFilterCheckBox,
                List.of(featuresButton, featureSelectionPane, markerDropdown, colorScaleDropdown,
                        computeController.getQualityPreset(), computeController.getKSpinner(),
                        computeController.getEpochsSpinner(), computeController.getMaxCellsSpinner(),
                        computeController.getSubsampleMode(), computeController.getScalingMode()),
                featuresPopup::hide));
        // Every settled change repaints the rail summary and the empty state's wording.
        // Subscribed to the SESSION rather than to uiState.stateProperty(): the wording
        // quotes cell and marker counts, which change without the ViewState changing —
        // a feature rebuild that lands on the same stage would otherwise leave the rail
        // quoting the previous index's size. The state controller subscribed first, so
        // its widgets are already coherent with the state this is handed.
        session.observe(this::refreshOverview);

        polygonSelector.setOnPolygonComplete(this::onPolygonComplete);
        umapCanvas.setOnPointPicked(this::onPointPicked);

        // Sync marker overlay zoom/pan with main canvas
        umapCanvas.setOnViewChanged(() -> {
            if (markerOverlayVisible) {
                markerOverlay.syncView(
                        umapCanvas.getViewMinX(), umapCanvas.getViewMaxX(),
                        umapCanvas.getViewMinY(), umapCanvas.getViewMaxY(),
                        umapCanvas.isViewOverride());
            }
        });

        legend.setOnPopulationRemove(this::removePopulationTag);
        legend.setOnPhenotypeToggled(this::togglePhenotypeVisibility);
        legend.setOnPhenotypeHover(this::highlightPhenotype);
        legend.setOnShowAll(() -> {
            if (!session.showAllPhenotypes()) return;
            applyPhenotypeVisibility();
            updateLegend();
        });

        // --- Listen for image changes ---
        imageDataListener = (obs, oldImg, newImg) -> Platform.runLater(this::initializeFromImage);
        qupath.imageDataProperty().addListener(imageDataListener);

        // --- Keyboard shortcuts ---
        setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> {
                    if (polygonSelector.isActive()) {
                        polygonSelector.deactivate();
                    } else if (session.hasGate()) {
                        clearPolygon();
                    }
                }
                case E -> {
                    if (e.isControlDown()) exportCsv();
                }
                default -> {}
            }
        });

        // Initialize if image already loaded
        Platform.runLater(this::initializeFromImage);
    }

    // --- Rail construction helpers ---

    /** A labelled group of rail controls, separated from its neighbours by a rule. */
    private VBox section(String title, javafx.scene.Node... children) {
        var header = new Label(title.toUpperCase(java.util.Locale.ROOT));
        header.setStyle(SECTION_HEADER_STYLE);

        var rule = new Separator(Orientation.HORIZONTAL);
        rule.setStyle("-fx-opacity: 0.25;");

        var box = new VBox(6);
        box.getChildren().addAll(header, rule);
        box.getChildren().addAll(children);
        return box;
    }

    /**
     * The Draw toggle, created already bound to {@code selector} in both directions.
     * <p>
     * A factory rather than a wire laid down beside the widget, because a wire is deletable
     * and a constructor argument is not: {@code drawButton} is final, so removing this call
     * fails to compile rather than leaving a toggle that silently stops following the
     * selector. The previous attempt at this was a loose
     * {@code selector.setOnActiveChanged(drawButton::setSelected)} line in the constructor
     * and a test that installed the same idiom on its own objects — deleting the production
     * line left the suite green, which is the failure mode this shape exists to remove.
     * <p>
     * Both directions matter. The button drives the selector when the user clicks it; the
     * selector drives the button whenever anything <em>else</em> deactivates it — Escape, a
     * snapshot teardown, a fresh embedding, an image change. Those were eight hand-written
     * {@code setSelected(false)} calls, and any path that forgot one left a pressed toggle
     * over a selector that was no longer listening.
     */
    static ToggleButton drawToggleFor(PolygonSelector selector) {
        var button = new ToggleButton("Draw Polygon");
        button.setTooltip(new Tooltip("Draw a polygon gate on the UMAP plot.\n"
                + "Click to add vertices, double-click to close.\n"
                + "Drag vertices to adjust the shape."));
        selector.setOnActiveChanged(button::setSelected);
        button.setOnAction(e -> {
            if (button.isSelected()) selector.activate();
            else selector.deactivate();
        });
        return button;
    }

    /** A dim caption above a rail control. */
    private static Label railLabel(String text) {
        var l = new Label(text);
        l.setStyle(RAIL_LABEL_STYLE);
        return l;
    }

    /** One half of a two-segment mode switch. */
    private static ToggleButton segmentedToggle(String text, ToggleGroup group) {
        var t = new ToggleButton(text);
        t.setToggleGroup(group);
        t.setMaxWidth(Double.MAX_VALUE);
        t.setStyle("-fx-font-size: 11;");
        return t;
    }

    /**
     * The overlay shown while there is no embedding.
     * <p>
     * An empty plot area is the single worst moment in the old UI: the canvas printed
     * "No UMAP data" in the middle of a grey rectangle, and the one control that would
     * fix it was a small button among seventeen others. This states what will happen,
     * on how many cells, and offers the action — so the first run is one click from the
     * place the user is already looking.
     */
    private StackPane buildEmptyState() {
        emptyHeadline = new Label("No embedding yet");
        emptyHeadline.setStyle("-fx-text-fill: #d5d5d5; -fx-font-size: 16; -fx-font-weight: bold;");

        emptySubline = new Label("Load an image with cell detections to begin.");
        emptySubline.setStyle("-fx-text-fill: #9aa0a6; -fx-font-size: 12;");
        emptySubline.setWrapText(true);
        emptySubline.setTextAlignment(TextAlignment.CENTER);
        emptySubline.setMaxWidth(380);

        emptyAction = new Button("Run UMAP");
        emptyAction.setStyle(PRIMARY_BUTTON_STYLE);
        emptyAction.setOnAction(e -> computeController.getComputeButton().fire());

        var box = new VBox(10, emptyHeadline, emptySubline, emptyAction);
        box.setAlignment(Pos.CENTER);
        // The panel is only a backdrop for its own contents; clicks anywhere else must
        // reach the canvas beneath so zoom and pan keep working the moment data arrives.
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setPadding(new Insets(24));

        var stack = new StackPane(box);
        stack.setAlignment(Pos.CENTER);
        stack.setPickOnBounds(false);
        stack.setMouseTransparent(false);
        return stack;
    }

    /**
     * Write the rail summary and the empty state's wording for the current
     * {@link ViewState}.
     * <p>
     * Words only. Whether the overlay is up, whether its Run button is offered and whether
     * it is clickable are the state machine's, derived from the session — which is what
     * stopped this method and the toolbar disagreeing about whether a run was possible:
     * the empty state used to answer that by asking the toolbar button whether it happened
     * to be disabled.
     */
    private void refreshOverview(ViewState state) {
        CellIndex index = session.index();
        PhenotypeSnapshot snapshot = session.snapshot();
        // The session's rule, not a second copy: while it is waiting for the gating tree to
        // re-index, the CellIndex it still holds belongs to the previous image.
        boolean hasCells = session.hasCells();
        int markers = session.includedMarkerCount();

        // The same composition the status bar prints on one line. It used to be spelled out
        // here with different wording and different arithmetic from UmapSession.describe,
        // and the two appeared within an inch of each other quoting different numbers.
        cellsSummary.setText(String.join("\n", session.overviewLines()));

        if (!state.showEmptyState()) return;

        // A run that crashed used to leave this overlay reading "Ready to embed" — the same
        // sentence it shows when nothing has been tried — because the only trace of the
        // failure was a modal alert and a status line that wiped itself after five seconds.
        if (state.stage() == ViewState.Stage.FAILED) {
            emptyHeadline.setText("The last UMAP run failed");
            emptySubline.setText(state.failure()
                    + (state.canCompute()
                            ? "\n\nAdjust the settings under Embedding and try again."
                            : ""));
            emptyAction.setText("Try again");
            return;
        }
        emptyAction.setText("Run UMAP");

        if (!hasCells) {
            emptyHeadline.setText("No cells to embed");
            // Not standalone covers both holding a snapshot and waiting for the next one.
            emptySubline.setText(state.standalone()
                    ? "Open an image with cell detections, then come back."
                    : "Waiting for the gating window to index this image.");
            return;
        }

        if (state.indexRebuilding()) {
            emptyHeadline.setText("Rebuilding the cell index…");
            emptySubline.setText("Applying the feature change. Run UMAP unlocks when the "
                    + "new columns are ready.");
            return;
        }

        if (state.stage() == ViewState.Stage.COMPUTING) {
            emptyHeadline.setText("Embedding…");
            emptySubline.setText(String.format("%,d cells across %d marker%s.",
                    index.size(), markers, markers == 1 ? "" : "s"));
            return;
        }

        // Say the shortfall here rather than let the run say it. EmbeddingFeatures refuses
        // fewer than two ticked markers, and an empty state that reads "Ready to embed"
        // over a Run button whose only possible outcome is a failure dialog is the same
        // silent-plausible-wrong shape the include flag itself had. The button that would
        // have invited that click is disabled by the same derivation, in both places it
        // appears.
        if (markers < EmbeddingFeatures.MINIMUM_FEATURES) {
            emptyHeadline.setText("Not enough markers to embed");
            emptySubline.setText(String.format(
                    "%,d cells, but %d of %d markers %s ticked. UMAP needs at least %d — "
                            + "tick more under Cells.",
                    index.size(), markers, session.markers().size(),
                    markers == 1 ? "is" : "are", EmbeddingFeatures.MINIMUM_FEATURES));
            return;
        }

        emptyHeadline.setText("Ready to embed");
        String base = String.format("%,d cells across %d marker%s.",
                index.size(), markers, markers == 1 ? "" : "s");
        emptySubline.setText(snapshot != null && !snapshot.gatedMarkers().isEmpty()
                ? base + " Features are pre-selected from your gates — adjust them under "
                        + "Cells, or run as-is."
                : base + " Choose which markers to use under Cells, or run as-is.");
    }

    // --- Snapshot handoff from the gating pane ---

    /**
     * Adopt a phenotyping produced by the gating tree.
     * <p>
     * This is the fused extension's main entry point: instead of rebuilding an index
     * from the hierarchy and recovering phenotypes from {@code PathClass}, the pane
     * takes the gating pane's own {@link CellIndex} and per-cell labels wholesale.
     *
     * <h4>Why the embedding usually survives</h4>
     * Editing a gate does not rebuild the cell index — the same {@link CellIndex}
     * instance is re-walked with new thresholds. So when the incoming snapshot carries
     * the identical index, the UMAP coordinates are still valid for every cell and only
     * the <em>colours</em> have changed. Recomputing a multi-minute embedding because
     * the user nudged a threshold would make the two halves unusable together; keeping
     * it means the UMAP recolours live as they gate, which is the whole point of the
     * fusion. A different index (new image, changed annotation filter, changed feature
     * resolution) does invalidate the embedding, and the pane clears it.
     *
     * @param incoming the new phenotyping; {@code null} detaches snapshot mode
     */
    public void applySnapshot(PhenotypeSnapshot incoming) {
        UmapSession.Adoption adoption = session.adopt(incoming);
        switch (adoption) {
            case DETACHED -> { /* standalone from here; nothing on screen changes yet */ }
            case RECOLOUR -> {
                // Gate edit only: keep the embedding, restyle it. The rail summary was
                // already repainted by adopt()'s own publish — a gate edit changes the
                // phenotype counts without changing the ViewState, which is exactly why
                // this pane subscribes to the session rather than to the state property.
                updatePhenotypeColors();
                updateLegend();
                setStatus(session.overviewLine() + " — recoloured from the gating tree.",
                        StatusLevel.INFO);
            }
            case REBUILD -> onSnapshotCellSetChanged();
        }
    }

    /**
     * Tear down every widget-side artefact of the previous cell set and re-dress the rail
     * for a new one. The session has already retired the gate, the tags and the cached
     * colours by the time this runs; what is left here is canvases and controls.
     */
    private void onSnapshotCellSetChanged() {
        computeService.cancel();
        session.cancelRun();
        umapCanvas.setData(null, null);
        umapCanvas.setHighlightIndices(null);
        umapCanvas.setHighlightMask(null);
        markerOverlay.setData(null, null);
        colorScaleLegend.clear();
        polygonSelector.clear();
        polygonSelector.deactivate();

        markerDropdown.getItems().setAll(UmapSession.NO_MARKER);
        markerDropdown.getItems().addAll(session.markers());
        markerDropdown.setValue(UmapSession.NO_MARKER);

        featureSelectionPane.populate(session.markers(), session.capability(), session.selection(),
                session::editSelection);
        updateLegend();

        ImageData<?> imageData = qupath.getImageData();
        if (imageData != null) {
            attachSelectionListener(imageData);
        }

        setStatus(session.overviewLine() + " — ready to embed.", StatusLevel.INFO);
    }

    // --- Initialization ---

    /**
     * Handle a change of the active QuPath image.
     * <p>
     * There is deliberately no "are you sure?" prompt here. The old one asked whether
     * to discard the current UMAP and its population tags — but by the time the
     * listener fires, QuPath has <em>already</em> switched images, so declining left
     * the panel holding {@code PathObject}s belonging to an image that was no longer
     * displayed: subsequent tagging wrote into the wrong hierarchy, and the previous
     * image's entire detection set stayed pinned in memory. The question also
     * overstated the stakes, since applying a population tag writes a PathClass onto
     * the cells themselves — those classifications live in the project and survive the
     * switch. Only the in-memory embedding is transient, and it is always recomputable.
     */
    private void initializeFromImage() {
        detachSelectionListener();

        // In snapshot mode the gating pane owns cell discovery — it applies the quality
        // filter and the annotation filter before handing anything over. Rebuilding from
        // the raw hierarchy here would silently replace that curated cell set with every
        // detection on the new image, so instead the view empties and waits: the gating
        // pane re-indexes on the same image-change event and pushes a fresh snapshot.
        if (session.isSnapshotMode()) {
            session.detachSnapshot();
            clearDerivedState();
            setStatus("Image changed — waiting for the gating tree to re-index.", StatusLevel.INFO);
            return;
        }

        int carriedTags = session.tags().size();
        reloadCells(carriedTags > 0
                ? String.format("Image changed — %d population tag(s) remain on the previous image's cells",
                        carriedTags)
                : null);
    }

    /**
     * Drop everything derived from the current cell set: the embedding, the gate, the
     * cached colours, the tags and every canvas overlay. Shared by the snapshot,
     * reload and image-change paths so they cannot fall out of step with each other.
     */
    private void clearDerivedState() {
        computeService.cancel();
        // The service's Cancelled outcome would clear the phase a moment later anyway; this
        // makes every sync() between here and then exact rather than merely self-healing.
        session.cancelRun();
        session.clearDerivedState();
        umapCanvas.setData(null, null);
        umapCanvas.setHighlightIndices(null);
        umapCanvas.setVisibleMask(null);
        markerOverlay.setData(null, null);
        legend.clear();
        colorScaleLegend.clear();
        polygonSelector.clear();
        polygonSelector.deactivate();
    }

    /**
     * Tear down derived state and rebuild the cell index for the current image.
     * <p>
     * Shared by the image-change listener and the annotation-filter toggle: both
     * invalidate the indexed cell set, and therefore the embedding and any gate built
     * on it. Safe to call repeatedly — the session's build generation ensures only the
     * most recent invocation's result is applied.
     *
     * @param notice optional status message explaining why the reload happened
     */
    private void reloadCells(String notice) {
        // Invalidate any in-flight index build and gate computation.
        int generation = session.beginIndexBuild();

        // Tear down any running computation cleanly
        computeService.cancel();
        session.cancelRun();
        session.clearIndex();
        umapCanvas.setData(null, null);
        umapCanvas.setHighlightIndices(null);
        markerOverlay.setData(null, null);
        legend.clear();
        colorScaleLegend.clear();
        polygonSelector.clear();
        polygonSelector.deactivate();

        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) {
            setStatus("No image loaded", StatusLevel.INFO);
            return;
        }
        attachSelectionListener(imageData);

        var detections = collectDetections(imageData);
        // Read on the FX thread: ImageData properties are not safe to touch off it.
        String stored = storedSelection(imageData);

        if (detections.isEmpty()) {
            setStatus(roiFilterCheckBox.isSelected()
                    ? "No cell detections found inside annotations"
                    : "No cell detections found", StatusLevel.WARN);
            return;
        }

        setStatus("Building cell index...", StatusLevel.INFO);

        // One read of the hierarchy, off the FX thread. Discovery, the compartment
        // capability and the index all come from a single measurement-key sample taken
        // inside DetectionIngest, so the feature picker can no longer offer a compartment
        // the index resolved to nothing — which is exactly the 20-vs-100 sample-depth drift
        // this half used to be on the wrong side of. The persisted selection is resolved by
        // callback because it cannot be loaded until the panel and capability are known,
        // yet the index cannot be built until the selection is.
        var options = new IngestOptions(
                DetectionIngest.channelNames(imageData),
                DetectionIngest.calibration(imageData),
                (discovered, cap) -> UmapSession.loadSelection(stored, discovered, cap));
        Thread bgThread = new Thread(() -> {
            IngestResult ingest = DetectionIngest.read(detections, options);
            MarkerStats builtStats = MarkerStats.compute(ingest.index());
            Platform.runLater(() -> {
                // A newer reload superseded this one (e.g. the user toggled the
                // annotation filter twice). Without this guard the winner is whichever
                // thread happened to finish last, not the request the user made last.
                if (!session.isCurrentBuild(generation)) return;

                var markersCopy = ingest.markerNames();
                if (markersCopy.isEmpty()) {
                    setStatus("No markers found in measurements", StatusLevel.WARN);
                    return;
                }
                var builtCapability = ingest.capability();
                var loadedSelection = ingest.selection();

                session.installIndex(ingest.index(), builtStats, markersCopy,
                        builtCapability, loadedSelection);

                markerDropdown.getItems().clear();
                markerDropdown.getItems().add(UmapSession.NO_MARKER);
                markerDropdown.getItems().addAll(markersCopy);
                markerDropdown.setValue(UmapSession.NO_MARKER);

                featureSelectionPane.populate(markersCopy, builtCapability, loadedSelection,
                        session::editSelection);

                // The reload notice rides along with the ready message rather than
                // being posted up-front, where "Building cell index..." would wipe it
                // before the user could read it. An ingest finding overrides the level:
                // an unresolved axis produces an empty plot, and "Ready to compute" is
                // the wrong thing to say about that.
                String warning = ingest.report().summary();
                setStatus(String.format("%s%,d cells, %d markers%s. %s",
                        notice != null ? notice + ". " : "",
                        ingest.index().size(), markersCopy.size(),
                        builtCapability.isRich() ? " (per-compartment)" : "",
                        warning.isEmpty() ? "Ready to compute UMAP." : "\u26a0 " + warning),
                        warning.isEmpty() ? StatusLevel.INFO : StatusLevel.WARN);
            });
        }, "flowpath-umap-init");
        bgThread.setDaemon(true);
        bgThread.start();
    }

    /**
     * The set of detections this panel operates on, read out of the hierarchy and handed
     * to {@link UmapSession#selectDetections} as plain data.
     * <p>
     * The rule — drop the Excluded class, narrow to the annotation ROIs when there are any
     * — lives on the session, where it can be tested without a {@code QuPathGUI}. All this
     * decides is where the two lists come from, which is the one part that genuinely needs
     * an {@link ImageData}.
     */
    private List<PathObject> collectDetections(ImageData<?> imageData) {
        if (imageData == null) return List.of();
        List<ROI> rois = roiFilterCheckBox.isSelected()
                ? imageData.getHierarchy().getAnnotationObjects().stream()
                        .map(PathObject::getROI)
                        .filter(Objects::nonNull)
                        .toList()
                : List.of();
        return UmapSession.selectDetections(imageData.getHierarchy().getDetectionObjects(), rois);
    }

    // --- Feature selection (per-marker compartment / statistic / include) ---

    /**
     * The persisted per-marker selection payload for an image, or {@code null}.
     * <p>
     * The pane owns the {@link ImageData} property; {@link UmapSession#loadSelection}
     * owns what a payload means. Splitting them is what lets the fallback rules — legacy
     * data forced to whole-cell mean, stored compartments the image no longer carries
     * discarded — be tested without an image.
     */
    private static String storedSelection(ImageData<?> imageData) {
        Object stored = imageData == null ? null : imageData.getProperty(UmapSession.SELECTION_PROPERTY);
        return stored instanceof String str ? str : null;
    }

    /**
     * Called when the user edits the feature picker. Persists the new selection and
     * rebuilds the cell index so the next UMAP run (and the marker overlay) use the
     * chosen keys. The existing embedding, if any, is left untouched until the user
     * recomputes.
     */
    private void onFeatureSelectionChanged() {
        ImageData<?> imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.setProperty(UmapSession.SELECTION_PROPERTY, session.selection().serialize());
        }
        if (session.markers().isEmpty()) return;

        // Rebuild the index with the new resolution off the FX thread. Whose cells those
        // are is the session's answer, not a second reading of it here: in snapshot mode
        // the set is the gating pane's, already narrowed by its quality and annotation
        // filters, and re-querying the hierarchy would widen it back to the whole slide
        // and break the positional alignment every snapshot array depends on. A null means
        // this panel owns its own cell set and must collect it.
        List<PathObject> forRebuild = session.detectionsForRebuild();
        List<PathObject> detections = forRebuild != null ? forRebuild : collectDetections(imageData);
        if (detections.isEmpty()) return;

        // The rebuild can take a few seconds on a large slide; without feedback it
        // looks like a freeze, so announce start and completion in the status bar.
        setStatus("Updating features — rebuilding cell index...", StatusLevel.INFO);
        var markersCopy = List.copyOf(session.markers());
        var selectionCopy = session.selection();
        // Shares the generation counter with reloadCells: editing several feature rows
        // in quick succession (or editing one while a reload is in flight) must land on
        // the newest request's result, not whichever build finishes last.
        int generation = session.beginIndexBuild();
        // Locking the inputs during a run stops edit-then-run. It does not stop
        // run-before-the-edit-lands: nothing in runUmap bumps the build generation, so
        // ticking a marker and immediately clicking Run would install this rebuilt index
        // under a live compute — the very thing the lock exists to prevent, reached by the
        // other direction. Withholding Run until the rebuild lands makes the exclusion
        // symmetric.
        session.beginRebuild();
        var rebuildCalibration = DetectionIngest.calibration(imageData);
        Thread bg = new Thread(() -> {
            CellIndex built = null;
            MarkerStats builtStats = null;
            try {
                // Same calibration as the initial read, so the rebuilt index's
                // CellGeometry reaches the same ScaleVerdict rather than silently
                // demoting to NO_CALIBRATION when a feature row is edited.
                built = CellIndex.build(detections, markersCopy, selectionCopy, rebuildCalibration);
                builtStats = MarkerStats.compute(built);
            } finally {
                final CellIndex rebuilt = built;
                final MarkerStats rebuiltStats = builtStats;
                Platform.runLater(() -> {
                    // Balances beginRebuild() on EVERY exit — landed, superseded or thrown.
                    // A build that died leaving the counter up would disable Run for the
                    // rest of the session.
                    session.endRebuild();
                    if (rebuilt == null || !session.isCurrentBuild(generation)) return;
                    // Reconciles the snapshot onto the rebuilt index rather than leaving the
                    // pane holding one index and its snapshot naming another — see
                    // UmapSession's index/snapshot invariant.
                    // The tick count may have crossed MINIMUM_FEATURES in either
                    // direction; installing publishes, so Run UMAP's enablement is
                    // re-derived rather than assumed unchanged.
                    session.installRebuiltIndex(rebuilt, rebuiltStats);
                    // Refresh the overlay if one is showing for the selected marker.
                    onMarkerSelected();
                    setStatus(String.format(java.util.Locale.US,
                            "Features updated — %,d cells, %d markers. Recompute UMAP to apply.",
                            rebuilt.size(), markersCopy.size()), StatusLevel.SUCCESS);
                });
            }
        }, "flowpath-umap-features");
        bg.setDaemon(true);
        bg.start();
    }

    // --- Compute integration ---
    //
    // The actual UMAP run, cancel and outcome wiring live in ComputeController. UmapPane
    // provides only the result consumer that pushes a finished embedding into the canvases
    // and the legend. It supplies no state: the panel's state is derived from the session,
    // which now owns the embedding too, so the resting-state resolver this pane used to
    // hand over — untested, and duplicated inside clearPolygon() with the gate-mask branch
    // missing — has no reason to exist.

    /**
     * Consume a finished {@link UmapResult} from {@link ComputeController}. The result is
     * already installed on the session and the panel already re-derived from it before
     * this runs — so when we read raw accessors and push them into the canvases, the UI
     * state matches the data.
     */
    private void onUmapResultReady(UmapResult result) {
        // A fresh embedding invalidates any gate drawn on the previous one: the
        // polygon's coordinates refer to a layout that no longer exists.
        session.retireGate();
        polygonSelector.clear();
        polygonSelector.deactivate();

        // Population tag masks are indexed positionally against the result's object
        // array. A recompute after a feature change can produce a different cell
        // count, at which point the masks refer to nothing meaningful — drop them
        // rather than render rings against mismatched indices.
        if (session.tagsAreStaleFor(result.size())) {
            session.clearTags();
            setStatus("Cell set changed — population tag overlays cleared "
                    + "(classifications on the cells are unaffected)", StatusLevel.WARN);
        }
        // Use raw accessors here: the canvases store the references for read-only
        // iteration during repaint, and at realistic dataset sizes (millions of cells)
        // cloning these arrays per call dominates the render path. See UmapResult.
        umapCanvas.setData(result.getUmapXRaw(), result.getUmapYRaw());
        markerOverlay.setData(result.getUmapXRaw(), result.getUmapYRaw());

        // Must follow setData: the canvas resolves overlay masks to index lists
        // against the current point count, and setData discards any resolved against
        // the previous embedding. Rebuilding the rings first would lose them.
        updatePopulationRings();
        updatePhenotypeColors();
        updateLegend();

        // What the first finished embedding should be coloured by is the session's
        // decision — a product rule that used to sit here behind thirteen lines of
        // justifying comment and no test, because reaching it needed a live toolkit.
        switch (session.firstColourMode(markerDropdown.getValue())) {
            case PHENOTYPE -> colorByPhenotype.setSelected(true);
            case MARKER -> {
                colorByMarker.setSelected(true);
                markerDropdown.setValue(session.preferredMarker());
                onMarkerSelected();   // setValue does not reliably fire the action handler
            }
            case UNCHANGED -> { /* the user has already chosen; leave the plot alone */ }
        }
    }

    // --- Phenotype Coloring ---

    /**
     * Re-apply gate shading to the cached phenotype colours.
     * <p>
     * Used when only the gate changed. {@link #updatePhenotypeColors()} re-derives every
     * point's colour by calling {@code getPathClass().getColor()} once per cell, which
     * on a multi-million-cell embedding is millions of virtual calls on the FX thread —
     * pure waste when the classifications have not moved and only the mask has.
     */
    private void refreshGateShading() {
        if (session.baseColors() == null) {
            updatePhenotypeColors();
            return;
        }
        umapCanvas.setPointColors(session.applyGateShading(session.baseColors()));
    }

    /**
     * Recompute per-point colours and push them to the canvas, greying anything outside
     * the current gate.
     * <p>
     * In snapshot mode the colours come straight from the gate tree, so the UMAP and the
     * gating tree cannot disagree — the same branch colour paints the tree row, the
     * tissue overlay and the embedding. Standalone, the pane falls back to reading each
     * cell's {@link PathClass}, which is how it behaved as a separate extension and is
     * still correct for cells classified by something other than FlowPath.
     */
    private void updatePhenotypeColors() {
        if (session.embedding() == null) return;
        // Raw accessor: read-only iteration over PathObject references.
        int[] colors = session.derivePointColors(session.embedding().getObjectsRaw());
        umapCanvas.setPointColors(session.applyGateShading(colors));
        applyPhenotypeVisibility();
    }

    /**
     * Hide or show one phenotype in the plot, then refresh the legend so the row
     * reflects its new state.
     */
    private void togglePhenotypeVisibility(String name) {
        if (name == null) return;
        session.togglePhenotype(name);
        applyPhenotypeVisibility();
        updateLegend();
    }

    /**
     * Push the current hidden set to the canvas as a per-point visibility mask.
     * <p>
     * Costs one pass over the label array per toggle, which is cheap next to the repaint
     * it triggers. Allocates nothing when nothing is hidden — the overwhelmingly common
     * case — because the canvas treats a null mask as "everything visible".
     */
    private void applyPhenotypeVisibility() {
        if (session.embedding() == null) return;
        umapCanvas.setVisibleMask(session.visibilityMask(session.embedding().size()));
    }

    /**
     * Transiently highlight one phenotype as the pointer rests on its legend row, or
     * clear the highlight when {@code name} is null.
     * <p>
     * Suppressed while a polygon gate is open: the highlight channel is the same one the
     * gate uses to show its selection, and stealing it on a stray mouse-over would make
     * the gate look like it had moved.
     */
    private void highlightPhenotype(String name) {
        if (session.embedding() == null || !session.isSnapshotMode() || session.hasGate()) return;
        if (!session.usesGatingColors(session.embedding().size())) return;
        if (name == null) {
            umapCanvas.setHighlightIndices(null);
            return;
        }
        umapCanvas.setHighlightMask(session.highlightMask(name, session.embedding().size()));
    }

    /**
     * Refresh the legend from whichever source is authoritative.
     * <p>
     * With a snapshot the legend lists the gate tree's populations with their real cell
     * counts and colours, ordered largest-first; without one it falls back to scanning
     * {@code PathClass} across the embedded objects.
     */
    private void updateLegend() {
        PhenotypeSnapshot snapshot = session.snapshot();
        if (snapshot != null) {
            legend.update(snapshot.populations(), session.tags(),
                    snapshot.includedCount(), session.hiddenPhenotypes());
        } else if (session.embedding() != null) {
            legend.update(session.embedding().getObjectsRaw(), session.tags());
        } else {
            legend.clear();
        }
    }

    // --- Polygon Gating ---

    /**
     * Handle a closed (or re-dragged) polygon gate.
     * <p>
     * The point-in-polygon scan runs off the FX thread. It is O(cells x vertices) and
     * fires on every handle drag release, so on a large slide doing it inline froze the
     * window for seconds at exactly the moment the user was adjusting the shape.
     * <p>
     * Nothing here writes to the QuPath hierarchy. The gate shows as greyed-out points
     * in the canvas and — when Link viewer is on — as a viewer selection, which
     * highlights the cells on the tissue without altering their classifications.
     */
    private void onPolygonComplete(List<double[]> vertices) {
        if (session.embedding() == null) return;

        UmapResult result = session.embedding();
        List<double[]> outline = new ArrayList<>(vertices);
        int generation = session.beginGateComputation();

        Thread worker = new Thread(() -> {
            boolean[] mask = PolygonSelector.computeInsideMask(
                    result.getUmapXRaw(), result.getUmapYRaw(), outline);
            int inside = 0;
            for (boolean b : mask) if (b) inside++;
            final int insideCount = inside;

            Platform.runLater(() -> {
                // Superseded by a newer drag, or the embedding changed underneath us.
                if (!session.isCurrentGate(generation) || session.embedding() != result) return;

                // A closed polygon is what unlocks the tag controls; setting the mask
                // publishes, so they unlock without anyone asking them to.
                session.setGateMask(mask);
                // Only the mask changed — reshade the cached colours rather than
                // re-reading every cell's PathClass.
                refreshGateShading();
                pushGateSelectionToViewer();

                setStatus(String.format(java.util.Locale.US, "%,d inside gate / %,d outside",
                        insideCount, mask.length - insideCount), StatusLevel.INFO);
            });
        }, "flowpath-umap-gate");
        worker.setDaemon(true);
        worker.start();
    }

    private void clearPolygon() {
        // One call, because retiring a gate is one thing: drop the mask AND invalidate the
        // in-flight computation that would otherwise re-apply it a moment later.
        session.retireGate();

        polygonSelector.clear();
        polygonSelector.deactivate();
        umapCanvas.setPolygonCompleted(false);
        refreshGateShading();
        clearViewerSelection();

        if (session.embedding() != null) {
            setStatus(String.format(java.util.Locale.US, "UMAP: %,d cells",
                    session.embedding().size()), StatusLevel.INFO);
        }
    }

    // --- QuPath viewer integration ---
    //
    // The link is selection-based on purpose. Selection is transient UI state that
    // QuPath does not persist, so linking the two views costs the user nothing and
    // can never corrupt a project — unlike the classification writes this panel used
    // to perform to achieve the same highlight.

    /**
     * Upper bound on how many objects we hand to QuPath's selection model. A gate can
     * cover millions of cells; selecting all of them makes the viewer sluggish for a
     * highlight nobody can visually resolve anyway.
     */
    private static final int MAX_VIEWER_SELECTION = 200_000;

    /** Select the gated cells in QuPath so they highlight on the tissue. */
    private void pushGateSelectionToViewer() {
        if (!viewerSyncCheckBox.isSelected() || session.embedding() == null
                || !session.hasGate()) return;
        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) return;

        // The mask itself never leaves the session — see UmapSession#gatedObjects.
        List<PathObject> selected =
                session.gatedObjects(session.embedding().getObjectsRaw(), MAX_VIEWER_SELECTION);
        boolean truncated = selected.size() == MAX_VIEWER_SELECTION;

        syncingSelection = true;
        try {
            imageData.getHierarchy().getSelectionModel().setSelectedObjects(selected, null);
        } finally {
            syncingSelection = false;
        }

        if (truncated) {
            setStatus(String.format(java.util.Locale.US,
                    "Gate selected in viewer (capped at %,d of the gated cells)",
                    MAX_VIEWER_SELECTION), StatusLevel.WARN);
        }
    }

    private void clearViewerSelection() {
        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) return;
        syncingSelection = true;
        try {
            imageData.getHierarchy().getSelectionModel().clearSelection();
        } finally {
            syncingSelection = false;
        }
    }

    /**
     * Handle a click on a UMAP point: select that cell in QuPath and centre the
     * viewer on it. This is the "which cell is that dot?" question the panel could not
     * previously answer — {@code findNearestPoint} existed but was never wired to
     * anything.
     */
    private void onPointPicked(int index) {
        // While a gate is being drawn, clicks are vertices, not picks.
        if (polygonSelector.isActive()) return;
        if (!viewerSyncCheckBox.isSelected() || session.embedding() == null) return;

        PathObject[] objects = session.embedding().getObjectsRaw();
        if (index < 0 || index >= objects.length) return;
        PathObject cell = objects[index];

        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) return;

        syncingSelection = true;
        try {
            imageData.getHierarchy().getSelectionModel().setSelectedObject(cell);
        } finally {
            syncingSelection = false;
        }

        // Centre the viewer on the cell. ROI centroids are in pixel coordinates,
        // which is exactly what setCenterPixelLocation expects — note we deliberately
        // read the ROI rather than CellIndex's centroid columns, since those may carry
        // an imported measurement in other units.
        ROI roi = cell.getROI();
        var viewer = qupath.getViewer();
        if (roi != null && viewer != null) {
            viewer.setCenterPixelLocation(roi.getCentroidX(), roi.getCentroidY());
        }

        umapCanvas.setHighlightIndices(new int[]{index});

        PathClass pc = cell.getPathClass();
        setStatus(String.format("Cell %,d%s — centred in viewer", index,
                pc != null ? " (" + pc.getName() + ")" : ""), StatusLevel.INFO);
    }

    /**
     * Reflect a QuPath viewer selection back into the UMAP as highlighted points.
     * Ignores selections we caused ourselves via {@link #syncingSelection}.
     */
    private void onViewerSelectionChanged(Collection<PathObject> allSelected) {
        if (syncingSelection || !viewerSyncCheckBox.isSelected() || session.embedding() == null) return;

        if (allSelected == null || allSelected.isEmpty()) {
            umapCanvas.setHighlightIndices(null);
            return;
        }

        Set<PathObject> selected = new HashSet<>(allSelected);
        PathObject[] objects = session.embedding().getObjectsRaw();
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < objects.length && hits.size() < MAX_VIEWER_SELECTION; i++) {
            if (selected.contains(objects[i])) hits.add(i);
        }

        int[] indices = new int[hits.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = hits.get(i);
        umapCanvas.setHighlightIndices(indices);
    }

    /** Attach the selection listener to an image's hierarchy, detaching any previous one. */
    private void attachSelectionListener(ImageData<?> imageData) {
        detachSelectionListener();
        if (imageData == null) return;
        selectionListener = (pathObjectSelected, previousObject, allSelected) -> {
            // QuPath fires selection events from whichever thread made the change.
            if (Platform.isFxApplicationThread()) {
                onViewerSelectionChanged(allSelected);
            } else {
                Platform.runLater(() -> onViewerSelectionChanged(allSelected));
            }
        };
        imageData.getHierarchy().getSelectionModel()
                .addPathObjectSelectionListener(selectionListener);
        listenedHierarchyOwner = imageData;
    }

    private void detachSelectionListener() {
        if (selectionListener != null && listenedHierarchyOwner != null) {
            listenedHierarchyOwner.getHierarchy().getSelectionModel()
                    .removePathObjectSelectionListener(selectionListener);
        }
        selectionListener = null;
        listenedHierarchyOwner = null;
    }

    /** The ImageData whose hierarchy currently holds our selection listener. */
    private ImageData<?> listenedHierarchyOwner;

    // --- Population Tagging ---

    private void applyPopulationTag() {
        if (session.embedding() == null || !session.hasGate()) {
            setStatus("Draw a polygon first to select cells", StatusLevel.WARN);
            return;
        }

        String name = tagNameField.getText().trim();
        if (name.isEmpty()) {
            setStatus("Enter a population name before applying tag", StatusLevel.ERROR);
            // Highlight the field so the required action is obvious even if the
            // status message is missed; the text listener clears it on first keypress.
            tagNameField.setStyle("-fx-border-color: #ff5555; -fx-border-width: 2;");
            tagNameField.requestFocus();
            return;
        }

        Color color = tagColorPicker.getValue();
        int packedColor = ((int) (color.getRed() * 255) << 16)
                | ((int) (color.getGreen() * 255) << 8)
                | (int) (color.getBlue() * 255);

        // The write loop, the tag and the gate retirement are all one operation on the
        // session: it reuses the mask computed when the polygon closed (rather than
        // re-running the O(cells x vertices) scan on the FX thread), names each cell
        // through its own tagClassName rule, and retires the gate that selected them.
        PopulationTag tag = session.applyTag(name, packedColor,
                session.embedding().getObjectsRaw());
        if (tag == null) return;

        updatePopulationRings();
        clearViewerSelection();
        polygonSelector.clear();
        polygonSelector.deactivate();
        updatePhenotypeColors();
        updateLegend();

        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
        }

        setStatus(String.format(java.util.Locale.US, "Tagged %,d cells as '%s'",
                tag.count(), name), StatusLevel.SUCCESS);
    }

    private void removePopulationTag(String tagName) {
        if (session.embedding() == null) return;

        // Restoring the classes the tag overwrote — colour carry included — is the
        // session's, beside the rule that derived those names in the first place.
        if (session.removeTag(tagName, session.embedding().getObjectsRaw()) == null) return;

        updatePopulationRings();
        updatePhenotypeColors();
        updateLegend();

        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
        }

        setStatus(String.format("Removed tag '%s'", tagName), StatusLevel.SUCCESS);
    }

    private void updatePopulationRings() {
        if (session.tags().isEmpty()) {
            umapCanvas.setPopulationRings(null, null);
            return;
        }

        umapCanvas.setPopulationRings(session.ringColors(), session.ringMasks());
    }

    // --- Marker Overlay ---

    private void onMarkerSelected() {
        if (session.embedding() == null) return;

        String selected = markerDropdown.getValue();
        if (selected == null || UmapSession.NO_MARKER.equals(selected)) {
            hideMarkerOverlay();
            return;
        }

        boolean useZScore = "Z-score".equals(colorScaleDropdown.getValue());
        double[] displayValues = session.colourValues(selected, useZScore);
        if (displayValues == null) return;
        MarkerOverlayCanvas.ColorScale scale = useZScore
                ? MarkerOverlayCanvas.ColorScale.BLUE_WHITE_RED
                : MarkerOverlayCanvas.ColorScale.VIRIDIS;

        markerOverlay.setMarkerValues(displayValues, selected, scale);
        String scaleLabel = useZScore ? "Z-score" : "Raw";
        colorScaleLegend.setScale(markerOverlay.getColorMin(), markerOverlay.getColorMax(),
                scale, selected, scaleLabel);

        showMarkerOverlay();
    }

    private void showMarkerOverlay() {
        if (!markerOverlayVisible) {
            // Insert marker overlay before legend
            var items = centerSplit.getItems();
            if (items.size() == 2) {
                items.add(1, markerOverlay);
                centerSplit.setDividerPositions(0.45, 0.88);
            }
            markerOverlayVisible = true;
        }
    }

    private void hideMarkerOverlay() {
        if (markerOverlayVisible) {
            centerSplit.getItems().remove(markerOverlay);
            centerSplit.setDividerPositions(0.85);
            markerOverlayVisible = false;
            colorScaleLegend.clear();
        }
    }

    // --- Export ---

    private void exportCsv() {
        // Ctrl+E reaches here without touching the Export button, so the button being
        // disabled protects nothing. Asking the same derivation the button is bound to is
        // what makes the keyboard path obey the exporting flag too — a double Ctrl+E used
        // to start the same write twice, which is precisely what beginExport() exists to
        // prevent.
        if (!session.viewState().canExport()) {
            setStatus(session.embedding() == null
                            ? "No UMAP data to export"
                            : "Export unavailable right now — a run or an earlier export is "
                                    + "still in progress",
                    StatusLevel.WARN);
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export UMAP Coordinates");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        chooser.setInitialFileName("umap_coordinates.csv");
        File file = chooser.showSaveDialog(getScene().getWindow());

        if (file == null) return;

        // Writing runs off the FX thread. A full export is one row per cell with two
        // formatted columns per marker — tens of millions of number formats on a large
        // slide — so doing it inline locked the window with no feedback and no way out.
        // Snapshot the inputs so a concurrent reload can't swap them mid-write.
        UmapResult result = session.embedding();
        CellIndex index = session.index();
        MarkerStats stats = session.stats();
        List<PopulationTag> tags = List.copyOf(session.tags());

        // "An export is writing" is a fact about the session, not a property of a button,
        // which is why re-enabling it afterwards no longer needs a third copy of the
        // resting-state rule.
        session.beginExport();
        setStatus("Exporting %,d cells to %s...".formatted(result.size(), file.getName()),
                StatusLevel.INFO);

        Thread writer = new Thread(() -> {
            String error = null;
            try {
                result.exportToCsv(file, index, stats, tags);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String failure = error;
            Platform.runLater(() -> {
                // Re-enable only if the panel is still in a state that allows export; a
                // reload during the write leaves no embedding, and the derivation has
                // already made that call.
                session.endExport();
                if (failure == null) {
                    setStatus("Exported to " + file.getName(), StatusLevel.SUCCESS);
                } else {
                    setStatus("Export failed: " + failure, StatusLevel.ERROR);
                }
            });
        }, "flowpath-umap-export");
        writer.setDaemon(true);
        writer.start();
    }

    // --- Status helper ---
    //
    // setStatus replaces direct statusLabel.setText calls so that the status
    // text is colored by severity. INFO is the default white; WARN/ERROR/
    // SUCCESS get colored text and AUTO-CLEAR after 5 seconds (the message
    // wipes back to "" with INFO styling). The auto-clear keeps transient
    // signals from sticking around indefinitely while persistent state-of-
    // the-world messages (computation status, ready, etc.) stay visible.
    // INFO messages are not auto-cleared because they describe the current
    // app state and the user expects them to remain.

    /**
     * Severity level for status-bar messages. Package-private (not {@code private})
     * so {@link ComputeController} can call {@link #setStatus} via the
     * {@link StatusReporter} bridge.
     */
    enum StatusLevel { INFO, WARN, ERROR, SUCCESS }

    /**
     * Bridge that lets sibling controllers (e.g. {@link ComputeController}) push status
     * into the bar without exposing the raw {@code statusLabel}.
     * <p>
     * Two channels, because the bar has two lifetimes. {@link #report} is the one line,
     * coloured by severity and wiped after five seconds for anything that is not INFO.
     * {@link #detail} is what stands behind it and stays: a UMAP run's full report is
     * several lines and includes the subsample size, which the user needs after the
     * status line has cleared and which a five-second message cannot carry.
     * {@code FlowPathPane} does the same thing with the ingest report.
     */
    interface StatusReporter {
        void report(String text, StatusLevel level);

        /** The persistent long form behind the status line; null or blank removes it. */
        void detail(String text);

        // There is deliberately no alert(String) channel any more. It existed because a
        // failure had nowhere durable to go: the status line wiped after five seconds and
        // the empty-state overlay only speaks when the plot is empty. ViewState.failure()
        // now reaches the overlay OR the rail's failure banner, whichever is showing, and
        // a modal on top of that only stood between the user and the panel that was
        // already telling them.
    }

    /**
     * The tooltip behind the status bar. Deliberately persistent where
     * {@link #setStatus} is transient — see {@link StatusReporter}.
     */
    private void setStatusDetail(String detail) {
        statusLabel.setTooltip(detail == null || detail.isBlank()
                ? null : new javafx.scene.control.Tooltip(detail));
    }

    private void setStatus(String text, StatusLevel level) {
        // While a run is in flight the compute service's phase messages are the most
        // useful thing on screen, so mirror them into the rail beside the progress bar
        // as well as into the status line.
        if (computeStage.isVisible() && level == StatusLevel.INFO && text != null) {
            computeStage.setText(text);
        }
        statusLabel.setText(text);
        statusLabel.setStyle(switch (level) {
            case INFO -> "-fx-text-fill: white;";
            case WARN -> "-fx-text-fill: #ffaa00;";
            case ERROR -> "-fx-text-fill: #ff5555;";
            case SUCCESS -> "-fx-text-fill: #55ff55;";
        });
        if (level != StatusLevel.INFO) {
            var pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
            pause.setOnFinished(e -> {
                statusLabel.setText("");
                statusLabel.setStyle("-fx-text-fill: white;");
            });
            pause.play();
        }
    }

    // --- Lifecycle ---

    public void shutdown() {
        computeService.shutdown();
        detachSelectionListener();
        if (imageDataListener != null) {
            qupath.imageDataProperty().removeListener(imageDataListener);
        }
    }
}
