package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.io.CsvExportJob;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

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

    /** Two enabled roots on two channels, matching the shape {@code CsvExportJobTest} uses. */
    private static GateTree twoRoots() {
        GateNode cd3Root = new GateNode("CD3", 5.5);
        cd3Root.setStatistic(Statistic.MEAN);
        GateNode cd8Root = new GateNode("CD8", 2.5);
        cd8Root.setStatistic(Statistic.MEAN);

        GateTree tree = new GateTree();
        tree.addRoot(cd3Root);
        tree.addRoot(cd8Root);
        return tree;
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
