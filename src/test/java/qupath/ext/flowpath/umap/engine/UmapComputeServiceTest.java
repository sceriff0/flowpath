package qupath.ext.flowpath.umap.engine;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.umap.model.ScalingMode;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.classes.PathClass;
import qupath.ext.flowpath.umap.testing.Embeddings;
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

    // Note on fixture sizes. Four of the end-to-end tests below used to build 10,500
    // cells for no reason of their own: below 10,000 SMILE tried a spectral
    // initialisation that needed an absent ARPACK native, so an oversized fixture was
    // the only way to reach an embedding at all. EmbeddingInitialisation now owns that
    // decision, so they run at the size a user's first UMAP really is — faster, and
    // covering the path that actually gets taken.

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
        service.compute(Embeddings.of(idx), params, maxCells);
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
    private static UmapResult stubResult(EmbeddingFeatures.Selected idx) {
        return new UmapResult(new double[idx.cellCount()], new double[idx.cellCount()],
                idx.objects(), idx.featureNames(), new UmapParameters(15, 0.1, 1.0, 50, 5));
    }

    /**
     * The outcome a work stub returns: a trivial embedding plus the report the type
     * refuses to succeed without. These tests are about the lifecycle around the work,
     * so the run they describe degraded nothing.
     */
    private static UmapOutcome stubSuccess(EmbeddingFeatures.Selected idx) {
        return UmapOutcome.succeeded(stubResult(idx),
                EmbeddingReport.training(idx, null)
                        .completedWith(EmbeddingReport.Steering.none(),
                                EmbeddingReport.Projection.none()));
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
        var index = Cells.of(4).marker("CD45", i -> i).marker("CD3", i -> i * 2.0).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        var service = serviceRunning((idx, p, max, mode, gen) -> {
            throw new NoClassDefFoundError("org/bytedeco/arpackng/global/arpack");
        });
        try {
            service.setOnOutcome(outcomes::add);
            service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);
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
        var index = Cells.of(4).marker("CD45", i -> i).marker("CD3", i -> i * 2.0).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        var service = serviceRunning((idx, p, max, mode, gen) -> {
            throw new OutOfMemoryError("Java heap space");
        });
        try {
            service.setOnOutcome(outcomes::add);
            service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);
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
        var index = Cells.of(4).marker("CD45", i -> i).marker("CD3", i -> i * 2.0).build();
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
            return stubSuccess(idx);
        });
        self.set(service);

        try {
            service.setOnOutcome(outcomes::add);
            service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);

            int expectedComputeCalls = 1;
            if (kind == UmapOutcome.Kind.SUPERSEDED) {
                spinUntil(started);
                service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);
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
        var index = Cells.of(4).marker("CD45", i -> i).marker("CD3", i -> i * 2.0).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        AtomicBoolean release = new AtomicBoolean();
        AtomicBoolean occupied = new AtomicBoolean();
        AtomicInteger bodies = new AtomicInteger();

        var service = serviceRunning((idx, p, max, mode, gen) -> {
            if (bodies.incrementAndGet() == 1) {
                occupied.set(true);
                spinUntil(release);
            }
            return stubSuccess(idx);
        });
        try {
            service.setOnOutcome(outcomes::add);
            service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);   // occupies the worker
            spinUntil(occupied);
            service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);   // queued behind it
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
        var index = Cells.of(4).marker("CD45", i -> i).marker("CD3", i -> i * 2.0).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        AtomicBoolean release = new AtomicBoolean();
        AtomicBoolean started = new AtomicBoolean();
        AtomicInteger bodies = new AtomicInteger();

        var service = serviceRunning((idx, p, max, mode, gen) -> {
            if (bodies.incrementAndGet() == 1) {
                started.set(true);
                spinUntil(release);
            }
            return stubSuccess(idx);
        });
        try {
            service.setOnOutcome(outcome -> {
                outcomes.add(outcome);
                if (outcome.kind() == UmapOutcome.Kind.SUPERSEDED) {
                    throw new IllegalStateException("consumer blew up on the superseded run");
                }
            });

            service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);   // occupies the worker
            spinUntil(started);
            // Ends run 1 -> the consumer throws inside cancel(), inside compute().
            assertDoesNotThrow(() -> service.compute(Embeddings.of(index), UmapParameters.defaults(), 0));
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
        var index = Cells.of(4).marker("CD45", i -> i).marker("CD3", i -> i * 2.0).build();
        var service = serviceRunning((idx, p, max, mode, gen) -> stubSuccess(idx));
        service.shutdown();

        assertDoesNotThrow(() -> service.compute(Embeddings.of(index), UmapParameters.defaults(), 0));

        var failed = assertInstanceOf(UmapOutcome.Failed.class, service.getLastOutcome(),
                "a rejected submit must still be recorded, even with no consumer left");
        assertEquals("java.util.concurrent.RejectedExecutionException", failed.throwableClass());
    }

    @Test
    void aFailureWithNoConsumerIsStillRecorded() {
        // shutdown() nulls the consumer deliberately — a disposed UI must not be called
        // back into. The reason the run ended must survive that anyway.
        var index = Cells.of(4).marker("CD45", i -> i).marker("CD3", i -> i * 2.0).build();
        var service = serviceRunning((idx, p, max, mode, gen) -> {
            throw new NoClassDefFoundError("org/bytedeco/arpackng/global/arpack");
        });
        try {
            service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);   // no consumer registered
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
        var index = Cells.of(2).marker("CD45", 1.0, 2.0).marker("CD3", 3.0, 4.0).build();
        List<UmapOutcome> outcomes = new CopyOnWriteArrayList<>();
        var service = new UmapComputeService(Runnable::run, null);
        try {
            service.setOnOutcome(outcomes::add);
            service.compute(Embeddings.of(index), UmapParameters.defaults(), 0);
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
    void aFiveHundredCellUmapProducesAnEmbedding() throws Exception {
        // The size a real user's first UMAP actually is, and the one that used to be
        // impossible. At 500 cells the neighbour graph is small and connected, which is
        // precisely the pair of conditions on which SMILE reaches for a spectral layout
        // and, through it, an ARPACK native FlowPath cannot ship on this platform:
        //
        //   UMAP failed: NoClassDefFoundError: org/bytedeco/arpackng/global/arpack
        //
        // Every other test in this class either stubs the work out or runs above 10,000
        // cells, where SMILE never looks at connectivity. Nobody had ever asserted that
        // the path a user takes on day one reaches an embedding at all.
        var idx = buildSyntheticIndex(500, 3, 5L);
        var service = new UmapComputeService();
        try {
            AtomicReference<UmapOutcome> outcomeRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnOutcome(o -> { outcomeRef.set(o); latch.countDown(); });
            service.compute(Embeddings.of(idx), new UmapParameters(15, 0.1, 1.0, 30, 5), 0);
            assertTrue(latch.await(180, TimeUnit.SECONDS), "a 500-cell UMAP must terminate");

            var succeeded = assertInstanceOf(UmapOutcome.Succeeded.class, outcomeRef.get(),
                    "a 500-cell UMAP must succeed: " + outcomeRef.get().describe());
            UmapResult result = succeeded.result();
            assertEquals(500, result.size());
            double[] xs = result.getUmapXRaw();
            double[] ys = result.getUmapYRaw();
            for (int i = 0; i < xs.length; i++) {
                assertTrue(Double.isFinite(xs[i]) && Double.isFinite(ys[i]),
                        "embedding coordinate " + i + " must be finite");
            }

            // Getting an embedding at this size costs one cell, and the outcome says so
            // rather than leaving the reader to find it in a log line.
            // Range is not asserted here: completedWith already refuses a detached row
            // outside the training matrix, and with no subsample the training matrix is
            // all 500 cells, so "0 <= imputed < 500" cannot fail. Nor is cellsAtOrigin,
            // for the same reason — the constructor refuses a parked cell when nothing
            // was held out. EmbeddingReportTest pins both refusals directly.
            int imputed = succeeded.report().imputedCell().orElseThrow(() ->
                    new AssertionError("a small connected graph must report the cell it "
                            + "detached to stay off the native layout"));
            assertEquals(EmbeddingReport.Initialisation.PCA_STEERED_FROM_SPECTRAL,
                    succeeded.report().initialisation(),
                    "the report must name the initialisation the run actually used");
            assertTrue(succeeded.report().unmeasuredMarkers().isEmpty(),
                    "three gaussian markers are not degenerate: "
                            + succeeded.report().unmeasuredMarkers());
            assertTrue(succeeded.report().reweightedCells() > 0,
                    "steering also rewrites the distance vector of every cell that listed "
                            + "the detached one; reporting only the imputed cell would "
                            + "understate it");

            // A convex combination of real positions cannot leave the population, so the
            // imputed cell must sit inside it. That the position is the RIGHT one — the
            // inverse-distance mean of the node's true neighbours — is pinned exactly in
            // EmbeddingInitialisationTest, where the graph is in hand; re-deriving it
            // here through an approximate NN-descent graph would test the tolerance, not
            // the placement.
            assertTrue(withinBoundsOfOthers(xs, imputed) && withinBoundsOfOthers(ys, imputed),
                    "the imputed cell must land inside the embedding, not beside it");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aSubsampledRunReportsTheImputedCellInTheCallersIndexNotTheTrainingMatrix() throws Exception {
        // The detached node is a row of the training matrix. When the run subsampled,
        // that row number addresses the subsample, not the CellIndex the caller handed
        // in — so reporting it raw would name an innocent cell, and every other test here
        // passes maxCells = 0, which leaves that translation never executed.
        //
        // This is a smoke test, not a proof: sampleIndices is not exposed, so the
        // assertion is that the reported index is present and addresses the caller's
        // index. A tighter check would mean widening the service's surface to let a test
        // see its subsample, which is a worse trade than this comment.
        int cells = 900;
        var idx = buildSyntheticIndex(cells, 3, 21L);
        var service = new UmapComputeService();
        try {
            AtomicReference<UmapOutcome> outcomeRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnOutcome(o -> { outcomeRef.set(o); latch.countDown(); });
            service.compute(Embeddings.of(idx), new UmapParameters(15, 0.1, 1.0, 30, 5), 300);
            assertTrue(latch.await(180, TimeUnit.SECONDS), "a subsampled UMAP must terminate");

            var succeeded = assertInstanceOf(UmapOutcome.Succeeded.class, outcomeRef.get(),
                    "a subsampled 900-cell UMAP should succeed: " + outcomeRef.get().describe());
            assertEquals(cells, succeeded.result().size(),
                    "subsampling trains on 300 but must still place all 900 cells");

            // Again no range assertion — completedWith bounds the detached row against the
            // 300-row training matrix before it ever becomes a cell index, so any value
            // reaching here is already inside 0..899.
            succeeded.report().imputedCell().orElseThrow(() -> new AssertionError(
                    "a 300-row training graph is small and connected, so it must be steered"));
            assertTrue(succeeded.report().reweightedCells() > 0,
                    "the cells that listed the detached node must be counted, not dropped");
            assertTrue(succeeded.report().subsampled(), "300 of 900 is a subsample");
            assertEquals(300, succeeded.report().trainedCells());
            assertEquals(cells, succeeded.report().totalCells());
            assertEquals(0, succeeded.report().cellsAtOrigin(),
                    "every held-out cell had five sampled neighbours to blend, so none "
                            + "should have been left at the origin");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aMarkerNoCellCarriesIsReportedRatherThanEmbeddedAsAColumnOfZeros() throws Exception {
        // The CellIndex.toMatrix hole, end to end. FoxP3 is absent on every cell, so its
        // column is imputed with the mean of nothing — 0.0 — and the run silently embeds
        // over two markers while the user believes it used three. The embedding is fine;
        // the belief is not, and only the report can correct it.
        var idx = Cells.of(500)
                .marker("CD45", i -> Math.sin(i))
                .marker("CD8", i -> Math.cos(i * 0.7))
                .marker("FoxP3", i -> 1.0).absentOn(i -> true)
                .build();
        var service = new UmapComputeService();
        try {
            AtomicReference<UmapOutcome> outcomeRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnOutcome(o -> { outcomeRef.set(o); latch.countDown(); });
            service.compute(Embeddings.of(idx), new UmapParameters(15, 0.1, 1.0, 30, 5), 0);
            assertTrue(latch.await(180, TimeUnit.SECONDS), "a degenerate column must not hang");

            var succeeded = assertInstanceOf(UmapOutcome.Succeeded.class, outcomeRef.get(),
                    "a degenerate column degrades the run, it does not fail it: "
                            + outcomeRef.get().describe());
            assertEquals(List.of("FoxP3"), succeeded.report().unmeasuredMarkers());
            assertFalse(succeeded.report().isClean());
            assertTrue(succeeded.report().describe().contains("FoxP3"),
                    succeeded.report().describe());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void anUntickedMarkerLeavesTheRunEntirelyRatherThanOnlyTheLabel() throws Exception {
        // The same population as the test above, run twice. FoxP3 is measured on no cell,
        // so while it is ticked the run reports it as an unmeasured feature and is not
        // clean. Untick it and that finding must be GONE — not because the report got
        // quieter, but because the column is no longer in the matrix. The run is clean and
        // says, as a note, that it embedded two of three markers.
        //
        // Before EmbeddingFeatures this assertion was unwritable: unticking FoxP3 changed
        // one label and the embedding still ran on all three columns, so the finding stayed.
        var idx = Cells.of(500)
                .marker("CD45", i -> Math.sin(i))
                .marker("CD8", i -> Math.cos(i * 0.7))
                .marker("FoxP3", i -> 1.0).absentOn(i -> true)
                .build();
        var picker = new MarkerSelection();
        picker.put("FoxP3", MarkerSelection.defaultEntry().withIncluded(false));

        var service = new UmapComputeService();
        try {
            AtomicReference<UmapOutcome> outcomeRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnOutcome(o -> { outcomeRef.set(o); latch.countDown(); });
            service.compute(Embeddings.of(idx, picker),
                    new UmapParameters(15, 0.1, 1.0, 30, 5), 0);
            assertTrue(latch.await(180, TimeUnit.SECONDS), "the run must terminate");

            var succeeded = assertInstanceOf(UmapOutcome.Succeeded.class, outcomeRef.get(),
                    outcomeRef.get().describe());
            assertEquals(List.of(), succeeded.report().unmeasuredMarkers(),
                    "an unticked marker is not a degraded feature — it is not a feature");
            assertTrue(succeeded.report().isClean(),
                    "excluding the offending marker must clean the run: "
                            + succeeded.report().describe());
            assertEquals(List.of("FoxP3"), succeeded.report().excludedMarkers());
            assertArrayEquals(new String[]{"CD45", "CD8"},
                    succeeded.result().getMarkerNamesRaw(),
                    "the result must not claim a dimension the layout never saw");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void untickingEveryMarkerFailsTheRunRatherThanEmbeddingNothing() throws Exception {
        var idx = Cells.of(500)
                .marker("CD45", i -> Math.sin(i))
                .marker("CD8", i -> Math.cos(i * 0.7))
                .build();
        var picker = new MarkerSelection();
        picker.put("CD45", MarkerSelection.defaultEntry().withIncluded(false));
        picker.put("CD8", MarkerSelection.defaultEntry().withIncluded(false));

        var service = new UmapComputeService();
        try {
            var outcomes = new CopyOnWriteArrayList<UmapOutcome>();
            service.setOnOutcome(outcomes::add);
            // The refusal is delivered through the ONE terminal channel, synchronously
            // from compute() rather than from the worker — so a caller that has already
            // shown a busy state still gets exactly one ending to clear it with.
            service.compute(EmbeddingFeatures.of(idx, picker),
                    new UmapParameters(15, 0.1, 1.0, 30, 5), 0);
            awaitOutcomes(outcomes, 1);

            assertEquals(1, outcomes.size(), "one compute() call, one outcome: " + outcomes);
            var failed = assertInstanceOf(UmapOutcome.Failed.class, outcomes.get(0),
                    "an embedding over nothing is not a degraded embedding");
            assertTrue(failed.reason().contains("No markers are selected"), failed.reason());
            assertNull(failed.throwableClass(),
                    "a refusal is a decision, not a crash to be reported as one");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void leavingOneMarkerTickedFailsTheRunRatherThanLayingOutALine() throws Exception {
        var idx = Cells.of(500)
                .marker("CD45", i -> Math.sin(i))
                .marker("CD8", i -> Math.cos(i * 0.7))
                .build();
        var picker = new MarkerSelection();
        picker.put("CD8", MarkerSelection.defaultEntry().withIncluded(false));

        var service = new UmapComputeService();
        try {
            var outcomes = new CopyOnWriteArrayList<UmapOutcome>();
            service.setOnOutcome(outcomes::add);
            service.compute(EmbeddingFeatures.of(idx, picker),
                    new UmapParameters(15, 0.1, 1.0, 30, 5), 0);
            awaitOutcomes(outcomes, 1);

            var failed = assertInstanceOf(UmapOutcome.Failed.class, outcomes.get(0));
            assertTrue(failed.reason().contains("Only 1 of 2"), failed.reason());
        } finally {
            service.shutdown();
        }
    }

    /**
     * Subsampling strata are the full class path, not {@link PathClass#getName()}.
     * <p>
     * QuPath's {@code getName()} for the derived class {@code "T cell: Core"} is the leaf
     * {@code "Core"}, so tagging two phenotypes with one population name merged them into a
     * single stratum. Proportional allocation then faithfully preserved the proportion of a
     * population that does not exist, while losing both of the ones that do — a
     * subsample-driven distortion of exactly the thing subsampling promises to preserve.
     * <p>
     * Ten phenotypes of ten cells each, all tagged "Core", sampled down to ten. Stratified
     * correctly, {@code classN = Math.max(1, ...)} guarantees every phenotype exactly one
     * seat. Merged into one stratum it is ten draws from a hundred cells, and drawing one
     * of each of ten populations that way has a probability of about four in a million — so
     * this asserts a structural guarantee rather than an average a lucky seed might hit.
     * An earlier version of this test used one common and one rare population and asserted
     * the rare one kept its share; the merged sampler cleared it by chance.
     */
    @Test
    void taggingPhenotypesAlikeDoesNotMergeTheirSubsamplingStrata() {
        var service = new UmapComputeService();
        try {
            var cells = Cells.of(100)
                    .marker("CD45", i -> Math.sin(i))
                    .marker("CD8", i -> Math.cos(i * 0.7));
            // detections() materialises once and build() reuses it, so these are the very
            // objects the index — and therefore the sampler — will read.
            var objects = cells.detections();
            for (int i = 0; i < objects.size(); i++) {
                objects.get(i).setPathClass(
                        PathClass.fromString("Strata-P" + (i / 10) + ": Core", 0xFF808080));
            }

            int[] sample = service.stratifiedSample(Embeddings.of(cells.build()), 10);

            var represented = new java.util.TreeSet<String>();
            for (int i : sample) represented.add(objects.get(i).getPathClass().toString());
            assertEquals(10, represented.size(),
                    "every phenotype gets a seat when they are ten strata; keyed on "
                            + "getName() they are one stratum called \"Core\" and this is a "
                            + "lottery. Got " + represented);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aCellTheProjectionCannotPlaceIsCountedRatherThanLeftLookingLikeACluster()
            throws Exception {
        // The brief's headline number, exercised through the real projection. A cell is
        // left at (0,0) only when no neighbour carries any weight, which needs every one
        // of its five nearest sampled neighbours to be at an infinite distance — in
        // practice a squared distance that saturated. So one held-out cell is given a
        // marker at 1e200 while every sampled cell reads 0.0 on it: the training matrix
        // stays finite and UMAP runs normally, and only that one query blows up.
        //
        // Which cell is held out is asked, not guessed. The subsample depends on the
        // population size, the class proportions and a seed, and a test that assumed an
        // answer would silently stop covering this the moment any of the three moved.
        int cells = 600;
        int trainOn = 200;
        var service = new UmapComputeService();
        try {
            var probe = Cells.of(cells)
                    .marker("CD45", i -> Math.sin(i))
                    .marker("CD8", i -> Math.cos(i * 0.7))
                    .marker("Rogue", i -> 0.0)
                    .build();
            int[] sample = service.stratifiedSample(Embeddings.of(probe), trainOn);
            boolean[] sampled = new boolean[cells];
            for (int idx : sample) sampled[idx] = true;
            int stranded = -1;
            for (int i = 0; i < cells && stranded < 0; i++) {
                if (!sampled[i]) stranded = i;
            }
            assertTrue(stranded >= 0, "training on 200 of 600 must hold something out");

            final int rogue = stranded;
            var idx = Cells.of(cells)
                    .marker("CD45", i -> Math.sin(i))
                    .marker("CD8", i -> Math.cos(i * 0.7))
                    .marker("Rogue", i -> i == rogue ? 1e200 : 0.0)
                    .build();

            AtomicReference<UmapOutcome> outcomeRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnOutcome(o -> { outcomeRef.set(o); latch.countDown(); });
            service.compute(Embeddings.of(idx), new UmapParameters(15, 0.1, 1.0, 30, 5), trainOn);
            assertTrue(latch.await(180, TimeUnit.SECONDS), "the run must terminate");

            var succeeded = assertInstanceOf(UmapOutcome.Succeeded.class, outcomeRef.get(),
                    "an unplaceable cell degrades the run, it does not fail it: "
                            + outcomeRef.get().describe());
            assertEquals(trainOn, succeeded.report().trainedCells(),
                    "the run must have held cells out, or there is nothing to project");
            assertEquals(1, succeeded.report().cellsAtOrigin(),
                    "the one cell no neighbour could be blended for must be counted");
            assertTrue(succeeded.report().summary().contains("(0,0)"),
                    succeeded.report().summary());
            assertEquals(0.0, succeeded.result().getUmapXRaw()[rogue],
                    "the stranded cell really is sitting at the origin");
            assertEquals(0.0, succeeded.result().getUmapYRaw()[rogue]);
        } finally {
            service.shutdown();
        }
    }

    /** True when {@code values[target]} lies within the range spanned by the others. */
    private static boolean withinBoundsOfOthers(double[] values, int target) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            if (i == target) continue;
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
        }
        return values[target] >= min && values[target] <= max;
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
        // service substitutes a concrete count derived from training-N. Below 10K cells
        // defaultsFor returns 200 epochs; the three size bands themselves are pinned in
        // UmapParametersTest.
        var idx = buildSyntheticIndex(500, 3, 7L);
        var service = new UmapComputeService();
        try {
            UmapResult result = runAndWait(service, idx, UmapParameters.defaults(), 0, null, 180);
            assertEquals(200, result.getParams().epochs(),
                    "Adaptive epochs at N=500 should resolve to 200");
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
        var idx = buildSyntheticIndex(500, 3, 13L);
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
            service.compute(Embeddings.of(idx), params, 0);
            service.cancel();
            secondStarted.set(true);
            service.compute(Embeddings.of(idx), params, 0);

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
        var idx = buildMultiScaleIndex(500, 99L);
        var service = new UmapComputeService();
        try {
            AtomicReference<UmapOutcome> outcomeRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            service.setOnOutcome(o -> { outcomeRef.set(o); latch.countDown(); });

            service.compute(Embeddings.of(idx), new UmapParameters(15, 0.1, 1.0, 30, 5), 0, ScalingMode.ZSCORE);
            assertTrue(latch.await(180, TimeUnit.SECONDS), "z-score UMAP should complete");
            var succeeded = assertInstanceOf(UmapOutcome.Succeeded.class, outcomeRef.get(),
                    "z-score UMAP should succeed: " + outcomeRef.get().describe());

            UmapResult result = succeeded.result();
            assertEquals(500, result.size());
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
        var idx = buildSyntheticIndex(500, 3, 1L);
        var service = new UmapComputeService();
        try {
            List<String> statusLog = new CopyOnWriteArrayList<>();
            var params = new UmapParameters(15, 0.1, 1.0, 30, 5);
            UmapResult result = runAndWait(service, idx, params, 0, statusLog, 180);
            assertEquals(500, result.size());

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
