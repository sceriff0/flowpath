package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.testing.AnalysisFixtures;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisPaneFxTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void anEmptyPaneShowsThePlaceholderRatherThanAnEmptyGrid() {
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(new AnalysisSession()));
        assertNotNull(FxTestSupport.onFx(pane::placeholderText),
                "an empty panel explains itself");
        assertEquals(0, FxTestSupport.onFx(() -> pane.rowCount()));
    }

    @Test
    void acceptingDataFillsTheTable() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.simpleInput()));

        assertTrue(FxTestSupport.onFx(pane::rowCount) > 0);
    }

    /** Changing the denominator changes the numbers, not the row set. */
    @Test
    void changingTheDenominatorKeepsTheSameRows() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.simpleInput()));

        int before = FxTestSupport.onFx(pane::rowCount);
        FxTestSupport.onFxRun(() -> pane.setDenominator(session.denominatorChoices().get(0)));
        assertEquals(before, FxTestSupport.onFx(pane::rowCount));
    }

    /**
     * {@code NaN} (no denominator chosen) must render as a blank cell, never as the text
     * "NaN" -- {@link qupath.ext.flowpath.model.PopulationStats.Row#percentOfDenominator()}'s
     * own javadoc. Pinned here because the pane previously exposed no accessor to read a
     * formatted cell at all, so a future edit collapsing this branch would pass silently.
     */
    @Test
    void percentOfDenominatorRendersBlankWithNoDenominatorChosen() {
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(new AnalysisSession()));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.simpleInput()));

        assertEquals("", FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0)));
    }

    /**
     * A denominator the user actually chose, that happens to hold zero cells, is a real
     * answer -- {@code 0.0} -- and must render differently from "no denominator chosen at
     * all". These are different values of the same field and must not collapse to the same
     * text.
     */
    @Test
    void percentOfDenominatorRendersZeroWhenTheChosenDenominatorHoldsNoCells() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.emptyDenominatorInput()));

        Branch emptyBranch = session.denominatorChoices().stream()
                .filter(b -> "CD45+".equals(b.getName()))
                .findFirst()
                .orElseThrow();
        FxTestSupport.onFxRun(() -> pane.setDenominator(emptyBranch));

        assertEquals("0.0", FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0)));
    }

    /**
     * Before this test, neither {@code RegionComparisonCanvas} nor
     * {@code ScopeComparisonCanvas} was reachable from the pane: both default their
     * selected population to whichever row the tree walk emits first and nothing in
     * {@code AnalysisPane} ever called their {@code setSelectedPopulation}, so those two
     * tabs were locked to an arbitrary population. Likewise {@code CompositionCanvas}'s
     * {@code setSelectedRoot} was never driven by anything in the pane.
     */
    @Test
    void theRootAndPopulationPickersDriveTheComparisonPlots() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootInput()));

        assertEquals(List.of(0, 1), FxTestSupport.onFx(pane::rootChoices));
        FxTestSupport.onFxRun(() -> pane.selectRoot(1));
        assertEquals(Set.of("CD19+", "CD19-"),
                Set.copyOf(FxTestSupport.onFx(() -> pane.compositionCanvas().barLabels())),
                "the root picker must reach CompositionCanvas, not just the pane's own bookkeeping");

        List<String> populations = FxTestSupport.onFx(pane::populationChoices);
        assertTrue(populations.contains("CD45+/CD3+"));
        FxTestSupport.onFxRun(() -> pane.selectPopulation("CD45+/CD3+"));
        int valueForWholeSlide = FxTestSupport.onFx(() ->
                pane.scopeComparisonCanvas().valueForScope(PopulationStats.Scope.WHOLE_SLIDE));
        assertEquals(5, valueForWholeSlide, "the population picker must reach ScopeComparisonCanvas");
    }
}
