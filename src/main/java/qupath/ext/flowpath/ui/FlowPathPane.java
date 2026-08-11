package qupath.ext.flowpath.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.LivePreviewService;
import qupath.ext.flowpath.io.FlowPathSerializer;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestReport;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.io.PhenotypeCsvExporter;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.UndoHistory;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.UmapWindow;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.util.*;

/**
 * Main panel for the FlowPath extension.
 * SplitPane: TreeView + QualityFilterPane (left), GateEditorPane (right).
 * Toolbar at bottom for save/load/export.
 */
public class FlowPathPane extends BorderPane {

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

    /**
     * The UMAP view this pane opens and keeps fed. Created eagerly but does not build
     * any UI until the user asks for it — an unopened window costs one object.
     */
    private final UmapWindow umapWindow = new UmapWindow();

    private final UndoHistory<GateTree> undoHistory =
        new UndoHistory<>(UndoHistory.DEFAULT_MAX_DEPTH, GateTree::deepCopy, System::currentTimeMillis);

    private GateTree gateTree;
    private CellIndex cellIndex;
    private MarkerStats markerStats;
    private List<String> markerNames;
    private CompartmentCapability compartmentCapability = CompartmentCapability.empty();
    /** What the last ingest could not resolve. Surfaced in the status bar, never modally. */
    private IngestReport ingestReport = IngestReport.empty();
    private boolean[] cachedQualityMask;
    private boolean[] cachedRoiMask;
    private PathObjectHierarchyListener hierarchyListener;
    private boolean suppressRoiFilterEvents = false;
    private ImageData<?> listenerImageData;

