package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.CohortStats;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per slide x column alignment to the reference slide, computed once from the fixed-seed samples
 * and never from a gate: two gates on CD8 share one alignment, and a child gate's small parent
 * population never destabilises it. What the live view, the review list and the batch run all
 * use, so what is reviewed is what runs.
 * <p>
 * Lives in {@code cohort} rather than {@code model/cohort} (documented plan deviation): it
 * consumes {@link SlideSample} and {@link GateTree}, which would make {@code model/cohort}
 * depend upward on both {@code cohort} and {@code model} in a way the other classes there do not.
 */
public final class AlignmentModel {

    static final int MIN_SLIDES_FOR_SPREAD = 3;
    static final double MIN_MAD = 0.05;
    static final double MAD_LIMIT = 3.0;

    /** One measurement column: the same triple every gate axis resolves through {@link CellIndex#column}. */
    public record ColumnRef(String channel, Compartment compartment, Statistic statistic) {
        public String key() {
            return CellIndex.keyFor(channel, compartment, statistic);
        }
    }

    /**
     * One slide's cached landmarks, keyed by column, tagged with the {@link SlideSample#cacheKey}
     * they were found from; each {@link Landmarks} carries the cofactor it was found with.
     */
    public record SlideEntry(String fingerprint, Map<String, Landmarks> columns) {}

    /**
     * The persisted alignment cache: one {@link SlideEntry} per slide, and the cofactor each column
     * had in the build that wrote it (a record — every build recomputes it from the reference).
     */
    public record Cache(Map<String, Double> cofactors, Map<String, SlideEntry> slides) {
        public static Cache empty() {
            return new Cache(Map.of(), Map.of());
        }

        public boolean isEmpty() {
            return cofactors.isEmpty() && slides.isEmpty();
        }
    }

    private final String referenceSlideId;
    private final boolean referenceMissing;
    private final Map<String, Double> cofactors;
    private final Map<String, Map<String, Landmarks>> landmarks;   // slide -> column -> landmarks
    private final Map<String, Map<String, Alignment>> alignments;  // slide -> column -> alignment
    private final Map<String, Map<String, String>> unusual;        // slide -> column -> reason
    private final Cache cache;

    private AlignmentModel(String referenceSlideId, boolean referenceMissing, Map<String, Double> cofactors,
                           Map<String, Map<String, Landmarks>> landmarks, Map<String, Map<String, Alignment>> alignments,
                           Map<String, Map<String, String>> unusual, Cache cache) {
        this.referenceSlideId = referenceSlideId;
        this.referenceMissing = referenceMissing;
        this.cofactors = cofactors;
        this.landmarks = landmarks;
        this.alignments = alignments;
        this.unusual = unusual;
        this.cache = cache;
    }

    public static AlignmentModel empty() {
        return empty(Cache.empty());
    }

    /**
     * No alignments, but carrying {@code cache}: what a score that could not align anything
     * (no reference, fewer than two samples) hands back, so the persisted landmarks survive it
     * rather than being replaced by nothing.
     */
    public static AlignmentModel empty(Cache cache) {
        return new AlignmentModel(null, false, Map.of(), Map.of(), Map.of(), Map.of(), cache);
    }

    /** Every axis column of every gate in {@code tree}, enabled or not. */
    public static Set<ColumnRef> columnsOf(GateTree tree) {
        Set<ColumnRef> out = new LinkedHashSet<>();
        collect(tree.getRoots(), out);
        return out;
    }

    private static void collect(List<GateNode> nodes, Set<ColumnRef> out) {
        for (GateNode gate : nodes) {
            List<String> channels = gate.getChannels();
            for (int k = 0; k < GateAxis.axisCount(gate) && k < channels.size(); k++) {
                String channel = channels.get(k);
                if (channel != null && !channel.isEmpty()) {
                    out.add(new ColumnRef(channel, gate.compartmentAt(k), gate.statisticAt(k)));
                }
            }
            for (Branch b : gate.getBranches()) collect(b.getChildren(), out);
        }
    }

