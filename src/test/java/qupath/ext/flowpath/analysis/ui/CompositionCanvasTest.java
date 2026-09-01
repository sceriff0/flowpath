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

        assertEquals(List.of(0, 1), canvas.availableRoots());
        assertEquals("CD45", canvas.rootLabel(0));
        assertEquals("CD19", canvas.rootLabel(1));

        canvas.setSelectedRoot(1);
        assertEquals(Set.of("CD19+", "CD19-"), Set.copyOf(canvas.barLabels()));
        int sum = canvas.barLabels().stream().mapToInt(canvas::barValue).sum();
        assertEquals(20, sum);
        assertEquals(20, canvas.total());
        assertTrue(sum < 40, "must not still be carrying the other root's leaves too");
    }

    /**
     * The residual from the first fix: partitioning by {@code gateChannel} adjacency never
     * split two roots on the <em>identical</em>, un-renamed channel into two blocks at all
     * -- the second root's rows were appended into the first's still-open block, and the
     * leaf/prefix matching then cross-matched between them. {@code rootIndex} is
     * identity-based and cannot collide the way a channel name (or a path) can, so this
     * fixture -- unlike {@link AnalysisFixtures#twoRootRows()} -- must also pass.
     */
    @Test
    void twoRootsOnTheIdenticalChannelAreKeptSeparateByRootIndexNotByName() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());

        assertEquals(List.of(0, 1), canvas.availableRoots());
        assertEquals("CD45", canvas.rootLabel(0));
        assertEquals("CD45", canvas.rootLabel(1), "both roots share the identical channel");

        // Root 0 (threshold 10.5): 10 positive, 10 negative.
        assertEquals(Set.of("CD45+", "CD45-"), Set.copyOf(canvas.barLabels()));
        assertEquals(10, canvas.barValue("CD45+"));
        assertEquals(10, canvas.barValue("CD45-"));
        int sum0 = canvas.barLabels().stream().mapToInt(canvas::barValue).sum();
        assertEquals(20, sum0, "the denominator exactly once, not merged with the other root");
        assertEquals(20, canvas.total());

        // Root 1 (threshold 15.5): the identical path labels, genuinely different values --
        // proof this is root 1's own data, not root 0's leaking through a name collision.
        canvas.setSelectedRoot(1);
        assertEquals(Set.of("CD45+", "CD45-"), Set.copyOf(canvas.barLabels()));
        assertEquals(5, canvas.barValue("CD45+"));
        assertEquals(15, canvas.barValue("CD45-"));
        int sum1 = canvas.barLabels().stream().mapToInt(canvas::barValue).sum();
        assertEquals(20, sum1, "still exactly the denominator after switching");
        assertEquals(20, canvas.total());
    }
}
