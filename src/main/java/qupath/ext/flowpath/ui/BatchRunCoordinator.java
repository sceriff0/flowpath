package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.batch.BatchResult;
import qupath.ext.flowpath.batch.BatchRunner;
import qupath.ext.flowpath.batch.BatchSlide;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * One "Run on all slides" at a time on the shared background executor, one slide per task, so
 * the rest of FlowPath's background work — an edit's derivation, a re-ingest, a sampling step —
 * interleaves between two slides instead of waiting for the whole project. The next slide is
 * submitted only after the previous one has landed on the FX thread, so a derivation or an ingest
 * queued during a run waits at most for the one slide being gated (plus the final write, which
 * runs as one task after the last slide).
 * <p>
 * Same shape as {@link CsvExportCoordinator}: a second {@link #run} while one is going is
 * ignored, and the finishing write catches {@code Error} as well as {@code Exception}, so an
 * {@code OutOfMemoryError} cannot leave {@link #running()} stuck {@code true} and the button
 * disabled with no explanation. Per-slide failures never reach here:
 * {@link BatchRunner#gateOne} turns each one (an {@code Error} included) into a value.
 * <p>
 * The tree is frozen by {@link BatchRunner.Settings} when the run starts (a deep copy), so gate
 * edits made while it runs never reach it — which is why editing stays allowed during a run
 * ({@link BusyState#editingBlocked()} does not include it). Cancelling stops before the next
 * slide; the slides already gated are still written out and the outcome says it was cancelled.
 * <p>
 * {@link #close()} — the pane going away — abandons the run: the executor is shut down with
 * {@code shutdownNow()}, which interrupts the slide in flight, so that slide's phenotype CSV or
 * its {@code .qpdata} save may be left unwritten or incomplete, and the combined outputs are never
 * written.
 * <p>
 * Toolkit-free: both executors are injected and a test drives them by hand. {@link #check} is
 * the decision the pane shows before a run — refused, or confirmed with the unreviewed count —
 * so that decision is table-tested here rather than living in a dialog.
 */
final class BatchRunCoordinator {

    /** Where the coordinator reports. Every call arrives on the FX thread. */
    interface Host {
        /** Slide {@code done} of {@code total}, named {@code name}, has been gated. */
        void progress(int done, int total, String name);

        /** The outputs were written, after every slide or after a cancel. */
        void finished(Outcome outcome);

        /** The finishing write failed; the per-slide phenotype CSVs already written stay. */
        void failed(Throwable error);
    }

    record Outcome(File outputDir, List<BatchResult> results, int total, boolean cancelled) {}

    /** Writes the run's combined outputs; {@link BatchRunner#writeOutputs}, injectable for tests. */
    @FunctionalInterface
    interface Finisher {
        void write(File dir, List<BatchResult> results) throws IOException;
    }

    /** Whether a run may start, and what to say either way; see {@link #check}. */
    sealed interface Start permits Refused, Confirm {
        String message();
    }

    /** The run must not start: the message says why. */
    record Refused(String message) implements Start {}

    /** The run may start once the user confirms the message. */
    record Confirm(String message) implements Start {}

    private final Executor background;
    private final Executor fxThread;
    private final Host host;

    /** Set on the FX thread when a run starts; cleared there once its outcome lands. */
    private boolean running;
    /** Read on the FX thread between slides; set from the FX thread by {@link #cancel()}. */
    private volatile boolean cancelled;
    /** The pane is gone: nothing more is submitted and nothing lands. */
    private volatile boolean closed;

    BatchRunCoordinator(Executor background, Executor fxThread, Host host) {
        this.background = Objects.requireNonNull(background, "background");
        this.fxThread = Objects.requireNonNull(fxThread, "fxThread");
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Whether a run of {@code tree} over {@code slides} may start. Refused when the tree has no
     * enabled gate, the project has no images, or the tree belongs to another project
     * ({@link BatchRunner#refusal}: its per-slide settings would land on different images here).
     * Otherwise a confirmation that says how many review items are still unreviewed, if any —
     * a run with open items is allowed (spec §6), but not without saying so.
     *
     * @param unreviewed the review items still open ({@code CohortState.remaining()})
     */
    static Start check(GateTree tree, List<BatchSlide> slides, int unreviewed) {
        if (!hasEnabledGate(tree)) return new Refused("No enabled gates to run.");
        if (slides.isEmpty()) return new Refused("This project has no images.");
        String foreign = BatchRunner.refusal(tree, slides);
        if (foreign != null) return new Refused(foreign);
        StringBuilder sb = new StringBuilder()
                .append("Gate all ").append(slides.size()).append(" slide(s) of this project, each with its own ")
                .append("applied thresholds, and save the phenotypes into each slide's data file — except the ")
                .append("slide open in the viewer, which you save from QuPath.");
        if (unreviewed > 0) {
            sb.append("\n\n").append(unreviewed)
              .append(unreviewed == 1 ? " review item is still unreviewed" : " review items are still unreviewed")
              .append(": the slides they concern run on the thresholds shown now.");
        }
        return new Confirm(sb.toString());
    }

    /** Whether {@code tree} has anything to run: the one rule both {@link #check} and the button use. */
    static boolean hasEnabledGate(GateTree tree) {
        return tree.getRoots().stream().anyMatch(GateNode::isEnabled);
    }

    /** A run is going: the caller offers Cancel instead of starting another. */
    boolean running() {
        return running;
    }

    /** Stop before the next slide; what has been gated is still written out. */
    void cancel() {
        cancelled = true;
    }

    /**
     * The pane is going away and the executors with it: stop before the next slide and land
     * nothing, since there is no pane left to report to and no executor left to write on.
     */
    void close() {
        closed = true;
        cancelled = true;
    }

    /**
     * Gate {@code slides} with {@code settings}, one per background task, then hand the results to
     * {@code finisher} on the background. Ignored while {@link #running()}.
     */
    void run(List<BatchSlide> slides, BatchRunner.Settings settings, Finisher finisher) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(finisher, "finisher");
        if (running || closed) return;
        running = true;
        cancelled = false;
        next(List.copyOf(slides), 0, settings, finisher, new ArrayList<>(), new HashSet<>());
    }

    /** On the FX thread: submit slide {@code i}, or finish. */
    private void next(List<BatchSlide> slides, int i, BatchRunner.Settings settings, Finisher finisher,
                      List<BatchResult> results, Set<String> used) {
        if (closed) return;
        if (i >= slides.size() || cancelled) {
            finish(slides.size(), settings, finisher, results);
            return;
        }
        BatchSlide slide = slides.get(i);
        background.execute(() -> {
            // `used` is only touched here, one task at a time, each submitted after the last landed.
            BatchResult r = BatchRunner.gateOne(slide, BatchRunner.phenoFileName(slide.name(), used), settings);
            fxThread.execute(() -> {
                if (closed) return;
                results.add(r);
                host.progress(i + 1, slides.size(), slide.name());
                next(slides, i + 1, settings, finisher, results, used);
            });
        });
    }

    private void finish(int total, BatchRunner.Settings settings, Finisher finisher, List<BatchResult> results) {
        // Cancelled only if a slide was actually left out: a cancel after the last slide landed
        // stopped nothing.
        boolean wasCancelled = cancelled && results.size() < total;
        List<BatchResult> frozen = List.copyOf(results);
        File dir = settings.outputDir();
        background.execute(() -> {
            try {
                finisher.write(dir, frozen);
                fxThread.execute(() -> land(() -> host.finished(new Outcome(dir, frozen, total, wasCancelled))));
            } catch (Exception | Error ex) {
                // Error too, as CsvExportCoordinator: an OutOfMemoryError must not leave `running`
                // stuck true and the button disabled with no explanation.
                fxThread.execute(() -> land(() -> host.failed(ex)));
            }
        });
    }

    private void land(Runnable notify) {
        if (closed) return;
        running = false;
        notify.run();
    }
}
