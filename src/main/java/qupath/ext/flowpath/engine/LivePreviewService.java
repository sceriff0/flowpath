package qupath.ext.flowpath.engine;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debounced live-preview service that re-runs gating on a background thread
 * whenever gate parameters change and pushes the results back onto the
 * JavaFX Application Thread so the QuPath viewer updates.
 */
public class LivePreviewService {

    private static final long DEBOUNCE_MS = 80;

    private final PauseTransition debounce;
    private final ExecutorService executor;

    private volatile GateTree gateTree;
    private volatile CellIndex cellIndex;
    private volatile MarkerStats markerStats;
    private volatile ImageData<?> imageData;
    private volatile boolean[] roiMask;

    /** Optional callback fired after MarkerStats is recomputed (e.g., to refresh UI sliders). */
    private volatile Runnable onStatsRecomputed;

    /** Optional callback fired when gating computation starts (e.g., to show a spinner). */
    private volatile Runnable onUpdateStarted;

    /** Optional callback fired after cell classifications are applied (e.g., to refresh tree counts). */
    private volatile Runnable onUpdateComplete;

    /** Count of excluded cells from the most recent gating run (updated on FX thread). */
    private int lastExcludedCount;

    /** Which enabled root's colors to display (-1 = default/last root). */
    private volatile int colorRootIndex = -1;

    /** Cached last result for lightweight recoloring without re-gating. */
    private volatile GatingEngine.AssignmentResult lastResult;
    private volatile CellIndex lastIndex;
    private volatile ImageData<?> lastImageData;

    /**
     * Guard flag set while {@link #applyResult} is firing a hierarchy changed event.
     * Listeners can check {@link #isFiringHierarchyEvent()} to avoid reacting to
     * events that originated from our own gating update.
     */
    private volatile boolean firingHierarchyEvent;

