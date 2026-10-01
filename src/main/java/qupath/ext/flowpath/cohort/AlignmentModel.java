package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.LogScale;
import qupath.ext.flowpath.model.cohort.Landmarks;

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
     * they were found from; each {@link Landmarks} carries the scale it was found on.
     */
    public record SlideEntry(String fingerprint, Map<String, Landmarks> columns) {}

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
    private final Map<String, Map<String, Landmarks>> landmarks;   // slide -> column -> landmarks
    private final Map<String, Map<String, Alignment>> alignments;  // slide -> column -> alignment
    private final Map<String, Map<String, String>> unusual;        // slide -> column -> reason
    private final Cache cache;

    private AlignmentModel(String referenceSlideId, boolean referenceMissing,
                           Map<String, Map<String, Landmarks>> landmarks, Map<String, Map<String, Alignment>> alignments,
                           Map<String, Map<String, String>> unusual, Cache cache) {
        this.referenceSlideId = referenceSlideId;
        this.referenceMissing = referenceMissing;
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
        return new AlignmentModel(null, false, Map.of(), Map.of(), Map.of(), cache);
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
     * Find every sample's landmarks for {@code columns}. Temporary (rewritten in Task 3): every
     * slide, the reference included, is given {@link Alignment#identity()} until the UniFORM
     * shift lands. A slide's cached landmarks are reused when its {@link SlideSample#cacheKey}
     * matches and they were found on the same {@link LogScale}.
     */
    public static AlignmentModel build(String referenceSlideId, List<SlideSample> samples,
                                       Set<ColumnRef> columns, Cache cache) {
        LogScale scale = LogScale.LN;
        Map<String, SlideEntry> cachedSlides = new HashMap<>(cache.slides());
        Map<String, Map<String, Landmarks>> landmarks = new HashMap<>();
        boolean referenceSampled = false;
        for (SlideSample s : samples) if (s.slideId().equals(referenceSlideId)) referenceSampled = true;

        for (SlideSample s : samples) {
            SlideEntry cached = cachedSlides.get(s.slideId());
            boolean fresh = cached != null && cached.fingerprint().equals(s.cacheKey());
            Map<String, Landmarks> kept = new HashMap<>(fresh ? cached.columns() : Map.of());
            Map<String, Landmarks> perColumn = new HashMap<>();
            for (ColumnRef col : columns) {
                String key = col.key();
                if (s.index().getMarkerIndex(col.channel()) < 0) {
                    kept.remove(key);
                    continue;
                }
                Landmarks lm = kept.get(key);
                if (lm == null || lm.scale() != scale) {
                    double[] raw = s.index().column(col.channel(), col.compartment(), col.statistic(), s.stats()).values();
                    lm = Landmarks.find(raw, s.clean(), scale);
                    kept.put(key, lm);
                }
                perColumn.put(key, lm);
            }
            landmarks.put(s.slideId(), perColumn);
            cachedSlides.put(s.slideId(), new SlideEntry(s.cacheKey(), Map.copyOf(kept)));
        }

        Map<String, Map<String, Alignment>> alignments = new HashMap<>();
        if (referenceSampled) {
            for (ColumnRef col : columns) {
                String key = col.key();
                if (landmarks.get(referenceSlideId).get(key) == null) continue;
                for (SlideSample s : samples) {
                    if (landmarks.get(s.slideId()).get(key) == null) continue;
                    alignments.computeIfAbsent(s.slideId(), k -> new HashMap<>()).put(key, Alignment.identity());
                }
            }
        }
        return new AlignmentModel(referenceSlideId, !referenceSampled, landmarks, alignments, new HashMap<>(),
                new Cache(Map.copyOf(cachedSlides)));
    }

    /**
     * The sample's clean values for {@code col}, or null when the slide lacks the channel.
     * Package-private so {@link ReferenceRanking} reads exactly the values alignment reads.
     */
    static double[] cleanValues(SlideSample s, ColumnRef col) {
        if (s.index().getMarkerIndex(col.channel()) < 0) return null;
        double[] raw = s.index().column(col.channel(), col.compartment(), col.statistic(), s.stats()).values();
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

    /** A human-readable "unusual staining" reason for {@code (slideId, columnKey)}, or null when typical. */
    public String unusualStaining(String slideId, String columnKey) {
        Map<String, String> m = unusual.get(slideId);
        return m == null ? null : m.get(columnKey);
    }

    public boolean referenceMissing() { return referenceMissing; }
    public String referenceSlideId() { return referenceSlideId; }
    public Cache cache() { return cache; }
}
