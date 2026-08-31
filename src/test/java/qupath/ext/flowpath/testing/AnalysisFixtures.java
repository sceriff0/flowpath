package qupath.ext.flowpath.testing;

import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.model.Statistic;

import java.util.List;

/**
 * The Analysis window's shared test population, for every test in the suite that needs a
 * gated pass rather than a hand-built {@code GateTree}/{@code BranchTally} pair.
 * <p>
 * Lives here rather than under {@code analysis.ui} (where the task that introduced it was
 * scoped) because a later exporter test in the {@code io} package needs {@link #stats()}
 * too, and a package-private fixture would be invisible to it. {@code CLAUDE.md} already
 * establishes {@code testing/} as the one place a shared fixture lives, for exactly the
 * reason {@link Cells} does: a fixture that only one package can see is a fixture that gets
 * re-invented, slightly differently, the next time a different package needs it.
 */
public final class AnalysisFixtures {

    private AnalysisFixtures() {}

    /**
     * One {@code CD45} threshold gate over 10 cells, no annotated regions: the smallest
     * population that produces more than one row (the positive and negative branches) and
     * more than one denominator choice.
     */
    public static AnalysisSession.AnalysisInput simpleInput() {
        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));

        GateNode root = new GateNode("CD45", 5.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, null, 0).getTally();

        return new AnalysisSession.AnalysisInput(
                tree, index, stats, tally, List.of(), null, "test-image");
    }

    /** {@link PopulationStats} built from {@link #simpleInput()}, with no denominator chosen. */
    public static PopulationStats stats() {
        AnalysisSession.AnalysisInput input = simpleInput();
        return PopulationStats.of(input.tree(), input.tally(), input.regionNames(),
                input.regionAreasMm2(), null);
    }
}
