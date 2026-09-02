package qupath.ext.flowpath.analysis.session;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.*;
import qupath.ext.flowpath.testing.AnalysisFixtures;
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
    void denominatorOptionsAreEveryBranchInTheTree() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(null, 0));

        List<String> names = session.denominatorOptions().stream()
                .map(o -> o.branch().getName()).toList();
        assertEquals(List.of("CD45+", "CD45-"), names);
    }

    @Test
    void statsAreComputedAgainstTheChosenDenominator() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(null, 0));

        Branch cd45pos = session.denominatorOptions().get(0).branch();
        PopulationStats withDenominator = session.stats(cd45pos);
        assertFalse(withDenominator.rows().isEmpty());
        assertEquals(5, withDenominator.rows().get(0).denominatorCount());

        assertTrue(Double.isNaN(session.stats(null).rows().get(0).percentOfDenominator()),
                "no denominator chosen leaves that column NaN");
    }

    /**
     * The regression this task fixes, at the session layer: every {@link DenominatorRef}
     * {@link AnalysisSession#denominatorOptions()} hands out must name exactly one row
     * {@link PopulationStats} actually reports — the same guarantee
     * {@code denominatorOptionsAreEveryBranchInTheTree} pins for the plain-{@link Branch}
     * accessor it replaced, restated for the ref that survives a deep copy.
     */
    @Test
    void everyDenominatorOptionMatchesExactlyOneReportedPopulation() {
        AnalysisSession session = new AnalysisSession();
        session.accept(AnalysisFixtures.twoRootsSameChannelInput());
        List<PopulationStats.Row> whole = session.stats(null).rows(PopulationStats.Scope.WHOLE_SLIDE);
        for (AnalysisSession.DenominatorOption option : session.denominatorOptions()) {
            long matches = whole.stream()
                    .filter(r -> r.rootIndex() == option.ref().rootIndex()
                            && r.path().equals(option.ref().path()))
                    .count();
            assertEquals(1, matches,
                    "a denominator the user can pick must name exactly one population: " + option.ref());
        }
    }

    /**
     * The core of the bug: {@link AnalysisSession#resolveDenominator} must re-find a branch
     * across a deep copy of the identical tree, since {@code FlowPathPane.buildAnalysisInput()}
     * hands the session a fresh copy on every gating pass and {@code Branch} carries no
     * value equality of its own.
     */
    @Test
    void aRefResolvesAcrossADeepCopyOfTheSameTree() {
        AnalysisSession session = new AnalysisSession();
        session.accept(AnalysisFixtures.twoRootsSameChannelInput());
        DenominatorRef ref = session.denominatorOptions().get(1).ref();
        session.accept(AnalysisFixtures.twoRootsSameChannelInput());   // fresh Branch objects
        assertNotNull(session.resolveDenominator(ref),
                "the ref outlives the objects it was derived from");
    }

    /**
     * The other half of the same fix: a ref must resolve to {@code null}, not to some
     * unrelated branch, once the population it named is genuinely gone -- distinct from
     * merely being rebuilt by a deep copy (the previous test). Disabling the gate the ref's
     * branch hung from is a hard stop for its whole subtree in {@code GatingEngine.walkNode},
     * so {@code PopulationStats} emits no row for it any more either.
     */
    @Test
    void aRefDoesNotResolveOnceItsGateIsDisabled() {
        AnalysisSession session = new AnalysisSession();
        session.accept(input(null, 0));
        DenominatorRef ref = session.denominatorOptions().get(0).ref();
        assertNotNull(session.resolveDenominator(ref));

        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        root.setEnabled(false);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);
        BranchTally tally = qupath.ext.flowpath.engine.GatingEngine
                .assignAll(tree, index, stats, null, null, 0).getTally();
        session.accept(new AnalysisSession.AnalysisInput(
                tree, index, stats, tally, List.of(), null, "test-image"));

        assertNull(session.resolveDenominator(ref),
                "the gate the ref's branch hung from is now disabled -- no row names it any more");
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

    @Test
    void analysisInputRejectsRegionNamesMismatchedWithTally() {
        BranchTally tally = new BranchTally(2);
        tally.recordCell(0, true);
        tally.recordCell(1, true);
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {{1, 2}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(2));
        GateTree tree = new GateTree();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new AnalysisSession.AnalysisInput(
                        tree, index, stats, tally, List.of("Core"), null, "img"),
                "a tally with 2 regions paired with 1 name describes two different images");
        assertTrue(ex.getMessage().contains("1") && ex.getMessage().contains("2"),
                "the message must name both counts: " + ex.getMessage());
    }

    @Test
    void analysisInputRejectsRegionAreasMismatchedWithTally() {
        BranchTally tally = new BranchTally(2);
        tally.recordCell(0, true);
        tally.recordCell(1, true);
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {{1, 2}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(2));
        GateTree tree = new GateTree();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new AnalysisSession.AnalysisInput(tree, index, stats, tally,
                        List.of("Core", "Margin"), new double[] {1.0, 2.0, 3.0}, "img"),
                "a tally with 2 regions paired with 3 areas describes two different images");
        assertTrue(ex.getMessage().contains("3") && ex.getMessage().contains("2"),
                "the message must name both counts: " + ex.getMessage());
    }

    /**
     * {@link AnalysisSession#denominatorOptions()} exists to stop a user picking a
     * denominator with no row to go with it. That guarantee has two independent parts —
     * the depth-first walk into a branch's children, and the skip of a disabled gate's
     * whole subtree — and a single flat, all-enabled root cannot exercise either.
     */
    @Test
    void denominatorOptionsAreDepthFirstAndSkipDisabledGates() {
        CellIndex index = Cells.of(10)
                .marker("CD45", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                .marker("CD3", 1, 1, 1, 1, 1, 6, 6, 6, 6, 6)
                .area(100.0)
                .build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        // An enabled child gate under the CD45+ branch: its branches must appear, spliced
        // in depth-first right after CD45+ and before CD45-.
        GateNode enabledChild = new GateNode("CD3", 3.5);
        enabledChild.setStatistic(Statistic.MEAN);
        enabledChild.setThresholdIsZScore(false);
        root.getBranches().get(0).getChildren().add(enabledChild);

        // A disabled child gate under the CD45- branch, on a channel ("CD8") that appears
        // nowhere else: if the disabled skip is missing, its branches would show up under
        // unmistakable names.
        GateNode disabledChild = new GateNode("CD8", 3.5);
        disabledChild.setEnabled(false);
        root.getBranches().get(1).getChildren().add(disabledChild);

        // ...and an ENABLED grandchild under that disabled gate, on yet another unique
        // channel. "Disabled" is a hard stop for the whole subtree in GatingEngine.walkNode,
        // not just for the one node, so neither PopulationStats.collect nor
        // AnalysisSession.collectOptions may descend past it -- and those are two separate
        // implementations of the same rule, so both are asserted below. A `continue` that
        // skipped only the node's own branches and still recursed would leave CD19+/CD19-
        // offered as denominators with no row, and no gating pass behind them.
        GateNode enabledGrandchild = new GateNode("CD19", 3.5);
        enabledGrandchild.setStatistic(Statistic.MEAN);
        enabledGrandchild.setThresholdIsZScore(false);
        disabledChild.getBranches().get(0).getChildren().add(enabledGrandchild);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        BranchTally tally = qupath.ext.flowpath.engine.GatingEngine
                .assignAll(tree, index, stats, null, null, 0).getTally();

        AnalysisSession session = new AnalysisSession();
        session.accept(new AnalysisSession.AnalysisInput(
                tree, index, stats, tally, List.of(), null, "test-image"));

        List<String> denominatorNames =
                session.denominatorOptions().stream().map(o -> o.branch().getName()).toList();

        List<String> rowBranchNames = session.stats(null).rows().stream()
                .filter(r -> r.scope() == PopulationStats.Scope.WHOLE_SLIDE)
                .map(PopulationStats.Row::branchName)
                .toList();

        assertEquals(rowBranchNames, denominatorNames,
                "denominator choices must match PopulationStats' own rows exactly, in the "
                        + "same depth-first order -- a denominator with no row is exactly "
                        + "the failure this guards");
        assertEquals(List.of("CD45+", "CD3+", "CD3-", "CD45-"), denominatorNames);
        assertFalse(denominatorNames.stream().anyMatch(n -> n.startsWith("CD8")),
                "a disabled gate's branches must not be offered as a denominator");
        assertFalse(denominatorNames.stream().anyMatch(n -> n.startsWith("CD19")),
                "nor an enabled gate's, when it hangs below a disabled one -- disabled is a "
                        + "hard stop for the whole subtree");
        assertFalse(rowBranchNames.stream().anyMatch(n -> n.startsWith("CD19")),
                "and PopulationStats must stop at the same place, or the two disagree");
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
