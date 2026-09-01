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
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+/CD3+"));

        assertEquals(List.of(PopulationStats.Scope.WHOLE_SLIDE, PopulationStats.Scope.ANNOTATION_ALL,
                PopulationStats.Scope.ANNOTATION_K), canvas.scopesPresent());
    }

    @Test
    void annotationKSumsAcrossEveryRegionRatherThanShowingJustOne() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+/CD3+"));

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

        canvas.setSelectedPopulation(new PopulationRef(0, "CD45-"));
        assertEquals(10, canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE));

        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+/CD3-"));
        assertEquals(5, canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE));
    }

    @Test
    void withNoRegionsOnlyWholeSlideIsOffered() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        canvas.setRows(AnalysisFixtures.stats().rows());
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+"));

        assertEquals(List.of(PopulationStats.Scope.WHOLE_SLIDE), canvas.scopesPresent());
        assertTrue(canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE) > 0);
    }

    /**
     * Two un-renamed root gates on one channel emit byte-identical {@code path} values, so a
     * path-keyed reduction adds both roots' cells into one bar — 2x the true count, the same
     * defect {@code CompositionCanvas} was fixed for — and offers only one of the two roots'
     * populations for selection at all. {@code CLAUDE.md} requires every new per-population
     * computation to be exercised with two enabled roots for exactly this reason.
     * <p>
     * The fixture cuts root 0 at 10.5 (10 positive of 20) and root 1 at 15.5 (5 positive),
     * so "root 1's own number" is distinguishable from "root 0's number leaking through a
     * name collision" and from their sum.
     */
    @Test
    void twoRootsOnOneChannelStayApart() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());

        assertEquals(List.of(
                        new PopulationRef(0, "CD45+"), new PopulationRef(0, "CD45-"),
                        new PopulationRef(1, "CD45+"), new PopulationRef(1, "CD45-")),
                canvas.availablePopulations(),
                "both roots' populations must be selectable, not de-duplicated by path");

        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+"));
        assertEquals(10, canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE),
                "root 0's own count -- not 15, which is both roots summed");

        canvas.setSelectedPopulation(new PopulationRef(1, "CD45+"));
        assertEquals(5, canvas.valueForScope(PopulationStats.Scope.WHOLE_SLIDE),
                "root 1's own count -- not root 0's 10, and not their sum");
    }

    /**
     * {@code plotData()} must read back {@link ScopeComparisonCanvas#scopesPresent()} and
     * {@link ScopeComparisonCanvas#valueForScope} — the exact calls {@code draw()} makes to
     * build its own axis and bars — not a fresh scan over the raw rows.
     */
    @Test
    void plotDataIsExactlyTheBarsInDrawOrder() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        canvas.setSelectedPopulation(new PopulationRef(0, "CD45+/CD3+"));

        List<PlotDatum> data = canvas.plotData();
        List<PopulationStats.Scope> scopes = canvas.scopesPresent();
        assertEquals(scopes.size(), data.size());
        for (int i = 0; i < scopes.size(); i++) {
            assertEquals(scopes.get(i).displayName(), data.get(i).category());
            assertEquals("CD45+/CD3+", data.get(i).series());
            assertEquals((double) canvas.valueForScope(scopes.get(i)), data.get(i).value());
        }
    }

    @Test
    void plotDataIsEmptyWithNoPopulationSelected() {
        ScopeComparisonCanvas canvas = new ScopeComparisonCanvas();
        assertEquals(List.of(), canvas.plotData());
    }
}
