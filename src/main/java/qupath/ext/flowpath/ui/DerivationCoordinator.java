package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.model.CellIndex;
import qupath.lib.objects.PathObject;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the heavy half of a {@link GatingSession#resync} <em>off the FX thread</em> for the
 * three paths that still change a mask: the annotation-filter toggle, an undo or redo whose
 * target carries a different quality filter or ROI flag, and loading a tree whose filters
 * differ from the open one's.
 * <p>
 * <b>Why.</b> A changed mask means {@code MarkerStats.compute} sorts every marker column over
 * every cell. On a million-cell slide with thirty-odd markers that is seconds, and it used to
 * run on the FX thread — one Ctrl+Z or one click on "Filter by annotations" froze QuPath.
 * {@link IngestCoordinator} had already moved an image read off the FX thread; this is the same
 * shape for the edits that follow it, and the two share one {@code flowpath-background} thread,
 * so a derivation and a re-ingest queue behind each other instead of racing for the session.
 * <p>
 * <b>The tree changes first, on the FX thread.</b> {@code session.undo()}, {@code redo()},
 * {@code replaceTree()} and {@code setRoiFilterEnabled()} are unchanged and still run
 * synchronously before {@link #request()} is called: the undo stack, its 500ms coalescing
 * window and the baseline the next edit records are exactly what they were. Only the derived
 * state — region mask, quality mask, statistics — and the render that shows it are deferred.
 * <p>
 * <b>Undo is superseded, never queued or disabled.</b> A second request while one is in flight
 * takes its own step at once and supersedes the derivation in flight; the older one lands
 * nowhere. Queueing would render an intermediate tree the user has already left, and disabling
 * undo during a derivation would silently drop steps from a Ctrl+Z burst — both change what
 * undo does, which this cannot. What a burst costs instead is one derivation per step on the
 * background thread, of which only the last is adopted.
 * <p>
 * <b>The stale-result guard</b> is {@link IngestCoordinator}'s: a generation stamped on the FX
 * thread when the work is submitted, checked before the expensive step and again before the
 * result is applied. It is the one piece of state the background side reads. On landing, the
 * session is asked whether the derivation {@linkplain GatingSession#stillDescribes still
 * describes} it — an image read may have landed in between — and if it does not, the work is
 * requested again here rather than left to {@code resync}'s synchronous fallback, which would
 * put the sort back on the FX thread.
 * <p>
 * Toolkit-free: both executors and the derivation itself are injected, so every ordering above
 * is a test the suite drives by hand. Every method must be called on the FX thread.
 */
final class DerivationCoordinator {

    /** The pane. Every call arrives on the FX thread. */
    interface Host {
        /** The annotations the ROI filter should use on the open image. */
        List<PathObject> annotations();

        /** A resync finished; the cells are unchanged, so only the derived state is new. */
        void resynced(Optional<GatingSession.MigrationNotice> notice);

        /**
         * Whether a derivation is running, for the spinner and anything disabled meanwhile.
         * Reported on every change, like {@link IngestCoordinator.Host#busyChanged}.
         */
        void busyChanged(boolean deriving);

        /** A derivation failed; the session keeps what it had. */
        void failed(Throwable error);
    }

    /** The heavy half: {@link GatingSession#derive}, injectable for tests. */
    @FunctionalInterface
    interface Deriver {
        GatingSession.Derived derive(GatingSession.DerivationInputs inputs,
                                     GatingSession.ReusableStats reusable);
    }

    /** What a derivation produced. */
    private sealed interface Outcome {}
    private record Computed(GatingSession.Derived derived) implements Outcome {}
    private record Failed(Throwable error) implements Outcome {}

    private final GatingSession session;
    private final Executor background;
    private final Executor fxThread;
    private final Host host;
    private final Deriver deriver;

    /** Bumped on the FX thread; read by a running derivation to stop once superseded. */
    private final AtomicLong generation = new AtomicLong();

    private boolean deriving;
    private boolean closed;

    DerivationCoordinator(GatingSession session, Executor background, Executor fxThread, Host host) {
        this(session, background, fxThread, host, GatingSession::derive);
    }

    DerivationCoordinator(GatingSession session, Executor background, Executor fxThread, Host host,
                          Deriver deriver) {
        this.session = Objects.requireNonNull(session, "session");
        this.background = Objects.requireNonNull(background, "background");
        this.fxThread = Objects.requireNonNull(fxThread, "fxThread");
        this.host = Objects.requireNonNull(host, "host");
        this.deriver = Objects.requireNonNull(deriver, "deriver");
    }

    /** Whether a derivation is in flight. */
    boolean deriving() {
        return deriving;
    }

    /**
     * Bring the derived state in line with the session's current tree, deriving the masks and
     * statistics in the background. Anything already in flight is superseded.
     * <p>
     * <b>The tree is settled first, here, on the FX thread.</b> The edit that asked for this
     * derivation — an undo, a redo, a load, a toggle — is complete the moment it is requested,
     * and {@link GatingSession#settle()} is what says so: it is the pre-state the <em>next</em>
     * edit's undo step is recorded from. Leaving it to {@link GatingSession#resync}, which
     * settles at the landing, opened a window in which the session's tree was already the
     * undone one while {@code settled} still held the tree it was undone away from — a gate
     * edit in that window recorded the abandoned tree as its undo step, so the next Ctrl+Z
     * moved <em>forward</em> onto the edit the user had just reverted. A derivation that fails
     * never lands at all, which made that state permanent.
     * <p>
     * With no cells there is nothing heavy to compute, so that resync happens here and now:
     * a round trip would leave the tree view showing the tree the user just left.
     */
    void request() {
        if (closed) return;
        session.settle();
        supersede();
        CellIndex index = session.index();
        if (index == null) {
            setDeriving(false);
            host.resynced(session.resync(host::annotations));
            return;
        }
        long stamp = generation.get();
        GatingSession.DerivationInputs inputs = session.derivationInputs(index, host::annotations);
        GatingSession.ReusableStats reusable = session.reusableStats();

        background.execute(() -> {
            // A superseded derivation stops before the sort and posts nothing: whatever
            // superseded it owns the session and the busy state now.
            if (superseded(stamp)) return;
            Outcome outcome;
            try {
                outcome = new Computed(deriver.derive(inputs, reusable));
            } catch (RuntimeException | Error ex) {
                outcome = new Failed(ex);
            }
            Outcome landed = outcome;
            fxThread.execute(() -> land(stamp, landed));
        });
        setDeriving(true);
    }

    /**
     * Drop anything in flight: its result, if it comes, is no longer wanted. The host is not
     * told the busy state cleared — it is the pane going away, and calling back into a panel
     * being torn down to re-enable controls that are about to be discarded is work for nobody.
     */
    void close() {
        closed = true;
        supersede();
        deriving = false;
    }

    private void land(long stamp, Outcome outcome) {
        if (closed || superseded(stamp)) return;        // newer work owns the session
        switch (outcome) {
            case Computed c -> {
                if (!session.stillDescribes(c.derived())) {
                    // The index changed under us (a read landed). Derive again in the
                    // background rather than let resync fall back to the FX thread.
                    request();
                    return;
                }
                setDeriving(false);
                host.resynced(session.resync(c.derived(), host::annotations));
            }
            case Failed f -> {
                // Nothing is adopted, so the session keeps the masks, statistics and counts it
                // had — the state a synchronous derive that threw would also have left, minus
                // the freeze. The tree edit that asked for this derivation stands either way.
                setDeriving(false);
                host.failed(f.error());
            }
        }
    }

    private boolean superseded(long stamp) {
        return generation.get() != stamp;
    }

    private void supersede() {
        generation.incrementAndGet();
    }

    private void setDeriving(boolean state) {
        if (deriving == state) return;
        deriving = state;
        host.busyChanged(state);
    }
}
