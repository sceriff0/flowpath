package qupath.ext.flowpath.ui.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.ColumnDiagnostics;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.cohort.LogScale;
import qupath.ext.flowpath.testing.CohortFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static qupath.ext.flowpath.ui.cohort.CohortGridModel.CellMark.*;

class CohortGridModelTest {

    private static CohortGridModel.Row row(CohortGridModel m, String id) {
        return m.rows().stream().filter(r -> r.slideId().equals(id)).findFirst().orElseThrow();
    }

    private static List<CohortGridModel.CellMark> marks(CohortGridModel.Row r) {
        return r.cells().stream().map(CohortGridModel.Cell::mark).toList();
    }

    /** The grid as the window derives it, with no open slide and no rescore in flight. */
    private static CohortGridModel derive(CohortSession s, GateTree tree, ReviewItem.Key selected, boolean onlyLooks) {
        return CohortGridModel.derive(s, tree, selected, onlyLooks, null, false);
    }

    /** The column key of {@code tree}'s root {@code root}, axis 0, as the alignment model keys it. */
    private static String columnKey(GateTree tree, int root) {
        GateNode g = tree.getRoots().get(root);
        return new AlignmentModel.ColumnRef(g.getChannels().get(0), g.compartmentAt(0), g.statisticAt(0)).key();
    }

    /** Rescores {@code s} against {@code tree} as the background pass would, then adopts it. */
    private static void rescore(CohortSession s, GateTree tree) {
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
    }

    /** {@code s1}'s own detector L1 on root 0's column, picked by hand: a landmark-mode correction. */
    private static CohortSession withPickedPeakOnS1(GateTree tree) {
        CohortSession s = CohortFixtures.sampled(tree);
        String key = columnKey(tree, 0);
        double l1 = s.model().landmarks("s1", key).l1();
        s.setPeaks(Map.of("s1", Map.of(key, s.model().scale().fromLog(l1))));
        rescore(s, tree);
        return s;
    }

