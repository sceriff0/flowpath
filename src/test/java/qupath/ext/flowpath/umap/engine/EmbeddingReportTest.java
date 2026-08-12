package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.testing.Cells;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a successful embedding had to degrade, and — the point of the type — that it
 * cannot be built without saying so.
 * <p>
 * No toolkit is started here on purpose. Everything this class decides is a fact about
 * the data the run walked, not about a pane, so it must be inspectable without JavaFX.
 */
class EmbeddingReportTest {

    /** Every cell of {@code marker} carries a value, and the values vary. */
    private static CellIndex healthy(int cells) {
        return Cells.of(cells)
                .marker("CD45", i -> i)
                .marker("CD8", i -> i * 2.0)
                .build();
    }

    /** Indices {@code 0..trained-1}, i.e. a subsample that is a prefix of the population. */
    private static int[] firstRows(int trained) {
        int[] rows = new int[trained];
        for (int i = 0; i < trained; i++) rows[i] = i;
        return rows;
    }

    @Test
    void aRunThatDegradedNothingReportsNothing() {
        EmbeddingReport report = EmbeddingReport.training(healthy(6), null)
                .completedWith(EmbeddingReport.Steering.none(), 0);

        assertTrue(report.isClean());
        assertTrue(report.findings().isEmpty(), report.findings().toString());
        assertEquals("", report.summary(),
                "an empty summary is what lets a caller concatenate it blindly");
        assertTrue(report.describe().startsWith("Embedding clean:"), report.describe());
        assertEquals(EmbeddingReport.Initialisation.PCA, report.initialisation());
        assertEquals(OptionalInt.empty(), report.imputedCell());
        assertEquals(0, report.cellsAtOrigin());
    }

    @Test
    void aMarkerNoTrainingCellCarriedIsAFindingRatherThanAColumnOfZeros() {
        // The CellIndex.toMatrix path this exists to make visible: a fully-NaN column is
        // imputed with its own column mean, and the mean of nothing is 0.0, so the marker
        // silently becomes a constant zero and drops out of every distance.
        CellIndex index = Cells.of(6)
                .marker("CD45", i -> i)
                .marker("FoxP3", i -> 1.0).absentOn(i -> true)
                .build();

        EmbeddingReport report = EmbeddingReport.training(index, null)
                .completedWith(EmbeddingReport.Steering.none(), 0);

        assertEquals(java.util.List.of("FoxP3"), report.unmeasuredMarkers());
        assertTrue(report.constantMarkers().isEmpty(),
                "never measured is not the same as measured and uniform");
        assertFalse(report.isClean());
        assertTrue(report.summary().contains("FoxP3"), report.summary());
    }

    @Test
    void aMarkerThatIsMerelyUniformIsReportedApartFromOneThatWasNeverMeasured() {
        // The same distinction IngestReport draws between a measurement omitted upstream
        // and a literal 0.0: both leave a column that contributes nothing to any
        // distance, and they mean opposite things about the data.
        CellIndex index = Cells.of(6)
                .marker("CD45", i -> i)
                .marker("CD3", i -> 7.0)
                .marker("FoxP3", i -> 1.0).absentOn(i -> true)
                .build();

        EmbeddingReport report = EmbeddingReport.training(index, null)
                .completedWith(EmbeddingReport.Steering.none(), 0);

        assertEquals(java.util.List.of("FoxP3"), report.unmeasuredMarkers());
        assertEquals(java.util.List.of("CD3"), report.constantMarkers());
        String described = report.describe();
        assertTrue(described.contains("no value on any training cell"), described);
        assertTrue(described.contains("the same value on every training cell"), described);
    }

    @Test
    void degeneracyIsJudgedOnTheCellsTheEmbeddingWasTrainedOnNotThePopulation() {
        // A marker can be perfectly informative across the slide and dead inside the
        // subsample UMAP actually saw. The embedding is the subsample's, so the report is
        // the subsample's too.
        CellIndex index = Cells.of(8)
                .marker("CD45", i -> i)
                .marker("CD3", i -> i < 4 ? 2.0 : i)
                .build();

        EmbeddingReport onSubsample = EmbeddingReport.training(index, firstRows(4))
                .completedWith(EmbeddingReport.Steering.none(), 0);
        EmbeddingReport onEverything = EmbeddingReport.training(index, null)
                .completedWith(EmbeddingReport.Steering.none(), 0);

        assertEquals(java.util.List.of("CD3"), onSubsample.constantMarkers());
        assertTrue(onEverything.constantMarkers().isEmpty());
    }

