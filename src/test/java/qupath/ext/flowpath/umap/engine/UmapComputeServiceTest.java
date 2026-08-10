package qupath.ext.flowpath.umap.engine;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.umap.model.ScalingMode;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UmapComputeServiceTest {

    @BeforeAll
    static void initJfx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException already) {
            // Already started
        }
    }

    private static PathObject createCell(double... values) {
        var obj = PathObjects.createDetectionObject(
                ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
        obj.getMeasurements().put("CD45", values[0]);
        if (values.length > 1) obj.getMeasurements().put("CD8", values[1]);
        if (values.length > 2) obj.getMeasurements().put("CD4", values[2]);
        return obj;
    }

    private static CellIndex buildIndex(int n) {
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cells.add(createCell(i * 1.0));
        }
        return CellIndex.build(cells, List.of("CD45"));
    }

    /** Build a synthetic CellIndex with multiple markers (random values) for realistic UMAP tests. */
    private static CellIndex buildSyntheticIndex(int n, int markers, long seed) {
        Random rng = new Random(seed);
        List<PathObject> cells = new ArrayList<>(n);
        List<String> markerNames = new ArrayList<>();
        markerNames.add("CD45");
        if (markers > 1) markerNames.add("CD8");
        if (markers > 2) markerNames.add("CD4");
        for (int i = 0; i < n; i++) {
            double[] vals = new double[Math.min(3, markers)];
            for (int j = 0; j < vals.length; j++) vals[j] = rng.nextGaussian();
            cells.add(createCell(vals));
        }
        return CellIndex.build(cells, markerNames);
    }

    /** Wait for a UMAP computation to complete (success or error) up to the given timeout. */
    private static UmapResult runAndWait(UmapComputeService service, CellIndex idx,
                                         UmapParameters params, int maxCells,
                                         List<String> statusLog, long timeoutSec) throws Exception {
        AtomicReference<UmapResult> resultRef = new AtomicReference<>();
        AtomicReference<String> errRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        if (statusLog != null) {
            service.setOnStatusUpdate(msg -> { statusLog.add(msg); });
        }
        service.setOnComplete(r -> { resultRef.set(r); latch.countDown(); });
        service.setOnError(e -> { errRef.set(e); latch.countDown(); });
        service.compute(idx, params, maxCells);
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for UMAP completion");
        }
        if (errRef.get() != null) throw new AssertionError("UMAP errored: " + errRef.get());
        return resultRef.get();
    }

    @Test
    void shutdownNullsCallbacks() {
        var service = new UmapComputeService();
        AtomicReference<String> errorMsg = new AtomicReference<>();
        service.setOnError(errorMsg::set);
        service.shutdown();
        // After shutdown, callbacks should be nulled - verify no NPE on next access
        assertDoesNotThrow(() -> service.shutdown());
    }

    @Test
    void cachedResultInitiallyNull() {
        var service = new UmapComputeService();
        assertNull(service.getCachedResult());
        service.shutdown();
    }

    @Test
    void cancelWithNoRunningTaskDoesNotThrow() {
        var service = new UmapComputeService();
        assertDoesNotThrow(service::cancel);
        service.shutdown();
    }

    @Test
    void autoSubsampleHardCapConstant() {
        // The hard cap is the contract. Running a real 1M-cell UMAP in unit tests is
        // infeasible (memory/time), so we pin the constant value and verify the
        // formula by inspection in the integration test below.
        assertEquals(150_000, UmapComputeService.AUTO_SUBSAMPLE_HARD_CAP,
                "AUTO_SUBSAMPLE_HARD_CAP should be exactly 150K");
    }

    @Test
    void adaptiveEpochsSubstitutedFromSentinel() throws Exception {
        // Use the sentinel ADAPTIVE_EPOCHS from defaults() and confirm the compute
        // service substitutes a concrete count derived from training-N. With 10_500
        // cells (between 10K and 50K), defaultsFor returns 100 epochs.
        var idx = buildSyntheticIndex(10_500, 3, 7L);
        var service = new UmapComputeService();
        try {
            UmapResult result = runAndWait(service, idx, UmapParameters.defaults(), 0, null, 180);
            assertNotNull(result);
            assertEquals(100, result.getParams().epochs(),
                    "Adaptive epochs at N=10_500 should resolve to 100");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cancelDoesNotResetCancelledFlagToFalse() throws Exception {
        // Regression for the historical race where cancel() ended with
        // `cancelled = false`, defeating the in-flight task's staleness check.
        // The contract is that cancel() leaves the flag latched at true; only
        // a fresh compute() may clear it (under a new generation).
        var service = new UmapComputeService();
        try {
            service.cancel();
            Field f = UmapComputeService.class.getDeclaredField("cancelled");
            f.setAccessible(true);
            assertTrue(f.getBoolean(service),
                    "cancel() must leave the 'cancelled' flag true; resetting it to "
                            + "false would cause an in-flight task to keep running");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cancelThenComputeSuppressesFirstComputeOnComplete() throws Exception {
        // Compute -> Cancel -> Compute: the *first* compute's onComplete must not
        // fire after the second compute starts. The generation counter is what
        // protects against that — verify it works end-to-end.
        var idx = buildSyntheticIndex(10_500, 3, 13L);
        var service = new UmapComputeService();
        try {
            AtomicInteger completeCount = new AtomicInteger(0);
            AtomicBoolean secondStarted = new AtomicBoolean(false);
            CountDownLatch secondDone = new CountDownLatch(1);

            service.setOnComplete(r -> {
                int n = completeCount.incrementAndGet();
                if (secondStarted.get()) secondDone.countDown();
            });
            service.setOnError(e -> {
                // ignore — first compute may surface a cancellation; the contract
                // is only that onComplete is not called for it.
            });

            var params = new UmapParameters(15, 0.1, 1.0, 30, 5);
            // Kick off first compute, then immediately cancel and start a second.
            service.compute(idx, params, 0);
            service.cancel();
            secondStarted.set(true);
            service.compute(idx, params, 0);

            assertTrue(secondDone.await(180, TimeUnit.SECONDS),
                    "Second compute should complete");
            // Allow any straggler runLater callbacks to flush.
            CountDownLatch flush = new CountDownLatch(1);
            Platform.runLater(flush::countDown);
            flush.await(5, TimeUnit.SECONDS);

            assertEquals(1, completeCount.get(),
                    "Only the second compute's onComplete should fire; the first "
                            + "must be suppressed by the generation guard");
        } finally {
            service.shutdown();
        }
    }

    /** Synthetic index whose markers live on very different scales (1x, 1000x, 0.01x). */
    private static CellIndex buildMultiScaleIndex(int n, long seed) {
        Random rng = new Random(seed);
        List<PathObject> cells = new ArrayList<>(n);
        double[] scales = {1.0, 1000.0, 0.01};
        for (int i = 0; i < n; i++) {
            double[] vals = new double[3];
            for (int j = 0; j < 3; j++) vals[j] = rng.nextGaussian() * scales[j];
            cells.add(createCell(vals));
        }
        return CellIndex.build(cells, List.of("CD45", "CD8", "CD4"));
    }

    @Test
    void zscoreScalingProducesFiniteEmbedding() throws Exception {
        // With markers on 1x/1000x/0.01x scales, z-scoring should still yield a
        // clean, all-finite embedding (the scaler must not introduce NaN/Inf and
        // the 4-arg compute path must run end to end).
        var idx = buildMultiScaleIndex(10_500, 99L);
        var service = new UmapComputeService();
        try {
            AtomicReference<UmapResult> resultRef = new AtomicReference<>();
            AtomicReference<String> errRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnComplete(r -> { resultRef.set(r); latch.countDown(); });
            service.setOnError(e -> { errRef.set(e); latch.countDown(); });

            service.compute(idx, new UmapParameters(15, 0.1, 1.0, 30, 5), 0, ScalingMode.ZSCORE);
            assertTrue(latch.await(180, TimeUnit.SECONDS), "z-score UMAP should complete");
            assertNull(errRef.get(), "z-score UMAP should not error: " + errRef.get());

            UmapResult result = resultRef.get();
            assertNotNull(result);
            assertEquals(10_500, result.size());
            double[] xs = result.getUmapXRaw();
            double[] ys = result.getUmapYRaw();
            for (int i = 0; i < xs.length; i++) {
                assertTrue(Double.isFinite(xs[i]) && Double.isFinite(ys[i]),
                        "embedding coordinate " + i + " must be finite");
            }
        } finally {
            service.shutdown();
        }
    }

    @Test
    void emitsPhaseTimingLogs() throws Exception {
        // SMILE's UMAP attempts spectral initialization for N < 10_000. Spectral
        // layout requires LAPACK native code (excluded from our shadow JAR), so it
        // hangs on small datasets in the test environment. Use N >= 10_000 so SMILE
        // falls back to random initialization.
        var idx = buildSyntheticIndex(10_500, 3, 1L);
        var service = new UmapComputeService();
        try {
            List<String> statusLog = new CopyOnWriteArrayList<>();
            var params = new UmapParameters(15, 0.1, 1.0, 30, 5);
            UmapResult result = runAndWait(service, idx, params, 0, statusLog, 180);
            assertNotNull(result);
            assertEquals(10_500, result.size());

            // Allow Platform.runLater queue to flush
            CountDownLatch flush = new CountDownLatch(1);
            Platform.runLater(flush::countDown);
            flush.await(5, TimeUnit.SECONDS);

            boolean hasNN = statusLog.stream().anyMatch(s -> s.matches(".*NN-Descent: \\d+ms.*"));
            boolean hasFit = statusLog.stream().anyMatch(s -> s.matches(".*UMAP\\.fit: \\d+ms.*"));
            assertTrue(hasNN, "Expected an 'NN-Descent: <ms>ms' status message; got: " + statusLog);
            assertTrue(hasFit, "Expected an 'UMAP.fit: <ms>ms' status message; got: " + statusLog);
        } finally {
            service.shutdown();
        }
    }
}
