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

    /**
     * A {@code CD45} threshold gate set so far above every value that its positive branch
     * is always empty — the fixture {@code AnalysisPane}'s "a chosen-but-empty denominator
     * renders {@code 0.0}, not blank" test needs. Choosing {@code CD45+} as the denominator
     * gives every row a {@code parentCount} of zero, hitting {@link
     * PopulationStats}'s {@code percent(part, whole)} zero-denominator branch rather than
     * its no-denominator-chosen {@code NaN} branch.
     */
    public static AnalysisSession.AnalysisInput emptyDenominatorInput() {
        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));

        GateNode root = new GateNode("CD45", 100.0);
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

    /**
     * Every row (every scope, every region) of a two-level, two-region population: a
     * {@code CD45} threshold gate at the root, with a {@code CD3} threshold gate hanging off
     * its positive branch only.
     * <p>
     * 20 cells, {@code CD45} values {@code 1..20} cut at {@code 10.5}: cells 0-9 (values
     * 1-10) are {@code CD45-}; cells 10-19 (values 11-20) are {@code CD45+}. {@code CD3} is
     * measured on every cell but only ever gated on the {@code CD45+} ten, with values
     * {@code 1..10} cut at {@code 5.5}: cells 10-14 are {@code CD3-}, cells 15-19 are
     * {@code CD3+}. The ten {@code CD45-} cells never reach the {@code CD3} gate at all —
     * they are the deliberately "ungated" cells {@code MarkerPositivityCanvas} exists to
     * surface, distinct from a real {@code CD3-} call.
     * <p>
     * Cells alternate region by parity ({@code i % 2}), so every branch splits across both
     * regions rather than one region holding a whole branch — {@code CD45+/CD3+} (cells
     * 15-19) lands 2 cells in "Region 1" and 3 in "Region 2", giving
     * {@code RegionComparisonCanvas} and {@code ScopeComparisonCanvas} a population that
     * genuinely differs by scope and by region rather than trivially matching everywhere.
     * <p>
     * Leaf populations at {@link PopulationStats.Scope#WHOLE_SLIDE}: {@code CD45-} (10),
     * {@code CD45+/CD3+} (5), {@code CD45+/CD3-} (5) — {@code CD45+} itself is an internal
     * branch, present in the rows but not a leaf.
     */
    public static List<PopulationStats.Row> twoLevelRows() {
        int n = 20;
        double[] cd45 = new double[n];
        double[] cd3 = new double[n];
        for (int i = 0; i < n; i++) {
            cd45[i] = i + 1;           // 1..20
            cd3[i] = (i % 10) + 1;     // 1..10, repeating -- only cells 10-19 are ever gated on it
        }
        CellIndex index = Cells.columns(List.of("CD45", "CD3"), new double[][] {cd45, cd3}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode cd3Gate = new GateNode("CD3", 5.5);
        cd3Gate.setStatistic(Statistic.MEAN);
        cd3Gate.setThresholdIsZScore(false);

        GateNode cd45Gate = new GateNode("CD45", 10.5);
        cd45Gate.setStatistic(Statistic.MEAN);
        cd45Gate.setThresholdIsZScore(false);
        cd45Gate.setPositiveChildren(List.of(cd3Gate));

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(cd45Gate);

        int[] regionOf = new int[n];
        for (int i = 0; i < n; i++) regionOf[i] = i % 2;
        List<String> regionNames = List.of("Region 1", "Region 2");

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, regionOf, regionNames.size())
                .getTally();

        return PopulationStats.of(tree, tally, regionNames, null, null).rows();
    }

    /**
     * Two independent root gates over the same 20 cells, no annotated regions:
     * {@code twoLevelRows()}'s {@code CD45}/{@code CD3} tree as the first root, plus an
     * unrelated {@code CD19} threshold gate as a second, sibling root.
     * <p>
     * {@code FlowPathPane} exposes "+ Add Root Gate" as a repeatable user action, and
     * parallel independent gating strategies from one starting population are an ordinary
     * FlowJo-style pattern -- a consumer of {@link PopulationStats#rows()} that assumes one
     * root (as {@code CompositionCanvas} once did) silently sums leaves from <em>both</em>
     * roots, double-counting the population.
     * <p>
     * {@code CD19} values {@code 1..20} cut at {@code 10.5}: cells 0-9 are {@code CD19-},
     * cells 10-19 are {@code CD19+} -- 10 and 10, independently of {@code CD45}/{@code CD3}'s
     * own partition of the identical 20 cells.
     */
    public static List<PopulationStats.Row> twoRootRows() {
        AnalysisSession.AnalysisInput input = twoRootInput();
        return PopulationStats.of(input.tree(), input.tally(), input.regionNames(),
                input.regionAreasMm2(), null).rows();
    }

    /** The {@link AnalysisSession.AnalysisInput} {@link #twoRootRows()} is built from. */
    public static AnalysisSession.AnalysisInput twoRootInput() {
        int n = 20;
        double[] cd45 = new double[n];
        double[] cd3 = new double[n];
        double[] cd19 = new double[n];
        for (int i = 0; i < n; i++) {
            cd45[i] = i + 1;
            cd3[i] = (i % 10) + 1;
            cd19[i] = i + 1;
        }
        CellIndex index = Cells.columns(List.of("CD45", "CD3", "CD19"),
                new double[][] {cd45, cd3, cd19}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode cd3Gate = new GateNode("CD3", 5.5);
        cd3Gate.setStatistic(Statistic.MEAN);
        cd3Gate.setThresholdIsZScore(false);

        GateNode cd45Gate = new GateNode("CD45", 10.5);
        cd45Gate.setStatistic(Statistic.MEAN);
        cd45Gate.setThresholdIsZScore(false);
        cd45Gate.setPositiveChildren(List.of(cd3Gate));

        GateNode cd19Gate = new GateNode("CD19", 10.5);
        cd19Gate.setStatistic(Statistic.MEAN);
        cd19Gate.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(cd45Gate);
        tree.addRoot(cd19Gate);

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, null, 0).getTally();

        return new AnalysisSession.AnalysisInput(
                tree, index, stats, tally, List.of(), null, "test-image");
    }

    /**
     * Two independent root gates on the <em>identical</em>, un-renamed channel — the case
     * {@link #twoRootRows()} (CD45/CD19, different channels) does not exercise. Both roots
     * emit byte-identical {@code path} values ({@code "CD45+"}/{@code "CD45-"}), since
     * {@code GateNode}'s default branch names are a pure function of the channel alone — a
     * consumer that partitions on {@code path} or {@code gateChannel} instead of
     * {@code rootIndex} would merge these two roots into one, exactly the residual the
     * 2026-09-01 re-review found.
     * <p>
     * Root 0 is cut at {@code 10.5} (10 positive, 10 negative); root 1 is cut at
     * {@code 15.5} (5 positive, 15 negative) — deliberately different splits, so a test can
     * tell "root 1's own data" apart from "root 0's data leaking through a name collision".
     */
    public static List<PopulationStats.Row> twoRootsSameChannelRows() {
        AnalysisSession.AnalysisInput input = twoRootsSameChannelInput();
        return PopulationStats.of(input.tree(), input.tally(), input.regionNames(),
                input.regionAreasMm2(), null).rows();
    }

    /**
     * {@link #twoRootsSameChannelRows()} over two annotated regions, so the per-region and
     * per-scope plots have something to compare that the whole-slide rows cannot stand in for.
     * <p>
     * Cells alternate region by parity, as in {@link #twoLevelRows()}. Root 0 ({@code > 10.5},
     * cells 10-19) puts 5 of its positives in "Region 1" and 5 in "Region 2"; root 1
     * ({@code > 15.5}, cells 15-19) puts 2 in "Region 1" and 3 in "Region 2". The two roots'
     * {@code CD45+} rows are byte-identical in {@code path} and {@code gateChannel} and differ
     * in every count, which is what a canvas keyed on path alone gets wrong.
     */
    public static List<PopulationStats.Row> twoRootsSameChannelRegionRows() {
        int n = 20;
        double[] cd45 = new double[n];
        for (int i = 0; i < n; i++) cd45[i] = i + 1;
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {cd45}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode rootA = new GateNode("CD45", 10.5);
        rootA.setStatistic(Statistic.MEAN);
        rootA.setThresholdIsZScore(false);

        GateNode rootB = new GateNode("CD45", 15.5);
        rootB.setStatistic(Statistic.MEAN);
        rootB.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(rootA);
        tree.addRoot(rootB);

        int[] regionOf = new int[n];
        for (int i = 0; i < n; i++) regionOf[i] = i % 2;
        List<String> regionNames = List.of("Region 1", "Region 2");

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, regionOf, regionNames.size())
                .getTally();

        return PopulationStats.of(tree, tally, regionNames, null, null).rows();
    }

    /**
     * One root over <b>two regions that share a name</b> — the region-axis twin of
     * {@link #twoRootsSameChannelRows()}.
     * <p>
     * This is not a contrived input. {@code RegionMask.nameOf} falls back to an
     * annotation's <em>classification</em> when it has no name of its own, so drawing two
     * annotations and classifying both {@code Tumor} — the ordinary way a slide gets
     * annotated — produces exactly this: two distinct regions, one name. A consumer keyed
     * on the name collapses them.
     * <p>
     * Both regions are called {@code "Tumor"}. Cells alternate by parity, so region 0 holds
     * the even indices and region 1 the odd ones; with {@code CD45 > 10.5} that is 5
     * positives in region 0 and 5 in region 1, and the two regions' {@code CD45-} counts
     * are 5 and 5 as well. To make a name-keyed reduction fail loudly rather than
     * coincidentally agree, the split is made <b>asymmetric</b>: region 1 takes cells 15-19
     * only, so its counts differ from region 0's.
     */
    public static List<PopulationStats.Row> twoRegionsSharingOneNameRows() {
        int n = 20;
        double[] cd45 = new double[n];
        for (int i = 0; i < n; i++) cd45[i] = i + 1;
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {cd45}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode root = new GateNode("CD45", 10.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        // Region 0: cells 0-14 (5 positive, 10 negative). Region 1: cells 15-19 (all
        // positive). Deliberately unequal, so a reduction that reads region 1's bar off
        // region 0's row is off by 3 rather than accidentally right.
        int[] regionOf = new int[n];
        for (int i = 0; i < n; i++) regionOf[i] = i < 15 ? 0 : 1;
        List<String> regionNames = List.of("Tumor", "Tumor");

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, regionOf, regionNames.size())
                .getTally();

        return PopulationStats.of(tree, tally, regionNames, null, null).rows();
    }

    /** The {@link AnalysisSession.AnalysisInput} {@link #twoRootsSameChannelRows()} is built from. */
    public static AnalysisSession.AnalysisInput twoRootsSameChannelInput() {
        int n = 20;
        double[] cd45 = new double[n];
        for (int i = 0; i < n; i++) cd45[i] = i + 1;
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {cd45}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode rootA = new GateNode("CD45", 10.5);
        rootA.setStatistic(Statistic.MEAN);
        rootA.setThresholdIsZScore(false);

        GateNode rootB = new GateNode("CD45", 15.5);
        rootB.setStatistic(Statistic.MEAN);
        rootB.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(rootA);
        tree.addRoot(rootB);

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, null, 0).getTally();

        return new AnalysisSession.AnalysisInput(
                tree, index, stats, tally, List.of(), null, "test-image");
    }

    /**
     * One root gate over three annotated regions whose areas are only <b>partially</b> known:
     * two real, one {@code NaN} — the way an uncalibrated image, or the implicit whole-image
     * region no single ROI describes, actually reports an area. Exists because every other
     * fixture in this file passes {@code regionAreasMm2} as {@code null}, so {@code Density}
     * and {@code Area} are uniformly {@code NaN} or (at a scope with a real area) uniformly
     * real within any one scope those fixtures can produce — nothing else here can build a
     * numeric column that is a genuine mix of blank and real values, which is exactly what
     * pinning "NaN sorts last, in both directions" needs: an implementation that put NaN
     * <em>first</em> under a descending sort would pass every all-NaN or all-real fixture in
     * this suite unnoticed, because neither kind can tell "first" from "last" among identical
     * values.
     * <p>
     * 30 cells, {@code CD45} cut at {@code 15.5} (15 positive, 15 negative), split evenly
     * across three regions by {@code i % 3}. Region areas are {@code 2.0}, {@code 5.0} and
     * {@code NaN} mm² respectively, so at {@link PopulationStats.Scope#ANNOTATION_K} every
     * branch has one row with a real density, a second row with a <em>different</em> real
     * density, and a third that is blank — enough to check both that the two real values order
     * correctly against each other and that the blank one never outranks either of them.
     */
    public static AnalysisSession.AnalysisInput partiallyKnownRegionAreasInput() {
        int n = 30;
        double[] cd45 = new double[n];
        for (int i = 0; i < n; i++) cd45[i] = i + 1;
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {cd45}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode root = new GateNode("CD45", 15.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        int[] regionOf = new int[n];
        for (int i = 0; i < n; i++) regionOf[i] = i % 3;
        List<String> regionNames = List.of("R0", "R1", "R2");
        double[] regionAreasMm2 = {2.0, 5.0, Double.NaN};

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, regionOf, regionNames.size())
                .getTally();

        return new AnalysisSession.AnalysisInput(
                tree, index, stats, tally, regionNames, regionAreasMm2, "test-image");
    }
}
