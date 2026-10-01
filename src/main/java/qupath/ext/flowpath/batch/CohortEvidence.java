package qupath.ext.flowpath.batch;

import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.ReviewScorer;
import qupath.ext.flowpath.cohort.SlideSample;
import qupath.ext.flowpath.cohort.SlideSource;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.GateTree;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Everything a run reports about the cohort — the alignments it gates with, the model, review
 * and marker-rule rates behind them, and how the sample they came from was drawn — as one value,
 * so the GUI and a headless run hand {@link FlowPathBatch#finish} the same thing.
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
public record CohortEvidence(AlignmentModel model, ReviewScorer.Result review, AlignmentLookup lookup,
                             Provenance provenance, Set<String> excluded) {

    /** The sample size came from the project's alignment cache, which records the one the GUI used. */
    public static final String FROM_CACHE = "alignment-cache";
    /** ... from this machine's FlowPath preference (nothing was recorded). */
    public static final String FROM_PREFERENCE = "preference";
    /** ... from the caller's explicit argument. */
    public static final String FROM_ARGUMENT = "argument";
    /** ... from the model the user reviewed in the GUI. */
    public static final String FROM_REVIEWED_MODEL = "reviewed-model";

    /**
     * How the cohort sample was drawn, for {@code run_info.txt}: cells per slide and where that
     * number came from; how many sampled slides reused cached landmarks ({@code cacheHits}, -1 when
     * not known) of {@code sampled}; and the reference slide's name beside its id.
     */
    public record Provenance(int cellsPerSlide, String sampleSizeSource, int cacheHits, int sampled,
                             String referenceSlideName) {}

    /** A headless {@link #sample}: the evidence, and each sampled slide's full detection fingerprint. */
    public record Sampled(CohortEvidence evidence, Map<String, String> detectionFingerprints) {
        public Sampled {
            detectionFingerprints = Map.copyOf(detectionFingerprints);
        }
    }

    public CohortEvidence {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(provenance, "provenance");
        lookup = lookup == null ? AlignmentLookup.NONE : lookup;
        excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
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
     * <p>
     * While the sampler holds each slide's hierarchy, the slide's full detection fingerprint
     * ({@link CohortSampler#detectionFingerprint}) is taken from it too, so a run's resume check
     * needs no second read of a slide the sampler already read.
     *
     * @param tree      the tree the run gates with; only read
     * @param cancelled asked before each slide is sampled
     */
    public static Sampled sample(List<BatchSlide> slides, GateTree tree, AlignmentModel.Cache cache, int cellsPerSlide,
                                 String sampleSizeSource, BooleanSupplier cancelled) {
        CohortSession session = new CohortSession();
        session.setProject("batch", slides.stream().map(s -> new CohortSession.SlideRef(s.id(), s.name())).toList());
        session.setCache(cache);
        Set<String> excluded = slides.stream().filter(BatchSlide::cohortExcluded).map(BatchSlide::id)
                .collect(Collectors.toSet());
        session.setExcluded(excluded);
        Map<String, String> fingerprints = new HashMap<>();
        if (tree.getReferenceSlideId() != null && slides.size() >= 2) {
            List<SlideSource> sources = new ArrayList<>();
            for (BatchSlide slide : slides) {
                if (!slide.cohortExcluded()) sources.add(fingerprinting(slide, fingerprints));
            }
            session.samplingStarted();
            CohortSampler.sampleAll(sources, tree, cellsPerSlide, session::landed, cancelled);
            session.samplingFinished();
        }
        session.adopt(CohortSession.score(session.snapshot(tree), tree.deepCopy()));
        int hits = 0;
        List<SlideSample> samples = session.samples();
        for (SlideSample s : samples) {
            AlignmentModel.SlideEntry cached = cache.slides().get(s.slideId());
            if (cached != null && cached.fingerprint().equals(AlignmentModel.fingerprint(s, session.model().scale()))) hits++;
        }
        String referenceName = slides.stream().filter(s -> s.id().equals(tree.getReferenceSlideId()))
                .map(BatchSlide::name).findFirst().orElse(null);
        CohortEvidence evidence = new CohortEvidence(session.model(), session.review(), session.lookupOn(session.model()),
                new Provenance(cellsPerSlide, sampleSizeSource, hits, samples.size(), referenceName), excluded);
        return new Sampled(evidence, fingerprints);
    }

    /** {@code slide} as the sampler reads it, recording its detection fingerprint from that same read. */
    private static SlideSource fingerprinting(BatchSlide slide, Map<String, String> fingerprints) {
        return new SlideSource() {
            @Override public String id() { return slide.id(); }
            @Override public String name() { return slide.name(); }
            @Override public PathObjectHierarchy readHierarchy() throws Exception {
                PathObjectHierarchy hierarchy = slide.readHierarchy();
                fingerprints.put(slide.id(),
                        CohortSampler.detectionFingerprint(new ArrayList<>(hierarchy.getDetectionObjects())));
                return hierarchy;
            }
        };
    }
}
