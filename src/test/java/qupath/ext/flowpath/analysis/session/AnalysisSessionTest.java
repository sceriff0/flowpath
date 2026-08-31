package qupath.ext.flowpath.analysis.session;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.*;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No JavaFX toolkit is started anywhere in this file. That is the point: the session holds
 * every decision the Analysis window makes, so the window can be a dumb applier -- the same
 * Humble Object split UmapSession and ViewState use.
 */
class AnalysisSessionTest {

    @Test
    void anEmptySessionOffersNothingAndSaysWhy() {
        AnalysisState state = new AnalysisSession().state();

        assertFalse(state.hasData());
        assertFalse(state.canExport());
        assertEquals(0, state.cellCount());
        assertNotNull(state.emptyMessage(), "an empty panel must explain itself");
        assertTrue(state.availableScopes().isEmpty());
    }

    @Test
    void acceptingInputWithoutRegionsOffersWholeSlideOnly() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(null, 0));

        AnalysisState state = session.state();
        assertTrue(state.hasData());
        assertFalse(state.hasRegions());
        assertTrue(state.canExport());
        assertEquals(List.of(PopulationStats.Scope.WHOLE_SLIDE), state.availableScopes(),
                "without annotations there is only one population to report on");
    }

    @Test
    void acceptingInputWithRegionsOffersAllThreeScopes() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(new int[] {0, 0, 0, 0, 0, 1, 1, 1, 1, 1}, 2));

        assertEquals(
                List.of(PopulationStats.Scope.WHOLE_SLIDE,
                        PopulationStats.Scope.ANNOTATION_ALL,
                        PopulationStats.Scope.ANNOTATION_K),
                session.state().availableScopes());
        assertTrue(session.state().hasRegions());
        assertEquals(2, session.state().regionCount());
    }

    @Test
    void denominatorChoicesAreEveryBranchInTheTree() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(null, 0));

        List<String> names = session.denominatorChoices().stream().map(Branch::getName).toList();
        assertEquals(List.of("CD45+", "CD45-"), names);
    }

    @Test
    void statsAreComputedAgainstTheChosenDenominator() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(null, 0));

        Branch cd45pos = session.denominatorChoices().get(0);
        PopulationStats withDenominator = session.stats(cd45pos);
        assertFalse(withDenominator.rows().isEmpty());
        assertEquals(5, withDenominator.rows().get(0).denominatorCount());

        assertTrue(Double.isNaN(session.stats(null).rows().get(0).percentOfDenominator()),
                "no denominator chosen leaves that column NaN");
    }

    /** A new image replaces the previous one wholesale; nothing carries over. */
    @Test
    void acceptingNewInputReplacesTheOld() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(new int[] {0, 0, 0, 0, 0, 1, 1, 1, 1, 1}, 2));
        assertTrue(session.state().hasRegions());

        session.accept(input(null, 0));
        assertFalse(session.state().hasRegions(), "the previous image's regions do not linger");
        assertEquals(List.of(PopulationStats.Scope.WHOLE_SLIDE), session.state().availableScopes());
    }

    @Test
    void clearReturnsToTheEmptyState() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(null, 0));
        session.clear();

        assertFalse(session.state().hasData());
        assertTrue(session.stats(null).rows().isEmpty());
    }

    private static AnalysisSession.AnalysisInput input(int[] regionOf, int regionCount) {
        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        BranchTally tally = qupath.ext.flowpath.engine.GatingEngine
                .assignAll(tree, index, stats, null, regionOf, regionCount).getTally();

        List<String> names = regionCount == 2 ? List.of("Core", "Margin") : List.of();
        return new AnalysisSession.AnalysisInput(
                tree, index, stats, tally, names, null, "test-image");
    }
}
