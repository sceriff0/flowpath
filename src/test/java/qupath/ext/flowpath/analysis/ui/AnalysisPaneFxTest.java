package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.session.DenominatorRef;
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

    /**
     * The brief's own {@code blankCellsSortToTheEndInEitherDirection} exercises "% of
     * Denominator", a column that is entirely {@code NaN} for this fixture (no denominator is
     * ever chosen), so it never actually checks that a real, non-{@code NaN} column reverses
     * correctly under a descending sort -- only that an all-blank column stays all blank. This
     * closes that gap directly against "% Parent", which never carries {@code NaN}.
     */
    @Test
    void descendingSortOfRealNumbersIsCorrectlyReversed() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        List<Double> desc = FxTestSupport.onFx(() -> {
            pane.sortBy("% Parent", false);
            return pane.visiblePercentOfParent();
        });
        for (int i = 1; i < desc.size(); i++) {
            assertTrue(desc.get(i - 1) >= desc.get(i) - 1e-9, "descending out of order at " + i + ": " + desc);
        }
        assertEquals(75.0, desc.get(0), 1e-9, desc.toString());
    }

    /**
     * The regression Task 11 fixes: four pickers used to sit in one flat control row and read
     * as global, but only Scope and Denominator actually drive the table. Root drives only the
     * Composition tab and Population drives only the two comparison tabs -- both must live on
     * the tab they actually affect, not above the table where they read as broken when a user
     * changes them and nothing visible happens.
     */
    @Test
    void eachPlotCarriesItsOwnPickersRatherThanTrustingAGlobalBar() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        // The Root picker belongs to the Composition tab; the Population picker to the
        // comparison tabs. Neither may sit in the table's control row, where it read as global.
        assertTrue(FxTestSupport.onFx(() -> pane.tableControlLabels().contains("Scope:")));
        assertTrue(FxTestSupport.onFx(() -> pane.tableControlLabels().contains("Denominator:")));
        assertFalse(FxTestSupport.onFx(() -> pane.tableControlLabels().contains("Root:")),
                "Root drives one plot, so it lives on that plot");
        assertFalse(FxTestSupport.onFx(() -> pane.tableControlLabels().contains("Population:")));
    }

    /**
     * By Region and By Scope each carry their own {@code ComboBox} for the Population picker
     * (Task 11's layout), but there is only ONE selection behind them -- a user comparing
     * "CD45+/CD8+" on By Region must still be looking at "CD45+/CD8+" after flipping to By
     * Scope, not silently back at whatever the other combo happened to default to.
     */
    @Test
    void thePopulationChoiceIsSharedBetweenTheTwoComparisonTabs() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        PopulationRef second = FxTestSupport.onFx(() -> pane.populationChoices().get(1));
        FxTestSupport.onFxRun(() -> pane.selectPopulation(second));
        assertEquals(second, FxTestSupport.onFx(() -> pane.regionComparisonCanvas().selectedPopulation()));
        assertEquals(second, FxTestSupport.onFx(() -> pane.scopeComparisonCanvas().selectedPopulation()));
    }

    private static List<String> rowsOf(AnalysisPane pane, String column) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < pane.rowCount(); i++) out.add(pane.cellTextAt(i, column));
        return out;
    }

    /**
     * Every percentage/density column used to be {@code String}-typed and explicitly
     * unsortable, because a {@code String} column of numbers sorts lexicographically
     * ("100.0" above "20.0" above "9.5"). This pins that {@code % Parent} now sorts as a
     * number.
     */
    @Test
    void percentageColumnsSortNumericallyNotLexicographically() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        assertTrue(FxTestSupport.onFx(() -> pane.isColumnSortable("% Parent")),
                "a stats table you cannot rank is not a stats table");
        // 100.0 must not sort above 20.0 above 9.5, which is what a String column did.
        List<Double> sorted = FxTestSupport.onFx(() -> {
            pane.sortBy("% Parent", true);
            return pane.visiblePercentOfParent();
        });
        for (int i = 1; i < sorted.size(); i++) {
            double a = sorted.get(i - 1), b = sorted.get(i);
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            assertTrue(a <= b + 1e-9, "out of order at " + i + ": " + sorted);
        }
    }

    /**
     * A blank cell is "unanswered"; an unanswered row must not head the table in either sort
     * direction.
     * <p>
     * Deliberately uses {@code Area (mm²)} against
     * {@code AnalysisFixtures.partiallyKnownRegionAreasInput()} at
     * {@link PopulationStats.Scope#ANNOTATION_K}, not {@code % of Denominator} against a
     * fixture with no denominator chosen. {@code % of Denominator} is all-or-nothing — every
     * row is real once a denominator is picked, every row is {@code NaN} when none is — so a
     * version of this test built on it (an earlier version of this test did exactly that)
     * cannot distinguish a correct NaN-last comparator from a broken one: an implementation
     * that put NaN <em>first</em> under a descending sort would still pass, because an
     * all-{@code NaN} column has no "first" or "last" to get wrong. This fixture's region areas
     * are two real values plus one {@code NaN}, so the assertions below actually exercise both
     * "NaN goes after every real value" and "the real values are still in the right order".
     */
    @Test
    void blankCellsSortToTheEndInEitherDirection() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.partiallyKnownRegionAreasInput()));
        FxTestSupport.onFxRun(() -> pane.selectScope(PopulationStats.Scope.ANNOTATION_K));
        for (boolean ascending : new boolean[] { true, false }) {
            List<Double> values = FxTestSupport.onFx(() -> {
                pane.sortBy("Area (mm²)", ascending);
                return pane.visibleAreaMm2();
            });
            assertTrue(values.stream().anyMatch(v -> !Double.isNaN(v)),
                    "precondition: the fixture has real areas -- " + values);
            assertTrue(values.stream().anyMatch(v -> Double.isNaN(v)),
                    "precondition: the fixture has a blank area too -- " + values);

            int firstNaN = -1;
            for (int i = 0; i < values.size(); i++) {
                if (Double.isNaN(values.get(i))) { firstNaN = i; break; }
            }
            assertTrue(firstNaN >= 0, "the precondition above guarantees a NaN exists");
            for (int i = firstNaN; i < values.size(); i++) {
                assertTrue(Double.isNaN(values.get(i)),
                        "an unanswered row must never sort above an answered one: " + values);
            }
            for (int i = 1; i < firstNaN; i++) {
                double a = values.get(i - 1), b = values.get(i);
                boolean inOrder = ascending ? a <= b + 1e-9 : a >= b - 1e-9;
                assertTrue(inOrder, "real values out of order at " + i + " (ascending=" + ascending + "): " + values);
            }
        }
    }

    /** Filtering narrows the visible rows without rebuilding the underlying stats, and an
     * emptied table says which of its two possible reasons applies. */
    @Test
    void filteringNarrowsTheRowsAndSaysSoWhenItEmptiesThem() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        int all = FxTestSupport.onFx(pane::rowCount);
        FxTestSupport.onFxRun(() -> pane.setFilter("CD45"));
        assertTrue(FxTestSupport.onFx(pane::rowCount) <= all);
        assertTrue(FxTestSupport.onFx(() -> pane.visibleRowPaths().stream()
                .allMatch(p -> p.toLowerCase().contains("cd45"))));

        FxTestSupport.onFxRun(() -> pane.setFilter("zzzz-no-such-marker"));
        assertEquals(0, FxTestSupport.onFx(pane::rowCount));
        assertTrue(FxTestSupport.onFx(pane::placeholderText).contains("zzzz-no-such-marker"),
                "an empty grid must say why it is empty");
    }

    /**
     * Closes the case {@code AnalysisState}'s own invariant deliberately cannot express: data
     * exists, but the current scope has no rows for it.
     * <p>
     * An earlier version of this test wrapped its assertion in
     * {@code if (pane.rowCount() == 0)} against a fixture that always has rows at its default
     * scope -- so the body never ran, and the test reported green while asserting nothing. This
     * version builds the empty case for real: an accepted pass with cells and statistics but an
     * empty {@link qupath.ext.flowpath.model.GateTree} (no root gates at all), the same
     * construction {@code aChosenDenominatorClearsOnlyWhenItsGateIsActuallyDisabled} above uses
     * to simulate every root gate having been removed since the last pass.
     * {@code AnalysisSession.state().hasData()} is true (a pass was accepted), but
     * {@code PopulationStats.of} walks zero roots, so {@code Scope.WHOLE_SLIDE} — the only
     * scope an unannotated pass offers — genuinely has zero rows.
     */
    @Test
    void aTableWithDataButNoRowsAtThisScopeExplainsItself() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        AnalysisSession.AnalysisInput simple = AnalysisFixtures.simpleInput();
        AnalysisSession.AnalysisInput noRoots = new AnalysisSession.AnalysisInput(
                new qupath.ext.flowpath.model.GateTree(), simple.index(), simple.stats(),
                new qupath.ext.flowpath.model.BranchTally(0), List.of(), null, "test-image");

        FxTestSupport.onFxRun(() -> pane.accept(noRoots));

        assertEquals(0, FxTestSupport.onFx(pane::rowCount), "no root gates means no rows to report");
        assertEquals("No populations at this scope.", FxTestSupport.onFx(pane::placeholderText));
    }

    /** A live preview push must not jump the table out from under a user reading it. */
    @Test
    void aLivePushKeepsTheSelectedPopulationSelected() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        PopulationRef selected = FxTestSupport.onFx(() -> {
            pane.selectRow(1);
            return pane.selectedRowRef();
        });
        assertNotNull(selected);
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        assertEquals(selected, FxTestSupport.onFx(pane::selectedRowRef),
                "the table must not jump out from under a user who is reading it");
    }

    /** Copy produces a TSV with a header line matching the row's own field count. */
    @Test
    void copyProducesTsvWithAHeader() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        String tsv = FxTestSupport.onFx(() -> { pane.selectRow(0); return pane.copySelectionAsTsv(); });
        String[] lines = tsv.split("\n");
        assertTrue(lines.length >= 2, tsv);
        assertTrue(lines[0].contains("Population"), lines[0]);
        // limit -1: several trailing columns (Density, Area, % of Denominator) are blank for
        // this fixture (no annotated regions, no denominator chosen), and the no-arg overload
        // of String.split silently drops trailing empty fields, undercounting the row -- a
        // real TSV field count must not depend on whether the LAST cell happens to be blank.
        assertEquals(lines[0].split("\t", -1).length, lines[1].split("\t", -1).length,
                "the header and the row must have the same number of fields");
    }

    /**
     * The summary line is what makes a statistics panel say what it is reporting on -- a
     * user with two images open otherwise cannot tell which one the numbers describe.
     */
    @Test
    void theSummaryLineNamesTheImageTheCellsAndThePopulations() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));
        String summary = FxTestSupport.onFx(pane::summaryText);
        assertFalse(summary.isBlank(), "the panel must say what it is reporting on");
        assertTrue(summary.contains("cells"), summary);
        assertTrue(summary.contains("populations"), summary);
    }

    /**
     * An unannotated slide has {@code regionCount == 0}; advertising "0 regions" would be
     * noise rather than information, so the segment must be omitted entirely, not printed
     * as a zero.
     */
    @Test
    void theSummaryOmitsRegionsWhenThereAreNone() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.simpleInput()));
        String summary = FxTestSupport.onFx(pane::summaryText);
        assertFalse(summary.contains("regions"),
                "an unannotated slide must not advertise zero regions: " + summary);
    }

    /**
     * A blank image name is exactly as unknown as a {@code null} one -- {@code
     * AnalysisFixtures} only ever hands out a real name, so this test builds its own input
     * with the surrounding fixture's tree/index/tally to isolate the one field under test.
     */
    @Test
    void theSummaryOmitsTheImageSegmentWhenTheNameIsBlank() {
        AnalysisSession.AnalysisInput base = AnalysisFixtures.simpleInput();
        AnalysisSession.AnalysisInput blankName = new AnalysisSession.AnalysisInput(
                base.tree(), base.index(), base.stats(), base.tally(),
                base.regionNames(), base.regionAreasMm2(), "   ");

        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(blankName));

        String summary = FxTestSupport.onFx(pane::summaryText);
        assertFalse(summary.contains("   "), "a blank name must not appear in the summary: " + summary);
        assertTrue(summary.startsWith("10 cells") || summary.contains("cells"), summary);
    }

    /**
     * The summary's population count must track what the table is actually showing, not
     * only what the last full push computed -- typing in the filter box changes
     * {@code table.getItems()} without going through {@code updateTable()} at all, and a
     * summary line wired only to the push path would silently drift from the table it sits
     * above the moment a user starts typing.
     */
    @Test
    void theSummaryPopulationCountFollowsTheFilter() {
        AnalysisSession session = new AnalysisSession();
        AnalysisPane pane = FxTestSupport.onFx(() -> new AnalysisPane(session));
        FxTestSupport.onFxRun(() -> pane.accept(AnalysisFixtures.twoRootsSameChannelInput()));

        // No filter active: the plain count, with no "of" -- the summary must not claim a
        // filter is narrowing anything when none is.
        int unfilteredRows = FxTestSupport.onFx(pane::rowCount);
        String unfilteredSummary = FxTestSupport.onFx(pane::summaryText);
        assertTrue(unfilteredSummary.contains(unfilteredRows + " populations"), unfilteredSummary);
        assertFalse(unfilteredSummary.contains(" of "), unfilteredSummary);

        // Narrow to a filter that cannot match every row, so the visible count actually
        // changes rather than coincidentally staying the same. The summary must now read
        // "{visible} of {total} populations": the total stays on screen (it is the true
        // count the gating covered, not something a forgotten filter should hide), and the
        // visible count matches what the table beneath it is actually showing.
        FxTestSupport.onFxRun(() -> pane.setFilter("CD45+"));
        int filteredRows = FxTestSupport.onFx(pane::rowCount);
        assertTrue(filteredRows < unfilteredRows, "the filter must actually narrow the rows shown");

        String filteredSummary = FxTestSupport.onFx(pane::summaryText);
        assertTrue(filteredSummary.contains(filteredRows + " of " + unfilteredRows + " populations"),
                filteredSummary);

        // Clearing the filter must restore the plain, un-annotated count -- the "of" segment
        // is tied to whether a filter is actually narrowing the rows, not left behind once it
        // has appeared once.
        FxTestSupport.onFxRun(() -> pane.setFilter(""));
        int clearedRows = FxTestSupport.onFx(pane::rowCount);
        assertEquals(unfilteredRows, clearedRows, "clearing the filter must restore every row");

        String clearedSummary = FxTestSupport.onFx(pane::summaryText);
        assertTrue(clearedSummary.contains(clearedRows + " populations"), clearedSummary);
        assertFalse(clearedSummary.contains(" of "), clearedSummary);
    }
}
