package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarkerPositivityCanvas}'s reduction: positive / negative / ungated per marker,
 * where ungated is its own segment rather than folded into negative. No pixel assertions --
 * see {@code ScatterPlotCanvasCoordinateTest}.
 */
class MarkerPositivityCanvasTest {

    @Test
    void bothGatedMarkersAreOffered() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        assertEquals(Set.of("CD45", "CD3"), Set.copyOf(canvas.markers()));
    }

    @Test
    void aMarkerGatedAtTheRootHasNoUngatedCells() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        assertEquals(10, canvas.positiveCount("CD45"));
        assertEquals(10, canvas.negativeCount("CD45"));
        assertEquals(0, canvas.ungatedCount("CD45"), "every cell was tested against the CD45 gate");
    }

    @Test
    void aMarkerGatedOnlyUnderOneBranchLeavesTheRestUngatedRatherThanNegative() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        // CD3 is only gated under CD45+ (10 cells): 5 positive, 5 negative. The 10 CD45-
        // cells never reached the CD3 gate at all -- they must show up as ungated, not as
        // an extra 10 added to "negative".
        assertEquals(5, canvas.positiveCount("CD3"));
        assertEquals(5, canvas.negativeCount("CD3"));
        assertEquals(10, canvas.ungatedCount("CD3"),
                "the CD45- cells were never evaluated against CD3 -- unmeasured is not negative");
    }

    @Test
    void everyMarkersThreeSegmentsAccountForTheWholeScope() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        for (String marker : canvas.markers()) {
            int sum = canvas.positiveCount(marker) + canvas.negativeCount(marker) + canvas.ungatedCount(marker);
            assertEquals(canvas.scopeTotal(), sum, marker + ": positive + negative + ungated must equal everyone");
        }
        assertTrue(canvas.scopeTotal() > 0);
    }
}
