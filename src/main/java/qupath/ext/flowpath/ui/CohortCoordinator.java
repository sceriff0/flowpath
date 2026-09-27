package qupath.ext.flowpath.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.SlideSource;
import qupath.ext.flowpath.model.GateTree;

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
    }

    private final CohortSession session;
    private final Executor background;
    private final Executor fxThread;
    private final Host host;
    /** Read on the background thread to drop superseded work before it starts; written on the FX thread. */
    private volatile long sampleGeneration;
    private volatile long scoreGeneration;
    private boolean sampling;

    CohortCoordinator(CohortSession session, Executor background, Executor fxThread, Host host) {
        this.session = Objects.requireNonNull(session);
        this.background = Objects.requireNonNull(background);
        this.fxThread = Objects.requireNonNull(fxThread);
        this.host = Objects.requireNonNull(host);
    }

    boolean sampling() { return sampling; }

    void start(List<SlideSource> sources, GateTree tree, int cellsPerSlide) {
        long generation = ++sampleGeneration;
        sampling = true;
        session.samplingStarted();
        next(generation, List.copyOf(sources), 0, tree.deepCopy(), cellsPerSlide);
    }

    void cancel() {
        sampleGeneration++;
        scoreGeneration++;
        if (sampling) {
            sampling = false;
            session.samplingFinished();
        }
    }

    private void next(long generation, List<SlideSource> sources, int i, GateTree tree, int cells) {
        if (i >= sources.size()) {
            sampling = false;
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
                });
            } catch (Exception | Error ex) {
                // Error too: scoring walks every sample; an OutOfMemoryError must not escape the
                // executor and leave the previous review silently in place with no log line.
                logger.error("Could not score the cohort; the previous review stays in place", ex);
            }
        });
    }
}
