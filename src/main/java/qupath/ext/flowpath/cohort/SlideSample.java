package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerStats;

/**
 * One slide's fixed-seed sample: the index over the sampled detections, the clean mask (quality
 * filter + ROI) positional against it, statistics over the clean cells, the slide's full
 * detection count, and a fingerprint of the sample (the alignment cache key).
 */
public record SlideSample(String slideId, String name, CellIndex index, boolean[] clean, MarkerStats stats,
                          int detectionCount, String fingerprint) {}
