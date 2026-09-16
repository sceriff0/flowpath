package qupath.ext.flowpath.model;

import qupath.lib.objects.PathObject;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.RandomAccess;
import java.util.Set;

/**
 * <b>The</b> measurement-key sample: which detections' key sets FlowPath reads to learn
 * what an export carries, and the union of those keys.
 * <p>
 * Every question of the form "does this export have a column for X?" — the marker panel,
 * the compartment capability, the one concrete key each marker and morphology field
 * resolves to, the centroid pair — is answered from this sample, never from a per-cell
 * scan. It used to be taken twice, by {@code CellIndex.sampleMeasurementKeys} and
 * {@code DetectionIngest.sampleMeasurementKeys} (and a third time by
 * {@code CompartmentCapability.scan}), three loops kept at one depth by a shared constant
 * and a comment. Now there is one.
 *
 * <h2>Shape of the sample</h2>
 * The first {@link #HEAD} detections in iteration order, then detections at a fixed
 * stride across the remainder, up to {@link #MAX_CELLS} in total. The stride is
 * {@code ceil((n - HEAD) / (MAX_CELLS - HEAD))}, so a collection of at most
 * {@code MAX_CELLS} is sampled in full, and a larger one is sampled evenly to its end.
 * <p>
 * The head alone was not enough: a key present only on later cells — a merged export, or
 * a first field of view quantified with a different panel — was never offered at all.
 *
 * <h2>Determinism</h2>
 * Positions depend only on {@code n}, and keys are collected in first-seen order over
 * ascending positions, so the same collection in the same order always yields the same
 * set in the same order. That order is load-bearing: {@code CellIndex}'s fuzzy key
 * resolvers return the <em>first</em> matching key.
 *
 * <h2>Cost</h2>
 * At most {@code MAX_CELLS} measurement maps are read, however large the slide. Random
 * access collections and arrays are indexed directly; any other collection is iterated
 * once without reading the skipped detections' measurements.
 */
public final class MeasurementKeySample {

    /** Detections sampled in full from the start of the collection. */
    public static final int HEAD = 100;

    /** The ceiling on detections sampled, head included. */
    public static final int MAX_CELLS = 1000;

    private MeasurementKeySample() {}

    /** The spacing between sampled detections after the head; 1 when everything is sampled. */
    static int stride(int n) {
        int remainder = n - HEAD;
        if (remainder <= 0) return 1;
        int slots = MAX_CELLS - HEAD;
        return (remainder + slots - 1) / slots;
    }

    /** True when detection {@code i} of a collection of {@code n} is in the sample. */
    public static boolean includes(int i, int n) {
        if (i < 0 || i >= n) return false;
        return i < HEAD || (i - HEAD) % stride(n) == 0;
    }

    /** How many detections a collection of {@code n} contributes to the sample. */
    public static int size(int n) {
        if (n <= HEAD) return Math.max(n, 0);
        int stride = stride(n);
        return HEAD + (n - HEAD + stride - 1) / stride;
    }

    /** The sampled keys of a positional array, in first-seen order. */
    public static Set<String> keys(PathObject[] objects) {
        Set<String> keys = new LinkedHashSet<>();
        int n = objects.length;
        int stride = stride(n);
        for (int i = 0; i < n; i = next(i, stride)) {
            keys.addAll(CellIndex.getMeasurements(objects[i]).keySet());
        }
        return keys;
    }

    /** The sampled keys of a collection, in first-seen order over its iteration order. */
    public static Set<String> keys(Collection<PathObject> detections) {
        Set<String> keys = new LinkedHashSet<>();
        int n = detections.size();
        int stride = stride(n);
        if (detections instanceof List<PathObject> list && detections instanceof RandomAccess) {
            for (int i = 0; i < n; i = next(i, stride)) {
                keys.addAll(CellIndex.getMeasurements(list.get(i)).keySet());
            }
            return keys;
        }
        int i = 0;
        int wanted = 0;
        for (PathObject obj : detections) {
            if (i == wanted) {
                keys.addAll(CellIndex.getMeasurements(obj).keySet());
                wanted = next(i, stride);
            }
            i++;
        }
        return keys;
    }

    private static int next(int i, int stride) {
        return i < HEAD ? i + 1 : i + stride;
    }
}
