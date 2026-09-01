package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
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
        FxTestSupport.onFxRun(() -> pane.setDenominator(session.denominatorOptions().get(0).ref()));
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

        DenominatorRef emptyRef = session.denominatorOptions().stream()
                .filter(o -> "CD45+".equals(o.branch().getName()))
                .map(AnalysisSession.DenominatorOption::ref)
                .findFirst()
                .orElseThrow();
        FxTestSupport.onFxRun(() -> pane.setDenominator(emptyRef));

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

        List<DenominatorRef> offered = FxTestSupport.onFx(pane::denominatorRefChoices);
        assertNull(offered.get(0), "the first offer is \"all cells\" -- rendered \"(none)\"");
        assertEquals(session.denominatorOptions().size() + 1, offered.size(),
                "every branch, plus the null");

        DenominatorRef positive = session.denominatorOptions().get(0).ref();
        FxTestSupport.onFxRun(() -> pane.setDenominator(positive));
        assertEquals("100.0", FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0)));
        FxTestSupport.onFxRun(() -> pane.setDenominator(null));
        assertEquals("", FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0)),
                "choosing \"(none)\" again clears the denominator column");
    }

    /**
     * The regression this task fixes. {@code FlowPathPane.buildAnalysisInput()} deep-copies
     * the gate tree on every push, and {@code GateNode.deepCopy()} mints fresh {@code Branch}
     * objects every time -- so a selection keyed on a {@code Branch} (identity comparison)
     * was never present in the next pass's list and silently went back to "(none)". Every
     * pre-existing test in this file calls {@code accept()} exactly once, which is why none
     * of them caught it; this is the same fixture accepted twice, with a selection made in
     * between.
     */
    @Test
    void aChosenDenominatorSurvivesTheNextGatingPass() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        DenominatorRef chosen = FxTestSupport.onFx(() -> pane.denominatorRefChoices().get(1));
        FxTestSupport.onFxRun(() -> pane.setDenominator(chosen));
        String before = FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0));
        assertNotEquals("", before, "precondition: a denominator produces a percentage");

        // A second, structurally identical pass -- exactly what a live preview push delivers.
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));

        assertEquals(chosen, FxTestSupport.onFx(pane::selectedDenominatorRef),
                "the choice is keyed on a value, not on a Branch object that was just replaced");
        assertEquals(before, FxTestSupport.onFx(() -> pane.formattedPercentOfDenominatorAt(0)),
                "and the column still reports against it");
    }

    /**
     * The distinction the survival test above does not exercise: a chosen denominator must
     * clear when the branch it names is genuinely gone (its gate disabled or removed between
     * passes), not merely rebuilt by a deep copy. Conflating the two would mean either the
     * survival fix regresses into "never clears", or this case regresses back into "always
     * clears" -- both are the same defect from opposite directions.
     */
    @Test
    void aChosenDenominatorClearsOnlyWhenItsGateIsActuallyDisabled() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        AnalysisSession.AnalysisInput simple = AnalysisFixtures.simpleInput();
        FxTestSupport.onFxRun(() -> pane.accept(simple));

        DenominatorRef chosen = session.denominatorOptions().get(0).ref();
        FxTestSupport.onFxRun(() -> pane.setDenominator(chosen));
        assertEquals(chosen, FxTestSupport.onFx(pane::selectedDenominatorRef));

        // Same cells and stats, but an empty tree -- as if every root gate had been
        // removed since the last accepted pass. No gate, no branch, no row: the ref chosen
        // must clear, unlike a mere deep copy of the SAME tree above.
        AnalysisSession.AnalysisInput noRoots = new AnalysisSession.AnalysisInput(
                new qupath.ext.flowpath.model.GateTree(), simple.index(), simple.stats(),
                new qupath.ext.flowpath.model.BranchTally(0), List.of(), null, "test-image");
        FxTestSupport.onFxRun(() -> pane.accept(noRoots));

        assertNull(FxTestSupport.onFx(pane::selectedDenominatorRef),
                "the branch the ref named no longer has a gate at all -- this must clear, "
                        + "unlike the deep-copy case above");
    }

    /**
     * Closes the second, smaller defect the same fix carries: two roots on one channel used
     * to render as two identical {@code "CD45+"}/{@code "CD45-"} entries in the denominator
     * combo, indistinguishable to the user. {@code twoRootInput()} (used by the picker test
     * below) cannot exercise this -- its two roots are on different channels and never
     * collide; only {@code twoRootsSameChannelInput()} does.
     */
    @Test
    void twoRootsOnOneChannelGiveDistinguishableDenominators() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        List<String> labels = FxTestSupport.onFx(() -> pane.denominatorLabels());
        assertEquals(labels.size(), labels.stream().distinct().count(),
                "no two entries read identically: " + labels);
        assertTrue(labels.stream().filter(l -> !l.equals("(none)")).allMatch(l -> l.contains("root")),
                "each names its root: " + labels);
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

    /**
     * The population <em>table</em> must tell two same-channel roots apart, not only the
     * pickers.
     * <p>
     * Every plot and both pickers were fixed to key on {@code rootIndex}, and the CSV
     * exports {@code root_index} — but the table itself rendered {@code Row::path} alone.
     * Two un-renamed roots on one channel emit byte-identical paths, so the primary display
     * showed four rows reading {@code CD45+}, {@code CD45-}, {@code CD45+}, {@code CD45-}
     * with different numbers and nothing to say which was which. The existing picker test
     * above uses {@code twoRootInput()}, whose two roots are on <em>different</em> channels
     * and therefore cannot collide; only {@code twoRootsSameChannelInput()} reproduces it.
     */
    @Test
    void theTableTellsTwoRootsOnOneChannelApart() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));

        assertTrue(FxTestSupport.onFx(pane::columnTitles).contains("Root"),
                "without a Root column the four rows are indistinguishable; columns were "
                        + FxTestSupport.onFx(pane::columnTitles));

        List<String> paths = FxTestSupport.onFx(() -> rowsOf(pane, "Population"));
        List<String> roots = FxTestSupport.onFx(() -> rowsOf(pane, "Root"));
        List<String> counts = FxTestSupport.onFx(() -> rowsOf(pane, "Count"));

        assertEquals(4, paths.size(), "two roots x two branches");
        assertEquals(List.of("CD45+", "CD45-", "CD45+", "CD45-"), paths,
                "the paths really are identical -- that is the whole problem");
        assertEquals(List.of("1", "1", "2", "2"), roots,
                "one-based, matching the root and population pickers");

        // And the pairing is right, not merely present: root 1 splits 10/10, root 2 splits
        // 5/15. If the Root column were derived from anything but the row's own rootIndex,
        // these would not line up.
        assertEquals(List.of("10", "10", "5", "15"), counts);
    }

    private static List<String> rowsOf(AnalysisPane pane, String column) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < pane.rowCount(); i++) out.add(pane.cellTextAt(i, column));
        return out;
    }
}