    public LivePreviewService() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "flowpath-preview");
            t.setDaemon(true);
            return t;
        });
        this.debounce = new PauseTransition(Duration.millis(DEBOUNCE_MS));
        this.debounce.setOnFinished(e -> submitGatingWork());
    }

    // ---- setters ----

    public void setGateTree(GateTree tree) {
        this.gateTree = tree;
    }

    public void setCellIndex(CellIndex index) {
        this.cellIndex = index;
    }

    public void setMarkerStats(MarkerStats stats) {
        this.markerStats = stats;
    }

    public MarkerStats getMarkerStats() {
        return this.markerStats;
    }

    public void setImageData(ImageData<?> imageData) {
        this.imageData = imageData;
    }

    public void setRoiMask(boolean[] roiMask) {
        this.roiMask = roiMask;
    }

    public void setOnStatsRecomputed(Runnable onStatsRecomputed) {
        this.onStatsRecomputed = onStatsRecomputed;
    }

    public void setOnUpdateStarted(Runnable onUpdateStarted) {
        this.onUpdateStarted = onUpdateStarted;
    }

    public void setOnUpdateComplete(Runnable onUpdateComplete) {
        this.onUpdateComplete = onUpdateComplete;
    }

    /**
     * The most recent gating result, or {@code null} before the first run completes.
     * <p>
     * Exposed so the gating pane can hand the assignment to the UMAP view without
     * re-running {@link GatingEngine#assignAll} over every cell. The returned object is
     * immutable in practice — the service replaces the reference on each run rather than
     * mutating it — so a caller that holds one is holding a consistent picture of the
     * gating at the moment it read it.
     */
    public GatingEngine.AssignmentResult getLastResult() {
        return lastResult;
    }

    public int getLastExcludedCount() {
        return lastExcludedCount;
    }

    /**
     * Set which enabled root's colors to display.
     * Use -1 for default (last root's color).
     * Triggers an immediate recolor without re-running the gating engine.
     * No-op if the index hasn't changed.
     */
    public void setColorRootIndex(int index) {
        if (this.colorRootIndex == index) return;
        this.colorRootIndex = index;
        recolorCells();
    }

    public int getColorRootIndex() {
        return colorRootIndex;
    }

    /**
     * Returns {@code true} while this service is firing a hierarchy changed event
     * as part of applying gating results. Hierarchy listeners should check this
     * to avoid feedback loops.
     */
    public boolean isFiringHierarchyEvent() {
        return firingHierarchyEvent;
    }

    // ---- public API ----

    /**
     * Request a gating update. The actual computation is debounced by 80 ms so
     * rapid successive calls (e.g., slider drags) are coalesced.
     */
    public void requestUpdate() {
        if (Platform.isFxApplicationThread()) {
            debounce.playFromStart();
        } else {
            Platform.runLater(debounce::playFromStart);
        }
    }

    /**
     * Recompute {@link MarkerStats} from the current {@link CellIndex} using
     * the quality mask derived from the gate tree's quality filter, then trigger
     * a gating update.
     */
    public void recomputeStats() {
        if (cellIndex == null || gateTree == null || executor.isShutdown()) {
            return;
        }
        final boolean[] roi = this.roiMask != null ? this.roiMask.clone() : null;
        final CellIndex idx = this.cellIndex;
        final QualityFilter rawQf = gateTree.getQualityFilter();
        if (rawQf == null) {
            requestUpdate();
            return;
        }
        final QualityFilter qf = rawQf.deepCopy();
        executor.submit(() -> {
            boolean[] qualityMask = GatingEngine.computeQualityMask(idx, qf);
            boolean[] mask = roi != null ? GatingEngine.combineMasks(qualityMask, roi) : qualityMask;
            MarkerStats recomputed = MarkerStats.compute(idx, mask);
            this.markerStats = recomputed;
            if (onStatsRecomputed != null) {
                Platform.runLater(onStatsRecomputed);
            }
            requestUpdate();
        });
    }

    /**
     * Shut down the background executor. Call this when the extension window
     * is closed or the image changes.
     */
    public void shutdown() {
        debounce.stop();
        executor.shutdown();
    }

    // ---- internal ----

    private void submitGatingWork() {
        if (executor.isShutdown()) return;
        // Capture references to avoid races
        final GateTree originalTree = this.gateTree;
        final CellIndex index = this.cellIndex;
        final MarkerStats stats = this.markerStats;
        final ImageData<?> data = this.imageData;
        final boolean[] roi = this.roiMask != null ? this.roiMask.clone() : null;

        if (originalTree == null || index == null || stats == null || data == null) {
            return;
        }

        // Deep-copy the tree so the background thread works on an immutable snapshot
        final GateTree tree = originalTree.deepCopy();

        if (onUpdateStarted != null) {
            Platform.runLater(onUpdateStarted);
        }

        executor.submit(() -> {
            GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats, roi);

            Platform.runLater(() -> {
                // Discard result if the live tree changed (e.g. undo/redo) while we were computing
                if (this.gateTree != originalTree) return;
                // Transfer counts from the snapshot back to the live tree for UI display
                GateTree.transferCounts(originalTree.getRoots(), tree.getRoots());
                applyResult(result, index, data, true);
            });
        });
    }

    private void applyResult(GatingEngine.AssignmentResult result, CellIndex index, ImageData<?> data,
                             boolean fireUpdateComplete) {
        // Cache for lightweight recoloring
        this.lastResult = result;
        this.lastIndex = index;
        this.lastImageData = data;

        String[] phenotypes = result.getPhenotypes();
        boolean[] excluded = result.getExcluded();
        int[] defaultColors = result.getColors();
        java.util.List<int[]> perRoot = result.getPerRootColors();
        int n = phenotypes.length;

        // Count total excluded cells for status display
        int excCount = 0;
        for (boolean ex : excluded) if (ex) excCount++;
        this.lastExcludedCount = excCount;

        Map<String, PathClass> classCache = new HashMap<>();

        // Build cache and force-update colors.
        // When a specific root is selected, use that root's per-cell colors instead of the default.
        int activeRoot = this.colorRootIndex;
        Map<String, Integer> colorByName = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (!excluded[i] && phenotypes[i] != null) {
                int color;
                if (activeRoot >= 0 && perRoot != null && activeRoot < perRoot.size()) {
                    color = perRoot.get(activeRoot)[i];
                } else {
                    color = defaultColors[i];
                }
                colorByName.put(phenotypes[i], color);
            }
        }
        for (var entry : colorByName.entrySet()) {
            int packed = entry.getValue();
            int qupathColor = ColorUtils.toQuPathColor(packed);
            PathClass pc = PathClass.fromString(entry.getKey(), qupathColor);
            pc.setColor(qupathColor);  // Force-update cached PathClass color
            classCache.put(entry.getKey(), pc);
        }

        // Near-invisible PathClass for excluded cells (avoids red "Unclassified" default)
        int excludedColor = ColorTools.packRGB(20, 20, 20);
        PathClass excludedClass = PathClass.fromString("Excluded", excludedColor);
        excludedClass.setColor(excludedColor);

        boolean anyChanged = false;
        boolean colorMutated = false;
        for (int i = 0; i < n; i++) {
            PathObject obj = index.getObject(i);
            if (obj == null) {
                continue;
            }
            PathClass newClass = excluded[i] ? excludedClass : classCache.get(phenotypes[i]);
            if (!java.util.Objects.equals(obj.getPathClass(), newClass)) {
                obj.setPathClass(newClass);
                anyChanged = true;
            } else if (newClass != null && obj.getPathClass() != null
                       && obj.getPathClass().getColor() != newClass.getColor()) {
                // Same PathClass reference but color was mutated — force reassign
                obj.setPathClass(null);
                obj.setPathClass(newClass);
                colorMutated = true;
            }
        }

        // Fire hierarchy event if classifications changed or colors were mutated
        if (anyChanged || colorMutated) {
            firingHierarchyEvent = true;
            try {
                data.getHierarchy().fireHierarchyChangedEvent(this);
            } finally {
                firingHierarchyEvent = false;
            }
        }

        if (fireUpdateComplete && onUpdateComplete != null) {
            onUpdateComplete.run();
        }
    }

    /**
     * Re-apply colors from the stored last result without re-running the gating engine.
     * Used when the user switches the color-by-root selection.
     *
     * <p>PathClass.fromString caches by name — the same Java object is returned for
     * the same name string. Mutating its color via setColor() doesn't change the
     * object reference on each cell's PathClass field, so QuPath's per-object rendering
     * cache isn't invalidated. We force-reassign via null→class on each cell to trigger
     * QuPath's per-object change tracking, then fire a hierarchy event for tile-level
     * cache invalidation.</p>
     */
    private void recolorCells() {
        final GatingEngine.AssignmentResult result = this.lastResult;
        final CellIndex index = this.lastIndex;
        final ImageData<?> data = this.lastImageData;
        if (result == null || index == null || data == null) return;

        Platform.runLater(() -> {
            String[] phenotypes = result.getPhenotypes();
            boolean[] excluded = result.getExcluded();
            int[] defaultColors = result.getColors();
            java.util.List<int[]> perRoot = result.getPerRootColors();
            int n = phenotypes.length;
            int activeRoot = this.colorRootIndex;

            // Build color map and update cached PathClass colors
            Map<String, Integer> colorByName = new HashMap<>();
            for (int i = 0; i < n; i++) {
                if (!excluded[i] && phenotypes[i] != null) {
                    int color;
                    if (activeRoot >= 0 && perRoot != null && activeRoot < perRoot.size()) {
                        color = perRoot.get(activeRoot)[i];
                    } else {
                        color = defaultColors[i];
                    }
                    colorByName.put(phenotypes[i], color);
                }
            }

            Map<String, PathClass> classCache = new HashMap<>();
            for (var entry : colorByName.entrySet()) {
                int packed = entry.getValue();
                int qupathColor = ColorUtils.toQuPathColor(packed);
                PathClass pc = PathClass.fromString(entry.getKey(), qupathColor);
                pc.setColor(qupathColor);
                classCache.put(entry.getKey(), pc);
            }

            // Force-reassign PathClass on each cell via null→class to trigger
            // QuPath's per-object change tracking (same-reference setPathClass
            // is silently ignored by QuPath)
            for (int i = 0; i < n; i++) {
                if (excluded[i]) continue;
                PathObject obj = index.getObject(i);
                if (obj == null) continue;
                PathClass pc = classCache.get(phenotypes[i]);
                if (pc != null) {
                    obj.setPathClass(null);
                    obj.setPathClass(pc);
                }
            }

            // Fire hierarchy event for tile-level cache invalidation
            firingHierarchyEvent = true;
            try {
                data.getHierarchy().fireHierarchyChangedEvent(this);
            } finally {
                firingHierarchyEvent = false;
            }
        });
    }
}
