package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.CohortStats;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Toolkit-free owner of the cohort: the project's slides, their samples, the alignment model, the
 * review items, the selected item (as a value), the editor's view mode and the batch run's
 * progress. Every mutator is called on the FX thread; {@link #score} runs anywhere.
 */
public final class CohortSession {

    public enum ViewMode { THIS_SLIDE, ALL_SLIDES }

    public record SlideRef(String id, String name) {}

    public record Snapshot(String referenceSlideId, List<SlideSample> samples, AlignmentModel.Cache cache) {}

    public record Scored(AlignmentModel model, ReviewScorer.Result review, List<String> columnKeys,
                         String suggestedReferenceId) {}

    public static final String REFERENCE_MISSING =
            "Reference slide is not in this project — correction is off; reference numbers are used as raw thresholds";

    private static final ReviewScorer.Result NO_REVIEW = new ReviewScorer.Result(List.of(), List.of());

    /** Volatile: the live pass reads it through {@link #lookup} on its own thread. */
    private volatile List<SlideRef> slides = List.of();
    private final Map<String, SlideSample> samples = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private boolean sampling;
    private AlignmentModel.Cache cache = AlignmentModel.Cache.empty();
    private volatile AlignmentModel model = AlignmentModel.empty();
    private volatile boolean correctionDisabled;
    private ReviewScorer.Result review = NO_REVIEW;
    private String referenceSlideId;
    private String suggestedReferenceId;
    private ReviewItem.Key selected;
    private ViewMode viewMode = ViewMode.THIS_SLIDE;
    private boolean batchRunning;
    private String batchProgress;

    /**
     * One stable instance: the live pass reads whatever model is current when it runs. Answers
     * null — every gate on its reference number — while the cohort is unavailable (fewer than two
     * slides) or the reference slide is not in the project.
     */
    private final AlignmentLookup lookup = (slideId, column) ->
            slides.size() < 2 || correctionDisabled ? null : model.alignment(slideId, column);

    public void setProjectSlides(List<SlideRef> refs) {
        slides = List.copyOf(refs);
        Set<String> ids = new HashSet<>();
        for (SlideRef r : refs) ids.add(r.id());
        samples.keySet().retainAll(ids);
        failures.keySet().retainAll(ids);
        updateCorrectionDisabled();
    }

    public List<SlideRef> projectSlides() { return slides; }

    public void setCache(AlignmentModel.Cache cache) { this.cache = cache == null ? AlignmentModel.Cache.empty() : cache; }

    public void samplingStarted() {
        sampling = true;
        samples.clear();
        failures.clear();
    }

    public void landed(CohortSampler.Outcome outcome) {
        switch (outcome) {
            case CohortSampler.Sampled s -> { samples.put(s.slideId(), s.sample()); failures.remove(s.slideId()); }
            case CohortSampler.Failed f -> { failures.put(f.slideId(), f.reason()); samples.remove(f.slideId()); }
        }
    }

    public void samplingFinished() { sampling = false; }

    public Snapshot snapshot(GateTree tree) {
        referenceSlideId = tree.getReferenceSlideId();
        updateCorrectionDisabled();
        return new Snapshot(referenceSlideId, List.copyOf(samples.values()), cache);
    }

    public static Scored score(Snapshot snapshot, GateTree treeCopy) {
        if (snapshot.referenceSlideId() == null || snapshot.samples().size() < 2) {
            return new Scored(AlignmentModel.empty(), NO_REVIEW, List.of(), null);
        }
        Set<AlignmentModel.ColumnRef> columns = AlignmentModel.columnsOf(treeCopy);
        AlignmentModel model = AlignmentModel.build(snapshot.referenceSlideId(), snapshot.samples(), columns, snapshot.cache());
        ReviewScorer.Result review = ReviewScorer.score(treeCopy, snapshot.samples(), model);
        List<String> keys = columns.stream().map(AlignmentModel.ColumnRef::key).toList();
        return new Scored(model, review, keys, mostTypical(model, snapshot.samples(), keys));
    }

    /** The slide whose L1 shifts sit closest to the cohort median across every column; null under 3 slides. */
    private static String mostTypical(AlignmentModel model, List<SlideSample> samples, List<String> keys) {
        if (samples.size() < 3 || keys.isEmpty()) return null;
        Map<String, Double> distance = new LinkedHashMap<>();
        for (SlideSample s : samples) distance.put(s.slideId(), 0.0);
        for (String key : keys) {
            double[] shifts = samples.stream().map(s -> model.alignment(s.slideId(), key))
                    .filter(a -> a != null).mapToDouble(Alignment::shift).toArray();
            if (shifts.length == 0) continue;
            // The one cohort median (pre-flight ruling C6), not a local upper median.
            double median = CohortStats.median(shifts);
            for (SlideSample s : samples) {
                Alignment a = model.alignment(s.slideId(), key);
                distance.merge(s.slideId(), a == null ? Double.POSITIVE_INFINITY : Math.abs(a.shift() - median), Double::sum);
            }
        }
        return distance.entrySet().stream().filter(e -> Double.isFinite(e.getValue()))
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** @return true when any sampled slide's alignment for any scored column changed */
    public boolean adopt(Scored scored) {
        AlignmentModel previous = model;
        boolean changed = false;
        for (String slideId : samples.keySet()) {
            for (String key : scored.columnKeys()) {
                if (!same(previous.alignment(slideId, key), scored.model().alignment(slideId, key))) changed = true;
            }
        }
        model = scored.model();
        cache = scored.model().cache();
        review = scored.review();
        suggestedReferenceId = scored.suggestedReferenceId();
        return changed;
    }

    private static boolean same(Alignment a, Alignment b) {
        if (a == null || b == null) return a == b;
        return a.kind() == b.kind() && a.stretch() == b.stretch() && a.shift() == b.shift()
                && a.offset() == b.offset() && a.cofactor() == b.cofactor();
    }

    private void updateCorrectionDisabled() {
        correctionDisabled = referenceSlideId != null && slides.size() >= 2
                && slides.stream().noneMatch(s -> s.id().equals(referenceSlideId));
    }

    public AlignmentLookup lookup() { return lookup; }
    public AlignmentModel model() { return model; }
    public ReviewScorer.Result review() { return review; }
    public List<SlideSample> samples() { return List.copyOf(samples.values()); }
    public SlideSample sample(String slideId) { return samples.get(slideId); }

    public String slideName(String slideId) {
        for (SlideRef r : slides) if (r.id().equals(slideId)) return r.name();
        return null;
    }

    public void select(ReviewItem.Key key) { selected = key; }

    public ReviewItem selected() {
        if (selected == null) return null;
        for (ReviewItem i : review.items()) if (i.key().equals(selected)) return i;
        return null;
    }

    public ReviewItem.Key step(int delta) {
        List<ReviewItem> items = review.items();
        if (items.isEmpty()) return null;
        int at = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(selected)) at = i;
        int next = at < 0 ? 0 : Math.floorMod(at + delta, items.size());
        selected = items.get(next).key();
        return selected;
    }

    /** The live gate an item names, by value: the enabled gate at {@code (rootIndex, gatePath)}. */
    public static GateNode liveGate(GateTree live, ReviewItem.Key key) {
        for (GateWalk.Entry e : GateWalk.enabled(live)) {
            if (e.rootIndex() == key.rootIndex() && e.gatePath().equals(key.gatePath())) return e.gate();
        }
        return null;
    }

    public ViewMode viewMode() { return viewMode; }
    public void setViewMode(ViewMode mode) { viewMode = mode == null ? ViewMode.THIS_SLIDE : mode; }

    public void batchStarted() { batchRunning = true; batchProgress = "Running on all slides…"; }

    public void batchProgress(int done, int total, String name) {
        batchProgress = String.format(Locale.US, "Running on all slides %d/%d: %s", done, total, name);
    }

    public void batchFinished() { batchRunning = false; batchProgress = null; }

    public CohortState state() {
        if (slides.size() < 2) return CohortState.UNAVAILABLE;
        String message = batchRunning ? batchProgress
                : correctionDisabled ? REFERENCE_MISSING
                : sampling ? String.format(Locale.US, "Sampling slides %d/%d…", samples.size() + failures.size(), slides.size())
                : !failures.isEmpty() ? String.format(Locale.US, "%d slide(s) could not be sampled", failures.size())
                : null;
        String suggested = suggestedReferenceId != null && !suggestedReferenceId.equals(referenceSlideId)
                ? slideName(suggestedReferenceId) : null;
        return new CohortState(true, sampling, samples.size(), slides.size(), failures.size(), review.items().size(),
                referenceSlideId == null ? null : slideName(referenceSlideId), suggested, correctionDisabled, message,
                batchRunning, !batchRunning);
    }

    /**
     * Make {@code newReferenceId} the reference: every gate's numbers become the values applied on
     * that slide, so no slide's applied value moves except by the change of alignment basis. A
     * {@code Manual} on the new reference becomes its reference number; a {@code Skip} there leaves
     * the numbers as they were.
     */
    public static void rebaseReference(GateTree tree, String newReferenceId, AlignmentLookup lookup) {
        TreeResolver.ResolvedTree r = TreeResolver.resolve(tree, newReferenceId, lookup);
        for (var e : r.appliedByLive().entrySet()) {
            TreeResolver.Applied a = e.getValue();
            if (a.sources().contains(TreeResolver.Source.SKIPPED)) continue;
            a.applied().writeTo(e.getKey());
            if (a.sources().contains(TreeResolver.Source.MANUAL)) e.getKey().setSlideSetting(newReferenceId, null);
        }
        tree.setReferenceSlideId(newReferenceId);
    }
}
