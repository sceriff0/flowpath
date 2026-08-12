package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.umap.testing.Embeddings;
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

    private static CellIndex indexOf(int cells) {
        // Two markers because an embedding is refused below that — see EmbeddingFeatures.
        return Cells.of(cells).marker("CD45", i -> i).marker("CD3", i -> i * 2.0).build();
    }

    private static UmapResult resultOf(int cells) {
        return resultOn(indexOf(cells));
    }

    private static UmapResult resultOn(CellIndex index) {
        int cells = index.size();
        return new UmapResult(new double[cells], new double[cells],
                index.getObjects(), index.getMarkerNames(),
                new UmapParameters(15, 0.1, 1.0, 50, 5));
    }

    /** The report of a run that degraded nothing — the shape most of these tests want. */
    private static EmbeddingReport cleanReport(int cells) {
        return cleanReport(indexOf(cells));
    }

    private static EmbeddingReport cleanReport(CellIndex index) {
        return EmbeddingReport.training(Embeddings.of(index), null)
                .completedWith(EmbeddingReport.Steering.none(),
                        EmbeddingReport.Projection.none());
    }

    /** A run that bought its layout by detaching one node, and what that cost. */
    private static EmbeddingReport steeredReport(CellIndex index, int detachedRow, int reweighted) {
        return EmbeddingReport.training(Embeddings.of(index), null)
                .completedWith(EmbeddingReport.Steering.detaching(detachedRow, reweighted),
                        EmbeddingReport.Projection.none());
    }

    private static UmapOutcome.Succeeded succeededOn(int cells) {
        CellIndex index = indexOf(cells);
        return UmapOutcome.succeeded(resultOn(index), cleanReport(index));
    }

    @Test
    void eachFactoryProducesItsOwnKind() {
        assertEquals(UmapOutcome.Kind.SUCCEEDED, succeededOn(3).kind());
        assertEquals(UmapOutcome.Kind.FAILED, UmapOutcome.failed("nope").kind());
        assertEquals(UmapOutcome.Kind.CANCELLED, UmapOutcome.cancelled().kind());
        assertEquals(UmapOutcome.Kind.SUPERSEDED, UmapOutcome.superseded().kind());
    }

    @Test
    void onlySucceededIsSuccessAndOnlyTheTwoAbandonmentsAreAbandoned() {
        assertTrue(succeededOn(3).isSuccess());
        assertFalse(UmapOutcome.failed("nope").isSuccess());

        assertTrue(UmapOutcome.cancelled().isAbandoned());
        assertTrue(UmapOutcome.superseded().isAbandoned());
        assertFalse(succeededOn(3).isAbandoned());
        assertFalse(UmapOutcome.failed("nope").isAbandoned());
    }

    @Test
    void succeededCarriesTheResultAndItsReport() {
        UmapResult result = resultOf(4);
        EmbeddingReport report = cleanReport(4);
        UmapOutcome.Succeeded succeeded = UmapOutcome.succeeded(result, report);
        assertSame(result, succeeded.result());
        assertSame(report, succeeded.report());
    }

    @Test
    void succeededRefusesANullResult() {
        assertThrows(NullPointerException.class, () -> UmapOutcome.succeeded(null, cleanReport(3)));
    }

    @Test
    void succeededRefusesAnEmbeddingWithNoAccountOfItself() {
        // The whole reason the report is a constructor argument rather than an optional
        // extra: a run that stranded cells at the origin or embedded a marker nothing was
        // measured for looks exactly like a clean one from here.
        assertThrows(NullPointerException.class, () -> UmapOutcome.succeeded(resultOf(3), null));
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
                succeededOn(10_500).describe());
    }

    @Test
    void aSteeredRunReportsBothTheImputedCellAndTheNeighbourhoodsAroundIt() {
        // Detaching a node to keep SMILE off its native initialisation path fabricates one
        // cell's position and shifts the edge weights of every cell that listed it — at
        // k=15, typically 3-4% of a small dataset. An outcome that named only the first
        // would be an understatement, and this is an instrument people draw conclusions
        // from. The facts now live on the report, and the sentence is unchanged — but it
        // reads out of describe() rather than out of the status line. Steering is
        // unconditional policy below the spectral limit, so it is provenance, not a
        // qualification: see EmbeddingReport's "findings versus notes".
        CellIndex index = indexOf(500);
        var steered = UmapOutcome.succeeded(resultOn(index), steeredReport(index, 241, 15));
        assertEquals(OptionalInt.of(241), steered.report().imputedCell());
        assertEquals(15, steered.report().reweightedCells());
        assertEquals("cell 241 imputed from its neighbours, 15 neighbourhoods reweighted",
                steered.report().describe());
        assertEquals("UMAP computed: 500 cells (k=15)", steered.describe(),
                "a run whose only qualification is policy does not spend the status line");
    }

    @Test
    void anUnsteeredRunSaysNothingAboutEither() {
        var plain = succeededOn(500);
        assertEquals(OptionalInt.empty(), plain.report().imputedCell());
        assertEquals(0, plain.report().reweightedCells());
        assertTrue(plain.report().isClean());
        assertEquals("UMAP computed: 500 cells (k=15)", plain.describe());
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
                succeededOn(2),
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
