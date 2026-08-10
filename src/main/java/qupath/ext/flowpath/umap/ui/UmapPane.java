package qupath.ext.flowpath.umap.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasurementKeys;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.engine.UmapComputeService;
import qupath.ext.flowpath.umap.model.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.util.*;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Main panel for the qUMAP extension.
 * Orchestrates UMAP computation, visualization, polygon gating, and marker overlays.
 */
public class UmapPane extends BorderPane {

    private final QuPathGUI qupath;

    // Data
    private CellIndex cellIndex;
    private MarkerStats markerStats;
    private UmapResult umapResult;

    /**
     * The gating phenotyping this view is showing, or {@code null} when the pane is
     * running standalone off the image hierarchy.
     * <p>
     * When present it is the authority for cell identity, colours and the legend: the
     * pane does not rebuild an index, does not re-read {@code PathClass}, and does not
     * apply its own annotation filter, because the gating pane already did all three.
     */
    private PhenotypeSnapshot snapshot;

    /**
     * Phenotypes the user has clicked out of the plot via the legend.
     * <p>
     * A view-only filter: hiding a population removes its points from the canvas and
     * nothing else. It never touches classifications, never changes what a polygon gate
     * selects, and is not persisted — it exists so a 60%-of-the-slide population can be
     * moved out of the way while inspecting what it was covering.
     */
    private final Set<String> hiddenPhenotypes = new LinkedHashSet<>();
    private final List<PopulationTag> populationTags = new ArrayList<>();

    /**
     * Inside-mask of the current polygon gate, or null when no gate is closed.
     * <p>
     * This replaces the old {@code originalClasses} backup array. Gating used to
     * express "unfocused" by overwriting every outside cell's {@link PathClass} in the
     * live hierarchy and keeping an in-memory backup to undo it. That made a purely
     * visual operation destructive: the backup was dropped on image switch and after
     * tagging, and QuPath offers no undo for bulk classification changes, so a save or
     * a closed window in between left the user's classifications permanently replaced.
     * The gate is now visual (grey-out in the canvas) plus a viewer selection, and
     * nothing writes to the hierarchy until the user explicitly presses Tag Selection.
     */
    private boolean[] gateMask;

    /**
     * Per-cell phenotype colours derived from PathClass, before any gate greying.
     * Cached so toggling a gate recolours without re-reading every PathObject.
     */
    private int[] baseColors;

    /**
     * Guards the two index-building paths against each other. Both run off the FX
     * thread; without this, rapid edits (toggling the ROI filter, or editing several
     * feature rows) leave whichever thread finishes last as the winner rather than
     * whichever request the user made last.
     */
    private final AtomicInteger indexGeneration = new AtomicInteger();

    /** Reentrancy guard so our own selection pushes don't echo back as viewer events. */
    private boolean syncingSelection = false;

    private PathObjectSelectionListener selectionListener;

    // Feature selection (per-marker compartment + statistic + include)
    private List<String> currentMarkers = new ArrayList<>();
    private CompartmentCapability capability = CompartmentCapability.empty();
    private MarkerSelection markerSelection = new MarkerSelection();

    /** Placeholder entry in the marker dropdown meaning "colour by phenotype, not expression". */
    private static final String NO_MARKER = "-- none --";

    /** ImageData property key under which the per-marker selection is persisted. */
    private static final String SELECTION_PROPERTY = "qumap.markerSelection";

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
        progressIndicator.setVisible(false);

        // --- Non-compute controls (compute controls are built inside ComputeController) ---

