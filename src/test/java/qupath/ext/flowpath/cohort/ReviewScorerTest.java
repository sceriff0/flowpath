package qupath.ext.flowpath.cohort;

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
                AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty(), qupath.ext.flowpath.model.cohort.LogScale.LN, java.util.Map.of());
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

    /**
     * A much brighter slide is corrected by its factor rather than flagged "unusual staining" (that
     * check is gone); how far its shift sits from the cohort's is a diagnostic the problem layer reads.
     */
    @Test
    void aBrighterSlideIsCorrectedAndItsShiftIsReportedAsAnOutlier() {
        GateTree tree = tree();
        List<SlideSample> samples = cohort();
        ReviewScorer.Result r = score(tree, samples);
        AlignmentModel model = AlignmentModel.build(tree.getReferenceSlideId(), samples,
                AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty(),
                qupath.ext.flowpath.model.cohort.LogScale.LN, java.util.Map.of());
        String column = AlignmentModel.columnsOf(tree).iterator().next().key();
        assertEquals(1.2, model.alignment("odd", column).logShift(), 0.1, "corrected by about e^1.2");
        assertTrue(model.diagnostics("odd", column).shiftOutlier());
        assertFalse(model.diagnostics("s1", column).shiftOutlier());
    }

    /** A slide with no negative peak on the log scale: ln values exponentially distributed, densest at the edge. */
    static SlideSample flat() {
        Random r = new Random(9);
        double[] flat = new double[3000];
        for (int i = 0; i < flat.length; i++) flat[i] = Math.exp(-Math.log(1 - r.nextDouble()));
        CellIndex index = Cells.of(flat.length).marker("CD3", i -> 1.0).marker("CD8", flat).build();
        return new SlideSample("flat", "flat.tif", index, Cells.allTrue(flat.length),
                MarkerStats.compute(index), flat.length, "f");
    }

    @Test
    void noLandmarkOnlyWhenCorrectionIsOn() {
        List<SlideSample> samples = cohort();
        samples.add(flat());
        GateTree tree = tree();
        ReviewItem item = itemsFor(score(tree, samples), "flat", 0).get(0);
        assertTrue(item.flags().contains(NO_NEGATIVE_PEAK));
        assertTrue(item.reasons().contains("No negative peak found on CD8"));

        tree.getRoots().get(0).setCorrectStaining(false);
        assertTrue(itemsFor(score(tree, samples), "flat", 0).stream().noneMatch(i -> i.flags().contains(NO_NEGATIVE_PEAK)));
    }

    /**
     * Final review M9: with the reference slide not in the project, correction is off for every
     * slide, so "No clear negative peak — not corrected" would single one slide out for nothing.
     */
    @Test
    void noLandmarkIsNotFlaggedWhileTheReferenceIsNotInTheProject() {
        List<SlideSample> samples = new ArrayList<>(cohort().subList(1, 4));
        samples.add(flat());
        GateTree tree = tree();
        assertTrue(samples.stream().noneMatch(s -> s.slideId().equals(tree.getReferenceSlideId())));
        ReviewScorer.Result result = score(tree, samples);
        for (int root = 0; root < 2; root++) {
            assertTrue(itemsFor(result, "flat", root).stream().noneMatch(i -> i.flags().contains(NO_NEGATIVE_PEAK)),
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

    // ---- the labelled problem layer ----

    static final java.util.Set<AlignmentModel.ColumnRef> CD8 = java.util.Set.of(new AlignmentModel.ColumnRef(
            "CD8", qupath.ext.flowpath.model.Compartment.WHOLE_CELL, Statistic.MEAN));

    static GateTree oneRoot(double threshold) {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        GateNode g = new GateNode("CD8", threshold);
        g.setStatistic(Statistic.MEAN);
        tree.addRoot(g);
        return tree;
    }

    static ReviewScorer.Result scoreWith(GateTree tree, List<SlideSample> samples,
                                         java.util.Map<String, java.util.Map<String, Double>> peaks) {
        AlignmentModel model = AlignmentModel.build("ref", samples, CD8, AlignmentModel.Cache.empty(),
                qupath.ext.flowpath.model.cohort.LogScale.LN, peaks);
        return ReviewScorer.score(tree, samples, model);
    }

    static List<SlideSample> typical() {
        return new ArrayList<>(List.of(
                AlignmentModelTest.slide("ref", 1, 1.0), AlignmentModelTest.slide("s1", 2, 1.05),
                AlignmentModelTest.slide("s2", 3, 0.95), AlignmentModelTest.slide("s3", 4, 1.1)));
    }

    static ReviewItem only(ReviewScorer.Result r, String slide) {
        List<ReviewItem> l = itemsFor(r, slide, 0);
        assertEquals(1, l.size(), "items for " + slide + ": " + r.items());
        return l.get(0);
    }

    static final double AT_VALLEY = 400;

    @Test
    void peakLockIsFlaggedWithItsReason() {
        List<SlideSample> s = typical();
        s.add(AlignmentModelTest.sample("dom", AlignmentModelTest.mixture(22, 4.0, 6.0, 0.85, 5000, 1.0)));
        ReviewItem item = only(scoreWith(oneRoot(AT_VALLEY), s, java.util.Map.of()), "dom");
        assertTrue(item.flags().contains(PEAK_LOCK), item.toString());
        assertTrue(item.reasons().stream().anyMatch(t -> t.startsWith("Automatic shift \u00D7")
                && t.contains("but the negative peaks differ by \u00D7")), item.reasons().toString());
    }

    @Test
    void tooFewValuesIsCantJudgeWithItsReason() {
        List<SlideSample> s = typical();
        double[] v = new double[4000];
        Random r = new Random(3);
        for (int i = 0; i < v.length; i++) v[i] = i < 30 ? 50 + 50 * r.nextDouble() : 0.5 * r.nextDouble();
        s.add(AlignmentModelTest.sample("few", v));
        ReviewItem item = only(scoreWith(oneRoot(AT_VALLEY), s, java.util.Map.of()), "few");
        assertTrue(item.flags().contains(CANT_JUDGE));
        assertTrue(item.reasons().contains("Fewer than 50 usable values on this slide or the reference \u2014 not corrected"));
        assertFalse(item.flags().contains(PEAK_LOCK));
        assertFalse(item.flags().contains(NO_NEGATIVE_PEAK));
    }

    @Test
    void aTooFewSlideWithAPickedPeakIsStillCantJudge() {
        List<SlideSample> s = typical();
        double[] v = new double[4000];
        Random r = new Random(3);
        for (int i = 0; i < v.length; i++) v[i] = i < 30 ? 50 + 50 * r.nextDouble() : 0.5 * r.nextDouble();
        s.add(AlignmentModelTest.sample("few", v));
        ReviewItem item = only(scoreWith(oneRoot(AT_VALLEY), s,
                java.util.Map.of("few", java.util.Map.of("CD8", 70.0))), "few");
        assertTrue(item.flags().contains(CANT_JUDGE));
    }

    @Test
    void belowRangeIsFlaggedWithItsReason() {
        List<SlideSample> s = typical();
        s.add(AlignmentModelTest.sample("low", AlignmentModelTest.subOne(3000, 4000)));
        ReviewItem item = only(scoreWith(oneRoot(AT_VALLEY), s, java.util.Map.of()), "low");
        assertTrue(item.flags().contains(BELOW_RANGE), item.toString());
        assertTrue(item.reasons().contains("75% of cells are below 1 and were not used to estimate the shift"),
                item.reasons().toString());
    }

    @Test
    void shiftOutlierIsFlaggedWithItsReason() {
        List<SlideSample> s = typical();
        s.add(AlignmentModelTest.slide("odd", 5, 3.0));
        ReviewItem item = only(scoreWith(oneRoot(AT_VALLEY), s, java.util.Map.of()), "odd");
        assertTrue(item.flags().contains(SHIFT_OUTLIER), item.toString());
        assertTrue(item.reasons().stream().anyMatch(t -> t.startsWith("Shift \u00D7") && t.contains(" vs cohort median \u00D7")));
    }

    @Test
    void otsuDiscordanceIsFlaggedWithItsReason() {
        List<SlideSample> s = typical();
        // Same negative peak, but a far smaller positive population: Otsu cuts elsewhere after correction.
        s.add(AlignmentModelTest.sample("shape", AlignmentModelTest.mixture(51, 4.0, 5.0, 0.60, 5000, 1.0)));
        AlignmentModel model = AlignmentModel.build("ref", s, CD8, AlignmentModel.Cache.empty(),
                qupath.ext.flowpath.model.cohort.LogScale.LN, java.util.Map.of());
        double disc = model.diagnostics("shape", "CD8").otsuDiscordance();
        assertTrue(disc > 0.10, "fixture must discord: " + disc);
        ReviewItem item = only(ReviewScorer.score(oneRoot(AT_VALLEY), s, model), "shape");
        assertTrue(item.flags().contains(OTSU_DISCORDANCE));
        assertTrue(item.reasons().contains(String.format("Otsu thresholds disagree on %d%% of cells after correction",
                Math.round(100 * disc))));
    }

    @Test
    void flagsAreOrderedBySeverity() {
        List<SlideSample> s = typical();
        s.add(AlignmentModelTest.sample("dom", AlignmentModelTest.mixture(22, 4.0, 6.0, 0.85, 5000, 1.0)));
        // A threshold on the reference's negative peak: ON_PEAK as well as PEAK_LOCK.
        ReviewItem item = only(scoreWith(oneRoot(Math.exp(2.0)), s, java.util.Map.of()), "dom");
        List<ReviewItem.Flag> flags = item.flags();
        assertEquals(PEAK_LOCK, flags.get(0), flags.toString());
        for (int i = 1; i < flags.size(); i++) assertTrue(flags.get(i - 1).ordinal() < flags.get(i).ordinal());
        assertTrue(flags.contains(ON_PEAK), flags.toString());
    }

    @Test
    void pickedPeakSilencesPeakLock() {
        List<SlideSample> s = typical();
        s.add(AlignmentModelTest.sample("dom", AlignmentModelTest.mixture(22, 4.0, 6.0, 0.85, 5000, 1.0)));
        double refL1 = AlignmentModel.build("ref", s, CD8, AlignmentModel.Cache.empty(),
                qupath.ext.flowpath.model.cohort.LogScale.LN, java.util.Map.of()).landmarks("ref", "CD8").l1();
        ReviewScorer.Result r = scoreWith(oneRoot(AT_VALLEY), s,
                java.util.Map.of("dom", java.util.Map.of("CD8", Math.exp(refL1))));
        assertTrue(itemsFor(r, "dom", 0).stream().noneMatch(i -> i.flags().contains(PEAK_LOCK)), r.items().toString());
    }

    @Test
    void aPickedPeakSilencesNoNegativePeak() {
        List<SlideSample> s = typical();
        s.add(flat());
        ReviewScorer.Result r = scoreWith(oneRoot(AT_VALLEY), s, java.util.Map.of("flat", java.util.Map.of("CD8", 70.0)));
        assertTrue(itemsFor(r, "flat", 0).stream().noneMatch(i -> i.flags().contains(NO_NEGATIVE_PEAK)));
    }

    @Test
    void referenceSlideGetsNoCorrectionFlags() {
        List<SlideSample> s = typical();
        SlideSample fl = flat();
        s.set(0, new SlideSample("ref", "ref.tif", fl.index(), fl.clean(), fl.stats(), 3000, "f-ref"));
        s.add(AlignmentModelTest.slide("odd", 5, 3.0));
        ReviewScorer.Result r = scoreWith(oneRoot(AT_VALLEY), s, java.util.Map.of());
        assertTrue(r.items().stream().filter(i -> i.key().slideId().equals("ref"))
                .allMatch(i -> i.flags().stream().noneMatch(f -> f != ON_PEAK && f != CANT_JUDGE && f != MARKER_RULE)),
                r.items().toString());
    }

    @Test
    void twoSameChannelRootsEachGetTheirOwnItem() {
        List<SlideSample> s = typical();
        s.add(AlignmentModelTest.slide("odd", 5, 3.0));
        GateTree tree = oneRoot(AT_VALLEY);
        GateNode twin = new GateNode("CD8", AT_VALLEY);
        twin.setStatistic(Statistic.MEAN);
        tree.addRoot(twin);
        ReviewScorer.Result r = scoreWith(tree, s, java.util.Map.of());
        assertEquals(1, itemsFor(r, "odd", 0).size());
        assertEquals(1, itemsFor(r, "odd", 1).size());
        assertTrue(itemsFor(r, "odd", 1).get(0).flags().contains(SHIFT_OUTLIER));
    }

    @Test
    void manifestTokensAreTheNewNames() {
        List<SlideSample> s = typical();
        s.add(AlignmentModelTest.sample("dom", AlignmentModelTest.mixture(22, 4.0, 6.0, 0.85, 5000, 1.0)));
        ReviewScorer.Result r = scoreWith(oneRoot(AT_VALLEY), s, java.util.Map.of());
        assertTrue(java.util.Arrays.asList(r.flagsFor("dom", 0, "CD8").split(";")).contains("peak-lock"));
        assertEquals("peak-lock", PEAK_LOCK.token());
        assertEquals("no-negative-peak", NO_NEGATIVE_PEAK.token());
        assertEquals("otsu-discordance", OTSU_DISCORDANCE.token());
        assertEquals("shift-outlier", SHIFT_OUTLIER.token());
        assertEquals("below-range", BELOW_RANGE.token());
    }

    @Test
    void everyFlagCarriesGlyphLabelAndSource() {
        for (ReviewItem.Flag f : ReviewItem.Flag.values()) {
            assertFalse(f.glyph().isBlank());
            assertFalse(f.label().isBlank());
            assertFalse(f.source().isBlank());
        }
        assertEquals("\u21C6", PEAK_LOCK.glyph());
    }
}
