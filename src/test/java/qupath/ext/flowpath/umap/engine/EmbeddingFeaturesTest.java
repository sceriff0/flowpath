package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The include flag's consumer. These tests are the ones that used to be impossible to
 * write: before {@code EmbeddingFeatures} there was no object between the picker and the
 * matrix to ask "did unticking that marker change anything?", and the honest answer was
 * no.
 */
class EmbeddingFeaturesTest {

    /** Everything ticked (the default), which is what a legacy image loads with. */
    private static MarkerSelection everything() {
        return new MarkerSelection();
    }

    private static MarkerSelection excluding(String... markers) {
        MarkerSelection sel = new MarkerSelection();
        for (String m : markers) sel.put(m, MarkerSelection.defaultEntry().withIncluded(false));
        return sel;
    }

    /** Three markers, cell i carrying i, 10i and 100i. */
    private static CellIndex threeMarkers(int cells) {
        return Cells.of(cells)
                .marker("CD3", i -> i)
                .marker("CD8", i -> i * 10.0)
                .marker("FoxP3", i -> i * 100.0)
                .build();
    }

    static EmbeddingFeatures.Selected selected(CellIndex index, MarkerSelection selection) {
        return assertInstanceOf(EmbeddingFeatures.Selected.class,
                EmbeddingFeatures.of(index, selection));
    }

    // --- What the run may see -------------------------------------------------

    @Test
    void anUntickedMarkerIsNotAFeature() {
        var features = selected(threeMarkers(6), excluding("FoxP3"));

        assertEquals(2, features.featureCount());
        assertArrayEquals(new String[]{"CD3", "CD8"}, features.featureNames());
        assertEquals(List.of("FoxP3"), features.excludedMarkers());
        assertEquals(6, features.cellCount(), "excluding a marker excludes no cells");
    }

    @Test
    void aMarkerWithNoEntryIsIncluded() {
        // MarkerSelection's own default, and the behaviour every image saved before the
        // feature picker existed relies on.
        var features = selected(threeMarkers(4), everything());

        assertEquals(3, features.featureCount());
        assertTrue(features.excludedMarkers().isEmpty());
    }

    @Test
    void theMatrixCarriesOnlyTheTickedColumns() {
        var features = selected(threeMarkers(3), excluding("CD8"));
        double[][] matrix = features.toMatrix();

        assertEquals(3, matrix.length);
        assertEquals(2, matrix[0].length, "CD8 must not occupy a column");
        // cell 2: CD3 = 2, FoxP3 = 200. CD8 (=20) appears nowhere.
        assertArrayEquals(new double[]{2.0, 200.0}, matrix[2]);
    }

    @Test
    void theMatrixTransposesToCellByFeature() {
        // Was CellIndexCentroidTest.toMatrixTransposesCorrectly, and moved here with the
        // method: [marker][cell] storage, [cell][feature] input.
        CellIndex index = Cells.of(2)
                .marker("CD45", 1.0, 2.0)
                .marker("CD3", 10.0, 20.0)
                .build();
        double[][] matrix = selected(index, everything()).toMatrix();

        assertEquals(2, matrix.length);
        assertEquals(2, matrix[0].length);
        assertArrayEquals(new double[]{1.0, 10.0}, matrix[0]);
        assertArrayEquals(new double[]{2.0, 20.0}, matrix[1]);
    }

    /**
     * The whole point, stated as strongly as it can be stated at this level: two
     * populations that differ <em>only</em> in an excluded marker produce byte-identical
     * embedding input. UMAP is a function of that matrix, so an excluded marker cannot
     * move a point — no run needed to prove it, and no dependence on the optimiser being
     * deterministic.
     */
    @Test
    void anExcludedMarkerCannotMoveAPoint() {
        CellIndex quiet = Cells.of(20)
                .marker("CD3", i -> i)
                .marker("CD8", i -> i * 10.0)
                .marker("FoxP3", i -> 1.0)
                .build();
        CellIndex loud = Cells.of(20)
                .marker("CD3", i -> i)
                .marker("CD8", i -> i * 10.0)
                .marker("FoxP3", i -> i * 1e6)
                .build();

        var withFoxP3Out = excluding("FoxP3");
        assertArrayEquals(selected(quiet, withFoxP3Out).toMatrix(),
                selected(loud, withFoxP3Out).toMatrix(),
                "the embedding input must not depend on an unticked marker");

        // And the same comparison with FoxP3 ticked, to show the test has teeth: if
        // exclusion did nothing, the assertion above would be this one.
        assertFalse(java.util.Arrays.deepEquals(selected(quiet, everything()).toMatrix(),
                        selected(loud, everything()).toMatrix()),
                "a ticked marker does change the input — otherwise the test above proves nothing");
    }

    @Test
    void theSubMatrixIsRestrictedTheSameWay() {
        var features = selected(threeMarkers(10), excluding("CD3"));
        double[] means = new double[features.featureCount()];
        double[][] sub = features.subMatrix(new int[]{2, 5}, means);

        assertEquals(2, sub.length);
        assertEquals(2, sub[0].length);
        assertArrayEquals(new double[]{20.0, 200.0}, sub[0]);
        assertArrayEquals(new double[]{50.0, 500.0}, sub[1]);
        assertArrayEquals(new double[]{35.0, 350.0}, means, 1e-9);
    }

