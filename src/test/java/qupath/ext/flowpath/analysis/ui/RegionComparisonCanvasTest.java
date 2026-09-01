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
        canvas.setSelectedPopulation("CD45+/CD3+");

        assertEquals(Set.of("Region 1", "Region 2"), Set.copyOf(canvas.regionLabels()),
                "every region the tally covers, not just the ones where the population is non-empty");
        assertEquals(2, canvas.valueForRegion("Region 1"));
        assertEquals(3, canvas.valueForRegion("Region 2"));
    }

    @Test
    void switchingThePopulationChangesTheValuesNotTheRegionSet() {
        RegionComparisonCanvas canvas = new RegionComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        canvas.setSelectedPopulation("CD45-");
        List<String> regionsForCd45Neg = canvas.regionLabels();
        int region1ForCd45Neg = canvas.valueForRegion("Region 1");

        canvas.setSelectedPopulation("CD45+/CD3+");
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
}
