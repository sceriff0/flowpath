package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.QualityFilter;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.common.ColorTools;
import qupath.lib.objects.classes.PathClass;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code PathClass} cache {@link PhenotypeClassWriter} writes through is JVM-wide and never
 * reset between tests, so every test here that cares about a class's exact colour value uses its
 * own uniquely-prefixed channel name ({@code PCW9x_...}) rather than the commonly-reused "CD3".
 */
class PhenotypeClassWriterTest {

    @Test
    void everyCellGetsItsPhenotypeClassAndASecondApplyChangesNothing() {
        CellIndex index = Cells.of(6).marker("CD3", i -> i).area(i -> i == 0 ? 1.0 : 100.0).build();
        GateTree tree = new GateTree();
        GateNode a = new GateNode("CD3", 3.0);
        a.setStatistic(Statistic.MEAN);
        GateNode b = new GateNode("CD3", 5.0);
        b.setStatistic(Statistic.MEAN);
        tree.addRoot(a);
        tree.addRoot(b);
        tree.getQualityFilter().setMin(QualityFilter.AREA, 50);
        MarkerStats stats = GatingEngine.recomputeStats(index, tree.getQualityFilter(), null);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        assertTrue(PhenotypeClassWriter.apply(result, index, -1));
        assertSame(PathClass.fromString("Excluded"), index.getObject(0).getPathClass());
        for (int i = 1; i < 6; i++) {
            assertSame(PathClass.fromString(result.getPhenotypes()[i]), index.getObject(i).getPathClass());
        }
        assertFalse(PhenotypeClassWriter.apply(result, index, -1), "nothing left to change");
    }

    /** Two enabled roots on the same channel, coloured differently, so root -1 (the last
     *  contributing root) and root 0 genuinely disagree on colour for the same phenotype name. */
    private static GatingEngine.AssignmentResult twoRootsSameChannel(String channel, CellIndex index) {
        GateTree tree = new GateTree();
        GateNode rootA = new GateNode(channel, 1.0);
        rootA.setStatistic(Statistic.MEAN);
        rootA.setPositiveColor(0xFF0000); // red
        GateNode rootB = new GateNode(channel, 1.0);
        rootB.setStatistic(Statistic.MEAN);
        rootB.setPositiveColor(0x0000FF); // blue
        tree.addRoot(rootA);
        tree.addRoot(rootB);
        MarkerStats stats = GatingEngine.recomputeStats(index, tree.getQualityFilter(), null);
        return GatingEngine.assignAll(tree, index, stats);
    }

    @Test
    void switchingColorRootRoundTripsAndAlwaysReassignsWithoutASecondWriter() {
        String channel = "PCW9A_CD3";
        CellIndex index = Cells.of(4).marker(channel, i -> i).area(100.0).build();
        GatingEngine.AssignmentResult result = twoRootsSameChannel(channel, index);

        assertTrue(PhenotypeClassWriter.apply(result, index, -1), "first application always reassigns");
        assertTrue(PhenotypeClassWriter.apply(result, index, 0), "switching to root 0's colours is a change");
        assertTrue(PhenotypeClassWriter.apply(result, index, -1), "switching back to -1 is a change again");
    }

    @Test
    void aClassWhoseColourDidNotChangeIsNotReassigned() {
        String channel = "PCW9B_CD3";
        CellIndex index = Cells.of(4).marker(channel, i -> i).area(100.0).build();
        GatingEngine.AssignmentResult result = twoRootsSameChannel(channel, index);

        assertTrue(PhenotypeClassWriter.apply(result, index, -1));
        assertFalse(PhenotypeClassWriter.apply(result, index, -1), "same root, same result: nothing to reassign");
        assertFalse(PhenotypeClassWriter.apply(result, index, -1), "repeatable");
    }

    @Test
    void excludedCellsKeepTheExcludedClassAndAColourResetCountsAsAChange() {
        String channel = "PCW9C_CD3";
        CellIndex index = Cells.of(4).marker(channel, i -> i).area(i -> i == 0 ? 1.0 : 100.0).build();
        GateTree tree = new GateTree();
        GateNode root = new GateNode(channel, 1.0);
        root.setStatistic(Statistic.MEAN);
        tree.addRoot(root);
        tree.getQualityFilter().setMin(QualityFilter.AREA, 50);
        MarkerStats stats = GatingEngine.recomputeStats(index, tree.getQualityFilter(), null);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        assertTrue(PhenotypeClassWriter.apply(result, index, -1));
        assertSame(PathClass.fromString("Excluded"), index.getObject(0).getPathClass());
        assertFalse(PhenotypeClassWriter.apply(result, index, -1), "nothing left to change");

        // Simulate something outside PhenotypeClassWriter tampering with the shared "Excluded"
        // class's colour.
        PathClass.fromString("Excluded").setColor(0x123456);
        assertTrue(PhenotypeClassWriter.apply(result, index, -1), "resetting Excluded's colour is a change");
        assertEquals(Integer.valueOf(ColorTools.packRGB(20, 20, 20)), PathClass.fromString("Excluded").getColor());
    }

    /**
     * Pins the diagnosed bug and (a)'s fix for it: {@code apply}'s own shared-state-based signal
     * can be fooled by a second writer (a background batch run gating a different slide with the
     * same phenotype names), but a caller comparing a fresh {@link PhenotypeClassWriter#colorPlan}
     * against its own last-applied memory is not.
     */
    @Test
    void aCallersOwnMemoryCatchesAColourChangeEvenAfterASecondWriterAgrees() {
        String channel = "PCW9D_CD3";
        CellIndex index = Cells.of(4).marker(channel, i -> i).area(100.0).build();
        GatingEngine.AssignmentResult result = twoRootsSameChannel(channel, index);

        Map<String, Integer> lastApplied = Map.of();

        Map<String, Integer> planMinus1 = PhenotypeClassWriter.colorPlan(result, -1);
        boolean c1 = PhenotypeClassWriter.apply(planMinus1, result, index);
        assertTrue(c1 || !planMinus1.equals(lastApplied), "first application is always a change");
        lastApplied = planMinus1;

        Map<String, Integer> plan0 = PhenotypeClassWriter.colorPlan(result, 0);
        boolean c2 = PhenotypeClassWriter.apply(plan0, result, index);
        assertTrue(c2 || !plan0.equals(lastApplied), "switching to root 0's colours is a change");
        lastApplied = plan0;

        // A second writer -- e.g. a background batch run gating a different slide, sharing the
        // same phenotype names and therefore the same cached PathClass objects -- sets the
        // shared colours back to plan -1's values, without this caller's own memory ever seeing it.
        CellIndex otherSlide = Cells.of(4).marker(channel, i -> i).area(100.0).build();
        PhenotypeClassWriter.apply(planMinus1, result, otherSlide);

        Map<String, Integer> planMinus1Again = PhenotypeClassWriter.colorPlan(result, -1);
        assertEquals(planMinus1, planMinus1Again, "root -1's colours have not themselves changed");

        boolean c3 = PhenotypeClassWriter.apply(planMinus1Again, result, index);
        assertFalse(c3, "the second writer already left the shared cache holding this exact plan, "
                + "so apply()'s own shared-state signal is fooled");
        assertTrue(c3 || !planMinus1Again.equals(lastApplied),
                "(a): the caller's own memory (still plan 0) catches the change apply() alone would miss");
    }
}