    /**
     * Align every sample to {@code referenceSlideId}'s for each of {@code columns}.
     * <p>
     * A column's asinh cofactor is the median |x| over the <b>reference slide's</b> clean sample
     * for that column ({@link Landmarks#cofactor}) — a function of the reference alone, so it does
     * not depend on which samples happened to have arrived first, and a run with no cache
     * reproduces a cached one exactly. With the reference not sampled (missing, failed, not yet
     * landed) no cofactor is known and no landmark is found for any slide: there is nothing to
     * align to.
     * <p>
     * A slide's cached landmarks are reused when its {@link SlideSample#cacheKey} — the sample
     * fingerprint plus the filter and ROI its clean mask came from — matches and they were found
     * with the cofactor in force now; anything else is found again.
     */
    public static AlignmentModel build(String referenceSlideId, List<SlideSample> samples,
                                       Set<ColumnRef> columns, Cache cache) {
        Map<String, SlideEntry> cachedSlides = new HashMap<>(cache.slides());
        Map<String, Map<String, Landmarks>> landmarks = new HashMap<>();
        SlideSample reference = null;
        for (SlideSample s : samples) if (s.slideId().equals(referenceSlideId)) reference = s;
        boolean referenceSampled = reference != null;

        Map<String, Double> cofactors = new HashMap<>();
        for (ColumnRef col : columns) {
            double[] v = reference == null ? null : cleanValues(reference, col);
            if (v != null) cofactors.put(col.key(), Landmarks.cofactor(v));
        }

        for (SlideSample s : samples) {
            SlideEntry cached = cachedSlides.get(s.slideId());
            boolean fresh = cached != null && cached.fingerprint().equals(s.cacheKey());
            Map<String, Landmarks> kept = new HashMap<>(fresh ? cached.columns() : Map.of());
            Map<String, Landmarks> perColumn = new HashMap<>();
            for (ColumnRef col : columns) {
                String key = col.key();
                Double c = cofactors.get(key);
                if (s.index().getMarkerIndex(col.channel()) < 0 || c == null) {
                    kept.remove(key);
                    continue;
                }
                Landmarks lm = kept.get(key);
                if (lm == null || Double.compare(lm.cofactor(), c) != 0) {
                    MeasuredColumn column = s.index().column(col.channel(), col.compartment(), col.statistic(), s.stats());
                    lm = Landmarks.find(column.values(), column.withoutRoundFailures(s.clean()), c);
                    kept.put(key, lm);
                }
                perColumn.put(key, lm);
            }
            landmarks.put(s.slideId(), perColumn);
            cachedSlides.put(s.slideId(), new SlideEntry(s.cacheKey(), Map.copyOf(kept)));
        }

        Map<String, Map<String, Alignment>> alignments = new HashMap<>();
        Map<String, Map<String, String>> unusual = new HashMap<>();
        for (ColumnRef col : columns) {
            String key = col.key();
            Landmarks ref = referenceSampled ? landmarks.get(referenceSlideId).get(key) : null;
            List<Double> shifts = new ArrayList<>();
            List<Double> stretches = new ArrayList<>();
            for (SlideSample s : samples) {
                Landmarks lm = landmarks.get(s.slideId()).get(key);
                if (ref == null || lm == null) continue;
                Alignment a = s.slideId().equals(referenceSlideId) ? Alignment.identity() : Alignment.between(ref, lm);
                alignments.computeIfAbsent(s.slideId(), k -> new HashMap<>()).put(key, a);
                if (lm.hasL1() && ref.hasL1()) shifts.add(lm.l1() - ref.l1());
                if (lm.hasL2() && ref.hasL2()) stretches.add((lm.l2() - lm.l1()) / (ref.l2() - ref.l1()));
            }
            // "Unusual staining" is judged only when at least 3 slides have an L1 (spec §13).
            if (shifts.size() < MIN_SLIDES_FOR_SPREAD) continue;
            double[] shiftValues = toArray(shifts);
            double medShift = CohortStats.median(shiftValues);
            double madShift = Math.max(MIN_MAD, CohortStats.mad(shiftValues, medShift));
            boolean haveStretch = stretches.size() >= MIN_SLIDES_FOR_SPREAD;
            double[] stretchValues = toArray(stretches);
            double medStretch = stretchValues.length == 0 ? 1.0 : CohortStats.median(stretchValues);
            double madStretch = Math.max(MIN_MAD, stretchValues.length == 0 ? 0 : CohortStats.mad(stretchValues, medStretch));
            for (SlideSample s : samples) {
                Landmarks lm = landmarks.get(s.slideId()).get(key);
                if (lm == null || !lm.hasL1() || ref == null || !ref.hasL1()) continue;
                double shift = lm.l1() - ref.l1();
                String reason = null;
                if (Math.abs(shift - medShift) > MAD_LIMIT * madShift) {
                    // asinh ~= log for bright values, so exp(|shift|) reads as a fold-change factor.
                    reason = String.format(Locale.US, "Staining %.1f× %s than typical",
                            Math.exp(Math.abs(shift - medShift)), shift > medShift ? "brighter" : "dimmer");
                } else if (haveStretch && lm.hasL2() && ref.hasL2()) {
                    double stretch = (lm.l2() - lm.l1()) / (ref.l2() - ref.l1());
                    if (Math.abs(stretch - medStretch) > MAD_LIMIT * madStretch) {
                        boolean higher = stretch > medStretch;
                        reason = String.format(Locale.US, "Staining contrast %.1f× %s than typical",
                                higher ? stretch / medStretch : medStretch / stretch, higher ? "higher" : "lower");
                    }
                }
                if (reason != null) unusual.computeIfAbsent(s.slideId(), k -> new HashMap<>()).put(key, reason);
            }
        }
        return new AlignmentModel(referenceSlideId, !referenceSampled, Map.copyOf(cofactors), landmarks,
                alignments, unusual, new Cache(Map.copyOf(cofactors), Map.copyOf(cachedSlides)));
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    /** The sample's clean values for {@code col}, or null when the slide lacks the channel. */
    private static double[] cleanValues(SlideSample s, ColumnRef col) {
        if (s.index().getMarkerIndex(col.channel()) < 0) return null;
        MeasuredColumn column = s.index().column(col.channel(), col.compartment(), col.statistic(), s.stats());
        double[] raw = column.values();
        boolean[] keep = column.withoutRoundFailures(s.clean());
        double[] out = new double[raw.length];
        int n = 0;
        for (int i = 0; i < raw.length; i++) if (keep[i]) out[n++] = raw[i];
        return Arrays.copyOf(out, n);
    }

    /** The alignment for {@code (slideId, columnKey)}, or null when unknown; pass {@code model::alignment} as an {@code AlignmentLookup}. */
    public Alignment alignment(String slideId, String columnKey) {
        Map<String, Alignment> m = alignments.get(slideId);
        return m == null ? null : m.get(columnKey);
    }

    /** {@code slideId}'s landmarks for {@code columnKey}, or null when the slide lacks the column or was not sampled. */
    public Landmarks landmarks(String slideId, String columnKey) {
        Map<String, Landmarks> m = landmarks.get(slideId);
        return m == null ? null : m.get(columnKey);
    }

    public Landmarks referenceLandmarks(String columnKey) {
        return referenceMissing ? null : landmarks(referenceSlideId, columnKey);
    }

    /** The cofactor for {@code columnKey} (the reference slide's median |x|); NaN when unknown. */
    public double cofactor(String columnKey) {
        Double c = cofactors.get(columnKey);
        return c == null ? Double.NaN : c;
    }

    /** A human-readable "unusual staining" reason for {@code (slideId, columnKey)}, or null when typical. */
    public String unusualStaining(String slideId, String columnKey) {
        Map<String, String> m = unusual.get(slideId);
        return m == null ? null : m.get(columnKey);
    }

    public boolean referenceMissing() { return referenceMissing; }
    public String referenceSlideId() { return referenceSlideId; }
    public Cache cache() { return cache; }
}
