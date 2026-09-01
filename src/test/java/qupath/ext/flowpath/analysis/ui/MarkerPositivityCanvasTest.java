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