    @Test
    void twoRootsOnOneChannelAreTwoColumns() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortGridModel m = derive(CohortFixtures.sampled(tree), tree, null, false);
        assertEquals(List.of("#1 CD8", "#2 CD8"), m.columns().stream().map(CohortGridModel.Column::header).toList());
        assertEquals(List.of(0, 1), m.columns().stream().map(CohortGridModel.Column::rootIndex).toList());
    }

    @Test
    void theReferenceRowIsReferenceAndMarked() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortGridModel m = derive(CohortFixtures.sampled(tree), tree, null, false);
        CohortGridModel.Row ref = row(m, "ref");
        assertTrue(ref.reference());
        // Root 1 sits on the negative peak, so the fixture flags it on ref too (brief-sanctioned fallback).
        assertEquals(REFERENCE, marks(ref).get(0));
        assertEquals(LOOK, marks(ref).get(1), "the fixture flags root 1 on its negative peak");
        assertFalse(ref.canExclude(), "the reference cannot be excluded (Review Focus 1)");
    }

    @Test
    void aFlaggedCellIsLookAndCountsTowardsTheRow() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        ReviewItem item = s.review().items().get(0);
        CohortGridModel m = derive(s, tree, null, false);
        CohortGridModel.Row r = row(m, item.key().slideId());
        assertEquals(LOOK, marks(r).get(item.key().rootIndex()));
        assertTrue(r.lookCount() >= 1);
    }

    @Test
    void slideSettingsWinOverAlignment() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        tree.getRoots().get(0).setSlideSetting("s2", new SlideSetting.Skip());
        tree.getRoots().get(1).setSlideSetting("s2", new SlideSetting.Manual(GateValues.of(new double[]{123})));
        CohortGridModel m = derive(s, tree, null, false);
        assertEquals(List.of(SKIPPED, ADJUSTED), marks(row(m, "s2")));
    }

    @Test
    void noReferenceMeansNotCorrectedEverywhereAndASuggestion() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        tree.setReferenceSlideId(null);
        CohortSession s = CohortFixtures.sampled(tree);
        CohortGridModel m = derive(s, tree, null, false);
        assertTrue(m.rows().stream().allMatch(r -> marks(r).stream().allMatch(k -> k == NOT_CORRECTED)));
        assertTrue(m.banner().headline().startsWith("No reference slide"));
        assertEquals(s.suggestedReferenceId(), m.banner().suggestedId());
        assertTrue(m.banner().notes().contains(CohortGridModel.SCOPE_NOTE));
    }

    @Test
    void excludedRowsAreGreyAndCarryNoMarks() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        s.setExcluded(Set.of("odd"));
        CohortGridModel m = derive(s, tree, null, false);
        CohortGridModel.Row odd = row(m, "odd");
        assertEquals(CohortGridModel.RowStatus.EXCLUDED, odd.status());
        assertEquals(List.of(NONE, NONE), marks(odd));
        assertFalse(odd.canBeReference());
    }

    @Test
    void onlyLooksKeepsRowsWithSomethingToReview() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        CohortGridModel m = derive(s, tree, null, true);
        assertFalse(m.rows().isEmpty());
        assertTrue(m.rows().stream().allMatch(r -> r.lookCount() > 0));
    }

    // --- Task 7: explicit cells, legend, row order, labelled detail ---

    @Test
    void everyMarkHasItsText() {
        List<ReviewItem.Flag> onPeak = List.of(ReviewItem.Flag.ON_PEAK);
        Object[][] table = {
                {LOOK, onPeak, Double.NaN, "\u22C0"},
                {OK, List.of(), 1.4, "×1.40"},
                {MANUAL_PEAK, List.of(), 1.4, "◆×1.40"},
                {REFERENCE, List.of(), Double.NaN, "★"},
                {REVIEWED, List.of(), Double.NaN, "☑"},
                {ADJUSTED, List.of(), Double.NaN, "✎"},
                {SKIPPED, List.of(), Double.NaN, "⊘"},
                {NOT_MEASURED, List.of(), Double.NaN, "n/a"},
                {NOT_CORRECTED, List.of(), Double.NaN, "raw"},
                {NONE, List.of(), Double.NaN, ""},
        };
        assertEquals(CohortGridModel.CellMark.values().length, table.length, "one row per mark");
        for (Object[] r : table) {
            @SuppressWarnings("unchecked") List<ReviewItem.Flag> flags = (List<ReviewItem.Flag>) r[1];
            assertEquals(r[3], CohortGridModel.cellText((CohortGridModel.CellMark) r[0], flags, (double) r[2]),
                    r[0].toString());
        }

        // The same texts reach the derived grid.
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortGridModel m = derive(CohortFixtures.sampled(tree), tree, null, false);
        assertEquals("★", row(m, "ref").cells().get(0).text());
        CohortGridModel.Cell odd0 = row(m, "odd").cells().get(0);
        if (odd0.mark() == OK) assertTrue(odd0.text().matches("×\\d+\\.\\d\\d"), odd0.text());
        CohortGridModel.Cell picked = row(derive(withPickedPeakOnS1(tree), tree, null, false), "s1").cells().get(0);
        assertEquals(MANUAL_PEAK, picked.mark());
        assertTrue(picked.text().matches("◆×\\d+\\.\\d\\d"), picked.text());
        CohortGridModel none = derive(CohortFixtures.sampledWithout(tree, "s2", "CD8"), tree, null, false);
        assertEquals(List.of("n/a", "n/a"), row(none, "s2").cells().stream().map(CohortGridModel.Cell::text).toList());
        GateTree off = CohortFixtures.twoCd8Roots();
        off.setReferenceSlideId(null);
        CohortGridModel raw = derive(CohortFixtures.sampled(off), off, null, false);
        assertEquals("raw", row(raw, "s1").cells().get(0).text());
    }

    @Test
    void lookCellShowsMostSeriousGlyphAndCount() {
        assertEquals("⇆+1", CohortGridModel.cellText(LOOK,
                List.of(ReviewItem.Flag.PEAK_LOCK, ReviewItem.Flag.ON_PEAK), Double.NaN));
        assertEquals("#+2", CohortGridModel.cellText(LOOK, List.of(ReviewItem.Flag.CANT_JUDGE,
                ReviewItem.Flag.MARKER_RULE, ReviewItem.Flag.ON_PEAK), Double.NaN));

        // Every LOOK cell of the derived grid carries its item's flags, the most serious first.
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        CohortGridModel m = derive(s, tree, null, false);
        int looks = 0;
        for (ReviewItem item : s.review().items()) {
            CohortGridModel.Cell c = row(m, item.key().slideId()).cells().get(item.key().rootIndex());
            assertEquals(LOOK, c.mark());
            assertEquals(item.flags(), c.flags());
            String expected = item.flags().get(0).glyph() + (item.flags().size() > 1 ? "+" + (item.flags().size() - 1) : "");
            assertEquals(expected, c.text());
            looks++;
        }
        assertTrue(looks > 0, "fixture check");
    }

    @Test
    void legendListsOnlyGlyphsOnScreenInOrder() {
        List<String> order = new ArrayList<>();
        for (ReviewItem.Flag f : ReviewItem.Flag.values()) order.add(f.glyph());
        order.addAll(List.of("×", "◆", "★", "☑", "✎", "⊘", "n/a", "raw"));

        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        CohortGridModel m = derive(s, tree, null, false);
        assertEquals(onScreen(order, m), m.legend().stream().map(CohortGridModel.LegendEntry::glyph).toList());
        assertFalse(m.legend().stream().anyMatch(e -> e.glyph().equals("⊘")), "nothing skipped yet");
        CohortGridModel.LegendEntry star = m.legend().stream().filter(e -> e.glyph().equals("★")).findFirst().orElseThrow();
        assertEquals("the reference row", star.label());
        for (CohortGridModel.LegendEntry e : m.legend()) {
            for (ReviewItem.Flag f : ReviewItem.Flag.values()) {
                if (f.glyph().equals(e.glyph())) assertEquals(f.label(), e.label());
            }
        }

        tree.getRoots().get(0).setSlideSetting("s2", new SlideSetting.Skip());
        CohortGridModel skipped = derive(s, tree, null, false);
        assertEquals(onScreen(order, skipped), skipped.legend().stream().map(CohortGridModel.LegendEntry::glyph).toList());
        assertTrue(skipped.legend().stream().anyMatch(e -> e.glyph().equals("⊘") && e.label().startsWith("skipped")));

        // Only the visible rows count: with "only looks", a row that has none is gone, and so is what only it showed.
        CohortGridModel looks = derive(s, tree, null, true);
        assertEquals(onScreen(order, looks), looks.legend().stream().map(CohortGridModel.LegendEntry::glyph).toList());
    }

    private static List<String> onScreen(List<String> order, CohortGridModel m) {
        List<String> texts = m.rows().stream().flatMap(r -> r.cells().stream()).map(CohortGridModel.Cell::text)
                .filter(t -> !t.isEmpty()).toList();
        return order.stream().filter(g -> texts.stream().anyMatch(t -> t.startsWith(g))).toList();
    }

    @Test
    void referenceRowIsFirstAndOpenSlideIsMarked() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        tree.setReferenceSlideId("s2");
        rescore(s, tree);
        CohortGridModel m = CohortGridModel.derive(s, tree, null, false, "odd", false);
        assertEquals(List.of("s2", "ref", "s1", "odd"), m.rows().stream().map(CohortGridModel.Row::slideId).toList(),
                "the reference first, then project order");
        assertTrue(m.rows().get(0).reference());
        assertEquals(List.of(false, false, false, true), m.rows().stream().map(CohortGridModel.Row::open).toList());
        assertTrue(derive(s, tree, null, false).rows().stream().noneMatch(CohortGridModel.Row::open));
        assertEquals(s.sample("odd").detectionCount(), row(m, "odd").cellCount());
    }

    @Test
    void tooltipNamesEachFlagSource() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        CohortGridModel m = derive(s, tree, null, false);
        int looks = 0;
        for (CohortGridModel.Row r : m.rows()) {
            for (CohortGridModel.Cell c : r.cells()) {
                if (c.mark() != LOOK) continue;
                looks++;
                String[] lines = c.tooltip().split("\n");
                assertEquals("Needs a look", lines[0]);
                for (ReviewItem.Flag f : c.flags()) {
                    assertTrue(c.tooltip().contains(f.glyph() + " " + f.label() + " — "), c.tooltip());
                    assertTrue(c.tooltip().contains("(" + f.source() + ")"), c.tooltip());
                }
            }
        }
        assertTrue(looks > 0, "fixture check");
        // The flag's own reason, not another's.
        assertTrue(row(m, "s1").cells().get(1).tooltip().contains(
                "⋀ Threshold sits on a peak — Threshold sits on a peak, not in a valley (FlowPath heuristic)"),
                row(m, "s1").cells().get(1).tooltip());

        CohortGridModel picked = derive(withPickedPeakOnS1(tree), tree, null, false);
        String tip = row(picked, "s1").cells().get(0).tooltip();
        assertTrue(tip.startsWith("Corrected from a hand-picked peak"), tip);
        assertTrue(tip.matches("(?s).*Factor ×\\d+\\.\\d\\d \\(from picked peak\\).*"), tip);
        String refTip = row(m, "ref").cells().get(0).tooltip();
        assertEquals("The reference row", refTip);
        for (CohortGridModel.Row r : m.rows()) {
            CohortGridModel.Cell c = r.cells().get(0);
            if (c.mark() == OK) assertTrue(c.tooltip().contains("(automatic)"), c.tooltip());
        }
    }

    @Test
    void detailLinesAreLabelled() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        String path = GateWalk.enabled(tree).get(0).gatePath();
        CohortGridModel.Detail d = derive(s, tree, new ReviewItem.Key("odd", 0, path), false).detail();
        assertTrue(d.valuesLine().startsWith("reference "), d.valuesLine());
        assertTrue(d.valuesLine().contains(" → this slide "), d.valuesLine());
        assertTrue(d.correctionLine().matches("×\\d+\\.\\d\\d · automatic \\(UniFORM\\)"), d.correctionLine());
        assertTrue(d.usageLine().matches("[\\d,]+ of [\\d,]+ cells used.*"), d.usageLine());
        assertTrue(d.canPickPeak());
        assertFalse(d.hasPickedPeak());
        CohortGridModel.HistogramView h = d.histogram();
        assertNotNull(h);
        assertTrue(h.gridMin() < h.gridMax());
        assertNotNull(h.reference());
        assertNotNull(h.slide());
        assertEquals(h.reference().length, h.slide().length);
        assertEquals(s.model().scale(), h.scale());
        assertEquals(s.model().referencePeak(columnKey(tree, 0)), h.referenceL1(), 1e-12);
        assertTrue(Double.isNaN(h.pickedSlidePeak()));
        assertTrue(Double.isNaN(h.pickedReferencePeak()));
        assertTrue(Double.isFinite(h.referenceThreshold()));
        assertNotEquals(h.referenceThreshold(), h.appliedThreshold(), "odd is shifted");

        CohortGridModel.Detail ref = derive(s, tree, new ReviewItem.Key("ref", 0, path), false).detail();
        assertFalse(ref.canPickPeak(), "the reference is not picked against itself");
        assertEquals("reference slide — its thresholds are the ones you draw", ref.correctionLine());
        assertFalse(d.region(), "a threshold gate is not a region");

        CohortSession p = withPickedPeakOnS1(tree);
        CohortGridModel.Detail picked = derive(p, tree, new ReviewItem.Key("s1", 0, path), false).detail();
        assertEquals(MANUAL_PEAK, picked.mark());
        assertTrue(picked.hasPickedPeak());
        assertTrue(picked.correctionLine().matches("×\\d+\\.\\d\\d · from your picked peak \\(UniFORM landmark mode\\)"),
                picked.correctionLine());
        assertTrue(Double.isFinite(picked.histogram().pickedSlidePeak()));

        GateNode off = tree.getRoots().get(0);
        off.setCorrectStaining(false);
        CohortGridModel.Detail raw = derive(s, tree, new ReviewItem.Key("odd", 0, path), false).detail();
        assertFalse(raw.canPickPeak(), "Correct staining off");
        assertEquals("not corrected", raw.correctionLine());
    }

    /** Review I1: a cut on a [0, 1] or pre-standardised column keeps its digits; no scientific notation. */
    @Test
    void valuesKeepFourSignificantDigits() {
        Object[][] table = {
                {0.00412, "0.00412"}, {0.5, "0.5"}, {0.982, "0.982"}, {12.345, "12.35"}, {99.996, "100"},
                {1234.5, "1235"}, {123456.7, "123457"}, {-0.03141, "-0.03141"}, {-2.5, "-2.5"}, {0.0, "0"},
        };
        for (Object[] r : table) assertEquals(r[1], CohortGridModel.number((double) r[0]), String.valueOf(r[0]));
    }

    @Test
    void theUsageLineCountsCellsOutsideTheScale() {
        ColumnDiagnostics d = new ColumnDiagnostics(9458, 412, 412 / 9870.0, false, false, Double.NaN, Double.NaN,
                false, Double.NaN, false);
        assertEquals("9,458 of 9,870 cells used · 412 below 1 not used to estimate the shift; corrected like the rest",
                CohortGridModel.usageLine(d, LogScale.LN));
        assertEquals("9,458 of 9,870 cells used · 412 below 0 not used to estimate the shift; corrected like the rest",
                CohortGridModel.usageLine(d, LogScale.LN1P));
        ColumnDiagnostics all = new ColumnDiagnostics(9870, 0, 0.0, false, false, Double.NaN, Double.NaN,
                false, Double.NaN, false);
        assertEquals("9,870 of 9,870 cells used", CohortGridModel.usageLine(all, LogScale.LN));
        assertEquals("", CohortGridModel.usageLine(null, LogScale.LN));
    }

    @Test
    void twoSameChannelRootsGetSeparateColumnsAndCells() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        ReviewItem.Key key = new ReviewItem.Key("s1", 1, GateWalk.enabled(tree).get(1).gatePath());
        CohortGridModel m = derive(s, tree, key, false);
        assertEquals(List.of("#1 CD8", "#2 CD8"), m.columns().stream().map(CohortGridModel.Column::header).toList());
        for (CohortGridModel.Row r : m.rows()) assertEquals(2, r.cells().size(), r.slideId());
        CohortGridModel.Row s1 = row(m, "s1");
        assertNotEquals(LOOK, s1.cells().get(0).mark(), "root 0 cuts in the valley");
        assertEquals(LOOK, s1.cells().get(1).mark(), "root 1 sits on the negative peak");
        assertEquals(1, s1.selectedColumn());
        assertEquals(1, m.detail().key().rootIndex());
        assertEquals(LOOK, m.detail().mark());
    }

    @Test
    void rescoringAddsToHeadline() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        String plain = CohortGridModel.derive(s, tree, null, false, null, false).banner().headline();
        assertFalse(plain.endsWith("re-aligning…"));
        assertEquals(plain + " · re-aligning…", CohortGridModel.derive(s, tree, null, false, null, true).banner().headline());
    }

    @Test
    void selectionSurvivesTwoPasses() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        ReviewItem.Key key = s.review().items().get(0).key();
        GateTree pass1 = tree.deepCopy();
        GateTree pass2 = pass1.deepCopy();
        CohortGridModel m1 = derive(s, pass1, key, false);
        CohortGridModel m2 = derive(s, pass2, key, false);
        assertEquals(key, m1.detail().key());
        assertEquals(key, m2.detail().key());
        assertEquals(m1.detail().title(), m2.detail().title());
    }

    @Test
    void detailShowsReferenceAndAppliedValues() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        String path = GateWalk.enabled(tree).get(0).gatePath();
        ReviewItem.Key key = new ReviewItem.Key("odd", 0, path);
        CohortGridModel m = derive(s, tree, key, false);
        assertNotNull(m.detail());
        String[] sides = m.detail().valuesLine().substring("reference ".length()).split(" → this slide ");
        assertEquals(2, sides.length, m.detail().valuesLine());
        assertNotEquals(sides[0], sides[1], "odd is shifted, so its applied cut moved");
    }

    @Test
    void aSlideLackingTheChannelIsNotMeasuredEvenThoughTheReviewScoredACopy() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampledWithout(tree, "s2", "CD8");
        CohortGridModel m = derive(s, tree, null, false);
        assertEquals(List.of(NOT_MEASURED, NOT_MEASURED), marks(row(m, "s2")));
    }

    @Test
    void aReviewScoredForAnotherReferenceIsNotTrusted() {
        for (String liveRef : new String[]{null, "s1"}) {
            GateTree tree = CohortFixtures.twoCd8Roots();
            CohortSession s = CohortFixtures.sampled(tree);          // scored with reference "ref"
            assertFalse(s.review().items().isEmpty(), "fixture check");
            tree.setReferenceSlideId(liveRef);                       // the rescore has not landed
            ReviewItem.Key key = s.review().items().get(0).key();
            CohortGridModel m = derive(s, tree, key, false);
            assertTrue(m.rows().stream().allMatch(r -> !marks(r).contains(LOOK) && r.lookCount() == 0),
                    "no stale LOOK for reference " + liveRef);
            if (liveRef == null) {
                assertTrue(m.rows().stream().allMatch(r -> marks(r).stream().allMatch(k -> k == NOT_CORRECTED)));
            }
            assertNotNull(m.detail());
            assertTrue(m.detail().reasons().isEmpty());
        }
    }

    @Test
    void aNotMeasuredDetailCarriesTheScorersMessage() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampledWithout(tree, "s2", "CD8");
        ReviewItem.Key key = new ReviewItem.Key("s2", 0, GateWalk.enabled(tree).get(0).gatePath());
        CohortGridModel m = derive(s, tree, key, false);
        assertEquals(NOT_MEASURED, m.detail().mark());
        assertEquals(1, m.detail().reasons().size());
        assertTrue(m.detail().reasons().get(0).contains("not measured"));
    }

    /** Final review item 5: the model carries the selected cell, so the grid can highlight it. */
    @Test
    void theSelectedCellIsCarriedOnItsRow() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        String path1 = GateWalk.enabled(tree).get(1).gatePath();
        ReviewItem.Key key = new ReviewItem.Key("s2", 1, path1);
        CohortGridModel m = derive(s, tree, key, false);
        assertEquals(1, row(m, "s2").selectedColumn());
        for (String other : List.of("ref", "s1", "odd")) assertEquals(-1, row(m, other).selectedColumn(), other);

        // N / P moved the selection: the next derive moves the highlight with it, over a fresh tree copy.
        ReviewItem.Key next = new ReviewItem.Key("odd", 0, GateWalk.enabled(tree).get(0).gatePath());
        CohortGridModel moved = derive(s, tree.deepCopy(), next, false);
        assertEquals(-1, row(moved, "s2").selectedColumn());
        assertEquals(0, row(moved, "odd").selectedColumn());
        assertEquals(next, moved.detail().key());

        assertTrue(derive(s, tree, null, false).rows().stream().allMatch(r -> r.selectedColumn() == -1));
    }

    /** Final review item 7: ☆ is clickable on a sampling row while there is no reference, not for a rebase. */
    @Test
    void aSamplingRowCanBeConfirmedButNotRebasedOnto() {
        assertTrue(CohortGridModel.canBeReference(CohortGridModel.RowStatus.SAMPLING, false, null));
        assertFalse(CohortGridModel.canBeReference(CohortGridModel.RowStatus.SAMPLING, false, "ref"));
        assertTrue(CohortGridModel.canBeReference(CohortGridModel.RowStatus.READY, false, "ref"));
        assertFalse(CohortGridModel.canBeReference(CohortGridModel.RowStatus.READY, true, "ref"));
        assertFalse(CohortGridModel.canBeReference(CohortGridModel.RowStatus.EXCLUDED, false, null));
        assertFalse(CohortGridModel.canBeReference(CohortGridModel.RowStatus.FAILED, false, null));

        GateTree tree = CohortFixtures.twoCd8Roots();
        tree.setReferenceSlideId(null);
        CohortSession s = new CohortSession();
        s.setProjectSlides(List.of(new CohortSession.SlideRef("a", "a.tif"), new CohortSession.SlideRef("b", "b.tif")));
        s.setLiveTree(tree);
        s.samplingStarted();
        CohortGridModel m = derive(s, tree, null, false);
        assertEquals(CohortGridModel.RowStatus.SAMPLING, row(m, "a").status());
        assertTrue(row(m, "a").canBeReference(), "spec §8: ☆ still clickable while sampling");
    }

    /** Final review item 8: the card names the reference and the guarded look count. */
    @Test
    void theCardNamesTheReferenceAndWhatToLookAt() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        int n = CohortGridModel.toLookAt(s, tree);
        assertTrue(n > 0, "fixture check");
        assertEquals("Cohort · 4 slides · ★ ref.tif · " + n + " to look at", CohortGridModel.cardLine(s, tree, true));

        s.setExcluded(Set.of("odd"));
        assertTrue(CohortGridModel.cardLine(s, tree, true).startsWith("Cohort · 3 slides · ★ ref.tif · "));

        // A review scored for another reference counts nothing, as the grid shows nothing.
        GateTree moved = tree.deepCopy();
        moved.setReferenceSlideId("s1");
        assertEquals(0, CohortGridModel.toLookAt(s, moved));

        s.samplingStarted();
        assertEquals(s.statusLine(true), CohortGridModel.cardLine(s, tree, true), "while sampling: the status line");
    }

    @Test
    void withNoReferenceTheCardPromptsForTheSuggestion() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        tree.setReferenceSlideId(null);
        CohortSession s = CohortFixtures.sampled(tree);
        assertEquals("Pick a reference slide — suggested: " + s.slideName(s.suggestedReferenceId()),
                CohortGridModel.cardLine(s, tree, true));
    }

    /** Final review item 9: the footer carries the "channels missing on some slides" notes. */
    @Test
    void theFooterCarriesTheMissingChannelNotes() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortGridModel m = derive(CohortFixtures.sampledWithout(tree, "s2", "CD8"), tree, null, false);
        assertEquals(1, m.missingChannels().size(), "two roots on one channel say it once: " + m.missingChannels());
        assertTrue(m.missingChannels().get(0).startsWith("s2.tif — CD8 is not measured"));
        assertTrue(derive(CohortFixtures.sampled(tree), tree, null, false).missingChannels().isEmpty());
    }

    /**
     * Final review M2: entry ids restart in every project, so a foreign tree's reference id names
     * an unrelated slide here. No row is starred, sorted first or described as the reference.
     */
    @Test
    void aForeignTreeMarksNoRowAsTheReference() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        tree.setSlideNames(Map.of("ref", "another-project-ref.tif", "s1", "another-project-s1.tif"));
        CohortSession s = CohortFixtures.sampled(tree);
        tree.setReferenceSlideId("s2");
        s.setLiveTree(tree);
        assertTrue(s.state().correctionDisabled(), "fixture check: foreign");
        String path = GateWalk.enabled(tree).get(0).gatePath();
        CohortGridModel m = derive(s, tree, new ReviewItem.Key("s2", 0, path), false);
        assertTrue(m.rows().stream().noneMatch(CohortGridModel.Row::reference), "no row is the reference");
        assertEquals(List.of("ref", "s1", "s2", "odd"), m.rows().stream().map(CohortGridModel.Row::slideId).toList(),
                "project order: nothing sorted first");
        for (CohortGridModel.Row r : m.rows()) {
            assertTrue(marks(r).stream().noneMatch(k -> k == REFERENCE), r.slideId() + ": " + marks(r));
        }
        assertNotEquals(CohortGridModel.REFERENCE_CORRECTION, m.detail().correctionLine());
    }

    /**
     * Final review I2: a pick below 1 on ln is ignored; the cell needs a look and both the
     * tooltip and the detail's correction line say why, rather than a silent "raw".
     */
    @Test
    void anUnusablePickIsALookWithItsReasonInTheTooltipAndDetail() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        String key = columnKey(tree, 0);
        s.setPeaks(Map.of("s1", Map.of(key, 0.5)));
        rescore(s, tree);
        String reason = "Your picked peak is below 1, outside the ln scale \u2014 not corrected; pick it again or use automatic";
        String path = GateWalk.enabled(tree).get(0).gatePath();
        CohortGridModel m = derive(s, tree, new ReviewItem.Key("s1", 0, path), false);
        CohortGridModel.Cell cell = row(m, "s1").cells().get(0);
        assertEquals(LOOK, cell.mark(), cell.toString());
        assertTrue(cell.tooltip().contains(reason), cell.tooltip());
        CohortGridModel.Detail d = m.detail();
        assertEquals("not corrected \u2014 " + reason, d.correctionLine());
        assertTrue(d.reasons().contains(reason), d.reasons().toString());
        assertTrue(d.hasPickedPeak(), "the stored pick can be cleared");
    }

    /**
     * Final review T9 / I2: a reference pick stored under ln1p below 1 reads NaN on ln; the detail
     * still reports it, from the stored raw pick, so "Use automatic (reference)" can clear it.
     */
    @Test
    void aReferencePickThatReadsNaNOnThisScaleIsStillReported() {
        GateTree tree = CohortFixtures.twoCd8Roots();
        CohortSession s = CohortFixtures.sampled(tree);
        String key = columnKey(tree, 0);
        s.setPeaks(Map.of("ref", Map.of(key, 0.5)));
        rescore(s, tree);
        String path = GateWalk.enabled(tree).get(0).gatePath();
        CohortGridModel.Detail d = derive(s, tree, new ReviewItem.Key("s1", 0, path), false).detail();
        assertTrue(Double.isNaN(d.histogram().pickedReferencePeak()), "fixture: NaN on ln");
        assertTrue(d.hasPickedReferencePeak());
        assertFalse(d.hasPickedPeak());
        CohortGridModel.Detail none = derive(CohortFixtures.sampled(tree), tree, new ReviewItem.Key("s1", 0, path), false)
                .detail();
        assertFalse(none.hasPickedReferencePeak());
    }
}
