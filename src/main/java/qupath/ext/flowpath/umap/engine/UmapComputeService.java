package qupath.ext.flowpath.umap.engine;

import javafx.application.Platform;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.umap.model.ScalingMode;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;
import smile.manifold.UMAP;
import smile.graph.NearestNeighborGraph;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Background UMAP computation service using SMILE.
 * Supports configurable subsampling, OOM protection, and result caching.
 *
 * <h2>One terminal outcome per run</h2>
 * Every {@link #compute} call ends in exactly one {@link UmapOutcome} handed to the
 * consumer registered with {@link #setOnOutcome}. That is the whole contract, and it
 * is upheld structurally: the run body's outcome is delivered from a {@code finally}
 * through a one-shot {@link TerminalDelivery}, so no {@code return}, {@code throw} or
 * staleness guard inside it can produce silence, and no race can produce two
 * callbacks. Progress messages travel on the separate, non-terminal
 * {@link #setOnStatusUpdate} channel and never end a run.
 * <p>
 * The catch is {@code Throwable}, not {@code Exception}. SMILE used to reach for ARPACK
 * natives this extension deliberately does not ship and fail with
 * {@code NoClassDefFoundError} — an {@link Error}, which the old
 * {@code catch (Exception)} / {@code catch (OutOfMemoryError)} pair matched neither
 * of. It escaped the {@code Runnable} into a {@code Future} nobody read, and the UI
 * waited forever. {@link EmbeddingInitialisation} now keeps SMILE off that path
 * entirely, so the {@code Error} no longer arrives; the {@code Throwable} catch stays
 * because the lesson was about which failures a seam can afford not to see, not about
 * that one library.
 */
public class UmapComputeService {

    /**
     * Upper bound on the training-set size chosen by Auto mode. Beyond this point,
     * UMAP runtime grows roughly linearly while marginal embedding quality is
     * minimal, and 150K is the sweet spot for ~10–40 markers on a developer
     * machine. Adjust if memory headroom or workload mix changes.
     */
    public static final int AUTO_SUBSAMPLE_HARD_CAP = 150_000;

    /**
     * The unit of work one {@code compute(...)} call performs, isolated so that the
     * lifecycle around it — staleness, one-shot delivery, {@code Throwable} handling —
     * can be tested without running a real embedding. A test that needs a 500-cell
     * UMAP to prove {@code Error} handling is testing the wrong thing.
     * <p>
     * Implementations return the outcome they reached and may throw anything; the
     * caller turns a throw into {@link UmapOutcome.Failed} and a stale run into
     * {@link UmapOutcome.Cancelled} or {@link UmapOutcome.Superseded}.
     */
    @FunctionalInterface
    interface EmbeddingWork {
        UmapOutcome run(CellIndex cellIndex, UmapParameters params, int maxCells,
                        ScalingMode mode, int runGeneration) throws Exception;
    }

    /**
     * The run that currently owns the service's terminal channel: its generation (so a
     * late ending can be reconciled) and its one-shot delivery.
     */
    private record PendingRun(int generation, TerminalDelivery delivery) {}

    private final ExecutorService executor;
    private final Executor deliveryExecutor;
    private final EmbeddingWork work;
    private volatile UmapResult cachedResult;
    private volatile UmapOutcome lastOutcome;
    private volatile Future<?> runningTask;
    private volatile PendingRun pendingRun;
    private volatile boolean cancelled;
    private final AtomicInteger generation = new AtomicInteger(0);

    private volatile Consumer<UmapOutcome> onOutcome;
    private volatile Consumer<String> onStatusUpdate;

    public UmapComputeService() {
        this(Platform::runLater, null);
    }

    /**
     * Visible for tests: {@code deliveryExecutor} decides where callbacks run
     * ({@code Runnable::run} for a toolkit-free test), and {@code work} substitutes the
     * embedding computation itself. A null {@code work} means the real one.
     */
    UmapComputeService(Executor deliveryExecutor, EmbeddingWork work) {
        this.deliveryExecutor = Objects.requireNonNull(deliveryExecutor, "deliveryExecutor");
        this.work = work == null ? this::runEmbedding : work;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "flowpath-umap-compute");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Register the consumer of terminal outcomes. Exactly one outcome per
     * {@link #compute} call reaches it, on the delivery executor (the FX thread in
     * production).
     */
    public void setOnOutcome(Consumer<UmapOutcome> cb) { this.onOutcome = cb; }

    public void setOnStatusUpdate(Consumer<String> cb) { this.onStatusUpdate = cb; }

    /**
     * The outcome of the most recently terminated run, recorded whether or not anyone
     * was listening. After {@link #shutdown()} nulls the consumer there is no callback
     * to receive an in-flight run's ending; this keeps that ending on the record
     * instead of losing it.
     */
    public UmapOutcome getLastOutcome() { return lastOutcome; }

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

        // Registered only after cancel() above has had its chance to end the PREVIOUS
        // run — otherwise the new run would terminate itself before it started. That
        // ordering is why TerminalDelivery.deliver does not throw once it has claimed a
        // run: anything escaping the cancel() above would escape compute() with this
        // generation already claimed and no delivery in existence for it.
        TerminalDelivery delivery = new TerminalDelivery(
                deliveryExecutor, () -> onOutcome, this::recordOutcome);
        pendingRun = new PendingRun(myGeneration, delivery);

        try {
            runningTask = executor.submit(() -> {
                UmapOutcome outcome = null;
                try {
                    outcome = work.run(cellIndex, params, maxCells, mode, myGeneration);
                } catch (OutOfMemoryError e) {
                    // Free memory immediately, then report with the tailored advice —
                    // "UMAP failed: OutOfMemoryError" tells the user nothing actionable.
                    System.gc();
                    outcome = UmapOutcome.failed(
                            "Out of memory. Try enabling subsampling or reducing cell count.\n"
                                    + "You can also increase QuPath's memory in Edit > Preferences.", e);
                } catch (Throwable t) {
                    // Throwable, not Exception: SMILE's missing-ARPACK failure is an
                    // Error, and an Error that escapes this Runnable is captured into a
                    // Future nobody reads — the silence this class exists to prevent.
                    outcome = UmapOutcome.failed("UMAP failed: " + describeThrowable(t), t);
                } finally {
                    // Structural, not incidental: however the body left — returned from
                    // any of its guards, threw, or (a bug here) produced nothing — the
                    // run terminates exactly once, right here.
                    deliver(delivery, outcome == null
                            ? UmapOutcome.failed("UMAP ended without producing an outcome")
                            : outcome, myGeneration);
                }
            });
        } catch (RejectedExecutionException afterShutdown) {
            // compute() after shutdown(). Reporting it through the same channel keeps
            // "one call, one outcome" true even here; letting it propagate would leave
            // a caller that had already shown a busy state with nothing to clear it.
            deliver(delivery, UmapOutcome.failed(
                    "UMAP service has been shut down", afterShutdown), myGeneration);
        }
    }

    /**
     * Decide what a run is allowed to report, given what happened while it ran.
     * <p>
     * The generation guard used to be spelled {@code if (stale) return;} in three
     * places, which is why a superseded run was indistinguishable from a broken one.
     * It now selects <em>which</em> outcome is delivered rather than <em>whether</em>
     * one is: a run overtaken by a newer {@code compute(...)} reports
     * {@link UmapOutcome.Superseded} (the newer run owns the UI, so the consumer must
     * do nothing), and a run the user stopped reports {@link UmapOutcome.Cancelled}
     * (nothing else is coming, so the consumer must react). Checking generation first
     * matters: after cancel-then-compute both conditions hold, and it is the newer run
     * that owns the UI.
     * <p>
     * A cancelled run reports {@code Cancelled} even if it also failed — the user asked
     * it to stop, and an error dialog for the interruption they caused is noise.
     */
    private UmapOutcome reconcile(UmapOutcome raw, int myGeneration) {
        if (generation.get() != myGeneration) return UmapOutcome.superseded();
        if (cancelled) return UmapOutcome.cancelled();
        return raw;
    }

    private void deliver(TerminalDelivery delivery, UmapOutcome raw, int myGeneration) {
        delivery.deliver(reconcile(raw, myGeneration));
    }

    /**
     * Bookkeeping run inside the one-shot guard, so it happens exactly once per run and
     * even when no consumer is registered — which is precisely the case after
     * {@link #shutdown()}, where an in-flight failure would otherwise vanish.
     */
    private void recordOutcome(UmapOutcome outcome) {
        lastOutcome = outcome;
        if (outcome instanceof UmapOutcome.Failed failed && onOutcome == null) {
            System.err.println("FlowPath UMAP (no outcome consumer registered): "
                    + failed.describe());
        }
    }

    /** Class name always, message when there is one — {@code Error}s often have none. */
    private static String describeThrowable(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank()
                ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + msg;
    }

    /**
     * The staleness guard's answer, from inside the run body. Mirrors
     * {@link #reconcile} so an abandoned run names its own reason; reconcile then
     * confirms it against the state at delivery time.
     */
    private UmapOutcome staleOutcome() {
        return cancelled ? UmapOutcome.cancelled() : UmapOutcome.superseded();
    }

    /**
     * The real embedding computation: subsample, scale, build the neighbour graph, fit,
     * project the held-out cells. Never delivers anything itself — it returns the
     * outcome it reached and lets {@link #compute} own the single terminal delivery.
     */
    private UmapOutcome runEmbedding(CellIndex cellIndex, UmapParameters params,
                                     int maxCells, ScalingMode mode, int myGeneration) {
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
        int effectiveK = Math.min(params.k(), computeN - 1);
        if (effectiveK < 2) {
            return UmapOutcome.failed(
                    "Too few cells (%d) for UMAP. Need at least 3.".formatted(computeN));
        }

        // Build kNN graph (always use approximate NN-descent for speed)
        postStatus("Building neighbor graph (k=%d)...".formatted(effectiveK));
        long nnStart = System.nanoTime();
        NearestNeighborGraph nng = NearestNeighborGraph.descent(matrix, effectiveK);
        long nnMs = (System.nanoTime() - nnStart) / 1_000_000L;
        String nnMsg = "NN-Descent: %dms".formatted(nnMs);
        postStatus(nnMsg);
        System.err.println(nnMsg);

        if (cancelled || generation.get() != myGeneration) return staleOutcome();

        // Resolve adaptive epochs from training-N if the caller used the sentinel.
        int effectiveEpochs = params.epochs() == UmapParameters.ADAPTIVE_EPOCHS
                ? UmapParameters.defaultsFor(computeN).epochs()
                : params.epochs();

        // Decide the embedding initialisation before SMILE gets the chance to. Left to
        // itself, UMAP.fit sends any small connected graph to a spectral layout that
        // needs an ARPACK native this extension does not ship, and a few-hundred-cell
        // run — the first one a user makes — died there with NoClassDefFoundError.
        EmbeddingInitialisation init = EmbeddingInitialisation.forGraph(nng);
        if (init.isSteered()) {
            postStatus("Steering initialisation to PCA (spectral needs absent natives)...");
        }

        // Run UMAP with pre-computed graph
        postStatus("Optimizing layout (epochs=%d)...".formatted(effectiveEpochs));
        var options = new UMAP.Options(effectiveK, 2, effectiveEpochs,
                1.0, params.minDist(), params.spread(), params.negativeSamples(), 1.0, 1.0);
        long fitStart = System.nanoTime();
        double[][] embedding = UMAP.fit(matrix, init.graph(), options);
        // The detached node carried no edges into the layout, so what fit returned for it
        // is an artefact. Replace it with where its real neighbours say it belongs.
        init.impute(embedding);
        int imputedRow = init.detachedNode().orElse(-1);
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
        init = null;

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

        if (cancelled || generation.get() != myGeneration) return staleOutcome();

        // Record the epochs actually used (resolve any adaptive sentinel) so
        // downstream consumers see a real value rather than the sentinel.
        UmapParameters resolvedParams = params.epochs() == effectiveEpochs
                ? params
                : new UmapParameters(params.k(), params.minDist(), params.spread(),
                        effectiveEpochs, params.negativeSamples());
        UmapResult result = new UmapResult(umapX, umapY, cellIndex.getObjects(),
                cellIndex.getMarkerNames(), resolvedParams);
        cachedResult = result;

        // Report the imputation as a cell index into cellIndex, not a training-matrix
        // row: the row number means nothing to a consumer that never saw the subsample.
        if (imputedRow < 0) return UmapOutcome.succeeded(result);
        return UmapOutcome.succeeded(result,
                subsampled ? sampleIndices[imputedRow] : imputedRow);
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

            // Weighted average of neighbor embeddings. The blend is shared with
            // EmbeddingInitialisation's repair of its detached node — same question,
            // "where does a cell that skipped the optimisation belong", so it must not
            // be two formulas. `dists` is a scratch array, converted in place to the
            // Euclidean distances the blend expects.
            for (int ki = 0; ki < knn; ki++) dists[ki] = Math.sqrt(dists[ki]);
            double[] placed = new double[2];
            if (InverseDistanceBlend.place(neighbors, dists, sampleEmbedding, -1, placed)) {
                umapX[ci] = placed[0];
                umapY[ci] = placed[1];
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

    /**
     * Progress, not termination. A status message says a run is still going; it can
     * arrive any number of times, including zero, and never ends a run.
     */
    private void postStatus(String msg) {
        // Snapshot the volatile callback BEFORE the delivery lambda. Without this
        // snapshot, a concurrent shutdown() or setOnStatusUpdate(null) between the
        // null-check and the lambda body would NPE on the FX thread.
        Consumer<String> cb = onStatusUpdate;
        if (cb != null) {
            deliveryExecutor.execute(() -> cb.accept(msg));
        }
    }

    /**
     * Abandon the run in flight, if any. Safe to call repeatedly and safe to call when
     * nothing is running: the one-shot delivery absorbs the extra calls.
     * <p>
     * The abandoned run is terminated here rather than left to notice on its own.
     * That is necessary, not merely prompt: {@code Future.cancel} on a task the
     * executor has not started yet means the task body never runs at all, so its
     * {@code finally} never fires and the run would end in exactly the silence this
     * class exists to prevent. When the body <em>is</em> already running, this call
     * wins and the body's own ending becomes a no-op.
     * <p>
     * Whether the consumer sees {@link UmapOutcome.Cancelled} or
     * {@link UmapOutcome.Superseded} is decided by {@link #reconcile}: {@code compute}
     * bumps the generation before calling this, so a run being replaced reports
     * superseded and only a genuine user cancel reports cancelled.
     */
    public void cancel() {
        cancelled = true;
        Future<?> task = runningTask;
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
        PendingRun run = pendingRun;
        if (run != null) {
            deliver(run.delivery(), UmapOutcome.cancelled(), run.generation());
        }
    }

    public UmapResult getCachedResult() { return cachedResult; }

    /**
     * Tear the service down. The callbacks are nulled deliberately — a run still in
     * flight must not call back into a UI that is being disposed — so an in-flight run
     * ends with nobody listening. Its outcome is still recorded on
     * {@link #getLastOutcome()} and, if it failed, logged, so teardown hides a defect
     * from the developer no more than it hides one from the user.
     */
    public void shutdown() {
        // Nulled BEFORE cancel(), which now terminates the run in flight: the point of
        // teardown is that nothing calls back into the disposed UI, and the recording
        // in TerminalDelivery happens either way.
        onOutcome = null;
        onStatusUpdate = null;
        cancel();
        executor.shutdownNow();
    }
}
