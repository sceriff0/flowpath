package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

/**
 * The outcome type carries no behaviour beyond naming the four ends and describing
 * them, so these tests are about exactly that: which end is which, and that a failure
 * caused by a throwable can always be traced back to its class — the whole point of
 * the type is that a failure stops being anonymous.
 * <p>
 * No toolkit is started here on purpose. The outcome is a decision about what happened,
 * not a UI concern, and it must remain constructible and inspectable without JavaFX.
 */
class UmapOutcomeTest {

    private static UmapResult resultOf(int cells) {
        CellIndex index = Cells.of(cells).marker("CD45", i -> i).build();
        return new UmapResult(new double[cells], new double[cells],
                index.getObjects(), index.getMarkerNames(),
                new UmapParameters(15, 0.1, 1.0, 50, 5));
    }

    @Test
    void eachFactoryProducesItsOwnKind() {
        assertEquals(UmapOutcome.Kind.SUCCEEDED, UmapOutcome.succeeded(resultOf(3)).kind());
        assertEquals(UmapOutcome.Kind.FAILED, UmapOutcome.failed("nope").kind());
        assertEquals(UmapOutcome.Kind.CANCELLED, UmapOutcome.cancelled().kind());
        assertEquals(UmapOutcome.Kind.SUPERSEDED, UmapOutcome.superseded().kind());
    }

    @Test
    void onlySucceededIsSuccessAndOnlyTheTwoAbandonmentsAreAbandoned() {
        assertTrue(UmapOutcome.succeeded(resultOf(3)).isSuccess());
        assertFalse(UmapOutcome.failed("nope").isSuccess());

        assertTrue(UmapOutcome.cancelled().isAbandoned());
        assertTrue(UmapOutcome.superseded().isAbandoned());
        assertFalse(UmapOutcome.succeeded(resultOf(3)).isAbandoned());
        assertFalse(UmapOutcome.failed("nope").isAbandoned());
    }

    @Test
    void succeededCarriesTheResult() {
        UmapResult result = resultOf(4);
        assertSame(result, UmapOutcome.succeeded(result).result());
    }

    @Test
    void succeededRefusesANullResult() {
        assertThrows(NullPointerException.class, () -> UmapOutcome.succeeded(null));
    }

    @Test
    void failedFromAThrowableNamesItsClassInBothTheFieldAndTheMessage() {
        // The failure that actually happens: SMILE reaching for an ARPACK native this
        // extension does not ship. It is an Error, its message is the missing class
        // path and nothing else, and before the outcome type it produced no report at
        // all. A consumer must be able to see WHAT threw without parsing prose.
        Throwable arpack = new NoClassDefFoundError("org/bytedeco/arpackng/global/arpack");
        UmapOutcome.Failed failed = UmapOutcome.failed("UMAP failed: " + arpack.getMessage(), arpack);

        assertTrue(failed.fromThrowable());
        assertEquals("java.lang.NoClassDefFoundError", failed.throwableClass());
        assertTrue(failed.describe().contains("java.lang.NoClassDefFoundError"),
                "the class name must reach the human-readable message too: " + failed.describe());
        assertTrue(failed.describe().contains("org/bytedeco/arpackng/global/arpack"),
                "the reason must survive: " + failed.describe());
    }

    @Test
    void failedWithoutAThrowableSaysSoRatherThanInventingOne() {
        UmapOutcome.Failed failed = UmapOutcome.failed("Too few cells (2) for UMAP. Need at least 3.");
        assertFalse(failed.fromThrowable());
        assertNull(failed.throwableClass());
        assertEquals("Too few cells (2) for UMAP. Need at least 3.", failed.describe());
    }

    @Test
    void failedRefusesANullReason() {
        assertThrows(NullPointerException.class, () -> UmapOutcome.failed(null));
    }

    @Test
    void succeededDescribesCellCountsWithTheUsGroupingSeparator() {
        // The JVM default locale here is en_IT, which would render 10500 as "10.500".
        assertEquals("UMAP computed: 10,500 cells (k=15)",
                UmapOutcome.succeeded(resultOf(10_500)).describe());
    }

    @Test
    void aSteeredRunReportsBothTheImputedCellAndTheNeighbourhoodsAroundIt() {
        // Detaching a node to keep SMILE off its native initialisation path fabricates one
        // cell's position and shifts the edge weights of every cell that listed it — at
        // k=15, typically 3-4% of a small dataset. An outcome that named only the first
        // would be an understatement, and this is an instrument people draw conclusions
        // from.
        var steered = UmapOutcome.succeeded(resultOf(500), 241, 15);
        assertEquals(OptionalInt.of(241), steered.imputedCell());
        assertEquals(15, steered.reweightedCells());
        assertEquals("UMAP computed: 500 cells (k=15); cell 241 imputed from its "
                + "neighbours, 15 neighbourhoods reweighted", steered.describe());
    }

    @Test
    void anUnsteeredRunSaysNothingAboutEither() {
        var plain = UmapOutcome.succeeded(resultOf(500));
        assertEquals(OptionalInt.empty(), plain.imputedCell());
        assertEquals(0, plain.reweightedCells());
        assertEquals("UMAP computed: 500 cells (k=15)", plain.describe());
    }

    @Test
    void reweightingWithoutAnImputationIsNotARunThatCanHappen() {
        // Nothing but detaching a node perturbs a neighbourhood, so the combination
        // describes a run that cannot exist. Refusing it here is what stops a future
        // caller reporting the blast radius while quietly dropping the cell at its centre.
        assertThrows(IllegalArgumentException.class,
                () -> new UmapOutcome.Succeeded(resultOf(3), OptionalInt.empty(), 4));
        assertThrows(IllegalArgumentException.class,
                () -> new UmapOutcome.Succeeded(resultOf(3), OptionalInt.of(1), -1));
    }

    @Test
    void theTwoAbandonmentsDescribeThemselvesDistinctly() {
        assertEquals("UMAP cancelled", UmapOutcome.cancelled().describe());
        assertTrue(UmapOutcome.superseded().describe().contains("superseded"));
    }

    /**
     * The sealed hierarchy must stay exhaustive without a default branch — that is what
     * makes a future fifth end a compile error at every consumer rather than a silently
     * ignored case.
     */
    @Test
    void everyOutcomeIsMatchedByAnExhaustiveSwitch() {
        for (UmapOutcome outcome : new UmapOutcome[] {
                UmapOutcome.succeeded(resultOf(2)),
                UmapOutcome.failed("nope"),
                UmapOutcome.cancelled(),
                UmapOutcome.superseded() }) {
            UmapOutcome.Kind matched = switch (outcome) {
                case UmapOutcome.Succeeded s -> UmapOutcome.Kind.SUCCEEDED;
                case UmapOutcome.Failed f -> UmapOutcome.Kind.FAILED;
                case UmapOutcome.Cancelled c -> UmapOutcome.Kind.CANCELLED;
                case UmapOutcome.Superseded s -> UmapOutcome.Kind.SUPERSEDED;
            };
            assertEquals(outcome.kind(), matched);
        }
    }
}
