package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.model.CellIndex;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reads an image's detections into the {@link GatingSession} <em>off the FX thread</em>, and
 * reads them again whenever the detection set changes while FlowPath is open.
 * <p>
 * <b>Why.</b> {@code FlowPathPane} used to call {@link DetectionIngest#read} and compute the
 * marker statistics on the FX thread, which froze QuPath for seconds on a million-cell slide;
 * and its hierarchy listener only ever recomputed the annotation mask, so cells added or
 * deleted in QuPath afterwards were never read — new cells were missing from every count and
 * from the CSV, and deleted ones were still being gated.
 * <p>
 * <b>The stale-result guard.</b> Every piece of background work is stamped with a generation
 * taken on the FX thread when it is submitted, and its result is applied — on the FX thread,
 * through {@link GatingSession#resync(GatingSession.Derived, Supplier)} — only if no newer work
 * was submitted since. A slow read of image A can therefore never land over image B. The job
 * also checks the generation before each expensive step and returns early once superseded, so
 * switching A→B→C does not run A's and B's reads in full on the single background thread
 * before C's. The generation is the one piece of state the background side reads; everything
 * else it needs is captured into the job first (the detection list, a copy of the quality
 * filter, the annotations, the index to compare against).
 * <p>
 * <b>Nothing derives on the FX thread at the landing either.</b> A read that lands against a
 * session whose filters moved on while it ran is <em>not</em> handed to {@code resync}'s
 * synchronous fallback; it is derived again in the background. See {@link #adoptOrRederive}.
 * <p>
 * <b>Only an image switch supersedes work in flight.</b> A hierarchy change that settles while
 * a read or check is still running does not replace it — that turned an annotation edit during
 * a first load into a second full read, and events arriving more often than one read takes
 * into a read that never landed. The change is remembered instead, and checked once the
 * running job lands, against the cells it produced.
 * <p>
 * <b>The refresh.</b> A hierarchy event that is not FlowPath's own classification write
 * ({@code firingOwnEvent}) and not mid-edit arms one {@value #REFRESH_DEBOUNCE_MS} ms timer;
 * each further event re-arms it, so a burst is one refresh. When it fires, the current
 * detections are captured and, in the background, compared with the cells the index holds.
 * A different set is read again, keeping the gate tree; the same set with the annotation
 * filter on only recomputes the masks and statistics (the annotation may be what changed);
 * otherwise nothing happens. A measurement or ROI change on a detection forces a read even
 * when the set is unchanged, because the index holds copies of the values.
 * <p>
 * Toolkit-free: the executors and the timer are injected, so every ordering above is a test
 * the suite drives by hand. Every method except the listener must be called on the FX thread.
 * <p>
 * Stays in {@code ui}, alongside {@link GatingSession} and {@code CsvExportCoordinator} for
 * the same reason — see {@link GatingSession}'s javadoc for why moving to a {@code
 * ui.session} package would only widen this class's deliberately package-private surface,
 * not buy back anything the {@code session} subpackages exist to provide.
 */
final class IngestCoordinator {

    /** Quiet period after the last hierarchy change before the detections are compared. */
    static final long REFRESH_DEBOUNCE_MS = 500;

    /** A one-shot timer. Returns an action that cancels the task if it has not run. */
    @FunctionalInterface
    interface Scheduler {
        Runnable schedule(Runnable task, long delayMs);
    }

    /** Why the session was left without cells. */
    enum Cleared {
        /** No image is open. */
        NO_IMAGE,
        /** The image holds no detections. */
        NO_DETECTIONS,
        /** A new image is being read; the previous image's cells are dropped meanwhile. */
        LOADING,
        /**
         * The very first read of an image's detections failed. Unlike {@link #LOADING}, which
         * keeps the previous image's gate/channel combos on screen (disabled) while a read is
         * in flight, there is no read left in flight to land and re-enable them against: the
         * editor is dropped in full, exactly as for {@link #NO_DETECTIONS}, or it would be left
         * showing a gate and channels for cells that do not exist once the busy state clears.
         */
        FAILED
    }

    /** What the background is doing, for the status bar and the controls that need cells. */
    enum Busy {
        IDLE,
        /** Reading an image the session has no cells for yet: controls that need them wait. */
        LOADING,
        /** Checking a changed hierarchy against cells the session still holds. */
        REFRESHING
    }

    /** Turns detections into an index: {@link DetectionIngest#read}, injectable for tests. */
    @FunctionalInterface
    interface Reader {
        IngestResult read(Collection<PathObject> detections, ImageData<?> imageData);
    }

    /** The pane. Every call arrives on the FX thread. */
    interface Host {
        /** The annotations the ROI filter should use on {@code imageData}. */
        List<PathObject> annotations(ImageData<?> imageData);

        /** The session's index was just set to {@code null}; a resync follows. */
        void cleared(Cleared why);

        /** A read landed and its index was adopted; the resync follows. */
        void ingested(ImageData<?> imageData, IngestResult result);

        /**
         * A resync finished.
         *
         * @param newIndex whether the cells changed (an image read or a clear) rather than only
         *                 the masks and statistics — the editor needs rebuilding only for this
         */
        void resynced(Optional<GatingSession.MigrationNotice> notice, boolean newIndex);

        void busyChanged(Busy state);

        /** A background read failed; the session keeps what it had. */
        void failed(Throwable error);
    }

    /** How one hierarchy event bears on the cells. Package-private for its test. */
    enum Change {
        /** Cannot affect the cells or the annotation mask. */
        NONE,
        /** May have changed the detection set or an annotation: compare after the quiet period. */
        CHECK,
        /** A detection's values changed: read again even if the set is the same. */
        READ
    }

    private final GatingSession session;
    private final Executor background;
    private final Scheduler scheduler;
    private final Executor fxThread;
    private final BooleanSupplier firingOwnEvent;
    private final Host host;
    private final Reader reader;
    private final PathObjectHierarchyListener listener = this::onHierarchyChanged;

    /** Bumped on the FX thread; read by a running job to stop once superseded. */
    private final AtomicLong generation = new AtomicLong();

    // ---- FX-thread state ---------------------------------------------------------------
    private ImageData<?> image;
    /** A change settled while a job was running: check again once it lands. */
    private boolean recheckAfterLanding;
    private Busy busy = Busy.IDLE;
    private boolean closed;
    private Runnable cancelRefresh;
    private long refreshToken;
    /** Detection value changes seen, and how many of them the last applied read covers. */
    private long readsRequested;
    private long readsApplied;

    IngestCoordinator(GatingSession session, Executor background, Scheduler scheduler, Executor fxThread,
                      BooleanSupplier firingOwnEvent, Host host) {
        this(session, background, scheduler, fxThread, firingOwnEvent, host, DetectionIngest::read);
    }

    IngestCoordinator(GatingSession session, Executor background, Scheduler scheduler, Executor fxThread,
                      BooleanSupplier firingOwnEvent, Host host, Reader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.session = Objects.requireNonNull(session, "session");
        this.background = Objects.requireNonNull(background, "background");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.fxThread = Objects.requireNonNull(fxThread, "fxThread");
        this.firingOwnEvent = Objects.requireNonNull(firingOwnEvent, "firingOwnEvent");
        this.host = Objects.requireNonNull(host, "host");
    }

    // ---- entry points ------------------------------------------------------------------

    /**
     * The open image is now {@code imageData} (or none). Anything in flight for the previous
     * one is superseded. A different image's cells are dropped at once, so a gate edit made
     * while the new image is read cannot classify the old image's detections; re-opening the
     * same image keeps its cells until the read lands.
     */
    void open(ImageData<?> imageData) {
        if (closed) return;
        boolean sameImage = imageData != null && imageData == image;
        detach();
        supersede();
        readsApplied = readsRequested;
        image = imageData;

        if (imageData == null) {
            clear(Cleared.NO_IMAGE);
            setBusy(Busy.IDLE);
            return;
        }
        imageData.getHierarchy().addListener(listener);

        List<PathObject> detections = detectionsOf(imageData);
        if (detections.isEmpty()) {
            clear(Cleared.NO_DETECTIONS);
            setBusy(Busy.IDLE);
            return;
        }
        if (!sameImage) clear(Cleared.LOADING);
        submit(imageData, detections, session.index(), true);
    }

    /** Stop for good: detach from the hierarchy and drop anything in flight. */
    void close() {
        closed = true;
        detach();
        supersede();
        image = null;
    }

    Busy busy() {
        return busy;
    }

    // ---- the hierarchy listener --------------------------------------------------------

    /**
     * Called on whichever thread changed the hierarchy. Reads nothing of this object's state
     * but the injected own-event flag, which must be read here: it is only set for the
     * duration of FlowPath's own synchronous event.
     */
    private void onHierarchyChanged(PathObjectHierarchyEvent event) {
        if (firingOwnEvent.getAsBoolean() || event.isChanging()) return;
        Change change = classify(event);
        if (change == Change.NONE) return;
        // Pure facts about the event itself, safe to read off the FX thread like classify()
        // and touchesDetection() already are -- unlike the ROI-filter check in noteChange,
        // which reads FlowPath's own mutable session state and so must stay on the FX thread.
        boolean namedObjectsExcludeDetections = namedObjectsExcludeDetections(event);
        PathObjectHierarchy hierarchy = event.getHierarchy();
        fxThread.execute(() -> noteChange(hierarchy, change, namedObjectsExcludeDetections));
    }

    /** How {@code event} bears on the cells; see {@link Change}. */
    static Change classify(PathObjectHierarchyEvent event) {
        return switch (event.getEventType()) {
            // FlowPath writes classifications itself, and never reads them back.
            case CHANGE_CLASSIFICATION -> Change.NONE;
            case CHANGE_MEASUREMENTS, CHANGE_OTHER -> touchesDetection(event) ? Change.READ : Change.CHECK;
            case ADDED, REMOVED, OTHER_STRUCTURE_CHANGE -> Change.CHECK;
        };
    }

    private static boolean touchesDetection(PathObjectHierarchyEvent event) {
        for (PathObject o : event.getChangedObjects()) {
            if (o != null && o.isDetection()) return true;
        }
        return false;
    }

    /**
     * Whether {@code event} names at least one changed object and none of them is a detection
     * -- an annotation-only edit, as far as the event can say. {@code false} both when a named
     * object is a detection and when the event names none at all (a structure change reported
     * without an object list): either way {@link #noteChange} cannot rule out that cells were
     * touched, so it must run the full check.
     */
    private static boolean namedObjectsExcludeDetections(PathObjectHierarchyEvent event) {
        Collection<PathObject> changed = event.getChangedObjects();
        if (changed.isEmpty()) return false;
        for (PathObject o : changed) {
            if (o != null && o.isDetection()) return false;
        }
        return true;
    }

    /**
     * @param annotationOnly {@link #namedObjectsExcludeDetections}, computed on the thread that
     *                       raised the event; only meaningful when {@code change == CHECK}
     */
    private void noteChange(PathObjectHierarchy hierarchy, Change change, boolean annotationOnly) {
        // An event queued by the previous image's hierarchy just before a switch.
        if (closed || image == null || image.getHierarchy() != hierarchy) return;
        // An annotation-only edit cannot change the detection set, and with the ROI filter off
        // there is no annotation mask for it to change either -- nothing downstream of a
        // refresh (the detection-list snapshot, the busy-state flip the status bar shows) has
        // anything to do. Every other CHECK still gets the full debounce-and-compare, including
        // a structure event that names no objects at all: it might be a detection add/remove
        // the platform simply did not list.
        if (change == Change.CHECK && annotationOnly && !session.tree().isRoiFilterEnabled()) return;
        if (change == Change.READ) readsRequested++;
        cancelPendingRefresh();
        long token = ++refreshToken;
        cancelRefresh = scheduler.schedule(() -> fxThread.execute(() -> {
            if (closed || token != refreshToken) return;
            cancelRefresh = null;
            refresh();
        }), REFRESH_DEBOUNCE_MS);
    }

    /** The quiet period ended: compare the detections now in the hierarchy with the index. */
    private void refresh() {
        if (image == null) return;
        if (busy != Busy.IDLE) {
            // A job is running. Superseding it would throw its work away and start over; let it
            // land, then compare against what it produced.
            recheckAfterLanding = true;
            return;
        }
        List<PathObject> detections = detectionsOf(image);
        CellIndex baseline = session.index();
        if (detections.isEmpty()) {
            // Nothing held and nothing being read: an annotation edit on an empty image.
            if (baseline == null && busy == Busy.IDLE) return;
            supersede();
            clear(Cleared.NO_DETECTIONS);
            setBusy(Busy.IDLE);
            return;
        }
        submit(image, detections, baseline, readsRequested > readsApplied);
    }

    // ---- background work ---------------------------------------------------------------

    /** What a job found. */
    private sealed interface Outcome {}
    private record Read(IngestResult result, GatingSession.Derived derived) implements Outcome {}
    /**
     * Masks and statistics for cells the session already holds. {@code newIndex} is what
     * {@link Host#resynced} is told, and is {@code true} only for a derivation
     * {@link #rederive} queued <em>after</em> a read had already installed new cells — see
     * {@link #adoptOrRederive}.
     */
    private record Rederived(GatingSession.Derived derived, boolean newIndex) implements Outcome {}
    private record Unchanged() implements Outcome {}
    private record Failed(Throwable error) implements Outcome {}

    /**
     * Submit one job against {@code baseline} (the index the session holds, or {@code null}).
     * Everything the job reads is captured here, on the FX thread.
     */
    private void submit(ImageData<?> imageData, List<PathObject> detections, CellIndex baseline, boolean forceRead) {
        supersede();
        long stamp = generation.get();
        long readsCovered = readsRequested;
        boolean read = forceRead || baseline == null;
        GatingSession.DerivationInputs inputs =
                session.derivationInputs(baseline, () -> host.annotations(imageData));

        background.execute(() -> {
            // Superseded jobs stop before each expensive step and post nothing: whatever
            // superseded them owns the session and the busy state now.
            if (superseded(stamp)) return;
            Outcome outcome;
            try {
                if (read || !sameCells(baseline, detections)) {
                    if (superseded(stamp)) return;
                    IngestResult result = reader.read(detections, imageData);
                    if (superseded(stamp)) return;
                    outcome = new Read(result, GatingSession.derive(inputs.withIndex(result.index())));
                } else if (inputs.roiFilterEnabled()) {
                    if (superseded(stamp)) return;
                    outcome = new Rederived(GatingSession.derive(inputs), false);
                } else {
                    outcome = new Unchanged();
                }
            } catch (RuntimeException | Error ex) {
                outcome = new Failed(ex);
            }
            Outcome landed = outcome;
            fxThread.execute(() -> land(stamp, imageData, baseline, readsCovered, landed));
        });
        setBusy(baseline == null ? Busy.LOADING : Busy.REFRESHING);
    }

    private void land(long stamp, ImageData<?> imageData, CellIndex baseline, long readsCovered, Outcome outcome) {
        if (closed || superseded(stamp)) return;     // newer work owns the session
        setBusy(Busy.IDLE);
        // Taken and cleared before the switch, because the re-derivation arm below supersedes
        // (which clears the flag) and then leaves a job in flight: the owed recheck must
        // survive that and be re-armed against the new job by the refresh() at the end.
        boolean recheck = recheckAfterLanding;
        recheckAfterLanding = false;
        switch (outcome) {
            case Read r -> {
                if (baseline == null) {
                    session.adoptIndex(r.result().index());
                } else {
                    session.rereadIndex(r.result().index());
                }
                readsApplied = readsCovered;
                host.ingested(imageData, r.result());
                adoptOrRederive(imageData, r.derived(), true);
            }
            case Rederived d -> adoptOrRederive(imageData, d.derived(), d.newIndex());
            case Unchanged u -> { }
            case Failed f -> {
                // A first-load failure (baseline == null) leaves no cells to come and nothing
                // in flight to land later: clear the editor now, or it would sit re-enabled
                // over the previous image's gate and channels once the busy state clears. A
                // failed REFRESH (baseline != null) keeps what the session already has, per
                // Host#failed's own contract.
                if (baseline == null) clear(Cleared.FAILED);
                host.failed(f.error());
            }
        }
        if (recheck) refresh();
    }

    /**
     * Adopt {@code derived} if it still describes the session; otherwise derive again in the
     * background rather than hand it to {@link GatingSession#resync(GatingSession.Derived,
     * Supplier)} anyway.
     * <p>
     * <b>Why this escape hatch exists.</b> {@code resync}'s two-argument form falls back to a
     * <em>synchronous</em> derive when the derivation handed to it has gone stale, and that
     * derive is {@code MarkerStats.compute} — a sort of every marker column over every cell,
     * seconds on a large slide, on the FX thread. Nothing disables the quality-filter panel or
     * the annotation-filter checkbox while an ingest is {@link Busy#REFRESHING}, so the stale
     * case is ordinary: edit a detection in QuPath, nudge a quality slider or toggle the ROI
     * filter while the re-ingest runs, and the read lands against a session whose filters have
     * moved on. That is precisely the freeze this coordinator exists to remove, reappearing at
     * the landing. {@link DerivationCoordinator#land} takes the same escape hatch for the same
     * reason; between them, {@code resync}'s synchronous fallback stays the safety net it is
     * documented to be.
     *
     * @param newIndex what {@link Host#resynced} is told once the adoption finally happens —
     *                 carried across the re-derivation, because the cells really did change and
     *                 the editor still needs rebuilding whenever the derivation lands
     */
    private void adoptOrRederive(ImageData<?> imageData, GatingSession.Derived derived, boolean newIndex) {
        if (session.stillDescribes(derived)) {
            host.resynced(session.resync(derived, () -> host.annotations(imageData)), newIndex);
        } else {
            rederive(imageData, newIndex);
        }
    }

    /**
     * Derive the masks and statistics for the cells the session now holds, in the background.
     * Reads nothing on the FX thread but the inputs, exactly as {@link #submit} does; the
     * result lands through {@link #land} and is re-checked there in turn, so a filter changed
     * again while <em>this</em> runs simply queues another one rather than falling through to a
     * synchronous derive.
     * <p>
     * <b>A bare generation bump, never {@link #supersede()}.</b> This runs inside {@link #land},
     * which has already passed its own generation check, so there is nothing in flight left to
     * supersede — and {@code supersede()} would also run {@link #cancelPendingRefresh()},
     * silently disarming the debounce timer a detection edit had set while the read was
     * running. Nothing here re-reads detections and nothing re-arms that timer, so the edit
     * would never be read at all: the index would sit stale, with no log line and no UI cue,
     * until some unrelated hierarchy event happened along — exactly the failure class this
     * coordinator exists to prevent. (The other two things {@code supersede()} does are already
     * handled: {@code land} takes and clears {@code recheckAfterLanding} before the switch and
     * re-arms it afterwards.) The bump itself stays, so one stamp still identifies one job.
     */
    private void rederive(ImageData<?> imageData, boolean newIndex) {
        long stamp = generation.incrementAndGet();
        CellIndex current = session.index();
        long readsCovered = readsApplied;
        GatingSession.DerivationInputs inputs =
                session.derivationInputs(current, () -> host.annotations(imageData));
        GatingSession.ReusableStats reusable = session.reusableStats();

        background.execute(() -> {
            if (superseded(stamp)) return;
            Outcome outcome;
            try {
                outcome = new Rederived(GatingSession.derive(inputs, reusable), newIndex);
            } catch (RuntimeException | Error ex) {
                outcome = new Failed(ex);
            }
            Outcome landed = outcome;
            fxThread.execute(() -> land(stamp, imageData, current, readsCovered, landed));
        });
        setBusy(Busy.REFRESHING);
    }

    private boolean superseded(long stamp) {
        return generation.get() != stamp;
    }

    /**
     * Whether {@code detections} are exactly the cells {@code index} holds. Order is tried
     * first, since an untouched hierarchy lists its detections the same way twice; a
     * reordering (an annotation re-parenting cells) falls back to set identity.
     */
    static boolean sameCells(CellIndex index, List<PathObject> detections) {
        PathObject[] held = index.getObjects();
        if (held.length != detections.size()) return false;
        boolean inOrder = true;
        for (int i = 0; i < held.length && inOrder; i++) {
            inOrder = held[i] == detections.get(i);
        }
        if (inOrder) return true;
        Set<PathObject> heldSet = Collections.newSetFromMap(new IdentityHashMap<>(held.length * 2));
        Collections.addAll(heldSet, held);
        for (PathObject o : detections) {
            if (!heldSet.contains(o)) return false;
        }
        return true;
    }

    // ---- helpers -----------------------------------------------------------------------

    private void clear(Cleared why) {
        session.adoptIndex(null);
        host.cleared(why);
        host.resynced(session.resync(List::of), true);
    }

    /** Drop anything in flight or armed: its result, if it comes, is no longer wanted. */
    private void supersede() {
        generation.incrementAndGet();
        recheckAfterLanding = false;
        cancelPendingRefresh();
    }

    private void cancelPendingRefresh() {
        refreshToken++;
        if (cancelRefresh != null) {
            cancelRefresh.run();
            cancelRefresh = null;
        }
    }

    private void setBusy(Busy state) {
        if (busy == state) return;
        busy = state;
        host.busyChanged(state);
    }

    private void detach() {
        if (image != null) image.getHierarchy().removeListener(listener);
    }

    private static List<PathObject> detectionsOf(ImageData<?> imageData) {
        return new ArrayList<>(imageData.getHierarchy().getDetectionObjects());
    }
}
