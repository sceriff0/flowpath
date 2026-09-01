package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RegionComparisonCanvas}'s reduction: one population, every region, on one shared
 * axis. No pixel assertions -- see {@code ScatterPlotCanvasCoordinateTest}.
 */
class RegionComparisonCanvasTest {

    @Test
    void oneSelectedPopulationIsShownAcrossEveryRegion() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+/CD3+"));

        assertEquals(Set.of("Region 1", "Region 2"), Set.copyOf(canvas.regionLabels()),
                "every region the tally covers, not just the ones where the population is non-empty");
        assertEquals(2, canvas.valueForRegion(0));
        assertEquals(3, canvas.valueForRegion(1));
    }

    @Test
    void switchingThePopulationChangesTheValuesNotTheRegionSet() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        canvas.setSelectedPopulation(new PopulationRef(0, "CD45-"));
        List<String> regionsForCd45Neg = canvas.regionLabels();
        int region1ForCd45Neg = canvas.valueForRegion(0);

        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+/CD3+"));
        assertEquals(Set.copyOf(regionsForCd45Neg), Set.copyOf(canvas.regionLabels()));
        // CD45- (5 in Region 1, cells 0,2,4,6,8) and CD45+/CD3+ (2 in Region 1) disagree.
        assertEquals(5, region1ForCd45Neg);
        assertEquals(2, canvas.valueForRegion(0));
    }

    @Test
    void withNoPopulationSelectedYetItDefaultsToOnePresentInTheRows() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        // No setSelectedPopulation call -- must not throw, and must show *some* population.
        assertEquals(2, canvas.regionLabels().size());
    }

    /**
     * Two un-renamed root gates on one channel emit byte-identical {@code path} values. A
     * path-keyed {@code regionLabels()} therefore emitted every region once per root — four
     * labels for two regions — and {@code valueForRegion}'s {@code findFirst()} then reported
     * the first root's number under both. {@code CLAUDE.md} requires every new per-population
     * computation to be exercised with two enabled roots for exactly this reason.
     * <p>
     * Root 0 ({@code CD45 > 10.5}) puts 5 positives in each region; root 1 ({@code > 15.5})
     * puts 2 in Region 1 and 3 in Region 2.
     */
    @Test
    void twoRootsOnOneChannelStayApart() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRegionRows());

        assertEquals(List.of(
                        new PopulationRef(0, "CD45+"), new PopulationRef(0, "CD45-"),
                        new PopulationRef(1, "CD45+"), new PopulationRef(1, "CD45-")),
                canvas.availablePopulations(),
                "both roots' populations must be selectable, not de-duplicated by path");

        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+"));
        assertEquals(List.of("Region 1", "Region 2"), canvas.regionLabels(),
                "one label per region, not one per matching row");
        assertEquals(5, canvas.valueForRegion(0));
        assertEquals(5, canvas.valueForRegion(1));

        canvas.setSelectedPopulation(new PopulationRef(1, "CD45+"));
        assertEquals(List.of("Region 1", "Region 2"), canvas.regionLabels());
        assertEquals(2, canvas.valueForRegion(0), "root 1's own count, not root 0's 5");
        assertEquals(3, canvas.valueForRegion(1), "root 1's own count, not root 0's 5");
    }

    /**
     * The same identity collision one axis down, and just as reachable: two annotations
     * both classified {@code Tumor} are both <em>named</em> {@code "Tumor"}, because
     * {@code RegionMask} names an unnamed annotation after its classification.
     * <p>
     * This canvas used to draw one bar per distinct name and resolve each with
     * {@code findFirst()}, so both Tumor bars showed the first Tumor region's count and the
     * second region was invisible — not merely mislabelled, but unreadable. Keying on
     * {@link qupath.ext.flowpath.model.PopulationStats.Row#regionIndex()} fixes it, and the
     * fixture's counts are deliberately unequal (5 and 5 positives would have let the old
     * code pass by coincidence).
     */
    @Test
    void twoRegionsSharingOneNameStayApart() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoRegionsSharingOneNameRows());
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+"));

        assertEquals(List.of("Tumor", "Tumor"), canvas.regionLabels(),
                "two regions means two bars, even when they carry the same label");
        assertEquals(2, canvas.regionBars().size(), "one bar per region, not per distinct name");

        assertEquals(5, canvas.valueForRegion(0), "region 0 holds cells 0-14: 5 are CD45+");
        assertEquals(5, canvas.valueForRegion(1), "region 1 holds cells 15-19: all 5 are CD45+");

        // The negatives are where the two regions actually differ, so this is the assertion
        // a name-keyed findFirst() cannot satisfy.
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45-"));
        assertEquals(10, canvas.valueForRegion(0), "region 0's own 10 negatives");
        assertEquals(0, canvas.valueForRegion(1), "region 1 has none, not region 0's 10");
        assertEquals(List.of(10, 0),
                canvas.regionBars().stream().map(r -> r.count()).toList(),
                "the bars are drawn from each region's own row, in region order");
    }

    /**
     * {@code plotData()} must read back {@link RegionComparisonCanvas#regionBars()} — the exact
     * rows {@code draw()} iterates — not a fresh filter/sort. Pinned against
     * {@link #regionLabels()} and {@link #valueForRegion} together, in region order, so a wrong
     * implementation that got the right *set* of numbers but the wrong order (or read a stale
     * selection) still fails.
     */
    @Test
    void plotDataIsExactlyTheBarsInDrawOrder() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+/CD3+"));

        List<PlotDatum> data = canvas.plotData();
        assertEquals(List.of("Region 1", "Region 2"), data.stream().map(PlotDatum::category).toList());
        assertEquals(List.of("CD45+/CD3+", "CD45+/CD3+"), data.stream().map(PlotDatum::series).toList(),
                "series is the selected population's own path");
        assertEquals(List.of(2.0, 3.0), data.stream().map(PlotDatum::value).toList());
    }

    /**
     * {@code RegionMask} names an unnamed annotation after its classification, but a region
     * can still reach here with a blank name; two such regions would be indistinguishable by
     * name alone, which is exactly the collision {@link #twoRegionsSharingOneNameRows()} pins
     * one axis over. {@code regionIndex} — a row's real identity, never guessed — is the
     * fallback, not an empty string or "Unnamed".
     */
    @Test
    void plotDataFallsBackToRegionIndexWhenTheNameIsBlank() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(blankRegionNameRows());
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+"));

        assertEquals(List.of("Region 0", "Tumor"),
                canvas.plotData().stream().map(PlotDatum::category).toList(),
                "a blank name falls back to \"Region \" + regionIndex; a real name is used as-is");
    }

    @Test
    void plotDataIsEmptyWithNoPopulationSelected() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        assertEquals(List.of(), canvas.plotData());
    }

    /** Region 0's name is blank; region 1's is a real, non-blank name. Same population both. */
    private static List<PopulationStats.Row> blankRegionNameRows() {
        return List.of(
                new PopulationStats.Row(PopulationStats.Scope.ANNOTATION_K, null, 0,
                        "CD45+", "CD45+", "CD45", 0, 0,
                        3, 3, 10, 10, 0,
                        30.0, 30.0, Double.NaN, 30.0, 30.0, Double.NaN, Double.NaN),
                new PopulationStats.Row(PopulationStats.Scope.ANNOTATION_K, "Tumor", 1,
                        "CD45+", "CD45+", "CD45", 0, 0,
                        7, 7, 10, 10, 0,
                        70.0, 70.0, Double.NaN, 70.0, 70.0, Double.NaN, Double.NaN));
    }
}