        markerDropdown = new ComboBox<>();
        markerDropdown.setPromptText(NO_MARKER);
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
            } else if (gateMask != null) {
                pushGateSelectionToViewer();
            }
        });

        drawButton = new ToggleButton("Draw Polygon");
        drawButton.setTooltip(new Tooltip("Draw a polygon gate on the UMAP plot.\nClick to add vertices, double-click to close.\nDrag vertices to adjust the shape."));
        drawButton.setOnAction(e -> {
            if (drawButton.isSelected()) {
                polygonSelector.activate();
            } else {
                polygonSelector.deactivate();
            }
        });

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
        // doesn't crowd the toolbar. Editing a row mutates markerSelection in place;
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

        // Build ComputeController first — it owns the compute / cancel buttons
        // that UiStateController needs as constructor args. We then build
        // UiStateController with those buttons and inject it back into
        // ComputeController via attachUiState. The order matters: lifecycle
        // methods on ComputeController must not run before attachUiState.
        computeController = new ComputeController(
                computeService,
                () -> cellIndex,
                this::computeRestingState,
                this::onUmapResultReady,
                this::setStatus,
                () -> getScene() != null ? getScene().getWindow() : null,
                dotSize -> {
                    umapCanvas.setDotSize(dotSize);
                    markerOverlay.setDotSize(dotSize);
                });
        uiState = new UiStateController(
                computeController.getComputeButton(), computeController.getCancelButton(), progressIndicator,
                drawButton, clearButton,
                tagNameField, tagColorPicker, applyTagButton,
                exportButton);
        computeController.attachUiState(uiState);
        uiState.setState(UiStateController.UiState.NO_IMAGE);

        // --- Layout ---

        // Advanced controls — hidden unless "Custom" preset
        var advancedParams = new HBox(6,
                new Label("k:"), computeController.getKSpinner(),
                new Label("Epochs:"), computeController.getEpochsSpinner(),
                new Separator(Orientation.VERTICAL),
                new Label("Subsample:"), computeController.getSubsampleMode(),
                new Label("Max:"), computeController.getMaxCellsSpinner()
        );
        advancedParams.setAlignment(Pos.CENTER_LEFT);
        advancedParams.setVisible(false);
        advancedParams.setManaged(false);

        // Show/hide advanced controls based on preset
        computeController.getQualityPreset().valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean show = "Custom".equals(newVal);
            advancedParams.setVisible(show);
            advancedParams.setManaged(show);
        });

        // Toolbar row 1
        var row1 = new HBox(6,
                computeController.getComputeButton(), computeController.getCancelButton(), progressIndicator,
                roiFilterCheckBox, viewerSyncCheckBox,
                computeController.getQualityPreset(),
                new Label("Scale:"), computeController.getScalingMode(),
                advancedParams,
                new Label("Dot size:"), computeController.getDotSizeSpinner()
        );
        row1.setPadding(new Insets(4));
        row1.setAlignment(Pos.CENTER_LEFT);

        // Toolbar row 2
        var row2 = new HBox(6,
                new Label("Marker:"), markerDropdown, colorScaleDropdown,
                featuresButton,
                new Separator(Orientation.VERTICAL),
                drawButton, clearButton,
                new Separator(Orientation.VERTICAL),
                new Label("Population:"), tagNameField, tagColorPicker, applyTagButton,
                new Separator(Orientation.VERTICAL),
                exportButton
        );
        row2.setPadding(new Insets(4));
        row2.setAlignment(Pos.CENTER_LEFT);

        var toolbar = new VBox(row1, row2);
        toolbar.setStyle("-fx-background-color: #333;");
        setTop(toolbar);

        // Center: UMAP canvas + optional marker overlay + legend
        var legendBox = new VBox(legend, colorScaleLegend);
        VBox.setVgrow(legend, Priority.ALWAYS);

        centerSplit = new SplitPane(umapCanvas, legendBox);
        centerSplit.setDividerPositions(0.85);
        setCenter(centerSplit);

        // Status bar
        var statusBar = new HBox(8, statusLabel);
        statusBar.setPadding(new Insets(3, 6, 3, 6));
        statusBar.setStyle("-fx-background-color: #2a2a2a;");
        setBottom(statusBar);

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
            if (hiddenPhenotypes.isEmpty()) return;
            hiddenPhenotypes.clear();
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
                        drawButton.setSelected(false);
                    } else if (gateMask != null) {
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
        if (incoming == null) {
            snapshot = null;
            return;
        }

        boolean sameCells = snapshot != null && snapshot.index() == incoming.index();
        this.snapshot = incoming;

        if (sameCells) {
            // Gate edit only: keep the embedding, restyle it.
            baseColors = null;                  // force a re-derive from the new labels
            updatePhenotypeColors();
            updateLegend();
            setStatus(describeSnapshot(incoming) + " — recoloured from the gating tree.",
                    StatusLevel.INFO);
            return;
        }

        // New cell set: everything derived from the old index is stale.
        indexGeneration.incrementAndGet();
        gateGeneration.incrementAndGet();
        computeService.cancel();
        computeController.disposeProgressDialog();
        umapResult = null;
        gateMask = null;
        baseColors = null;
        populationTags.clear();
        umapCanvas.setData(null, null);
        umapCanvas.setHighlightIndices(null);
        umapCanvas.setHighlightMask(null);
        markerOverlay.setData(null, null);
        colorScaleLegend.clear();
        polygonSelector.clear();
        polygonSelector.deactivate();
        drawButton.setSelected(false);

        cellIndex = incoming.index();
        markerStats = incoming.stats();
        currentMarkers = incoming.markerNames();
        capability = incoming.capability();
        markerSelection = seedSelection(incoming);

        markerDropdown.getItems().setAll(NO_MARKER);
        markerDropdown.getItems().addAll(currentMarkers);
        markerDropdown.setValue(NO_MARKER);

        featureSelectionPane.populate(currentMarkers, capability, markerSelection);
        updateLegend();

        ImageData<?> imageData = qupath.getImageData();
        if (imageData != null) {
            attachSelectionListener(imageData);
        }

        uiState.setState(UiStateController.UiState.READY);
        setStatus(describeSnapshot(incoming) + " — ready to embed.", StatusLevel.INFO);
    }

    /** {@code true} when this pane is driven by the gating tree rather than the hierarchy. */
    private boolean isSnapshotMode() {
        return snapshot != null;
    }

    /**
     * The initial feature selection for a snapshot: the markers the user actually gated
     * on, in the compartment and statistic they gated them in.
     * <p>
     * Defaulting to the gated panel rather than to every channel on the slide is the
     * single biggest usability difference between the fused view and the old standalone
     * one. A 40-plex image opened cold offers 40 checkboxes and no guidance; opened from
     * a gate tree it offers the 8 markers that define the phenotypes on screen, already
     * ticked. Ungated markers stay available in the picker, just unticked.
     */
    private MarkerSelection seedSelection(PhenotypeSnapshot incoming) {
        MarkerSelection sel = MarkerSelection.defaultFor(incoming.markerNames());
        List<String> gated = incoming.gatedMarkers();
        if (gated.isEmpty()) {
            return sel;   // nothing gated yet — fall back to "everything included"
        }
        MarkerSelection gateSel = incoming.gateSelection();
        for (String marker : incoming.markerNames()) {
            boolean isGated = gated.contains(marker);
            if (isGated) {
                var e = gateSel.entryFor(marker);
                // Only honour a compartment/statistic the image actually carries;
                // otherwise the column resolves to NaN for every cell.
                Compartment c = capabilityAllows(incoming.capability(), marker, e.compartment())
                        ? e.compartment() : Compartment.defaultCompartment();
                Statistic st = capabilityAllowsStat(incoming.capability(), marker, e.statistic())
                        ? e.statistic() : Statistic.defaultStatistic();
                sel.put(marker, new MarkerSelection.Entry(c, st, true));
            } else {
                sel.put(marker, sel.entryFor(marker).withIncluded(false));
            }
        }
        return sel;
    }

    private static boolean capabilityAllows(CompartmentCapability cap, String marker, Compartment c) {
        return !cap.isRich() ? c == Compartment.defaultCompartment()
                : cap.compartmentsFor(marker).contains(c);
    }

    private static boolean capabilityAllowsStat(CompartmentCapability cap, String marker, Statistic st) {
        return !cap.isRich() ? st == Statistic.defaultStatistic()
                : cap.statisticsFor(marker).contains(st);
    }

    /** One-line summary of a snapshot for the status bar. */
    private static String describeSnapshot(PhenotypeSnapshot s) {
        int populations = s.populations().size();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%,d cells", s.includedCount()));
        int dropped = s.cellCount() - s.includedCount();
        if (dropped > 0) {
            sb.append(String.format(" (%,d filtered out)", dropped));
        }
        if (s.hasPhenotypes()) {
            sb.append(String.format(", %d phenotype%s from %d gate%s",
                    populations, populations == 1 ? "" : "s",
                    s.gateCount(), s.gateCount() == 1 ? "" : "s"));
        } else {
            sb.append(", no gates applied yet");
        }
        if (!s.gatedMarkers().isEmpty()) {
            sb.append(String.format(", %d gated marker%s pre-selected",
                    s.gatedMarkers().size(), s.gatedMarkers().size() == 1 ? "" : "s"));
        }
        return sb.toString();
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
        if (isSnapshotMode()) {
            snapshot = null;
            clearDerivedState();
            uiState.setState(UiStateController.UiState.NO_IMAGE);
            setStatus("Image changed — waiting for the gating tree to re-index.", StatusLevel.INFO);
            return;
        }

        int carriedTags = populationTags.size();
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
        computeController.disposeProgressDialog();
        umapResult = null;
        gateMask = null;
        baseColors = null;
        hiddenPhenotypes.clear();
        populationTags.clear();
        umapCanvas.setData(null, null);
        umapCanvas.setHighlightIndices(null);
        umapCanvas.setVisibleMask(null);
        markerOverlay.setData(null, null);
        legend.clear();
        colorScaleLegend.clear();
        polygonSelector.clear();
        polygonSelector.deactivate();
        drawButton.setSelected(false);
    }

    /**
     * Tear down derived state and rebuild the cell index for the current image.
     * <p>
     * Shared by the image-change listener and the annotation-filter toggle: both
     * invalidate the indexed cell set, and therefore the embedding and any gate built
     * on it. Safe to call repeatedly — {@link #indexGeneration} ensures only the most
     * recent invocation's result is applied.
     *
     * @param notice optional status message explaining why the reload happened
     */
    private void reloadCells(String notice) {
        // Invalidate any in-flight index build and gate computation.
        int generation = indexGeneration.incrementAndGet();
        gateGeneration.incrementAndGet();

        // Tear down any running computation cleanly
        computeService.cancel();
        computeController.disposeProgressDialog();
        umapResult = null;
        cellIndex = null;
        markerStats = null;
        gateMask = null;
        baseColors = null;
        currentMarkers = new ArrayList<>();
        capability = CompartmentCapability.empty();
        markerSelection = new MarkerSelection();
        populationTags.clear();
        umapCanvas.setData(null, null);
        umapCanvas.setHighlightIndices(null);
        markerOverlay.setData(null, null);
        legend.clear();
        colorScaleLegend.clear();
        polygonSelector.clear();
        polygonSelector.deactivate();

        // Reset to NO_IMAGE — disables everything, hides cancel/progress, clears draw selection
        uiState.setState(UiStateController.UiState.NO_IMAGE);

        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) {
            setStatus("No image loaded", StatusLevel.INFO);
            return;
        }
        attachSelectionListener(imageData);

        var detections = collectDetections(imageData);

        if (detections.isEmpty()) {
            setStatus(roiFilterCheckBox.isSelected()
                    ? "No cell detections found inside annotations"
                    : "No cell detections found", StatusLevel.WARN);
            return;
        }

        // Discover marker names (structured keys collapsed to base markers)
        List<String> markers = discoverMarkerNames(imageData, detections);
        if (markers.isEmpty()) {
            setStatus("No markers found in measurements", StatusLevel.WARN);
            return;
        }

        // Scan per-compartment capability from a sample of detections. Drives
        // whether the feature picker's compartment/statistic combos are live.
        CompartmentCapability builtCapability = CompartmentCapability.scan(detections, 20);

        // Load any persisted per-marker selection for this image; legacy / v1
        // images (no property, or no rich keys) fall back to whole-cell mean.
        MarkerSelection loadedSelection = loadSelection(imageData, markers, builtCapability);

        setStatus("Building cell index...", StatusLevel.INFO);

        // Build CellIndex and MarkerStats off the FX thread
        var detectionsCopy = new ArrayList<>(detections);
        var markersCopy = List.copyOf(markers);
        Thread bgThread = new Thread(() -> {
            CellIndex builtIndex = CellIndex.build(detectionsCopy, markersCopy, loadedSelection);
            MarkerStats builtStats = MarkerStats.compute(builtIndex);
            Platform.runLater(() -> {
                // A newer reload superseded this one (e.g. the user toggled the
                // annotation filter twice). Without this guard the winner is whichever
                // thread happened to finish last, not the request the user made last.
                if (generation != indexGeneration.get()) return;
                cellIndex = builtIndex;
                markerStats = builtStats;
                currentMarkers = markersCopy;
                capability = builtCapability;
                markerSelection = loadedSelection;
                uiState.setState(UiStateController.UiState.READY);

                markerDropdown.getItems().clear();
                markerDropdown.getItems().add(NO_MARKER);
                markerDropdown.getItems().addAll(markersCopy);
                markerDropdown.setValue(NO_MARKER);

                featureSelectionPane.populate(markersCopy, builtCapability, loadedSelection);

                // The reload notice rides along with the ready message rather than
                // being posted up-front, where "Building cell index..." would wipe it
                // before the user could read it.
                setStatus(String.format("%s%,d cells, %d markers%s. Ready to compute UMAP.",
                        notice != null ? notice + ". " : "",
                        builtIndex.size(), markersCopy.size(),
                        builtCapability.isRich() ? " (per-compartment)" : ""), StatusLevel.INFO);
            });
        }, "flowpath-umap-init");
        bgThread.setDaemon(true);
        bgThread.start();
    }

    /**
     * The set of detections this panel operates on: all non-Excluded detections,
     * narrowed to those inside annotation ROIs when the annotation filter is on.
     * <p>
     * Extracted so the feature-rebuild path uses exactly the same cell set as the
     * initial load. It previously re-queried the hierarchy without applying the
     * annotation filter, so editing a feature silently widened the analysis back to
     * the whole slide — and, because population tag masks are indexed positionally
     * against this list, quietly misaligned every existing tag.
     */
    private List<PathObject> collectDetections(ImageData<?> imageData) {
        if (imageData == null) return new ArrayList<>();

        var detections = imageData.getHierarchy().getDetectionObjects()
                .stream()
                .filter(d -> {
                    var pc = d.getPathClass();
                    return pc == null || !"Excluded".equals(pc.getName());
                })
                .collect(Collectors.toCollection(ArrayList::new));

        // Filter by annotation ROIs if enabled
        if (roiFilterCheckBox.isSelected()) {
            List<ROI> rois = imageData.getHierarchy().getAnnotationObjects()
                    .stream()
                    .map(PathObject::getROI)
                    .filter(Objects::nonNull)
                    .toList();
            if (!rois.isEmpty()) {
                detections.removeIf(d -> {
                    ROI cellRoi = d.getROI();
                    if (cellRoi == null) return true;
                    double cx = cellRoi.getCentroidX();
                    double cy = cellRoi.getCentroidY();
                    return rois.stream().noneMatch(roi -> roi.contains(cx, cy));
                });
            }
        }
        return detections;
    }

    // --- Feature selection (per-marker compartment / statistic / include) ---

    /**
     * Load the persisted {@link MarkerSelection} for an image from its
     * {@link #SELECTION_PROPERTY}. Falls back to whole-cell/mean defaults for every
     * marker when nothing is stored or the payload is legacy/unrecognised. For
     * legacy (non-rich) data, the selection is forced to whole-cell mean.
     */
    private MarkerSelection loadSelection(ImageData<?> imageData, List<String> markers,
                                          CompartmentCapability cap) {
        MarkerSelection sel = MarkerSelection.defaultFor(markers);
        if (cap.isRich()) {
            Object stored = imageData.getProperty(SELECTION_PROPERTY);
            if (stored instanceof String s) {
                MarkerSelection parsed = MarkerSelection.deserialize(s);
                // Overlay stored entries onto the defaults, but only for markers
                // that still exist and only with compartments/statistics the data
                // actually carries (otherwise keep the default).
                for (String marker : markers) {
                    var e = parsed.entryFor(marker);
                    if (!parsed.markers().contains(marker)) continue;
                    Compartment c = cap.compartmentsFor(marker).contains(e.compartment())
                            ? e.compartment() : Compartment.defaultCompartment();
                    Statistic st = cap.statisticsFor(marker).contains(e.statistic())
                            ? e.statistic() : Statistic.defaultStatistic();
                    sel.put(marker, new MarkerSelection.Entry(c, st, e.included()));
                }
            }
        }
        return sel;
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
            imageData.setProperty(SELECTION_PROPERTY, markerSelection.serialize());
        }
        if (currentMarkers.isEmpty()) return;

        // Rebuild the index with the new resolution off the FX thread.
        // In snapshot mode the cell set is the gating pane's, already narrowed by its
        // quality and annotation filters. Re-querying the hierarchy would widen it back
        // to the whole slide and break the positional alignment every snapshot array
        // depends on, so reuse exactly the objects the snapshot indexed.
        List<PathObject> detections = isSnapshotMode()
                ? List.of(snapshot.index().getObjects())
                : collectDetections(imageData);
        if (detections.isEmpty()) return;

        // The rebuild can take a few seconds on a large slide; without feedback it
        // looks like a freeze, so announce start and completion in the status bar.
        setStatus("Updating features — rebuilding cell index...", StatusLevel.INFO);
        var markersCopy = List.copyOf(currentMarkers);
        var selectionCopy = markerSelection;
        // Shares the generation counter with reloadCells: editing several feature rows
        // in quick succession (or editing one while a reload is in flight) must land on
        // the newest request's result, not whichever build finishes last.
        int generation = indexGeneration.incrementAndGet();
        Thread bg = new Thread(() -> {
            CellIndex rebuilt = CellIndex.build(detections, markersCopy, selectionCopy);
            MarkerStats rebuiltStats = MarkerStats.compute(rebuilt);
            Platform.runLater(() -> {
                if (generation != indexGeneration.get()) return;
                cellIndex = rebuilt;
                markerStats = rebuiltStats;
                // Refresh the overlay if one is showing for the selected marker.
                onMarkerSelected();
                setStatus(String.format("Features updated — %,d cells, %d markers. Recompute UMAP to apply.",
                        rebuilt.size(), markersCopy.size()), StatusLevel.SUCCESS);
            });
        }, "flowpath-umap-features");
        bg.setDaemon(true);
        bg.start();
    }

    private List<String> discoverMarkerNames(ImageData<?> imageData, Collection<PathObject> detections) {
        // Primary: from image metadata channels
        var channels = imageData.getServer().getMetadata().getChannels();
        List<String> candidates = new ArrayList<>();
        for (var ch : channels) {
            candidates.add(ch.getName());
        }
        // Collapse any structured channel names to their base marker so a channel
        // literally named "CD3: Nucleus: Mean" shows once as "CD3".
        candidates = collapseToBaseMarkers(candidates);

        // Validate against actual measurements (sample up to 20 cells to avoid outlier bias)
        if (!candidates.isEmpty() && !detections.isEmpty()) {
            Set<String> allKeys = new HashSet<>();
            int sampled = 0;
            for (PathObject obj : detections) {
                var measurements = obj.getMeasurements();
                if (measurements != null) {
                    allKeys.addAll(measurements.keySet());
                }
                if (++sampled >= 20) break;
            }
            candidates.removeIf(name -> {
                if (allKeys.contains(name)) return false;
                // Check layer-prefixed
                for (String key : allKeys) {
                    if (key.endsWith("] " + name)) return false;
                }
                return true;
            });
        }

        // Fallback: from measurements directly (sample up to 20 cells)
        if (candidates.isEmpty() && !detections.isEmpty()) {
            Set<String> exclude = Set.of(
                    "Centroid X", "Centroid Y", "Centroid X µm", "Centroid Y µm",
                    "area", "area µm²", "eccentricity", "perimeter", "convex_area",
                    "axis_major_length", "axis_minor_length", "solidity",
                    "x", "y", "label", "fov", "cell_size"
            );
            Set<String> allKeys = new LinkedHashSet<>();
            int sampled = 0;
            for (PathObject obj : detections) {
                var measurements = obj.getMeasurements();
                if (measurements != null) {
                    allKeys.addAll(measurements.keySet());
                }
                if (++sampled >= 20) break;
            }
            for (String key : allKeys) {
                boolean skip = false;
                for (String ex : exclude) {
                    if (key.equalsIgnoreCase(ex) || key.startsWith(ex)) { skip = true; break; }
                }
                if (!skip) candidates.add(key);
            }
            candidates = collapseToBaseMarkers(candidates);
        }

        return candidates;
    }

    /**
     * Collapse structured measurement keys to their base marker, preserving order
     * and de-duplicating. A key like {@code "CD3: Nucleus: Mean"} (optionally with
     * a {@code "[Layer0] "} prefix) collapses to {@code "CD3"}; a bare name like
     * {@code "DAPI"} is kept verbatim (after stripping any layer prefix). This is
     * what makes the marker list show one row per marker rather than one per
     * compartment/statistic combination.
     */
    private static List<String> collapseToBaseMarkers(List<String> names) {
        var seen = new LinkedHashSet<String>();
        for (String name : names) {
            var parsed = MeasurementKeys.parse(name);
            String base = parsed != null ? parsed.marker() : MeasurementKeys.stripLayerPrefix(name);
            if (base != null && !base.isBlank()) seen.add(base);
        }
        return new ArrayList<>(seen);
    }

    // --- Compute integration ---
    //
    // The actual UMAP run, cancel, complete/error wiring, and progress dialog
    // lifecycle live in ComputeController. UmapPane provides only the bridges
    // needed to honor data ownership: a resting-state resolver (because the
    // state depends on umapResult/cellIndex which remain UmapPane-owned) and a
    // result consumer that pushes the embedding into the canvases and legend.

    /**
     * Resolve the post-cancel / post-error resting UI state. COMPUTED if a prior
     * result is on screen, READY if cells are indexed, NO_IMAGE otherwise.
     * Passed to {@link ComputeController} via {@code Supplier<UiState>} so the
     * controller never needs direct access to {@code umapResult}/{@code cellIndex}.
     */
    private UiStateController.UiState computeRestingState() {
        if (umapResult != null) {
            // An open gate keeps the tag controls unlocked — returning COMPUTED here
            // would disable Tag Selection out from under a user who has a polygon
            // closed and is only, say, waiting for an export to finish.
            if (gateMask != null) return UiStateController.UiState.GATING;
            return populationTags.isEmpty()
                    ? UiStateController.UiState.COMPUTED
                    : UiStateController.UiState.TAGGED;
        }
        if (cellIndex != null) return UiStateController.UiState.READY;
        return UiStateController.UiState.NO_IMAGE;
    }

    /**
     * Consume a finished {@link UmapResult} from {@link ComputeController}. The
     * controller has ALREADY set {@link UiStateController.UiState#COMPUTED} and
     * closed the progress dialog before invoking this — so when we read raw
     * accessors and push them into the canvases, the UI state matches the data.
     */
    private void onUmapResultReady(UmapResult result) {
        this.umapResult = result;

        // A fresh embedding invalidates any gate drawn on the previous one: the
        // polygon's coordinates refer to a layout that no longer exists.
        gateGeneration.incrementAndGet();
        gateMask = null;
        polygonSelector.clear();
        polygonSelector.deactivate();

        // Population tag masks are indexed positionally against the result's object
        // array. A recompute after a feature change can produce a different cell
        // count, at which point the masks refer to nothing meaningful — drop them
        // rather than render rings against mismatched indices.
        if (!populationTags.isEmpty()
                && populationTags.get(0).mask().length != result.size()) {
            populationTags.clear();
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

        // Friendly default: if the user hasn't picked a marker yet, auto-select the
        // first one so a fresh embedding shows expression immediately instead of
        // sitting on NO_MARKER and forcing a hunt through the dropdown.
        String currentMarker = markerDropdown.getValue();
        boolean noMarkerChosen = currentMarker == null || NO_MARKER.equals(currentMarker);
        if (noMarkerChosen && !currentMarkers.isEmpty()) {
            markerDropdown.setValue(currentMarkers.get(0));
            onMarkerSelected(); // setValue does not reliably fire the action handler
        }
    }

    // --- Phenotype Coloring ---

    /** Packed RGB used for cells outside the current gate. */
    private static final int UNFOCUSED_RGB = 0x505050;

    /**
     * Re-apply gate shading to the cached phenotype colours.
     * <p>
     * Used when only the gate changed. {@link #updatePhenotypeColors()} re-derives every
     * point's colour by calling {@code getPathClass().getColor()} once per cell, which
     * on a multi-million-cell embedding is millions of virtual calls on the FX thread —
     * pure waste when the classifications have not moved and only the mask has.
     */
    private void refreshGateShading() {
        if (baseColors == null) {
            updatePhenotypeColors();
            return;
        }
        umapCanvas.setPointColors(applyGateShading(baseColors));
    }

    /** Packed RGB for cells the quality/ROI filters removed, when they are still drawn. */
    private static final int FILTERED_RGB = 0x3A3A3A;

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
        if (umapResult == null) return;

        // Raw accessor: read-only iteration over PathObject references.
        PathObject[] objects = umapResult.getObjectsRaw();
        int n = objects.length;
        int[] colors = new int[n];

        // The snapshot's arrays are positional against its CellIndex. The embedding
        // covers every indexed cell (subsampled runs project the remainder rather than
        // dropping it), so the two line up 1:1 — but only while the index is the one the
        // snapshot was taken from. A length mismatch means they have drifted apart, and
        // painting through it would mislabel cells; fall back rather than lie.
        boolean fromGating = snapshot != null && snapshot.cellCount() == n;

        if (fromGating) {
            String[] labels = snapshot.phenotypes();
            int[] gateColors = snapshot.colors();
            boolean[] excluded = snapshot.excluded();
            for (int i = 0; i < n; i++) {
                if (excluded[i]) {
                    colors[i] = FILTERED_RGB;
                } else if (PhenotypeSnapshot.UNCLASSIFIED.equals(labels[i])) {
                    colors[i] = UNCLASSIFIED_RGB;
                } else {
                    colors[i] = gateColors[i] & 0xFFFFFF;
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                PathClass pc = objects[i].getPathClass();
                if (pc != null) {
                    int c = pc.getColor();
                    // QuPath uses ARGB, extract RGB
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    colors[i] = (r << 16) | (g << 8) | b;
                } else {
                    colors[i] = UNCLASSIFIED_RGB;
                }
            }
        }

        baseColors = colors;
        umapCanvas.setPointColors(applyGateShading(colors));
        applyPhenotypeVisibility();
    }

    /** Neutral grey for cells with no phenotype. */
    private static final int UNCLASSIFIED_RGB = 0x808080;

    /**
     * Hide or show one phenotype in the plot, then refresh the legend so the row
     * reflects its new state.
     */
    private void togglePhenotypeVisibility(String name) {
        if (name == null) return;
        if (!hiddenPhenotypes.remove(name)) {
            hiddenPhenotypes.add(name);
        }
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
        if (umapResult == null) return;
        if (hiddenPhenotypes.isEmpty() || snapshot == null
                || snapshot.cellCount() != umapResult.size()) {
            umapCanvas.setVisibleMask(null);
            return;
        }
        String[] labels = snapshot.phenotypes();
        boolean[] visible = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            visible[i] = !hiddenPhenotypes.contains(labels[i]);
        }
        umapCanvas.setVisibleMask(visible);
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
        if (umapResult == null || snapshot == null || gateMask != null) return;
        if (snapshot.cellCount() != umapResult.size()) return;
        if (name == null) {
            umapCanvas.setHighlightIndices(null);
            return;
        }
        String[] labels = snapshot.phenotypes();
        boolean[] excluded = snapshot.excluded();
        boolean[] mask = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            mask[i] = !excluded[i] && name.equals(labels[i]);
        }
        umapCanvas.setHighlightMask(mask);
    }

    /**
     * Refresh the legend from whichever source is authoritative.
     * <p>
     * With a snapshot the legend lists the gate tree's populations with their real cell
     * counts and colours, ordered largest-first; without one it falls back to scanning
     * {@code PathClass} across the embedded objects.
     */
    private void updateLegend() {
        if (snapshot != null) {
            legend.update(snapshot.populations(), populationTags,
                    snapshot.includedCount(), hiddenPhenotypes);
        } else if (umapResult != null) {
            legend.update(umapResult.getObjectsRaw(), populationTags);
        } else {
            legend.clear();
        }
    }

    /**
     * Grey out points outside the gate. Returns {@code colors} unchanged when no gate
     * is active, so the common path allocates nothing.
     * <p>
     * This is what replaced writing a "qUMAP: Unfocused" PathClass onto every outside
     * cell: the same visual result, achieved without touching the user's data.
     */
    private int[] applyGateShading(int[] colors) {
        if (gateMask == null) return colors;
        int[] shaded = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            shaded[i] = (i < gateMask.length && gateMask[i]) ? colors[i] : UNFOCUSED_RGB;
        }
        return shaded;
    }

    // --- Polygon Gating ---

    /** Generation guard so a superseded gate computation cannot apply its result. */
    private final AtomicInteger gateGeneration = new AtomicInteger();

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
        if (umapResult == null) return;

        UmapResult result = umapResult;
        List<double[]> snapshot = new ArrayList<>(vertices);
        int generation = gateGeneration.incrementAndGet();

        Thread worker = new Thread(() -> {
            boolean[] mask = PolygonSelector.computeInsideMask(
                    result.getUmapXRaw(), result.getUmapYRaw(), snapshot);
            int inside = 0;
            for (boolean b : mask) if (b) inside++;
            final int insideCount = inside;

            Platform.runLater(() -> {
                // Superseded by a newer drag, or the embedding changed underneath us.
                if (generation != gateGeneration.get() || umapResult != result) return;

                gateMask = mask;
                // Only the mask changed — reshade the cached colours rather than
                // re-reading every cell's PathClass.
                refreshGateShading();
                pushGateSelectionToViewer();

                // Polygon is closed — unlock tag controls
                uiState.setState(UiStateController.UiState.GATING);
                setStatus(String.format("%,d inside gate / %,d outside",
                        insideCount, mask.length - insideCount), StatusLevel.INFO);
            });
        }, "flowpath-umap-gate");
        worker.setDaemon(true);
        worker.start();
    }

    private void clearPolygon() {
        // Invalidate any in-flight gate computation so it cannot re-apply a mask.
        gateGeneration.incrementAndGet();
        gateMask = null;

        polygonSelector.clear();
        polygonSelector.deactivate();
        umapCanvas.setPolygonCompleted(false);
        refreshGateShading();
        clearViewerSelection();

        // Back to pre-gate state — disables tag controls and clears Draw selection
        uiState.setState(populationTags.isEmpty()
                ? UiStateController.UiState.COMPUTED
                : UiStateController.UiState.TAGGED);

        if (umapResult != null) {
            setStatus(String.format("UMAP: %,d cells", umapResult.size()), StatusLevel.INFO);
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
        if (!viewerSyncCheckBox.isSelected() || umapResult == null || gateMask == null) return;
        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) return;

        PathObject[] objects = umapResult.getObjectsRaw();
        List<PathObject> selected = new ArrayList<>();
        boolean truncated = false;
        int limit = Math.min(objects.length, gateMask.length);
        for (int i = 0; i < limit; i++) {
            if (!gateMask[i]) continue;
            if (selected.size() >= MAX_VIEWER_SELECTION) { truncated = true; break; }
            selected.add(objects[i]);
        }

        syncingSelection = true;
        try {
            imageData.getHierarchy().getSelectionModel().setSelectedObjects(selected, null);
        } finally {
            syncingSelection = false;
        }

        if (truncated) {
            setStatus(String.format(
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
        if (!viewerSyncCheckBox.isSelected() || umapResult == null) return;

        PathObject[] objects = umapResult.getObjectsRaw();
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
        if (syncingSelection || !viewerSyncCheckBox.isSelected() || umapResult == null) return;

        if (allSelected == null || allSelected.isEmpty()) {
            umapCanvas.setHighlightIndices(null);
            return;
        }

        Set<PathObject> selected = new HashSet<>(allSelected);
        PathObject[] objects = umapResult.getObjectsRaw();
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
        if (umapResult == null || gateMask == null) {
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

        // Reuse the gate mask already computed when the polygon closed, rather than
        // re-running the O(cells x vertices) scan on the FX thread. It is derived from
        // the polygon geometry, so it is unaffected by earlier tagging operations.
        PathObject[] objects = umapResult.getObjectsRaw();
        boolean[] insideMask = gateMask;

        // Apply derived PathClass to cells inside the polygon, preserving phenotype
        // color. This is the ONE place the panel writes classifications, and it only
        // runs because the user pressed Tag Selection.
        int tagLimit = Math.min(objects.length, insideMask.length);
        for (int i = 0; i < tagLimit; i++) {
            if (insideMask[i]) {
                PathClass current = objects[i].getPathClass();
                String baseName = current != null ? current.getName() : "Unclassified";
                int originalColor = current != null ? current.getColor() : 0xFF808080;
                // Strip existing tag suffix if present (use lastIndexOf to preserve phenotype names containing ": ")
                int tagSep = baseName.lastIndexOf(": ");
                if (tagSep >= 0) {
                    String possibleTag = baseName.substring(tagSep + 2);
                    // Only strip if it matches a known population tag name
                    boolean isKnownTag = populationTags.stream()
                            .anyMatch(t -> t.name().equals(possibleTag));
                    if (isKnownTag) {
                        baseName = baseName.substring(0, tagSep);
                    }
                }
                PathClass derived = PathClass.fromString(baseName + ": " + name,
                        originalColor);
                objects[i].setPathClass(derived);
            }
        }

        // Create population tag
        PopulationTag tag = new PopulationTag(name, packedColor, insideMask);
        populationTags.add(tag);

        // Update ring rendering
        updatePopulationRings();

        // Retire the gate. Cells outside it were never modified, so there is nothing
        // to restore — the old code needed a restore pass here only because closing a
        // polygon had reclassified every outside cell.
        gateGeneration.incrementAndGet();
        gateMask = null;
        clearViewerSelection();

        polygonSelector.clear();
        polygonSelector.deactivate();
        // Tag is applied — return to TAGGED (mirrors COMPUTED with active tag in legend)
        uiState.setState(UiStateController.UiState.TAGGED);
        updatePhenotypeColors();
        updateLegend();

        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
        }

        setStatus(String.format("Tagged %,d cells as '%s'", tag.count(), name), StatusLevel.SUCCESS);
    }

    private void removePopulationTag(String tagName) {
        if (umapResult == null) return;

        PopulationTag tagToRemove = null;
        for (PopulationTag tag : populationTags) {
            if (tag.name().equals(tagName)) {
                tagToRemove = tag;
                break;
            }
        }
        if (tagToRemove == null) return;

        // Restore original PathClass (strip ": tagName" suffix)
        PathObject[] objects = umapResult.getObjectsRaw();
        boolean[] mask = tagToRemove.mask();
        String suffix = ": " + tagName;
        int limit = Math.min(objects.length, mask.length);
        for (int i = 0; i < limit; i++) {
            if (mask[i]) {
                PathClass current = objects[i].getPathClass();
                if (current != null && current.getName().endsWith(suffix)) {
                    String baseName = current.getName().substring(0,
                            current.getName().length() - suffix.length());
                    // Carry the colour across. Tagging preserved the phenotype colour
                    // on the derived class, so dropping it here left every untagged
                    // cell rendered in QuPath's default rather than its own phenotype.
                    objects[i].setPathClass(
                            PathClass.fromString(baseName, current.getColor()));
                }
            }
        }

        populationTags.remove(tagToRemove);
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
        if (populationTags.isEmpty()) {
            umapCanvas.setPopulationRings(null, null);
            return;
        }

        List<int[]> colors = new ArrayList<>();
        List<boolean[]> masks = new ArrayList<>();
        for (PopulationTag tag : populationTags) {
            colors.add(new int[]{tag.color()});
            masks.add(tag.mask());
        }
        umapCanvas.setPopulationRings(colors, masks);
    }

    // --- Marker Overlay ---

    private void onMarkerSelected() {
        if (umapResult == null || cellIndex == null || markerStats == null) return;

        String selected = markerDropdown.getValue();
        if (selected == null || NO_MARKER.equals(selected)) {
            hideMarkerOverlay();
            return;
        }

        int idx = cellIndex.getMarkerIndex(selected);
        if (idx < 0) return;

        double[] rawValues = cellIndex.getMarkerValues(idx);
        boolean useZScore = "Z-score".equals(colorScaleDropdown.getValue());

        double[] displayValues;
        MarkerOverlayCanvas.ColorScale scale;
        if (useZScore) {
            displayValues = new double[rawValues.length];
            for (int i = 0; i < rawValues.length; i++) {
                displayValues[i] = markerStats.toZScore(selected, rawValues[i]);
            }
            scale = MarkerOverlayCanvas.ColorScale.BLUE_WHITE_RED;
        } else {
            displayValues = rawValues;
            scale = MarkerOverlayCanvas.ColorScale.VIRIDIS;
        }

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
        if (umapResult == null) {
            setStatus("No UMAP data to export", StatusLevel.WARN);
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
        UmapResult result = umapResult;
        CellIndex index = cellIndex;
        MarkerStats stats = markerStats;
        List<PopulationTag> tags = List.copyOf(populationTags);

        exportButton.setDisable(true);
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
                // Re-enable only if the panel is still in a state that allows export;
                // a reload during the write leaves us in READY/NO_IMAGE, where the
                // state machine has already made the correct call.
                uiState.setState(computeRestingState());
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
     * Functional bridge that lets sibling controllers (e.g. {@link ComputeController})
     * push colored / auto-clearing status messages without exposing the raw
     * {@code statusLabel}.
     */
    @FunctionalInterface
    interface StatusReporter {
        void report(String text, StatusLevel level);
    }

    private void setStatus(String text, StatusLevel level) {
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
