package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.cohort.EvidenceCrop;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.model.GateValues;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Evidence crops on the dedicated {@code flowpath-crops} executor — image reads must not queue
 * behind gating on {@code flowpath-background} (spec §6). The shown item first, then the next
 * {@link #PREFETCH}; an LRU of {@link #CACHE_SIZE} keyed by (item, applied values), so a moved
 * threshold re-renders.
 * <p>
 * A landing crop is handed on only when it is the one the <em>current</em> request asked for:
 * every {@link #show} (and {@link #cancel}) replaces the request, and the landing compares its
 * key with it. The stamp is the request's value rather than a counter because a prefetch
 * submitted under an earlier request must still land for a later one that asks for it. So a
 * crop for an item the user has left — or for the same item at numbers since moved — is cached
 * but never shown. The cache, the in-flight set and the request live on the FX thread.
 * Toolkit-free: tests drive both executors by hand.
 */
final class EvidenceCropCoordinator {

    static final int CACHE_SIZE = 64;
    static final int PREFETCH = 3;

    record CacheKey(ReviewItem.Key key, GateValues applied) {
        static CacheKey of(ReviewItem item) { return new CacheKey(item.key(), item.applied()); }
    }

    /** Called on the FX thread, so the job it returns captures what it needs from the session. */
    interface JobFactory {
        Callable<EvidenceCrop.Crop> jobFor(ReviewItem item);
    }

    private record Request(CacheKey key, Consumer<EvidenceCrop.Crop> onReady) {}

    private final Executor crops;
    private final Executor fxThread;
    private final JobFactory jobs;
    private final Map<CacheKey, EvidenceCrop.Crop> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, EvidenceCrop.Crop> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final Set<CacheKey> inFlight = new HashSet<>();
    private Request current;

    EvidenceCropCoordinator(Executor crops, Executor fxThread, JobFactory jobs) {
        this.crops = Objects.requireNonNull(crops);
        this.fxThread = Objects.requireNonNull(fxThread);
        this.jobs = Objects.requireNonNull(jobs);
    }

    int cachedCount() { return cache.size(); }

    /**
     * Show {@code item}'s crop through {@code onReady} — at once when cached, else when it lands —
     * and prefetch the first {@link #PREFETCH} of {@code following}.
     */
    void show(ReviewItem item, List<ReviewItem> following, Consumer<EvidenceCrop.Crop> onReady) {
        CacheKey key = CacheKey.of(item);
        current = new Request(key, Objects.requireNonNull(onReady));
        EvidenceCrop.Crop hit = cache.get(key);
        if (hit != null) onReady.accept(hit);
        else submit(item);
        for (int i = 0; i < Math.min(PREFETCH, following.size()); i++) {
            if (!cache.containsKey(CacheKey.of(following.get(i)))) submit(following.get(i));
        }
    }

    /** Nothing is shown any more: a crop still in flight is cached when it lands, never handed on. */
    void cancel() {
        current = null;
    }

    private void submit(ReviewItem item) {
        CacheKey key = CacheKey.of(item);
        if (!inFlight.add(key)) return;
        Callable<EvidenceCrop.Crop> job;
        try {
            job = jobs.jobFor(item);
        } catch (Exception | Error ex) {
            EvidenceCrop.Crop failed = EvidenceCrop.Crop.failed(EvidenceCrop.messageOf(ex));
            job = () -> failed;
        }
        Callable<EvidenceCrop.Crop> work = job;
        crops.execute(() -> {
            EvidenceCrop.Crop crop;
            try {
                crop = work.call();
                if (crop == null) crop = EvidenceCrop.Crop.failed("No crop was rendered");
            } catch (Exception | Error ex) {
                // Error too: a huge region read is where an OutOfMemoryError is plausible; the
                // item shows the text and stays answerable.
                crop = EvidenceCrop.Crop.failed(EvidenceCrop.messageOf(ex));
            }
            EvidenceCrop.Crop done = crop;
            fxThread.execute(() -> land(key, done));
        });
    }

    private void land(CacheKey key, EvidenceCrop.Crop crop) {
        inFlight.remove(key);
        cache.put(key, crop);
        if (current != null && current.key().equals(key)) current.onReady().accept(crop);
    }
}
