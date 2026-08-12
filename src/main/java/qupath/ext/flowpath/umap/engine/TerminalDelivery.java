package qupath.ext.flowpath.umap.engine;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The terminal channel of a single UMAP run: at most one {@link UmapOutcome} ever
 * leaves it, and the second attempt is a no-op rather than a second callback.
 * <p>
 * This exists because "exactly one terminal callback per run" is an invariant that
 * cannot be upheld by discipline. The run body has several exits — three staleness
 * guards, a precondition refusal, two catch clauses, the normal return — and the old
 * code repeated a bespoke null-check-and-{@code runLater} at each one. Some exits
 * delivered twice under a race, most delivered nothing. Handing every exit the same
 * one-shot object makes both mistakes unexpressible: the caller's {@code finally} can
 * deliver blindly, knowing a real outcome delivered earlier wins.
 *
 * <h2>Why the sink is a supplier</h2>
 * The consumer callback is a volatile field on the service that {@code shutdown()}
 * nulls. Reading it through a supplier at delivery time — rather than capturing it at
 * construction — means a run in flight during teardown does not resurrect a torn-down
 * UI. It also means the outcome can be recorded even when nobody is listening, which
 * is what {@code onDelivered} is for: after {@code shutdown()} there is no callback,
 * but the run still ended for a reason, and a reason nobody records is the very
 * silence this whole type exists to end.
 */
final class TerminalDelivery {

    private final AtomicBoolean delivered = new AtomicBoolean(false);
    private final Executor executor;
    private final Supplier<Consumer<UmapOutcome>> sink;
    private final Consumer<UmapOutcome> onDelivered;

    /**
     * @param executor    where the consumer callback runs — the FX thread in production,
     *                    a direct executor in tests that own no toolkit
     * @param sink        reads the current consumer; may return null (no listener)
     * @param onDelivered synchronous bookkeeping, run inside the one-shot guard and
     *                    before the callback is scheduled, so it happens exactly once
     *                    per run whether or not anyone is listening
     */
    TerminalDelivery(Executor executor,
                     Supplier<Consumer<UmapOutcome>> sink,
                     Consumer<UmapOutcome> onDelivered) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.onDelivered = Objects.requireNonNull(onDelivered, "onDelivered");
    }

    /**
     * Deliver {@code outcome} unless this run already delivered one.
     *
     * @return true if this call was the one that delivered; false if the run was
     *         already terminated, in which case nothing happened
     */
    boolean deliver(UmapOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (!delivered.compareAndSet(false, true)) {
            return false;
        }
        onDelivered.accept(outcome);
        Consumer<UmapOutcome> consumer = sink.get();
        if (consumer != null) {
            executor.execute(() -> consumer.accept(outcome));
        }
        return true;
    }

    /** True once an outcome has been delivered for this run. */
    boolean isDelivered() {
        return delivered.get();
    }
}
