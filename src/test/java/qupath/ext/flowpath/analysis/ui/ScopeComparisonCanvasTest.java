package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScopeComparisonCanvas}'s reduction: one population, compared at all three scopes.
 * No pixel assertions -- see {@code ScatterPlotCanvasCoordinateTest}.
 */
class ScopeComparisonCanvasTest {

    @Test
    void allThreeScopesAreOfferedWhenTheImageHasAnnotations() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        canvas.setSelectedPopulation("CD45+/CD3+");

        assertEquals(List.of(PopulationStats.Scope.WHOLE_SLIDE, PopulationStats.Scope.ANNOTATION_ALL,
                PopulationStats.Scope.ANNOTATION_K), canvas.scopesPresent());
    }

    @Test
    void annotationKSumsAcrossEveryRegionRatherThanShowingJustOne() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        canvas.setSelectedPopulation("CD45+/CD3+");

        // Region 1 holds 2, Region 2 holds 3 -- ANNOTATION_K must show their sum, 5, not
        // either region alone, matching WHOLE_SLIDE and ANNOTATION_ALL exactly since these
        // two regions cover every cell.
        assertEquals(5, canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE));
        assertEquals(5, canvas.valueForScope(PopulationStats.Scope.ANNOTATION_ALL));
        assertEquals(5, canvas.valueForScope(PopulationStats.Scope.ANNOTATION_K));
    }

    @Test
    void differentPopulationsReportDifferentCounts() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        canvas.setSelectedPopulation("CD45-");
        assertEquals(10, canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE));

        canvas.setSelectedPopulation("CD45+/CD3-");
        assertEquals(5, canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE));
    }

    @Test
    void withNoRegionsOnlyWholeSlideIsOffered() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        canvas.setRows(AnalysisFixtures.stats().rows());
        canvas.setSelectedPopulation("CD45+");

        assertEquals(List.of(PopulationStats.Scope.WHOLE_SLIDE), canvas.scopesPresent());
        assertTrue(canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE) > 0);
    }
}
