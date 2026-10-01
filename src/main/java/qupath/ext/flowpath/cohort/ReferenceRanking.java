package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.model.cohort.LogScale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which slide to suggest as the cohort's reference, and why (spec §4.2). Pure.
 * <p>
 * <b>Eligibility</b> uses the alignment's own landmark finder: a slide R qualifies only
 * if, on every gated column, {@code Landmarks.find(R's clean values, null, scale)} has an L1 — exactly what {@link AlignmentModel#build} computes when R is the
 * reference — and at least the cohort's modal landmark count (fdaNorm's rule, Hahne et al. 2010).
 * <p>
 * <b>The suggestion</b> is the medoid of the eligible slides under the L1 distance between
 * normalised histograms on the same log scale, summed over the gated columns. flowLearn (Lux et al. 2018)
 * chooses its prototype by k-medoids on L1 density distance per channel; summing over channels to
 * pick ONE slide is a FlowPath extension, because every gate is drawn on one slide. The medoid now
 * runs on UniFORM's scale, so eligibility, alignment and ranking read one transform.
 */
public final class ReferenceRanking {

    public static final int GRID_BINS = 256;
    public static final int MIN_ELIGIBLE = 3;

    private ReferenceRanking() {}

    public record SlideRank(String slideId, String name, boolean eligible, List<String> ineligibleBecause,
                            double score, int columnsMostCentral) {
        public SlideRank {
            ineligibleBecause = List.copyOf(ineligibleBecause);
        }
    }

    public record Result(String suggestedId, List<SlideRank> slides, Map<String, String> columnMedoids,
                         Map<String, Integer> modalLandmarks, List<String> uncorrectableColumns,
                         boolean heterogeneous, int gatedColumns) {

        public static final Result NONE = new Result(null, List.of(), Map.of(), Map.of(), List.of(), false, 0);

        public Result {
            slides = List.copyOf(slides);
            // Insertion-ordered, so the banner's notes come out in column order every time.
            columnMedoids = Collections.unmodifiableMap(new LinkedHashMap<>(columnMedoids));
            modalLandmarks = Collections.unmodifiableMap(new LinkedHashMap<>(modalLandmarks));
            uncorrectableColumns = List.copyOf(uncorrectableColumns);
        }

        public SlideRank rank(String slideId) {
            for (SlideRank r : slides) if (r.slideId().equals(slideId)) return r;
            return null;
        }

        public long eligibleCount() {
            return slides.stream().filter(SlideRank::eligible).count();
        }

        /** "most central on k of n gated columns" for the suggestion; null without one. */
        public String reason() {
            SlideRank r = suggestedId == null ? null : rank(suggestedId);
            if (r == null) return null;
            return String.format(Locale.US, "most central on %d of %d gated columns", r.columnsMostCentral(), gatedColumns);
        }

        /** 1-based position among eligible slides by score; 0 when ineligible or unknown. */
        public int position(String slideId) {
            List<SlideRank> ordered = slides.stream().filter(SlideRank::eligible).sorted(ORDER).toList();
            for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).slideId().equals(slideId)) return i + 1;
            return 0;
        }

        /**
         * What the banner says about {@code referenceId} as the confirmed reference: fewer landmarks
         * than the mode, columns whose medoid is another slide, and a heterogeneous cohort.
         */
        public List<String> notesFor(String referenceId, Function<String, String> names) {
            List<String> out = new ArrayList<>();
            SlideRank r = rank(referenceId);
            if (r != null) out.addAll(r.ineligibleBecause().stream()
                    .map(reason -> names.apply(referenceId) + ": " + reason).toList());
            columnMedoids.forEach((column, medoid) -> {
                if (!medoid.equals(referenceId)) out.add("for " + column + " the most central slide is " + names.apply(medoid));
            });
            uncorrectableColumns.forEach(c -> out.add(c + ": no slide has a clear negative peak — " + c + " is not corrected"));
            if (heterogeneous) out.add("this cohort looks heterogeneous — per-group references are not supported yet");
            return out;
        }
    }

    /** Lowest score first, then name, then id: deterministic. */
    static final Comparator<SlideRank> ORDER = Comparator.comparingDouble(SlideRank::score)
            .thenComparing(SlideRank::name).thenComparing(SlideRank::slideId);

    public static Result rank(List<SlideSample> samples, Set<AlignmentModel.ColumnRef> columns, LogScale scale) {
        if (samples.isEmpty() || columns.isEmpty()) return Result.NONE;
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        for (SlideSample s : samples) reasons.put(s.slideId(), new ArrayList<>());

        // Landmarks per (slide, column), on the log scale: what alignment would find
        // with that slide as the reference.
        Map<String, Map<String, Landmarks>> landmarks = new HashMap<>();
        Map<String, Map<String, double[]>> values = new HashMap<>();
        for (SlideSample s : samples) {
            Map<String, Landmarks> perColumn = new HashMap<>();
            Map<String, double[]> perValues = new HashMap<>();
            for (AlignmentModel.ColumnRef col : columns) {
                double[] v = AlignmentModel.cleanValues(s, col);
                if (v == null) continue;
                perValues.put(col.key(), v);
                perColumn.put(col.key(), Landmarks.find(v, null, scale));
            }
            landmarks.put(s.slideId(), perColumn);
            values.put(s.slideId(), perValues);
        }

        Map<String, Integer> modal = new LinkedHashMap<>();
        List<String> uncorrectable = new ArrayList<>();
        // Columns some slide can be corrected on; one no slide has a negative peak on is reported, not ranked.
        List<AlignmentModel.ColumnRef> usable = new ArrayList<>();
        for (AlignmentModel.ColumnRef col : columns) {
            String label = label(col, columns);
            Map<Integer, Integer> counts = new HashMap<>();
            boolean anyL1 = false;
            for (SlideSample s : samples) {
                Landmarks lm = landmarks.get(s.slideId()).get(col.key());
                if (lm == null) continue;
                counts.merge(count(lm), 1, Integer::sum);
                anyL1 |= lm.hasL1();
            }
            // Mode, ties to the larger count: a reference short of a peak most slides show is the risk.
            int mode = counts.entrySet().stream()
                    .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).orElse(0);
            modal.put(label, mode);
            if (!anyL1) {
                uncorrectable.add(label);
                continue;
            }
            usable.add(col);
            for (SlideSample s : samples) {
                Landmarks lm = landmarks.get(s.slideId()).get(col.key());
                List<String> why = reasons.get(s.slideId());
                if (lm == null) why.add(label + " not measured");
                else if (!lm.hasL1()) why.add("no negative peak on " + label);
                else if (count(lm) < mode) why.add("shows no " + label + "+ peak; most slides do");
            }
        }

        List<SlideSample> eligible = samples.stream().filter(s -> reasons.get(s.slideId()).isEmpty()).toList();
        Map<String, Double> score = new HashMap<>();
        Map<String, Integer> central = new HashMap<>();
        Map<String, String> columnMedoids = new LinkedHashMap<>();
        for (SlideSample s : eligible) score.put(s.slideId(), 0.0);
        if (eligible.size() >= 2) {
            for (AlignmentModel.ColumnRef col : usable) {
                double[][] h = histograms(eligible, values, col.key(), scale);
                String best = null;
                double bestSum = Double.POSITIVE_INFINITY;
                for (int i = 0; i < eligible.size(); i++) {
                    double sum = 0;
                    for (int j = 0; j < eligible.size(); j++) if (i != j) sum += l1(h[i], h[j]);
                    String id = eligible.get(i).slideId();
                    score.merge(id, sum, Double::sum);
                    if (sum < bestSum || (sum == bestSum && tieBefore(eligible.get(i), best, eligible))) {
                        bestSum = sum;
                        best = id;
                    }
                }
                columnMedoids.put(label(col, columns), best);
                central.merge(best, 1, Integer::sum);
            }
        }

        List<SlideRank> ranks = new ArrayList<>();
        for (SlideSample s : samples) {
            boolean ok = reasons.get(s.slideId()).isEmpty();
            ranks.add(new SlideRank(s.slideId(), s.name(), ok, reasons.get(s.slideId()),
                    ok ? score.getOrDefault(s.slideId(), 0.0) : Double.POSITIVE_INFINITY,
                    central.getOrDefault(s.slideId(), 0)));
        }
        String suggested = usable.isEmpty() || eligible.size() < MIN_ELIGIBLE ? null
                : ranks.stream().filter(SlideRank::eligible).min(ORDER).map(SlideRank::slideId).orElse(null);
        Set<String> distinctMedoids = new LinkedHashSet<>(columnMedoids.values());
        // A FlowPath heuristic: flowLearn shows more prototypes help on diverse cohorts; the "more than half" cut-off is ours.
        boolean heterogeneous = usable.size() > 1 && eligible.size() >= MIN_ELIGIBLE
                && distinctMedoids.size() * 2 > eligible.size();
        return new Result(suggested, ranks, columnMedoids, modal, uncorrectable, heterogeneous, usable.size());
    }

    private static boolean tieBefore(SlideSample candidate, String currentBest, List<SlideSample> all) {
        if (currentBest == null) return true;
        SlideSample best = all.stream().filter(s -> s.slideId().equals(currentBest)).findFirst().orElseThrow();
        int byName = candidate.name().compareTo(best.name());
        return byName != 0 ? byName < 0 : candidate.slideId().compareTo(best.slideId()) < 0;
    }

    private static int count(Landmarks lm) {
        return lm.count();
    }

    /** The column's channel, or its full key when another gated column shares the channel. */
    static String label(AlignmentModel.ColumnRef col, Set<AlignmentModel.ColumnRef> all) {
        long same = all.stream().filter(c -> c.channel().equals(col.channel())).count();
        return same > 1 ? col.key() : col.channel();
    }

    /** Normalised histograms of {@code scale.toLog(x)} (out-of-domain values skipped) on one grid shared by the eligible slides. */
    private static double[][] histograms(List<SlideSample> slides, Map<String, Map<String, double[]>> values, String key,
                                         LogScale scale) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (SlideSample s : slides) {
            for (double v : values.get(s.slideId()).get(key)) {
                double u = scale.toLog(v);
                if (Double.isNaN(u)) continue;
                lo = Math.min(lo, u);
                hi = Math.max(hi, u);
            }
        }
        double[][] out = new double[slides.size()][GRID_BINS];
        if (!(hi > lo)) return out;
        double width = (hi - lo) / GRID_BINS;
        for (int i = 0; i < slides.size(); i++) {
            int n = 0;
            for (double v : values.get(slides.get(i).slideId()).get(key)) {
                double u = scale.toLog(v);
                if (Double.isNaN(u)) continue;
                int bin = (int) Math.min(GRID_BINS - 1, (u - lo) / width);
                out[i][bin]++;
                n++;
            }
            if (n > 0) for (int b = 0; b < GRID_BINS; b++) out[i][b] /= n;
        }
        return out;
    }

    private static double l1(double[] a, double[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d;
    }
}
