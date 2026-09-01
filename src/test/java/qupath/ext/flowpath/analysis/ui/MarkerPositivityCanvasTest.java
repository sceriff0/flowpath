package qupath.ext.flowpath.analysis.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.AnalysisFixtures;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarkerPositivityCanvas}'s reduction: positive / negative / ungated per marker,
 * where ungated is its own segment rather than folded into negative. No pixel assertions --
 * see {@code ScatterPlotCanvasCoordinateTest}.
 */
class MarkerPositivityCanvasTest {

    @Test
    void bothGatedMarkersAreOffered() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        assertEquals(Set.of("CD45", "CD3"), Set.copyOf(canvas.markers()));
    }

    @Test
    void aMarkerGatedAtTheRootHasNoUngatedCells() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        assertEquals(10, canvas.positiveCount("CD45"));
        assertEquals(10, canvas.negativeCount("CD45"));
        assertEquals(0, canvas.ungatedCount("CD45"), "every cell was tested against the CD45 gate");
    }

    @Test
    void aMarkerGatedOnlyUnderOneBranchLeavesTheRestUngatedRatherThanNegative() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        // CD3 is only gated under CD45+ (10 cells): 5 positive, 5 negative. The 10 CD45-
        // cells never reached the CD3 gate at all -- they must show up as ungated, not as
        // an extra 10 added to "negative".
        assertEquals(5, canvas.positiveCount("CD3"));
        assertEquals(5, canvas.negativeCount("CD3"));
        assertEquals(10, canvas.ungatedCount("CD3"),
                "the CD45- cells were never evaluated against CD3 -- unmeasured is not negative");
    }

    @Test
    void everyMarkersThreeSegmentsAccountForTheWholeScope() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());

        for (String marker : canvas.markers()) {
            int sum = canvas.positiveCount(marker) + canvas.negativeCount(marker) + canvas.ungatedCount(marker);
            assertEquals(canvas.scopeTotal(), sum, marker + ": positive + negative + ungated must equal everyone");
        }
        assertTrue(canvas.scopeTotal() > 0);
    }

    /**
     * Two sibling {@code CD3} gates hanging off {@code CD45+} (constructible: {@code
     * Branch.setChildren} accepts any list, with no dedup check) group into one malformed
     * node with 4 rows instead of the expected 2. Those 10 {@code CD45+} cells really were
     * gated -- ambiguously, by two independent thresholds -- so they must be excluded from
     * both "measured" and "ungated" rather than silently reported as never gated at all.
     * {@code CD3} also has one properly formed gate under {@code CD45-}, so the marker must
     * not vanish from the chart either.
     */
    @Test
    void aMalformedSiblingGateGroupIsExcludedRatherThanReportedAsUngated() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(MarkerPositivityCanvas.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
            canvas.setRows(malformedSiblingGateRows());

            assertTrue(canvas.markers().contains("CD3"), "a marker with any valid gate must not vanish");
            // The one valid CD3 gate (under CD45-) contributes 5 positive, 5 negative.
            assertEquals(5, canvas.positiveCount("CD3"));
            assertEquals(5, canvas.negativeCount("CD3"));
            // The malformed group's 10 CD45+ cells were gated (ambiguously) -- they must
            // not inflate "ungated" to 10. With them correctly excluded from the
            // denominator as well as from measured, nothing is left over to call ungated.
            assertEquals(0, canvas.ungatedCount("CD3"),
                    "cells caught by a malformed gate group were gated, not skipped -- must not appear as ungated");

            assertTrue(appender.list.stream().anyMatch(e ->
                            e.getLevel() == Level.WARN
                                    && e.getFormattedMessage().contains("CD3")
                                    && e.getFormattedMessage().contains("CD45+")),
                    "a malformed group must be logged, naming the channel and the parent branch");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }

    /**
     * Two independent root gates on the identical channel — "compare two thresholds side by
     * side", an ordinary FlowJo-style workflow — is <b>not</b> the malformed sibling gate
     * group above, and must not be reported as one.
     * <p>
     * With {@code MarkerKey} keyed on {@code (parentPath, channel)} alone, both roots' four
     * rows collected under a single key; the "expected exactly 2" branch then logged a WARN
     * and dropped every one of those cells, so the workflow rendered an empty bar plus a
     * spurious warning. {@code rootIndex} in the key makes each root its own node group
     * again, and each root gets its own bar rather than being summed into one that would
     * claim more positives and negatives than the slide has cells.
     */
    @Test
    void twoRootsOnOneChannelAreTwoBarsRatherThanAMalformedGroup() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(MarkerPositivityCanvas.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
            canvas.setRows(AnalysisFixtures.twoRootsSameChannelRows());

            assertEquals(List.of("CD45 (root 1)", "CD45 (root 2)"), canvas.markers(),
                    "one bar per root gate, labelled so the two can be told apart");

            // Root 0 cuts at 10.5 (10 of 20 positive); root 1 cuts at 15.5 (5 positive).
            assertEquals(10, canvas.positiveCount("CD45 (root 1)"));
            assertEquals(10, canvas.negativeCount("CD45 (root 1)"));
            assertEquals(5, canvas.positiveCount("CD45 (root 2)"));
            assertEquals(15, canvas.negativeCount("CD45 (root 2)"));

            assertEquals(20, canvas.scopeTotal());
            assertEquals(0, canvas.ungatedCount("CD45 (root 1)"),
                    "every cell was tested against root 0's gate");
            assertEquals(0, canvas.ungatedCount("CD45 (root 2)"),
                    "every cell was tested against root 1's gate");
            assertEquals(0, canvas.excludedCount("CD45 (root 1)"));
            assertEquals(0, canvas.excludedCount("CD45 (root 2)"),
                    "two legitimate roots are not a malformed gate group");

            assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                    "no warning: nothing here is malformed");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }

    /**
     * A marker gated at the root <em>and again</em> beneath its own positive branch must not
     * have the two measurements added together.
     * <p>
     * This canvas pools every group of rows for one marker within a root, on the stated
     * grounds that "those branches partition the cells". That holds for <b>siblings</b> —
     * CD3 under {@code CD45+} and CD3 under {@code CD45-} see disjoint cells — but not for
     * <b>ancestor and descendant</b>: the nested gate re-measures a subset of the cells the
     * root gate already judged. Pooling both counted those cells twice, so
     * {@code positive + negative} exceeded the population. Nothing caught it, because
     * {@code ungatedCount} clamps at 0 with {@code Math.max} and the bar segments clamp at
     * full height in {@code PlotCanvas.valueToY} — the chart looked like a cleanly measured
     * marker while its numbers summed past the slide's own cell count.
     * <p>
     * Unlike the sibling case there is no ambiguity to refuse here: the shallower gate saw
     * every cell the deeper one saw, so it alone is the answer.
     */
    @Test
    void aMarkerGatedAgainBelowItselfIsNotCountedTwice() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(nestedSameMarkerRows());

        assertEquals(10, canvas.positiveCount("CD45"), "the root gate's own positives");
        assertEquals(10, canvas.negativeCount("CD45"), "the root gate's own negatives");

        // The invariant the old pooling broke: a marker's three segments describe the
        // population once, never more than once.
        int measured = canvas.positiveCount("CD45") + canvas.negativeCount("CD45");
        assertTrue(measured <= 20,
                "positive + negative must not exceed the 20 cells on the slide, but was " + measured);
        assertEquals(20, measured + canvas.ungatedCount("CD45"),
                "the three segments account for the population exactly once");
    }

    /**
     * {@code CD45} gated at the root, and gated a second time under its own {@code CD45+}
     * branch. Both gates are well-formed; it is their nesting that makes pooling wrong.
     * The nested gate's threshold (15.5) splits the root's 10 positives 5/5, so a pooled
     * reduction reports 15 positive and 15 negative against a 20-cell slide.
     */
    private static List<PopulationStats.Row> nestedSameMarkerRows() {
        int n = 20;
        double[] cd45 = new double[n];
        for (int i = 0; i < n; i++) cd45[i] = i + 1;
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {cd45}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode nested = new GateNode("CD45", 15.5);
        nested.setStatistic(Statistic.MEAN);
        nested.setThresholdIsZScore(false);

        GateNode root = new GateNode("CD45", 10.5);
        root.setStatistic(Statistic.MEAN);
        root.setThresholdIsZScore(false);
        root.setPositiveChildren(List.of(nested));

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(root);

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, null, 0).getTally();
        return PopulationStats.of(tree, tally, List.of(), null, null).rows();
    }

    /** As {@link AnalysisFixtures#twoLevelRows()}, but {@code CD45+} carries two sibling CD3 gates. */
    private static List<PopulationStats.Row> malformedSiblingGateRows() {
        int n = 20;
        double[] cd45 = new double[n];
        double[] cd3 = new double[n];
        for (int i = 0; i < n; i++) {
            cd45[i] = i + 1;
            cd3[i] = (i % 10) + 1;
        }
        CellIndex index = Cells.columns(List.of("CD45", "CD3"), new double[][] {cd45, cd3}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(n));

        GateNode cd3UnderNegative = new GateNode("CD3", 5.5);
        cd3UnderNegative.setStatistic(Statistic.MEAN);
        cd3UnderNegative.setThresholdIsZScore(false);

        GateNode cd3UnderPositiveA = new GateNode("CD3", 5.5);
        cd3UnderPositiveA.setStatistic(Statistic.MEAN);
        cd3UnderPositiveA.setThresholdIsZScore(false);

        GateNode cd3UnderPositiveB = new GateNode("CD3", 7.5);
        cd3UnderPositiveB.setStatistic(Statistic.MEAN);
        cd3UnderPositiveB.setThresholdIsZScore(false);

        GateNode cd45Gate = new GateNode("CD45", 10.5);
        cd45Gate.setStatistic(Statistic.MEAN);
        cd45Gate.setThresholdIsZScore(false);
        cd45Gate.setPositiveChildren(List.of(cd3UnderPositiveA, cd3UnderPositiveB));
        cd45Gate.setNegativeChildren(List.of(cd3UnderNegative));

        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(cd45Gate);

        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, null, 0).getTally();
        return PopulationStats.of(tree, tally, List.of(), null, null).rows();
    }
}
