package qupath.ext.flowpath.cohort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestOptions;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RegionMask;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.interfaces.ROI;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Samples every project slide with a fixed seed, headlessly. Each slide's outcome is a value:
 * a slide that cannot be read (including an {@link OutOfMemoryError}) is reported and skipped,
 * never the end of the run.
 */
public final class CohortSampler {

    private static final Logger logger = LoggerFactory.getLogger(CohortSampler.class);

    public static final long SEED = 0x5EED_F10DL;

    public sealed interface Outcome permits Sampled, Failed {
        String slideId();
        String name();
    }

    public record Sampled(SlideSample sample) implements Outcome {
        @Override public String slideId() { return sample.slideId(); }
        @Override public String name() { return sample.name(); }
    }

    public record Failed(String slideId, String name, String reason) implements Outcome {}

    private CohortSampler() {}

    public static List<Outcome> sampleAll(List<SlideSource> sources, GateTree tree, int cellsPerSlide,
                                          Consumer<Outcome> onSlide, BooleanSupplier cancelled) {
        List<Outcome> out = new ArrayList<>();
        for (SlideSource source : sources) {
            if (cancelled.getAsBoolean()) break;
            Outcome outcome = sampleOne(source, tree, cellsPerSlide);
            out.add(outcome);
            onSlide.accept(outcome);
        }
        return out;
    }

    public static Outcome sampleOne(SlideSource source, GateTree tree, int cellsPerSlide) {
        try {
            PathObjectHierarchy hierarchy = source.readHierarchy();
            List<PathObject> detections = new ArrayList<>(hierarchy.getDetectionObjects());
            if (detections.isEmpty()) return new Failed(source.id(), source.name(), "no detections on this slide");
            List<PathObject> sampled = draw(detections, cellsPerSlide, SEED ^ source.id().hashCode());
            CellIndex index = DetectionIngest.read(sampled, IngestOptions.none()).index();
            boolean[] clean = cleanMask(index, tree, new ArrayList<>(hierarchy.getAnnotationObjects()));
            return new Sampled(new SlideSample(source.id(), source.name(), index, clean,
                    MarkerStats.compute(index, clean), detections.size(),
                    fingerprint(cellsPerSlide, detections.size(), sampled)));
        } catch (Exception | Error ex) {
            // Error too: sampling every cell of a million-cell slide is where an OutOfMemoryError
            // is plausible, and it must not end the run or strand the sampler flag.
            logger.warn("Could not sample {}", source.name(), ex);
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Failed(source.id(), source.name(), reason);
        }
    }

    /** The clean mask exactly as {@code GatingSession.derive} builds it: quality filter, then ROI. */
    static boolean[] cleanMask(CellIndex index, GateTree tree, List<PathObject> annotations) {
        boolean[] mask = new boolean[index.size()];
        Arrays.fill(mask, true);
        if (tree.getQualityFilter() != null) mask = GatingEngine.computeQualityMask(index, tree.getQualityFilter());
        if (tree.isRoiFilterEnabled()) {
            RegionMask regions = RegionMask.compute(index, annotations);
            if (!regions.isEmpty()) mask = GatingEngine.combineMasks(mask, regions.included());
        }
        return mask;
    }

    /** A fixed-seed sample of {@code cellsPerSlide} detections, in their original order; all when 0 or more. */
    public static List<PathObject> draw(List<PathObject> detections, int cellsPerSlide, long seed) {
        int n = detections.size();
        if (cellsPerSlide <= 0 || cellsPerSlide >= n) return List.copyOf(detections);
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Random random = new Random(seed);
        for (int i = 0; i < cellsPerSlide; i++) {
            int j = i + random.nextInt(n - i);
            int t = idx[i]; idx[i] = idx[j]; idx[j] = t;
        }
        int[] chosen = Arrays.copyOf(idx, cellsPerSlide);
        Arrays.sort(chosen);
        List<PathObject> out = new ArrayList<>(cellsPerSlide);
        for (int i : chosen) out.add(detections.get(i));
        return out;
    }

    /** SHA-256 over the setting, the detection count and every sampled cell's centroid. */
    public static String fingerprint(int cellsPerSlide, int detectionCount, List<PathObject> sampled) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buf = ByteBuffer.allocate(16);
            buf.putInt(cellsPerSlide).putInt(detectionCount).putLong(sampled.size());
            digest.update(buf.array());
            for (PathObject o : sampled) {
                ROI roi = o.getROI();
                buf.clear();
                buf.putDouble(roi == null ? Double.NaN : roi.getCentroidX());
                buf.putDouble(roi == null ? Double.NaN : roi.getCentroidY());
                digest.update(buf.array());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }
}
