package qupath.ext.flowpath.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.SlideSource;
import qupath.ext.flowpath.model.GateTree;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Runs the sampler and the scoring on the shared {@code flowpath-background} executor and lands
 * both on the FX thread, generation-stamped like {@code IngestCoordinator}. One slide per task:
 * the next slide is submitted only after the previous one landed, so other background work
 * queued meanwhile runs between slides. Toolkit-free; tests drive both executors by hand.
 */
final class CohortCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(CohortCoordinator.class);

    interface Host {
        void sampled(CohortSampler.Outcome outcome);
        void samplingFinished();
        void scored(boolean alignmentsChanged);

        /**
         * The first scoring adopted after a run finished: {@code cache} holds every landmark that
         * run found, to be written to {@code file} — the cache file of the project the run was
         * started for, captured then, never looked up again — and {@code sampledCellsPerSlide},
         * the sample size that run used, recorded beside it so a headless run can sample alike.
         * Never called with an empty cache.
         */
        void cacheSettled(Path file, AlignmentModel.Cache cache, int sampledCellsPerSlide);
    }

    private final CohortSession session;
    private final Executor background;
    private final Executor fxThread;
    private final Host host;
    /** Read on the background thread to drop superseded work before it starts; written on the FX thread. */
    private volatile long sampleGeneration;
    private volatile long scoreGeneration;
    private boolean sampling;
    /** Where the running (or just finished) run's landmarks belong; null when they are not to be written. */
    private Path cacheFile;
    /** A run finished and its landmarks wait for the next adopted scoring, which includes its last slide. */
    private boolean cacheDue;
    /** The sample size of the last run started, -1 before any: what the session's samples were drawn with. */
    private int sampledCellsPerSlide = -1;

    CohortCoordinator(CohortSession session, Executor background, Executor fxThread, Host host) {
        this.session = Objects.requireNonNull(session);
        this.background = Objects.requireNonNull(background);
        this.fxThread = Objects.requireNonNull(fxThread);
        this.host = Objects.requireNonNull(host);
    }

    boolean sampling() { return sampling; }

    /**
     * The cells per slide the session's samples — and so the model scored from them — were drawn
     * with, or -1 before any sampling started. What a run reports as its sample size: the
     * preference may have changed since.
     */
    int sampledCellsPerSlide() { return sampledCellsPerSlide; }

    void start(List<SlideSource> sources, GateTree tree, int cellsPerSlide) {
        start(sources, tree, cellsPerSlide, null);
    }

    /**
     * Sample {@code sources}, superseding any run in flight.
     *
     * @param cacheFile the project's alignment cache, captured now so a run that finishes after
     *                  the user has moved to another project still writes into its own; null to
     *                  write nothing
     */
    void start(List<SlideSource> sources, GateTree tree, int cellsPerSlide, Path cacheFile) {
        long generation = ++sampleGeneration;
        sampling = true;
        this.cacheFile = cacheFile;
        this.sampledCellsPerSlide = cellsPerSlide;
        cacheDue = false;
        session.samplingStarted();
        next(generation, List.copyOf(sources), 0, tree.deepCopy(), cellsPerSlide);
    }

    void cancel() {
        sampleGeneration++;
        scoreGeneration++;
        cacheDue = false;
        if (sampling) {
            sampling = false;
            session.samplingFinished();
        }
    }

    private void next(long generation, List<SlideSource> sources, int i, GateTree tree, int cells) {
        if (i >= sources.size()) {
            sampling = false;
            // Not written from here: the rescore the last slide asked for has not landed yet, so
            // the model still lacks that slide's landmarks. The next adopted scoring has them.
            cacheDue = cacheFile != null;
            session.samplingFinished();
            host.samplingFinished();
            return;
        }
        background.execute(() -> {
            if (generation != sampleGeneration) return;
            CohortSampler.Outcome outcome = CohortSampler.sampleOne(sources.get(i), tree, cells);
            fxThread.execute(() -> {
                if (generation != sampleGeneration) return;
                session.landed(outcome);
                host.sampled(outcome);
                next(generation, sources, i + 1, tree, cells);
            });
        });
    }

    /** Re-score on a deep copy in the background; only the newest request is adopted. */
    void rescore(GateTree liveTree) {
        long generation = ++scoreGeneration;
        CohortSession.Snapshot snapshot = session.snapshot(liveTree);
        GateTree copy = liveTree.deepCopy();
        background.execute(() -> {
            if (generation != scoreGeneration) return;
            try {
                CohortSession.Scored scored = CohortSession.score(snapshot, copy);
                fxThread.execute(() -> {
                    if (generation != scoreGeneration) return;
                    host.scored(session.adopt(scored));
                    if (cacheDue) {
                        cacheDue = false;
                        AlignmentModel.Cache cache = session.model().cache();
                        if (!cache.isEmpty()) host.cacheSettled(cacheFile, cache, sampledCellsPerSlide);
                    }
                });
            } catch (Exception | Error ex) {
                // Error too: scoring walks every sample; an OutOfMemoryError must not escape the
                // executor and leave the previous review silently in place with no log line.
                logger.error("Could not score the cohort; the previous review stays in place", ex);
            }
        });
    }
}
