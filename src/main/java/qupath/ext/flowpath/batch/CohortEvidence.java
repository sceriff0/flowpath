package qupath.ext.flowpath.batch;

import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.ReviewScorer;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.GateTree;

import java.util.List;
import java.util.Objects;

/**
 * Everything a run reports about the cohort — the alignments it gates with, and the model, review
 * and marker-rule rates behind them — as one value, so the GUI and a headless run hand
 * {@link FlowPathBatch#finish} the same thing.
 * <ul>
 *   <li>The GUI builds it from what the user reviewed ({@code CohortSession.model()},
 *       {@code review()}, {@code lookupOn(model)}), never recomputed.</li>
 *   <li>A headless run {@linkplain #sample samples} the cohort itself through a
 *       {@link CohortSession} — the same seed, the same model build and the same "may the lookup
 *       answer" rule the GUI's session applies — so it reproduces what the GUI showed.</li>
 * </ul>
 * Marker-rule rates are read from {@link ReviewScorer.Result#rules()}, the numbers the review was
 * built from, never evaluated a second time.
 */
public record CohortEvidence(AlignmentModel model, ReviewScorer.Result review, AlignmentLookup lookup) {

    /** No cohort: no alignment, no review. What a run of a single slide, or with no reference, reports. */
    public static final CohortEvidence NONE = new CohortEvidence(AlignmentModel.empty(),
            new ReviewScorer.Result(List.of(), List.of()), AlignmentLookup.NONE);

    public CohortEvidence {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(review, "review");
        lookup = lookup == null ? AlignmentLookup.NONE : lookup;
    }

    /** The manifest's landmark and flag columns, blank landmarks when {@link #lookup} corrects nothing. */
    public GatingManifestExporter.Annotations annotations() {
        return GatingManifestExporter.Annotations.of(model, review, lookup);
    }

    /**
     * Sample every slide with {@link CohortSampler}'s fixed seed and {@code cellsPerSlide}, align
     * against {@code cache} (reused per slide when its sample fingerprint matches, recomputed
     * otherwise), and score the review — the GUI's sequence, run synchronously. A slide that
     * cannot be sampled is left out of the cohort, as in the GUI; it may still be gated. Nothing
     * is read when there is nothing to align (no reference slide, or fewer than two slides).
     *
     * @param tree the tree the run gates with; only read
     */
    public static CohortEvidence sample(List<BatchSlide> slides, GateTree tree, AlignmentModel.Cache cache,
                                        int cellsPerSlide) {
        CohortSession session = new CohortSession();
        session.setProject("batch", slides.stream().map(s -> new CohortSession.SlideRef(s.id(), s.name())).toList());
        session.setCache(cache);
        if (tree.getReferenceSlideId() != null && slides.size() >= 2) {
            session.samplingStarted();
            CohortSampler.sampleAll(slides.stream().map(FlowPathBatch::asSource).toList(), tree, cellsPerSlide,
                    session::landed, () -> false);
            session.samplingFinished();
        }
        session.adopt(CohortSession.score(session.snapshot(tree), tree.deepCopy()));
        return new CohortEvidence(session.model(), session.review(), session.lookupOn(session.model()));
    }
}
