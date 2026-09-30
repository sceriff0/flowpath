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
import qupath.ext.flowpath.batch.BatchRunner;
import qupath.ext.flowpath.batch.BatchSlide;
import qupath.ext.flowpath.batch.CohortEvidence;
import qupath.ext.flowpath.batch.FlowPathBatch;
import qupath.ext.flowpath.ui.cohort.CohortGridModel;
import qupath.ext.flowpath.ui.cohort.CohortGridPane;
import qupath.ext.flowpath.ui.cohort.CohortWindow;
import qupath.ext.flowpath.ui.cohort.ReviewKey;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.BoundaryHotspot;
import qupath.ext.flowpath.cohort.CohortCurvesCache;
import qupath.ext.flowpath.cohort.CohortExclusions;
import qupath.ext.flowpath.cohort.CohortIdentity;
import qupath.ext.flowpath.cohort.CohortPrefs;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.CohortState;
import qupath.ext.flowpath.cohort.EvidenceCrop;
import qupath.ext.flowpath.cohort.ReviewGroup;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.cohort.SlideSample;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.LivePreviewService;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.io.AlignmentCacheFile;
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
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.RegionMask;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.ui.editor.EditorAlignment;
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
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.util.List;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
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
    /** One gate drag in {@link #treeView}, shared by every recycled cell; see {@link FlowPathCell}. */
    private final GateDragCoordinator dragCoordinator;
    private final GateEditorPane editorPane;
    private final QualityFilterPane qualityFilterPane;
    private final CheckBox roiFilterCheckBox;
    private final CheckBox syncViewerChannelsToggle;
    private final LivePreviewService previewService;
    private final Label statusBar;
    private final ComboBox<String> colorByRootCombo;
    /** Which root {@link #colorByRootCombo} is on, as a value; see {@link ColorByRootSelection}. */
    private final ColorByRootSelection colorByRoot = new ColorByRootSelection();
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

    /**
     * Where the open slide's applied values come from: {@link #cohort}'s one stable lookup,
     * assigned in the constructor. It reads the cohort's current alignment model on every pass
     * and answers null — every gate on its reference number — until one is computed.
     */
    private AlignmentLookup alignments = AlignmentLookup.NONE;

    /**
     * The project id of the slide whose cells {@link GatingSession#index()} holds, taken with the
     * index when it lands (see {@link IngestHost#ingested}) rather than read from the viewer. The
     * viewer switches images before the read is even submitted, so reading the id there let a
     * Ctrl+E — or a gating pass — pair the new slide's id with the previous slide's cells.
     */
    private String indexSlideId;

    /**
     * The project id of the image open in the viewer, which a running batch asks from its
     * background thread before every write-back ({@code BatchRunner.Settings.isOpen}). Written
     * only on the FX thread, from the viewer's image property; volatile so the run never has to
     * touch {@code qupath.getImageData()} off the FX thread. Unlike {@link #indexSlideId}, this is
     * the viewer's image whether or not its cells were read: its file is the one QuPath saves.
     */
    private volatile String viewerSlideId;

    /** The project's slides, their samples, the alignment model and the review; see {@link #refreshCohort()}. */
    private final CohortSession cohort = new CohortSession();
    /** The All slides view's last answer, kept while its key holds (see {@link CohortCurvesCache}). */
    private final CohortCurvesCache cohortCurves = new CohortCurvesCache();

    /** Samples the project's slides and scores them on {@link #backgroundExecutor}. */
    private final CohortCoordinator cohortCoordinator;

    /** The slide ids and sample size the running or last sampling run was started for. */
    private String lastSampledKey;

    /** The side panel's cohort status (spec 2026-09-30 §3.1). */
    private final CohortCard cohortCard = new CohortCard();
    /** The Cohort window's body; kept across close and reopen, like the Analysis pane. */
    private final CohortGridPane cohortGrid = new CohortGridPane();
    private final CohortWindow cohortWindow = new CohortWindow();
    /** The grid's "Only ⚠" filter. */
    private boolean onlyLooks;
    /** The grid's selected cell, by value; may name a cell with no review item (✓, ↷, ✎, ⊘). */
    private ReviewItem.Key gridSelection;
    /** The review visual on the viewer; paints only (see {@link BoundaryOverlay}). */
    private final BoundaryOverlay boundaryOverlay = new BoundaryOverlay();
    /** The viewer {@link #boundaryOverlay} was added to, so {@link #shutdown()} removes it from that one. */
    private QuPathViewer overlayViewer;
    /** An item opened on another slide, focused once that slide's cells have landed. */
    private ReviewItem.Key pendingFocus;
    /** A non-item grid cell's [Adjust] on another slide, shown once that slide's cells have landed. */
    private ReviewItem.Key pendingAdjust;
    /**
     * The item being answered (by value; its gate is found in the live tree on every use), its
     * baseline and its undo mark; what a drag or an answer on it does lives there, toolkit-free.
     */
    private final ReviewFlow review;
    /**
     * What the editor's cohort seam last rendered against: availability, the model's reference
     * and whether correction is off. A rescore that changes any of them re-renders even when no
     * alignment number changed (see {@link CohortHost#scored}).
     */
    private List<Object> renderedCohortContext;

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

    /**
     * The evidence crops' own single thread (spec §6): an image read must not queue behind gating
     * on {@link #backgroundExecutor}, nor a gating pass behind a slow read. Shut down in
     * {@link #shutdown()}.
     */
    private final ExecutorService cropExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "flowpath-crops");
        t.setDaemon(true);
        return t;
    });

    /** The selected review item's crop, the next few prefetched, an LRU of recent ones. */
    private final EvidenceCropCoordinator crops = new EvidenceCropCoordinator(cropExecutor, Platform::runLater,
            cohort::model, this::cropJob);

    /** The (item, applied values) whose crop the pane shows or awaits; null when none. */
    private EvidenceCropCoordinator.CacheKey shownCrop;

    /** Reads the open image's detections in the background, and again whenever they change. */
    private final IngestCoordinator ingest;

    /**
     * Recomputes the masks and statistics in the background for the edits that change them —
     * the annotation-filter toggle, an undo or redo across a filter change, and a load. See
     * {@link #resyncToTree()}.
     */
    private final DerivationCoordinator derivations;

    /** Gates the export snapshot and writes the CSV in the background. */
    private final CsvExportCoordinator csvExport;

    /** "Run on all slides": one slide per task on {@link #backgroundExecutor}, cancellable. */
    private final BatchRunCoordinator batchRun;

    private final Button addRootBtn;
    private final Button exportBtn;
    /** "Run on all slides…", or "Cancel run" while {@link #batchRun} is running; see {@link #updateBusyControls()}. */
    private final Button runAllButton;

    /** "New project from MIRAGE…": one patient per task on {@link #backgroundExecutor}, cancellable. */
    private final MirageImportCoordinator mirageImport;
    /** "New project from MIRAGE…", or "Cancel import (i/n)" while {@link #mirageImport} runs. */
    private final Button importButton;
    /** The patients the running import's preview listed but will not add, for its summary. */
    private int importSkipped;
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
        this.review = new ReviewFlow(session);

        // --- Left side: TreeView + Quality Filter ---
        // A drop is a tree edit like any other: recorded as one undo step before it is applied,
        // and refused while a derivation is in flight for the same reason the editor is greyed
        // out (see BusyState#editingBlocked).
        this.dragCoordinator = new GateDragCoordinator(session::tree,
                () -> busyState().editingBlocked(), this::pushUndo, this::onGateMoved);
        treeView = new TreeView<>();
        treeView.setCellFactory(tv -> {
            FlowPathCell cell = new FlowPathCell();
            cell.setOnEnabledToggled(this::onGateEnabledToggled);
            cell.setDragCoordinator(dragCoordinator);
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
        // value the drag started from. A drag is coalesced into one step; Reset is a
        // discrete step that ends any drag burst rather than folding into it.
        qualityFilterPane.setOnBeforeFilterChange(kind -> {
            switch (kind) {
                case DRAG -> session.recordEditCoalesced(GatingSession.EditSource.QUALITY_FILTER);
                case RESET -> session.recordEdit();
            }
        });
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
                colorByRoot.selected(idx.intValue());
            }
        });

        HBox treeToolbar = new HBox(4, addRootBtn, colorByRootCombo);
        HBox.setHgrow(addRootBtn, Priority.ALWAYS);

        HBox togglesRow = new HBox(8, roiFilterCheckBox, syncViewerChannelsToggle);
        VBox leftPane = new VBox(4, treeView, treeToolbar, togglesRow, cohortCard, qualityFilterPane);
        VBox.setVgrow(treeView, Priority.ALWAYS);
        leftPane.setPadding(new Insets(4));
        leftPane.setPrefWidth(280);

        // --- Right side: Gate Editor ---
        editorPane = new GateEditorPane();
        editorPane.setOnNodeChanged(node -> onGateNodeChanged());
        editorPane.setOnNodeNormalised(node -> onGateNodeNormalised());
        editorPane.setOnDiscreteEdit(node -> onGateDiscreteEdit());
        editorPane.setOnAddToBranch(this::addChildGate);
        editorPane.setOnRemoveGate(this::removeSelectedGate);
        editorPane.setOnReplaceGate(this::replaceGateNode);
        editorPane.setOnClearSlideSetting(this::clearSlideSetting);

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
        // The card's button: it sits in the cohort card, not the toolbar.
        runAllButton = cohortCard.runAllButton();
        runAllButton.setOnAction(e -> runOnAllSlides());
        runAllButton.setTooltip(new Tooltip("Gate every slide in the project with its own applied thresholds; "
                + "write the population table, one phenotype CSV per slide and gating_manifest.csv"));
        // Shown only in a project with a cohort; updateBusyControls decides, as for every control.
        runAllButton.setVisible(false);
        runAllButton.setManaged(false);
        importButton = new Button("New project from MIRAGE…");
        importButton.setOnAction(e -> newProjectFromMirage());
        importButton.setTooltip(new Tooltip("Create a QuPath project from a MIRAGE output folder: one image per "
                + "patient, its cells imported and saved — or add new patients to an existing project"));

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
            exportBtn, importButton, toolbarSpacer, analysisSlot, umapSlot);
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
            } else if (onReviewKey(e)) {
                e.consume();
            }
        });

        // Style — follows the active QuPath theme's base colour instead of forcing dark.
        getStyleClass().add("fp-panel");

        // Detections are read on backgroundExecutor and applied on the FX thread. FlowPath's own
        // classification writes fire hierarchy events too; the coordinator ignores those.
        ingest = new IngestCoordinator(session, backgroundExecutor, this::scheduleOnBackground,
                Platform::runLater, previewService::isFiringHierarchyEvent, new IngestHost());

        // The same executor: a derivation and a re-ingest queue behind each other rather than
        // racing for the session's inputs.
        derivations = new DerivationCoordinator(session, backgroundExecutor, Platform::runLater,
                new DerivationHost());

        // A CSV export's snapshot is gated and written on the same single-threaded executor,
        // so it never races an ingest for the session's inputs.
        csvExport = new CsvExportCoordinator(backgroundExecutor, Platform::runLater, new CsvExportHost());

        // The same executor, one slide per task: a derivation, a re-ingest or a sampling step queued
        // meanwhile runs between two slides rather than after the whole project.
        batchRun = new BatchRunCoordinator(backgroundExecutor, Platform::runLater, new BatchHost());

        // The same executor, one patient per task, for the same reason as the batch run.
        mirageImport = new MirageImportCoordinator(backgroundExecutor, Platform::runLater, new MirageImportHost());

        // The same executor again, one slide per task: a pass, a derivation or an export queued
        // meanwhile runs between two slides rather than after the whole project.
        cohortCoordinator = new CohortCoordinator(cohort, backgroundExecutor, Platform::runLater, new CohortHost());

        // A plain assignment, once: the lookup is one stable instance that reads the cohort's
        // current model on every pass, so nothing has to hand the pass a new one later.
        alignments = cohort.lookup();
        applySlideContext();
        editorPane.setEditorAlignment(editorAlignment);
        // All slides (U1): each sample's values for the shown gate, aligned through the same
        // correctionFor the pass gates with; read live, like the alignment seam above, and
        // memoised, because the editor asks on every refresh (every resync, filter tick, pass).
        editorPane.setCohortValues(g -> cohortCurves.get(session.tree(), g, cohort.samples(), cohort.model(),
                alignments, currentSlideId()));
        // Reviewing by gate (spec §6): the slides flagged on the shown gate, asked by value —
        // the enabled gate's (rootIndex, gatePath) — so two same-channel roots never share flags.
        editorPane.setFlaggedSlides(g -> {
            for (GateWalk.Entry e : GateWalk.enabled(session.tree())) {
                if (e.gate() == g) return cohort.flaggedSlides(e.rootIndex(), e.gatePath());
            }
            return Set.of();
        });
        editorPane.setViewMode(cohort.viewMode());
        editorPane.setOnViewModeChanged(mode -> {
            cohort.setViewMode(mode);
            showSlideSetting();
        });
        // An edit outside the open item (a filter drag, the ROI toggle, …) closed it.
        review.setOnEnded(() -> {
            hideBoundaryOverlay();
            showSlideSetting();
        });

        cohortCard.setOnOpen(this::openCohortWindow);
        cohortGrid.setOnCellChosen(this::chooseCell);
        cohortGrid.setOnLooksRight(this::answerEnter);
        cohortGrid.setOnSkip(this::answerSkip);
        cohortGrid.setOnAdjust(this::adjustSelectedCell);
        cohortGrid.setOnUseCohortValue(this::clearSelectedSlideSetting);
        cohortGrid.setOnColumnLooksRight(key -> {
            cohort.selectGroup(key);
            answerGroup();
        });
        cohortGrid.setOnMakeReference(this::chooseReference);
        cohortGrid.setOnUseSuggested(() -> {
            String id = cohort.suggestedReferenceId();
            if (id != null) chooseReference(id);
        });
        cohortGrid.setOnToggleExcluded(this::toggleExcluded);
        cohortGrid.setOnOnlyLooksChanged(b -> {
            onlyLooks = b;
            renderCohort();
        });
        cohortGrid.setOnKey(this::onCohortKey);
        // The sample size is part of the sampling key, so the refresh re-samples.
        cohortGrid.setOnSampleSizeChanged(n -> {
            CohortPrefs.setSampledCellsPerSlide(CohortPrefs.node(), n);
            refreshCohort();
        });

        // Initialize from current image
        Platform.runLater(this::initializeFromImage);

        // Listen for image changes
        viewerSlideId = slideIdOf(qupath.getImageData());
        qupath.imageDataProperty().addListener((obs, oldImg, newImg) -> {
            // At once, not in the runLater: a batch run asks it between two slides.
            viewerSlideId = slideIdOf(newImg);
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
            indexSlideId = null;
            // The outlines are the previous slide's cells.
            hideBoundaryOverlay();
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
            refreshCohort();
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
            indexSlideId = slideIdOf(imageData);
            refreshCohort();
        }

        @Override
        public void resynced(Optional<GatingSession.MigrationNotice> notice, boolean newIndex) {
            render(notice, newIndex);
            // After the resync, so the index, masks and statistics describe the slide just opened.
            if (pendingFocus != null && pendingFocus.slideId().equals(currentSlideId())) {
                ReviewItem.Key key = pendingFocus;
                pendingFocus = null;
                focusReviewItem(key);
            }
            if (pendingAdjust != null && pendingAdjust.slideId().equals(currentSlideId())) {
                ReviewItem.Key key = pendingAdjust;
                pendingAdjust = null;
                // Only while it is still the grid's selection: the user may have moved on.
                if (key.equals(gridSelection)) showGateOnSlide(key);
            }
        }

        @Override
        public void busyChanged(IngestCoordinator.Busy state) {
            updateBusyControls();
        }

        @Override
        public void failed(Throwable error) {
            logger.error("Reading the image's detections failed", error);
            Dialogs.showErrorNotification("FlowPath",
                    "Could not read the detections: " + ErrorMessages.describe(error));
        }
    }

    /** What {@link #derivations} asks of this pane. Every call arrives on the FX thread. */
    private final class DerivationHost implements DerivationCoordinator.Host {

        @Override
        public List<PathObject> annotations() {
            return annotationsForRoiFilter();
        }

        @Override
        public void resynced(Optional<GatingSession.MigrationNotice> notice) {
            render(notice, false);
        }

        @Override
        public void busyChanged(boolean deriving) {
            updateBusyControls();
        }

        /**
         * Nothing was adopted, so the session still holds the masks and statistics it had. The
         * tree edit that asked for the derivation stands — it was taken on the FX thread before
         * the work was submitted — so the widgets are rendered against what the session actually
         * holds rather than left showing the tree before it.
         */
        @Override
        public void failed(Throwable error) {
            logger.error("Recomputing the masks and statistics failed", error);
            render(Optional.empty(), false);
            Dialogs.showErrorNotification("FlowPath",
                    "Could not recompute the statistics: " + ErrorMessages.describe(error));
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
            Dialogs.showErrorMessage("Export Error", ErrorMessages.describe(error));
        }
    }

    /** What {@link #batchRun} asks of this pane. Every call arrives on the FX thread. */
    private final class BatchHost implements BatchRunCoordinator.Host {
        @Override
        public void progress(int done, int total, String name) {
            cohort.batchProgress(done, total, name);
            updateStatusBar();
        }

        @Override
        public void finished(BatchRunCoordinator.Outcome outcome) {
            cohort.batchFinished();
            updateBusyControls();
            Dialogs.showPlainMessage("Run on all slides", FlowPathBatch.summary(outcome.outputDir(),
                    outcome.runs(), outcome.total(), outcome.cancelled(), id -> id.equals(viewerSlideId)));
        }

        @Override
        public void failed(Throwable error) {
            logger.error("Run on all slides failed", error);
            cohort.batchFinished();
            updateBusyControls();
            Dialogs.showErrorMessage("Run on all slides", ErrorMessages.describe(error));
        }
    }

    /** What {@link #mirageImport} asks of this pane. Every call arrives on the FX thread. */
    private final class MirageImportHost implements MirageImportCoordinator.Host {
        @Override
        public void progress(int done, int total, String id) {
            updateImportButton(busyState(), done + "/" + total);
        }

        /**
         * Open what was built. The project instance the import wrote is handed to QuPath as it is,
         * so only one in-memory {@code Project} ever writes its {@code project.qpproj}.
         */
        @Override
        public void finished(MirageImportCoordinator.Outcome outcome) {
            updateBusyControls();
            if (!outcome.added().isEmpty() || qupath.getProject() == null) {
                qupath.setProject(outcome.project());
            }
            Dialogs.showPlainMessage("New project from MIRAGE", MirageImportCoordinator.summary(outcome, importSkipped));
        }

        @Override
        public void failed(Throwable error) {
            logger.error("New project from MIRAGE failed", error);
            updateBusyControls();
            Dialogs.showErrorMessage("New project from MIRAGE", ErrorMessages.describe(error));
        }
    }

    /** What {@link #cohortCoordinator} asks of this pane. Every call arrives on the FX thread. */
    private final class CohortHost implements CohortCoordinator.Host {

        @Override
        public void sampled(CohortSampler.Outcome outcome) {
            cohortCoordinator.rescore(session.tree());
            updateBusyControls();
        }

        @Override
        public void samplingFinished() {
            updateBusyControls();
        }

        /**
         * The landmarks a finished run found are derived data: written in the background to the
         * cache file captured when that run started (see {@link CohortCoordinator#start}).
         */
        @Override
        public void cacheSettled(Path file, AlignmentModel.Cache cache, int sampledCellsPerSlide) {
            backgroundExecutor.execute(() -> {
                try {
                    AlignmentCacheFile.write(file, cache, sampledCellsPerSlide);
                } catch (Exception | Error ex) {
                    // Error too: an OutOfMemoryError serialising a large cache must be logged, not
                    // left to kill the shared executor's task silently.
                    logger.warn("Could not write the alignment cache {}", file, ex);
                }
            });
        }

        /**
         * A pass is requested only when an alignment changed: every pass rescores (see
         * {@link #onPreviewUpdated()}), so requesting one on every rescore would never stop.
         */
        @Override
        public void scored(boolean alignmentsChanged) {
            // A new reference's model, or correction switching off or on, changes what the
            // editor draws even when no alignment number changed: render, the one path that
            // brings the editor, the Correct staining switch and the banner in line.
            if (alignmentsChanged || !cohortContext().equals(renderedCohortContext)) {
                render(Optional.empty(), false);
                requestPreviewUpdate();
            }
            updateBusyControls();
            onCohortScored();
        }
    }

    /** A rescore was adopted. The Cohort card and window and the editor's cohort view hook in here. */
    private void onCohortScored() {
        // New samples or alignments: the All slides view re-reads them (a refresh, not a rebuild).
        if (cohort.viewMode() == CohortSession.ViewMode.ALL_SLIDES) editorPane.refreshEditor();
        renderCohort();
    }

    /** What {@link #renderedCohortContext} holds; see there. */
    private List<Object> cohortContext() {
        CohortState state = cohort.state();
        return Arrays.asList(state.available(), cohort.model().referenceSlideId(), state.correctionDisabled());
    }

    /**
     * Bring {@link #cohort} in line with the project: its slides and which of them are excluded
     * ({@link CohortExclusions}, read before any sampling starts), a sampling run of the included
     * slides when the slides or the sample size changed (or a slide was included again, see
     * {@link #toggleExcluded}), and a rescore. It never
     * chooses a reference: a tree has none until the user confirms one ({@link #chooseReference}).
     * Called when an ingest lands or clears, before its resync requests the pass, and after an
     * exclusion toggle or a sample-size change.
     */
    private void refreshCohort() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            cohort.setProject(null, List.of());
            cohortCoordinator.cancel();
            lastSampledKey = null;
            updateBusyControls();
            return;
        }
        // Entry ids restart in every project, so the project's folder is part of every key: two
        // projects' "1".."N" must never share samples, alignments or a cache file.
        Path projectDir = ProjectSlides.projectDir(project);
        List<CohortSession.SlideRef> refs = ProjectSlides.refs(project);
        cohort.setProject(projectDir.toString(), refs);
        Set<String> excluded = CohortExclusions.of(project).excluded();
        cohort.setExcluded(excluded);
        if (refs.size() < 2) {
            cohortCoordinator.cancel();
            lastSampledKey = null;
            updateBusyControls();
            return;
        }
        int cells = CohortPrefs.sampledCellsPerSlide(CohortPrefs.node());
        // The exclusions are deliberately NOT part of the key: excluding a slide only drops its
        // sample and review (CohortSession.setExcluded) and rescores, keeping every other slide's
        // sample. Including one again forces a re-sample instead (toggleExcluded resets the key).
        String key = projectDir + "|" + refs.stream().map(CohortSession.SlideRef::id).toList() + "|" + cells;
        if (!key.equals(lastSampledKey)) {
            lastSampledKey = key;
            Path cacheFile = AlignmentCacheFile.pathFor(projectDir);
            cohort.setCache(AlignmentCacheFile.read(cacheFile));
            cohortCoordinator.start(ProjectSlides.sources(project).stream()
                    .filter(source -> !excluded.contains(source.id())).toList(), session.tree(), cells, cacheFile);
        }
        cohortCoordinator.rescore(session.tree());
        updateBusyControls();
    }

    /**
     * The open slide's alignment per gate axis, for the editor's display seam. Read live on every
     * call — the tree, the open slide and the cohort's model are the session's current ones — and
     * answered by {@link TreeResolver#correctionFor}, the same rule the live pass resolves with,
     * so the editor cannot draw an axis as corrected that the pass gates uncorrected. Identity
     * when correction is off, the open slide is the reference, or the tree is foreign
     * ({@link #currentSlideId()} is null).
     */
    private final EditorAlignment editorAlignment = new EditorAlignment() {
        @Override
        public Alignment forAxis(GateNode gate, int axis) {
            return TreeResolver.correctionFor(session.tree(), gate, axis, currentSlideId(), alignments);
        }

        @Override
        public String referenceName() {
            String reference = session.tree().getReferenceSlideId();
            return reference == null ? null : cohort.slideName(reference);
        }
    };

    /** The shown gate's Manual/Skip on the open slide as the editor's banner, or none. */
    private void showSlideSetting() {
        GateNode shown = editorPane.getGateNode();
        String slideId = currentSlideId();
        editorPane.setSlideSetting(shown == null ? null : shown.slideSetting(slideId));
        // Whether a drag may move the cut: not on a gate with its own setting here, outside its
        // review item — the drag would move every other slide's cut and not this one's.
        editorPane.setCutEditable(review.cutEditable(shown, slideId,
                editorPane.viewMode() == CohortSession.ViewMode.THIS_SLIDE));
    }

    /** "Use the cohort value": drop the open slide's setting for the shown gate, as one undo step. */
    private void clearSlideSetting() {
        GateNode gate = currentNode;
        String id = currentSlideId();
        if (gate == null || id == null || gate.slideSetting(id) == null) return;
        session.recordEdit();
        gate.setSlideSetting(id, null);
        resyncToTree();
    }

    // --- The cohort (spec 2026-09-30 §3): the card, the window, the click-through and the answers ---

    /** The cohort as it now stands, in the card and (when open) the window; decides nothing. */
    private void renderCohort() {
        CohortState state = cohort.state();
        boolean noReference = session.tree().getReferenceSlideId() == null;
        String suggested = cohort.suggestedReferenceId();
        String open = noReference && state.available() ? "Choose reference…" : "Open cohort…";
        String line = noReference && suggested != null
                ? "Pick a reference slide — suggested: " + cohort.slideName(suggested)
                : cohort.statusLine(runAllowed(busyState()));
        // Shown while a run goes too, so its Cancel stays reachable (see updateRunAllButton).
        boolean running = batchRun != null && batchRun.running();
        cohortCard.render(line, open, state.available() || running);
        if (cohortWindow.isOpen()) {
            cohortGrid.render(CohortGridModel.derive(cohort, session.tree(), gridSelection, onlyLooks),
                    CohortPrefs.sampledCellsPerSlide(CohortPrefs.node()));
        }
        syncCrop(cohort.selected(), cohort.visibleItems());
    }

    /**
     * The crop follows the selected review item: it is requested when the item, its applied
     * values (a rescore after a moved cut) or the alignment model differ from the one shown, so a
     * moved threshold or a rescore re-renders; nothing is shown when no review item is selected —
     * a grid cell with no item (✓, ↷, ✎, ⊘) included. The items after it in the review are
     * prefetched. A crop landing for anything else is never shown (see
     * {@link EvidenceCropCoordinator}). The crop is requested whether or not the window is open,
     * so reopening it shows the current crop at once.
     */
    private void syncCrop(ReviewItem selected, List<ReviewItem> items) {
        if (selected == null) {
            if (shownCrop == null) return;
            shownCrop = null;
            crops.cancel();
            cohortGrid.clearCrop();
            return;
        }
        EvidenceCropCoordinator.CacheKey key = crops.keyOf(selected);
        if (key.equals(shownCrop)) return;
        shownCrop = key;
        int at = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(selected.key())) at = i;
        List<ReviewItem> following = at < 0 ? List.of() : items.subList(at + 1, items.size());
        cohortGrid.showCropLoading();
        crops.show(selected, List.copyOf(following), cohortGrid::showCrop);
    }

    private void openCohortWindow() {
        java.net.URL css = FlowPathPane.class.getResource("/qupath/ext/flowpath/ui/flowpath.css");
        cohortWindow.open(getScene() == null ? null : getScene().getWindow(), cohortGrid,
                css == null ? null : css.toExternalForm());
        renderCohort();
    }

    /**
     * A grid cell chosen. The grid keeps its own selection by value, because a cell may have no
     * review item (✓, ↷, ✎, ⊘) and {@link CohortSession#selected()} answers only for items; a
     * cell that is an item is selected in the session too, so Enter and S answer it.
     */
    private void chooseCell(ReviewItem.Key key) {
        gridSelection = key;
        boolean isItem = cohort.review().items().stream().anyMatch(i -> i.key().equals(key));
        if (isItem) {
            showItem(key);
        } else {
            if (review.active() != null) endActiveReview();
            pendingFocus = null;
            cohort.select(null);
            renderCohort();
        }
    }

    /**
     * [Adjust] (spec §3.4): the slide opened in the viewer with the gate selected in the editor.
     * A review item goes through its own click-through ({@link #openSelectedInViewer}); any other
     * cell opens its slide and shows the gate in This slide view, without opening a review.
     */
    private void adjustSelectedCell() {
        if (cohort.selected() != null) {
            openSelectedInViewer();
            return;
        }
        ReviewItem.Key key = gridSelection;
        if (key == null) return;
        if (key.slideId().equals(currentSlideId())) {
            showGateOnSlide(key);
            return;
        }
        endActiveReview();
        Project<BufferedImage> project = qupath.getProject();
        ProjectImageEntry<BufferedImage> entry = project == null ? null : project.getImageList().stream()
                .filter(e -> key.slideId().equals(e.getID())).findFirst().orElse(null);
        if (entry == null) {
            Dialogs.showWarningNotification("FlowPath", "That slide is no longer in the project");
            return;
        }
        pendingAdjust = key;
        if (!qupath.openImageEntry(entry)) pendingAdjust = null;
    }

    /** The gate of {@code key} shown in This slide view on its (open) slide; nothing is reviewed. */
    private void showGateOnSlide(ReviewItem.Key key) {
        GateNode gate = CohortSession.liveGate(session.tree(), key);
        if (gate == null) {
            renderCohort();
            return;
        }
        currentNode = gate;
        cohort.setViewMode(CohortSession.ViewMode.THIS_SLIDE);
        editorPane.setViewMode(CohortSession.ViewMode.THIS_SLIDE);
        render(Optional.empty(), false);
        syncViewerChannels(gate);
    }

    /**
     * Select a review item in the Cohort window — its cell, its detail and its evidence crop —
     * without opening its slide (opening is the exception: Adjust or {@code V}). A viewer review
     * of another item is left, as Esc leaves it, so Enter and S can only ever answer the item the
     * window's detail shows.
     */
    private void showItem(ReviewItem.Key key) {
        if (review.active() != null && !review.active().equals(key)) endActiveReview();
        // A click-through still waiting for its slide is for the item the user has just left.
        pendingFocus = ReviewTarget.pendingAfterSelecting(pendingFocus, key);
        pendingAdjust = null;
        cohort.select(key);
        gridSelection = key;   // N / P and the next item after an answer move the grid's selection too
        renderCohort();
    }

    /** V / "Open in viewer": the selected item's full click-through (Task 14's). */
    private void openSelectedInViewer() {
        ReviewItem selected = cohort.selected();
        if (selected != null) openReviewItem(selected.key());
    }

    /**
     * Snapshot, on the FX thread, what the selected item's crop needs, and return the work for
     * {@code flowpath-crops}: the open slide's own server when the item is on it, else a server
     * built from the project entry and closed after the read.
     */
    private Callable<EvidenceCrop.Crop> cropJob(ReviewItem item) {
        GateTree tree = session.tree().deepCopy();
        String slideId = item.key().slideId();
        SlideSample sample = cohort.sample(slideId);
        SlideSample reference = tree.getReferenceSlideId() == null ? null : cohort.sample(tree.getReferenceSlideId());
        AlignmentModel model = cohort.model();
        // Bound to the captured model — the one the crop's cache key names — never the live
        // lookup, which a rescore landing mid-read would move to another model.
        AlignmentLookup lookup = cohort.lookupOn(model);
        boolean open = slideId.equals(currentSlideId());
        ImageData<BufferedImage> imageData = qupath.getImageData();
        ImageServer<BufferedImage> openServer = open && imageData != null ? imageData.getServer() : null;
        Project<BufferedImage> project = qupath.getProject();
        ProjectImageEntry<BufferedImage> entry = openServer != null || project == null ? null
                : project.getImageList().stream().filter(e -> slideId.equals(e.getID())).findFirst().orElse(null);
        return () -> {
            if (sample == null) return EvidenceCrop.Crop.failed("This slide has not been sampled yet");
            if (model == null) return EvidenceCrop.Crop.failed("The cohort has not been aligned yet");
            if (openServer != null) return renderCrop(openServer, item, tree, sample, reference, model, lookup);
            if (entry == null) return EvidenceCrop.Crop.failed("This slide is no longer in the project");
            try (ImageServer<BufferedImage> server = entry.getServerBuilder().build()) {
                return renderCrop(server, item, tree, sample, reference, model, lookup);
            }
        };
    }

    private static EvidenceCrop.Crop renderCrop(ImageServer<BufferedImage> server, ReviewItem item, GateTree tree,
                                                SlideSample sample, SlideSample reference, AlignmentModel model,
                                                AlignmentLookup lookup) {
        EvidenceCrop.Spec spec = EvidenceCrop.spec(item, tree, sample, reference, model, lookup,
                BoundaryHotspot.fieldPixels(server.getPixelCalibration()));
        return spec == null ? EvidenceCrop.Crop.failed("No cells near the threshold in this slide's sample")
                : EvidenceCrop.render(server, spec);
    }

    /**
     * Open an item: its slide first, when another one is open — QuPath owns the save prompt for
     * the slide being left, and the item is focused once the new slide's cells have landed (see
     * {@link IngestHost#resynced}).
     */
    private void openReviewItem(ReviewItem.Key key) {
        cohort.select(key);
        gridSelection = key;
        if (key.slideId().equals(currentSlideId())) {
            focusReviewItem(key);
            return;
        }
        endActiveReview();
        Project<BufferedImage> project = qupath.getProject();
        ProjectImageEntry<BufferedImage> entry = project == null ? null : project.getImageList().stream()
                .filter(e -> key.slideId().equals(e.getID())).findFirst().orElse(null);
        if (entry == null) {
            Dialogs.showWarningNotification("FlowPath", "That slide is no longer in the project");
            renderCohort();
            return;
        }
        pendingFocus = key;
        if (!qupath.openImageEntry(entry)) pendingFocus = null;
        renderCohort();
    }

    /**
     * N / P (in the main pane or the Cohort window): the next or previous review item — opened in
     * the viewer while a viewer review is under way, else selected in the window with its crop.
     */
    private void stepReview(int delta) {
        boolean inViewer = viewerGate() != null;
        ReviewItem.Key key = cohort.step(delta);
        if (key == null) return;
        if (inViewer) openReviewItem(key);
        else showItem(key);
    }

    /**
     * The item's slide is open: select its gate in This slide view (through {@link #render}, which
     * shows the selected gate), centre the viewer on the tile with most boundary cells, and turn
     * the overlay on. The viewer centres on the tile the evidence crop does: the most-boundary
     * {@link BoundaryHotspot#fieldPixels} (200 µm) tile of the slide's <em>sample</em>, in level-0
     * pixels — the space ROI centroids are in — so the click-through lands on the cells the Cohort
     * window's crop showed. Only a slide with no sample falls back to its full index. Nothing opens
     * when the item is no longer the selected one ({@link ReviewTarget#mayFocus}).
     */
    private void focusReviewItem(ReviewItem.Key key) {
        GateNode gate = CohortSession.liveGate(session.tree(), key);
        ReviewItem selected = cohort.selected();
        if (!ReviewTarget.mayFocus(key, selected == null ? null : selected.key())) {
            // The user moved on while the slide opened: open nothing the window is not showing.
            renderCohort();
            return;
        }
        if (gate == null) {
            renderCohort();
            return;
        }
        if (session.index() == null || session.stats() == null) {
            pendingFocus = key;   // this slide's cells are still being read
            return;
        }
        review.open(key);
        currentNode = gate;
        cohort.setViewMode(CohortSession.ViewMode.THIS_SLIDE);
        editorPane.setViewMode(CohortSession.ViewMode.THIS_SLIDE);
        render(Optional.empty(), false);
        syncViewerChannels(gate);

        BoundaryHotspot.Boundary boundary = BoundaryHotspot.of(session.tree(), gate, currentSlideId(), alignments,
                session.index(), session.stats(), session.combinedMask(), cohort.model()::cofactor);
        QuPathViewer viewer = qupath.getViewer();
        if (viewer != null && overlayViewer != viewer) {
            if (overlayViewer != null) overlayViewer.getCustomOverlayLayers().remove(boundaryOverlay);
            viewer.getCustomOverlayLayers().add(boundaryOverlay);
            overlayViewer = viewer;
        }
        boundaryOverlay.setCells(session.index(), boundary.cells(), boundary.rgb());
        boundaryOverlay.setVisible(true);
        ImageData<BufferedImage> imageData = qupath.getImageData();
        double field = BoundaryHotspot.fieldPixels(imageData == null ? null : imageData.getServer().getPixelCalibration());
        // Centred as the crop is: the sample's boundary on the same 200 µm grid, the pair of calls
        // EvidenceCrop.spec makes. The full index is the fallback only for a slide with no sample;
        // the overlay above still outlines every boundary cell on the slide.
        SlideSample sample = cohort.sample(currentSlideId());
        BoundaryHotspot.Hotspot hot = sample != null
                ? BoundaryHotspot.hotspot(sample.index(),
                        BoundaryHotspot.ofSample(session.tree(), gate, sample, alignments, cohort.model()::cofactor).cells(), field)
                : BoundaryHotspot.hotspot(session.index(), boundary.cells(), field);
        if (viewer != null) {
            if (hot != null) viewer.setCenterPixelLocation(hot.centerX(), hot.centerY());
            viewer.repaint();
        }
    }

    /** The item reviewed in the viewer, only while its slide is the open one; else null. */
    private GateNode viewerGate() {
        return review.gate(currentSlideId());
    }

    /** What Enter and S answer now ({@link ReviewTarget}), or null. */
    private ReviewTarget answerTarget() {
        ReviewItem selected = cohort.selected();
        return ReviewTarget.of(viewerGate() == null ? null : review.active(), selected == null ? null : selected.key());
    }

    /** The gate Enter and S would answer, or null: its live gate, still in the tree. */
    private GateNode activeGate() {
        ReviewTarget target = answerTarget();
        return target == null ? null : CohortSession.liveGate(session.tree(), target.key());
    }

    /**
     * Put the item Enter or S is about to answer into {@link #review}: the viewer's open item as
     * it is, else the item selected in the Cohort window — its undo mark taken now, so the answer
     * is its one undo step. An Adjust still needs the viewer: nothing here can drag a cut.
     *
     * @return what is answered, or null when there is nothing to answer
     */
    private ReviewTarget openAnswerTarget() {
        ReviewTarget target = answerTarget();
        if (target == null || target.inViewer()) return target;
        endActiveReview();
        return review.open(target.key()) == null ? null : target;
    }

    /**
     * Enter / "Looks right": an Adjust the drags already wrote is kept, else
     * {@code Reviewed(applied values)}; the whole item is one undo step (see {@link ReviewFlow}).
     */
    private void answerEnter() {
        ReviewTarget target = openAnswerTarget();
        if (target == null) return;
        String slideId = target.key().slideId();
        if (review.answerEnter(slideId, cohort.slideName(slideId), alignments)) afterAnswer(target.key(), target.inViewer());
    }

    /** S / "Skip slide for this gate": its cells are unmeasured on this slide. One undo step. */
    private void answerSkip() {
        ReviewTarget target = openAnswerTarget();
        if (target == null) return;
        String slideId = target.key().slideId();
        if (review.answerSkip(slideId, cohort.slideName(slideId))) afterAnswer(target.key(), target.inViewer());
    }

    /**
     * An answer is a tree edit: the one resync path shows it (editor, banner, Correct staining,
     * the Cohort card and window) and re-gates — a Manual or a Skip changes classification — and
     * the rescore turns the answered cell from ⚠ into its answer's mark. Then the next item: opened
     * in the viewer when the answer was given there, else selected in the window with its crop.
     */
    private void afterAnswer(ReviewItem.Key answered, boolean inViewer) {
        endActiveReview();
        resyncToTree();
        cohortCoordinator.rescore(session.tree());
        ReviewItem.Key next = cohort.step(+1);
        if (next != null && !next.equals(answered)) {
            if (inViewer) openReviewItem(next);
            else showItem(next);
        } else {
            cohort.select(null);
            renderCohort();
        }
    }

    /**
     * Shift+Enter / "All look right": every flagged slide of the selected gate reviewed at its
     * applied number, as ONE undo step holding every slide's name (see {@link ReviewFlow#answerGroup},
     * which also decides what the answer does to an open item). Then the one resync path and a
     * rescore, as any answer.
     */
    private void answerGroup() {
        ReviewGroup group = cohort.selectedGroup();
        if (group == null) return;
        review.answerGroup(group, cohort::slideName, alignments);
        cohort.selectGroup(null);
        ReviewItem selected = cohort.selected();
        if (selected != null && selected.key().rootIndex() == group.rootIndex()
                && selected.key().gatePath().equals(group.gatePath())) {
            cohort.select(null);
        }
        endActiveReview();
        resyncToTree();
        cohortCoordinator.rescore(session.tree());
        renderCohort();
    }

    /** Esc: back to All slides, the overlay off. A drag made meanwhile stays, as the edit it is. */
    private void leaveReview() {
        endActiveReview();
        cohort.select(null);
        gridSelection = null;
        pendingAdjust = null;
        cohort.setViewMode(CohortSession.ViewMode.ALL_SLIDES);
        editorPane.setViewMode(CohortSession.ViewMode.ALL_SLIDES);
        showSlideSetting();
        renderCohort();
    }

    /**
     * Stop answering the open item in the viewer: B acts on nothing, and Enter and S fall back to
     * the item selected in the Cohort window (its detail and crop), until an item is opened again.
     * Also on undo, redo, a load and a reference rebase — each can change the reviewed gate's
     * reference numbers, and a stale baseline would write the old numbers back on the next drag.
     */
    private void endActiveReview() {
        review.end();
        hideBoundaryOverlay();
        showSlideSetting();
    }

    private void hideBoundaryOverlay() {
        if (!boundaryOverlay.isVisible()) return;
        boundaryOverlay.setVisible(false);
        if (overlayViewer != null) overlayViewer.repaint();
    }

    /**
     * ☆ / "Use X" (spec 2026-09-30 §4.1). No reference yet: a tree with no gates takes {@code id}
     * directly; a tree with gates first asks which slide they were drawn on — that slide becomes
     * the reference (the numbers are its numbers), and the suggestion is offered again once the
     * rescore lands. An existing reference is rebased, after one confirmation, as one undo step
     * ({@link CohortSession#rebaseReference} through {@code recordSlideEdit}) — refused while the
     * current reference has no sample or model ({@link CohortSession#rebaseRefusal}).
     */
    private void chooseReference(String id) {
        if (id == null || !CohortIdentity.matches(session.tree(), cohort.projectNames())) return;
        GateTree tree = session.tree();
        if (tree.getReferenceSlideId() == null) {
            String chosen = id;
            if (!tree.getRoots().isEmpty()) {
                // Label → id, each label unique: two images may share a name, so a shared name is
                // shown with its id and mapped back by the label, never by name.
                Map<String, String> byLabel = SlideChoices.labels(cohort.projectSlides().stream()
                        .filter(r -> !cohort.excluded().contains(r.id())).toList());
                String preselect = SlideChoices.labelOf(byLabel, indexSlideId);
                if (preselect == null) preselect = SlideChoices.labelOf(byLabel, id);
                String picked = Dialogs.showChoiceDialog("Reference slide",
                        "Which slide were these gates drawn on? Its thresholds are kept as they are; "
                                + "you can switch to " + cohort.slideName(id) + " afterwards.",
                        List.copyOf(byLabel.keySet()), preselect);
                if (picked == null) return;
                chosen = byLabel.get(picked);
                if (chosen == null) return;
            }
            session.confirmReference(chosen, cohort.projectNames());
        } else {
            if (id.equals(tree.getReferenceSlideId())) return;
            String refusal = cohort.rebaseRefusal(tree.getReferenceSlideId());
            if (refusal != null) {
                Dialogs.showWarningNotification("FlowPath", refusal);
                return;
            }
            if (!Dialogs.showConfirmDialog("Reference slide",
                    "Thresholds will be re-expressed on " + cohort.slideName(id) + ". Ctrl+Z undoes it.")) return;
            session.recordSlideEdit(id, cohort.slideName(id),
                    () -> CohortSession.rebaseReference(session.tree(), id, alignments));
        }
        endActiveReview();
        resyncToTree();
        cohortCoordinator.rescore(session.tree());
        renderCohort();
    }

    /**
     * Exclude or include a slide (spec §5): project metadata, not a tree edit, so no undo step.
     * Excluding the current reference is refused; including it is not. A selection or pending click-through on the slide just
     * excluded is dropped; excluding keeps every other slide's sample and rescores, including a
     * slide again re-samples (its sample was never taken while it was excluded).
     */
    private void toggleExcluded(String slideId) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null || slideId == null) return;
        CohortExclusions exclusions = CohortExclusions.of(project);
        boolean exclude = !exclusions.isExcluded(slideId);
        // Only excluding the reference is refused: including it again is the way out of a tree
        // whose reference is excluded (an undo, a load or another project's exclusions).
        if (exclude && slideId.equals(session.tree().getReferenceSlideId())) {
            Dialogs.showWarningNotification("FlowPath", "Pick another reference first");
            return;
        }
        try {
            exclusions.setExcluded(slideId, exclude);
        } catch (IOException e) {
            logger.error("Could not save the exclusion of {}", slideId, e);
            Dialogs.showErrorMessage("FlowPath", "Could not save the project: " + e.getMessage());
            return;
        }
        if (exclude) {
            if (gridSelection != null && slideId.equals(gridSelection.slideId())) gridSelection = null;
            ReviewItem selected = cohort.selected();
            if (selected != null && slideId.equals(selected.key().slideId())) {
                if (review.active() != null) endActiveReview();
                cohort.select(null);
            }
            if (pendingFocus != null && slideId.equals(pendingFocus.slideId())) pendingFocus = null;
            if (pendingAdjust != null && slideId.equals(pendingAdjust.slideId())) pendingAdjust = null;
        } else {
            // An included slide has no sample (it was never sampled while excluded): force one run.
            lastSampledKey = null;
        }
        // Excluding needs no re-sample: setExcluded drops the slide's sample and review, and the
        // refresh ends in a rescore either way.
        refreshCohort();
        renderCohort();
    }

    /**
     * "Use cohort value" for the grid's selected cell: drop that slide's setting on that gate, as
     * one undo step, then the one resync path and a rescore.
     */
    private void clearSelectedSlideSetting() {
        ReviewItem.Key key = gridSelection;
        if (key == null) return;
        GateNode gate = CohortSession.liveGate(session.tree(), key);
        if (gate == null || gate.slideSetting(key.slideId()) == null) return;
        session.recordEdit();
        gate.setSlideSetting(key.slideId(), null);
        resyncToTree();
        cohortCoordinator.rescore(session.tree());
        renderCohort();
    }

    /** A review key pressed in the Cohort window's grid; the same handlers as the main pane's keys. */
    private void onCohortKey(ReviewKey key) {
        switch (key) {
            case LOOKS_RIGHT -> answerEnter();
            case SKIP -> answerSkip();
            case NEXT -> stepReview(+1);
            case PREVIOUS -> stepReview(-1);
            case BACK -> leaveReview();
            case REVIEW_GROUP -> {
                // Shift+Enter in the grid: the selected cell's column, as its "All look right".
                if (gridSelection != null) {
                    cohort.selectGroup(new ReviewGroup.Key(gridSelection.rootIndex(), gridSelection.gatePath()));
                }
                answerGroup();
            }
            case OPEN_IN_VIEWER -> adjustSelectedCell();
            case TOGGLE_OVERLAY -> {
                if (viewerGate() == null) return;
                boundaryOverlay.toggle();
                if (overlayViewer != null) overlayViewer.repaint();
            }
        }
    }

    /**
     * The review keys (spec §6), never while a text field has focus. Enter and S answer the item
     * open in the viewer, else the selected one; V opens the selected item in the viewer; B acts
     * only on an item open on its own slide; N and P while the cohort is available; Esc while
     * reviewing.
     *
     * @return whether the key was handled (and should be consumed)
     */
    private boolean onReviewKey(javafx.scene.input.KeyEvent e) {
        boolean textFocused = e.getTarget() instanceof TextInputControl
                || (getScene() != null && getScene().getFocusOwner() instanceof TextInputControl);
        boolean otherModifier = e.isShortcutDown() || e.isControlDown() || e.isMetaDown() || e.isAltDown();
        ReviewKey key = ReviewKey.of(e.getCode(), e.isShiftDown(), otherModifier, textFocused);
        if (key == null) return false;
        switch (key) {
            case LOOKS_RIGHT -> {
                if (activeGate() == null) return false;
                answerEnter();
            }
            case SKIP -> {
                if (activeGate() == null) return false;
                answerSkip();
            }
            case OPEN_IN_VIEWER -> {
                if (cohort.selected() == null) return false;
                openSelectedInViewer();
            }
            case NEXT, PREVIOUS -> {
                if (!cohort.state().available()) return false;
                stepReview(key == ReviewKey.NEXT ? +1 : -1);
            }
            case BACK -> {
                if (review.active() != null) {
                    leaveReview();
                } else if (cohort.selectedGroup() != null) {
                    // Out of the gate's group: N / P step through every item again.
                    cohort.selectGroup(null);
                    renderCohort();
                } else {
                    return false;
                }
            }
            case REVIEW_GROUP -> {
                if (cohort.selectedGroup() == null) return false;
                answerGroup();
            }
            case TOGGLE_OVERLAY -> {
                if (viewerGate() == null) return false;
                boundaryOverlay.toggle();
                if (overlayViewer != null) overlayViewer.repaint();
            }
        }
        return true;
    }

    /**
     * The one place that applies {@link BusyState} to the widgets — every background worker
     * reports its state here rather than each disabling its own set of controls, and the rule
     * itself (what each state blocks, what the status bar says) lives in {@code BusyState},
     * where it is table-tested.
     * <p>
     * While a new image is read the session has no cells, so the controls that need them wait
     * for it; a refresh of cells the session still holds disables nothing. While a derivation
     * is in flight the editor waits too — the tree edit that asked for it has already been
     * taken and shown, but the statistics beside it are being replaced, and an editor write
     * lands in the gate before it is reported, which is one place the old values must not
     * reach. Undo, redo, the toggle and the quality filter stay live: a second edit supersedes
     * the derivation in flight rather than being queued or refused (see
     * {@link DerivationCoordinator}).
     */
    private void updateBusyControls() {
        BusyState busy = busyState();
        addRootBtn.setDisable(busy.loading());
        editorPane.setDisable(busy.editingBlocked());
        umapButton.setDisable(!UMAP_ENABLED || session.index() == null);
        analysisButton.setDisable(!ANALYSIS_ENABLED || session.index() == null);
        updateExportControlsDisabled();
        updateRunAllButton(busy);
        updateImportButton(busy, null);
        updateStatusBar();
        renderCohort();
    }

    /**
     * "Run on all slides" applies {@link BusyState#batchBlocked()} and decides nothing else of
     * its own: while a run goes it becomes its Cancel, which is never disabled. Shown only where
     * there is a cohort to run over — and while a run goes, so its Cancel stays reachable.
     */
    private void updateRunAllButton(BusyState busy) {
        boolean running = batchRun.running();
        boolean shown = running || cohort.state().available();
        runAllButton.setVisible(shown);
        runAllButton.setManaged(shown);
        runAllButton.setText(running ? "Cancel run" : "Run on all slides…");
        runAllButton.setDisable(!running && !runAllowed(busy));
    }

    /**
     * "New project from MIRAGE…" applies {@link BusyState#importBlocked()} and decides nothing else:
     * while an import runs it is that import's Cancel, never disabled, with its progress.
     *
     * @param progress "done/total" while running, or {@code null} to keep what it says
     */
    private void updateImportButton(BusyState busy, String progress) {
        boolean running = mirageImport.running();
        if (!running) importButton.setText("New project from MIRAGE…");
        else if (progress != null) importButton.setText("Cancel import (" + progress + ")");
        else if (!importButton.getText().startsWith("Cancel import")) importButton.setText("Cancel import");
        importButton.setDisable(!running && busy.importBlocked());
    }

    /**
     * Whether "Run on all slides" may start: {@link BusyState#batchAllowed}, over whether the tree
     * has an enabled gate. The one answer the button and the status line's "Ready to run" both
     * read, so the line can never call a run ready that the button refuses.
     */
    private boolean runAllowed(BusyState busy) {
        return busy.batchAllowed(BatchRunner.hasEnabledGate(session.tree()));
    }

    /** What the background workers are doing right now; see {@link BusyState}. */
    private BusyState busyState() {
        return new BusyState(ingest.busy() == IngestCoordinator.Busy.LOADING,
                derivations.deriving(), csvExport.exporting(), cohortCoordinator.sampling(),
                batchRun.running(), mirageImport.running());
    }

    /**
     * Export CSV (and, through {@link #exportCsv()}'s own guard, Ctrl+E) is disabled whenever
     * {@link BusyState#exportBlocked()} says so: no cells read yet, an export already running,
     * or a derivation in flight, whose statistics and masks would otherwise be snapshotted
     * beside a tree they do not describe. One guard, checked from both places that can end any
     * of those states, rather than a second parallel disable mechanism.
     */
    private void updateExportControlsDisabled() {
        exportBtn.setDisable(busyState().exportBlocked());
        updateSpinner();
    }

    private void updateSpinner() {
        spinner.setVisible(previewRunning || ingest.busy() != IngestCoordinator.Busy.IDLE
                || derivations.deriving() || csvExport.exporting() || cohortCoordinator.sampling()
                || batchRun.running());
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
     * A gate was dragged onto another branch — or onto the tree's background, which promotes it
     * back to a root. {@link GateDragCoordinator} has already recorded the undo step and applied
     * the move; re-parenting a gate changes neither the ROI mask nor the statistics, so there is
     * nothing for a derivation to recompute and this stops short of {@link #resyncToTree()},
     * exactly as {@link #addRootGate()} and {@link #addChildGate(int)} do.
     * <p>
     * <b>It goes through {@link #render} rather than rebuilding the tree here.</b> A
     * {@code rebuildTreeView()} call on its own clears the selection — {@code setRoot} does —
     * which fires the selection listener with {@code null} and blanks the editor, while the
     * {@link #selectNodeInTree} that follows suppresses the listener and so never restores it.
     * A completed move then left the tree showing the gate selected and the editor empty, with
     * the ancestor mask (which genuinely changed: the gate hangs under a different parent now)
     * never recomputed. {@link #render} is the one place that already does all of this
     * correctly — suppressed rebuild, {@link EditorRebuild#surviving}, ancestor mask,
     * {@link EditorRebuild#needed} — so the only decision left here is which gate the editor
     * should end up on, and that is the one that was just moved.
     * <p>
     * Seeding {@link #currentNode} rather than calling {@code setGateNode} outright keeps the
     * rebuild conditional: a re-parented gate's own controls (channel, threshold, branch names)
     * are unchanged, so {@link EditorRebuild#needed} says no, and a polygon the user is halfway
     * through drawing survives the move.
     */
    private void onGateMoved(GateNode moved) {
        currentNode = moved;
        render(Optional.empty(), false);
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

        // Another gate chosen by hand: the open item is left, so its answer can never fold an
        // edit of a different gate into its undo step.
        if (review.active() != null && node != viewerGate()) endActiveReview();

        if (node != null) {
            currentNode = node;
            editorPane.setAncestorMask(computeAncestorMask(node));
            editorPane.setGateNode(node);
            showSlideSetting();
            syncViewerChannels(node);
        } else {
            editorPane.setAncestorMask(null);
            editorPane.setGateNode(null);
        }
    }

    private boolean[] computeAncestorMask(GateNode node) {
        if (session.index() == null || session.stats() == null) return null;
        // The editor's parent population is the one the live pass classifies: the open slide's
        // resolved tree, not the reference numbers.
        TreeResolver.ResolvedTree resolved = TreeResolver.resolve(session.tree(), currentSlideId(), alignments);
        GateNode target = resolved.resolvedOf(node);
        if (target == null) return null;
        return GatingEngine.computeAncestorMask(resolved.tree(), target, session.index(), session.stats(),
                session.combinedMask());
    }

    /**
     * The slide id the tree is resolved for: the project id of the slide whose cells the session
     * holds (taken with the index, never from the viewer; see {@link #indexSlideId}), or null —
     * every number the reference — outside a project, or when the tree's recorded slide names
     * say it belongs to another project ({@link CohortIdentity}), whose ids name other images.
     */
    private String currentSlideId() {
        return CohortIdentity.resolutionSlideId(session.tree(), cohort.projectNames(), indexSlideId);
    }

    /** {@code data}'s project id, or null when there is no project or it holds no entry for it. */
    @SuppressWarnings("unchecked")
    private String slideIdOf(ImageData<?> data) {
        try {
            Project<BufferedImage> project = qupath.getProject();
            if (project == null || data == null) return null;
            var entry = project.getEntry((ImageData<BufferedImage>) data);
            return entry == null ? null : entry.getID();
        } catch (Exception e) {
            logger.debug("No project entry for the open image", e);
            return null;
        }
    }

    /** Hand the open slide and the current alignments to the live pass. */
    private void applySlideContext() {
        cohort.setLiveTree(session.tree());
        previewService.setSlideContext(currentSlideId(), alignments);
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
        // Recorded as the editor's coalesced step; on an open review item in This slide view the
        // drag is this slide's Manual instead of a reference edit (see ReviewFlow).
        String slideId = currentSlideId();
        if (review.gateEdited(editorPane.getGateNode(), slideId, cohort.slideName(slideId), alignments,
                editorPane.viewMode() == CohortSession.ViewMode.THIS_SLIDE)) {
            showSlideSetting();
        }
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

    /**
     * A discrete switch on the shown gate ("Correct staining", "Lineage marker"), already written:
     * its own undo step, never folded into a drag just before it, then a pass (which rescores).
     */
    private void onGateDiscreteEdit() {
        session.recordAppliedDiscreteEdit();
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
        applySlideContext();
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

        // A rescore requests a pass only when an alignment changed, so this cannot loop.
        if (cohort.state().available()) cohortCoordinator.rescore(session.tree());
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

        // The selection is restored by VALUE, not by index -- see ColorByRootSelection. The
        // rebuild happens BEFORE the items are replaced, so the listener above resolves any
        // index JavaFX reports mid-replacement against the new entries, never the old ones.
        int restore = colorByRoot.rebuild(rootNames);
        colorByRootCombo.getItems().setAll(rootNames);
        if (rootNames.size() <= 1) {
            colorByRootCombo.setDisable(true);
            colorByRootCombo.getSelectionModel().clearSelection();
            colorByRoot.clear();
            // Reset to default color mode (no-op if already -1)
            previewService.setColorRootIndex(-1);
        } else if (restore >= 0) {
            colorByRootCombo.setDisable(false);
            colorByRootCombo.getSelectionModel().select(restore);
        } else {
            // The root that was being coloured by is gone (deleted or disabled). Fall back to
            // the default colours rather than repaint the slide by whichever root inherited
            // its position.
            colorByRootCombo.setDisable(false);
            colorByRootCombo.getSelectionModel().clearSelection();
            previewService.setColorRootIndex(-1);
        }
    }

    private void updateStatusBar() {
        Optional<String> busyMessage = busyState().message();
        if (busyMessage.isPresent()) {
            statusBar.setText(String.format("%s | Gates: %d", busyMessage.get(),
                countGates(session.tree().getRoots())));
            statusBar.setTooltip(null);
            return;
        }
        int total = session.index() != null ? session.index().size() : 0;
        int excluded = previewService.getLastExcludedCount();
        int gateCount = countGates(session.tree().getRoots());
        String roiInfo = session.tree().isRoiFilterEnabled() ? describeRegions() : "";
        // Sampling and a batch run last minutes: their progress rides on the counts, not over them.
        String cohortMessage = cohort.state().message();
        statusBar.setText(String.format("Total: %,d cells | Excluded: %,d | Gates: %d%s%s%s",
            total, excluded, gateCount, roiInfo, ingestWarning(),
            cohortMessage == null ? "" : " | " + cohortMessage));
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
            Dialogs.showErrorMessage("Save Error", ErrorMessages.describe(ex));
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
            endActiveReview();
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
            Dialogs.showErrorMessage("Load Error", ErrorMessages.describe(ex));
        }
    }

    /**
     * Snapshot the tree, cells, statistics, ROI mask and regions at this moment and hand them
     * to {@link #csvExport}, which gates and writes them on {@link #backgroundExecutor}. The
     * snapshot -- not the live session -- is what reaches the file, so a gate edited while the
     * export is running never leaks into it. A stray Ctrl+E while the export button is
     * disabled -- the accelerator is not tied to it -- is a no-op: an export already running,
     * cells still being read, or a derivation in flight, in which case the statistics and ROI
     * mask beside the tree are the ones it is replacing and the file would not describe any
     * state the session was ever in.
     */
    private void exportCsv() {
        if (busyState().exportBlocked()) return;
        if (session.index() == null || session.stats() == null || session.tree().getRoots().isEmpty()) {
            Dialogs.showWarningNotification("FlowPath", "No gates defined or no cells loaded.");
            return;
        }

        File file = Dialogs.promptToSaveFile("Export Phenotypes", null, "gate_pheno.csv", "CSV", ".csv");
        if (file == null) return;

        CsvExportJob.Snapshot snapshot = CsvExportJob.Snapshot.of(
                file, session.tree(), session.index(), session.stats(), session.roiMask(), session.regions(),
                currentSlideId(), alignments);
        csvExport.export(snapshot);
        updateExportControlsDisabled();
    }

    /**
     * Run the gate tree on every slide of the project, or cancel the run going. What may start is
     * {@link BatchRunCoordinator#check}'s answer — a tree from another project is refused, and the
     * confirmation says how many review items are still open. Everything the run reads is taken
     * after the dialogs, since landings run while they are open: the tree (frozen by
     * {@link BatchRunner.Settings}), the alignments bound to the model the user reviewed — never
     * recomputed — and that model's landmarks and review flags for the manifest.
     */
    /**
     * "New project from MIRAGE…", or its Cancel while one runs. Refused up front while the open
     * image has unsaved changes: the import ends by switching QuPath's project. When the target
     * is the project QuPath has open, that project is closed before the import starts, so the
     * import's own {@code Project} is the only one writing {@code project.qpproj}; it is reopened
     * at the end ({@link MirageImportHost#finished}).
     */
    private void newProjectFromMirage() {
        if (mirageImport.running()) {
            mirageImport.cancel();
            return;
        }
        if (busyState().importBlocked()) return;
        String refused = MirageImportCoordinator.refusal(openImageUnsaved(), 1);
        if (refused != null) {
            Dialogs.showPlainMessage("New project from MIRAGE", refused);
            return;
        }
        Optional<MirageImportDialog.Request> request =
                MirageImportDialog.show(getScene() != null ? getScene().getWindow() : null);
        if (request.isEmpty()) return;

        // The dialog ran a nested event loop: re-ask, against QuPath as it is now.
        refused = MirageImportCoordinator.refusal(openImageUnsaved(), request.get().ready().size());
        if (refused != null) {
            Dialogs.showPlainMessage("New project from MIRAGE", refused);
            return;
        }
        if (busyState().importBlocked()) {
            Dialogs.showWarningNotification("FlowPath", "FlowPath became busy; start the import again when it is idle.");
            return;
        }
        Path target = request.get().projectDir().toAbsolutePath().normalize();
        Project<BufferedImage> open = qupath.getProject();
        if (open != null && ProjectSlides.projectDir(open).toAbsolutePath().normalize().equals(target)) {
            qupath.setProject(null);
        }
        importSkipped = request.get().skipped();
        mirageImport.run(target, request.get().ready(), MirageImportCoordinator.Importer.standard());
        updateBusyControls();
    }

    private boolean openImageUnsaved() {
        ImageData<BufferedImage> data = qupath.getImageData();
        return data != null && data.isChanged();
    }

    private void runOnAllSlides() {
        if (batchRun.running()) {
            batchRun.cancel();
            return;
        }
        Project<BufferedImage> project = qupath.getProject();
        if (project == null || busyState().batchBlocked()) return;
        BatchRunCoordinator.Start start = BatchRunCoordinator.check(session.tree(),
                ProjectSlides.batchSlides(project), cohort.state().remaining(), cohort.failedSlideNames());
        if (start instanceof BatchRunCoordinator.Refused refused) {
            Dialogs.showPlainMessage("Run on all slides", refused.message());
            return;
        }
        if (!Dialogs.showConfirmDialog("Run on all slides", start.message())) return;
        File dir = Dialogs.promptForDirectory("Where should the results go?", null);
        if (dir == null) return;

        // The dialogs ran a nested event loop: re-ask, against the project and tree as they are now.
        project = qupath.getProject();
        if (project == null || busyState().batchBlocked()) {
            Dialogs.showWarningNotification("FlowPath", "FlowPath became busy; run on all slides again when it is idle.");
            return;
        }
        List<BatchSlide> slides = ProjectSlides.batchSlides(project);
        if (BatchRunCoordinator.check(session.tree(), slides, 0, List.of()) instanceof BatchRunCoordinator.Refused refused) {
            Dialogs.showPlainMessage("Run on all slides", refused.message());
            return;
        }
        AlignmentModel model = cohort.model();
        AlignmentLookup lookup = cohort.lookupOn(model);
        // The viewer's image, asked live at every write-back: the user may open another slide
        // while the run goes, and its file must not be written behind QuPath's back either.
        viewerSlideId = slideIdOf(qupath.getImageData());
        BatchRunner.Settings settings = new BatchRunner.Settings(session.tree(), lookup, dir,
                id -> id.equals(viewerSlideId), true, previewService.getColorRootIndex());
        // What the user reviewed, never recomputed: the model, its review (and marker-rule rates)
        // and the lookup bound to that model — the run gates with exactly these alignments.
        // The sample size recorded is the one those samples were drawn with, not the preference
        // now (which may have changed since); the preference only when nothing was sampled.
        int sampled = cohortCoordinator.sampledCellsPerSlide();
        CohortEvidence evidence = new CohortEvidence(model, cohort.review(), lookup, new CohortEvidence.Provenance(
                sampled >= 0 ? sampled : CohortPrefs.sampledCellsPerSlide(CohortPrefs.node()),
                sampled >= 0 ? CohortEvidence.FROM_REVIEWED_MODEL : CohortEvidence.FROM_PREFERENCE,
                -1, cohort.samples().size(), cohort.projectNames().get(settings.tree().getReferenceSlideId())),
                cohort.excluded());
        cohort.batchStarted();
        batchRun.run(slides, settings, (d, runs) -> FlowPathBatch.finish(d, settings.tree(), runs, evidence));
        updateBusyControls();
    }

    // --- Context menu ---

    private void showTreeContextMenu(double screenX, double screenY) {
        GateNode selected = getSelectedGateNode();
        ContextMenu menu = new ContextMenu();
        // Same predicate as addRootBtn's own setDisable: there is no index yet to add a gate
        // against, so "Add Root Gate..." and "Add child to..." are disabled while loading,
        // exactly as the toolbar button already is -- the tree view offered them regardless,
        // whether or not the tree is currently editable (Duplicate/Remove need no index and
        // stay enabled).
        boolean loading = busyState().loading();

        if (selected != null) {
            // Add child gate to each branch
            for (int i = 0; i < selected.getBranches().size(); i++) {
                Branch branch = selected.getBranches().get(i);
                int branchIdx = i;
                MenuItem addItem = new MenuItem("Add child to '" + branch.getName() + "'");
                addItem.setOnAction(e -> addChildGate(branchIdx));
                addItem.setDisable(loading);
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
            addRoot.setDisable(loading);
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

    private void undo() {
        if (session.undo()) {
            endActiveReview();
            resyncToTree();
        }
    }

    private void redo() {
        if (session.redo()) {
            endActiveReview();
            resyncToTree();
        }
    }

    /**
     * Bring every widget and the gating pass in line with the session's current tree and
     * index: the path for a load, an undo or redo and an ROI toggle. An image switch and a
     * hierarchy change take the same resync through {@link #ingest}; these take it through
     * {@link #derivations}. Either way the masks and statistics are computed on
     * {@link #backgroundExecutor} and the result is rendered here through {@link #render} —
     * a changed mask re-sorts every marker column over every cell, which on a million-cell
     * slide froze QuPath for seconds when it ran here.
     * <p>
     * What is <em>not</em> deferred is the tree edit itself: the undo, redo, load or toggle
     * has already been applied to {@link #session} on this thread before this is called, so the
     * undo stack, its coalescing window and the baseline the next edit records are untouched.
     * A second edit while a derivation is in flight is taken at once and supersedes it.
     * <p>
     * Nor is <em>showing</em> that edit deferred. The widgets are rendered here, against the
     * derived state the session still holds, and again when the derivation lands. Rendering
     * only at the landing left the tree view and the editor holding {@code GateNode}s an undo
     * or a load had already replaced — an edit made in that window was written into a node no
     * longer in the tree and silently dropped, while still costing an undo step. The counts
     * shown meanwhile are the previous pass's, exactly as they are for any edit whose gating
     * pass has not finished.
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
        // Show the edit, then ask for its derivation: requesting first would render twice on
        // the no-cells path, where the request resyncs and renders on this very thread.
        render(Optional.empty(), false);
        derivations.request();
    }

    /**
     * Render what the session holds — a resync's result, from {@link #ingest} or from
     * {@link #derivations}, or the tree as an edit has just left it, before its derivation has
     * been asked for (see {@link #resyncToTree()}).
     * <p>
     * The editor is rebuilt only when it has to be: the cells changed ({@code newIndex}), a
     * migration rewrote gates in place, or the selected gate is not the one it shows (an undo
     * or a load swaps in fresh {@code GateNode}s). An annotation edit or a filter toggle keeps
     * it, and its plots redraw through the masks and statistics set below. Rebuilding on every
     * resync threw away a polygon half-drawn on the scatter plot whenever an annotation moved.
     */
    private void render(Optional<GatingSession.MigrationNotice> notice, boolean newIndex) {
        GateTree tree = session.tree();
        // First: the cohort lookup answers for the tree's reference and identity, and the editor
        // asks it (All slides curves, the display seam) from setGateNode and every refresh below.
        // After an undo or a load of a foreign or re-referenced tree it would otherwise answer —
        // and the curves cache store — for the tree just left.
        cohort.setLiveTree(tree);

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
        editorPane.setCohortAvailable(cohort.state().available());
        if (EditorRebuild.needed(newIndex, notice.isPresent(), editorPane.getGateNode(), currentNode)) {
            editorPane.setGateNode(currentNode);
        }
        showSlideSetting();
        renderedCohortContext = cohortContext();

        updateStatusBar();
        renderCohort();
        notice.ifPresent(this::showMigrationNotice);
    }

    /** Hand a resync's result to the live preview and request the pass. */
    private void requestGatingPass(GatingSession.PassInput input) {
        applySlideContext();
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
        if (overlayViewer != null) {
            overlayViewer.getCustomOverlayLayers().remove(boundaryOverlay);
            overlayViewer.repaint();
            overlayViewer = null;
        }
        cohortCoordinator.cancel();
        batchRun.close();
        mirageImport.close();
        cohortWindow.close();
        crops.cancel();
        // shutdownNow: a crop still reading is for a pane that is gone.
        cropExecutor.shutdownNow();
        ingest.close();
        derivations.close();
        umapWindow.close();
        analysisWindow.dispose();
        previewService.shutdown();
        // shutdownNow: a read or an export still queued on this executor is for a pane that is
        // gone. Its result could not land anyway -- ingest is closed above, and a queued export
        // has no pane left to notify either way -- so there is nothing to wait for.
        backgroundExecutor.shutdownNow();
    }
}
