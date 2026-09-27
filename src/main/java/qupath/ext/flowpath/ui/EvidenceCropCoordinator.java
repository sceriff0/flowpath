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
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Evidence crops on the dedicated {@code flowpath-crops} executor — image reads must not queue
 * behind gating on {@code flowpath-background} (spec §6). The shown item first, then the next
 * {@link #PREFETCH}; an LRU of {@link #CACHE_SIZE} keyed by (item, applied values, alignment
 * model), so a moved threshold or a rescore re-renders. Only a crop that rendered is cached: a
 * failure is shown, and the next request for the item tries again.
 * <p>
 * A landing crop is handed on only when it is the one the <em>current</em> request asked for:
 * every {@link #show} (and {@link #cancel}) replaces the request, and the landing compares its
 * key with it. The stamp is the request's value rather than a counter because a prefetch
 * submitted under an earlier request must still land for a later one that asks for it. So a
 * crop for an item the user has left — or for the same item at numbers since moved — is never
 * shown. A queued job whose key has left the window (the shown item and its prefetch) by the
 * time it starts is skipped, so fast stepping does not queue a read per item stepped past.
 * <p>
 * The cache, the in-flight set and the request live on the FX thread; the window is an immutable
 * map published through a volatile field, the one thing the crops thread reads. Toolkit-free:
 * tests drive both executors by hand.
 */
final class EvidenceCropCoordinator {

    static final int CACHE_SIZE = 64;
    static final int PREFETCH = 3;

    /** {@code basis} is the alignment model the crop was drawn against, compared by identity. */
    record CacheKey(ReviewItem.Key key, GateValues applied, Object basis) {
        static CacheKey of(ReviewItem item, Object basis) { return new CacheKey(item.key(), item.applied(), basis); }
    }

    /** Called on the FX thread, so the job it returns captures what it needs from the session. */
    interface JobFactory {
        Callable<EvidenceCrop.Crop> jobFor(ReviewItem item);
    }

    private record Request(CacheKey key, Consumer<EvidenceCrop.Crop> onReady) {}

    private final Executor crops;
    private final Executor fxThread;
    private final Supplier<?> basis;
    private final JobFactory jobs;
    private final Map<CacheKey, EvidenceCrop.Crop> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, EvidenceCrop.Crop> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final Set<CacheKey> inFlight = new HashSet<>();
    private Request current;
    /** The shown item and its prefetch, by key: what a job about to start is still wanted for. */
    private volatile Map<CacheKey, ReviewItem> window = Map.of();

    /**
     * @param basis the alignment model the next job will capture; read on the FX thread when a
     *              key is made, so a key names the model its crop was drawn against
     */
    EvidenceCropCoordinator(Executor crops, Executor fxThread, Supplier<?> basis, JobFactory jobs) {
        this.crops = Objects.requireNonNull(crops);
        this.fxThread = Objects.requireNonNull(fxThread);
        this.basis = Objects.requireNonNull(basis);
        this.jobs = Objects.requireNonNull(jobs);
    }

    int cachedCount() { return cache.size(); }

    /** The key {@code item}'s crop is cached under now. */
    CacheKey keyOf(ReviewItem item) {
        return CacheKey.of(item, basis.get());
    }

    /**
     * Show {@code item}'s crop through {@code onReady} — at once when cached, else when it lands —
     * and prefetch the first {@link #PREFETCH} of {@code following}.
     */
    void show(ReviewItem item, List<ReviewItem> following, Consumer<EvidenceCrop.Crop> onReady) {
        CacheKey key = keyOf(item);
        current = new Request(key, Objects.requireNonNull(onReady));
        Map<CacheKey, ReviewItem> next = new LinkedHashMap<>();
        next.put(key, item);
        List<ReviewItem> ahead = following.subList(0, Math.min(PREFETCH, following.size()));
        for (ReviewItem f : ahead) next.putIfAbsent(keyOf(f), f);
        window = Map.copyOf(next);

        EvidenceCrop.Crop hit = cache.get(key);
        if (hit != null) onReady.accept(hit);
        else submit(item, key);
        for (ReviewItem f : ahead) {
            CacheKey k = keyOf(f);
            if (!cache.containsKey(k)) submit(f, k);
        }
    }

    /** Nothing is shown any more: a crop still in flight is cached when it lands, never handed on. */
    void cancel() {
        current = null;
        window = Map.of();
    }

    private void submit(ReviewItem item, CacheKey key) {
        if (!inFlight.add(key)) return;
        Callable<EvidenceCrop.Crop> job;
        try {
            job = jobs.jobFor(item);
        } catch (Exception | Error ex) {
            EvidenceCrop.Crop failed = EvidenceCrop.Crop.failed(EvidenceCrop.messageOf(ex));
            job = () -> failed;
        }
        Callable<EvidenceCrop.Crop> work = job;
        try {
            crops.execute(() -> run(key, work));
        } catch (RejectedExecutionException ex) {
            // The executor is shut down (the pane is going away): nothing will land for this key.
            inFlight.remove(key);
        }
    }

    /** On the crops thread. */
    private void run(CacheKey key, Callable<EvidenceCrop.Crop> work) {
        if (!window.containsKey(key)) {
            fxThread.execute(() -> skipped(key));
            return;
        }
        EvidenceCrop.Crop crop;
        try {
            crop = work.call();
            if (crop == null) crop = EvidenceCrop.Crop.failed("No crop was rendered");
        } catch (Exception | Error ex) {
            // Error too: a huge region read is where an OutOfMemoryError is plausible; the item
            // shows the text and stays answerable.
            crop = EvidenceCrop.Crop.failed(EvidenceCrop.messageOf(ex));
        }
        EvidenceCrop.Crop done = crop;
        fxThread.execute(() -> land(key, done));
    }

    /**
     * A job was skipped as unwanted; if its key was asked for again meanwhile — while the key was
     * still in flight, so no new job was queued — submit it now, or it would never land.
     */
    private void skipped(CacheKey key) {
        inFlight.remove(key);
        ReviewItem again = window.get(key);
        if (again != null && !cache.containsKey(key)) submit(again, key);
    }

    private void land(CacheKey key, EvidenceCrop.Crop crop) {
        inFlight.remove(key);
        if (crop.ok()) cache.put(key, crop);
        if (current != null && current.key().equals(key)) current.onReady().accept(crop);
    }
}
