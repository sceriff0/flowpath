package qupath.ext.flowpath.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.analysis.AnalysisWindow;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.ui.PopulationRef;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.LivePreviewService;
import qupath.ext.flowpath.io.CsvExportJob;
import qupath.ext.flowpath.io.FlowPathSerializer;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestReport;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.RegionMask;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.UmapWindow;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.PixelCalibration;

import java.util.List;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Main panel for the FlowPath extension.
 * SplitPane: TreeView + QualityFilterPane (left), GateEditorPane (right).
 * Toolbar at bottom for save/load/export.
 */
public class FlowPathPane extends BorderPane {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(FlowPathPane.class);

    private final QuPathGUI qupath;
    private final TreeView<Object> treeView;
    private final GateEditorPane editorPane;
    private final QualityFilterPane qualityFilterPane;
    private final CheckBox roiFilterCheckBox;
    private final CheckBox syncViewerChannelsToggle;
    private final LivePreviewService previewService;
    private final Label statusBar;
    private final ComboBox<String> colorByRootCombo;
    private final Button umapButton;
    private final Button analysisButton;

    /**
     * Whether the UMAP half of the extension is offered to users.
     * <p>
     * The code is complete, but the feature is being held back for a future release, so
     * every entry point into it — the toolbar button, the {@code Ctrl+U} accelerator and
     * the per-pass snapshot push — is gated on this one constant. Nothing under
     * {@code qupath.ext.flowpath.umap} was deleted or stubbed: flipping this to
     * {@code true} restores the feature in full, which is the point of having a single
     * flag rather than commented-out call sites.
     * <p>
     * Package-private so {@code UmapFeatureFlagTest} can assert the shipped value.
     */
    static final boolean UMAP_ENABLED = false;

    /**
     * Whether the Analysis half of the extension is offered to users.
     * <p>
     * Held back for this release exactly as {@link #UMAP_ENABLED} holds back the UMAP, and
     * for the same reason: the feature is complete but is not part of what ships. Every
     * entry point into it — the toolbar button, the per-pass push in
     * {@link #onPreviewUpdated()}, the tree-to-table selection link and
     * {@link #openAnalysisWindow()} itself — is gated on this one constant. Nothing under
     * {@code qupath.ext.flowpath.analysis} was deleted or stubbed: flipping this to
     * {@code true} restores the feature in full, which is the point of having a single flag
     * rather than commented-out call sites.
     * <p>
     * Note the entry point UMAP does not have. {@code AnalysisWindow}'s population-selection
     * listener is wired from this pane's constructor and runs on a tree selection rather
     * than on a button press, so disabling the button alone would leave a window that can
     * never be opened still driving this pane. See the constructor.
     * <p>
     * Package-private so {@code AnalysisFeatureFlagTest} can assert the shipped value.
     */
    static final boolean ANALYSIS_ENABLED = false;

    /**
     * The UMAP view this pane opens and keeps fed. Created eagerly but does not build
     * any UI until the user asks for it — an unopened window costs one object.
     */
    private final UmapWindow umapWindow = new UmapWindow();

    /**
     * The Analysis view this pane opens and keeps fed, the same way {@link #umapWindow} is.
     */
    private final AnalysisWindow analysisWindow = new AnalysisWindow();

    /**
     * The gate tree, the cells, everything derived from the two, and the undo history.
     * {@link #resyncToTree()} is the one place that brings it and the widgets back in line.
     */
    private final GatingSession session;

    private List<String> markerNames;
    private CompartmentCapability compartmentCapability = CompartmentCapability.empty();
    /** What the last ingest could not resolve. Surfaced in the status bar, never modally. */
    private IngestReport ingestReport = IngestReport.empty();
    private boolean suppressRoiFilterEvents = false;

