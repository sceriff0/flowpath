package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static qupath.ext.flowpath.cohort.ReviewItem.Flag.*;

class ReviewScorerTest {

    static final double VALLEY = 100 * Math.sinh(2.5);
    static final double NEG_PEAK = 100 * Math.sinh(1.0);

    static SlideSample slide(String id, long seed, double shift, int n, boolean withCd8) {
        Random r = new Random(seed);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) raw[i] = 100 * Math.sinh((r.nextDouble() < 0.3 ? 4.0 : 1.0) + shift + 0.3 * r.nextGaussian());
        Cells cells = Cells.of(n).marker("CD3", i -> 1.0);
        if (withCd8) cells.marker("CD8", raw);
        CellIndex index = cells.build();
        boolean[] clean = Cells.allTrue(n);
        return new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), n, "f-" + id);
    }

    static List<SlideSample> cohort() {
        List<SlideSample> s = new ArrayList<>();
        s.add(slide("ref", 1, 0.0, 3000, true));
        s.add(slide("s1", 2, 0.05, 3000, true));
        s.add(slide("s2", 3, -0.05, 3000, true));
        s.add(slide("odd", 4, 1.2, 3000, true));
        return s;
    }

    /** Root 0 in the valley, root 1 on the negative peak — two roots on ONE channel. */
    static GateTree tree() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        for (double t : new double[]{VALLEY, NEG_PEAK}) {
            GateNode g = new GateNode("CD8", t);
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        return tree;
    }

    static ReviewScorer.Result score(GateTree tree, List<SlideSample> samples) {
        AlignmentModel model = AlignmentModel.build(tree.getReferenceSlideId(), samples,
                AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty());
        return ReviewScorer.score(tree, samples, model);
    }

    static List<ReviewItem> itemsFor(ReviewScorer.Result r, String slide, int root) {
        return r.items().stream().filter(i -> i.key().slideId().equals(slide) && i.key().rootIndex() == root).toList();
    }

    @Test
    void aThresholdOnAPeakIsFlaggedAndOneInTheValleyIsNot() {
        ReviewScorer.Result r = score(tree(), cohort());
        assertTrue(itemsFor(r, "s1", 0).isEmpty(), "valley cut on a typical slide: nothing to look at");
        ReviewItem onPeak = itemsFor(r, "s1", 1).get(0);
        assertEquals(List.of(ON_PEAK), onPeak.flags());
        assertEquals(List.of("Threshold sits on a peak, not in a valley"), onPeak.reasons());
        assertEquals(new ReviewItem.Key("s1", 1, "CD8"), onPeak.key());
    }

    @Disabled("rewritten in Task 3")
    @Test
    void unusualStainingIsFlaggedWithItsReason() {
        ReviewItem odd = itemsFor(score(tree(), cohort()), "odd", 0).get(0);
        assertTrue(odd.flags().contains(UNUSUAL_STAINING));
        assertTrue(odd.reasons().get(0).endsWith("brighter than typical"), odd.reasons().toString());
    }

    @Disabled("rewritten in Task 3")
    @Test
    void noLandmarkOnlyWhenCorrectionIsOn() {
        List<SlideSample> samples = cohort();
        Random r = new Random(9);
        double[] flat = new double[3000];
        for (int i = 0; i < flat.length; i++) flat[i] = 100 * Math.sinh(-Math.log(1 - r.nextDouble()));
        CellIndex index = Cells.of(flat.length).marker("CD3", i -> 1.0).marker("CD8", flat).build();
        samples.add(new SlideSample("flat", "flat.tif", index, Cells.allTrue(flat.length),
                MarkerStats.compute(index), flat.length, "f"));
        GateTree tree = tree();
        ReviewItem item = itemsFor(score(tree, samples), "flat", 0).get(0);
        assertTrue(item.flags().contains(NO_LANDMARK));
        assertTrue(item.reasons().contains("No clear negative peak — not corrected"));

        tree.getRoots().get(0).setCorrectStaining(false);
        assertTrue(itemsFor(score(tree, samples), "flat", 0).stream().noneMatch(i -> i.flags().contains(NO_LANDMARK)));
    }

    /**
     * Final review M9: with the reference slide not in the project, correction is off for every
     * slide, so "No clear negative peak — not corrected" would single one slide out for nothing.
     */
    @Test
    void noLandmarkIsNotFlaggedWhileTheReferenceIsNotInTheProject() {
        List<SlideSample> samples = new ArrayList<>(cohort().subList(1, 4));
        Random r = new Random(9);
        double[] flat = new double[3000];
        for (int i = 0; i < flat.length; i++) flat[i] = 100 * Math.sinh(-Math.log(1 - r.nextDouble()));
        CellIndex index = Cells.of(flat.length).marker("CD3", i -> 1.0).marker("CD8", flat).build();
        samples.add(new SlideSample("flat", "flat.tif", index, Cells.allTrue(flat.length),
                MarkerStats.compute(index), flat.length, "f"));
        GateTree tree = tree();
        assertTrue(samples.stream().noneMatch(s -> s.slideId().equals(tree.getReferenceSlideId())));
        ReviewScorer.Result result = score(tree, samples);
        for (int root = 0; root < 2; root++) {
            assertTrue(itemsFor(result, "flat", root).stream().noneMatch(i -> i.flags().contains(NO_LANDMARK)),
                    "root " + root);
        }
        assertFalse(itemsFor(result, "s1", 1).isEmpty(), "the other flags still judge the slides");
    }

    @Test
    void tooFewParentCellsCannotBeJudged() {
        List<SlideSample> samples = cohort();
        samples.add(slide("tiny", 5, 0.0, 120, true));
        ReviewItem tiny = itemsFor(score(tree(), samples), "tiny", 0).get(0);
        assertTrue(tiny.flags().contains(CANT_JUDGE));
        assertTrue(tiny.reasons().contains("Only 120 cells reach this gate"));
        assertFalse(tiny.flags().contains(ON_PEAK), "a gate that cannot be judged is not also judged on a peak");
    }

    @Test
    void aReviewHidesTheItemUntilTheAppliedValueMoves() {
        GateTree tree = tree();
        List<SlideSample> samples = cohort();
        ReviewItem item = itemsFor(score(tree, samples), "s1", 1).get(0);
        tree.getRoots().get(1).setSlideSetting("s1", new SlideSetting.Reviewed(item.applied()));
        assertTrue(itemsFor(score(tree, samples), "s1", 1).isEmpty(), "reviewed: hidden");

        tree.getRoots().get(1).setThreshold(NEG_PEAK * 1.02);
        assertFalse(itemsFor(score(tree, samples), "s1", 1).isEmpty(), "the number moved: the review lapses");
    }

    @Test
    void skipAndManualAreAnswers() {
        GateTree tree = tree();
        tree.getRoots().get(1).setSlideSetting("s1", new SlideSetting.Skip());
        tree.getRoots().get(1).setSlideSetting("s2", new SlideSetting.Manual(
                qupath.ext.flowpath.model.GateValues.of(new double[]{VALLEY})));
        ReviewScorer.Result r = score(tree, cohort());
        assertTrue(itemsFor(r, "s1", 1).isEmpty());
        assertTrue(itemsFor(r, "s2", 1).isEmpty());
    }

    /** Review Focus 2. */
    @Test
    void aChannelAbsentOnASlideIsInfoNotAnItem() {
        List<SlideSample> samples = cohort();
        samples.add(slide("nocd8", 6, 0.0, 3000, false));
        ReviewScorer.Result r = score(tree(), samples);
        assertTrue(r.items().stream().noneMatch(i -> i.key().slideId().equals("nocd8")));
        assertEquals(2, r.infos().stream().filter(i -> i.slideId().equals("nocd8")).count(), "one per gate");
        assertEquals("CD8 is not measured on this slide — its cells are unmeasured", r.infos().get(0).message());
    }

    @Test
    void itemsComeTopDownGateByGateAndCarryTheManifestFlags() {
        ReviewScorer.Result r = score(tree(), cohort());
        int lastRoot = -1;
        for (ReviewItem i : r.items()) {
            assertTrue(i.key().rootIndex() >= lastRoot, "gate order is the outer order");
            lastRoot = i.key().rootIndex();
        }
        assertEquals("on-peak", r.flagsFor("s1", 1, "CD8"));
        assertEquals("", r.flagsFor("s1", 0, "CD8"), "root 0 on s1 needs nothing — same channel, different rootIndex");
    }
}
