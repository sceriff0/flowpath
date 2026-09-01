package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
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
        assertEquals(2, canvas.valueForRegion("Region 1"));
        assertEquals(3, canvas.valueForRegion("Region 2"));
    }

    @Test
    void switchingThePopulationChangesTheValuesNotTheRegionSet() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        canvas.setSelectedPopulation(new PopulationRef(0, "CD45-"));
        List<String> regionsForCd45Neg = canvas.regionLabels();
        int region1ForCd45Neg = canvas.valueForRegion("Region 1");

        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+/CD3+"));
        assertEquals(Set.copyOf(regionsForCd45Neg), Set.copyOf(canvas.regionLabels()));
        // CD45- (5 in Region 1, cells 0,2,4,6,8) and CD45+/CD3+ (2 in Region 1) disagree.
        assertEquals(5, region1ForCd45Neg);
        assertEquals(2, canvas.valueForRegion("Region 1"));
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
        assertEquals(5, canvas.valueForRegion("Region 1"));
        assertEquals(5, canvas.valueForRegion("Region 2"));

        canvas.setSelectedPopulation(new PopulationRef(1, "CD45+"));
        assertEquals(List.of("Region 1", "Region 2"), canvas.regionLabels());
        assertEquals(2, canvas.valueForRegion("Region 1"), "root 1's own count, not root 0's 5");
        assertEquals(3, canvas.valueForRegion("Region 2"), "root 1's own count, not root 0's 5");
    }
}
