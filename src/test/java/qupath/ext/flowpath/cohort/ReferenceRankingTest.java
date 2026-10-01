package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceRankingTest {

    static GateTree cd8Tree() {
        GateTree tree = new GateTree();
        GateNode g = new GateNode("CD8", 100);
        g.setStatistic(Statistic.MEAN);
        tree.addRoot(g);
        return tree;
    }

    static Set<AlignmentModel.ColumnRef> cols(GateTree tree) {
        return AlignmentModel.columnsOf(tree);
    }

    /** ref sits between s1 and s2; odd is far away: ref is the medoid. */
    static List<SlideSample> centred() {
        List<SlideSample> s = new ArrayList<>();
        s.add(ReviewScorerTest.slide("ref", 1, 0.0, 3000, true));
        s.add(ReviewScorerTest.slide("s1", 2, 0.1, 3000, true));
        s.add(ReviewScorerTest.slide("s2", 3, -0.1, 3000, true));
        s.add(ReviewScorerTest.slide("odd", 4, 1.5, 3000, true));
        return s;
    }

    static SlideSample noPeak(String id) {
        int n = 3000;
        CellIndex index = Cells.of(n).marker("CD3", i -> 1.0).marker("CD8", i -> Double.NaN).build();
        boolean[] clean = Cells.allTrue(n);
        return new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), n, "f-" + id);
    }

    @Test
    void theCentralSlideIsSuggested() {
        ReferenceRanking.Result r = ReferenceRanking.rank(centred(), cols(cd8Tree()));
        assertEquals("ref", r.suggestedId());
        assertEquals(1, r.position("ref"));
        assertEquals("most central on 1 of 1 gated columns", r.reason());
    }

    @Test
    void aSlideMissingTheColumnIsIneligible() {
        List<SlideSample> s = centred();
        s.add(ReviewScorerTest.slide("nocd8", 5, 0.0, 3000, false));
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(cd8Tree()));
        ReferenceRanking.SlideRank rank = r.rank("nocd8");
        assertFalse(rank.eligible());
        assertEquals(List.of("CD8 not measured"), rank.ineligibleBecause());
        assertEquals(4, r.eligibleCount());
    }

    @Test
    void aSlideWithNoNegativePeakIsIneligibleAndAgreesWithAlignment() {
        List<SlideSample> s = centred();
        s.add(noPeak("flat"));
        GateTree tree = cd8Tree();
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(tree));
        assertEquals(List.of("no negative peak on CD8"), r.rank("flat").ineligibleBecause());
        String key = cols(tree).iterator().next().key();
        // The engine agrees: with "flat" as reference, its own landmarks have no L1 ...
        AlignmentModel asFlat = AlignmentModel.build("flat", s, cols(tree), AlignmentModel.Cache.empty(), qupath.ext.flowpath.model.cohort.LogScale.LN, java.util.Map.of());
        assertFalse(asFlat.referenceLandmarks(key).hasL1());
        // ... and with the suggestion as reference, they do.
        AlignmentModel asSuggested = AlignmentModel.build(r.suggestedId(), s, cols(tree), AlignmentModel.Cache.empty(), qupath.ext.flowpath.model.cohort.LogScale.LN, java.util.Map.of());
        assertTrue(asSuggested.referenceLandmarks(key).hasL1());
    }

    @Test
    void twoEligibleSlidesGiveNoSuggestion() {
        List<SlideSample> s = centred().subList(0, 2);
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(cd8Tree()));
        assertNull(r.suggestedId());
        assertEquals(2, r.eligibleCount());
    }

    @Test
    void noSlideWithANegativePeakNamesTheColumn() {
        List<SlideSample> s = List.of(noPeak("a"), noPeak("b"), noPeak("c"));
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(cd8Tree()));
        assertNull(r.suggestedId());
        assertEquals(List.of("CD8"), r.uncorrectableColumns());
    }

    @Test
    void tiesBreakByNameThenId() {
        // Three identical slides: equal scores; the first name wins.
        List<SlideSample> s = new ArrayList<>();
        for (String id : List.of("c", "a", "b")) s.add(ReviewScorerTest.slide(id, 7, 0.0, 3000, true));
        assertEquals("a", ReferenceRanking.rank(s, cols(cd8Tree())).suggestedId());
    }

    @Test
    void noColumnsNoRanking() {
        assertNull(ReferenceRanking.rank(centred(), Set.of()).suggestedId());
    }

    @Test
    void notesSayWhatTheReferenceLacks() {
        List<SlideSample> s = centred();
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(cd8Tree()));
        // "odd" is eligible but not the medoid: the per-column note names the medoid.
        List<String> notes = r.notesFor("odd", id -> id + ".tif");
        assertTrue(notes.contains("for CD8 the most central slide is ref.tif"), notes.toString());
    }

    /** Bimodal column, like ReviewScorerTest.slide's CD8; {@code positives} is the share of the high mode. */
    static double[] bimodal(long seed, double shift, int n, double positives) {
        Random r = new Random(seed);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++)
            raw[i] = 100 * Math.sinh((r.nextDouble() < positives ? 4.0 : 1.0) + shift + 0.3 * r.nextGaussian());
        return raw;
    }

    static SlideSample sample(String id, String name, CellIndex index) {
        int n = index.getObjects().length;
        boolean[] clean = Cells.allTrue(n);
        return new SlideSample(id, name, index, clean, MarkerStats.compute(index, clean), n, "f-" + id);
    }

    /** Two bimodal gated columns, CD8 and CD4, each with its own shift. */
    static SlideSample twoMarkers(String id, long seed, double shiftCd8, double shiftCd4) {
        int n = 3000;
        return sample(id, id + ".tif", Cells.of(n).marker("CD8", bimodal(seed, shiftCd8, n, 0.3))
                .marker("CD4", bimodal(seed + 100, shiftCd4, n, 0.3)).build());
    }

    static GateTree cd8AndCd4Tree() {
        GateTree tree = new GateTree();
        for (String ch : List.of("CD8", "CD4")) {
            GateNode g = new GateNode(ch, 100);
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        return tree;
    }

    @Test
    void aColumnNoSlideCanBeCorrectedOnIsReportedNotRanked() {
        List<SlideSample> s = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            int n = 3000;
            double shift = new double[]{0.0, 0.1, -0.1, 1.5}[k];
            s.add(sample("s" + k, "s" + k + ".tif", Cells.of(n).marker("CD8", bimodal(k + 1, shift, n, 0.3))
                    .marker("CD4", i -> Double.NaN).build()));
        }
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(cd8AndCd4Tree()));
        assertNotNull(r.suggestedId());
        assertEquals(List.of("CD4"), r.uncorrectableColumns());
        assertEquals(4, r.eligibleCount());
        assertTrue(r.reason().endsWith("of 1 gated columns"), r.reason());
        assertEquals(Set.of("CD8"), r.columnMedoids().keySet());
    }

    @Test
    void aSlideWithFewerPeaksThanMostIsIneligible() {
        List<SlideSample> s = centred();
        int n = 3000;
        // Unimodal negative: an L1 but no positive peak, while the rest of the cohort shows both.
        s.add(sample("neg", "neg.tif", Cells.of(n).marker("CD3", i -> 1.0).marker("CD8", bimodal(9, 0.0, n, 0.0)).build()));
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(cd8Tree()));
        assertEquals(List.of("shows no CD8+ peak; most slides do"), r.rank("neg").ineligibleBecause());
        assertFalse(r.rank("neg").eligible());
    }

    @Test
    void theLabelIsTheFullKeyWhenTwoColumnsShareAChannel() {
        AlignmentModel.ColumnRef mean = new AlignmentModel.ColumnRef("CD8", Compartment.WHOLE_CELL, Statistic.MEAN);
        AlignmentModel.ColumnRef median = new AlignmentModel.ColumnRef("CD8", Compartment.NUCLEAR, Statistic.MEDIAN);
        AlignmentModel.ColumnRef cd3 = new AlignmentModel.ColumnRef("CD3", Compartment.WHOLE_CELL, Statistic.MEAN);
        Set<AlignmentModel.ColumnRef> both = new java.util.LinkedHashSet<>(List.of(mean, median, cd3));
        assertEquals(mean.key(), ReferenceRanking.label(mean, both));
        assertEquals(median.key(), ReferenceRanking.label(median, both));
        assertEquals("CD3", ReferenceRanking.label(cd3, both));
        assertEquals("CD8", ReferenceRanking.label(mean, Set.of(mean, cd3)));
    }

    @Test
    void centralityAndPositionOnTwoColumns() {
        List<SlideSample> s = List.of(
                twoMarkers("ref", 1, 0.0, 0.0), twoMarkers("s1", 2, 0.2, -0.2),
                twoMarkers("s2", 3, -0.2, 0.2), twoMarkers("odd", 4, 1.5, 1.5));
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(cd8AndCd4Tree()));
        assertEquals("ref", r.suggestedId());
        assertEquals(2, r.rank("ref").columnsMostCentral());
        assertEquals("most central on 2 of 2 gated columns", r.reason());
        assertEquals(1, r.position("ref"));
        assertEquals(4, r.position("odd"));
        assertTrue(r.position("s1") > 1 && r.position("s1") < 4);
        assertEquals(0, r.position("unknown"));
        assertFalse(r.heterogeneous());
        assertEquals(List.of("CD8", "CD4"), List.copyOf(r.columnMedoids().keySet()));
    }

    @Test
    void aCohortWhoseColumnsDisagreeOnTheMedoidIsHeterogeneous() {
        // CD8 is most central on b, CD4 on c: two distinct medoids among three eligible slides.
        List<SlideSample> s = List.of(
                twoMarkers("a", 1, -1.0, -1.0), twoMarkers("b", 2, 0.0, 1.0), twoMarkers("c", 3, 1.0, 0.0));
        ReferenceRanking.Result r = ReferenceRanking.rank(s, cols(cd8AndCd4Tree()));
        assertEquals("b", r.columnMedoids().get("CD8"));
        assertEquals("c", r.columnMedoids().get("CD4"));
        assertTrue(r.heterogeneous());
        assertTrue(r.notesFor("b", id -> id).stream().anyMatch(n -> n.contains("heterogeneous")));
    }

    @Test
    void sameNameSlidesBreakTiesById() {
        List<SlideSample> s = new ArrayList<>();
        for (String id : List.of("z9", "a1", "m5")) {
            SlideSample t = ReviewScorerTest.slide(id, 7, 0.0, 3000, true);
            s.add(sample(id, "shared.tif", t.index()));
        }
        assertEquals("a1", ReferenceRanking.rank(s, cols(cd8Tree())).suggestedId());
    }
}
