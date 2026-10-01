package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.CohortStats;
import qupath.ext.flowpath.model.cohort.LogScale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Toolkit-free owner of the cohort: the project's slides, their samples, the alignment model, the
 * review items, the selected item (as a value), the editor's view mode and the batch run's
 * progress. Every mutator is called on the FX thread; {@link #score} runs anywhere.
 */
public final class CohortSession {

    public enum ViewMode { THIS_SLIDE, ALL_SLIDES }

    public record SlideRef(String id, String name) {}

    /**
     * What {@link #score} needs. {@code ranking} is the last adopted ranking with the inputs it was
     * computed from; {@link #score} reuses it while they are unchanged, since every gating pass
     * rescores and the ranking (landmarks and log histograms per slide × column, on the cohort's log scale) does not
     * depend on anything a pass changes.
     */
    public record Snapshot(String referenceSlideId, List<SlideSample> samples, AlignmentModel.Cache cache,
                           RankingMemo ranking, LogScale scale, Map<String, Map<String, Double>> peaks) {
        public Snapshot {
            ranking = ranking == null ? RankingMemo.NONE : ranking;
            scale = scale == null ? LogScale.LN : scale;
            peaks = peaks == null ? Map.of() : peaks;
        }
    }

    /** A ranking and the key of its inputs ({@link #rankingKey}); see {@link Snapshot}. */
    public record RankingMemo(String key, ReferenceRanking.Result result) {
        public static final RankingMemo NONE = new RankingMemo(null, ReferenceRanking.Result.NONE);
    }

    /**
     * One scoring: the model, the review, the columns scored, the reference ranking, and the
     * samples as scored — each {@linkplain SlideSample#scopedTo scoped} to the scored tree's quality
     * filter and ROI, which {@link #adopt} keeps so the curves and crops read the same clean cells.
     */
    public record Scored(AlignmentModel model, ReviewScorer.Result review, List<String> columnKeys,
                         ReferenceRanking.Result ranking, List<SlideSample> samples, String rankingKey) {
        public Scored {
            samples = List.copyOf(samples);
            ranking = ranking == null ? ReferenceRanking.Result.NONE : ranking;
        }
    }

    /** One slide's state on the slide strip (spec §6 "Slide strip and status line"). */
    public enum SlideStatus { SAMPLING, READY, NEEDS_LOOK, FAILED, EXCLUDED }

    /**
     * One square of the slide strip: its status, and what its tooltip says — the failure reason
     * when {@link SlideStatus#FAILED}, else how many cells were sampled and how many of its
     * gates need a look.
     */
    public record SlideSquare(String slideId, String name, SlideStatus status, int cells, int items, String failure) {}

    public static final String REFERENCE_MISSING =
            "Reference slide is not in this project — correction is off; reference numbers are used as raw thresholds";

    /**
     * The live tree's reference slide is excluded from the cohort: it has no sample, so nothing can
     * be aligned to it. Correction is off — never an identity model that looks like an all-clear —
     * until it is included again or another reference is picked.
     */
    public static final String REFERENCE_EXCLUDED =
            "Reference slide is excluded — include it or pick another reference";

    /**
     * A rebase re-expresses every threshold through the current reference's alignments; with the
     * current reference unsampled, or the model not built for it, there are none, and its raw
     * numbers would be copied onto the new reference as that slide's thresholds.
     */
    public static final String REBASE_NOT_ALIGNED =
            "The current reference has not been aligned yet — include it (or wait for sampling) before switching";

    public static final String FOREIGN_TREE =
            "This gate tree's per-slide settings belong to another project — correction and slide settings are off";

    /**
     * The live tree names no reference slide: every slide gates on the tree's own numbers,
     * uncorrected, until a reference is confirmed (spec 2026-09-30 §4.1). Said in words, never
     * left to look like an all-clear.
     */
    public static final String NO_REFERENCE =
            "No reference slide — thresholds are not corrected between slides; pick one in the Cohort window";

    private static final ReviewScorer.Result NO_REVIEW = new ReviewScorer.Result(List.of(), List.of());

    /** Which project {@link #slides} belong to; a change drops everything sampled for the last one. */
    private String projectKey;
    /** Volatile: the live pass reads it through {@link #lookup} on its own thread. */
    private volatile List<SlideRef> slides = List.of();
    private volatile Map<String, String> projectNames = Map.of();
    private final Map<String, SlideSample> samples = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private boolean sampling;
    private AlignmentModel.Cache cache = AlignmentModel.Cache.empty();
    private volatile AlignmentModel model = AlignmentModel.empty();
    /** The live tree's reference slide is not in the project, or is excluded from the cohort. */
    private volatile boolean correctionDisabled;
    /** Why {@link #correctionDisabled}: the reference is excluded (else it is missing from the project). */
    private volatile boolean referenceExcluded;
    /** The live tree's recorded slide names contradict this project's (see {@link CohortIdentity}). */
    private volatile boolean foreign;
    private ReviewScorer.Result review = NO_REVIEW;
    /** The live tree's reference slide, as last handed in by {@link #setLiveTree} or {@link #snapshot}. */
    private volatile String referenceSlideId;
    private ReferenceRanking.Result ranking = ReferenceRanking.Result.NONE;
    /** The adopted ranking with its inputs' key, handed to the next {@link #score} to reuse. */
    private RankingMemo rankingMemo = RankingMemo.NONE;
    /** Slides the project marks {@code flowpath.cohort.excluded}: never sampled, ranked or reviewed. */
    private volatile Set<String> excluded = Set.of();
    /** The project's log scale and hand-picked peaks (slide -> column key -> raw intensity). */
    private volatile LogScale scale = LogScale.LN;
    private volatile Map<String, Map<String, Double>> peaks = Map.of();
    private ReviewItem.Key selected;
    /** The gate whose group is being reviewed, as a value; see {@link #selectGroup}. */
    private ReviewGroup.Key selectedGroup;
    private ViewMode viewMode = ViewMode.THIS_SLIDE;
    private boolean batchRunning;
    private String batchProgress;

    /**
     * One stable instance: the live pass reads whatever model is current when it runs. Answers
     * null — every gate on its reference number — while the cohort is unavailable (fewer than two
     * slides), while the reference slide is not in the project, while the tree belongs to another
     * project, and while the current model was built for a reference other than the live tree's
     * (a reference just changed, its rescore not yet landed): alignments to the old reference
     * applied to numbers now meant for the new one would move every threshold wrongly.
     */
    private final AlignmentLookup lookup = this::currentAlignment;

    private Alignment currentAlignment(String slideId, String column) {
        AlignmentModel m = model;
        return !excluded.contains(slideId) && answers(m) ? m.alignment(slideId, column) : null;
    }

    /** Whether the lookup may answer from {@code m} as the session now stands. */
    private boolean answers(AlignmentModel m) {
        String reference = referenceSlideId;
        return slides.size() >= 2 && !correctionDisabled && !foreign && reference != null
                && reference.equals(m.referenceSlideId());
    }

    /**
     * {@link #lookup()} frozen now, on {@link #model()} as it is now: for work that runs later on
     * another thread against a model it captured (an evidence crop), so a rescore landing
     * meanwhile cannot hand it alignments from a different model than the one it keyed on.
     */
    public AlignmentLookup lookupOn(AlignmentModel m) {
        if (m == null || !answers(m)) return AlignmentLookup.NONE;
        return (slideId, column) -> excluded.contains(slideId) ? null : m.alignment(slideId, column);
    }

    /**
     * The project's slides, and which project they belong to. Entry ids restart in every
     * project, so a change of {@code projectKey} drops every sample, failure, alignment, cached
     * landmark, review item and selection held for the previous project — kept by id, they would
     * be the other project's slides' numbers.
     *
     * @return whether the project changed: a caller holding its own id-keyed state (a selection, a
     *         pending click-through) must drop it too
     */
    public boolean setProject(String projectKey, List<SlideRef> refs) {
        boolean changed = !Objects.equals(this.projectKey, projectKey);
        if (changed) {
            this.projectKey = projectKey;
            samples.clear();
            failures.clear();
            cache = AlignmentModel.Cache.empty();
            model = AlignmentModel.empty();
            review = NO_REVIEW;
            ranking = ReferenceRanking.Result.NONE;
            rankingMemo = RankingMemo.NONE;
            excluded = Set.of();
            selected = null;
            selectedGroup = null;
        }
        setProjectSlides(refs);
        return changed;
    }

    /** The slides of the current project; see {@link #setProject} for a change of project. */
    public void setProjectSlides(List<SlideRef> refs) {
        slides = List.copyOf(refs);
        Map<String, String> names = new LinkedHashMap<>();
        for (SlideRef r : refs) names.put(r.id(), r.name());
        projectNames = Collections.unmodifiableMap(names);
        Set<String> ids = new HashSet<>();
        for (SlideRef r : refs) ids.add(r.id());
        samples.keySet().retainAll(ids);
        failures.keySet().retainAll(ids);
        updateCorrectionDisabled();
    }

    public List<SlideRef> projectSlides() { return slides; }

    /** The project's images, id → name. */
    public Map<String, String> projectNames() { return projectNames; }

    /**
     * The live tree as the next gating pass will read it: its reference slide, and whether it
     * belongs to this project. Called before every pass, so the lookup never answers for a
     * reference the model was not built for.
     */
    public void setLiveTree(GateTree tree) {
        referenceSlideId = tree.getReferenceSlideId();
        foreign = !CohortIdentity.matches(tree, projectNames);
        updateCorrectionDisabled();
    }

    public void setCache(AlignmentModel.Cache cache) { this.cache = cache == null ? AlignmentModel.Cache.empty() : cache; }

    /** The project's excluded slides; any sample or failure held for one is dropped now. */
    public void setExcluded(Set<String> slideIds) {
        excluded = Set.copyOf(slideIds);
        updateCorrectionDisabled();
        samples.keySet().removeAll(excluded);
        failures.keySet().removeAll(excluded);
        // Read-side guarantee: whatever a scoring already landed holds nothing for an excluded slide.
        review = new ReviewScorer.Result(
                review.items().stream().filter(i -> !excluded.contains(i.key().slideId())).toList(),
                review.infos().stream().filter(i -> !excluded.contains(i.slideId())).toList(),
                review.rules());
    }

    public Set<String> excluded() { return excluded; }

    public void setScale(LogScale scale) { this.scale = scale == null ? LogScale.LN : scale; }

    public LogScale scale() { return scale; }

    /** slide id -&gt; column key -&gt; raw intensity; copied so a later edit of the argument cannot reach a scoring. */
    public void setPeaks(Map<String, Map<String, Double>> peaks) {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        if (peaks != null) peaks.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
        this.peaks = Collections.unmodifiableMap(copy);
    }

    public Map<String, Map<String, Double>> peaks() { return peaks; }

    public void samplingStarted() {
        sampling = true;
        samples.clear();
        failures.clear();
    }

    /** A run that samples more slides, keeping every sample and failure already held. */
    public void samplingResumed() {
        sampling = true;
    }

    public void landed(CohortSampler.Outcome outcome) {
        String slideId = switch (outcome) {
            case CohortSampler.Sampled s -> s.slideId();
            case CohortSampler.Failed f -> f.slideId();
        };
        if (excluded.contains(slideId)) return;
        switch (outcome) {
            case CohortSampler.Sampled s -> { samples.put(s.slideId(), s.sample()); failures.remove(s.slideId()); }
            case CohortSampler.Failed f -> { failures.put(f.slideId(), f.reason()); samples.remove(f.slideId()); }
        }
    }

    public void samplingFinished() { sampling = false; }

    /** What {@link #score} needs, taken on the FX thread; a foreign tree is scored as having no reference. */
    public Snapshot snapshot(GateTree tree) {
        setLiveTree(tree);
        return new Snapshot(foreign ? null : referenceSlideId, List.copyOf(samples.values()), cache, rankingMemo, scale, peaks);
    }

    /**
     * Align and review the cohort for {@code treeCopy}. Every sample is first
     * {@linkplain SlideSample#scopedTo scoped} to the tree's own quality filter and ROI (spec §3:
     * landmarks come from the slide's clean cells), so a filter loaded or edited after sampling
     * reaches every landmark, flag and rule — a no-op per sample while the filter is unchanged.
     */
    public static Scored score(Snapshot snapshot, GateTree treeCopy) {
        List<SlideSample> samples = snapshot.samples().stream().map(s -> s.scopedTo(treeCopy)).toList();
        Set<AlignmentModel.ColumnRef> columns = AlignmentModel.columnsOf(treeCopy);
        // Ranked whether or not a reference exists: the suggestion is what lets one be chosen.
        // Only the columns a slide is corrected on count, and an unchanged input reuses the last answer.
        Set<AlignmentModel.ColumnRef> ranked = rankedColumns(treeCopy);
        String rankingKey = samples.size() >= 2 ? rankingKey(samples, ranked, snapshot.scale()) : null;
        ReferenceRanking.Result ranking = rankingKey == null ? ReferenceRanking.Result.NONE
                : rankingKey.equals(snapshot.ranking().key()) ? snapshot.ranking().result()
                : ReferenceRanking.rank(samples, ranked, snapshot.scale());
        if (snapshot.referenceSlideId() == null || samples.size() < 2) {
            // Nothing to align, but the persisted landmarks carry through: adopting an empty cache
            // here would throw them away and the next write would lose them.
            return new Scored(AlignmentModel.empty(snapshot.cache()), NO_REVIEW, List.of(), ranking, samples, rankingKey);
        }
        AlignmentModel model = AlignmentModel.build(snapshot.referenceSlideId(), samples, columns, snapshot.cache(),
                snapshot.scale(), snapshot.peaks());
        ReviewScorer.Result review = ReviewScorer.score(treeCopy, samples, model);
        List<String> keys = columns.stream().map(AlignmentModel.ColumnRef::key).toList();
        return new Scored(model, review, keys, ranking, samples, rankingKey);
    }

    /**
     * The columns the reference ranking scores: those of the enabled gates ({@link GateWalk#enabled})
     * with Correct staining on — the columns a slide is actually corrected on. A disabled or
     * opted-out gate would otherwise count toward "most central on k of n" and could make every
     * slide ineligible over a column nothing is aligned on.
     */
    public static Set<AlignmentModel.ColumnRef> rankedColumns(GateTree tree) {
        Set<AlignmentModel.ColumnRef> out = new LinkedHashSet<>();
        for (GateWalk.Entry e : GateWalk.enabled(tree)) {
            GateNode gate = e.gate();
            if (!gate.isCorrectStaining()) continue;
            List<String> channels = gate.getChannels();
            for (int k = 0; k < GateAxis.axisCount(gate) && k < channels.size(); k++) {
                String channel = channels.get(k);
                if (channel != null && !channel.isEmpty()) {
                    out.add(new AlignmentModel.ColumnRef(channel, gate.compartmentAt(k), gate.statisticAt(k)));
                }
            }
        }
        return out;
    }

    /**
     * Everything {@link ReferenceRanking#rank} reads: each scoped sample's id, name and
     * {@link SlideSample#cacheKey} (fingerprint plus the filter and ROI of its clean mask), in
     * order, and the ranked columns.
     */
    static String rankingKey(List<SlideSample> samples, Set<AlignmentModel.ColumnRef> columns, LogScale scale) {
        StringBuilder sb = new StringBuilder();
        for (SlideSample s : samples) {
            sb.append(s.slideId()).append('\u0000').append(s.name()).append('\u0000').append(s.cacheKey()).append('\u0001');
        }
        sb.append('|');
        for (AlignmentModel.ColumnRef c : columns) sb.append(c.key()).append('\u0001');
        sb.append('|').append(scale.token());
        return sb.toString();
    }

    /** @return true when any sampled slide's alignment for any scored column changed */
    public boolean adopt(Scored scored) {
        // A scoring that still holds a slide excluded since it began is stale: the rescore every
        // exclusion change triggers replaces it.
        if (scored.samples().stream().anyMatch(x -> excluded.contains(x.slideId()))) return false;
        AlignmentModel previous = model;
        boolean changed = false;
        for (String slideId : samples.keySet()) {
            for (String key : scored.columnKeys()) {
                if (!sameAlignment(previous.alignment(slideId, key), scored.model().alignment(slideId, key))) changed = true;
            }
        }
        model = scored.model();
        cache = scored.model().cache();
        // The scoped samples replace the ones they were scoped from — only those: a slide
        // re-sampled while this scoring ran keeps its newer sample (a different index).
        for (SlideSample scopedSample : scored.samples()) {
            SlideSample current = samples.get(scopedSample.slideId());
            if (current != null && current.index() == scopedSample.index()) samples.put(scopedSample.slideId(), scopedSample);
        }
        review = scored.review();
        ranking = scored.ranking();
        rankingMemo = scored.rankingKey() == null ? RankingMemo.NONE : new RankingMemo(scored.rankingKey(), ranking);
        return changed;
    }

    /** Whether two alignments map every number identically; package-private for its table test. */
    static boolean sameAlignment(Alignment a, Alignment b) {
        if (a == null || b == null) return a == b;
        return a.kind() == b.kind() && a.shiftBins() == b.shiftBins() && a.binWidth() == b.binWidth();
    }

    private void updateCorrectionDisabled() {
        String reference = referenceSlideId;
        boolean missing = reference != null && slides.size() >= 2
                && slides.stream().noneMatch(s -> s.id().equals(reference));
        referenceExcluded = !missing && reference != null && slides.size() >= 2 && excluded.contains(reference);
        correctionDisabled = missing || referenceExcluded;
    }

    public AlignmentLookup lookup() { return lookup; }
    public AlignmentModel model() { return model; }
    public ReviewScorer.Result review() { return review; }
    public List<SlideSample> samples() { return List.copyOf(samples.values()); }
    public SlideSample sample(String slideId) { return samples.get(slideId); }

    /**
     * Why switching away from {@code currentReference} must wait, or null when it may go ahead (or
     * there is no current reference: confirming one is not a rebase). See {@link #REBASE_NOT_ALIGNED}.
     */
    public String rebaseRefusal(String currentReference) {
        if (currentReference == null) return null;
        boolean aligned = samples.containsKey(currentReference) && !excluded.contains(currentReference)
                && currentReference.equals(model.referenceSlideId());
        return aligned ? null : REBASE_NOT_ALIGNED;
    }

    /** The ranking's suggestion while it is not already the live reference; never a default. */
    public String suggestedReferenceId() {
        if (foreign) return null;
        String id = ranking.suggestedId();
        return id != null && !id.equals(referenceSlideId) && !excluded.contains(id) && projectNames.containsKey(id) ? id : null;
    }

    public ReferenceRanking.Result ranking() { return ranking; }

    /** The names of the slides whose sampling failed, in project order: they run uncorrected. */
    public List<String> failedSlideNames() {
        List<String> out = new ArrayList<>();
        for (SlideRef r : slides) if (failures.containsKey(r.id())) out.add(r.name());
        return out;
    }

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

    /** The review items grouped by gate, top-down (spec §6 "Reviewing by gate"). */
    public List<ReviewGroup> groups() {
        return ReviewGroup.of(review.items());
    }

    /** Review {@code key}'s gate as a group; null clears. Kept by value, so a rescore keeps it. */
    public void selectGroup(ReviewGroup.Key key) {
        selectedGroup = key;
    }

    /** The selected group as the review now stands, or null when none is selected or it has no items left. */
    public ReviewGroup selectedGroup() {
        if (selectedGroup == null) return null;
        for (ReviewGroup g : groups()) if (g.key().equals(selectedGroup)) return g;
        return null;
    }

    /** The slides flagged on the gate at {@code (rootIndex, gatePath)}; empty when none. */
    public Set<String> flaggedSlides(int rootIndex, String gatePath) {
        ReviewGroup.Key key = new ReviewGroup.Key(rootIndex, gatePath);
        for (ReviewGroup g : groups()) if (g.key().equals(key)) return g.slideIds();
        return Set.of();
    }

    /**
     * One square per slide, in project order; empty while the cohort is unavailable (fewer than
     * two slides). The colour is decided here, once — the pane only renders (spec §6).
     */
    public List<SlideSquare> slideStrip() {
        if (slides.size() < 2) return List.of();
        List<SlideSquare> out = new ArrayList<>();
        for (SlideRef r : slides) {
            SlideSample sample = samples.get(r.id());
            int items = (int) review.items().stream().filter(i -> i.key().slideId().equals(r.id())).count();
            SlideStatus status = excluded.contains(r.id()) ? SlideStatus.EXCLUDED
                    : failures.containsKey(r.id()) ? SlideStatus.FAILED
                    : sample == null ? SlideStatus.SAMPLING
                    : items > 0 ? SlideStatus.NEEDS_LOOK : SlideStatus.READY;
            out.add(new SlideSquare(r.id(), r.name(), status, sample == null ? 0 : sample.detectionCount(), items,
                    failures.get(r.id())));
        }
        return out;
    }

    /**
     * {@code "38/40 sampled · 5 to review · Ready to run"} (spec §6); {@code ""} while the cohort
     * is unavailable. The last part says why a run is not ready when it is not: a batch going,
     * sampling, {@code runAllowed} false, or a tree with no reference slide or from another
     * project. {@code runAllowed} is the run button's own predicate
     * ({@code BusyState.batchAllowed}), handed in rather than decided a second time here, so the
     * line can never call a run ready that the button refuses.
     */
    public String statusLine(boolean runAllowed) {
        if (slides.size() < 2) return "";
        String run = batchRunning ? "Running…"
                : sampling ? "Sampling…"
                : !runAllowed ? "Not ready to run"
                : foreign ? "Tree from another project"
                : referenceSlideId == null ? "No reference slide"
                : referenceExcluded ? "Reference slide excluded"
                : "Ready to run";
        return String.format(Locale.US, "%d/%d sampled · %d to review · %s", samples.size(), includedCount(),
                review.items().size(), run);
    }

    /** The project's slides less the excluded ones: what sampling reads. */
    private int includedCount() {
        return slides.size() - (int) slides.stream().filter(r -> excluded.contains(r.id())).count();
    }

    /** What N / P step through, in order: the selected group's items, else every item. */
    public List<ReviewItem> stepOrder() {
        ReviewGroup group = selectedGroup();
        return group == null ? review.items() : group.items();
    }

    public ReviewItem.Key step(int delta) {
        // Task 16 carry: with a group selected, N/P step only through its items.
        List<ReviewItem> items = stepOrder();
        if (items.isEmpty()) return null;
        int at = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(selected)) at = i;
        int next = at < 0 ? 0 : Math.floorMod(at + delta, items.size());
        selected = items.get(next).key();
        return selected;
    }

    /**
     * The live gate an item names, by value: the enabled gate at {@code (rootIndex, gatePath)}.
     * {@link GateWalk} numbers same-label siblings so every path is unique; should two gates still
     * answer to one key, the key is refused (null) rather than an answer landing on whichever
     * came first.
     */
    public static GateNode liveGate(GateTree live, ReviewItem.Key key) {
        GateNode found = null;
        for (GateWalk.Entry e : GateWalk.enabled(live)) {
            if (e.rootIndex() == key.rootIndex() && e.gatePath().equals(key.gatePath())) {
                if (found != null) return null;
                found = e.gate();
            }
        }
        return found;
    }

    public ViewMode viewMode() { return viewMode; }
    public void setViewMode(ViewMode mode) { viewMode = mode == null ? ViewMode.THIS_SLIDE : mode; }

    public void batchStarted() { batchRunning = true; batchProgress = "Running on all slides…"; }

    public void batchProgress(int done, int total, String name) {
        batchProgress = String.format(Locale.US, "Running on all slides %d/%d: %s", done, total, name);
    }

    public void batchFinished() { batchRunning = false; batchProgress = null; }

    /**
     * Why correction between slides is off although the live tree names a reference — a tree from
     * another project, a reference missing from or excluded in this one — or null when it is not
     * off for any of those. The words the banner and the status line use.
     */
    public String correctionOffReason() {
        return foreign ? FOREIGN_TREE
                : correctionDisabled ? (referenceExcluded ? REFERENCE_EXCLUDED : REFERENCE_MISSING)
                : null;
    }

    public CohortState state() {
        if (slides.size() < 2) {
            // Nothing to offer, but a tree from another project is still resolved with no slide
            // settings, and the status line must say why a Manual threshold is not applied.
            return foreign ? new CohortState(false, false, 0, 0, 0, 0, null, null, false, FOREIGN_TREE, false, false)
                    : CohortState.UNAVAILABLE;
        }
        String message = batchRunning ? batchProgress
                : correctionOffReason() != null ? correctionOffReason()
                : referenceSlideId == null ? NO_REFERENCE
                : sampling ? String.format(Locale.US, "Sampling slides %d/%d…", samples.size() + failures.size(), includedCount())
                : !failures.isEmpty() ? String.format(Locale.US, "%d slide(s) could not be sampled", failures.size())
                : null;
        String suggestedId = suggestedReferenceId();
        String suggested = suggestedId == null ? null : slideName(suggestedId);
        return new CohortState(true, sampling, samples.size(), slides.size(), failures.size(), review.items().size(),
                referenceSlideId == null || foreign ? null : slideName(referenceSlideId), suggested,
                correctionDisabled || foreign, message,
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
