package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.io.CsvExportJob;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Runs a CSV export off the FX thread.
 * <p>
 * <b>Why.</b> {@code FlowPathPane.exportCsv} used to call {@link CsvExportJob#run} directly
 * on the FX thread, freezing QuPath while a million-cell slide was gated and written for
 * the same reason a synchronous ingest did. The snapshot is built on the FX thread when the
 * user asks to export ({@link CsvExportJob.Snapshot#of} deep-copies the tree and clones the
 * ROI mask there, so a later gate edit cannot leak in), the gating pass and the file write
 * happen on {@code background}, and the outcome lands back on {@code fxThread}.
 * <p>
 * Only one export runs at a time: {@link #export} refuses a second call while
 * {@link #exporting()} is still {@code true}, exactly as {@code FlowPathPane} refuses to
 * gate a re-ingest queued behind a running export. There is no generation counter here the
 * way {@link IngestCoordinator} has one — an export is never superseded by a newer one, it
 * is simply not started while one is in flight.
 * <p>
 * Toolkit-free: both executors are injected, so a test drives the background job and the
 * FX-thread landing by hand, the same pattern {@link IngestCoordinatorTest} uses for
 * {@link IngestCoordinator}.
 */
final class CsvExportCoordinator {

    /** Where the coordinator hands its outcome. Every call arrives on the FX thread. */
    interface Host {
        /** The file was written. */
        void exported(File file);

        /** The gating pass or the write failed; nothing was written, or it is incomplete. */
        void failed(Throwable error);
    }

    private final Executor background;
    private final Executor fxThread;
    private final Host host;

    /** Set on the FX thread when a job is submitted; cleared there once it lands. */
    private boolean exporting;

    CsvExportCoordinator(Executor background, Executor fxThread, Host host) {
        this.background = Objects.requireNonNull(background, "background");
        this.fxThread = Objects.requireNonNull(fxThread, "fxThread");
        this.host = Objects.requireNonNull(host, "host");
    }

    /** An export is running: the caller should keep the export button and Ctrl+E disabled. */
    boolean exporting() {
        return exporting;
    }

    /**
     * Run {@code snapshot} in the background and report the outcome on the FX thread. Does
     * nothing while {@link #exporting()} is already {@code true} — the caller is expected to
     * have disabled the entry points, but a second call (a stray Ctrl+E) is ignored rather
     * than queued or allowed to race the first.
     */
    void export(CsvExportJob.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (exporting) return;
        exporting = true;
        background.execute(() -> {
            try {
                CsvExportJob.run(snapshot);
                fxThread.execute(() -> land(() -> host.exported(snapshot.file())));
            } catch (Exception ex) {
                fxThread.execute(() -> land(() -> host.failed(ex)));
            }
        });
    }

    private void land(Runnable notify) {
        exporting = false;
        notify.run();
    }
}