    @Test
    void subMatrixRefusesAMeansArrayOfTheWrongWidth() {
        var features = selected(threeMarkers(4), excluding("CD3"));
        assertThrows(IllegalArgumentException.class,
                () -> features.subMatrix(new int[]{0, 1}, new double[3]));
    }

    // --- Imputation, inherited from the matrix builder this replaced ----------

    @Test
    void aMissingValueIsImputedWithItsOwnColumnMean() {
        CellIndex index = Cells.of(3)
                .marker("CD3", 2.0, 0.0, 6.0).absentOn(i -> i == 1)
                .marker("CD8", 1.0, 2.0, 3.0)
                .build();
        double[][] matrix = selected(index, everything()).toMatrix();

        assertEquals(4.0, matrix[1][0], "mean of the values the column DOES carry");
        assertEquals(2.0, matrix[1][1]);
    }

    @Test
    void aColumnNoCellCarriesImputesToZero() {
        // The mean of nothing. Reported as a finding by EmbeddingReport; here only the
        // arithmetic is pinned.
        CellIndex index = Cells.of(3)
                .marker("CD3", 1.0, 2.0, 3.0)
                .marker("CD8", 1.0, 2.0, 3.0)
                .panel("CD3", "CD8", "FoxP3")
                .build();
        double[][] matrix = selected(index, everything()).toMatrix();

        assertEquals(3, matrix[0].length);
        assertEquals(0.0, matrix[0][2]);
        assertEquals(0.0, matrix[2][2]);
    }

    @Test
    void subMatrixImputesFromTheSampledCellsOnly() {
        CellIndex index = Cells.of(4)
                .marker("CD3", 10.0, 0.0, 20.0, 1000.0).absentOn(i -> i == 1)
                .marker("CD8", 1.0, 2.0, 3.0, 4.0)
                .build();
        double[] means = new double[2];
        double[][] sub = selected(index, everything()).subMatrix(new int[]{0, 1, 2}, means);

        assertEquals(15.0, means[0], "the held-out cell's 1000 must not shift the mean");
        assertEquals(15.0, sub[1][0]);
    }

    // --- The no-copy contract -------------------------------------------------

    @Test
    void columnsAreTheBackingArrays() {
        CellIndex index = threeMarkers(5);
        var features = selected(index, excluding("CD3"));

        assertSame(index.getMarkerValuesRaw(1), features.column(0),
                "feature 0 is CD8 — the index's column 1, not a copy of it");
        assertSame(index.getObjects(), features.objects());
    }

    @Test
    void featureNamesAreSafeToKeep() {
        var features = selected(threeMarkers(2), everything());
        String[] first = features.featureNames();
        first[0] = "mutated";
        assertEquals("CD3", features.featureNames()[0]);
    }

    // --- The edges are a type ------------------------------------------------

    @Test
    void untickingEverythingIsRefusedRatherThanEmbedded() {
        var refused = assertInstanceOf(EmbeddingFeatures.Refused.class,
                EmbeddingFeatures.of(threeMarkers(50), excluding("CD3", "CD8", "FoxP3")));
        assertTrue(refused.reason().contains("No markers are selected"), refused.reason());
        assertTrue(refused.reason().contains("3"), "say how many were unticked: " + refused.reason());
    }

    @Test
    void oneTickedMarkerIsRefusedRatherThanLaidOutInOneDimension() {
        var refused = assertInstanceOf(EmbeddingFeatures.Refused.class,
                EmbeddingFeatures.of(threeMarkers(50), excluding("CD3", "CD8")));
        assertTrue(refused.reason().contains("Only 1 of 3"), refused.reason());
        assertTrue(refused.reason().contains("at least 2"), refused.reason());
    }

    @Test
    void exactlyTwoTickedMarkersEmbed() {
        // The boundary itself, and the case a "nearly degenerate" run must still reach:
        // two constant markers embed and are reported by EmbeddingReport, not refused here.
        var features = selected(threeMarkers(50), excluding("FoxP3"));
        assertEquals(EmbeddingFeatures.MINIMUM_FEATURES, features.featureCount());
    }

    @Test
    void anEmptyPanelIsRefusedWithoutBlamingTheUser() {
        CellIndex index = Cells.of(3).marker("CD3", 1.0, 2.0, 3.0).panel().build();
        var refused = assertInstanceOf(EmbeddingFeatures.Refused.class,
                EmbeddingFeatures.of(index, everything()));
        assertEquals("No markers are available to embed.", refused.reason());
    }

    @Test
    void nullsAreRejectedAtTheFactory() {
        assertThrows(NullPointerException.class,
                () -> EmbeddingFeatures.of(null, everything()));
        assertThrows(NullPointerException.class,
                () -> EmbeddingFeatures.of(threeMarkers(2), null));
    }

    // --- The label and the run read one rule ---------------------------------

    @Test
    void theSharedFilterAgreesWithTheFeatureSet() {
        var selection = excluding("CD8");
        List<String> panel = List.of("CD3", "CD8", "FoxP3");

        assertEquals(List.of("CD3", "FoxP3"),
                EmbeddingFeatures.includedMarkers(panel, selection));
        assertArrayEquals(EmbeddingFeatures.includedMarkers(panel, selection).toArray(),
                selected(threeMarkers(4), selection).featureNames(),
                "the count in the empty-state label and the columns the run reads are one rule");
    }
}
