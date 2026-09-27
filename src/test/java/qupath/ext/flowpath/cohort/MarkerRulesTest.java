package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

class MarkerRulesTest {

    /** 1000 cells: CD3+ 0..599, CD8+ 0..299 (+extra), CD20+ 600..799 (+extra on CD3+ cells). */
    static SlideSample slide(String id, IntPredicate extraCd8, IntPredicate extraCd20, IntPredicate cd3Absent) {
        Cells cells = Cells.of(1000)
                .marker("CD3", i -> i < 600 ? 100 : 1).absentOn(cd3Absent)
                .marker("CD8", i -> i < 300 || extraCd8.test(i) ? 100 : 1)
                .marker("CD20", i -> (i >= 600 && i < 800) || extraCd20.test(i) ? 100 : 1)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEAN, i -> i < 600 ? 100 : 1)
                .marker("CD20", Compartment.NUCLEAR, Statistic.MEAN, i -> i >= 600 && i < 800 ? 100 : 1);
        CellIndex index = cells.build();
        boolean[] clean = Cells.allTrue(1000);
        return new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), 1000, "f-" + id);
    }

    static final IntPredicate NONE = i -> false;

    static List<SlideSample> cohort() {
        List<SlideSample> s = new ArrayList<>();
        for (String id : List.of("s1", "s2", "s3", "s4")) s.add(slide(id, NONE, NONE, NONE));
        s.add(slide("bad", i -> i >= 900 && i < 950, NONE, NONE));   // 50 CD8+ cells are CD3-
        s.add(slide("dbl", NONE, i -> i < 40, NONE));                  // 40 CD3+CD20+ cells
        return s;
    }

    static GateNode threshold(String channel, double t) {
        GateNode g = new GateNode(channel, t);
        g.setStatistic(Statistic.MEAN);
        return g;
    }

    /** Roots CD3 (lineage) and CD20 (lineage); CD8 under CD3+. */
    static GateTree tree() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("s1");
        GateNode cd3 = threshold("CD3", 50);
        GateNode cd8 = threshold("CD8", 50);
        GateNode cd20 = threshold("CD20", 50);
        cd3.setLineageMarker(true);
        cd20.setLineageMarker(true);
        cd3.getBranches().get(0).getChildren().add(cd8);
        tree.addRoot(cd3);
        tree.addRoot(cd20);
        return tree;
    }

    /**
     * {@link #tree()} plus a third root on CD3 again — the same channel as root 0 — cut at 500,
     * where every cell is CD3-, with its own CD8 child. Its implies rule reads 100% on every
     * slide, so it is never unusual; root 0's rule, identically labelled, is.
     */
    static GateTree treeWithSameChannelTwin() {
        GateTree tree = tree();
        GateNode twin = threshold("CD3", 500);
        twin.getBranches().get(0).getChildren().add(threshold("CD8", 50));
        tree.addRoot(twin);
        return tree;
    }

    @Test
    void rulesComeFromTheTreeAndTheLineageTicks() {
        GateTree tree = tree();
        GateNode twin = threshold("CD3", 70);
        twin.setLineageMarker(true);
        tree.addRoot(twin);                              // a second CD3 root, also ticked
        List<MarkerRules.Rule> rules = MarkerRules.rulesOf(tree);
        assertEquals(List.of("CD8+ => CD3+", "CD3+ & CD20+", "CD20+ & CD3+"),
                rules.stream().map(MarkerRules.Rule::label).toList(),
                "no exclusive rule between two gates on the same column");
        assertEquals(new MarkerRules.GateRef(0, "CD3+/CD8"), rules.get(0).aRef());
        assertEquals(new MarkerRules.GateRef(0, "CD3"), rules.get(0).bRef());
        assertEquals(new MarkerRules.GateRef(2, "CD3"), rules.get(2).bRef(),
                "the twin is told apart from root 0 by its root index");
    }

    @Test
    void onlyThresholdGatesTakePartInExclusiveRules() {
        GateTree tree = tree();
        QuadrantGate quad = new QuadrantGate("CD4", "CD8", 50, 50);
        quad.setLineageMarker(true);
        tree.addRoot(quad);
        assertEquals(List.of("CD8+ => CD3+", "CD3+ & CD20+"),
                MarkerRules.rulesOf(tree).stream().map(MarkerRules.Rule::label).toList());
    }

    @Test
    void anImpliesViolationPointsAtTheAncestorWithADirection() {
        MarkerRules.Evaluation e = MarkerRules.evaluate(tree(), cohort(), AlignmentLookup.NONE);
        List<MarkerRules.Finding> bad = e.findings().stream().filter(f -> f.slideId().equals("bad")).toList();
        assertEquals(1, bad.size());
        assertEquals("CD3", bad.get(0).gate().getChannel(), "the ancestor, not the child");
        assertEquals(new MarkerRules.GateRef(0, "CD3"), bad.get(0).gateRef());
        assertEquals("14% of CD8+ cells are CD3- here (cohort 0%) — CD3 threshold may be too high", bad.get(0).reason());
    }

    @Test
    void aChildUnderTheNegativeBranchSaysTheThresholdMayBeTooLow() {
        GateTree tree = new GateTree();
        GateNode cd3 = threshold("CD3", 50);
        GateNode cd20 = threshold("CD20", 50);
        cd3.getBranches().get(1).getChildren().add(cd20);  // CD20 under CD3-
        tree.addRoot(cd3);
        tree.addRoot(threshold("CD8", 50));
        List<SlideSample> samples = new ArrayList<>();
        for (String id : List.of("s1", "s2", "s3", "s4")) samples.add(slide(id, NONE, NONE, NONE));
        samples.add(slide("dbl", NONE, i -> i < 40, NONE));  // 40 CD20+ cells are CD3+
        List<MarkerRules.Finding> dbl = MarkerRules.evaluate(tree, samples, AlignmentLookup.NONE).findings();
        assertEquals(1, dbl.size());
        assertEquals("17% of CD20+ cells are CD3+ here (cohort 0%) — CD3 threshold may be too low", dbl.get(0).reason());
    }

    @Test
    void anExclusiveViolationFlagsBothGatesAndHintsAtNucleus() {
        MarkerRules.Evaluation e = MarkerRules.evaluate(tree(), cohort(), AlignmentLookup.NONE);
        List<MarkerRules.Finding> dbl = e.findings().stream().filter(f -> f.slideId().equals("dbl")).toList();
        assertEquals(List.of("CD3", "CD20"), dbl.stream().map(f -> f.gate().getChannel()).toList());
        assertEquals(List.of(new MarkerRules.GateRef(0, "CD3"), new MarkerRules.GateRef(1, "CD20")),
                dbl.stream().map(MarkerRules.Finding::gateRef).toList());
        assertEquals("CD3+CD20+ 5% (cohort 0%) — a threshold may be too low, or signal spills from "
                + "neighbouring cells — try Nucleus", dbl.get(0).reason());
    }

    @Test
    void noNucleusHintWithoutTheNucleusColumn() {
        List<SlideSample> samples = new ArrayList<>();
        for (String id : List.of("s1", "s2", "s3", "s4", "dbl")) {
            IntPredicate extra = id.equals("dbl") ? i -> i < 40 : NONE;
            CellIndex index = Cells.of(1000)
                    .marker("CD3", i -> i < 600 ? 100 : 1)
                    .marker("CD8", i -> i < 300 ? 100 : 1)
                    .marker("CD20", i -> (i >= 600 && i < 800) || extra.test(i) ? 100 : 1)
                    .build();
            boolean[] clean = Cells.allTrue(1000);
            samples.add(new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), 1000, "f-" + id));
        }
        List<MarkerRules.Finding> dbl = MarkerRules.evaluate(tree(), samples, AlignmentLookup.NONE).findings();
        assertEquals(2, dbl.size());
        assertEquals("CD3+CD20+ 5% (cohort 0%) — a threshold may be too low, or signal spills from "
                + "neighbouring cells", dbl.get(0).reason());
    }

    @Test
    void noNucleusHintFromARateOverTooFewCells() {
        // Nucleus columns present, but nothing on them reaches the thresholds set on the Cell column.
        List<SlideSample> samples = new ArrayList<>();
        for (String id : List.of("s1", "s2", "s3", "s4", "dbl")) {
            IntPredicate extra = id.equals("dbl") ? i -> i < 40 : NONE;
            CellIndex index = Cells.of(1000)
                    .marker("CD3", i -> i < 600 ? 100 : 1)
                    .marker("CD8", i -> i < 300 ? 100 : 1)
                    .marker("CD20", i -> (i >= 600 && i < 800) || extra.test(i) ? 100 : 1)
                    .marker("CD3", Compartment.NUCLEAR, Statistic.MEAN, i -> 1)
                    .marker("CD20", Compartment.NUCLEAR, Statistic.MEAN, i -> 1)
                    .build();
            boolean[] clean = Cells.allTrue(1000);
            samples.add(new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), 1000, "f-" + id));
        }
        List<MarkerRules.Finding> dbl = MarkerRules.evaluate(tree(), samples, AlignmentLookup.NONE).findings();
        assertEquals(2, dbl.size());
        assertFalse(dbl.get(0).reason().contains("Nucleus"), dbl.get(0).reason());
    }

    /** {@code n} cells: CD3+ the first 60%, CD20+ the next 20%, plus CD20+ on the first {@code doubles} CD3+ cells. */
    static SlideSample exclusiveSlide(String id, int n, int doubles) {
        int cd3 = n * 6 / 10, cd20 = n * 8 / 10;
        CellIndex index = Cells.of(n)
                .marker("CD3", i -> i < cd3 ? 100 : 1)
                .marker("CD8", i -> 1)
                .marker("CD20", i -> (i >= cd3 && i < cd20) || i < doubles ? 100 : 1)
                .build();
        boolean[] clean = Cells.allTrue(n);
        return new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), n, "f-" + id);
    }

    static List<MarkerRules.RuleRate> flaggedExclusive(int n, int doubles) {
        List<SlideSample> samples = new ArrayList<>();
        for (String id : List.of("s1", "s2", "s3", "s4")) samples.add(exclusiveSlide(id, n, 0));
        samples.add(exclusiveSlide("dbl", n, doubles));
        MarkerRules.Evaluation e = MarkerRules.evaluate(tree(), samples, AlignmentLookup.NONE);
        MarkerRules.RuleRate dbl = e.rates().stream()
                .filter(r -> r.slideId().equals("dbl") && r.rule().kind() == MarkerRules.Kind.EXCLUSIVE)
                .findFirst().orElseThrow();
        assertEquals(doubles, dbl.violations());
        return e.findings().stream().anyMatch(f -> f.slideId().equals("dbl")) ? List.of(dbl) : List.of();
    }

    @Test
    void anUnusualRateNeedsAtLeastTwentyViolatingCells() {
        assertTrue(flaggedExclusive(1000, 19).isEmpty(), "19/800 = 2.4%, far above the cohort, but 19 cells");
        assertEquals(1, flaggedExclusive(1000, 20).size(), "20/800 = 2.5% on 20 cells");
    }

    @Test
    void anUnusualRateNeedsToBeAboveTwoPercent() {
        assertTrue(flaggedExclusive(5000, 80).isEmpty(), "80/4000 = exactly 2%, on 80 cells");
        assertEquals(1, flaggedExclusive(5000, 81).size(), "81/4000 > 2%");
    }

    @Test
    void anExclusivePairWhereOneGateSitsUnderTheOtherIsNotARule() {
        GateTree tree = new GateTree();
        GateNode cd3 = threshold("CD3", 50);
        GateNode cd20 = threshold("CD20", 50);
        cd3.setLineageMarker(true);
        cd20.setLineageMarker(true);
        cd3.getBranches().get(0).getChildren().add(cd20);
        tree.addRoot(cd3);
        GateNode cd8 = threshold("CD8", 50);
        cd8.setLineageMarker(true);
        tree.addRoot(cd8);
        assertEquals(List.of("CD20+ => CD3+", "CD3+ & CD8+", "CD20+ & CD8+"),
                MarkerRules.rulesOf(tree).stream().map(MarkerRules.Rule::label).toList());
    }

    @Test
    void aRegionBranchIsNamedWithItsChannelsAndATwoBranchGateByItsOtherBranch() {
        GateTree tree = new GateTree();
        RectangleGate region = new RectangleGate("CD3", "CD20", 50, 1000, -1000, 1000);
        region.setStatisticX(Statistic.MEAN);
        region.setStatisticY(Statistic.MEAN);
        region.getBranches().get(0).setName("Inside");     // as a loaded gate names them
        region.getBranches().get(1).setName("Outside");
        region.getBranches().get(0).getChildren().add(threshold("CD8", 50));
        tree.addRoot(region);
        GateNode cd3 = threshold("CD3", 50);
        RectangleGate child = new RectangleGate("CD8", "CD20", 50, 1000, -1000, 1000);
        cd3.getBranches().get(0).getChildren().add(child);
        tree.addRoot(cd3);
        assertEquals(List.of("CD8+ => CD3×CD20 Inside", "CD8/CD20 (in) => CD3+"),
                MarkerRules.rulesOf(tree).stream().map(MarkerRules.Rule::label).toList());

        List<MarkerRules.Finding> bad = MarkerRules.evaluate(tree, cohort(), AlignmentLookup.NONE).findings().stream()
                .filter(f -> f.slideId().equals("bad") && f.gateRef().rootIndex() == 0).toList();
        assertEquals(1, bad.size());
        assertEquals("14% of CD8+ cells are CD3×CD20 Outside here (cohort 0%) — check the CD3 vs CD20 gate",
                bad.get(0).reason());
    }

    @Test
    void unmeasuredCellsAreLeftOutNotCountedAsViolations() {
        List<SlideSample> samples = cohort();
        samples.add(slide("gap", NONE, NONE, i -> i < 100));
        MarkerRules.RuleRate gap = MarkerRules.evaluate(tree(), samples, AlignmentLookup.NONE).rates().stream()
                .filter(r -> r.slideId().equals("gap") && r.rule().kind() == MarkerRules.Kind.IMPLIES)
                .findFirst().orElseThrow();
        assertEquals(200, gap.judged(), "CD8+ cells 100..299 only — CD3 is unmeasured on 0..99");
        assertEquals(0, gap.violations());
    }

    @Test
    void aSkippedAncestorJudgesNothingOnThatSlide() {
        GateTree tree = tree();
        tree.getRoots().get(0).setSlideSetting("bad", new SlideSetting.Skip());
        MarkerRules.Evaluation e = MarkerRules.evaluate(tree, cohort(), AlignmentLookup.NONE);
        MarkerRules.RuleRate bad = e.rates().stream()
                .filter(r -> r.slideId().equals("bad") && r.rule().kind() == MarkerRules.Kind.IMPLIES)
                .findFirst().orElseThrow();
        assertEquals(0, bad.judged(), "a skipped gate reads every cell UNMEASURED");
        assertTrue(e.findings().stream().noneMatch(f -> f.slideId().equals("bad")));
    }

    @Test
    void twoSameChannelRootsKeepTheirOwnRatesAndFindings() {
        MarkerRules.Evaluation e = MarkerRules.evaluate(treeWithSameChannelTwin(), cohort(), AlignmentLookup.NONE);
        List<MarkerRules.RuleRate> bad = e.rates().stream()
                .filter(r -> r.slideId().equals("bad") && r.rule().kind() == MarkerRules.Kind.IMPLIES).toList();
        assertEquals(List.of("CD8+ => CD3+", "CD8+ => CD3+"), bad.stream().map(r -> r.rule().label()).toList(),
                "the labels alone cannot tell the two rules apart");
        assertEquals(List.of(new MarkerRules.GateRef(0, "CD3"), new MarkerRules.GateRef(2, "CD3")),
                bad.stream().map(r -> r.rule().bRef()).toList());
        assertEquals(50, bad.get(0).violations());
        assertEquals(350, bad.get(0).judged());
        assertEquals(350, bad.get(1).judged());
        assertEquals(350, bad.get(1).violations(), "every CD8+ cell is CD3- under the 500 cut");
        assertEquals(List.of(new MarkerRules.GateRef(0, "CD3")),
                e.findings().stream().filter(f -> f.slideId().equals("bad")).map(MarkerRules.Finding::gateRef).toList(),
                "only root 0's rule is unusual; the twin reads 100% everywhere");
    }

    @Test
    void ruleFindingsMergeIntoTheReviewListAndAReviewHidesThem() {
        GateTree tree = treeWithSameChannelTwin();
        List<SlideSample> samples = cohort();
        AlignmentModel model = AlignmentModel.build("s1", samples, AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty());
        ReviewScorer.Result result = ReviewScorer.score(tree, samples, model);
        ReviewItem item = result.items().stream()
                .filter(i -> i.key().equals(new ReviewItem.Key("bad", 0, "CD3"))).findFirst().orElseThrow();
        assertTrue(item.flags().contains(ReviewItem.Flag.MARKER_RULE));
        assertTrue(item.reasons().contains("14% of CD8+ cells are CD3- here (cohort 0%) — CD3 threshold may be too high"));
        assertTrue(result.items().stream().noneMatch(i -> i.key().equals(new ReviewItem.Key("bad", 2, "CD3"))
                && i.flags().contains(ReviewItem.Flag.MARKER_RULE)), "the same-channel twin is not flagged");
        assertEquals("marker-rule", ReviewItem.Flag.MARKER_RULE.token());
        assertTrue(result.flagsFor("bad", 0, "CD3").contains("marker-rule"));
        assertFalse(result.rules().rates().isEmpty(), "the rates travel with the review, for qc_summary");

        tree.getRoots().get(0).setSlideSetting("bad", new SlideSetting.Reviewed(item.applied()));
        assertTrue(ReviewScorer.score(tree, samples, model).items().stream()
                .noneMatch(i -> i.key().equals(item.key())));
    }

    @Test
    void theMergedListStaysTopDown() {
        GateTree tree = treeWithSameChannelTwin();
        List<SlideSample> samples = cohort();
        AlignmentModel model = AlignmentModel.build("s1", samples, AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty());
        List<ReviewItem> items = ReviewScorer.score(tree, samples, model).items();
        List<String> slideOrder = samples.stream().map(SlideSample::slideId).toList();
        List<GateNode> gateOrder = qupath.ext.flowpath.model.GateWalk.enabled(tree).stream()
                .map(qupath.ext.flowpath.model.GateWalk.Entry::gate).toList();
        for (int i = 1; i < items.size(); i++) {
            ReviewItem p = items.get(i - 1), c = items.get(i);
            int gp = gateOrder.indexOf(p.gate()), gc = gateOrder.indexOf(c.gate());
            assertTrue(gp < gc || (gp == gc && slideOrder.indexOf(p.key().slideId()) < slideOrder.indexOf(c.key().slideId())),
                    "item " + i + " out of order: " + items.stream().map(ReviewItem::key).toList());
        }
        // The exclusive finding put an item on CD20 (root 1) for "dbl", after root 0's items.
        assertTrue(items.stream().anyMatch(i -> i.key().equals(new ReviewItem.Key("dbl", 1, "CD20"))));
    }
}
