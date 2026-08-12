package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-shot guarantee, tested where it lives rather than through a UMAP run.
 * <p>
 * Toolkit-free by construction: the delivery executor is an argument, so proving
 * "exactly one outcome leaves this channel" needs no JavaFX and no SMILE.
 */
class TerminalDeliveryTest {

    /** Runs the callback inline, so assertions need no waiting. */
    private static final Executor DIRECT = Runnable::run;

    private final List<UmapOutcome> received = new ArrayList<>();
    private final List<UmapOutcome> recorded = new ArrayList<>();
    private Consumer<UmapOutcome> sink = received::add;

    private TerminalDelivery delivery() {
        return new TerminalDelivery(DIRECT, () -> sink, recorded::add);
    }

    @Test
    void deliversTheOutcomeOnce() {
        TerminalDelivery delivery = delivery();
        assertFalse(delivery.isDelivered());

        assertTrue(delivery.deliver(UmapOutcome.cancelled()));

        assertTrue(delivery.isDelivered());
        assertEquals(1, received.size());
        assertSame(UmapOutcome.cancelled(), received.get(0));
    }

    @Test
    void aSecondDeliveryIsANoOpRatherThanASecondCallback() {
        // Two paths can reach the end of one run — cancel() terminating it from the
        // outside while the body is still unwinding — and the consumer must not be
        // told twice. The first outcome wins; the second is dropped whole.
        TerminalDelivery delivery = delivery();

        assertTrue(delivery.deliver(UmapOutcome.cancelled()));
        assertFalse(delivery.deliver(UmapOutcome.failed("late failure")),
                "the second delivery must report that it did nothing");

        assertEquals(1, received.size());
        assertEquals(UmapOutcome.Kind.CANCELLED, received.get(0).kind());
        assertEquals(1, recorded.size(), "bookkeeping must run once per run, not once per attempt");
    }

    @Test
    void anOutcomeWithNobodyListeningIsStillRecorded() {
        // shutdown() nulls the consumer while a run is in flight. The run still ended
        // for a reason, and losing that reason is the failure mode this type exists to
        // close — so the bookkeeping runs whether or not anyone is listening.
        sink = null;
        TerminalDelivery delivery = delivery();

        assertTrue(delivery.deliver(UmapOutcome.failed("boom")));

        assertTrue(received.isEmpty());
        assertEquals(1, recorded.size());
        assertEquals(UmapOutcome.Kind.FAILED, recorded.get(0).kind());
    }

    @Test
    void theConsumerIsReadAtDeliveryTimeNotAtConstructionTime() {
        // The service's consumer is a volatile field that shutdown() nulls; reading it
        // through the supplier is what stops a run in flight from resurrecting a
        // torn-down UI — and what lets a consumer registered late still be reached.
        sink = null;
        TerminalDelivery delivery = delivery();
        sink = received::add;

        delivery.deliver(UmapOutcome.superseded());

        assertEquals(1, received.size());
    }

    @Test
    void refusesANullOutcome() {
        assertThrows(NullPointerException.class, () -> delivery().deliver(null));
    }
}
