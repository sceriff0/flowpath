package qupath.ext.flowpath.cohort;

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
import qupath.ext.flowpath.model.cohort.LogScale;
import qupath.ext.flowpath.model.cohort.Otsu;
import qupath.ext.flowpath.model.cohort.UniformShift;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per slide x column alignment to the reference slide, computed once from the fixed-seed samples
 * and never from a gate: two gates on CD8 share one alignment, and a child gate's small parent
 * population never destabilises it. What the live view, the review list and the batch run all
 * use, so what is reviewed is what runs.
 * <p>
 * The alignment is UniFORM's feature-level registration (Wang et al. 2025, Cell Rep Methods,
 * PMC12539234 [FULL: main text + STAR Methods]; code kunlunW/UniFORM @ c750a9a [FULL]): per
 * column, the slides' values on the {@link LogScale}, a {@link UniformShift#BINS}-bin histogram over
 * the column's global range across slides, and one integer bin shift per slide — the automatic
 * mode's cross-correlation peak ({@link Alignment.Kind#AUTO}), or, for a slide with a hand-picked
 * negative peak, the landmark mode's {@code bin(peak_slide) - bin(peak_reference)}
 * ({@link Alignment.Kind#LANDMARK}). Alongside it, {@link ColumnDiagnostics} records what the
 * problem layer judges (spec §3); none of it moves a threshold.
 * <p>
 * Departures from UniFORM (spec §2):
 * <ol>
 *   <li>One reference per tree, chosen by the user; UniFORM picks one per marker.</li>
 *   <li>The input is the sampled clean cells for the column a gate uses (any compartment and
 *       statistic); UniFORM uses every cell's mean intensity.</li>
 *   <li>Exact integer cross-correlation, first maximum on a tie; UniFORM's FFT path can differ
 *       only on an exact tie.</li>
 *   <li>In landmark mode, a reference with no hand-picked peak uses the detector's lowest peak
 *       ({@link Landmarks#l1()}); UniFORM requires every landmark picked by hand.</li>
 *   <li>A slide or reference with fewer than {@link #MIN_USABLE} usable values is left
 *       uncorrected and flagged {@link ColumnDiagnostics#tooFew()}; UniFORM crashes or returns a
 *       meaningless shift there.</li>
 * </ol>
 * <p>
 * Lives in {@code cohort} rather than {@code model/cohort} (documented plan deviation): it
 * consumes {@link SlideSample} and {@link GateTree}, which would make {@code model/cohort}
 * depend upward on both {@code cohort} and {@code model} in a way the other classes there do not.
 */
public final class AlignmentModel {

    /** Fewer usable values than this on a slide or on the reference: not corrected (departure 5). */
    public static final int MIN_USABLE = 50;
    /** Automatic and detector shifts further apart than this (log units) raise peak lock. */
    static final double PEAK_LOCK_LOG = Math.log(1.5);
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
     * One slide's cached landmarks, keyed by column, all found on {@code scale}, tagged with the
     * {@link #fingerprint} (sample cache key plus scale token) they were found from.
     */
    public record SlideEntry(String fingerprint, LogScale scale, Map<String, Landmarks> columns) {
        public SlideEntry {
            columns = Map.copyOf(columns);
            for (Landmarks lm : columns.values()) {
                if (lm.scale() != scale) {
                    throw new IllegalArgumentException("a slide entry on " + scale.token()
                            + " holds landmarks found on " + lm.scale().token());
                }
            }
        }
    }

    /** The persisted alignment cache: one {@link SlideEntry} per slide. */
    public record Cache(Map<String, SlideEntry> slides) {
        public static Cache empty() {
            return new Cache(Map.of());
        }

        public boolean isEmpty() {
            return slides.isEmpty();
        }
    }

    private final String referenceSlideId;
    private final boolean referenceMissing;
    private final LogScale scale;
    private final Map<String, Map<String, Landmarks>> landmarks;          // slide -> column -> landmarks
    private final Map<String, Map<String, Alignment>> alignments;         // slide -> column -> alignment
    private final Map<String, UniformShift.Grid> grids;                   // column -> grid
    private final Map<String, Map<String, long[]>> histograms;            // slide -> column -> histogram
    private final Map<String, Map<String, ColumnDiagnostics>> diagnostics; // slide -> column -> diagnostics
    private final Map<String, Double> referencePeaks;                     // column -> log peak
    private final Cache cache;

    private AlignmentModel(String referenceSlideId, boolean referenceMissing, LogScale scale,
                           Map<String, Map<String, Landmarks>> landmarks, Map<String, Map<String, Alignment>> alignments,
                           Map<String, UniformShift.Grid> grids, Map<String, Map<String, long[]>> histograms,
                           Map<String, Map<String, ColumnDiagnostics>> diagnostics, Map<String, Double> referencePeaks,
                           Cache cache) {
        this.referenceSlideId = referenceSlideId;
        this.referenceMissing = referenceMissing;
        this.scale = scale;
        this.landmarks = frozen(landmarks);
        this.alignments = frozen(alignments);
        this.grids = Map.copyOf(grids);
        this.histograms = frozen(histograms);
        this.diagnostics = frozen(diagnostics);
        this.referencePeaks = Map.copyOf(referencePeaks);
        this.cache = cache;
    }

    private static <V> Map<String, Map<String, V>> frozen(Map<String, Map<String, V>> nested) {
        Map<String, Map<String, V>> out = new HashMap<>();
        nested.forEach((k, v) -> out.put(k, Map.copyOf(v)));
        return Map.copyOf(out);
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
        return new AlignmentModel(null, false, LogScale.LN, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), cache);
    }

    /** What a slide's cached landmarks are keyed on: its sample's cache key and the scale they were found on. */
    public static String fingerprint(SlideSample s, LogScale scale) {
        return s.cacheKey() + "|" + scale.token();
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

    /** One slide's values for one column, on the scale. */
    private record Logs(SlideSample sample, double[] logs, int usable, int outsideDomain) {}

    /**
     * Align every sample to {@code referenceSlideId} on every column in {@code columns}.
     * A slide's cached landmarks are reused when its {@link SlideEntry#fingerprint} equals
     * {@link #fingerprint}{@code (sample, scale)}.
     *
     * @param peaks slide id → (column key → raw intensity of a hand-picked negative peak); picks
     *              for a slide not in {@code samples} or a column not in {@code columns} are ignored
     */
    public static AlignmentModel build(String referenceSlideId, List<SlideSample> samples, Set<ColumnRef> columns,
                                       Cache cache, LogScale scale, Map<String, Map<String, Double>> peaks) {
        Map<String, SlideEntry> cachedSlides = new HashMap<>(cache.slides());
        Map<String, Map<String, Landmarks>> landmarks = new HashMap<>();
        boolean referenceSampled = false;
        for (SlideSample s : samples) if (s.slideId().equals(referenceSlideId)) referenceSampled = true;

        for (SlideSample s : samples) {
            String fingerprint = fingerprint(s, scale);
            SlideEntry cached = cachedSlides.get(s.slideId());
            boolean fresh = cached != null && cached.fingerprint().equals(fingerprint) && cached.scale() == scale;
            Map<String, Landmarks> kept = new HashMap<>(fresh ? cached.columns() : Map.of());
            Map<String, Landmarks> perColumn = new HashMap<>();
            for (ColumnRef col : columns) {
                String key = col.key();
                if (s.index().getMarkerIndex(col.channel()) < 0) {
                    kept.remove(key);
                    continue;
                }
                Landmarks lm = kept.get(key);
                if (lm == null) {
                    lm = Landmarks.find(rawValues(s, col), s.clean(), scale);
                    kept.put(key, lm);
                }
                perColumn.put(key, lm);
            }
            landmarks.put(s.slideId(), perColumn);
            cachedSlides.put(s.slideId(), new SlideEntry(fingerprint, scale, kept));
        }

        Map<String, Map<String, Alignment>> alignments = new HashMap<>();
        Map<String, UniformShift.Grid> grids = new HashMap<>();
        Map<String, Map<String, long[]>> histograms = new HashMap<>();
        Map<String, Map<String, ColumnDiagnostics>> diagnostics = new HashMap<>();
        Map<String, Double> referencePeaks = new HashMap<>();

        for (ColumnRef col : columns) {
            String key = col.key();
            List<Logs> present = new ArrayList<>();
            for (SlideSample s : samples) {
                if (s.index().getMarkerIndex(col.channel()) >= 0) present.add(logsOf(s, col, scale));
            }
            if (present.isEmpty()) continue;
            UniformShift.Grid grid = UniformShift.grid(present.stream().map(Logs::logs).toList());
            grids.put(key, grid);
            Map<String, long[]> hist = new HashMap<>();
            for (Logs l : present) {
                long[] h = UniformShift.histogram(l.logs(), grid);
                hist.put(l.sample().slideId(), h);
                histograms.computeIfAbsent(l.sample().slideId(), k -> new HashMap<>()).put(key, h);
            }

            Logs ref = null;
            for (Logs l : present) if (l.sample().slideId().equals(referenceSlideId)) ref = l;
            if (!referenceSampled || ref == null) continue;

            Landmarks refLm = landmarks.get(referenceSlideId).get(key);
            Double refPick = pick(peaks, referenceSlideId, key);
            double refPeak = refPick != null ? scale.toLog(refPick) : refLm.l1();
            referencePeaks.put(key, refPeak);

            Map<String, Alignment> aligned = new HashMap<>();
            Map<String, Boolean> tooFew = new HashMap<>();
            for (Logs l : present) {
                String id = l.sample().slideId();
                boolean few = !id.equals(referenceSlideId)
                        && (!grid.usable() || l.usable() < MIN_USABLE || ref.usable() < MIN_USABLE);
                tooFew.put(id, few);
                Alignment a = alignmentFor(l, id.equals(referenceSlideId), few, pick(peaks, id, key), refPeak,
                        hist.get(id), hist.get(referenceSlideId), grid, scale);
                aligned.put(id, a);
                alignments.computeIfAbsent(id, k -> new HashMap<>()).put(key, a);
            }

            Map<String, Double> discordance = otsuDiscordance(present, aligned, tooFew, referenceSlideId);
            double median = cohortMedianLogShift(present, aligned, referenceSlideId);
            double mad = Double.isNaN(median) ? Double.NaN
                    : Math.max(MIN_MAD, CohortStats.mad(correctedShifts(present, aligned, referenceSlideId), median));

            for (Logs l : present) {
                String id = l.sample().slideId();
                boolean isRef = id.equals(referenceSlideId);
                diagnostics.computeIfAbsent(id, k -> new HashMap<>()).put(key, diagnosticsFor(l, isRef,
                        tooFew.get(id), aligned.get(id), landmarks.get(id).get(key), refLm, discordance.get(id),
                        median, mad, !isRef && pick(peaks, id, key) != null));
            }
        }
        return new AlignmentModel(referenceSlideId, !referenceSampled, scale, landmarks, alignments, grids, histograms,
                diagnostics, referencePeaks, new Cache(Map.copyOf(cachedSlides)));
    }

    private static double[] rawValues(SlideSample s, ColumnRef col) {
        return s.index().column(col.channel(), col.compartment(), col.statistic(), s.stats()).values();
    }

    /** Rule 1: the clean cells on the scale, how many are usable and how many a finite raw value left outside its domain. */
    private static Logs logsOf(SlideSample s, ColumnRef col, LogScale scale) {
        double[] raw = rawValues(s, col);
        boolean[] clean = s.clean();
        double[] logs = Landmarks.toLog(raw, clean, scale);
        int usable = 0, outside = 0;
        for (int i = 0; i < raw.length; i++) {
            if (Double.isFinite(logs[i])) usable++;
            else if ((clean == null || clean[i]) && Double.isFinite(raw[i])) outside++;
        }
        return new Logs(s, logs, usable, outside);
    }

    private static Double pick(Map<String, Map<String, Double>> peaks, String slideId, String columnKey) {
        Map<String, Double> m = peaks.get(slideId);
        return m == null ? null : m.get(columnKey);
    }

    /**
     * Rule 3. A picked peak outside the scale's domain (or a reference peak that is NaN) cannot be
     * binned, so the slide is left uncorrected rather than given a meaningless landmark shift.
     */
    private static Alignment alignmentFor(Logs l, boolean isReference, boolean tooFew, Double picked, double refPeak,
                                          long[] hist, long[] refHist, UniformShift.Grid grid, LogScale scale) {
        if (isReference || tooFew) return Alignment.identity();
        if (picked != null) {
            double peak = scale.toLog(picked);
            if (Double.isNaN(peak) || Double.isNaN(refPeak)) return Alignment.identity();
            return Alignment.landmark(UniformShift.binOf(peak, grid) - UniformShift.binOf(refPeak, grid), grid.binWidth());
        }
        return Alignment.auto(UniformShift.shiftBins(hist, refHist), grid.binWidth());
    }

    /**
     * Rule 6: each slide's own Otsu threshold against the pooled cohort's, on the corrected log
     * values; the pool is the reference plus every slide actually corrected. NaN when too few.
     */
    private static Map<String, Double> otsuDiscordance(List<Logs> present, Map<String, Alignment> aligned,
                                                       Map<String, Boolean> tooFew, String referenceSlideId) {
        Map<String, double[]> normalised = new HashMap<>();
        int pooledSize = 0;
        for (Logs l : present) {
            String id = l.sample().slideId();
            double[] norm = normalised(l.logs(), aligned.get(id).logShift());
            normalised.put(id, norm);
            if (inPool(id, aligned.get(id), referenceSlideId)) pooledSize += norm.length;
        }
        double[] pooled = new double[pooledSize];
        int at = 0;
        for (Logs l : present) {
            String id = l.sample().slideId();
            if (!inPool(id, aligned.get(id), referenceSlideId)) continue;
            double[] norm = normalised.get(id);
            System.arraycopy(norm, 0, pooled, at, norm.length);
            at += norm.length;
        }
        double pooledThreshold = Otsu.threshold(pooled);
        Map<String, Double> out = new HashMap<>();
        for (Logs l : present) {
            String id = l.sample().slideId();
            double[] norm = normalised.get(id);
            double own = Otsu.threshold(norm);
            // Otsu.discordance with a NaN threshold would count every cell as discordant.
            boolean judged = !tooFew.get(id) && !Double.isNaN(own) && !Double.isNaN(pooledThreshold);
            out.put(id, judged ? Otsu.discordance(norm, own, pooledThreshold) : Double.NaN);
        }
        return out;
    }

    private static boolean inPool(String id, Alignment a, String referenceSlideId) {
        return id.equals(referenceSlideId) || a.kind() != Alignment.Kind.IDENTITY;
    }

    private static double[] normalised(double[] logs, double logShift) {
        double[] out = new double[logs.length];
        int n = 0;
        for (double u : logs) if (Double.isFinite(u)) out[n++] = u - logShift;
        return Arrays.copyOf(out, n);
    }

    /** Rule 7's population: the log shifts of every corrected, non-reference slide. */
    private static double[] correctedShifts(List<Logs> present, Map<String, Alignment> aligned, String referenceSlideId) {
        return present.stream().map(l -> l.sample().slideId())
                .filter(id -> !id.equals(referenceSlideId) && aligned.get(id).kind() != Alignment.Kind.IDENTITY)
                .mapToDouble(id -> aligned.get(id).logShift()).toArray();
    }

    private static double cohortMedianLogShift(List<Logs> present, Map<String, Alignment> aligned, String referenceSlideId) {
        double[] shifts = correctedShifts(present, aligned, referenceSlideId);
        return shifts.length >= MIN_SLIDES_FOR_SPREAD ? CohortStats.median(shifts) : Double.NaN;
    }

    /** Rules 5 and 7, and the counts of rule 1, as one record. */
    private static ColumnDiagnostics diagnosticsFor(Logs l, boolean isReference, boolean tooFew, Alignment a,
                                                    Landmarks lm, Landmarks refLm, double discordance,
                                                    double median, double mad, boolean peakPicked) {
        int seen = l.usable() + l.outsideDomain();
        double outsideFraction = seen == 0 ? 0.0 : l.outsideDomain() / (double) seen;
        double detector = lm != null && lm.hasL1() && refLm != null && refLm.hasL1() ? lm.l1() - refLm.l1() : Double.NaN;
        boolean peakLock = a.kind() == Alignment.Kind.AUTO && !Double.isNaN(detector)
                && Math.abs(a.logShift() - detector) > PEAK_LOCK_LOG;
        boolean outlier = !isReference && a.kind() != Alignment.Kind.IDENTITY && !Double.isNaN(median)
                && Math.abs(a.logShift() - median) > MAD_LIMIT * mad;
        return new ColumnDiagnostics(l.usable(), l.outsideDomain(), outsideFraction, tooFew, peakLock, detector,
                discordance, outlier, median, peakPicked);
    }

    /**
     * The sample's clean values for {@code col}, or null when the slide lacks the channel.
     * Package-private so {@link ReferenceRanking} reads exactly the values alignment reads.
     */
    static double[] cleanValues(SlideSample s, ColumnRef col) {
        if (s.index().getMarkerIndex(col.channel()) < 0) return null;
        double[] raw = rawValues(s, col);
        double[] out = new double[raw.length];
        int n = 0;
        for (int i = 0; i < raw.length; i++) if (s.clean()[i]) out[n++] = raw[i];
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

    /** The scale every landmark, grid and shift in this model is on. */
    public LogScale scale() { return scale; }

    /** The column's histogram range across the sampled slides, or null when no slide carries it. */
    public UniformShift.Grid grid(String columnKey) { return grids.get(columnKey); }

    /** A copy of {@code slideId}'s histogram for {@code columnKey} on {@link #grid}, or null when unknown. */
    public long[] histogram(String slideId, String columnKey) {
        Map<String, long[]> m = histograms.get(slideId);
        long[] h = m == null ? null : m.get(columnKey);
        return h == null ? null : h.clone();
    }

    /** What aligning {@code (slideId, columnKey)} measured, or null when it was not aligned. */
    public ColumnDiagnostics diagnostics(String slideId, String columnKey) {
        Map<String, ColumnDiagnostics> m = diagnostics.get(slideId);
        return m == null ? null : m.get(columnKey);
    }

    /**
     * The log value landmark mode measures shifts against on {@code columnKey}: the reference's
     * hand-picked peak, else its detector L1 (departure 4); NaN when there is none.
     */
    public double referencePeak(String columnKey) {
        Double p = referencePeaks.get(columnKey);
        return p == null ? Double.NaN : p;
    }

    public boolean referenceMissing() { return referenceMissing; }
    public String referenceSlideId() { return referenceSlideId; }
    public Cache cache() { return cache; }
}