    /**
     * The pane's one thread for heavy work, so it never runs on the FX thread: reading an
     * image's detections and computing their statistics, and exporting the CSV. Single
     * threaded, so two background jobs never race each other for the session's inputs — a
     * re-ingest requested while a CSV export is running simply queues behind it, and lands
     * once the export's gating pass and write are done. It also times {@link #ingest}'s
     * re-read debounce. Shut down in {@link #shutdown()}.
     */
    private final ScheduledExecutorService backgroundExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "flowpath-background");
                t.setDaemon(true);
                return t;
            });

    /** Reads the open image's detections in the background, and again whenever they change. */
    private final IngestCoordinator ingest;

    /** Gates the export snapshot and writes the CSV in the background. */
    private final CsvExportCoordinator csvExport;

    private final Button addRootBtn;
    private final Button exportBtn;
    private final ProgressIndicator spinner;
    /**
     * A gating pass is running; the spinner also shows while {@link #ingest} is busy or
     * {@link #csvExport} is exporting.
     */
    private boolean previewRunning;

    public FlowPathPane(QuPathGUI qupath) {
        this.qupath = qupath;
        this.previewService = new LivePreviewService();
        this.session = new GatingSession(System::currentTimeMillis, this::requestGatingPass);

        // --- Left side: TreeView + Quality Filter ---
        treeView = new TreeView<>();
        treeView.setCellFactory(tv -> {
            FlowPathCell cell = new FlowPathCell();
            cell.setOnEnabledToggled(this::onGateEnabledToggled);
            return cell;
        });
        treeView.setShowRoot(false);
        treeView.setRoot(new TreeItem<>("Root"));
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> onTreeSelectionChanged(sel));
        treeView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                removeSelectedGate();
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN).match(e)) {
                duplicateSelectedGate();
                e.consume();
            }
        });
        // Right-click context menu
        treeView.setOnContextMenuRequested(e -> showTreeContextMenu(e.getScreenX(), e.getScreenY()));

        // Add root gate button
        addRootBtn = new Button("+ Add Root Gate");
        addRootBtn.setMaxWidth(Double.MAX_VALUE);
        addRootBtn.setOnAction(e -> addRootGate());
        addRootBtn.setTooltip(new Tooltip("Add a new top-level gate to the gating hierarchy"));

        // ROI filter
        roiFilterCheckBox = new CheckBox("Filter by annotations");
        roiFilterCheckBox.getStyleClass().add("fp-primary-text");
        roiFilterCheckBox.setStyle("-fx-font-size: 10;");
        roiFilterCheckBox.setOnAction(e -> { if (!suppressRoiFilterEvents) onRoiFilterToggled(); });

        // Auto-sync the QuPath viewer's visible channels to the selected gate's channel(s)
        syncViewerChannelsToggle = new CheckBox("Sync viewer channels");
        syncViewerChannelsToggle.getStyleClass().add("fp-primary-text");
        syncViewerChannelsToggle.setStyle("-fx-font-size: 10;");
        syncViewerChannelsToggle.setSelected(true);
        syncViewerChannelsToggle.setTooltip(new Tooltip(
            "Show only the selected gate's channel(s) in the QuPath viewer."));
        syncViewerChannelsToggle.setOnAction(e -> {
            if (syncViewerChannelsToggle.isSelected()) syncViewerChannels(currentNode);
        });

        qualityFilterPane = new QualityFilterPane(session.tree().getQualityFilter());
        // Recorded before the panel writes into the tree's filter, so undo restores the
        // value the drag started from. Coalesced: a drag is one step.
        qualityFilterPane.setOnBeforeFilterChange(
            () -> session.recordEditCoalesced(GatingSession.EditSource.QUALITY_FILTER));
        qualityFilterPane.setOnFilterChanged(filter -> onQualityFilterChanged());

        // Color-by-root selector (for multi-root trees)
        colorByRootCombo = new ComboBox<>();
        colorByRootCombo.setPromptText("Color by...");
        colorByRootCombo.setMaxWidth(120);
        colorByRootCombo.setDisable(true);
        colorByRootCombo.setTooltip(new Tooltip("Choose which root gate's colors to display"));
        colorByRootCombo.getSelectionModel().selectedIndexProperty().addListener((obs, old, idx) -> {
            if (idx.intValue() >= 0) {
                previewService.setColorRootIndex(idx.intValue());
            }
        });

        HBox treeToolbar = new HBox(4, addRootBtn, colorByRootCombo);
        HBox.setHgrow(addRootBtn, Priority.ALWAYS);

        HBox togglesRow = new HBox(8, roiFilterCheckBox, syncViewerChannelsToggle);
        VBox leftPane = new VBox(4, treeView, treeToolbar, togglesRow, qualityFilterPane);
        VBox.setVgrow(treeView, Priority.ALWAYS);
        leftPane.setPadding(new Insets(4));
        leftPane.setPrefWidth(280);

        // --- Right side: Gate Editor ---
        editorPane = new GateEditorPane();
        editorPane.setOnNodeChanged(node -> onGateNodeChanged());
        editorPane.setOnNodeNormalised(node -> onGateNodeNormalised());
        editorPane.setOnAddToBranch(this::addChildGate);
        editorPane.setOnRemoveGate(this::removeSelectedGate);
        editorPane.setOnReplaceGate(this::replaceGateNode);

        ScrollPane editorScroll = new ScrollPane(editorPane);
        editorScroll.setFitToWidth(true);
        editorScroll.setPrefWidth(420);

        // --- SplitPane ---
        SplitPane splitPane = new SplitPane(leftPane, editorScroll);
        splitPane.setDividerPositions(0.4);
        setCenter(splitPane);

        // --- Status bar ---
        statusBar = new Label("Total: 0 cells | Excluded: 0 | Gates: 0");
        statusBar.getStyleClass().add("fp-muted");
        statusBar.setStyle("-fx-font-size: 11; -fx-padding: 2 6 2 6;");
        spinner = new ProgressIndicator();
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        spinner.setVisible(false);
        previewService.setOnUpdateStarted(() -> Platform.runLater(() -> {
            previewRunning = true;
            updateSpinner();
        }));
        previewService.setOnUpdateComplete(() -> Platform.runLater(() -> {
            previewRunning = false;
            updateSpinner();
            onPreviewUpdated();
        }));
        previewService.setOnStatsRecomputed(() -> {
            // A quality-filter drag recomputes the statistics in the background; adopt them
            // here. computeAncestorMask and the CSV exporter both read the session's
            // statistics, and when the filter narrows the population, ancestors that
            // excludeOutliers reject every cell otherwise -- the editor shows "No data" while
            // the gate-engine count, computed with fresh stats, still reads the true number.
            // The service drops a recompute that a resync has superseded, so this never
            // brings back statistics for a filter the tree no longer has.
            session.adoptStats(previewService.getMarkerStats());
            session.recomputeQualityMask();
            refreshAncestorMask();
            editorPane.setMarkerStats(session.stats());
        });

        // --- Bottom toolbar ---
        Button saveBtn = new Button("Save JSON");
        saveBtn.setOnAction(e -> saveTree());
        saveBtn.setTooltip(new Tooltip("Save gate tree to JSON file (Ctrl+S)"));
        Button loadBtn = new Button("Load JSON");
        loadBtn.setOnAction(e -> loadTree());
        loadBtn.setTooltip(new Tooltip("Load gate tree from JSON file (Ctrl+O)"));
        exportBtn = new Button("Export CSV");
        exportBtn.setOnAction(e -> exportCsv());
        exportBtn.setTooltip(new Tooltip("Export phenotype assignments to CSV (Ctrl+E)"));

        // The bridge to the other half of the extension. See createUmapControl.
        UmapControl umap = createUmapControl(this::openUmapWindow);
        umapButton = umap.button();
        Node umapSlot = umap.slot();

        // Beside UMAP, not folded into it: this reports what the gate tree already found
        // (counts, percentages, density) rather than re-embedding the cells in a new space.
        // See createAnalysisControl.
        AnalysisControl analysis = createAnalysisControl(this::openAnalysisWindow);
        analysisButton = analysis.button();
        Node analysisSlot = analysis.slot();

        // The tree-selection link's reverse direction: a population selected in the Analysis
        // window's table (or clicked on a plot bar) lands the TreeView's selection on the gate
        // that produced it. See onPopulationSelectedFromAnalysis(); the forward direction is
        // wired the other way, inside onTreeSelectionChanged() below.
        //
        // Gated as well, and this is the entry point UMAP had no equivalent of: the listener
        // is driven by a tree selection rather than by the toolbar button, so leaving it
        // wired while the feature is off would let a window the user cannot open still call
        // back into this pane.
        if (ANALYSIS_ENABLED) {
            analysisWindow.setPopulationSelectionListener(this::onPopulationSelectedFromAnalysis);
        }

        HBox toolbarSpacer = new HBox();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8, saveBtn, loadBtn, new Separator(Orientation.VERTICAL),
            exportBtn, toolbarSpacer, analysisSlot, umapSlot);
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6));

        HBox statusRow = new HBox(6, spinner, statusBar);
        statusRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox bottomBox = new VBox(statusRow, toolbar);
        setBottom(bottomBox);

        // --- Keyboard shortcuts ---
        setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(e)) {
                redo(); e.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(e)) {
                undo(); e.consume();
            } else if (new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN).match(e)) {
                saveTree(); e.consume();
            } else if (new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN).match(e)) {
                loadTree(); e.consume();
            } else if (new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN).match(e)) {
                exportCsv(); e.consume();
            } else if (UMAP_ENABLED
                    && new KeyCodeCombination(KeyCode.U, KeyCombination.SHORTCUT_DOWN).match(e)) {
                // Deliberately not consumed while the feature is off, so Ctrl+U falls
                // through to QuPath rather than dying in a dead shortcut.
                openUmapWindow(); e.consume();
            }
        });

        // Style — follows the active QuPath theme's base colour instead of forcing dark.
        getStyleClass().add("fp-panel");

        // Detections are read on backgroundExecutor and applied on the FX thread. FlowPath's own
        // classification writes fire hierarchy events too; the coordinator ignores those.
        ingest = new IngestCoordinator(session, backgroundExecutor, this::scheduleOnBackground,
                Platform::runLater, previewService::isFiringHierarchyEvent, new IngestHost());

        // A CSV export's snapshot is gated and written on the same single-threaded executor,
        // so it never races an ingest for the session's inputs.
        csvExport = new CsvExportCoordinator(backgroundExecutor, Platform::runLater, new CsvExportHost());

        // Initialize from current image
        Platform.runLater(this::initializeFromImage);

        // Listen for image changes
        qupath.imageDataProperty().addListener((obs, oldImg, newImg) -> {
            Platform.runLater(this::initializeFromImage);
        });
    }

    /**
     * Hand the current image to {@link #ingest}. Its detections are read and their statistics
     * computed on {@link #backgroundExecutor}, and every path ends in {@link #render}, so
     * switching images runs a gating pass over the new cells straight away — without freezing
     * QuPath while a million cells are read. The coordinator also listens to the image's
     * hierarchy: cells added or deleted while FlowPath is open are read again (debounced,
     * keeping the gate tree), and an annotation edit under the ROI filter recomputes the mask.
     */
    private void initializeFromImage() {
        ingest.open(qupath.getImageData());
    }

    /** {@link IngestCoordinator}'s debounce timer, on the background thread. */
    private Runnable scheduleOnBackground(Runnable task, long delayMs) {
        ScheduledFuture<?> future = backgroundExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    /** What {@link #ingest} asks of this pane. Every call arrives on the FX thread. */
    private final class IngestHost implements IngestCoordinator.Host {

        @Override
        public List<PathObject> annotations(ImageData<?> imageData) {
            return annotationsToFilterBy(imageData);
        }

        /**
         * Drop every reference to the previous image, in this pane <em>and</em> in the
         * preview service. Clearing only this pane's fields left the service holding the old
         * {@code ImageData}: a gate edit made with no image open — or while the next image is
         * still being read — would re-run gating and write PathClass assignments onto the
         * previous image's detections. The index, statistics and masks reach the service
         * through the resync that follows; the image data is the one piece it does not carry.
         */
        @Override
        public void cleared(IngestCoordinator.Cleared why) {
            markerNames = Collections.emptyList();
            ingestReport = IngestReport.empty();
            previewService.setImageData(null);
            qualityFilterPane.setCellIndex(null);
            // A switch keeps the selected gate, and the channel list its combos show, for the
            // image being read; the editor is disabled until it lands. With no cells to come
            // there is nothing to show — and the gate leaves the editor before the channel
            // list empties, so emptying it cannot retarget the gate's channel.
            if (why != IngestCoordinator.Cleared.LOADING) {
                currentNode = null;
                editorPane.setGateNode(null);
                editorPane.setChannelNames(markerNames);
            }
            if (why == IngestCoordinator.Cleared.NO_DETECTIONS) {
                Dialogs.showWarningNotification("FlowPath", "No detections found. Import GeoJSON cells first.");
            }
        }

        /**
         * One read of the hierarchy: the panel, the per-compartment capability, the index and
         * the report all come from a single measurement-key sample, so the gate editor can no
         * longer offer a compartment the index resolved to nothing. The pixel calibration
         * rides along inside — it is what makes ScaleVerdict possible.
         */
        @Override
        public void ingested(ImageData<?> imageData, IngestResult result) {
            // Re-read on every detection edit: repopulating the channel list the editor's
            // combos share is skipped when the panel is unchanged.
            if (!result.markerNames().equals(markerNames)) {
                markerNames = result.markerNames();
                editorPane.setChannelNames(markerNames);
            }
            compartmentCapability = result.capability();
            ingestReport = result.report();
            editorPane.setCompartmentCapability(compartmentCapability);
            // Which QC metrics exist, and how far each slider should travel, are both read
            // from the index's discovered morphology.
            qualityFilterPane.setCellIndex(result.index());
            previewService.setImageData(imageData);
        }

        @Override
        public void resynced(Optional<GatingSession.MigrationNotice> notice, boolean newIndex) {
            render(notice, newIndex);
        }

        @Override
        public void busyChanged(IngestCoordinator.Busy state) {
            onIngestBusyChanged(state);
        }

        @Override
        public void failed(Throwable error) {
            logger.error("Reading the image's detections failed", error);
            Dialogs.showErrorNotification("FlowPath",
                    "Could not read the detections: " + error.getMessage());
        }
    }

    /** What {@link #csvExport} asks of this pane. Every call arrives on the FX thread. */
    private final class CsvExportHost implements CsvExportCoordinator.Host {
        @Override
        public void exported(File file) {
            updateExportControlsDisabled();
            Dialogs.showInfoNotification("FlowPath", "Exported " + file.getName());
        }

        @Override
        public void failed(Throwable error) {
            logger.error("Exporting the phenotype CSV failed", error);
            updateExportControlsDisabled();
            Dialogs.showErrorMessage("Export Error", error.getMessage());
        }
    }

    /**
     * While a new image is read the session has no cells, so the controls that need them wait
     * for it; a refresh of cells the session still holds disables nothing.
     */
    private void onIngestBusyChanged(IngestCoordinator.Busy state) {
        boolean loading = state == IngestCoordinator.Busy.LOADING;
        addRootBtn.setDisable(loading);
        editorPane.setDisable(loading);
        umapButton.setDisable(!UMAP_ENABLED || session.index() == null);
        analysisButton.setDisable(!ANALYSIS_ENABLED || session.index() == null);
        updateExportControlsDisabled();
        updateStatusBar();
    }

    /**
     * Export CSV (and, through {@link #exportCsv()}'s own guard, Ctrl+E) is disabled while
     * ingest is loading -- there are no cells to export yet -- or while {@link #csvExport}
     * is already running one. One guard, checked from both places that can end either state,
     * rather than a second parallel disable mechanism.
     */
    private void updateExportControlsDisabled() {
        exportBtn.setDisable(ingest.busy() == IngestCoordinator.Busy.LOADING || csvExport.exporting());
        updateSpinner();
    }

    private void updateSpinner() {
        spinner.setVisible(previewRunning || ingest.busy() != IngestCoordinator.Busy.IDLE || csvExport.exporting());
    }

    /**
     * True if a measurement name is a morphology/identity column rather than a marker
     * channel. Delegates to {@link DetectionIngest}, which owns the single copy of the
     * rule; this pane and {@code UmapSession} each used to carry their own, and they did
     * not agree. Package-private so the rule stays testable without a QuPath GUI.
     */
    static boolean isMorphologyName(String name) {
        return DetectionIngest.isMorphologyName(name);
    }

    // --- Tree building ---

    private void rebuildTreeView() {
        TreeItem<Object> root = new TreeItem<>("Root");
        for (GateNode gate : session.tree().getRoots()) {
            root.getChildren().add(buildTreeItem(gate));
        }
        treeView.setRoot(root);
        root.setExpanded(true);
        expandAll(root);
    }

    private void expandAll(TreeItem<?> item) {
        item.setExpanded(true);
        for (TreeItem<?> child : item.getChildren()) {
            expandAll(child);
        }
    }

    private TreeItem<Object> buildTreeItem(GateNode gate) {
        TreeItem<Object> gateItem = new TreeItem<>(gate);
        gateItem.setExpanded(true);

        for (int i = 0; i < gate.getBranches().size(); i++) {
            Branch branch = gate.getBranches().get(i);
            TreeItem<Object> branchItem = new TreeItem<>(new FlowPathCell.BranchItem(gate, branch, i));
            branchItem.setExpanded(true);
            for (GateNode child : branch.getChildren()) {
                branchItem.getChildren().add(buildTreeItem(child));
            }
            gateItem.getChildren().add(branchItem);
        }

        return gateItem;
    }

    // --- Gate operations ---

    private void addRootGate() {
        if (markerNames == null || markerNames.isEmpty()) {
            Dialogs.showWarningNotification("FlowPath", "No markers available. Load an image with detections first.");
            return;
        }
        GateNode node = promptForNewGate();
        if (node == null) return;
        pushUndo();
        session.tree().addRoot(node);
        rebuildTreeView();
        requestPreviewUpdate();
    }

    private void addChildGate(int branchIndex) {
        GateNode selected = getSelectedGateNode();
        if (selected == null || markerNames == null || markerNames.isEmpty()) return;
        if (branchIndex >= selected.getBranches().size()) return;

        GateNode child = promptForNewGate();
        if (child == null) return;
        pushUndo();
        selected.getBranches().get(branchIndex).getChildren().add(child);
        rebuildTreeView();
        requestPreviewUpdate();
    }

    /**
     * Show a dialog to choose gate type and create a new gate node.
     * Returns null if the user cancels.
     */
    private GateNode promptForNewGate() {
        List<String> gateTypes = List.of("Threshold", "Quadrant", "Region");
        ChoiceDialog<String> dialog = new ChoiceDialog<>("Threshold", gateTypes);
        dialog.setTitle("Add Gate");
        dialog.setHeaderText("Select gate type");
        dialog.setContentText("Gate type:");

        var result = dialog.showAndWait();
        if (result.isEmpty()) return null;

        String ch = markerNames.get(0);
        String ch2 = markerNames.size() > 1 ? markerNames.get(1) : ch;

        GateNode gate = switch (result.get()) {
            case "Threshold" -> new GateNode(ch);
            case "Quadrant" -> new QuadrantGate(ch, ch2);
            case "Region" -> new PolygonGate(ch, ch2);
            default -> new GateNode(ch);
        };
        // Resolve the signal selection against this image's measurements before the gate
        // reaches the tree, so it never renders a compartment/statistic badge that the
        // editor then has to correct.
        GateAxis.pinAll(gate, compartmentCapability);
        return gate;
    }

    private void removeSelectedGate() {
        GateNode selected = getSelectedGateNode();
        if (selected == null) return;

        boolean hasChildren = !selected.isLeaf();
        if (hasChildren) {
            boolean confirm = Dialogs.showConfirmDialog("Remove Gate",
                "This gate has child gates. Remove entire subtree?");
            if (!confirm) return;
        }

        pushUndo();

        // Remove from parent
        if (!session.tree().getRoots().remove(selected)) {
            removeFromTree(session.tree().getRoots(), selected);
        }

        editorPane.setGateNode(null);
        suppressTreeSelection = true;
        rebuildTreeView();
        suppressTreeSelection = false;
        requestPreviewUpdate();
    }

    private void replaceGateNode(GateNode oldNode, GateNode newNode) {
        // One step with the editor's gateChanged() that follows, not two.
        session.recordReplacement();
        // Replace in roots
        int rootIdx = session.tree().getRoots().indexOf(oldNode);
        if (rootIdx >= 0) {
            session.tree().getRoots().set(rootIdx, newNode);
        } else {
            replaceInTree(session.tree().getRoots(), oldNode, newNode);
        }
        currentNode = newNode;
        // Suppress selection events during rebuild to prevent the editor from being
        // cleared — the editor already updated its currentNode in the draw callback.
        suppressTreeSelection = true;
        try {
            rebuildTreeView();
            selectNodeInTree(newNode);
        } finally {
            suppressTreeSelection = false;
        }
        requestPreviewUpdate();
    }

    private GateNode currentNode; // tracks currently selected gate for replacement
    private boolean suppressTreeSelection = false;

    // Set for the duration of onPopulationSelectedFromAnalysis()'s own tree selection, so
    // onTreeSelectionChanged does not treat that programmatic move as a user pick and push it
    // straight back to analysisWindow.selectPopulation() -- the round-trip loop the
    // tree-selection link exists to avoid.
    // Deliberately NOT suppressTreeSelection above: that flag also skips the editorPane/ancestor
    // mask update onTreeSelectionChanged performs, and "selecting a population should select its
    // gate" needs that update to still happen.
    private boolean applyingPopulationSelection = false;

    private void replaceInTree(List<GateNode> nodes, GateNode oldNode, GateNode newNode) {
        for (GateNode node : nodes) {
            for (Branch branch : node.getBranches()) {
                int idx = branch.getChildren().indexOf(oldNode);
                if (idx >= 0) {
                    branch.getChildren().set(idx, newNode);
                    return;
                }
                replaceInTree(branch.getChildren(), oldNode, newNode);
            }
        }
    }

    private void selectNodeInTree(GateNode node) {
        boolean wasSuppressed = suppressTreeSelection;
        suppressTreeSelection = true;
        TreeItem<Object> item = findTreeItem(treeView.getRoot(), node);
        if (item != null) {
            treeView.getSelectionModel().select(item);
        }
        suppressTreeSelection = wasSuppressed;
    }

    private TreeItem<Object> findTreeItem(TreeItem<Object> parent, GateNode target) {
        if (parent == null) return null;
        if (parent.getValue() == target) return parent;
        for (TreeItem<Object> child : parent.getChildren()) {
            TreeItem<Object> found = findTreeItem(child, target);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Resolve a population ref pushed back from the Analysis window's table (or a clicked plot
     * bar) against the LIVE {@code session.tree()} and land the TreeView's selection on it — the
     * reverse direction of the push {@link #onTreeSelectionChanged} makes into
     * {@link AnalysisWindow#selectPopulation}.
     * <p>
     * {@code ref} was minted from a report built off {@code session.tree().deepCopy()} (see
     * {@link #buildAnalysisInput()}), so {@link GateTree#findBranch} — not any object
     * reference — is what resolves it against the tree the user may have gone on editing since.
     * A ref that no longer resolves (the gate was deleted, disabled, or renamed since the
     * report was pushed) is ignored silently: a stale ref is an ordinary consequence of live
     * editing, not an error to surface, the same rule {@link GateTree#findBranch}'s own javadoc
     * states.
     */
    private void onPopulationSelectedFromAnalysis(PopulationRef ref) {
        if (ref == null) return;
        Branch branch = session.tree().findBranch(ref.rootIndex(), ref.path());
        if (branch == null) return;
        TreeItem<Object> item = findBranchTreeItem(treeView.getRoot(), branch);
        if (item == null) return;
        applyingPopulationSelection = true;
        try {
            treeView.getSelectionModel().select(item);
            treeView.scrollTo(treeView.getRow(item));
        } finally {
            applyingPopulationSelection = false;
        }
    }

    /** As {@link #findTreeItem}, but locating the {@link FlowPathCell.BranchItem} naming {@code target}. */
    private TreeItem<Object> findBranchTreeItem(TreeItem<Object> parent, Branch target) {
        if (parent == null) return null;
        if (parent.getValue() instanceof FlowPathCell.BranchItem bi && bi.branch == target) {
            return parent;
        }
        for (TreeItem<Object> child : parent.getChildren()) {
            TreeItem<Object> found = findBranchTreeItem(child, target);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The {@code (rootIndex, path)} that names {@code target} in the live {@code session.tree()} —
     * a one-line map from {@link GateTree#locate}'s {@code BranchLocation} (a {@code model}
     * value) onto {@link PopulationRef} (an {@code analysis.ui} value). The walk itself lives
     * exactly once, in {@code GateTree}, alongside {@link GateTree#findBranch} which it is the
     * inverse of, for every path {@code PopulationStats} can emit — see
     * {@code GateTree.BranchLocation}'s own javadoc for the one case (same-named sibling gates)
     * that qualifier exists for, and for why that walk cannot live here or on {@code GateTree}
     * returning a {@code PopulationRef} directly without creating the {@code ui} ↔ {@code model}
     * layering violation this method exists to avoid.
     */
    private PopulationRef populationRefFor(Branch target) {
        GateTree.BranchLocation location = session.tree().locate(target);
        return location == null ? null : new PopulationRef(location.rootIndex(), location.path());
    }

    private boolean removeFromTree(List<GateNode> nodes, GateNode target) {
        for (GateNode node : nodes) {
            for (Branch branch : node.getBranches()) {
                if (branch.getChildren().remove(target)) return true;
                if (removeFromTree(branch.getChildren(), target)) return true;
            }
        }
        return false;
    }

    // --- Selection handling ---

    private void onTreeSelectionChanged(TreeItem<Object> selected) {
        if (suppressTreeSelection) return;
        if (selected == null) {
            editorPane.setAncestorMask(null);
            editorPane.setGateNode(null);
            return;
        }

        Object item = selected.getValue();
        GateNode node = null;
        if (item instanceof GateNode gn) {
            node = gn;
        } else if (item instanceof FlowPathCell.BranchItem branch) {
            node = branch.parentGate;
            // The forward direction: a branch selected in the TREE highlights its population in
            // the Analysis window's table, unless this selection is itself the RESULT of an
            // inbound population pick (see onPopulationSelectedFromAnalysis) -- echoing that
            // back out is the round-trip loop the tree-selection link exists to avoid.
            // AnalysisWindow.selectPopulation is already a no-op while the window is closed, so
            // there is no need to check isShowing() here too.
            if (ANALYSIS_ENABLED && !applyingPopulationSelection) {
                PopulationRef ref = populationRefFor(branch.branch);
                if (ref != null) {
                    analysisWindow.selectPopulation(ref);
                }
            }
        }

        if (node != null) {
            currentNode = node;
            editorPane.setAncestorMask(computeAncestorMask(node));
            editorPane.setGateNode(node);
            syncViewerChannels(node);
        } else {
            editorPane.setAncestorMask(null);
            editorPane.setGateNode(null);
        }
    }

    private boolean[] computeAncestorMask(GateNode node) {
        if (session.index() == null || session.stats() == null) return null;
        return GatingEngine.computeAncestorMask(session.tree(), node, session.index(), session.stats(),
                session.combinedMask());
    }

    /** Recompute and apply the ancestor mask for the currently selected gate. */
    private void refreshAncestorMask() {
        if (currentNode != null) {
            editorPane.setAncestorMask(computeAncestorMask(currentNode));
        }
    }

    private GateNode getSelectedGateNode() {
        TreeItem<Object> sel = treeView.getSelectionModel().getSelectedItem();
        if (sel == null) return null;
        Object item = sel.getValue();
        if (item instanceof GateNode node) return node;
        if (item instanceof FlowPathCell.BranchItem branch) return branch.parentGate;
        return null;
    }

    // --- ROI filtering ---

    /**
     * The annotations the filter should use: whatever is <b>selected</b> in the viewer, or
     * every annotation on the image when the selection holds none.
     * <p>
     * Selection-first matches how the rest of QuPath behaves and makes "gate on just this
     * region" a click rather than a deletion. Falling back to all annotations keeps the
     * previous behaviour intact for anyone who never selects anything.
     */
    private List<PathObject> annotationsToFilterBy(ImageData<?> imageData) {
        var hierarchy = imageData.getHierarchy();
        List<PathObject> selected = new ArrayList<>();
        for (PathObject obj : hierarchy.getSelectionModel().getSelectedObjects()) {
            if (obj != null && obj.isAnnotation() && obj.getROI() != null) selected.add(obj);
        }
        if (!selected.isEmpty()) return selected;
        return new ArrayList<>(hierarchy.getAnnotationObjects());
    }

    /** The annotations for the ROI filter, or none when no image is open. */
    private List<PathObject> annotationsForRoiFilter() {
        ImageData<?> imageData = qupath.getImageData();
        return imageData == null ? List.of() : annotationsToFilterBy(imageData);
    }

    /** One undo step, then the same resync as a load or an undo. */
    private void onRoiFilterToggled() {
        session.setRoiFilterEnabled(roiFilterCheckBox.isSelected());
        resyncToTree();
    }

    // --- Updates ---

    private void onGateNodeChanged() {
        pushUndoCoalesced();
        // A legacy z-score gate whose channel this image lacked keeps its flag. Re-pointed
        // in the editor onto a channel the image does carry, it is convertible now, and must
        // be converted before the pass below reads its z-value as a raw threshold.
        session.migrateLegacyZScores().ifPresent(this::showMigrationNotice);
        treeView.refresh();
        requestPreviewUpdate();
        syncViewerChannels(currentNode);
    }

    /**
     * Opening a gate pinned it to a signal the export carries. No undo step — the user did not
     * edit anything, and undoing it would only have the editor write it again on the next
     * opening — but the tree changed, so it is settled as the pre-state for the next edit's
     * undo step (otherwise that step would silently revert the pin too) and gated, so the
     * counts describe the column the editor now draws.
     */
    private void onGateNodeNormalised() {
        treeView.refresh();
        requestPreviewUpdate();
    }

    private void onGateEnabledToggled(GateNode node) {
        // The cell has already written the flag, so record from the settled tree.
        session.recordAppliedDiscreteEdit();
        requestPreviewUpdate();
    }

    /**
     * Restrict the QuPath viewer's visible channels to those used by {@code gate}.
     * Threshold gates yield 1 channel; 2D gates (Quadrant/Polygon/Rect/Ellipse) yield 2.
     * No-op if the toggle is off, no image is open, the gate has no channels, or none
     * of the gate's channels match an available channel (defensive — never blacks out
     * the viewer on bad data).
     *
     * <p>Channel matching tries multiple name forms because QuPath's
     * {@link DirectServerChannelInfo#getName()} may return a decorated form like
     * {@code "DAPI (Channel 1)"} while {@link DirectServerChannelInfo#getOriginalChannelName()}
     * (and FlowPath's stored marker names) use the raw {@code "DAPI"}.
     */
    private void syncViewerChannels(GateNode gate) {
        if (syncViewerChannelsToggle == null || !syncViewerChannelsToggle.isSelected()) return;
        if (gate == null) return;
        try {
            QuPathViewer viewer = qupath.getViewer();
            if (viewer == null) return;
            ImageDisplay display = viewer.getImageDisplay();
            if (display == null) return;

            List<String> gateChannels = gate.getChannels();
            if (gateChannels == null || gateChannels.isEmpty()) return;

            Set<String> wantedLower = new HashSet<>();
            for (String c : gateChannels) {
                if (c == null || c.isBlank()) continue;
                wantedLower.add(c.toLowerCase(Locale.ROOT));
            }
            if (wantedLower.isEmpty()) return;

            List<ChannelDisplayInfo> available = display.availableChannels();
            if (available == null || available.isEmpty()) return;

            // For each available channel, build the set of candidate names we'll match
            // against (lowercased): getName() always, plus getOriginalChannelName() when
            // it's a DirectServerChannelInfo (the common fluorescence case).
            class Decision {
                final ChannelDisplayInfo info;
                final boolean show;
                Decision(ChannelDisplayInfo info, boolean show) { this.info = info; this.show = show; }
            }
            List<Decision> decisions = new ArrayList<>(available.size());
            int matchCount = 0;
            for (ChannelDisplayInfo info : available) {
                Set<String> candidates = new HashSet<>();
                String displayName = info.getName();
                if (displayName != null) candidates.add(displayName.toLowerCase(Locale.ROOT));
                if (info instanceof DirectServerChannelInfo dsci) {
                    String original = dsci.getOriginalChannelName();
                    if (original != null) candidates.add(original.toLowerCase(Locale.ROOT));
                }
                boolean show = !Collections.disjoint(candidates, wantedLower);
                if (show) matchCount++;
                decisions.add(new Decision(info, show));
            }

            if (matchCount == 0) {
                // No match — leave display untouched and tell the user why so they can
                // check for a name-format mismatch.
                if (logger.isDebugEnabled()) {
                    List<String> availNames = available.stream()
                        .map(ChannelDisplayInfo::getName).toList();
                    logger.debug("Viewer channel sync: no match for gate channels {} in available {}",
                        gateChannels, availNames);
                }
                return;
            }

            for (Decision d : decisions) {
                display.setChannelSelected(d.info, d.show);
            }
            if (logger.isTraceEnabled()) {
                logger.trace("Viewer channel sync: {} of {} channels selected (gate channels {})",
                    matchCount, available.size(), gateChannels);
            }
        } catch (Exception ex) {
            // Never let a viewer-sync failure break gate editing.
            logger.warn("Failed to sync viewer channels: {}", ex.toString());
        }
    }

    /**
     * A quality-filter slider moved (the undo step was recorded just before, see the
     * constructor). The incremental path rather than {@link #resyncToTree()}: this runs on
     * every tick of a drag, so the statistics are recomputed in the background and adopted
     * in the service's {@code onStatsRecomputed} callback.
     */
    private void onQualityFilterChanged() {
        session.settle();
        if (session.index() == null) return;
        session.recomputeQualityMask();
        refreshAncestorMask();
        // Recompute stats on background thread, then trigger preview update
        previewService.recomputeStats();
    }



    /**
     * Every edit ends here, or in {@link #resyncToTree()}: the edit is complete, so it is
     * settled as the pre-state for the next gate edit's undo step, and a pass is requested.
     */
    private void requestPreviewUpdate() {
        session.settle();
        previewService.setGateTree(session.tree());
        previewService.requestUpdate();
    }

    private void onPreviewUpdated() {
        // Already on FX thread (called from Platform.runLater in the constructor callback)
        treeView.refresh();
        updateStatusBar();
        refreshColorByRootCombo();
        umapButton.setDisable(!UMAP_ENABLED || session.index() == null);
        analysisButton.setDisable(!ANALYSIS_ENABLED || session.index() == null);

        // Push the new phenotyping to the UMAP if it is open. push() is a no-op when it
        // is not, so the common case costs one boolean check rather than the snapshot
        // build — which matters because this runs after every debounced gating pass,
        // i.e. continuously while a threshold slider is being dragged.
        if (UMAP_ENABLED && umapWindow.isShowing()) {
            PhenotypeSnapshot snap = buildSnapshot();
            if (snap != null) {
                umapWindow.push(snap);
            }
        }

        // Same idea for the Analysis window. Skipped (not merely a no-op push) when the
        // tree has no enabled root gate: PopulationStats.rows() would then be empty at
        // every scope, and pushing that would show a blank table with no explanation --
        // see AnalysisState.emptyMessage(), which is deliberately null whenever hasData()
        // is true and has nothing to say about "there are no gates". The window simply
        // keeps showing its last real report until a gate exists again.
        if (ANALYSIS_ENABLED && analysisWindow.isShowing() && hasEnabledRootGate()) {
            AnalysisSession.AnalysisInput input = buildAnalysisInput();
            if (input != null) {
                analysisWindow.push(input);
            }
        }
    }

    // --- UMAP handoff ---

    /**
     * The UMAP toolbar button plus the node the toolbar should actually contain.
     * <p>
     * The two differ only while the feature is held back, when the button is wrapped so
     * that its explanation stays reachable — see {@link #createUmapControl}.
     *
     * @param button the button itself, whose disabled state the pane keeps updating
     * @param slot   what to add to the toolbar, which may be a wrapper around {@code button}
     */
    record UmapControl(Button button, Node slot) {}

    /**
     * Build the UMAP toolbar control for the current value of {@link #UMAP_ENABLED}.
     * <p>
     * Extracted from the constructor so it is reachable from a test: {@code FlowPathPane}
     * itself needs a live {@link QuPathGUI} and cannot be instantiated in the suite, which
     * is precisely how a "disabled" button could regain a handler unnoticed.
     * <p>
     * When the feature is off the button is disabled <em>and</em> carries no action
     * handler. Either alone would do for the UI, but a disabled button with a live handler
     * is one {@code setDisable(false)} away from opening a window this release does not
     * ship, so both are removed. The label states the reason because a disabled JavaFX
     * node is not hit-tested and therefore never shows its own tooltip; the fuller
     * explanation is installed on an enabled wrapper, where hovering can still reach it.
     *
     * @param onOpen what pressing the button should do when the feature is enabled
     */
    static UmapControl createUmapControl(Runnable onOpen) {
        Button button = new Button(UMAP_ENABLED ? "Open UMAP" : "UMAP (coming soon)");
        // Disabled at construction either way: when the feature is on, onPreviewUpdated()
        // enables it once there are cells to embed.
        button.setDisable(true);

        if (UMAP_ENABLED) {
            // Styled as the primary action on this toolbar because it is the one step
            // that is not file I/O: everything else here saves or loads the gating,
            // this one takes it somewhere new.
            button.getStyleClass().add("fp-cta-button");
            button.setTooltip(new Tooltip(
                "Embed these cells in a UMAP, coloured by the phenotypes above (Ctrl+U).\n"
                + "Opens pre-configured on the markers your gates use.\n"
                + "Edits to the gate tree recolour the UMAP live — no recompute needed."));
            button.setOnAction(e -> onOpen.run());
            return new UmapControl(button, button);
        }

        StackPane wrapper = new StackPane(button);
        Tooltip.install(wrapper, new Tooltip(
            "UMAP exploration of the gated phenotypes is not part of this release.\n"
            + "It is planned for a future version."));
        return new UmapControl(button, wrapper);
    }

    /**
     * The Analysis toolbar button plus the node the toolbar should actually contain.
     * <p>
     * The two differ only while the feature is held back, when the button is wrapped so
     * that its explanation stays reachable — see {@link #createAnalysisControl}.
     *
     * @param button the button itself, whose disabled state the pane keeps updating
     * @param slot   what to add to the toolbar, which may be a wrapper around {@code button}
     */
    record AnalysisControl(Button button, Node slot) {}

    /**
     * Build the Analysis toolbar control for the current value of {@link #ANALYSIS_ENABLED}.
     * <p>
     * Extracted from the constructor for the same reason {@link #createUmapControl} was:
     * {@code FlowPathPane} needs a live {@link QuPathGUI} and cannot be instantiated in the
     * suite, so a "disabled" button could otherwise regain a handler unnoticed.
     * <p>
     * The same defences, in the same order. The button is disabled <em>and</em> carries no
     * action handler, because a disabled button with a live handler is one
     * {@code setDisable(false)} away from opening a window this release does not ship. The
     * label states the reason, because a disabled JavaFX node is not hit-tested and so
     * never shows its own tooltip; the fuller explanation goes on an enabled wrapper, where
     * hovering can still reach it.
     *
     * @param onOpen what pressing the button should do when the feature is enabled
     */
    static AnalysisControl createAnalysisControl(Runnable onOpen) {
        Button button = new Button(ANALYSIS_ENABLED ? "Analysis" : "Analysis (coming soon)");
        // Disabled at construction either way: when the feature is on, onPreviewUpdated()
        // enables it once there are cells to report on.
        button.setDisable(true);

        if (ANALYSIS_ENABLED) {
            button.setTooltip(new Tooltip(
                "Population counts, percentages and density for the current gating, live.\n"
                + "Three nested scopes when annotations are in use: per region, all regions, "
                + "whole slide."));
            button.setOnAction(e -> onOpen.run());
            return new AnalysisControl(button, button);
        }

        StackPane wrapper = new StackPane(button);
        Tooltip.install(wrapper, new Tooltip(
            "Population counts, percentages and density for the gated phenotypes are not\n"
            + "part of this release. They are planned for a future version."));
        return new AnalysisControl(button, wrapper);
    }

    /**
     * Open (or focus) the UMAP window on the current phenotyping.
     * <p>
     * Requires a gating pass to have completed: the snapshot carries per-cell labels, and
     * before the first pass there are none. Rather than opening an empty window, this
     * says so and leaves the user where they are.
     */
    private void openUmapWindow() {
        if (!UMAP_ENABLED) {
            // Unreachable through the UI while the flag is false. Kept so that a future
            // caller cannot open the window without also flipping the flag.
            return;
        }
        PhenotypeSnapshot snap = buildSnapshot();
        if (snap == null) {
            Dialogs.showWarningNotification("FlowPath",
                session.index() == null
                    ? "Load an image with cell detections first."
                    : "Waiting for the first gating pass to finish — try again in a moment.");
            return;
        }
        umapWindow.open(qupath, snap, getScene() != null ? getScene().getWindow() : null);
    }

    /**
     * Capture the current gating state for the UMAP view, or {@code null} if there is
     * nothing to capture yet.
     * <p>
     * Cheap by construction: the {@link CellIndex} and {@link MarkerStats} are passed by
     * reference (the UMAP view reads them, never mutates them) and the per-cell arrays
     * come straight off the last gating result. Nothing here re-walks the tree or
     * re-reads the hierarchy, which is what makes it safe to call on every preview
     * update.
     */
    private PhenotypeSnapshot buildSnapshot() {
        if (session.index() == null || session.stats() == null) return null;
        GatingEngine.AssignmentResult result = previewService.getLastResult();
        if (result == null) return null;

        String[] phenotypes = result.getPhenotypes();
        int[] colors = result.getColors();
        boolean[] excluded = result.getExcluded();
        // A result produced against an older index (image switched mid-pass) would
        // mislabel every cell. Drop it and wait for the next pass instead.
        if (phenotypes.length != session.index().size()) return null;

        var panel = PhenotypeSnapshot.collectGatedPanel(session.tree());
        return new PhenotypeSnapshot(
                session.index(),
                session.stats(),
                markerNames != null ? markerNames : List.of(),
                compartmentCapability,
                phenotypes,
                colors,
                excluded,
                panel.markers(),
                panel.selection(),
                countGates(session.tree().getRoots()),
                imageKey());
    }

    /** A stable identity for the active image, used to detect that a snapshot is stale. */
    private String imageKey() {
        ImageData<?> data = qupath.getImageData();
        if (data == null) return "";
        try {
            var server = data.getServer();
            if (server != null && server.getPath() != null) return server.getPath();
        } catch (Exception ignored) {
            // A server mid-teardown can throw; identity is still better than nothing.
        }
        return "image@" + System.identityHashCode(data);
    }

    // --- Analysis handoff ---

    /**
     * Open (or focus) the Analysis window on the current gating pass.
     * <p>
     * Mirrors {@link #openUmapWindow()}'s own refusals exactly, including the tone: a
     * missing prerequisite says so and leaves the user where they are, rather than opening
     * an empty or unexplained window. The one refusal UMAP does not need is the gate check
     * — {@code PopulationStats.rows()} is empty at every scope when the tree has no
     * enabled root gate, which would otherwise open straight onto a blank table with
     * nothing in {@code AnalysisState.emptyMessage()} to explain why.
     */
    private void openAnalysisWindow() {
        if (!ANALYSIS_ENABLED) {
            // Unreachable through the UI while the flag is false. Kept so that a future
            // caller cannot open the window without also flipping the flag.
            return;
        }
        if (session.index() == null) {
            Dialogs.showWarningNotification("FlowPath", "Load an image with cell detections first.");
            return;
        }
        if (previewService.getLastResult() == null) {
            Dialogs.showWarningNotification("FlowPath",
                "Waiting for the first gating pass to finish — try again in a moment.");
            return;
        }
        if (!hasEnabledRootGate()) {
            Dialogs.showWarningNotification("FlowPath",
                "Add at least one gate to see population statistics.");
            return;
        }
        AnalysisSession.AnalysisInput input = buildAnalysisInput();
        if (input == null) {
            Dialogs.showWarningNotification("FlowPath",
                "Waiting for the first gating pass to finish — try again in a moment.");
            return;
        }
        analysisWindow.open(qupath, input, getScene() != null ? getScene().getWindow() : null);
    }

    /** {@code true} when the tree has at least one enabled root gate. */
    private boolean hasEnabledRootGate() {
        for (GateNode root : session.tree().getRoots()) {
            if (root.isEnabled()) return true;
        }
        return false;
    }

    /**
     * Build the current gating pass as an {@link AnalysisSession.AnalysisInput}, or
     * {@code null} if there is nothing to report yet.
     * <p>
     * The tally comes straight off {@link LivePreviewService#getLastResult()} — the same
     * walk that just ran, never a second one — per {@code BranchTally}'s own invariant that
     * counting outside the walk would be a second gate predicate. Region names and areas
     * come from {@code session.regions()}, the same {@link RegionMask} instance the walk's
     * region indices were assigned from, so the two can never describe different region
     * sets.
     * <p>
     * <b>The tree is deep-copied, and the tally rebound onto the copy.</b> The window does
     * not merely read the input once: {@code AnalysisSession.stats()} re-walks
     * {@code input.tree()} on every scope, denominator or population change. Handing it
     * {@code session.tree()} itself therefore handed it a tree the user goes on editing, so
     * disabling the last enabled root left the window holding a tree that yields no rows
     * at all — and because {@code AnalysisState.hasData()} is derived from "a pass was
     * accepted" rather than from the row count, {@code emptyMessage()} stayed {@code null}
     * and the panel went blank with nothing to explain it, Export still enabled and
     * writing a header-only file. The push is deliberately skipped in that situation
     * (see {@link #onPreviewUpdated()}), which stops the window being *updated* into that
     * state but not from *drifting* into it, because the reference was shared.
     * <p>
     * This is {@code BranchTally}'s rebind rule applied one layer out: a tally must be
     * re-keyed whenever it crosses into a different copy of the tree, and
     * {@link BranchTally#rebindTo} throws rather than migrate half-way, so a structural
     * mismatch fails loudly here instead of silently reporting zeroes.
     */
    private AnalysisSession.AnalysisInput buildAnalysisInput() {
        if (session.index() == null || session.stats() == null) return null;
        GatingEngine.AssignmentResult result = previewService.getLastResult();
        if (result == null) return null;
        BranchTally tally = result.getTally();

        List<String> regionNames = session.regions() != null ? session.regions().regionNames() : List.of();
        // A pass computed just before session.regions() changed underneath it (a resync
        // ran between this preview's submit and its completion) would carry a tally sized
        // for the region set that pass actually walked, not the one session.regions() now
        // describes. Drop it and wait for the next pass rather than hand
        // AnalysisSession.AnalysisInput's constructor a mismatch it would only reject.
        if (tally.regionCount() != regionNames.size()) return null;

        double[] regionAreas = session.regions() != null
                ? regionAreasMm2(session.regions(), qupath.getImageData()) : null;

        // Freeze the tree this report describes, and move the tally's keys onto the frozen
        // copy in the same breath -- the tally is identity-keyed on Branch objects, and
        // deepCopy() builds fresh ones, so a copy without a rebind would answer 0 for every
        // branch by design.
        GateTree frozen = session.tree().deepCopy();
        BranchTally reboundTally;
        try {
            reboundTally = tally.rebindTo(session.tree().getRoots(), frozen.getRoots());
        } catch (IllegalArgumentException structureChanged) {
            // The live tree was edited between the walk finishing and this call, so the
            // tally and the copy describe different trees. Drop the pass and wait for the
            // next one, exactly as the region-count guard above does -- never publish a
            // half-migrated report.
            logger.debug("Gate tree changed under the Analysis push; waiting for the next pass",
                    structureChanged);
            return null;
        }

        return new AnalysisSession.AnalysisInput(frozen, session.index(), session.stats(), reboundTally,
                regionNames, regionAreas, currentImageName());
    }

    /**
     * Each region's area in mm², parallel to {@code regions.regionNames()}.
     * <p>
     * {@code ROI.getArea()} is in pixels²; {@code pixelWidthMicrons * pixelHeightMicrons}
     * converts to µm², and {@code / 1e6} to mm². An uncalibrated image, or the implicit
     * "whole image minus exclusions" region (which has no single ROI — see
     * {@link RegionMask#regionRois()}), leaves that entry {@link Double#NaN}: a density in
     * the wrong unit reads as an answer, so an unknown area must never be reported as zero
     * or as a raw pixel count.
     */
    private double[] regionAreasMm2(RegionMask regions, ImageData<?> imageData) {
        // The *effective* area, not the raw ROI area: RegionMask subtracts Ignore*
        // exclusions and resolves overlaps first-match-wins, so a region's raw ROI can
        // cover area whose cells this mask assigns elsewhere or drops entirely. Dividing a
        // count that respects those rules by an area that does not is how density came to
        // read low precisely on the slides where someone had carefully excluded artefact.
        double[] pixels = regions.effectiveAreasPixels();

        PixelCalibration cal = DetectionIngest.calibration(imageData);
        boolean calibrated = cal != null && cal.hasPixelSizeMicrons();
        double pw = calibrated ? cal.getPixelWidthMicrons() : Double.NaN;
        double ph = calibrated ? cal.getPixelHeightMicrons() : Double.NaN;
        boolean usable = calibrated && pw > 0 && ph > 0;

        double[] areas = new double[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            // An effective area of exactly 0 -- a region wholly covered by an earlier one,
            // or wholly excluded -- is reported as unknown rather than zero: it would
            // otherwise divide into an infinite density, and PopulationStats already treats
            // `areaMm2 <= 0` as unknown for the same reason.
            areas[i] = (!usable || Double.isNaN(pixels[i]) || pixels[i] <= 0)
                    ? Double.NaN
                    : pixels[i] * pw * ph / 1e6;
        }
        return areas;
    }

    /**
     * What the active image is called, defensively: a server mid-teardown, or no image at
     * all, is not a reason to refuse whatever is asking for a name.
     */
    private String currentImageName() {
        try {
            ImageData<?> data = qupath.getImageData();
            if (data != null && data.getServer() != null) {
                return data.getServer().getMetadata().getName();
            }
        } catch (Exception e) {
            logger.debug("No image name available", e);
        }
        return null;
    }

    private void refreshColorByRootCombo() {
        List<String> rootNames = new ArrayList<>();
        for (GateNode root : session.tree().getRoots()) {
            if (root.isEnabled()) {
                List<String> channels = root.getChannels();
                rootNames.add(channels.isEmpty() ? "Root" : channels.get(0));
            }
        }
        // Skip update if items haven't changed (avoids triggering selection listeners)
        if (rootNames.equals(colorByRootCombo.getItems())) return;

        int prev = colorByRootCombo.getSelectionModel().getSelectedIndex();
        colorByRootCombo.getItems().setAll(rootNames);
        if (rootNames.size() <= 1) {
            colorByRootCombo.setDisable(true);
            colorByRootCombo.getSelectionModel().clearSelection();
            // Reset to default color mode (no-op if already -1)
            previewService.setColorRootIndex(-1);
        } else {
            colorByRootCombo.setDisable(false);
            if (prev >= 0 && prev < rootNames.size()) {
                colorByRootCombo.getSelectionModel().select(prev);
            }
        }
    }

    private void updateStatusBar() {
        if (ingest.busy() == IngestCoordinator.Busy.LOADING) {
            statusBar.setText(String.format("Reading detections\u2026 | Gates: %d",
                countGates(session.tree().getRoots())));
            statusBar.setTooltip(null);
            return;
        }
        int total = session.index() != null ? session.index().size() : 0;
        int excluded = previewService.getLastExcludedCount();
        int gateCount = countGates(session.tree().getRoots());
        String roiInfo = session.tree().isRoiFilterEnabled() ? describeRegions() : "";
        statusBar.setText(String.format("Total: %,d cells | Excluded: %,d | Gates: %d%s%s",
            total, excluded, gateCount, roiInfo, ingestWarning()));
        // The full report goes in the tooltip rather than a dialog: an ingest finding is
        // context for reading the histograms, not an event that should block the user.
        statusBar.setTooltip(session.index() == null ? null : new Tooltip(ingestReport.describe()));
    }

    /**
     * The annotation filter, in one status-bar clause: how many regions are in use, how
     * many annotations are subtracting, and how many were skipped for enclosing no area.
     * <p>
     * The bar used to read a flat {@code "| ROI: annotations"} whatever was going on, so a
     * stray annotation widening the population, or a points annotation contributing
     * nothing, looked exactly like a correct setup. Every number here answers a question
     * the old text could not.
     */
    private String describeRegions() {
        if (session.regions() == null) {
            return " | ROI: no usable annotation";
        }
        StringBuilder sb = new StringBuilder(" | ROI: ");
        int regions = session.regions().regionNames().size();
        sb.append(regions).append(regions == 1 ? " region" : " regions");
        if (session.regions().excludeRegionCount() > 0) {
            sb.append(" \u2212 ").append(session.regions().excludeRegionCount()).append(" excluded");
        }
        if (session.regions().droppedNonArea() > 0) {
            sb.append(" (").append(session.regions().droppedNonArea())
              .append(" annotation(s) skipped: no area)");
        }
        return sb.toString();
    }

    /**
     * The ingest report, condensed to one line and shown only when something failed to
     * resolve. An empty histogram used to be the only symptom of an unresolved axis; this
     * is where the cause is now named. Deliberately not a modal — a channel dropped for
     * want of a measurement is extremely common on a partially quantified panel and a
     * dialog on every image load would train the user to dismiss it unread.
     * <p>
     * This subsumes the separate scale-mismatch warning that used to sit beside it: the
     * ScaleVerdict is one of the report's findings, and printing it twice in one status
     * line was the same duplication the whole ingest seam exists to remove.
     */
    private String ingestWarning() {
        if (session.index() == null) return "";
        String summary = ingestReport.summary();
        return summary.isEmpty() ? "" : " | \u26a0 " + summary;
    }

    private int countGates(List<GateNode> nodes) {
        int count = 0;
        for (GateNode node : nodes) {
            count++;
            for (Branch branch : node.getBranches()) {
                count += countGates(branch.getChildren());
            }
        }
        return count;
    }

    private void collectGateChannels(List<GateNode> nodes, Set<String> missing, Set<String> available) {
        for (GateNode node : nodes) {
            for (String ch : node.getChannels()) {
                if (ch != null && !available.contains(ch)) missing.add(ch);
            }
            for (Branch branch : node.getBranches()) {
                collectGateChannels(branch.getChildren(), missing, available);
            }
        }
    }

    // --- IO ---

    private void saveTree() {
        File file = Dialogs.promptToSaveFile("Save FlowPath", null, "flowpath.json", "JSON", ".json");
        if (file == null) return;
        try {
            FlowPathSerializer.save(session.tree(), file, currentProvenance());
            Dialogs.showInfoNotification("FlowPath", "Saved to " + file.getName());
        } catch (Exception ex) {
            Dialogs.showErrorMessage("Save Error", ex.getMessage());
        }
    }

    /**
     * What this gate tree was drawn against, for the saved file's {@code meta} block.
     * <p>
     * Read defensively: a tree can be saved before any image is open, or with the index
     * still null, and neither is a reason to refuse the save. Unknown fields are simply
     * not recorded -- see {@link FlowPathSerializer.Provenance}.
     */
    private FlowPathSerializer.Provenance currentProvenance() {
        String imageName = currentImageName();
        int cells = session.index() != null ? session.index().getSize() : -1;
        List<String> channels = session.index() != null
                ? List.of(session.index().getMarkerNames())
                : List.of();
        return new FlowPathSerializer.Provenance(imageName, cells, channels);
    }

    private void loadTree() {
        File file = Dialogs.promptForFile("Load FlowPath", null, "JSON", ".json");
        if (file == null) return;
        try {
            session.replaceTree(FlowPathSerializer.load(file));
            resyncToTree();

            // Check for missing markers and warn user
            if (markerNames != null && !markerNames.isEmpty()) {
                Set<String> available = new HashSet<>(markerNames);
                Set<String> missing = new LinkedHashSet<>();
                collectGateChannels(session.tree().getRoots(), missing, available);
                if (!missing.isEmpty()) {
                    Dialogs.showWarningNotification("FlowPath",
                        "Gate channels not found in current image: " + String.join(", ", missing));
                }
            }

            Dialogs.showInfoNotification("FlowPath", "Loaded from " + file.getName());
        } catch (Exception ex) {
            Dialogs.showErrorMessage("Load Error", ex.getMessage());
        }
    }

    /**
     * Snapshot the tree, cells, statistics, ROI mask and regions at this moment and hand them
     * to {@link #csvExport}, which gates and writes them on {@link #backgroundExecutor}. The
     * snapshot -- not the live session -- is what reaches the file, so a gate edited while the
     * export is running never leaks into it. A second call while one is already running (a
     * stray Ctrl+E; the button is disabled but the accelerator is not tied to it) is a no-op.
     */
    private void exportCsv() {
        if (csvExport.exporting()) return;
        if (session.index() == null || session.stats() == null || session.tree().getRoots().isEmpty()) {
            Dialogs.showWarningNotification("FlowPath", "No gates defined or no cells loaded.");
            return;
        }

        File file = Dialogs.promptToSaveFile("Export Phenotypes", null, "gate_pheno.csv", "CSV", ".csv");
        if (file == null) return;

        CsvExportJob.Snapshot snapshot = CsvExportJob.Snapshot.of(
                file, session.tree(), session.index(), session.stats(), session.roiMask(), session.regions());
        csvExport.export(snapshot);
        updateExportControlsDisabled();
    }

    // --- Context menu ---

    private void showTreeContextMenu(double screenX, double screenY) {
        GateNode selected = getSelectedGateNode();
        ContextMenu menu = new ContextMenu();

        if (selected != null) {
            // Add child gate to each branch
            for (int i = 0; i < selected.getBranches().size(); i++) {
                Branch branch = selected.getBranches().get(i);
                int branchIdx = i;
                MenuItem addItem = new MenuItem("Add child to '" + branch.getName() + "'");
                addItem.setOnAction(e -> addChildGate(branchIdx));
                menu.getItems().add(addItem);
            }
            menu.getItems().add(new SeparatorMenuItem());

            MenuItem dupItem = new MenuItem("Duplicate (Ctrl+D)");
            dupItem.setOnAction(e -> duplicateSelectedGate());
            menu.getItems().add(dupItem);

            MenuItem removeItem = new MenuItem("Remove (Del)");
            removeItem.setOnAction(e -> removeSelectedGate());
            menu.getItems().add(removeItem);
        } else {
            MenuItem addRoot = new MenuItem("Add Root Gate...");
            addRoot.setOnAction(e -> addRootGate());
            menu.getItems().add(addRoot);
        }

        menu.show(treeView, screenX, screenY);
    }

    private void duplicateSelectedGate() {
        GateNode selected = getSelectedGateNode();
        if (selected == null) return;

        pushUndo();
        GateNode copy = selected.deepCopy();

        // Insert as sibling: find parent and add to the same branch
        if (session.tree().getRoots().contains(selected)) {
            session.tree().addRoot(copy);
        } else {
            // Search for the branch containing the selected gate
            for (GateNode root : session.tree().getRoots()) {
                if (insertSiblingCopy(root, selected, copy)) break;
            }
        }
        rebuildTreeView();
        requestPreviewUpdate();
    }

    private boolean insertSiblingCopy(GateNode node, GateNode target, GateNode copy) {
        for (Branch branch : node.getBranches()) {
            if (branch.getChildren().contains(target)) {
                branch.getChildren().add(copy);
                return true;
            }
            for (GateNode child : branch.getChildren()) {
                if (insertSiblingCopy(child, target, copy)) return true;
            }
        }
        return false;
    }

    // --- Undo / Redo ---

    private void pushUndo() {
        session.recordEdit();
    }

    /**
     * Record a gate edit the editor has already written (it writes, then fires
     * {@code onNodeChanged}), from the tree as it stood before, coalesced with the rest of
     * its drag.
     */
    private void pushUndoCoalesced() {
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
    }

    private void undo() {
        if (session.undo()) resyncToTree();
    }

    private void redo() {
        if (session.redo()) resyncToTree();
    }

    /**
     * Bring every widget and the gating pass in line with the session's current tree and
     * index, synchronously: the path for a load, an undo or redo and an ROI toggle. An image
     * switch and a hierarchy change take the same resync through {@link #ingest}, which
     * computes its heavy half in the background and renders here through {@link #render}.
     * <p>
     * These three stay synchronous on purpose: each is a single user action whose undo
     * baseline, legacy migration and next gating pass must see the resync completed before the
     * next edit, and the tests pinning undo (see {@code GatingSessionResyncTest}) rely on it.
     * <p>
     * {@link GatingSession#resync} recomputes the ROI mask, quality mask and statistics from
     * this tree's own filters, migrates a legacy z-score tree through those statistics, and
     * requests the gating pass (see {@link #requestGatingPass}). This method then renders the
     * result (see {@link #render}): quality-filter panel, ROI checkbox (events suppressed, so rendering is not
     * mistaken for a toggle), editor masks and statistics, tree view, the selected gate if it
     * is still in the tree, and the status bar.
     * <p>
     * Undo used to redraw only the panel and the tree, so the ROI checkbox, the cached masks
     * and the statistics went on describing the tree before the undo. Add a step here, never
     * at a caller.
     */
    private void resyncToTree() {
        render(session.resync(this::annotationsForRoiFilter), false);
    }

    /**
     * Render a resync's result — the synchronous one above, or one {@link #ingest} finished in
     * the background.
     * <p>
     * The editor is rebuilt only when it has to be: the cells changed ({@code newIndex}), a
     * migration rewrote gates in place, or the selected gate is not the one it shows (an undo
     * or a load swaps in fresh {@code GateNode}s). An annotation edit or a filter toggle keeps
     * it, and its plots redraw through the masks and statistics set below. Rebuilding on every
     * resync threw away a polygon half-drawn on the scatter plot whenever an annotation moved.
     */
    private void render(Optional<GatingSession.MigrationNotice> notice, boolean newIndex) {
        GateTree tree = session.tree();

        qualityFilterPane.setFilter(tree.getQualityFilter());
        suppressRoiFilterEvents = true;
        try {
            roiFilterCheckBox.setSelected(tree.isRoiFilterEnabled());
        } finally {
            suppressRoiFilterEvents = false;
        }

        editorPane.setCellIndex(session.index());
        editorPane.setRoiMask(session.roiMask());
        editorPane.setMarkerStats(session.stats());

        // Undo and load swap in a tree of fresh GateNode objects, so the gate the editor was
        // showing may no longer be in it; an image switch or a filter toggle keeps it.
        suppressTreeSelection = true;
        try {
            rebuildTreeView();
            currentNode = EditorRebuild.surviving(currentNode, tree);
            if (currentNode != null) selectNodeInTree(currentNode);
        } finally {
            suppressTreeSelection = false;
        }
        editorPane.setAncestorMask(currentNode != null ? computeAncestorMask(currentNode) : null);
        if (EditorRebuild.needed(newIndex, notice.isPresent(), editorPane.getGateNode(), currentNode)) {
            editorPane.setGateNode(currentNode);
        }

        updateStatusBar();
        notice.ifPresent(this::showMigrationNotice);
    }

    /** Hand a resync's result to the live preview and request the pass. */
    private void requestGatingPass(GatingSession.PassInput input) {
        RegionMask regions = input.regions();
        previewService.setCellIndex(input.index());
        previewService.setMarkerStats(input.stats());
        previewService.setRoiMask(input.roiMask());
        previewService.setRegions(regions != null ? regions.regionOf() : null,
                regions != null ? regions.regionNames().size() : 0);
        previewService.setGateTree(input.tree());
        previewService.requestUpdate();
    }

    private void showMigrationNotice(GatingSession.MigrationNotice notice) {
        if (notice.warning()) {
            Dialogs.showWarningNotification("FlowPath", notice.message());
        } else {
            Dialogs.showInfoNotification("FlowPath", notice.message());
        }
    }

    /**
     * Clean up resources when the window is closed.
     * <p>
     * Closing the ingest coordinator (which detaches its hierarchy listener) matters as much
     * as stopping the executors:
     * the extension builds a fresh pane every time the window is reopened
     * ({@code FlowPathExtension.showGateTreeWindow}), so a listener left attached
     * keeps a discarded pane — and its whole {@code CellIndex} — reachable, and
     * keeps recomputing ROI masks for a window that is gone.
     * <p>
     * {@code analysisWindow.dispose()}, not {@code close()}: THIS {@code FlowPathPane} — and
     * the {@code AnalysisWindow} it owns — is what is going away here, a fresh pair is built
     * the next time the window reopens, so there is no future {@code open()} on this instance
     * left to benefit from {@code close()}'s pane-survival behaviour. Keeping the pane alive
     * past this point would only be a leak — see {@code AnalysisWindow.dispose()}'s own javadoc.
     */
    public void shutdown() {
        ingest.close();
        umapWindow.close();
        analysisWindow.dispose();
        previewService.shutdown();
        // shutdownNow: a read still queued is for a pane that is gone. Its result could not
        // land anyway -- the coordinator is closed -- so there is nothing to wait for.
        backgroundExecutor.shutdownNow();
    }
}