    public FlowPathPane(QuPathGUI qupath) {
        this.qupath = qupath;
        this.gateTree = new GateTree();
        this.previewService = new LivePreviewService();

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
        Button addRootBtn = new Button("+ Add Root Gate");
        addRootBtn.setMaxWidth(Double.MAX_VALUE);
        addRootBtn.setOnAction(e -> addRootGate());
        addRootBtn.setTooltip(new Tooltip("Add a new top-level gate to the gating hierarchy"));

        // ROI filter
        roiFilterCheckBox = new CheckBox("Filter by annotations");
        roiFilterCheckBox.setStyle("-fx-text-fill: black; -fx-font-size: 10;");
        roiFilterCheckBox.setOnAction(e -> { if (!suppressRoiFilterEvents) onRoiFilterToggled(); });

        // Auto-sync the QuPath viewer's visible channels to the selected gate's channel(s)
        syncViewerChannelsToggle = new CheckBox("Sync viewer channels");
        syncViewerChannelsToggle.setStyle("-fx-text-fill: black; -fx-font-size: 10;");
        syncViewerChannelsToggle.setSelected(true);
        syncViewerChannelsToggle.setTooltip(new Tooltip(
            "Show only the selected gate's channel(s) in the QuPath viewer."));
        syncViewerChannelsToggle.setOnAction(e -> {
            if (syncViewerChannelsToggle.isSelected()) syncViewerChannels(currentNode);
        });

        qualityFilterPane = new QualityFilterPane(gateTree.getQualityFilter());
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
        editorPane.setOnAddToPositive(this::addGateToPositive);
        editorPane.setOnAddToNegative(this::addGateToNegative);
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
        statusBar.setStyle("-fx-font-size: 11; -fx-text-fill: #aaaaaa; -fx-padding: 2 6 2 6;");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        spinner.setVisible(false);
        previewService.setOnUpdateStarted(() -> Platform.runLater(() -> spinner.setVisible(true)));
        previewService.setOnUpdateComplete(() -> Platform.runLater(() -> {
            spinner.setVisible(false);
            onPreviewUpdated();
        }));

        // --- Bottom toolbar ---
        Button saveBtn = new Button("Save JSON");
        saveBtn.setOnAction(e -> saveTree());
        saveBtn.setTooltip(new Tooltip("Save gate tree to JSON file (Ctrl+S)"));
        Button loadBtn = new Button("Load JSON");
        loadBtn.setOnAction(e -> loadTree());
        loadBtn.setTooltip(new Tooltip("Load gate tree from JSON file (Ctrl+O)"));
        Button exportBtn = new Button("Export CSV");
        exportBtn.setOnAction(e -> exportCsv());
        exportBtn.setTooltip(new Tooltip("Export phenotype assignments to CSV (Ctrl+E)"));

        // The bridge to the other half of the extension. Styled as the primary action on
        // this toolbar because it is the one step that is not file I/O: everything else
        // here saves or loads the gating, this one takes it somewhere new.
        umapButton = new Button("Open UMAP");
        umapButton.setStyle("-fx-base: #2563eb; -fx-text-fill: white; -fx-font-weight: bold;");
        umapButton.setDisable(true);
        umapButton.setTooltip(new Tooltip(
            "Embed these cells in a UMAP, coloured by the phenotypes above (Ctrl+U).\n"
            + "Opens pre-configured on the markers your gates use.\n"
            + "Edits to the gate tree recolour the UMAP live — no recompute needed."));
        umapButton.setOnAction(e -> openUmapWindow());

        HBox toolbarSpacer = new HBox();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8, saveBtn, loadBtn, new Separator(Orientation.VERTICAL),
            exportBtn, toolbarSpacer, umapButton);
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
            } else if (new KeyCodeCombination(KeyCode.U, KeyCombination.SHORTCUT_DOWN).match(e)) {
                openUmapWindow(); e.consume();
            }
        });

        // Style
        setStyle("-fx-background-color: #1e1e1e;");

        // Initialize from current image
        Platform.runLater(this::initializeFromImage);

        // Listen for image changes
        qupath.imageDataProperty().addListener((obs, oldImg, newImg) -> {
            Platform.runLater(this::initializeFromImage);
        });
    }

    /**
     * Build CellIndex and MarkerStats from the currently loaded image's detections.
     */
    private void initializeFromImage() {
        detachHierarchyListener();

        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) {
            clearImageState();
            editorPane.setChannelNames(markerNames);
            return;
        }

        Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) {
            clearImageState();
            editorPane.setChannelNames(markerNames);
            editorPane.setGateNode(null);
            Dialogs.showWarningNotification("FlowPath", "No detections found. Import GeoJSON cells first.");
            return;
        }

        // One read of the hierarchy: the panel, the per-compartment capability, the index
        // and the report all come from a single measurement-key sample, so the gate editor
        // can no longer offer a compartment the index resolved to nothing. The pixel
        // calibration rides along inside — it is the only thing FlowPath holds that MIRAGE
        // does not, and it is what makes ScaleVerdict possible.
        IngestResult ingest = DetectionIngest.read(detections, imageData);
        markerNames = ingest.markerNames();
        compartmentCapability = ingest.capability();
        cellIndex = ingest.index();
        ingestReport = ingest.report();

        // Compute ROI mask (if filter is enabled)
        recomputeRoiMask();

        // Compute quality mask and stats (using combined mask)
        recomputeQualityMask();
        markerStats = MarkerStats.compute(cellIndex, getCombinedMask());

        // Update UI
        editorPane.setChannelNames(markerNames);
        editorPane.setCompartmentCapability(compartmentCapability);
        editorPane.setCellIndex(cellIndex);
        editorPane.setRoiMask(cachedRoiMask);
        editorPane.setMarkerStats(markerStats);

        // Update quality filter ranges + check which QC metrics have data
        double maxArea = 0, maxTotalInt = 0, maxPerim = 0;
        boolean hasEccentricity = false, hasSolidity = false, hasPerimeter = false;
        for (int i = 0; i < cellIndex.size(); i++) {
            maxArea = Math.max(maxArea, cellIndex.getArea(i));
            maxTotalInt = Math.max(maxTotalInt, cellIndex.getTotalIntensity(i));
            double perim = cellIndex.getPerimeter(i);
            if (!Double.isNaN(perim)) {
                maxPerim = Math.max(maxPerim, perim);
                if (perim > 0) hasPerimeter = true;
            }
            if (cellIndex.getEccentricity(i) > 0) hasEccentricity = true;
            if (cellIndex.getSolidity(i) > 0 && cellIndex.getSolidity(i) < 1.0) hasSolidity = true;
        }
        qualityFilterPane.updateRanges(maxArea, maxTotalInt, maxPerim);
        qualityFilterPane.setAvailableMetrics(hasEccentricity, hasSolidity, hasPerimeter);

        // Setup preview service
        previewService.setCellIndex(cellIndex);
        previewService.setMarkerStats(markerStats);
        previewService.setRoiMask(cachedRoiMask);
        previewService.setGateTree(gateTree);
        previewService.setImageData(imageData);
        previewService.setOnStatsRecomputed(() -> {
            // Keep this pane's markerStats in sync with the preview service.
            // computeAncestorMask (line 608) and the CSV exporter (line 912+)
            // both consult this field; when the annotation filter narrows the
            // stats population, ancestors that excludeOutliers reject every
            // cell otherwise — the editor shows "No data" while the gate-engine
            // count, computed with fresh stats, still reads the true number.
            this.markerStats = previewService.getMarkerStats();
            recomputeQualityMask();
            refreshAncestorMask();
            editorPane.setMarkerStats(this.markerStats);
        });

        // Listen for annotation changes (add/remove) to recompute ROI mask
        hierarchyListener = event -> {
            // Skip events fired by our own gating update to prevent feedback loops
            if (previewService.isFiringHierarchyEvent()) return;
            if (!event.isChanging() && gateTree.isRoiFilterEnabled()) {
                Platform.runLater(() -> {
                    recomputeRoiMask();
                    refreshAncestorMask();
                    editorPane.setRoiMask(cachedRoiMask);
                    previewService.recomputeStats();
                });
            }
        };
        listenerImageData = imageData;
        imageData.getHierarchy().addListener(hierarchyListener);

        updateStatusBar();
    }

    /** Detach the hierarchy listener from whichever image it was registered on. */
    private void detachHierarchyListener() {
        if (hierarchyListener != null && listenerImageData != null) {
            listenerImageData.getHierarchy().removeListener(hierarchyListener);
        }
        hierarchyListener = null;
        listenerImageData = null;
    }

    /**
     * Drop every reference to the previous image, in this pane <em>and</em> in the
     * preview service.
     * <p>
     * Clearing only this pane's fields left the service holding the old
     * {@code CellIndex} and {@code ImageData}, which pass its non-null guard: a gate
     * edit made with no image open would then re-run gating and write PathClass
     * assignments onto the previous image's detections.
     */
    private void clearImageState() {
        cellIndex = null;
        markerStats = null;
        markerNames = Collections.emptyList();
        ingestReport = IngestReport.empty();
        cachedQualityMask = null;
        cachedRoiMask = null;
        previewService.setCellIndex(null);
        previewService.setMarkerStats(null);
        previewService.setImageData(null);
        previewService.setRoiMask(null);
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
        for (GateNode gate : gateTree.getRoots()) {
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
        gateTree.addRoot(node);
        rebuildTreeView();
        requestPreviewUpdate();
    }

    private void addGateToPositive() {
        addChildGate(0);
    }

    private void addGateToNegative() {
        addChildGate(1);
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
        GateEditorPane.applyAvailableSignal(gate, compartmentCapability);
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
        if (!gateTree.getRoots().remove(selected)) {
            removeFromTree(gateTree.getRoots(), selected);
        }

        editorPane.setGateNode(null);
        suppressTreeSelection = true;
        rebuildTreeView();
        suppressTreeSelection = false;
        requestPreviewUpdate();
    }

    private void replaceGateNode(GateNode oldNode, GateNode newNode) {
        pushUndo();
        // Replace in roots
        int rootIdx = gateTree.getRoots().indexOf(oldNode);
        if (rootIdx >= 0) {
            gateTree.getRoots().set(rootIdx, newNode);
        } else {
            replaceInTree(gateTree.getRoots(), oldNode, newNode);
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
        if (cellIndex == null || markerStats == null) return null;
        boolean[] baseMask = getCombinedMask();
        return GatingEngine.computeAncestorMask(gateTree, node, cellIndex, markerStats, baseMask);
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

    private void recomputeRoiMask() {
        if (cellIndex == null) {
            cachedRoiMask = null;
            return;
        }

        if (!gateTree.isRoiFilterEnabled()) {
            cachedRoiMask = null;
            previewService.setRoiMask(null);
            return;
        }

        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) {
            cachedRoiMask = null;
            previewService.setRoiMask(null);
            return;
        }

        List<ROI> rois = new ArrayList<>();
        for (PathObject ann : imageData.getHierarchy().getAnnotationObjects()) {
            ROI roi = ann.getROI();
            if (roi != null) rois.add(roi);
        }
        if (rois.isEmpty()) {
            cachedRoiMask = null;
            previewService.setRoiMask(null);
        } else {
            cachedRoiMask = GatingEngine.computeRoiMask(cellIndex, rois);
            previewService.setRoiMask(cachedRoiMask);
        }
    }

    private boolean[] getCombinedMask() {
        if (cachedQualityMask == null) return cachedRoiMask;
        if (cachedRoiMask == null) return cachedQualityMask;
        return GatingEngine.combineMasks(cachedQualityMask, cachedRoiMask);
    }

    private void onRoiFilterToggled() {
        gateTree.setRoiFilterEnabled(roiFilterCheckBox.isSelected());
        recomputeRoiMask();
        refreshAncestorMask();
        editorPane.setRoiMask(cachedRoiMask);
        previewService.recomputeStats();
    }

    // --- Updates ---

    private void onGateNodeChanged() {
        pushUndoCoalesced();
        treeView.refresh();
        requestPreviewUpdate();
        syncViewerChannels(currentNode);
    }

    private void onGateEnabledToggled(GateNode node) {
        pushUndoCoalesced();
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
        var log = org.slf4j.LoggerFactory.getLogger(FlowPathPane.class);
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
                if (log.isDebugEnabled()) {
                    List<String> availNames = available.stream()
                        .map(ChannelDisplayInfo::getName).toList();
                    log.debug("Viewer channel sync: no match for gate channels {} in available {}",
                        gateChannels, availNames);
                }
                return;
            }

            for (Decision d : decisions) {
                display.setChannelSelected(d.info, d.show);
            }
            if (log.isTraceEnabled()) {
                log.trace("Viewer channel sync: {} of {} channels selected (gate channels {})",
                    matchCount, available.size(), gateChannels);
            }
        } catch (Exception ex) {
            // Never let a viewer-sync failure break gate editing.
            log.warn("Failed to sync viewer channels: {}", ex.toString());
        }
    }

    private void onQualityFilterChanged() {
        if (cellIndex == null) return;
        recomputeQualityMask();
        refreshAncestorMask();
        // Recompute stats on background thread, then trigger preview update
        previewService.recomputeStats();
    }

    private void recomputeQualityMask() {
        if (cellIndex == null) {
            cachedQualityMask = null;
            return;
        }
        cachedQualityMask = GatingEngine.computeQualityMask(cellIndex, gateTree.getQualityFilter());
    }



    private void requestPreviewUpdate() {
        previewService.setGateTree(gateTree);
        previewService.requestUpdate();
    }

    private void onPreviewUpdated() {
        // Already on FX thread (called from Platform.runLater in the constructor callback)
        treeView.refresh();
        updateStatusBar();
        refreshColorByRootCombo();
        umapButton.setDisable(cellIndex == null);

        // Push the new phenotyping to the UMAP if it is open. push() is a no-op when it
        // is not, so the common case costs one boolean check rather than the snapshot
        // build — which matters because this runs after every debounced gating pass,
        // i.e. continuously while a threshold slider is being dragged.
        if (umapWindow.isShowing()) {
            PhenotypeSnapshot snap = buildSnapshot();
            if (snap != null) {
                umapWindow.push(snap);
            }
        }
    }

    // --- UMAP handoff ---

    /**
     * Open (or focus) the UMAP window on the current phenotyping.
     * <p>
     * Requires a gating pass to have completed: the snapshot carries per-cell labels, and
     * before the first pass there are none. Rather than opening an empty window, this
     * says so and leaves the user where they are.
     */
    private void openUmapWindow() {
        PhenotypeSnapshot snap = buildSnapshot();
        if (snap == null) {
            Dialogs.showWarningNotification("FlowPath",
                cellIndex == null
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
        if (cellIndex == null || markerStats == null) return null;
        GatingEngine.AssignmentResult result = previewService.getLastResult();
        if (result == null) return null;

        String[] phenotypes = result.getPhenotypes();
        int[] colors = result.getColors();
        boolean[] excluded = result.getExcluded();
        // A result produced against an older index (image switched mid-pass) would
        // mislabel every cell. Drop it and wait for the next pass instead.
        if (phenotypes.length != cellIndex.size()) return null;

        var panel = PhenotypeSnapshot.collectGatedPanel(gateTree);
        return new PhenotypeSnapshot(
                cellIndex,
                markerStats,
                markerNames != null ? markerNames : List.of(),
                compartmentCapability,
                phenotypes,
                colors,
                excluded,
                panel.markers(),
                panel.selection(),
                countGates(gateTree.getRoots()),
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

    private void refreshColorByRootCombo() {
        List<String> rootNames = new ArrayList<>();
        for (GateNode root : gateTree.getRoots()) {
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
        int total = cellIndex != null ? cellIndex.size() : 0;
        int excluded = previewService.getLastExcludedCount();
        int gateCount = countGates(gateTree.getRoots());
        String roiInfo = gateTree.isRoiFilterEnabled()
            ? " | ROI: annotations"
            : "";
        statusBar.setText(String.format("Total: %,d cells | Excluded: %,d | Gates: %d%s%s",
            total, excluded, gateCount, roiInfo, ingestWarning()));
        // The full report goes in the tooltip rather than a dialog: an ingest finding is
        // context for reading the histograms, not an event that should block the user.
        statusBar.setTooltip(cellIndex == null ? null : new Tooltip(ingestReport.describe()));
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
        if (cellIndex == null) return "";
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
            FlowPathSerializer.save(gateTree, file);
            Dialogs.showInfoNotification("FlowPath", "Saved to " + file.getName());
        } catch (Exception ex) {
            Dialogs.showErrorMessage("Save Error", ex.getMessage());
        }
    }

    private void loadTree() {
        File file = Dialogs.promptForFile("Load FlowPath", null, "JSON", ".json");
        if (file == null) return;
        try {
            pushUndo();
            gateTree = FlowPathSerializer.load(file);
            // Sync the quality filter pane to the new filter object
            qualityFilterPane.setFilter(gateTree.getQualityFilter());

            // Restore ROI filter checkbox state
            suppressRoiFilterEvents = true;
            roiFilterCheckBox.setSelected(gateTree.isRoiFilterEnabled());
            suppressRoiFilterEvents = false;
            recomputeRoiMask();

            rebuildTreeView();
            onQualityFilterChanged();
            requestPreviewUpdate();

            // Check for missing markers and warn user
            if (markerNames != null && !markerNames.isEmpty()) {
                Set<String> available = new HashSet<>(markerNames);
                Set<String> missing = new LinkedHashSet<>();
                collectGateChannels(gateTree.getRoots(), missing, available);
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

    private void exportCsv() {
        if (cellIndex == null || markerStats == null || gateTree.getRoots().isEmpty()) {
            Dialogs.showWarningNotification("FlowPath", "No gates defined or no cells loaded.");
            return;
        }

        File file = Dialogs.promptToSaveFile("Export Phenotypes", null, "gate_pheno.csv", "CSV", ".csv");
        if (file == null) return;

        try {
            GatingEngine.AssignmentResult result = GatingEngine.assignAll(
                gateTree, cellIndex, markerStats, cachedRoiMask);
            PhenotypeCsvExporter.export(file, cellIndex, result, gateTree, markerStats);
            Dialogs.showInfoNotification("FlowPath", "Exported " + file.getName());
        } catch (Exception ex) {
            Dialogs.showErrorMessage("Export Error", ex.getMessage());
        }
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
        if (gateTree.getRoots().contains(selected)) {
            gateTree.addRoot(copy);
        } else {
            // Search for the branch containing the selected gate
            for (GateNode root : gateTree.getRoots()) {
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
        undoHistory.record(gateTree);
    }

    private void pushUndoCoalesced() {
        undoHistory.recordCoalesced(gateTree);
    }

    private void undo() {
        undoHistory.undo(gateTree).ifPresent(previous -> {
            gateTree = previous;
            afterUndoRedo();
        });
    }

    private void redo() {
        undoHistory.redo(gateTree).ifPresent(next -> {
            gateTree = next;
            afterUndoRedo();
        });
    }

    private void afterUndoRedo() {
        currentNode = null;
        qualityFilterPane.setFilter(gateTree.getQualityFilter());
        editorPane.setGateNode(null);
        rebuildTreeView();
        requestPreviewUpdate();
    }

    /**
     * Clean up resources when the window is closed.
     * <p>
     * Detaching the hierarchy listener matters as much as stopping the executor:
     * the extension builds a fresh pane every time the window is reopened
     * ({@code FlowPathExtension.showGateTreeWindow}), so a listener left attached
     * keeps a discarded pane — and its whole {@code CellIndex} — reachable, and
     * keeps recomputing ROI masks for a window that is gone.
     */
    public void shutdown() {
        detachHierarchyListener();
        umapWindow.close();
        previewService.shutdown();
    }
}
