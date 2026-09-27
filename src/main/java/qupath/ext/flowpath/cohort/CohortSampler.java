package qupath.ext.flowpath.cohort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestOptions;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MeasurementKeySample;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.interfaces.ROI;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

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
            SlideSample unscoped = new SlideSample(source.id(), source.name(), index, null, null, detections.size(),
                    fingerprint(cellsPerSlide, detections.size(), sampled),
                    detached(hierarchy.getAnnotationObjects()), null);
            // Scoped to the tree it was sampled under; every rescore re-scopes it to the tree scored.
            return new Sampled(unscoped.scopedTo(tree));
        } catch (Exception | Error ex) {
            // Error too: sampling every cell of a million-cell slide is where an OutOfMemoryError
            // is plausible, and it must not end the run or strand the sampler flag.
            // An interrupt (a cancelled script) must outlive this catch, or the caller never sees it.
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.warn("Could not sample {}", source.name(), ex);
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Failed(source.id(), source.name(), reason);
        }
    }

    /**
     * The slide's annotations as the ROI filter reads them — shape, class and name — detached from
     * the hierarchy, so a sample kept for the session does not keep the slide's annotation
     * objects (and through them its hierarchy) alive.
     */
    static List<PathObject> detached(Collection<PathObject> annotations) {
        List<PathObject> out = new ArrayList<>(annotations.size());
        for (PathObject ann : annotations) {
            if (ann == null || ann.getROI() == null) continue;
            PathObject copy = PathObjects.createAnnotationObject(ann.getROI(), ann.getPathClass());
            copy.setName(ann.getName());
            out.add(copy);
        }
        return out;
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

    /**
     * The alignment cache key: SHA-256 over the setting, the detection count, every sampled cell's
     * centroid and every sampled cell's measurement values. The values matter: a slide
     * re-quantified in place keeps its centroids, and landmarks cached from its old values would
     * silently be reused.
     */
    public static String fingerprint(int cellsPerSlide, int detectionCount, List<PathObject> sampled) {
        MessageDigest digest = sha256();
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putInt(cellsPerSlide).putInt(detectionCount).putLong(sampled.size());
        digest.update(buf.array());
        centroids(digest, sampled);
        values(digest, sampled, i -> true);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * What a batch run's resume is keyed on for one slide: every detection's centroid, plus the
     * measurement values of the {@link MeasurementKeySample} cells (the first
     * {@value MeasurementKeySample#HEAD} and a fixed stride, at most
     * {@value MeasurementKeySample#MAX_CELLS}) — so a slide re-quantified with unchanged
     * centroids is gated again, at a bounded cost however large the slide.
     */
    public static String detectionFingerprint(List<PathObject> detections) {
        MessageDigest digest = sha256();
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(detections.size());
        digest.update(buf.array());
        centroids(digest, detections);
        int n = detections.size();
        values(digest, detections, i -> MeasurementKeySample.includes(i, n));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void centroids(MessageDigest digest, List<PathObject> cells) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        for (PathObject o : cells) {
            ROI roi = o.getROI();
            buf.clear();
            buf.putDouble(roi == null ? Double.NaN : roi.getCentroidX());
            buf.putDouble(roi == null ? Double.NaN : roi.getCentroidY());
            digest.update(buf.array());
        }
    }

    /**
     * The chosen cells' measurements: their names sorted once over the union, then per cell each
     * value's exact bits ({@code Double.doubleToLongBits}), a marker for a name the cell lacks.
     */
    private static void values(MessageDigest digest, List<PathObject> cells, IntPredicate chosen) {
        SortedSet<String> names = new TreeSet<>();
        for (int i = 0; i < cells.size(); i++) {
            if (chosen.test(i)) names.addAll(measurements(cells.get(i)).keySet());
        }
        for (String name : names) digest.update((name + "\n").getBytes(StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.allocate(9);
        for (int i = 0; i < cells.size(); i++) {
            if (!chosen.test(i)) continue;
            Map<String, Number> m = measurements(cells.get(i));
            for (String name : names) {
                Number v = m.get(name);
                buf.clear();
                buf.put((byte) (v == null ? 0 : 1));
                buf.putLong(v == null ? 0L : Double.doubleToLongBits(v.doubleValue()));
                digest.update(buf.array());
            }
        }
    }

    private static Map<String, Number> measurements(PathObject o) {
        Map<String, Number> m = o.getMeasurements();
        return m == null ? Map.of() : m;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }
}