    @Test
    void cellsLeftAtTheOriginAreCountedAndNamedAsTheFakeStructureTheyAre() {
        EmbeddingReport report = EmbeddingReport.training(healthy(2000), firstRows(500))
                .completedWith(EmbeddingReport.Steering.none(), 1204);

        assertEquals(1204, report.cellsAtOrigin());
        assertFalse(report.isClean());
        String summary = report.summary();
        assertTrue(summary.contains("1,204"),
                "the JVM default locale here is en_IT, which would render this as 1.204: " + summary);
        assertTrue(summary.contains("(0,0)"), summary);
    }

    @Test
    void aCellCannotBeParkedAtTheOriginWhenEveryCellWasTrainedOn() {
        // Parking happens in the projection of held-out cells and nowhere else, so a
        // count without a subsample describes a run that cannot have happened.
        EmbeddingReport.Training training = EmbeddingReport.training(healthy(6), null);
        assertThrows(IllegalArgumentException.class,
                () -> training.completedWith(EmbeddingReport.Steering.none(), 1));
    }

    @Test
    void moreCellsCannotBeParkedThanWereHeldOut() {
        EmbeddingReport.Training training = EmbeddingReport.training(healthy(10), firstRows(4));
        assertThrows(IllegalArgumentException.class,
                () -> training.completedWith(EmbeddingReport.Steering.none(), 7));
    }

    @Test
    void theImputedCellIsTranslatedOutOfTheTrainingMatrixIntoTheCallersIndex() {
        // The detached node is a row of the training matrix. Reporting it raw would name
        // an innocent cell, and the only place holding both the row and the subsample is
        // this one — so the translation happens here rather than at a call site that
        // could forget it.
        int[] sample = {2, 5, 7};
        EmbeddingReport report = EmbeddingReport.training(healthy(10), sample)
                .completedWith(EmbeddingReport.Steering.detaching(1, 4), 0);

        assertEquals(OptionalInt.of(5), report.imputedCell());
        assertEquals(4, report.reweightedCells());
        assertEquals(EmbeddingReport.Initialisation.PCA_STEERED_FROM_SPECTRAL,
                report.initialisation());
    }

    @Test
    void aDetachedRowOutsideTheTrainingMatrixIsRefused() {
        EmbeddingReport.Training training = EmbeddingReport.training(healthy(10), firstRows(3));
        assertThrows(IllegalArgumentException.class,
                () -> training.completedWith(EmbeddingReport.Steering.detaching(3, 1), 0));
    }

    @Test
    void steeringSaysWhatItCostInTheSameBreathAsSayingItHappened() {
        // Task 2's discipline, moved here with the facts: nothing but detaching a node
        // perturbs a neighbourhood, so a blast radius without a centre — or a centre
        // reported as an absence — describes a run that cannot exist.
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingReport.Steering(OptionalInt.empty(), 4));
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingReport.Steering(OptionalInt.of(1), -1));
        assertFalse(EmbeddingReport.Steering.none().isSteered());
        assertTrue(EmbeddingReport.Steering.detaching(0, 0).isSteered());
    }

    @Test
    void theSteeringLineReadsExactlyAsItDidWhenItLivedOnTheOutcome() {
        EmbeddingReport report = EmbeddingReport.training(healthy(500), null)
                .completedWith(EmbeddingReport.Steering.detaching(241, 15), 0);
        assertEquals("cell 241 imputed from its neighbours, 15 neighbourhoods reweighted",
                report.summary());
    }

    @Test
    void subsamplingIsRecordedAsProvenanceRatherThanAsADefect() {
        // Auto subsampling is the default and a deliberate speed/memory trade, not a
        // failure — so it belongs where IngestReport puts a literal 0.0: on the record,
        // out of the findings.
        EmbeddingReport report = EmbeddingReport.training(healthy(900), firstRows(300))
                .completedWith(EmbeddingReport.Steering.none(), 0);

        assertTrue(report.isClean(), report.findings().toString());
        assertTrue(report.subsampled());
        assertEquals(300, report.trainedCells());
        assertEquals(900, report.totalCells());
        assertEquals(1, report.notes().size());
        assertTrue(report.notes().get(0).contains("300 of 900"), report.notes().get(0));
    }

    @Test
    void theSummaryNamesTheFirstFindingAndCountsTheRest() {
        CellIndex index = Cells.of(2000)
                .marker("CD45", i -> i)
                .marker("CD3", i -> 7.0)
                .build();
        EmbeddingReport report = EmbeddingReport.training(index, firstRows(500))
                .completedWith(EmbeddingReport.Steering.detaching(1, 9), 12);

        assertEquals(3, report.findings().size(), report.findings().toString());
        assertTrue(report.summary().endsWith("(+2 more)"), report.summary());
        assertTrue(report.describe().contains("\n"),
                "describe() is the long form: every finding and every note");
    }
}
