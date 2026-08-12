package qupath.ext.flowpath.umap.engine;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.umap.model.ScalingMode;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.lang.reflect.Field;
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
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    /** The panel {@code createCell} used to hard-code, in the same order. */
    private static final List<String> PANEL = List.of("CD45", "CD8", "CD4");

    /**
     * A synthetic index of {@code markers} markers of standard-normal values.
     * <p>
     * Values are drawn cell-major, marker-minor — the order the per-cell construction
     * loop this replaced used — so a given seed still yields exactly the same population.
     */
    private static CellIndex buildSyntheticIndex(int n, int markers, long seed) {
        return randomIndex(n, seed, PANEL.subList(0, Math.min(3, markers)), new double[]{1, 1, 1});
    }

    /** Synthetic index whose markers live on very different scales (1x, 1000x, 0.01x). */
    private static CellIndex buildMultiScaleIndex(int n, long seed) {
        return randomIndex(n, seed, PANEL, new double[]{1.0, 1000.0, 0.01});
    }

    private static CellIndex randomIndex(int n, long seed, List<String> markers, double[] scales) {
        Random rng = new Random(seed);
        double[][] values = new double[markers.size()][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < markers.size(); j++) {
                values[j][i] = rng.nextGaussian() * scales[j];
            }
        }
        Cells cells = Cells.of(n);
        for (int j = 0; j < markers.size(); j++) {
            cells.marker(markers.get(j), values[j]);
        }
        return cells.build();
    }

    /** Wait for a UMAP computation to terminate, whatever the outcome. */
    private static UmapResult runAndWait(UmapComputeService service, CellIndex idx,
                                         UmapParameters params, int maxCells,
                                         List<String> statusLog, long timeoutSec) throws Exception {
        AtomicReference<UmapOutcome> outcomeRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        if (statusLog != null) {
            service.setOnStatusUpdate(msg -> { statusLog.add(msg); });
        }
        service.setOnOutcome(o -> { outcomeRef.set(o); latch.countDown(); });
        service.compute(idx, params, maxCells);
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for UMAP completion");
        }
        UmapOutcome outcome = outcomeRef.get();
        if (!(outcome instanceof UmapOutcome.Succeeded succeeded)) {
            throw new AssertionError("UMAP did not succeed: " + outcome.describe());
        }
        return succeeded.result();
    }

    /** A trivial embedding over {@code idx}, for work stubs that never run a real UMAP. */
    private static UmapResult stubResult(CellIndex idx) {
        return new UmapResult(new double[idx.size()], new double[idx.size()],
                idx.getObjects(), idx.getMarkerNames(), new UmapParameters(15, 0.1, 1.0, 50, 5));
    }

    /** A service whose callbacks run inline and whose embedding is replaced by {@code work}. */
    private static UmapComputeService serviceRunning(UmapComputeService.EmbeddingWork work) {
        return new UmapComputeService(Runnable::run, work);
    }

    @Test
    void shutdownNullsCallbacks() {
        var service = new UmapComputeService();
        service.setOnOutcome(o -> { });
        service.shutdown();
        // After shutdown, callbacks should be nulled - verify no NPE on next access
        assertDoesNotThrow(() -> service.shutdown());
    }

    // --- Terminal outcome ----------------------------------------------------

    @Test
    void anErrorInTheRunBodyEndsTheRunInsteadOfVanishing() {
        // The bug this whole seam exists to close. SMILE's UMAP.fit reaches for an
        // ARPACK native FlowPath deliberately does not ship and fails with
        // NoClassDefFoundError — an Error, matched by neither catch (Exception) nor
        // catch (OutOfMemoryError). It escaped the Runnable into a Future nobody read,
        // no callback fired, and the UI waited forever for a call that could not come.
        //
        // Injected through the work seam rather than provoked with a real 500-cell
        // UMAP: what is under test is the lifecycle around the work, not SMILE.
        var index = Cells.of(4).marker("CD45", i -> i).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        var service = serviceRunning((idx, p, max, mode, gen) -> {
            throw new NoClassDefFoundError("org/bytedeco/arpackng/global/arpack");
        });
        try {
            service.setOnOutcome(outcomes::add);
            service.compute(index, UmapParameters.defaults(), 0);
            awaitOutcomes(outcomes, 1);

            assertEquals(1, outcomes.size(), "an Error must produce exactly one outcome, not silence");
            assertInstanceOf(UmapOutcome.Failed.class, outcomes.get(0));
            var failed = (UmapOutcome.Failed) outcomes.get(0);
            assertEquals("java.lang.NoClassDefFoundError", failed.throwableClass());
            assertTrue(failed.describe().contains("java.lang.NoClassDefFoundError"),
                    "the throwable's class name must reach the message: " + failed.describe());
            assertTrue(failed.describe().contains("arpack"),
                    "the Error's own message must survive: " + failed.describe());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void outOfMemoryKeepsItsTailoredAdvice() {
        // "UMAP failed: OutOfMemoryError" is true and useless; the advice is the point.
        var index = Cells.of(4).marker("CD45", i -> i).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        var service = serviceRunning((idx, p, max, mode, gen) -> {
            throw new OutOfMemoryError("Java heap space");
        });
        try {
            service.setOnOutcome(outcomes::add);
            service.compute(index, UmapParameters.defaults(), 0);
            awaitOutcomes(outcomes, 1);

            var failed = assertInstanceOf(UmapOutcome.Failed.class, outcomes.get(0));
            assertTrue(failed.reason().contains("subsampling"), failed.reason());
            assertTrue(failed.reason().contains("Preferences"), failed.reason());
            assertEquals("java.lang.OutOfMemoryError", failed.throwableClass());
        } finally {
            service.shutdown();
        }
    }

    /**
     * Every way a run can end delivers exactly one outcome — the promise the old
     * three-callback seam made in prose and broke on seven paths.
     */
    @ParameterizedTest
    @EnumSource(UmapOutcome.Kind.class)
    void everyTerminatingPathDeliversExactlyOneOutcomePerComputeCall(UmapOutcome.Kind kind)
            throws Exception {
        var index = Cells.of(4).marker("CD45", i -> i).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        AtomicReference<UmapComputeService> self = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean release = new AtomicBoolean();

        var service = serviceRunning((idx, p, max, mode, gen) -> {
            int run = runs.incrementAndGet();
            switch (kind) {
                case SUCCEEDED -> { }
                case FAILED -> throw new NoClassDefFoundError("org/bytedeco/arpackng/global/arpack");
                // Cancel from inside the body: the run is stopped while it still holds
                // the thread, which is the shape a user's Cancel click really takes.
                case CANCELLED -> self.get().cancel();
                // Hold the first run open so the second one demonstrably overtakes it.
                case SUPERSEDED -> {
                    if (run == 1) {
                        started.set(true);
                        spinUntil(release);
                    }
                }
            }
            return UmapOutcome.succeeded(stubResult(idx));
        });
        self.set(service);

        try {
            service.setOnOutcome(outcomes::add);
            service.compute(index, UmapParameters.defaults(), 0);

            int expectedComputeCalls = 1;
            if (kind == UmapOutcome.Kind.SUPERSEDED) {
                spinUntil(started);
                service.compute(index, UmapParameters.defaults(), 0);
                release.set(true);
                expectedComputeCalls = 2;
            }
            awaitOutcomes(outcomes, expectedComputeCalls);

            assertEquals(expectedComputeCalls, outcomes.size(),
                    "one outcome per compute() call, no more and no less: " + outcomes);
            assertEquals(kind, outcomes.get(0).kind(),
                    "the first run must end as " + kind + " but ended as " + outcomes.get(0).describe());
        } finally {
            release.set(true);
            service.shutdown();
        }
    }

    @Test
    void aRunCancelledBeforeItStartsStillEnds() {
        // Future.cancel on a task the executor has not reached means the body never
        // runs at all, so no `finally` inside it can save the run. Occupying the single
        // worker with a first run makes the second one queue behind it, and cancelling
        // then must still produce an outcome.
        var index = Cells.of(4).marker("CD45", i -> i).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        AtomicBoolean release = new AtomicBoolean();
        AtomicBoolean occupied = new AtomicBoolean();
        AtomicInteger bodies = new AtomicInteger();

        var service = serviceRunning((idx, p, max, mode, gen) -> {
            if (bodies.incrementAndGet() == 1) {
                occupied.set(true);
                spinUntil(release);
            }
            return UmapOutcome.succeeded(stubResult(idx));
        });
        try {
            service.setOnOutcome(outcomes::add);
            service.compute(index, UmapParameters.defaults(), 0);   // occupies the worker
            spinUntil(occupied);
            service.compute(index, UmapParameters.defaults(), 0);   // queued behind it
            service.cancel();                                       // never gets to run
            release.set(true);

            awaitOutcomes(outcomes, 2);
            assertEquals(2, outcomes.size(), "both compute() calls must end: " + outcomes);
            assertEquals(UmapOutcome.Kind.SUPERSEDED, outcomes.get(0).kind());
            assertEquals(UmapOutcome.Kind.CANCELLED, outcomes.get(1).kind());
            assertEquals(1, bodies.get(), "the second run's body must never have executed");
        } finally {
            release.set(true);
            service.shutdown();
        }
    }

    @Test
    void aThrowingConsumerDoesNotPoisonTheNextRun() {
        // compute() claims its generation, ends the previous run, and only then builds
        // its own delivery. A consumer that throws while that previous run is being
        // ended would otherwise escape compute() with the new generation claimed, no
        // delivery in existence for it and nothing submitted — a run that could never
        // terminate. The synchronous delivery executor here is what makes that
        // reachable; production's Platform.runLater hides it, which is exactly why the
        // guarantee must not depend on which executor is passed.
        var index = Cells.of(4).marker("CD45", i -> i).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        AtomicBoolean release = new AtomicBoolean();
        AtomicBoolean started = new AtomicBoolean();
        AtomicInteger bodies = new AtomicInteger();

        var service = serviceRunning((idx, p, max, mode, gen) -> {
            if (bodies.incrementAndGet() == 1) {
                started.set(true);
                spinUntil(release);
            }
            return UmapOutcome.succeeded(stubResult(idx));
        });
        try {
            service.setOnOutcome(outcome -> {
                outcomes.add(outcome);
                if (outcome.kind() == UmapOutcome.Kind.SUPERSEDED) {
                    throw new IllegalStateException("consumer blew up on the superseded run");
                }
            });

            service.compute(index, UmapParameters.defaults(), 0);   // occupies the worker
            spinUntil(started);
            // Ends run 1 -> the consumer throws inside cancel(), inside compute().
            assertDoesNotThrow(() -> service.compute(index, UmapParameters.defaults(), 0));
            release.set(true);

            awaitOutcomes(outcomes, 2);
            assertEquals(2, outcomes.size(), "both runs must end: " + outcomes);
            assertEquals(UmapOutcome.Kind.SUPERSEDED, outcomes.get(0).kind());
            assertEquals(UmapOutcome.Kind.SUCCEEDED, outcomes.get(1).kind(),
                    "the run that followed the throwing consumer must still get its outcome");
        } finally {
            release.set(true);
            service.shutdown();
        }
    }

    @Test
    void computeAfterShutdownFailsLoudlyRatherThanThrowingAtTheCaller() {
        // The caller has already shown a busy state by the time it calls compute();
        // a RejectedExecutionException thrown back at it leaves that state with nothing
        // to clear it. The rejection is an outcome like any other.
        var index = Cells.of(4).marker("CD45", i -> i).build();
        var service = serviceRunning((idx, p, max, mode, gen) -> UmapOutcome.succeeded(stubResult(idx)));
        service.shutdown();

        assertDoesNotThrow(() -> service.compute(index, UmapParameters.defaults(), 0));

        var failed = assertInstanceOf(UmapOutcome.Failed.class, service.getLastOutcome(),
                "a rejected submit must still be recorded, even with no consumer left");
        assertEquals("java.util.concurrent.RejectedExecutionException", failed.throwableClass());
    }

    @Test
    void aFailureWithNoConsumerIsStillRecorded() {
        // shutdown() nulls the consumer deliberately — a disposed UI must not be called
        // back into. The reason the run ended must survive that anyway.
        var index = Cells.of(4).marker("CD45", i -> i).build();
        var service = serviceRunning((idx, p, max, mode, gen) -> {
            throw new NoClassDefFoundError("org/bytedeco/arpackng/global/arpack");
        });
        try {
            service.compute(index, UmapParameters.defaults(), 0);   // no consumer registered
            spinUntil(() -> service.getLastOutcome() != null);

            var failed = assertInstanceOf(UmapOutcome.Failed.class, service.getLastOutcome());
            assertEquals("java.lang.NoClassDefFoundError", failed.throwableClass());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void tooFewCellsIsAFailedOutcomeRatherThanAnEmptyEmbedding() {
        // Exercises the real embedding body's precondition refusal — a failure with no
        // throwable behind it, and the one path that never reaches SMILE.
        var index = Cells.of(2).marker("CD45", 1.0, 2.0).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        var service = new UmapComputeService(Runnable::run, null);
        try {
            service.setOnOutcome(outcomes::add);
            service.compute(index, UmapParameters.defaults(), 0);
            awaitOutcomes(outcomes, 1);

            var failed = assertInstanceOf(UmapOutcome.Failed.class, outcomes.get(0));
            assertTrue(failed.reason().contains("Too few cells"), failed.reason());
            assertFalse(failed.fromThrowable(), "a refused precondition is not a throw");
        } finally {
            service.shutdown();
        }
    }

    /** Spin until {@code flag} is set, failing rather than hanging if it never is. */
    private static void spinUntil(AtomicBoolean flag) {
        spinUntil(flag::get);
    }

    private static void spinUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting");
            Thread.onSpinWait();
        }
    }

    private static void awaitOutcomes(List<UmapOutcome> outcomes, int expected) {
        spinUntil(() -> outcomes.size() >= expected);
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

            service.setOnOutcome(outcome -> {
                // Only a success counts here: the first compute is expected to end as
                // cancelled or superseded, and the contract is that it never reports
                // an embedding of its own.
                if (!outcome.isSuccess()) return;
                completeCount.incrementAndGet();
                if (secondStarted.get()) secondDone.countDown();
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

    @Test
    void zscoreScalingProducesFiniteEmbedding() throws Exception {
        // With markers on 1x/1000x/0.01x scales, z-scoring should still yield a
        // clean, all-finite embedding (the scaler must not introduce NaN/Inf and
        // the 4-arg compute path must run end to end).
        var idx = buildMultiScaleIndex(10_500, 99L);
        var service = new UmapComputeService();
        try {
            AtomicReference<UmapOutcome> outcomeRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnOutcome(o -> { outcomeRef.set(o); latch.countDown(); });

            service.compute(idx, new UmapParameters(15, 0.1, 1.0, 30, 5), 0, ScalingMode.ZSCORE);
            assertTrue(latch.await(180, TimeUnit.SECONDS), "z-score UMAP should complete");
            var succeeded = assertInstanceOf(UmapOutcome.Succeeded.class, outcomeRef.get(),
                    "z-score UMAP should succeed: " + outcomeRef.get().describe());

            UmapResult result = succeeded.result();
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
