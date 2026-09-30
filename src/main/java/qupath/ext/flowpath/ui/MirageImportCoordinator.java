package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.mirage.MirageRun;
import qupath.ext.flowpath.mirage.ProjectBuilder;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * "New project from MIRAGE…": one import at a time, one patient per task on the shared background
 * executor — the {@link BatchRunCoordinator} shape — so an ingest or a derivation queued meanwhile
 * waits for at most one patient rather than the whole run. The next patient is submitted only after
 * the previous one has landed on the FX thread; cancelling stops before the next, and what was added
 * stays (the project file is written after every patient by {@link ProjectBuilder}).
 * <p>
 * A patient that fails — an {@code Error} included — is recorded and the import goes on: one
 * unreadable pyramid must not cost the other thirty-nine patients. Only a project that cannot be
 * opened at all fails the import as a whole.
 * <p>
 * Toolkit-free: both executors and the {@link Importer} are injected and a test drives them by hand.
 */
final class MirageImportCoordinator {

    /** Where the coordinator reports. Every call arrives on the FX thread. */
    interface Host {
        /** Patient {@code done} of {@code total}, named {@code id}, has been added or has failed. */
        void progress(int done, int total, String id);

        /** Every patient was tried, or the import was cancelled between two. */
        void finished(Outcome outcome);

        /** The project could not be opened or created; nothing was added. */
        void failed(Throwable error);
    }

    /** The two steps, injectable for tests; {@link #standard()} is {@link ProjectBuilder}'s. */
    interface Importer {
        Project<BufferedImage> open(Path projectDir) throws IOException;

        /** @return the number of cells imported */
        int add(Project<BufferedImage> project, MirageRun.Patient patient) throws IOException;

        static Importer standard() {
            ProjectBuilder builder = ProjectBuilder.standard();
            return new Importer() {
                @Override public Project<BufferedImage> open(Path dir) throws IOException {
                    return ProjectBuilder.openOrCreate(dir);
                }
                @Override public int add(Project<BufferedImage> project, MirageRun.Patient patient) throws IOException {
                    return builder.addPatient(project, patient);
                }
            };
        }
    }

    record Added(String id, int cells) {}

    record Failure(String id, String reason) {}

    /** {@code project} is the one written to, for the pane to open in QuPath. */
    record Outcome(Project<BufferedImage> project, List<Added> added, List<Failure> failures, int total,
                   boolean cancelled) {}

    private final Executor background;
    private final Executor fxThread;
    private final Host host;

    /** Set on the FX thread when an import starts; cleared there once its outcome lands. */
    private boolean running;
    private volatile boolean cancelled;
    private volatile boolean closed;

    MirageImportCoordinator(Executor background, Executor fxThread, Host host) {
        this.background = Objects.requireNonNull(background, "background");
        this.fxThread = Objects.requireNonNull(fxThread, "fxThread");
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Why an import must not start, or {@code null}. The import ends by switching QuPath to the new
     * project, and switching away from an image with unsaved changes would put them at risk, so
     * they are saved (or discarded) by the user first, in QuPath, the way they always are.
     */
    static String refusal(boolean openImageUnsaved, int ready) {
        if (openImageUnsaved) {
            return "The image open in QuPath has unsaved changes. Save it (File → Save) or close it first: "
                    + "the import ends by opening the new project.";
        }
        if (ready == 0) return "Nothing to import: no patient in this folder is ready to add.";
        return null;
    }

    /** What the import did, for the dialog that ends it; {@code skipped} is from the preview. */
    static String summary(Outcome outcome, int skipped) {
        int cells = outcome.added().stream().mapToInt(Added::cells).sum();
        StringBuilder sb = new StringBuilder()
                .append("Added ").append(outcome.added().size()).append(" of ").append(outcome.total())
                .append(outcome.total() == 1 ? " patient" : " patients")
                .append(String.format(java.util.Locale.US, " (%,d cells).", cells));
        if (skipped > 0) sb.append("\n").append(skipped).append(" skipped (already in the project, or no cells file).");
        if (outcome.cancelled()) sb.append("\nCancelled before the remaining patients.");
        if (!outcome.failures().isEmpty()) {
            sb.append("\n\nNot added:");
            for (Failure f : outcome.failures()) sb.append("\n  ").append(f.id()).append(": ").append(f.reason());
        }
        return sb.toString();
    }

    boolean running() {
        return running;
    }

    /** Stop before the next patient; those already added stay. */
    void cancel() {
        cancelled = true;
    }

    /** The pane is going away: submit nothing more and land nothing. */
    void close() {
        closed = true;
        cancelled = true;
    }

    /** Open (or create) the project in {@code projectDir} and add {@code patients}. Ignored while running. */
    void run(Path projectDir, List<MirageRun.Patient> patients, Importer importer) {
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(importer, "importer");
        if (running || closed) return;
        running = true;
        cancelled = false;
        List<MirageRun.Patient> frozen = List.copyOf(patients);
        background.execute(() -> {
            Project<BufferedImage> project;
            try {
                project = importer.open(projectDir);
            } catch (Exception | Error e) {
                fxThread.execute(() -> land(() -> host.failed(e)));
                return;
            }
            fxThread.execute(() -> next(project, frozen, 0, importer, new ArrayList<>(), new ArrayList<>()));
        });
    }

    /** On the FX thread: submit patient {@code i}, or finish. */
    private void next(Project<BufferedImage> project, List<MirageRun.Patient> patients, int i, Importer importer,
                      List<Added> added, List<Failure> failures) {
        if (closed) return;
        if (i >= patients.size() || cancelled) {
            boolean wasCancelled = cancelled && i < patients.size();
            Outcome outcome = new Outcome(project, List.copyOf(added), List.copyOf(failures), patients.size(), wasCancelled);
            land(() -> host.finished(outcome));
            return;
        }
        MirageRun.Patient patient = patients.get(i);
        background.execute(() -> {
            Added ok = null;
            Failure failed = null;
            try {
                ok = new Added(patient.id(), importer.add(project, patient));
            } catch (Exception | Error e) {
                // Error too, as BatchRunCoordinator: an OutOfMemoryError on one huge slide is that
                // patient's failure, not a reason to leave the button stuck on Cancel.
                failed = new Failure(patient.id(), reason(e));
            }
            Added a = ok;
            Failure f = failed;
            fxThread.execute(() -> {
                if (closed) return;
                if (a != null) added.add(a);
                if (f != null) failures.add(f);
                host.progress(i + 1, patients.size(), patient.id());
                next(project, patients, i + 1, importer, added, failures);
            });
        });
    }

    private static String reason(Throwable e) {
        if (e instanceof Error) return e.getClass().getSimpleName() + ": " + e.getMessage();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void land(Runnable notify) {
        if (closed) return;
        running = false;
        notify.run();
    }
}
