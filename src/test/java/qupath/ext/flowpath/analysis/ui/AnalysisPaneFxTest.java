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
     * A denominator the user chose that happens to hold zero cells gives every row a
     * percentage with no defined value, not a percentage of zero: 10 cells "out of 0" is not
     * {@code 0.0%} of anything, and rendering it as a plausible zero states something false.
     * The row carries {@link Double#NaN} and the cell renders blank -- the same rendering as
     * "no denominator chosen", which is the honest answer in both cases; the two are
     * distinguishable in the data through {@code Row.denominatorCount()}.
     */
    @Test
    void percentOfDenominatorRendersBlankWhenTheChosenDenominatorHoldsNoCells() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.emptyDenominatorInput()));

        Branch emptyBranch = session.denominatorChoices().stream()
                .filter(b -> "CD45+".equals(b.getName()))
                .findFirst()
                .orElseThrow();
        FxTestSupport.onFxRun(() -> pane.setDenominator(emptyBranch));

        assertEquals("", FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0)));
        assertTrue(Double.isNaN(FxTestSupport.onFx(() -> pane.percentOfDenominatorAt(0))),
                "an empty denominator yields NaN, not a zero that reads as an answer");
    }

    /**
     * {@code AnalysisState.canExport()} shipped as a derived field nothing consumed — there
     * was no Export control in the pane at all, and {@code PopulationStatsExporter} had no
     * production caller. The button is that consumer; this pins that the state actually
     * reaches it in both directions rather than the button simply always being live.
     */
    @Test
    void theExportButtonFollowsCanExport() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        assertFalse(FxTestSupport.onFx(pane::exportEnabled),
                "nothing accepted yet -- there is nothing to write");

        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.simpleInput()));
        assertTrue(FxTestSupport.onFx(pane::exportEnabled),
                "a pass has been accepted, so canExport() is true and the button follows");
    }

    /**
     * Spec §4 asks the denominator dropdown to also offer "all cells". The converter has
     * always been able to render a {@code null} branch as {@code "(none)"}, but nothing ever
     * put a {@code null} in the list -- so the choice was one-way: a user who picked a
     * denominator had no item to pick to get back off it.
     */
    @Test
    void theDenominatorPickerOffersAllCellsAsWellAsEveryBranch() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.simpleInput()));

        List<Branch> offered = FxTestSupport.onFx(pane::denominatorChoices);
        assertNull(offered.get(0), "the first offer is \"all cells\" -- rendered \"(none)\"");
        assertEquals(session.denominatorChoices().size() + 1, offered.size(),
                "every branch, plus the null");

        Branch positive = session.denominatorChoices().get(0);
        FxTestSupport.onFxRun(() -> pane.setDenominator(positive));
        assertEquals("100.0", FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0)));
        FxTestSupport.onFxRun(() -> pane.setDenominator(null));
        assertEquals("", FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0)),
                "choosing \"(none)\" again clears the denominator column");
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

        List<PopulationRef> populations = FxTestSupport.onFx(pane::populationChoices);
        assertTrue(populations.contains(new PopulationRef(0, "CD45+/CD3+")));
        FxTestSupport.onFxRun(() -> pane.selectPopulation(new PopulationRef(0, "CD45+/CD3+")));
        int valueForWholeSlide = FxTestSupport.onFx(() ->
                pane.scopeComparisonCanvas().valueForScope(PopulationStats.Scope.WHOLE_SLIDE));
        assertEquals(5, valueForWholeSlide, "the population picker must reach ScopeComparisonCanvas");
    }
}
