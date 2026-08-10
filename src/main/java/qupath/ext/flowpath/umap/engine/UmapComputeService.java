package qupath.ext.flowpath.umap.engine;

import javafx.application.Platform;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.umap.model.ScalingMode;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;
import smile.manifold.UMAP;
import smile.graph.NearestNeighborGraph;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Background UMAP computation service using SMILE.
 * Supports configurable subsampling, OOM protection, and result caching.
 */
public class UmapComputeService {

    /**
     * Upper bound on the training-set size chosen by Auto mode. Beyond this point,
     * UMAP runtime grows roughly linearly while marginal embedding quality is
     * minimal, and 150K is the sweet spot for ~10–40 markers on a developer
     * machine. Adjust if memory headroom or workload mix changes.
     */
    public static final int AUTO_SUBSAMPLE_HARD_CAP = 150_000;

    private final ExecutorService executor;
    private volatile UmapResult cachedResult;
    private volatile Future<?> runningTask;
    private volatile boolean cancelled;
    private final AtomicInteger generation = new AtomicInteger(0);

    private volatile Consumer<UmapResult> onComplete;
    private volatile Consumer<String> onError;
    private volatile Consumer<String> onStatusUpdate;

    public UmapComputeService() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "flowpath-umap-compute");
            t.setDaemon(true);
            return t;
        });
    }

    public void setOnComplete(Consumer<UmapResult> cb) { this.onComplete = cb; }
    public void setOnError(Consumer<String> cb) { this.onError = cb; }
    public void setOnStatusUpdate(Consumer<String> cb) { this.onStatusUpdate = cb; }

    /**
     * Compute UMAP embedding with raw (unscaled) features. Equivalent to calling
     * {@link #compute(CellIndex, UmapParameters, int, ScalingMode)} with
     * {@link ScalingMode#NONE}; retained for callers/tests that predate feature
     * scaling. New UI paths pass an explicit mode (default {@link ScalingMode#ZSCORE}).
     */
    public void compute(CellIndex cellIndex, UmapParameters params, int maxCells) {
        compute(cellIndex, params, maxCells, ScalingMode.NONE);
    }

    /**
     * Compute UMAP embedding. Runs in background thread.
     *
     * @param cellIndex   cell data
     * @param params      UMAP parameters
     * @param maxCells    maximum cells before subsampling (0 = no limit)
     * @param scalingMode per-marker feature scaling applied before UMAP. UMAP is
     *                    distance-based, so without scaling high-magnitude markers
     *                    dominate the embedding; {@link ScalingMode#ZSCORE} is the
     *                    recommended default for multiplexed imaging. The scaler is
     *                    fit on the training matrix and reused for the projection of
     *                    held-out cells so both share one coordinate frame.
     */
    public void compute(CellIndex cellIndex, UmapParameters params, int maxCells, ScalingMode scalingMode) {
        final ScalingMode mode = scalingMode == null ? ScalingMode.ZSCORE : scalingMode;
        final int myGeneration = generation.incrementAndGet();
        cancel();
        cancelled = false;

        runningTask = executor.submit(() -> {
            try {
                int n = cellIndex.size();
                int m = cellIndex.getMarkerNames().length;

                // Memory estimation
                long estimatedBytes = (long) n * m * 8 + (long) n * params.k() * 32 + (long) n * 2 * 8;
                long freeMemory = Runtime.getRuntime().maxMemory()
                        - Runtime.getRuntime().totalMemory()
                        + Runtime.getRuntime().freeMemory();

                // Determine if subsampling is needed
                int computeN = n;
                int[] sampleIndices = null;
                boolean subsampled = false;

                if (maxCells > 0 && n > maxCells) {
                    // Fixed mode: user-specified limit
                    postStatus("Subsampling %,d -> %,d cells...".formatted(n, maxCells));
                    sampleIndices = stratifiedSample(cellIndex, maxCells);
                    computeN = sampleIndices.length;
                    subsampled = true;
                } else if (maxCells < 0 || estimatedBytes > freeMemory * 0.6) {
                    // Auto mode (maxCells == -1) or memory pressure
                    int memoryLimit = (int) Math.min(n, Math.max(10000,
                            freeMemory * 0.4 / (m * 8 + params.k() * 32 + 2 * 8)));
                    int autoLimit = Math.min(memoryLimit, AUTO_SUBSAMPLE_HARD_CAP);
                    if (autoLimit < n) {
                        postStatus("Auto-subsampling %,d -> %,d cells (based on available memory)..."
                                .formatted(n, autoLimit));
                        sampleIndices = stratifiedSample(cellIndex, autoLimit);
                        computeN = sampleIndices.length;
                        subsampled = true;
                    }
                }

                // Build matrix
                postStatus("Preparing data matrix (%,d cells x %d markers)...".formatted(computeN, m));
                double[][] matrix;
                double[] imputationMeans = null;
                if (subsampled) {
                    imputationMeans = new double[m];
                    matrix = extractSubMatrix(cellIndex, sampleIndices, imputationMeans);
                } else {
                    matrix = cellIndex.toMatrix();
                }

                // Fit feature scaler on the training matrix and apply it in place.
                // UMAP is distance-based; without scaling, high-magnitude markers
                // dominate the neighbor graph. The same scaler is reused to project
                // held-out cells below, so training and projection share one frame.
                if (mode != ScalingMode.NONE) {
                    postStatus("Scaling features (%s)...".formatted(mode.label()));
                }
                FeatureScaler scaler = FeatureScaler.fit(matrix, mode);
                scaler.transformInPlace(matrix);

                // Clamp k to dataset size (NN-descent requires k < n)
                final int finalComputeN = computeN;
                int effectiveK = Math.min(params.k(), computeN - 1);
                if (effectiveK < 2) {
                    // Snapshot the volatile callback so a concurrent shutdown/setOn*
                    // cannot null it between the null-check and the dereference.
                    Consumer<String> err = onError;
                    if (err != null) {
                        Platform.runLater(() -> err.accept(
                                "Too few cells (%d) for UMAP. Need at least 3.".formatted(finalComputeN)));
                    }
                    return;
                }

                // Build kNN graph (always use approximate NN-descent for speed)
                postStatus("Building neighbor graph (k=%d)...".formatted(effectiveK));
                long nnStart = System.nanoTime();
                NearestNeighborGraph nng = NearestNeighborGraph.descent(matrix, effectiveK);
                long nnMs = (System.nanoTime() - nnStart) / 1_000_000L;
                String nnMsg = "NN-Descent: %dms".formatted(nnMs);
                postStatus(nnMsg);
                System.err.println(nnMsg);

                if (cancelled || generation.get() != myGeneration) return;

                // Resolve adaptive epochs from training-N if the caller used the sentinel.
                int effectiveEpochs = params.epochs() == UmapParameters.ADAPTIVE_EPOCHS
                        ? UmapParameters.defaultsFor(computeN).epochs()
                        : params.epochs();

                // Run UMAP with pre-computed graph
                postStatus("Optimizing layout (epochs=%d)...".formatted(effectiveEpochs));
                var options = new UMAP.Options(effectiveK, 2, effectiveEpochs,
                        1.0, params.minDist(), params.spread(), params.negativeSamples(), 1.0, 1.0);
                long fitStart = System.nanoTime();
                double[][] embedding = UMAP.fit(matrix, nng, options);
                long fitMs = (System.nanoTime() - fitStart) / 1_000_000L;
                String fitMsg = "NN-Descent: %dms | UMAP.fit: %dms".formatted(nnMs, fitMs);
                postStatus(fitMsg);
                System.err.println(fitMsg);

                // Build result arrays
                double[] umapX = new double[n];
                double[] umapY = new double[n];

                // The training matrix and neighbour graph are dead once fit returns.
                // Releasing them here matters: the projection stage below allocates a
                // second [samples][markers] matrix plus its index, and peak memory
                // otherwise spans both at once.
                matrix = null;
                nng = null;

                if (subsampled) {
                    // Fill sampled cells
                    for (int i = 0; i < sampleIndices.length; i++) {
                        umapX[sampleIndices[i]] = embedding[i][0];
                        umapY[sampleIndices[i]] = embedding[i][1];
                    }

                    // Project remaining cells via kNN
                    postStatus("Projecting remaining %,d cells...".formatted(n - computeN));
                    long projStart = System.nanoTime();
                    projectRemaining(cellIndex, sampleIndices, embedding, umapX, umapY, imputationMeans, scaler);
                    long projMs = (System.nanoTime() - projStart) / 1_000_000L;
                    String projMsg = "NN-Descent: %dms | UMAP.fit: %dms | Project: %dms"
                            .formatted(nnMs, fitMs, projMs);
                    postStatus(projMsg);
                    System.err.println(projMsg);
                } else {
                    for (int i = 0; i < n; i++) {
                        umapX[i] = embedding[i][0];
                        umapY[i] = embedding[i][1];
                    }
                }

                if (cancelled || generation.get() != myGeneration) return;

                // Record the epochs actually used (resolve any adaptive sentinel) so
                // downstream consumers see a real value rather than the sentinel.
                UmapParameters resolvedParams = params.epochs() == effectiveEpochs
                        ? params
                        : new UmapParameters(params.k(), params.minDist(), params.spread(),
                                effectiveEpochs, params.negativeSamples());
                UmapResult result = new UmapResult(umapX, umapY, cellIndex.getObjects(),
                        cellIndex.getMarkerNames(), resolvedParams);
                cachedResult = result;

                if (cancelled || generation.get() != myGeneration) return;
                // Snapshot the volatile callback so shutdown() nulling it after the
                // generation check still leaves a stable reference for the lambda.
                Consumer<UmapResult> done = onComplete;
                if (done != null) {
                    Platform.runLater(() -> {
                        if (generation.get() == myGeneration) done.accept(result);
                    });
                }

            } catch (OutOfMemoryError e) {
                // Free memory immediately
                System.gc();
                Consumer<String> err = onError;
                if (err != null) {
                    Platform.runLater(() -> err.accept(
                            "Out of memory. Try enabling subsampling or reducing cell count.\n"
                                    + "You can also increase QuPath's memory in Edit > Preferences."));
                }
            } catch (Exception e) {
                if (!cancelled && generation.get() == myGeneration) {
                    Consumer<String> err = onError;
                    if (err != null) {
                        Platform.runLater(() -> {
                            if (generation.get() == myGeneration)
                                err.accept("UMAP failed: " + e.getMessage());
                        });
                    }
                }
            }
        });
    }

    /**
     * Stratified random sample preserving phenotype proportions.
     */
    private int[] stratifiedSample(CellIndex cellIndex, int targetN) {
        int n = cellIndex.size();
        if (targetN >= n) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            return all;
        }

        // Group by PathClass
        var classGroups = new java.util.LinkedHashMap<String, java.util.List<Integer>>();
        for (int i = 0; i < n; i++) {
            var pc = cellIndex.getObject(i).getPathClass();
            String key = pc != null ? pc.getName() : "__unclassified__";
            classGroups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(i);
        }

        Random rng = new Random(42); // reproducible
        int[] sample = new int[targetN];
        int idx = 0;

        // Proportional allocation per class
        for (var entry : classGroups.entrySet()) {
            var indices = entry.getValue();
            int classN = (int) Math.round((double) indices.size() / n * targetN);
            classN = Math.max(1, Math.min(classN, indices.size()));
            classN = Math.min(classN, targetN - idx);

            java.util.Collections.shuffle(indices, rng);
            for (int i = 0; i < classN && idx < targetN; i++) {
                sample[idx++] = indices.get(i);
            }
        }

        // Fill remaining slots randomly (using seeded RNG for reproducibility)
        if (idx < targetN) {
            boolean[] used = new boolean[n];
            for (int i = 0; i < idx; i++) used[sample[i]] = true;
            // Collect unused indices and shuffle with the seeded RNG
            var unused = new java.util.ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                if (!used[i]) unused.add(i);
            }
            java.util.Collections.shuffle(unused, rng);
            for (int i = 0; i < unused.size() && idx < targetN; i++) {
                sample[idx++] = unused.get(i);
            }
        }

        Arrays.sort(sample, 0, idx);
        return idx == targetN ? sample : Arrays.copyOf(sample, idx);
    }

    /**
     * Extract sub-matrix for sampled cells, returning imputation means for reuse.
     */
    private double[][] extractSubMatrix(CellIndex cellIndex, int[] indices, double[] imputationMeans) {
        int m = cellIndex.getMarkerNames().length;
        double[][] matrix = new double[indices.length][m];
        for (int j = 0; j < m; j++) {
            // Raw accessor: read-only gather into `matrix`. The defensive
            // getMarkerValues would clone the full column on every iteration,
            // copying the entire dataset to build a subsample of it.
            double[] markerVals = cellIndex.getMarkerValuesRaw(j);

            // Compute mean for NaN replacement from sampled cells only
            double sum = 0;
            int count = 0;
            for (int idx : indices) {
                double v = markerVals[idx];
                if (!Double.isNaN(v)) { sum += v; count++; }
            }
            double mean = count > 0 ? sum / count : 0.0;
            imputationMeans[j] = mean;

            for (int i = 0; i < indices.length; i++) {
                double v = markerVals[indices[i]];
                matrix[i][j] = Double.isNaN(v) ? mean : v;
            }
        }
        return matrix;
    }

    /**
     * Project non-sampled cells by finding their k nearest neighbors among the sampled cells
     * (using a KD-tree for fast lookup) and averaging those neighbors' UMAP coordinates
     * weighted by inverse distance.
     */
    private void projectRemaining(CellIndex cellIndex, int[] sampleIndices,
                                  double[][] sampleEmbedding,
                                  double[] umapX, double[] umapY,
                                  double[] imputationMeans,
                                  FeatureScaler scaler) {
        int n = cellIndex.size();
        int m = cellIndex.getMarkerNames().length;
        int knn = Math.min(5, sampleIndices.length);
        if (knn == 0) return;

        boolean[] isSampled = new boolean[n];
        for (int idx : sampleIndices) isSampled[idx] = true;

        // Reference all marker columns once. These are the BACKING arrays, not
        // copies: the defensive getMarkerValues clones N doubles per marker, so
        // this loop used to allocate a complete N x M duplicate of the dataset —
        // the very 1.2 GB allocation the comment further down explains we avoid
        // for the query matrix. Read-only use only.
        double[][] allMarkerValues = new double[m][];
        for (int j = 0; j < m; j++) {
            allMarkerValues[j] = cellIndex.getMarkerValuesRaw(j);
        }

        // Build sample marker matrix for kNN lookup (with NaN imputation), then
        // apply the SAME scaler used for UMAP training. The sample embedding was
        // produced from the scaled training matrix, so the KD-tree it is queried
        // against must live in that scaled space too — otherwise neighbor lookups
        // for held-out cells would use a different metric than training.
        double[][] sampleMarkers = new double[sampleIndices.length][m];
        for (int j = 0; j < m; j++) {
            for (int s = 0; s < sampleIndices.length; s++) {
                double v = allMarkerValues[j][sampleIndices[s]];
                sampleMarkers[s][j] = Double.isNaN(v) ? imputationMeans[j] : v;
            }
        }
        scaler.transformInPlace(sampleMarkers);

        // Build the neighbour index for the projection queries.
        postStatus("Building spatial index for projection...");
        NeighborIndex tree = new NeighborIndex(sampleMarkers);

        // Build a flat list of original-cell indices that need projection. We
        // intentionally do NOT pre-allocate a `double[remaining][m]` query matrix
        // here — that allocation can dwarf the actual UMAP working set on large
        // images (e.g. 5M cells x 30 markers x 8 bytes = 1.2 GB). Each worker
        // builds its own per-cell marker vector on the fly inside the parallel
        // stream; the per-thread allocation is m doubles, reused across the
        // worker's lifetime via a thread-local would be possible but the JIT
        // already escape-analyzes this aggressively.
        int remaining = 0;
        for (int i = 0; i < n; i++) if (!isSampled[i]) remaining++;
        int[] queryIndices = new int[remaining];
        int qi = 0;
        for (int i = 0; i < n; i++) {
            if (!isSampled[i]) queryIndices[qi++] = i;
        }

        // Snapshot dimensions/marker arrays as final locals so the parallel lambda
        // captures stable references rather than re-reading the field each iter.
        final int dims = m;
        final double[][] markerCols = allMarkerValues;
        final double[] meansSnapshot = imputationMeans;

        // Parallel kNN projection using KD-tree — each query is independent
        final int totalRemaining = remaining;
        final int progressStep = Math.max(1, remaining / 10);
        AtomicInteger progressCount = new AtomicInteger(0);

        IntStream.range(0, remaining).parallel().forEach(q -> {
            if (cancelled) return;

            int ci = queryIndices[q];

            // Build the query vector on the fly (per-iteration allocation,
            // typically m=10..40 doubles — JIT-friendly and short-lived), then
            // scale it with the training-fit scaler so it matches sampleMarkers.
            double[] query = new double[dims];
            for (int j = 0; j < dims; j++) {
                double v = markerCols[j][ci];
                query[j] = Double.isNaN(v) ? meansSnapshot[j] : v;
            }
            scaler.transformRowInPlace(query);

            // kNN query (returns squared distances, ascending)
            int[] neighbors = new int[knn];
            double[] dists = new double[knn];
            tree.kNearest(query, knn, neighbors, dists);

            // Weighted average of neighbor embeddings
            double totalWeight = 0;
            double wx = 0, wy = 0;
            for (int ki = 0; ki < knn; ki++) {
                if (neighbors[ki] < 0) continue; // unfilled slot
                double w = 1.0 / (Math.sqrt(dists[ki]) + 1e-10);
                wx += w * sampleEmbedding[neighbors[ki]][0];
                wy += w * sampleEmbedding[neighbors[ki]][1];
                totalWeight += w;
            }
            if (totalWeight > 0) {
                umapX[ci] = wx / totalWeight;
                umapY[ci] = wy / totalWeight;
            }
            // else: leave at 0.0 (no valid neighbors found)

            // Progress update every ~10%
            int done = progressCount.incrementAndGet();
            if (done % progressStep == 0) {
                int pct = (int) ((double) done / totalRemaining * 100);
                postStatus("Projecting remaining cells... %d%%".formatted(pct));
            }
        });
    }

    private void postStatus(String msg) {
        // Snapshot the volatile callback BEFORE the runLater lambda. Without this
        // snapshot, a concurrent shutdown() or setOnStatusUpdate(null) between the
        // null-check and the lambda body would NPE on the FX thread.
        Consumer<String> cb = onStatusUpdate;
        if (cb != null) {
            Platform.runLater(() -> cb.accept(msg));
        }
    }

    public void cancel() {
        cancelled = true;
        if (runningTask != null && !runningTask.isDone()) {
            runningTask.cancel(true);
        }
    }

    public UmapResult getCachedResult() { return cachedResult; }

    public void shutdown() {
        cancel();
        executor.shutdownNow();
        onComplete = null;
        onError = null;
        onStatusUpdate = null;
    }
}
