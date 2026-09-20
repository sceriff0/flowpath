package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.io.CsvExportJob;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The background/FX-thread orchestration on top of {@link CsvExportJob#run}: the export runs
 * on {@code background}, its outcome lands on {@code fxThread}, and {@link
 * CsvExportCoordinator#exporting()} is {@code true} for exactly the span between {@link
 * CsvExportCoordinator#export} and that landing -- on both the success and the failure path.
 * <p>
 * Both executors are driven by hand, the same pattern {@code IngestCoordinatorTest} uses for
 * {@link IngestCoordinator}, so "the background job hasn't run yet" and "the outcome landed"
 * are orderings the test chooses rather than ones a real thread pool happens to produce.
 */
class CsvExportCoordinatorTest {

    @TempDir
    Path tempDir;

    /** Work queued until the test runs it. */
    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        int pending() { return queue.size(); }
        void runAll() { while (!queue.isEmpty()) queue.remove(0).run(); }
    }

    private static final class RecordingHost implements CsvExportCoordinator.Host {
        final List<File> exported = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        @Override public void exported(File file) { exported.add(file); }
        @Override public void failed(Throwable error) { failures.add(error); }
    }

    /**
     * Two enabled roots on two channels, matching the shape {@code CsvExportJobTest} uses --
     * shared as {@link GateTreeFixtures#twoRootsOnCd3AndCd8} rather than duplicated.
     */
    private static GateTree twoRoots() {
        return GateTreeFixtures.twoRootsOnCd3AndCd8(5.5, 2.5);
    }

    private CsvExportJob.Snapshot snapshotFor(File file) {
        CellIndex index = Cells.of(4)
                .marker("CD3", 1.0, 4.0, 6.0, 9.0)
                .marker("CD8", 1.0, 4.0, 6.0, 9.0)
                .area(50.0)
                .build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));
        return CsvExportJob.Snapshot.of(file, twoRoots(), index, stats, null, null);
    }

    @Test
    void exportRunsOnBackgroundAndLandsOnFxThreadOnSuccess() {
        ManualExecutor background = new ManualExecutor();
        ManualExecutor fxThread = new ManualExecutor();
        RecordingHost host = new RecordingHost();
        CsvExportCoordinator coordinator = new CsvExportCoordinator(background, fxThread, host);

        File file = tempDir.resolve("out.csv").toFile();
        coordinator.export(snapshotFor(file));

        assertTrue(coordinator.exporting(), "exporting until the outcome lands");
        assertEquals(1, background.pending());
        assertTrue(host.exported.isEmpty());

        background.runAll();
        assertEquals(1, fxThread.pending(), "the outcome is posted to the FX thread, not applied inline");
        assertTrue(coordinator.exporting(), "still exporting until the FX-thread runnable actually runs");

        fxThread.runAll();
        assertFalse(coordinator.exporting(), "reset once the outcome has landed");
        assertEquals(List.of(file), host.exported);
        assertTrue(host.failures.isEmpty());
        assertTrue(file.exists(), "the file was actually written");
    }

    @Test
    void aFailingWriteSurfacesAsAFailureCallbackAndResetsTheFlag() {
        ManualExecutor background = new ManualExecutor();
        ManualExecutor fxThread = new ManualExecutor();
        RecordingHost host = new RecordingHost();
        CsvExportCoordinator coordinator = new CsvExportCoordinator(background, fxThread, host);

        // A directory in place of a file: PhenotypeCsvExporter.export cannot open it for
        // writing, so the job throws rather than silently no-op-ing.
        File asDirectory = tempDir.toFile();
        coordinator.export(snapshotFor(asDirectory));

        assertTrue(coordinator.exporting());
        background.runAll();
        fxThread.runAll();

        assertFalse(coordinator.exporting(), "reset on the failure path too");
        assertTrue(host.exported.isEmpty());
        assertEquals(1, host.failures.size());
        assertNotNull(host.failures.get(0));
    }

    /**
     * {@code CsvExportJob.run} walks and serializes the full cell population, so a
     * million-cell export is exactly where an {@code OutOfMemoryError} is plausible. Catching
     * only {@code Exception} let an {@code Error} escape the executor's {@code Runnable}
     * uncaught: {@code exporting} was never reset, {@code land()} never ran, and the export
     * button/Ctrl+E stayed disabled until QuPath restarted. A {@link CsvExportCoordinator.Job}
     * is injected here (rather than actually exhausting the heap) purely so the test can throw
     * an {@code Error} deterministically.
     */
    @Test
    void anErrorThrownByTheJobSurfacesAsAFailureAndResetsTheFlag() {
        ManualExecutor background = new ManualExecutor();
        ManualExecutor fxThread = new ManualExecutor();
        RecordingHost host = new RecordingHost();
        OutOfMemoryError thrown = new OutOfMemoryError("simulated: exporting a huge population");
        CsvExportCoordinator coordinator = new CsvExportCoordinator(background, fxThread, host,
                snapshot -> { throw thrown; });

        coordinator.export(snapshotFor(tempDir.resolve("oom.csv").toFile()));
        assertTrue(coordinator.exporting());

        background.runAll();
        fxThread.runAll();

        assertFalse(coordinator.exporting(), "the flag must not get stuck on an Error");
        assertTrue(host.exported.isEmpty());
        assertEquals(List.of(thrown), host.failures, "the Error itself reaches the failure callback");
    }

    @Test
    void aSecondExportWhileOneIsRunningIsIgnored() {
        ManualExecutor background = new ManualExecutor();
        ManualExecutor fxThread = new ManualExecutor();
        RecordingHost host = new RecordingHost();
        CsvExportCoordinator coordinator = new CsvExportCoordinator(background, fxThread, host);

        File first = tempDir.resolve("first.csv").toFile();
        File second = tempDir.resolve("second.csv").toFile();
        coordinator.export(snapshotFor(first));
        coordinator.export(snapshotFor(second)); // ignored: an export is already running

        assertEquals(1, background.pending(), "the second call queued no work");

        background.runAll();
        fxThread.runAll();

        assertEquals(List.of(first), host.exported);
        assertFalse(second.exists());
        assertFalse(coordinator.exporting());
    }

    @Test
    void exportingIsFalseBeforeTheFirstExport() {
        CsvExportCoordinator coordinator = new CsvExportCoordinator(
                new ManualExecutor(), new ManualExecutor(), new RecordingHost());
        assertFalse(coordinator.exporting());
    }
}
