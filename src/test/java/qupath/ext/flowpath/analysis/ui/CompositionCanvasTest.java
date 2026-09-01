package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
