package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.CleanMask;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.lib.objects.PathObject;

import java.util.List;

/**
 * One slide's fixed-seed sample: the index over the sampled detections, the clean mask (quality
 * filter + ROI) positional against it, statistics over the clean cells, the slide's full
 * detection count, a fingerprint of the sampled detections, the slide's annotations (detached
 * copies: shape and class, what the ROI filter reads) and the {@code scope} — the
 * {@link CleanMask#inputsDigest} of the filter and ROI the clean mask was built for.
 * <p>
 * The clean mask belongs to the tree being scored, not to the tree the slide happened to be
 * sampled under: {@link #scopedTo} re-derives it (through {@link CleanMask}, the composition the
 * live pass uses) whenever the tree's quality filter or ROI filter differ from {@code scope}, so a
 * filter the user loads or edits after sampling reaches every landmark, flag, rule, curve and crop.
 * A sample built with the seven-argument constructor carries a mask given by hand and no scope.
 */
public record SlideSample(String slideId, String name, CellIndex index, boolean[] clean, MarkerStats stats,
                          int detectionCount, String fingerprint, List<PathObject> annotations, String scope) {

    public SlideSample {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** A sample whose clean mask is given, unscoped, with no annotations. */
    public SlideSample(String slideId, String name, CellIndex index, boolean[] clean, MarkerStats stats,
                       int detectionCount, String fingerprint) {
        this(slideId, name, index, clean, stats, detectionCount, fingerprint, List.of(), null);
    }

    /** {@link #scopedTo(QualityFilter, boolean)} with {@code tree}'s quality filter and ROI flag. */
    public SlideSample scopedTo(GateTree tree) {
        return scopedTo(tree.getQualityFilter(), tree.isRoiFilterEnabled());
    }

    /**
     * This sample with its clean mask and statistics for {@code filter} and the ROI flag; itself
     * when it already describes them (the rescore after every gating pass costs a digest, not a
     * re-sort, until a filter actually changes).
     */
    public SlideSample scopedTo(QualityFilter filter, boolean roiFilterEnabled) {
        String digest = CleanMask.inputsDigest(filter, roiFilterEnabled, annotations);
        if (digest.equals(scope)) return this;
        boolean[] mask = CleanMask.of(index, filter, roiFilterEnabled, annotations).cleanOrAll(index.size());
        return new SlideSample(slideId, name, index, mask, MarkerStats.compute(index, mask), detectionCount,
                fingerprint, annotations, digest);
    }

    /**
     * What this slide's cached landmarks are keyed on: the sample fingerprint and, once scoped, the
     * filter and ROI inputs its clean mask came from — so a filter change misses the cache and
     * anything else hits it.
     */
    public String cacheKey() {
        return scope == null ? fingerprint : fingerprint + "@" + scope;
    }
}
