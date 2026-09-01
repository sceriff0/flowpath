package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompositionCanvas}'s reduction: leaf rows at {@code WHOLE_SLIDE}, largest first.
 * No pixel assertions — see {@code ScatterPlotCanvasCoordinateTest}.
 */
class CompositionCanvasTest {

    @Test
    void compositionUsesLeafPopulationsOnlySortedBySize() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        List<String> bars = canvas.barLabels();
        assertFalse(bars.contains("CD45+"),
                "an internal branch would be double-counted with its own children");
        assertEquals(bars, bars.stream()
                        .sorted((a, b) -> Integer.compare(canvas.barValue(b), canvas.barValue(a)))
                        .toList(),
                "largest first -- the populations that dominate are read first");
    }

    @Test
    void barsSumToTheDenominator() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        int sum = canvas.barLabels().stream().mapToInt(canvas::barValue).sum();
        assertEquals(canvas.total(), sum, "a composition that does not sum is not a composition");
    }

    @Test
    void leavesAreExactlyTheThreeTerminalPopulationsWithTheirTrueCounts() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        assertEquals(Set.of("CD45-", "CD45+/CD3+", "CD45+/CD3-"), Set.copyOf(canvas.barLabels()));
        assertEquals(10, canvas.barValue("CD45-"));
        assertEquals(5, canvas.barValue("CD45+/CD3+"));
        assertEquals(5, canvas.barValue("CD45+/CD3-"));
        assertEquals(20, canvas.total());
    }

    /**
     * A tree with two independent root gates ({@code twoRootRows()}) must not have its
     * leaves pooled across both roots -- each root already sums to the whole population on
     * its own, so pooling both would sum the bars to 2x the true denominator.
     * "+ Add Root Gate" makes this an ordinary FlowJo-style pattern, not a corner case.
     */
    @Test
    void defaultsToTheFirstRootAndDoesNotDoubleCountAcrossRoots() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoRootRows());

        assertEquals(Set.of("CD45-", "CD45+/CD3+", "CD45+/CD3-"), Set.copyOf(canvas.barLabels()),
                "defaults to the first root (CD45) -- CD19's leaves must not be pooled in");
        int sum = canvas.barLabels().stream().mapToInt(canvas::barValue).sum();
        assertEquals(20, sum, "the true population, not double it");
        assertEquals(20, canvas.total());
    }

    @Test
    void switchingTheSelectedRootChangesTheBarsAndStillSumsExactlyOnce() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoRootRows());

        assertEquals(List.of("CD45", "CD19"), canvas.availableRoots());

        canvas.setSelectedRoot("CD19");
        assertEquals(Set.of("CD19+", "CD19-"), Set.copyOf(canvas.barLabels()));
        int sum = canvas.barLabels().stream().mapToInt(canvas::barValue).sum();
        assertEquals(20, sum);
        assertEquals(20, canvas.total());
        assertTrue(sum < 40, "must not still be carrying the other root's leaves too");
    }
}
